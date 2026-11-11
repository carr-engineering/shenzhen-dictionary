package com.szdict.recognition

import com.szdict.recognition.ocr.CharLayout
import com.szdict.recognition.ocr.CtcDecoder
import com.szdict.recognition.ocr.Geometry
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure-Kotlin OCR stages against tensors and answers exported from the reference implementations. */
class OcrGoldenTest {
    private val labels = HostNets.labels("ocr/hani-labels.txt")

    private class LineGolden(val frames: Int, val classes: Int, val scale: Float, val width: Int, val rendered: String, val greedy: String, val logits: ByteArray, val columns: ByteArray)

    private fun lines(): List<LineGolden> = File(TestData.golden, "hani-lines.tsv").readLines().filter { it.isNotBlank() }.map { row ->
        val f = row.split('\t')
        val frames = f[1].toInt()
        val classes = f[2].toInt()
        val width = f[5].toInt()
        val bytes = TestData.gunzip("hani-line-${f[0]}.bin.gz")
        assertEquals(frames * classes + width * 24, bytes.size)
        LineGolden(frames, classes, f[3].toFloat(), width, f[6], f[7], bytes.copyOfRange(0, frames * classes), bytes.copyOfRange(frames * classes, bytes.size))
    }

    private fun decode(line: LineGolden) = CtcDecoder.decode(line.logits, line.frames, line.classes, labels.size, line.scale, 8)

    @Test
    fun decoderReproducesReferenceGreedyText() {
        val golden = lines()
        // Lines without doubled letters: identical to the reference browser decoder.
        for (line in golden.take(2)) {
            assertEquals(line.greedy, decode(line).joinToString("") { labels[it.label] }.trim())
        }
        // The reference collapses "ll" to "l"; splitting on the repeat separator recovers it.
        assertEquals("未来的土地Helo 123", golden[2].greedy)
        assertEquals("未来的土地Hello 123", decode(golden[2]).joinToString("") { labels[it.label] }.trim())
    }

    @Test
    fun alternativesContainLookAlikesBestFirst() {
        val line = lines()[0] // rendered 我已经吃过了。
        val emissions = decode(line)
        val text = emissions.map { labels[it.label] }
        assertEquals(listOf("我", "己", "经", "吃", "过", "了", "。"), text)
        for (e in emissions) {
            assertEquals(e.label, e.labels[0], "top-1 is the decoded symbol")
            assertEquals(e.labels.size, e.labels.toSet().size, "no duplicate candidates")
            for (i in 1 until e.scores.size) assertTrue(e.scores[i] <= e.scores[i - 1], "scores are descending")
            assertTrue(e.scores.all { it in 0f..1f } && e.scores.sum() <= 1.0001f, "scores are a probability prefix")
            assertTrue(e.firstFrame <= e.peakFrame && e.peakFrame <= e.lastFrame)
            println(e.labels.indices.joinToString(" ") { "${labels[e.labels[it]]}:${"%.3f".format(e.scores[it])}" })
        }
        // The printed character was 已; the recogniser preferred 己 but must offer 已 next.
        val second = emissions[1]
        val alternatives = second.labels.map { labels[it] }
        assertTrue("已" in alternatives.take(3), "已 offered for 己: $alternatives")
        assertTrue("巳" in alternatives, "巳 offered for 己: $alternatives")
        // 未 / 末 on the third line.
        val third = decode(lines()[2])
        assertTrue("末" in third[0].labels.map { labels[it] }.take(4), "末 offered for 未")
        assertTrue("士" in third[3].labels.map { labels[it] }.take(4), "士 offered for 土")
    }

    @Test
    fun characterSpansTileTheLineInOrder() {
        for (line in lines()) {
            val emissions = decode(line)
            val spans = CharLayout.spans(line.columns, line.width, emissions.map { it.peakFrame }, emissions.map { labels[it.label].isBlank() })
            assertEquals(emissions.size, spans.size)
            for (i in spans.indices) {
                assertTrue(spans[i][1] > spans[i][0], "span $i is not empty")
                assertTrue(spans[i][0] >= 0 && spans[i][1] <= line.width)
                if (i > 0) assertTrue(spans[i][0] >= spans[i - 1][1] - 1e-6, "spans do not overlap")
            }
            // Rendered full-width Hanzi are 20 px square on the strip: spans must be glyph-sized and
            // centred where the glyph was drawn.
            if (line.rendered.all { it.code > 0x2E80 }) {
                val pitch = (line.width - 12 - 4).toDouble() / line.rendered.length
                for (i in spans.indices) {
                    val centre = (spans[i][0] + spans[i][1]) / 2
                    val expected = 6 + pitch * (i + .5)
                    assertTrue(abs(centre - expected) < pitch * .45, "glyph $i centre $centre vs $expected")
                    assertTrue(spans[i][1] - spans[i][0] <= 24.0, "glyph $i width")
                }
            }
        }
    }

    @Test
    fun detectorDecodingAndMergingMatchReferenceGeometry() {
        val rows = File(TestData.golden, "detector-columns.txt").readLines().filter { it.isNotBlank() }
        val head = rows[0].split(' ')
        val raw = TestData.gunzip("detector-columns.bin.gz")
        val w = head[4].toDouble()
        val h = head[5].toDouble()
        val proposals = Geometry.decodeDetector(raw, head[0].toInt(), head[1].toInt(), head[2].toDouble(), head[3].toInt(), w / head[6].toDouble(), h / head[7].toDouble())
        val regions = Geometry.mergeProposals(proposals).filter { it.w > 4 && it.h > 5 && it.h < maxOf(w, h) * 1.3 }
        val counts = rows[1].split(' ').map { it.toInt() }
        assertEquals(counts[0], proposals.size)
        assertEquals(counts[1], regions.size)
        assertEquals(counts[2], Geometry.verticalColumns(regions).size)
        regions.forEachIndexed { i, r ->
            val e = rows[i + 2].split(' ').map { it.toDouble() }
            val actual = doubleArrayOf(r.cx, r.cy, r.w, r.h, r.angle, r.score, r.proposals.toDouble())
            for (k in actual.indices) assertEquals(e[k], actual[k], 1e-6, "region $i field $k")
        }
    }
}
