package com.szdict.recognition

import com.szdict.recognition.ink.InkPipeline
import com.szdict.recognition.ocr.OcrPipeline
import org.junit.Assume.assumeTrue
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Full pipelines on the host against the real graphs (optional; needs `RECOGNITION_HOST_PYTHON`, see
 * HostNets). The same cases run on a device in the instrumented tests.
 */
class HostPipelineTest {
    private fun image(file: File): OcrImage {
        val src = ImageIO.read(file)
        val rgb = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, src.width, src.height); drawImage(src, 0, 0, null); dispose() }
        return OcrImage(src.width, src.height, rgb.getRGB(0, 0, src.width, src.height, null, 0, src.width))
    }

    @Test
    fun ocrMatchesExpectedTextOnReferenceImages() {
        val nets = HostNets.startOrNull()
        assumeTrue("no host Python configured", nets != null)
        nets!!
        try {
            val pipeline = OcrPipeline(nets, HostNets.labels("ocr/hani-labels.txt"), HostNets.labels("ocr/latn-labels.txt"), trace = if (System.getProperty("recognition.hostOut") != null) ::println else null)
            var confSum = 0.0; var confN = 0
            val dir = File(System.getProperty("recognition.testImages") ?: File(HostNets.modelDir, "../../../androidInstrumentedTest/assets/testdata").path)
            val outDir = System.getProperty("recognition.hostOut")?.let { File(it).apply { mkdirs() } }
            val failures = ArrayList<String>()
            for (case in OcrCases.all) {
                val file = File(dir, case.file)
                if (!file.exists()) continue
                val img = image(file)
                val start = System.nanoTime()
                val result = pipeline.recognize(img)
                val ms = (System.nanoTime() - start) / 1e6
                println("== ${case.file} (${img.width}x${img.height}) ${"%.0f".format(ms)} ms")
                for (line in result.lines) {
                    println("   ${line.text}${if (line.vertical) "  [vertical]" else ""}")
                    for (c in line.chars) println("      ${c.text}  " + c.alternatives.joinToString(" ") { "${it.text}:${"%.2f".format(it.score)}" })
                }
                for (l in result.lines) for (c in l.chars) { confSum += c.confidence; confN++ }
                if (!case.matches(result.text)) failures.add("${case.file}: expected <${case.expected}> got <${result.text}>")
                for (line in result.lines) {
                    assertEquals(line.text, line.chars.joinToString("") { it.text })
                    for (c in line.chars) assertEquals(c.text, c.alternatives.first().text)
                }
                if (outDir != null) overlay(file, result, File(outDir, file.nameWithoutExtension + "-overlay.png"))
            }
            println("MEANCONF ${confSum / confN} chars=$confN failures=${failures.size}")
            assertEquals(emptyList(), failures)
        } finally {
            nets.close()
        }
    }

    private fun overlay(file: File, result: OcrResult, out: File) {
        val src = ImageIO.read(file)
        val scale = if (maxOf(src.width, src.height) < 600) 3 else 1
        val canvas = BufferedImage(src.width * scale, src.height * scale, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        g.drawImage(src, 0, 0, canvas.width, canvas.height, null)
        fun draw(q: Quad, color: Color, width: Float) {
            g.color = color
            g.stroke = BasicStroke(width)
            val p = q.points
            for (i in 0 until 4) {
                val a = p[i]
                val b = p[(i + 1) % 4]
                g.drawLine((a.x * scale).toInt(), (a.y * scale).toInt(), (b.x * scale).toInt(), (b.y * scale).toInt())
            }
            g.fillOval((p[0].x * scale).toInt() - 2, (p[0].y * scale).toInt() - 2, 5, 5)
        }
        for (line in result.lines) {
            draw(line.box, Color(0, 120, 255), 1f)
            line.chars.forEachIndexed { i, c -> draw(c.box, if (i % 2 == 0) Color(255, 0, 0) else Color(0, 160, 0), 1f) }
        }
        g.dispose()
        ImageIO.write(canvas, "png", out)
    }

    @Test
    fun inkRecognisesReferenceDrawings() {
        val nets = HostNets.startOrNull()
        assumeTrue("no host Python configured", nets != null)
        nets!!
        try {
            val pipeline = InkPipeline(nets, HostNets.labels("ink/labels.txt"))
            val golden = TestData.inkGolden()
            for (case in golden.cases) {
                val start = System.nanoTime()
                val candidates = pipeline.recognize(case.strokes, 30)
                val ms = (System.nanoTime() - start) / 1e6
                println("${case.expected} ${"%.1f".format(ms)} ms -> " + candidates.joinToString(" ") { "${it.text}:${"%.2f".format(it.score)}" })
                assertEquals(case.referenceTop.first(), candidates.first().text, "first candidate for ${case.expected}")
                assertEquals(30, candidates.size)
            }
        } finally {
            nets.close()
        }
    }
}
