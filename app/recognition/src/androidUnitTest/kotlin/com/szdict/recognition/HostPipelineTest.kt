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
            val dir = TestData.testdata
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

    /** Exploration aid: recognises every image in `RECOGNITION_TEST_IMAGES` and prints the result. */
    @Test
    fun ocrOnExtraImages() {
        val dir = System.getProperty("recognition.testImages")?.let { File(it) }
        assumeTrue("no extra image directory configured", dir != null && dir.isDirectory)
        val nets = HostNets.startOrNull()
        assumeTrue("no host Python configured", nets != null)
        nets!!
        try {
            val pipeline = OcrPipeline(nets, HostNets.labels("ocr/hani-labels.txt"), HostNets.labels("ocr/latn-labels.txt"), trace = if (System.getenv("RECOGNITION_TRACE") != null) ::println else null)
            val outDir = System.getProperty("recognition.hostOut")?.let { File(it).apply { mkdirs() } }
            for (file in dir!!.listFiles { f -> f.extension in setOf("png", "jpg", "jpeg") }!!.sortedBy { it.name }) {
                val img = image(file)
                val start = System.nanoTime()
                val result = pipeline.recognize(img)
                println("== ${file.name} (${img.width}x${img.height}) ${"%.0f".format((System.nanoTime() - start) / 1e6)} ms")
                for (line in result.lines) {
                    val weak = line.chars.filter { it.confidence < .5f }.joinToString(" ") { c -> c.alternatives.take(4).joinToString("/") { it.text } }
                    println("   ${line.text}${if (line.vertical) "  [vertical]" else ""}    ${if (weak.isEmpty()) "" else "weak: $weak"}")
                }
                if (outDir != null) overlay(file, result, File(outDir, file.nameWithoutExtension + "-overlay.png"))
            }
        } finally {
            nets.close()
        }
    }

    /**
     * Character accuracy per degradation category over an evaluation directory (`RECOGNITION_EVAL_DIR`,
     * see tools/make_eval_set.py). Scores Han characters only: top-1 is the share of ground-truth
     * characters found in order in the output, top-5 counts a character as found when it is among
     * the first five alternatives at its position.
     */
    @Test
    fun ocrEvaluation() {
        val dir = System.getProperty("recognition.evalDir")?.let { File(it) }
        assumeTrue("no evaluation directory configured", dir != null && dir.isDirectory)
        val nets = HostNets.startOrNull()
        assumeTrue("no host Python configured", nets != null)
        nets!!
        try {
            val options = com.szdict.recognition.ocr.OcrOptions(fineDetection = System.getenv("RECOGNITION_NO_FINE") == null)
            val pipeline = OcrPipeline(nets, HostNets.labels("ocr/hani-labels.txt"), HostNets.labels("ocr/latn-labels.txt"), options)
            class Tally(var images: Int = 0, var chars: Int = 0, var top1: Int = 0, var top5: Int = 0, var ms: Double = 0.0, var marked: Int = 0, var markedWrong: Int = 0, var wrong: Int = 0)
            val tallies = sortedMapOf<String, Tally>()
            val details = StringBuilder()
            fun han(s: String) = s.filter { it.code in 0x3400..0x9FFF }
            for (file in dir!!.listFiles { f -> f.extension in setOf("png", "jpg", "jpeg") }!!.sortedBy { it.name }) {
                val truth = File(dir, file.nameWithoutExtension + ".txt").takeIf { it.exists() }?.readText() ?: continue
                val expected = han(truth)
                val start = System.nanoTime()
                val result = pipeline.recognize(image(file))
                val ms = (System.nanoTime() - start) / 1e6
                val chars = result.lines.flatMap { it.chars }
                fun aligned(match: (OcrChar, Char) -> Boolean): Int {
                    val table = Array(expected.length + 1) { IntArray(chars.size + 1) }
                    for (i in 1..expected.length) for (j in 1..chars.size) {
                        table[i][j] = maxOf(table[i - 1][j], table[i][j - 1], table[i - 1][j - 1] + if (match(chars[j - 1], expected[i - 1])) 1 else 0)
                    }
                    return table[expected.length][chars.size]
                }
                val top1 = aligned { c, e -> c.text == e.toString() }
                val top5 = aligned { c, e -> c.alternatives.take(5).any { it.text == e.toString() } }
                val tally = tallies.getOrPut(file.name.substringBefore("__")) { Tally() }
                tally.images++; tally.chars += expected.length; tally.top1 += top1; tally.top5 += top5; tally.ms += ms
                details.append("${file.name}\t$top1/${expected.length}\t$top5\t${result.lines.joinToString(" | ") { it.text }}\n")
            }
            val table = StringBuilder("category\timages\tchars\ttop1\ttop5\tms\n")
            var chars = 0; var top1 = 0; var top5 = 0
            for ((name, t) in tallies) {
                table.append("$name\t${t.images}\t${t.chars}\t${"%.1f".format(100.0 * t.top1 / t.chars)}\t${"%.1f".format(100.0 * t.top5 / t.chars)}\t${"%.0f".format(t.ms / t.images)}\n")
                chars += t.chars; top1 += t.top1; top5 += t.top5
            }
            table.append("ALL\t${tallies.values.sumOf { it.images }}\t$chars\t${"%.1f".format(100.0 * top1 / chars)}\t${"%.1f".format(100.0 * top5 / chars)}\t\n")
            println(table)
            System.getProperty("recognition.hostOut")?.let { File(it).apply { mkdirs() } }?.let {
                File(it, "eval-table.tsv").writeText(table.toString())
                File(it, "eval-details.tsv").writeText(details.toString())
            }
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
