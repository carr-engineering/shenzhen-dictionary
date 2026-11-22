package com.szdict.recognition

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import com.szdict.recognition.ocr2.FloatSink
import com.szdict.recognition.ocr2.Ocr2Nets
import com.szdict.recognition.ocr2.RecognitionOutput
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The OCR graphs on ONNX Runtime, configured as in the evaluation: all graph optimisations, [threads]
 * intra-op threads, no arena. (The float16-stored recogniser weights are cast to float32 once, when
 * the session is created.) The classifier and the recogniser run on the CPU provider. The detector's
 * convolutions run on the XNNPACK provider: on the arm64 emulator it takes 150 instead of 255 ms on
 * 1,000-pixel images and 500 instead of 560 ms on 2,560-pixel photographs, and reads the same text
 * on all of them; the recogniser does not run on XNNPACK (the session fails on its graph).
 * Not thread-safe; the recogniser serialises calls.
 *
 * @param model returns the bytes of `det.onnx`, `cls.onnx` and `rec.onnx` as a direct buffer.
 */
internal class OrtOcr2Nets(model: (String) -> ByteBuffer, threads: Int) : Ocr2Nets {
    private val environment = OrtEnvironment.getEnvironment()
    private val sessions = ArrayList<OrtSession>()
    private val detector: OrtSession
    private val classifier: OrtSession
    private val recogniser: OrtSession

    init {
        try {
            detector = session(model("det.onnx"), threads, xnnpack = true)
            classifier = session(model("cls.onnx"), threads)
            recogniser = session(model("rec.onnx"), threads)
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    /**
     * @param xnnpack whether convolutions run on the XNNPACK execution provider where this build of
     *   the runtime has it (the Android library does, the desktop one of the host tests does not).
     */
    private fun session(model: ByteBuffer, threads: Int, xnnpack: Boolean = false): OrtSession {
        if (xnnpack) {
            try {
                return create(model, threads, true)
            } catch (_: OrtException) {
                // No such provider here: the CPU provider computes the same.
            }
        }
        return create(model, threads, false)
    }

    private fun create(model: ByteBuffer, threads: Int, xnnpack: Boolean): OrtSession {
        OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(threads)
            options.setInterOpNumThreads(1)
            options.setCPUArenaAllocator(false)
            if (xnnpack) {
                options.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                // Two thread pools share the cores: the runtime's own must not spin while XNNPACK works.
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
            }
            model.rewind()
            return environment.createSession(model, options).also { sessions.add(it) }
        }
    }

    /** Runs [session] on a float tensor of [shape] written by [fill]; returns the first output and its shape. */
    private fun run(session: OrtSession, shape: LongArray, fill: (FloatSink) -> Unit): Pair<FloatArray, LongArray> {
        var size = 1
        for (d in shape) size *= d.toInt()
        // Direct buffers are zero-filled and handed to the runtime without a copy.
        val buffer = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fill(object : FloatSink {
            override fun put(offset: Int, source: FloatArray, count: Int) {
                buffer.position(offset)
                buffer.put(source, 0, count)
            }
        })
        buffer.rewind()
        OnnxTensor.createTensor(environment, buffer, shape).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { result ->
                val output = result[0] as OnnxTensor
                val floats: FloatBuffer = output.floatBuffer
                val data = if (floats.hasArray() && floats.arrayOffset() == 0 && floats.array().size == floats.remaining()) {
                    floats.array()
                } else {
                    FloatArray(floats.remaining()).also { floats.get(it) }
                }
                return data to output.info.shape
            }
        }
    }

    override suspend fun detect(height: Int, width: Int, fill: (FloatSink) -> Unit): FloatArray =
        run(detector, longArrayOf(1, 3, height.toLong(), width.toLong()), fill).first

    override suspend fun classify(batch: Int, fill: (FloatSink) -> Unit): FloatArray =
        run(classifier, longArrayOf(batch.toLong(), 3, 48, 192), fill).first

    override suspend fun recognize(batch: Int, width: Int, fill: (FloatSink) -> Unit): RecognitionOutput {
        val (data, shape) = run(recogniser, longArrayOf(batch.toLong(), 3, 48, width.toLong()), fill)
        return RecognitionOutput(data, shape[1].toInt(), shape[2].toInt())
    }

    override fun close() {
        sessions.forEach { it.close() }
        sessions.clear()
    }
}
