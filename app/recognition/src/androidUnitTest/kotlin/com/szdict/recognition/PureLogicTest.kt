package com.szdict.recognition

import com.szdict.recognition.ocr.Box
import com.szdict.recognition.ocr.CtcDecoder
import com.szdict.recognition.ocr.Geometry
import com.szdict.recognition.ocr.Gray
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
    fun rotatedCropOfAnUprightRegionIsTheIdentity() {
        val src = Gray(10, 6)
        for (i in src.px.indices) src.px[i] = (i * 3).toByte()
        val crop = src.rotatedCrop(5.0, 3.0, 0.0, 4, 2)
        for (y in 0 until 2) for (x in 0 until 4) assertEquals(src[x + 3, y + 2], crop[x, y])
    }
}
