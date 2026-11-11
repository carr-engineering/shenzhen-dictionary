package com.szdict.recognition

import android.content.res.AssetManager
import com.szdict.recognition.ink.InkNet
import com.szdict.recognition.ocr.DetectorOutput
import com.szdict.recognition.ocr.OcrNets
import com.szdict.recognition.ocr.SequenceModel
import com.szdict.recognition.ocr.SequenceOutput
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

internal const val MODEL_DIR = "models"

/** Maps an asset straight from the APK when it is stored uncompressed, otherwise reads it into native memory. */
internal fun AssetManager.loadModel(path: String): ByteBuffer {
    try {
        openFd(path).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    } catch (_: java.io.IOException) {
        // Compressed in this APK: fall through to a copy.
    }
    val bytes = open(path).use { it.readBytes() }
    return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
}

internal fun AssetManager.loadLabels(path: String): List<String> =
    open(path).use { it.readBytes() }.toString(Charsets.UTF_8).split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }

private fun interpreter(model: ByteBuffer, threads: Int): Interpreter =
    Interpreter(model, Interpreter.Options().setNumThreads(threads).setUseXNNPACK(false))

private fun direct(size: Int): ByteBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())

/** The seven OCR graphs on LiteRT's CPU kernels. Not thread-safe; the recogniser serialises calls. */
internal class LiteRtOcrNets(assets: AssetManager, threads: Int) : OcrNets {
    private val detector = interpreter(assets.loadModel("$MODEL_DIR/ocr/detector.tflite"), threads)
    private var detectorShape = intArrayOf(0, 0)
    private val lines = SequenceModel.entries.associateWith { LineModel(assets, it) }

    override fun detect(gray: ByteArray, width: Int, height: Int): DetectorOutput {
        if (detectorShape[0] != height || detectorShape[1] != width) {
            detector.resizeInput(0, intArrayOf(1, height, width, 1))
            detector.allocateTensors()
            detectorShape = intArrayOf(height, width)
        }
        val tensor = detector.getOutputTensor(0)
        val shape = tensor.shape()
        val out = direct(tensor.numBytes())
        detector.run(ByteBuffer.wrap(gray, 0, width * height), out)
        val data = ByteArray(out.capacity())
        out.rewind()
        out.get(data)
        val q = tensor.quantizationParams()
        return DetectorOutput(data, shape[1], shape[2], q.scale, q.zeroPoint)
    }

    override fun sequence(model: SequenceModel, columns: ByteArray, width: Int): SequenceOutput = lines.getValue(model).run(columns, width)

    override fun close() {
        detector.close()
        lines.values.forEach { it.close() }
    }

    /** A convolutional encoder over the whole line plus a recurrent decoder stepped once per 4-px column. */
    private class LineModel(assets: AssetManager, model: SequenceModel) {
        private val encoder = interpreter(assets.loadModel("$MODEL_DIR/ocr/${model.assetPrefix}-encoder.tflite"), 1)
        private val decoder = interpreter(assets.loadModel("$MODEL_DIR/ocr/${model.assetPrefix}-decoder.tflite"), 1)
        private var width = 0

        // Decoder contract: inputs [features, c0, h0, c1, h1]; outputs [probabilities, c0, h0, c1, h1, logits?].
        // The recognisers are repackaged to expose their logits as output 5; the script classifier's
        // probabilities (output 0) are used as they are.
        private val resultIndex = if (model == SequenceModel.SCRIPT) 0 else 5
        private val resultTensor = decoder.getOutputTensor(resultIndex)
        private val classes = resultTensor.shape().last()
        private val featureSize = decoder.getInputTensor(0).numBytes()
        private val stateIn = Array(4) { direct(decoder.getInputTensor(it + 1).numBytes()) }
        private val stateOut = Array(4) { direct(decoder.getOutputTensor(it + 1).numBytes()) }
        private val stateZero = IntArray(4) { decoder.getInputTensor(it + 1).quantizationParams().zeroPoint }
        private val stateIsByte = BooleanArray(4) { decoder.getInputTensor(it + 1).numBytes() == decoder.getInputTensor(it + 1).numElements() }
        private val frame = direct(featureSize)
        private val result = direct(classes)

        fun run(columns: ByteArray, lineWidth: Int): SequenceOutput {
            if (width != lineWidth) {
                encoder.resizeInput(0, intArrayOf(1, lineWidth, 24, 1))
                encoder.allocateTensors()
                width = lineWidth
            }
            val features = direct(encoder.getOutputTensor(0).numBytes())
            encoder.run(ByteBuffer.wrap(columns, 0, lineWidth * 24), features)
            val frames = features.capacity() / featureSize
            // Recurrent state starts at real zero for every line.
            for (i in 0 until 4) {
                val b = stateIn[i]
                b.clear()
                if (stateIsByte[i]) while (b.hasRemaining()) b.put(stateZero[i].toByte()) else while (b.hasRemaining()) b.putShort(stateZero[i].toShort())
            }
            val out = ByteArray(frames * classes)
            val inputs = arrayOfNulls<Any>(5)
            val outputs = HashMap<Int, Any>()
            var a = stateIn
            var b = stateOut
            for (t in 0 until frames) {
                frame.clear()
                features.limit((t + 1) * featureSize).position(t * featureSize)
                frame.put(features)
                frame.rewind()
                inputs[0] = frame
                for (i in 0 until 4) {
                    a[i].rewind()
                    b[i].clear()
                    inputs[i + 1] = a[i]
                    outputs[i + 1] = b[i]
                }
                result.clear()
                outputs[resultIndex] = result
                decoder.runForMultipleInputsOutputs(inputs, outputs)
                result.rewind()
                result.get(out, t * classes, classes)
                val swap = a; a = b; b = swap
            }
            val q = resultTensor.quantizationParams()
            return SequenceOutput(out, frames, classes, q.scale, q.zeroPoint)
        }

        fun close() {
            encoder.close()
            decoder.close()
        }
    }
}

/** The Digital Ink network: four bidirectional LSTM layers, float `[frames, 1, 10]` in, `[frames, 1, classes]` out. */
internal class LiteRtInkNet(assets: AssetManager, threads: Int) : InkNet {
    private val model = interpreter(assets.loadModel("$MODEL_DIR/ink/chinese_lstm_4x192.tflite"), threads)
    private var frames = 0
    override val classes: Int = model.getOutputTensor(0).shape().last()

    override fun infer(features: FloatArray, frames: Int): FloatArray {
        if (this.frames != frames) {
            model.resizeInput(0, intArrayOf(frames, 1, 10))
            model.allocateTensors()
            this.frames = frames
        }
        // One drawing must not influence the next.
        model.resetVariableTensors()
        val input = direct(frames * 10 * 4)
        input.asFloatBuffer().put(features, 0, frames * 10)
        val output = direct(frames * classes * 4)
        model.run(input, output)
        val logits = FloatArray(frames * classes)
        output.rewind()
        output.asFloatBuffer().get(logits)
        return logits
    }

    override fun close() = model.close()
}
