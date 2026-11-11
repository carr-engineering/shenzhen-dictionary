package com.szdict.recognition

import com.szdict.recognition.ocr.Box
import com.szdict.recognition.ocr.CtcDecoder
import com.szdict.recognition.ocr.Geometry
import com.szdict.recognition.ocr.Gray
import com.szdict.recognition.ocr.Region
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PureLogicTest {
    /** Builds `frames x classes` quantised logits from per-frame (class -> logit byte) maps; others are 0. */
    private fun logits(classes: Int, vararg frames: Map<Int, Int>): ByteArray {
        val out = ByteArray(frames.size * classes)
        frames.forEachIndexed { t, m -> m.forEach { (k, v) -> out[t * classes + k] = v.toByte() } }
        return out
    }

    @Test
    fun ctcCollapsesRepeatsAndBlanksAndRanksAlternatives() {
        // 3 symbols (0..2), repeat separator 3, blank 4.
        val blank = mapOf(4 to 200)
        val data = logits(
            5,
            blank,
            mapOf(0 to 200, 1 to 180, 4 to 100),
            mapOf(0 to 190, 2 to 185, 4 to 150),
            blank,
            mapOf(1 to 220),
            mapOf(4 to 210, 1 to 200),
            mapOf(1 to 220),
        )
        val out = CtcDecoder.decode(data, 7, 5, 3, 0.1f, 3)
        assertEquals(listOf(0, 1, 1), out.map { it.label })
        assertEquals(listOf(1 to 2, 4 to 4, 6 to 6), out.map { it.firstFrame to it.lastFrame })
        // Symbol 1 was strong on the first frame, symbol 2 on the second: both are offered.
        assertEquals(setOf(0, 1, 2), out[0].labels.toSet())
        assertEquals(0, out[0].labels[0])
        assertEquals(1f, out[0].scores.sum(), 1e-4f)
    }

    @Test
    fun ctcSplitsARunOnTheRepeatSeparator() {
        val data = logits(5, mapOf(0 to 200), mapOf(0 to 200, 3 to 195), mapOf(0 to 200), mapOf(4 to 200))
        assertEquals(listOf(0, 0), CtcDecoder.decode(data, 4, 5, 3, 0.1f, 2).map { it.label })
        val weak = logits(5, mapOf(0 to 200), mapOf(0 to 200, 3 to 100), mapOf(0 to 200), mapOf(4 to 200))
        assertEquals(listOf(0), CtcDecoder.decode(weak, 4, 5, 3, 0.1f, 2).map { it.label })
    }

    @Test
    fun quadContainmentWorksForRotatedAndFlippedQuads() {
        val q = Quad(Point(10f, 0f), Point(20f, 10f), Point(10f, 20f), Point(0f, 10f))
        assertTrue(q.contains(10f, 10f))
        assertFalse(q.contains(1f, 1f))
        val flipped = Quad(q.bottomRight, q.bottomLeft, q.topLeft, q.topRight)
        assertTrue(flipped.contains(10f, 10f))
        assertEquals(Point(10f, 10f), q.center)
    }

    @Test
    fun readingOrderSplitsColumnsThenRows() {
        // Two columns of three rows, given shuffled; expected column-major order.
        val boxes = listOf(
            "R2" to Box(430.0, 105.0, 590.0, 150.0), "L3" to Box(35.0, 176.0, 195.0, 220.0), "L1" to Box(35.0, 35.0, 195.0, 80.0),
            "R1" to Box(430.0, 36.0, 590.0, 80.0), "L2" to Box(36.0, 105.0, 195.0, 150.0), "R3" to Box(430.0, 175.0, 590.0, 220.0),
        )
        assertEquals(listOf("L1", "L2", "L3", "R1", "R2", "R3"), Geometry.readingOrder(boxes, false) { it.second }.map { it.first })
        // Vertical columns read right to left.
        val columns = listOf("left" to Box(45.0, 10.0, 90.0, 240.0), "right" to Box(125.0, 10.0, 170.0, 240.0))
        assertEquals(listOf("right", "left"), Geometry.readingOrder(columns, true) { it.second }.map { it.first })
    }

    @Test
    fun resamplingPreservesFlatAreasAndAveragesWhenShrinking() {
        val src = Gray(8, 4)
        for (y in 0 until 4) for (x in 0 until 8) src.px[y * 8 + x] = (if (x < 4) 0 else 200).toByte()
        val half = src.resize(4, 2)
        assertEquals(listOf(0, 0, 200, 200), (0 until 4).map { half[it, 0] })
        val third = src.resample(2.0, 0.0, 6.0, 4.0, 1, 1)
        assertEquals(100, third[0, 0])
        val grown = src.resize(16, 8)
        assertEquals(0, grown[0, 0])
        assertEquals(200, grown[15, 7])
        val turned = src.rotate180()
        assertEquals(200, turned[0, 0])
        assertEquals(0, turned[7, 3])
    }

    @Test
    fun fragmentsAndContinuationsAreFoldedIntoTheirLine() {
        val line = Region(100.0, 50.0, 160.0, 30.0, 0.0, .9, proposals = 10)
        val fullStop = Region(192.0, 55.0, 8.0, 10.0, 0.0, .8)
        val otherRow = Region(100.0, 120.0, 160.0, 30.0, 0.0, .9, proposals = 10)
        val absorbed = Geometry.absorbFragments(listOf(line, fullStop, otherRow))
        assertEquals(2, absorbed.size)
        val grown = absorbed.first { it.cy == 50.0 }
        assertEquals(20.0, grown.cx - grown.w / 2, 1e-9)
        assertEquals(196.0, grown.cx + grown.w / 2, 1e-9)

        // Two pieces of one row that touch are one line; a piece across a gutter is not.
        val left = Region(60.0, 50.0, 100.0, 30.0, 0.0, .9, proposals = 6)
        val right = Region(165.0, 51.0, 100.0, 28.0, 0.0, .8, proposals = 6)
        val acrossGutter = Region(400.0, 50.0, 100.0, 30.0, 0.0, .9, proposals = 6)
        val joined = Geometry.joinCollinear(listOf(left, right, acrossGutter))
        assertEquals(2, joined.size)
        val row = joined.first { it.cx < 300 }
        assertEquals(10.0, row.cx - row.w / 2, 1e-9)
        assertEquals(215.0, row.cx + row.w / 2, 1e-9)
        assertEquals(12, row.proposals)
    }

    @Test
    fun aSmallRegionInsideALineNeverSuppressesTheLine() {
        // Hysteresis lowers a long line's mean score below that of a full stop detected inside it.
        val proposals = ArrayList<Region>()
        for (i in 0 until 10) proposals.add(Region(20.0 + i * 16, 50.0, 32.0, 30.0, 0.0, if (i % 2 == 0) .9 else .4))
        proposals.add(Region(150.0, 58.0, 6.0, 8.0, 0.0, .95))
        val regions = Geometry.mergeProposals(proposals, strong = .65)
        assertEquals(1, regions.size)
        assertTrue(regions[0].w > 150)
        // Weak proposals alone never make a line.
        assertEquals(0, Geometry.mergeProposals(proposals.filter { it.score < .65 }, strong = .65).size)
    }

    @Test
    fun scriptPriorRanksOtherScriptsDownInChineseLinesOnly() {
        fun alts(vararg pairs: Pair<String, Float>) = pairs.map { OcrAlt(it.first, it.second) }
        val chinese = listOf(
            alts("施" to .9f, "旅" to .05f),
            alts("工" to .5f, "エ" to .3f, "T" to .1f, "士" to .06f, "土" to .04f),
            alts("口" to .3f, "ロ" to .5f).sortedByDescending { it.score }, // kana first, Han close behind
            alts("3" to .6f, "了" to .3f), // a digit the recogniser put first stays
            alts("楼" to .8f, "樓" to .1f),
        )
        val ranked = com.szdict.recognition.ocr.ScriptPrior.rerank(chinese, 4)
        assertEquals(listOf("工", "士", "土", "エ"), ranked[1].map { it.text })
        assertEquals(1f, ranked[1].sumOf { it.score.toDouble() }.toFloat() + /* dropped T */ .1f * .25f / (.5f + .03f + .025f + .06f + .04f), 1e-3f)
        assertTrue(ranked[1][0].score > .5f, "freed probability goes to the remaining candidates")
        assertEquals(listOf("口", "ロ"), ranked[2].map { it.text })
        assertEquals(listOf("3", "了"), ranked[3].map { it.text })
        for (list in ranked) assertTrue(list.zipWithNext().all { (a, b) -> a.score >= b.score })

        // A Japanese line (kana among the top readings) and a Latin line are left as they are.
        val japanese = listOf(alts("日" to .9f), alts("本" to .9f), alts("の" to .8f, "的" to .1f), alts("カ" to .6f, "力" to .4f), alts("ー" to .7f, "一" to .3f))
        assertEquals(japanese, com.szdict.recognition.ocr.ScriptPrior.rerank(japanese, 4))
        val latin = listOf(alts("l" to .5f, "丨" to .3f), alts("o" to .6f, "口" to .2f))
        assertEquals(latin, com.szdict.recognition.ocr.ScriptPrior.rerank(latin, 4))
    }

    @Test
    fun convexIntersectionMeasuresSharedAreaOfRotatedRectangles() {
        val a = Geometry.corners(Region(50.0, 50.0, 100.0, 20.0, 0.0, 1.0))
        val b = Geometry.corners(Region(50.0, 50.0, 100.0, 20.0, Math.PI / 2, 1.0))
        assertEquals(2000.0, Geometry.area(a.toList()), 1e-6)
        assertEquals(400.0, Geometry.intersectionArea(a, b), 1e-6)
        // Two neighbouring lines of text tilted by 15 degrees: bounding boxes overlap, the lines do not.
        val upper = Region(200.0, 100.0, 400.0, 40.0, .26, 1.0)
        val lower = Region(190.0, 158.0, 300.0, 40.0, .26, 1.0)
        assertTrue(Geometry.overlap(Geometry.bounds(upper), Geometry.bounds(lower)) > .3)
        assertEquals(0.0, Geometry.intersectionArea(Geometry.corners(upper), Geometry.corners(lower)), 1e-6)
    }

    @Test
    fun inkPolarityFollowsTheStrokesNotTheBrightness() {
        // Thin strokes on a mid-grey panel: lighter strokes in one crop, darker in the other.
        fun crop(paper: Int, stroke: Int): Gray {
            val g = Gray(120, 40)
            g.px.fill(paper.toByte())
            for (x in 10 until 110 step 12) for (y in 8 until 32) for (dx in 0 until 3) g.px[y * 120 + x + dx] = stroke.toByte()
            return g
        }
        val light = com.szdict.recognition.ocr.InkAnalysis.inkBounds(crop(140, 250))
        val dark = com.szdict.recognition.ocr.InkAnalysis.inkBounds(crop(140, 30))
        assertTrue(light.dark, "light strokes are flipped")
        assertFalse(dark.dark)
        assertEquals(light.box!!.toList(), dark.box!!.toList())
        assertEquals(listOf(10, 8, 109, 32), dark.box!!.toList())
        // Normalised: paper white, strokes black, whichever way round it started.
        for (ink in listOf(light, dark)) {
            val clean = com.szdict.recognition.ocr.InkAnalysis.normalizeContrast(ink)
            assertTrue(clean[11, 20] < 40, "stroke is black")
            assertTrue(clean[17, 20] > 230 && clean[60, 3] > 230, "paper is white")
        }
        // The inside of a wide dark block is not ink: it does not stand out from its own neighbourhood.
        val block = Gray(120, 40)
        block.px.fill(200.toByte())
        for (y in 0 until 40) for (x in 0 until 50) block.px[y * 120 + x] = 40
        val mask = com.szdict.recognition.ocr.InkAnalysis.inkBounds(block).mask
        assertEquals(0, mask[20 * 120 + 10].toInt())
    }

    @Test
    fun stackedLinesAreSeparatedAtTheBlankBandBetweenThem() {
        // A tall line (rows 10..40) above a short one (rows 46..58), with dots above (rows 2..3).
        val w = 100
        val h = 64
        val mask = ByteArray(w * h)
        fun fill(from: Int, to: Int, every: Int) { for (y in from until to) for (x in 0 until w step every) mask[y * w + x] = 1 }
        fill(2, 4, 9)
        fill(10, 40, 3)
        fill(46, 58, 3)
        val bands = com.szdict.recognition.ocr.InkAnalysis.stackedLines(mask, w, h, 0, w).map { it[0] to it[1] }
        assertEquals(listOf(10 to 40, 46 to 58), bands)
        // The line band grows from a core inside the tall line and stops at the valley.
        val rows = com.szdict.recognition.ocr.InkAnalysis.textRows(mask, w, h, 15, 35, 0, w)
        assertEquals(listOf(10, 40), rows.toList())
    }

    @Test
    fun quarterTurnMapsPixelsClockwise() {
        val src = Gray(3, 2)
        for (i in src.px.indices) src.px[i] = i.toByte()
        val turned = src.rotate90()
        assertEquals(2, turned.w)
        assertEquals(3, turned.h)
        // Turned (x', y') is the source's (y', h - 1 - x').
        for (y in 0 until 3) for (x in 0 until 2) assertEquals(src[y, 1 - x], turned[x, y])
    }

    @Test
    fun columnSlicerKeepsSplitGlyphsWholeAndDividesTouchingOnes() {
        // A 10-px-wide column: a full glyph, a glyph of two bars (二), a flat glyph (一), and two
        // glyphs touching each other.
        val w = 10
        val h = 80
        val mask = ByteArray(w * h)
        fun fill(from: Int, to: Int) { for (y in from until to) for (x in 0 until w) mask[y * w + x] = 1 }
        fill(0, 9)      // glyph 1
        fill(14, 16)    // 二 upper bar
        fill(20, 22)    // 二 lower bar
        fill(31, 33)    // 一
        fill(42, 62)    // two touching glyphs
        val cells = com.szdict.recognition.ocr.ColumnSlicer.slice(mask, w, 0, 0, w, h).map { it[0] to it[1] }
        assertEquals(listOf(0 to 9, 14 to 22, 31 to 33, 42 to 52, 52 to 62), cells)
    }

    @Test
    fun rotatedCropOfAnUprightRegionIsTheIdentity() {
        val src = Gray(10, 6)
        for (i in src.px.indices) src.px[i] = (i * 3).toByte()
        val crop = src.rotatedCrop(5.0, 3.0, 0.0, 4, 2)
        for (y in 0 until 2) for (x in 0 until 4) assertEquals(src[x + 3, y + 2], crop[x, y])
    }
}
