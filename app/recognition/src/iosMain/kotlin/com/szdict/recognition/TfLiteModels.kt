@file:OptIn(ExperimentalForeignApi::class)

package com.szdict.recognition

import com.szdict.recognition.ink.InkNet
import com.szdict.recognition.ocr.DetectorOutput
import com.szdict.recognition.ocr.OcrNets
import com.szdict.recognition.ocr.SequenceModel
import com.szdict.recognition.ocr.SequenceOutput
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
import com.szdict.recognition.tflite.TfLiteTensorQuantizationParams
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.memcpy
import platform.posix.memset
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
 * kernels, as on Android: no delegate is created, which keeps results identical to the reference
 * interpreter. Not thread-safe; the recogniser serialises calls.
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

private val CPointer<TfLiteTensor>.elements: Int
    get() {
        var n = 1
        for (i in 0 until TfLiteTensorNumDims(this)) n *= TfLiteTensorDim(this, i)
        return n
    }

private val CPointer<TfLiteTensor>.lastDim: Int get() = TfLiteTensorDim(this, TfLiteTensorNumDims(this) - 1)

private val CPointer<TfLiteTensor>.bytes: CPointer<ByteVar>
    get() = TfLiteTensorData(this)?.reinterpret() ?: throw IllegalStateException("Tensor is not allocated")

private fun CPointer<TfLiteTensor>.scale(): Float = TfLiteTensorQuantizationParams(this).useContents { scale }

private fun CPointer<TfLiteTensor>.zeroPoint(): Int = TfLiteTensorQuantizationParams(this).useContents { zero_point }

private fun CPointer<TfLiteTensor>.write(source: ByteArray, size: Int) {
    check(byteSize == size && source.size >= size) { "Input size mismatch" }
    source.usePinned { memcpy(bytes, it.addressOf(0), size.toULong()) }
}

private fun CPointer<TfLiteTensor>.read(): ByteArray {
    val out = ByteArray(byteSize)
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, out.size.toULong()) }
    return out
}

/** The seven OCR graphs on TensorFlow Lite's CPU kernels. Not thread-safe; the recogniser serialises calls. */
internal class TfLiteOcrNets(modelDir: String, threads: Int) : OcrNets {
    private val detector = TfLiteGraph("$modelDir/ocr/detector.tflite", threads)
    private var detectorWidth = 0
    private var detectorHeight = 0
    private val lines = HashMap<SequenceModel, LineModel>()

    init {
        try {
            for (model in SequenceModel.entries) lines[model] = LineModel(modelDir, model)
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    override suspend fun detect(gray: ByteArray, width: Int, height: Int): DetectorOutput {
        if (detectorHeight != height || detectorWidth != width) {
            detector.resize(1, height, width, 1)
            detectorHeight = height
            detectorWidth = width
        }
        detector.input(0).write(gray, width * height)
        detector.invoke()
        val tensor = detector.output(0)
        return DetectorOutput(tensor.read(), TfLiteTensorDim(tensor, 1), TfLiteTensorDim(tensor, 2), tensor.scale(), tensor.zeroPoint())
    }

    override suspend fun sequence(model: SequenceModel, columns: ByteArray, width: Int): SequenceOutput = lines.getValue(model).run(columns, width)

    override fun close() {
        detector.close()
        lines.values.forEach { it.close() }
        lines.clear()
    }

    /** A convolutional encoder over the whole line plus a recurrent decoder stepped once per 4-px column. */
    private class LineModel(modelDir: String, model: SequenceModel) {
        private val encoder = TfLiteGraph("$modelDir/ocr/${model.assetPrefix}-encoder.tflite", 1)
        private val decoder = try {
            TfLiteGraph("$modelDir/ocr/${model.assetPrefix}-decoder.tflite", 1)
        } catch (e: Throwable) {
            encoder.close()
            throw e
        }
        private var width = 0

        // Decoder contract: inputs [features, c0, h0, c1, h1]; outputs [probabilities, c0, h0, c1, h1, logits?].
        // The recognisers are repackaged to expose their logits as output 5; the script classifier's
        // probabilities (output 0) are used as they are.
        private val resultIndex = if (model == SequenceModel.SCRIPT) 0 else 5
        private val classes = decoder.output(resultIndex).lastDim
        private val featureSize = decoder.input(0).byteSize

        fun run(columns: ByteArray, lineWidth: Int): SequenceOutput {
            if (width != lineWidth) {
                encoder.resize(1, lineWidth, 24, 1)
                width = lineWidth
            }
            encoder.input(0).write(columns, lineWidth * 24)
            encoder.invoke()
            val featureTensor = encoder.output(0)
            val features = featureTensor.bytes
            val frames = featureTensor.byteSize / featureSize
            // Recurrent state starts at real zero for every line.
            for (i in 1..4) {
                val state = decoder.input(i)
                val zero = state.zeroPoint()
                if (state.byteSize == state.elements) {
                    memset(state.bytes, zero, state.byteSize.toULong())
                } else {
                    val shorts = state.bytes.reinterpret<ShortVar>()
                    for (k in 0 until state.elements) shorts[k] = zero.toShort()
                }
            }
            val frameInput = decoder.input(0)
            val out = ByteArray(frames * classes)
            out.usePinned { pinned ->
                for (t in 0 until frames) {
                    memcpy(frameInput.bytes, features + t * featureSize, featureSize.toULong())
                    decoder.invoke()
                    memcpy(pinned.addressOf(t * classes), decoder.output(resultIndex).bytes, classes.toULong())
                    // This step's state outputs are the next step's state inputs.
                    for (i in 1..4) {
                        val next = decoder.input(i)
                        memcpy(next.bytes, decoder.output(i).bytes, next.byteSize.toULong())
                    }
                }
            }
            val result = decoder.output(resultIndex)
            return SequenceOutput(out, frames, classes, result.scale(), result.zeroPoint())
        }

        fun close() {
            encoder.close()
            decoder.close()
        }
    }
}

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
