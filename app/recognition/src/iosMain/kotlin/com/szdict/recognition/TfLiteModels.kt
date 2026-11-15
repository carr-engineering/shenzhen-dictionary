@file:OptIn(ExperimentalForeignApi::class)

package com.szdict.recognition

import com.szdict.recognition.ink.InkNet
import cnames.structs.TfLiteInterpreter
import com.szdict.recognition.tflite.TfLiteInterpreterAllocateTensors
import com.szdict.recognition.tflite.TfLiteInterpreterCreate
import com.szdict.recognition.tflite.TfLiteInterpreterDelete
import com.szdict.recognition.tflite.TfLiteInterpreterGetInputTensor
import com.szdict.recognition.tflite.TfLiteInterpreterGetOutputTensor
import com.szdict.recognition.tflite.TfLiteInterpreterInvoke
import com.szdict.recognition.tflite.TfLiteInterpreterOptionsCreate
import com.szdict.recognition.tflite.TfLiteInterpreterOptionsDelete
import com.szdict.recognition.tflite.TfLiteInterpreterOptionsSetNumThreads
import com.szdict.recognition.tflite.TfLiteInterpreterResetVariableTensors
import com.szdict.recognition.tflite.TfLiteInterpreterResizeInputTensor
import cnames.structs.TfLiteModel
import com.szdict.recognition.tflite.TfLiteModelCreateFromFile
import com.szdict.recognition.tflite.TfLiteModelDelete
import com.szdict.recognition.tflite.TfLiteStatus
import com.szdict.recognition.tflite.TfLiteTensor
import com.szdict.recognition.tflite.TfLiteTensorByteSize
import com.szdict.recognition.tflite.TfLiteTensorData
import com.szdict.recognition.tflite.TfLiteTensorDim
import com.szdict.recognition.tflite.TfLiteTensorNumDims
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.memcpy
import platform.posix.rewind

internal fun readFile(path: String): ByteArray {
    val file = fopen(path, "rb") ?: throw IllegalStateException("Cannot open $path")
    try {
        fseek(file, 0, SEEK_END)
        val size = ftell(file).toInt()
        rewind(file)
        val bytes = ByteArray(size)
        if (size > 0) {
            val read = bytes.usePinned { fread(it.addressOf(0), 1u, size.toULong(), file) }
            check(read.toInt() == size) { "Cannot read $path" }
        }
        return bytes
    } finally {
        fclose(file)
    }
}

internal fun readLabels(path: String): List<String> =
    readFile(path).decodeToString().split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }

/**
 * One graph on the TensorFlow Lite C API. The model file is memory-mapped by the runtime. Plain CPU
 * kernels, as on Android: no delegate is created (and the runtime applies none by itself to this
 * graph). The float ink network matches the host interpreter to 1e-5.
 * Not thread-safe; the recogniser serialises calls.
 */
internal class TfLiteGraph(path: String, threads: Int) {
    private var model: CPointer<TfLiteModel>? = TfLiteModelCreateFromFile(path) ?: throw IllegalStateException("Cannot load model $path")
    private var interpreter: CPointer<TfLiteInterpreter>?

    init {
        val options = TfLiteInterpreterOptionsCreate()
        TfLiteInterpreterOptionsSetNumThreads(options, threads)
        interpreter = TfLiteInterpreterCreate(model, options)
        TfLiteInterpreterOptionsDelete(options)
        if (interpreter == null) {
            close()
            throw IllegalStateException("Cannot create an interpreter for $path")
        }
        allocate()
    }

    private fun ok(status: TfLiteStatus, what: String) {
        if (status != TfLiteStatus.kTfLiteOk) throw IllegalStateException("TensorFlow Lite: $what failed ($status)")
    }

    fun allocate() = ok(TfLiteInterpreterAllocateTensors(interpreter), "tensor allocation")

    /** Gives input 0 a new shape and reallocates the graph's tensors. */
    fun resize(vararg shape: Int) {
        shape.usePinned { ok(TfLiteInterpreterResizeInputTensor(interpreter, 0, it.addressOf(0), shape.size), "input resize") }
        allocate()
    }

    fun resetVariables() = ok(TfLiteInterpreterResetVariableTensors(interpreter), "state reset")

    fun invoke() = ok(TfLiteInterpreterInvoke(interpreter), "inference")

    fun input(index: Int): CPointer<TfLiteTensor> = TfLiteInterpreterGetInputTensor(interpreter, index) ?: throw IllegalStateException("No input $index")

    fun output(index: Int): CPointer<TfLiteTensor> = TfLiteInterpreterGetOutputTensor(interpreter, index) ?: throw IllegalStateException("No output $index")

    fun close() {
        interpreter?.let { TfLiteInterpreterDelete(it) }
        interpreter = null
        model?.let { TfLiteModelDelete(it) }
        model = null
    }
}

private val CPointer<TfLiteTensor>.byteSize: Int get() = TfLiteTensorByteSize(this).toInt()

private val CPointer<TfLiteTensor>.lastDim: Int get() = TfLiteTensorDim(this, TfLiteTensorNumDims(this) - 1)

private val CPointer<TfLiteTensor>.bytes: CPointer<ByteVar>
    get() = TfLiteTensorData(this)?.reinterpret() ?: throw IllegalStateException("Tensor is not allocated")

/** The Digital Ink network: four bidirectional LSTM layers, float `[frames, 1, 10]` in, `[frames, 1, classes]` out. */
internal class TfLiteInkNet(modelDir: String, threads: Int) : InkNet {
    private val model = TfLiteGraph("$modelDir/ink/chinese_lstm_4x192.tflite", threads)
    private var frames = 0
    override val classes: Int = model.output(0).lastDim

    override suspend fun infer(features: FloatArray, frames: Int): FloatArray {
        if (this.frames != frames) {
            model.resize(frames, 1, 10)
            this.frames = frames
        }
        // One drawing must not influence the next.
        model.resetVariables()
        val input = model.input(0)
        check(input.byteSize == frames * 10 * 4 && features.size >= frames * 10) { "Input size mismatch" }
        features.usePinned { memcpy(input.bytes, it.addressOf(0), input.byteSize.toULong()) }
        model.invoke()
        val output = model.output(0)
        check(output.byteSize == frames * classes * 4) { "Unexpected output size" }
        val logits = FloatArray(frames * classes)
        logits.usePinned { memcpy(it.addressOf(0), output.bytes, output.byteSize.toULong()) }
        return logits
    }

    override fun close() = model.close()
}
