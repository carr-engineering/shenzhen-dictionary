package com.szdict.recognition

import android.content.res.AssetManager
import com.szdict.recognition.ink.InkNet
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

// Plain CPU kernels: XNNPACK brought no measurable gain on this graph; this also keeps results
// identical to the reference interpreter.
private fun interpreter(model: ByteBuffer, threads: Int): Interpreter =
    Interpreter(model, Interpreter.Options().setNumThreads(threads).setUseXNNPACK(false))

private fun direct(size: Int): ByteBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())

/** The Digital Ink network: four bidirectional LSTM layers, float `[frames, 1, 10]` in, `[frames, 1, classes]` out. */
internal class LiteRtInkNet(assets: AssetManager, threads: Int) : InkNet {
    private val model = interpreter(assets.loadModel("$MODEL_DIR/ink/chinese_lstm_4x192.tflite"), threads)
    private var frames = 0
    override val classes: Int = model.getOutputTensor(0).shape().last()

    override suspend fun infer(features: FloatArray, frames: Int): FloatArray {
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
