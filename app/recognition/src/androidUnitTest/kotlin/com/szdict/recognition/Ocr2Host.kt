package com.szdict.recognition

import com.szdict.recognition.ocr2.Ocr2Options
import com.szdict.recognition.ocr2.Ocr2Pipeline
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/** The Android OCR stack (common pipeline + ONNX Runtime Java API) on the host JVM, models read from the assets directory. */
internal object Ocr2Host {
    val modelDir: File get() = File(System.getProperty("recognition.modelDir"), "ocr2")

    val available: Boolean get() = listOf("det.onnx", "cls.onnx", "rec.onnx", "rec-labels.txt").all { File(modelDir, it).isFile }

    fun nets(threads: Int = 4): OrtOcr2Nets = OrtOcr2Nets({ name ->
        val bytes = File(modelDir, name).readBytes()
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
    }, threads)

    fun labels(): List<String> = File(modelDir, "rec-labels.txt").readText().split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }

    fun pipeline(nets: OrtOcr2Nets, options: Ocr2Options = Ocr2Options()): Ocr2Pipeline = Ocr2Pipeline(nets, labels(), options)

    fun image(file: File): OcrImage {
        val image = ImageIO.read(file) ?: error("Cannot decode $file")
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        return OcrImage(image.width, image.height, pixels)
    }
}
