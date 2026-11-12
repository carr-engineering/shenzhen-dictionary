package com.szdict.recognition

import com.szdict.recognition.ink.InkNet
import com.szdict.recognition.ocr.DetectorOutput
import com.szdict.recognition.ocr.OcrNets
import com.szdict.recognition.ocr.SequenceModel
import com.szdict.recognition.ocr.SequenceOutput
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the real graphs on the host through `tools/host_nets.py` (see that file). Only available when
 * the `recognition.hostPython` system property (env `RECOGNITION_HOST_PYTHON`) names a Python command
 * line with LiteRT installed; host pipeline tests are skipped otherwise.
 */
internal class HostNets private constructor(private val process: Process) : OcrNets, InkNet {
    private val input = DataInputStream(process.inputStream.buffered())
    private val output = process.outputStream.buffered()

    private fun header(): List<String> {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            check(c >= 0) { "Host model process ended" }
            if (c == '\n'.code) break
            sb.append(c.toChar())
        }
        return sb.toString().split(' ')
    }

    override suspend fun detect(gray: ByteArray, width: Int, height: Int): DetectorOutput {
        output.write("detect $width $height\n".toByteArray())
        output.write(gray, 0, width * height)
        output.flush()
        val h = header()
        val data = ByteArray(h[0].toInt() * h[1].toInt() * 7)
        input.readFully(data)
        return DetectorOutput(data, h[0].toInt(), h[1].toInt(), h[2].toFloat(), h[3].toInt())
    }

    override suspend fun sequence(model: SequenceModel, columns: ByteArray, width: Int): SequenceOutput {
        output.write("seq ${model.assetPrefix} $width\n".toByteArray())
        output.write(columns, 0, width * 24)
        output.flush()
        val h = header()
        val data = ByteArray(h[0].toInt() * h[1].toInt())
        input.readFully(data)
        return SequenceOutput(data, h[0].toInt(), h[1].toInt(), h[2].toFloat(), h[3].toInt())
    }

    override val classes: Int = 12362

    override suspend fun infer(features: FloatArray, frames: Int): FloatArray {
        output.write("ink $frames\n".toByteArray())
        val buffer = ByteBuffer.allocate(frames * 40).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(features, 0, frames * 10)
        output.write(buffer.array())
        output.flush()
        val h = header()
        val bytes = ByteArray(h[0].toInt() * h[1].toInt() * 4)
        input.readFully(bytes)
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    override fun close() {
        process.destroy()
    }

    companion object {
        val modelDir: File get() = File(System.getProperty("recognition.modelDir"))

        fun labels(path: String): List<String> = File(modelDir, path).readText().split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }

        /** Null when no host Python is configured. */
        fun startOrNull(): HostNets? {
            val python = System.getProperty("recognition.hostPython")?.takeIf { it.isNotBlank() } ?: return null
            val script = File(modelDir, "../../../../tools/host_nets.py").canonicalPath
            val command = python.split(' ') + listOf(script, modelDir.absolutePath)
            return HostNets(ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start())
        }
    }
}
