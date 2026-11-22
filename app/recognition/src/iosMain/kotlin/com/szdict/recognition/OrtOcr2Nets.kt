@file:OptIn(ExperimentalForeignApi::class)

package com.szdict.recognition

import com.szdict.recognition.ocr2.FloatSink
import com.szdict.recognition.ocr2.Ocr2Nets
import com.szdict.recognition.ocr2.RecognitionOutput
import com.szdict.recognition.ort.SzOrtOutput
import com.szdict.recognition.ort.SzOrtSession
import com.szdict.recognition.ort.sz_ort_close
import com.szdict.recognition.ort.sz_ort_free_output
import com.szdict.recognition.ort.sz_ort_open
import com.szdict.recognition.ort.sz_ort_run
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.free
import platform.posix.memcpy

/**
 * One OCR graph on ONNX Runtime's C API (the static runtime the app links for speech), on the CPU
 * provider configured as in the evaluation and on Android: all graph optimisations, [threads]
 * intra-op threads, no arena.
 */
private class OrtGraph(path: String, threads: Int) {
    private var session: CPointer<SzOrtSession>? = memScoped {
        val out = alloc<CPointerVar<SzOrtSession>>()
        check(sz_ort_open(path, threads, out.ptr), "load $path")
        out.value ?: throw IllegalStateException("Cannot load model $path")
    }

    private fun check(error: CPointer<ByteVar>?, what: String) {
        if (error == null) return
        val message = error.toKString()
        free(error)
        throw IllegalStateException("ONNX Runtime: $what failed: $message")
    }

    /** Runs the graph on a float tensor of [shape] written by [fill]; returns the output and its shape. */
    fun run(shape: LongArray, fill: (FloatSink) -> Unit): Pair<FloatArray, LongArray> {
        var size = 1
        for (d in shape) size *= d.toInt()
        val input = FloatArray(size)
        fill(object : FloatSink {
            override fun put(offset: Int, source: FloatArray, count: Int) {
                source.copyInto(input, offset, 0, count)
            }
        })
        return memScoped {
            val output = alloc<SzOrtOutput>()
            input.usePinned { pinned ->
                shape.usePinned { dims -> check(sz_ort_run(session, pinned.addressOf(0), dims.addressOf(0), shape.size, output.ptr), "inference") }
            }
            try {
                val outShape = LongArray(output.rank) { output.shape[it] }
                var count = 1
                for (d in outShape) count *= d.toInt()
                val data = FloatArray(count)
                if (count > 0) data.usePinned { memcpy(it.addressOf(0), output.data, (count * 4).toULong()) }
                data to outShape
            } finally {
                sz_ort_free_output(output.ptr)
            }
        }
    }

    fun close() {
        session?.let { sz_ort_close(it) }
        session = null
    }
}

/** The OCR graphs of `modelDir/ocr2`. Not thread-safe; the recogniser serialises calls. */
internal class OrtOcr2Nets(modelDir: String, threads: Int) : Ocr2Nets {
    private val graphs = ArrayList<OrtGraph>()
    private val detector: OrtGraph
    private val classifier: OrtGraph
    private val recogniser: OrtGraph

    init {
        try {
            detector = OrtGraph("$modelDir/ocr2/det.onnx", threads).also { graphs.add(it) }
            classifier = OrtGraph("$modelDir/ocr2/cls.onnx", threads).also { graphs.add(it) }
            recogniser = OrtGraph("$modelDir/ocr2/rec.onnx", threads).also { graphs.add(it) }
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    override suspend fun detect(height: Int, width: Int, fill: (FloatSink) -> Unit): FloatArray =
        detector.run(longArrayOf(1, 3, height.toLong(), width.toLong()), fill).first

    override suspend fun classify(batch: Int, fill: (FloatSink) -> Unit): FloatArray =
        classifier.run(longArrayOf(batch.toLong(), 3, 48, 192), fill).first

    override suspend fun recognize(batch: Int, width: Int, fill: (FloatSink) -> Unit): RecognitionOutput {
        val (data, shape) = recogniser.run(longArrayOf(batch.toLong(), 3, 48, width.toLong()), fill)
        return RecognitionOutput(data, shape[1].toInt(), shape[2].toInt())
    }

    override fun close() {
        graphs.forEach { it.close() }
        graphs.clear()
    }
}
