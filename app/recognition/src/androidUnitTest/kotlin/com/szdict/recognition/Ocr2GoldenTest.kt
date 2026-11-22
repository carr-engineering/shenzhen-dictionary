package com.szdict.recognition

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Android OCR stack on the host: the common pipeline with the real graphs on ONNX Runtime's
 * desktop build, on the reference images. Skipped when the models have not been fetched
 * (`./gradlew :recognition:fetchOcr2Models`).
 */
class Ocr2GoldenTest {
    private val testdata = File("src/androidInstrumentedTest/assets/testdata")

    private fun withPipeline(block: suspend (read: suspend (String) -> OcrResult) -> Unit) = runBlocking {
        if (!Ocr2Host.available) {
            println("OCR models not fetched: skipped")
            return@runBlocking
        }
        val nets = Ocr2Host.nets()
        try {
            val pipeline = Ocr2Host.pipeline(nets)
            block { name -> pipeline.recognize(Ocr2Host.image(File(testdata, name))) }
        } finally {
            nets.close()
        }
    }

    @Test
    fun readsTheReferenceImagesWithConsistentStructure() = withPipeline { read ->
        val failures = ArrayList<String>()
        for (case in Ocr2Cases.all) {
            val result = read(case.file)
            if (!case.matches(result.text)) failures.add("${case.file}: expected <${case.expected}> got <${result.text}>")
            for (line in result.lines) {
                assertEquals(line.text, line.chars.joinToString("") { it.text }, "line text is the concatenation of its symbols")
                assertTrue(line.chars.isNotEmpty())
                for (c in line.chars) {
                    assertEquals(c.text, c.alternatives.first().text)
                    assertEquals(c.confidence, c.alternatives.first().score)
                    assertEquals(8, c.alternatives.size, "eight candidates per character")
                    assertEquals(8, c.alternatives.map { it.text }.toSet().size)
                    assertTrue(c.alternatives.drop(1).none { it.text.isBlank() }, "no whitespace among a glyph's alternatives")
                    assertTrue(c.confidence > 0f && c.confidence <= 1f)
                    assertTrue(c.alternatives.zipWithNext().all { (a, b) -> a.score >= b.score }, "alternatives are best first")
                    assertTrue(c.alternatives.sumOf { it.score.toDouble() } <= 1.0001)
                    for (p in c.box.points) {
                        assertTrue(p.x >= -3f && p.x <= result.width + 3f && p.y >= -3f && p.y <= result.height + 3f, "glyph box inside the image: $p in ${case.file}")
                    }
                    val centre = c.box.center
                    assertTrue(c.text.isBlank() || line.box.contains(centre.x, centre.y), "glyph inside its line in ${case.file}")
                }
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun offersLookAlikeCharactersAsAlternatives() = withPipeline { read ->
        val result = read("similar.png")
        assertEquals(listOf("我已经吃过了", "未来的土地很大"), result.lines.map { it.text })
        fun alternatives(line: Int, index: Int) = result.lines[line].chars[index].alternatives.map { it.text }
        // 已 / 己 / 巳 are near-identical; all are offered at that position, the reading first.
        assertEquals(listOf("已", "己", "巳"), alternatives(0, 1).take(3))
        assertTrue(alternatives(1, 0).take(4).containsAll(listOf("未", "末")), "未/末: ${alternatives(1, 0)}")
        assertTrue(alternatives(1, 3).containsAll(listOf("土", "士")), "土/士: ${alternatives(1, 3)}")
        assertTrue(alternatives(1, 6).take(3).containsAll(listOf("大", "天", "犬")), "大/天/犬: ${alternatives(1, 6)}")
        // Glyph boxes run left to right, about a glyph wide, without overlapping.
        for (line in result.lines) {
            val boxes = line.chars.map { it.box }
            assertTrue(boxes.zipWithNext().all { (a, b) -> a.topRight.x <= b.topLeft.x + 0.5f })
            assertTrue(boxes.all { it.topRight.x - it.topLeft.x in 30f..45f }, "glyph widths ${boxes.map { it.topRight.x - it.topLeft.x }}")
        }
    }

    @Test
    fun boxesFollowVerticalAndTurnedText() = withPipeline { read ->
        val vertical = read("vertical-tight.png")
        assertEquals(4, vertical.lines.size)
        assertTrue(vertical.lines.all { it.vertical })
        for (line in vertical.lines) {
            val ys = line.chars.map { it.box.center.y }
            assertEquals(ys.sorted(), ys, "vertical glyphs run top to bottom")
            // Upright glyphs: corners in image order, as wide as the column.
            assertTrue(line.chars.all { it.box.topLeft.x < it.box.topRight.x && it.box.topLeft.y < it.box.bottomLeft.y })
            assertTrue(line.box.topLeft.y < line.box.bottomLeft.y && line.box.topLeft.x < line.box.topRight.x)
        }
        // Columns read right to left.
        val xs = vertical.lines.map { it.box.center.x }
        assertEquals(xs.sortedDescending(), xs)

        // Rows first, then the column.
        assertEquals(listOf(false, false, false, false, true), read("mixed-right-tight.png").lines.map { it.vertical })

        val upsideDown = read("pipeline-upside-down.png").lines.single()
        // Reading order is preserved, so on the page the glyphs run right to left and the
        // glyph-relative top-left corner is at the bottom right.
        val centres = upsideDown.chars.map { it.box.center.x }
        assertEquals(centres.sortedDescending(), centres)
        assertTrue(upsideDown.box.topLeft.x > upsideDown.box.bottomRight.x && upsideDown.box.topLeft.y > upsideDown.box.bottomRight.y)
        assertTrue(upsideDown.chars.all { it.box.topLeft.x > it.box.bottomRight.x && it.box.topLeft.y > it.box.bottomRight.y })
    }
}
