package com.szdict.recognition.ocr2

import com.szdict.recognition.OcrImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The parts of the pipeline that need no network, against values computed with OpenCV 5.0 and pyclipper 1.4. */
class Ocr2PureTest {
    private fun map(width: Int, height: Int, fill: (Int, Int) -> Float) = FloatArray(width * height) { fill(it % width, it / width) }

    private fun pairs(points: IntArray): Set<Pair<Int, Int>> = (0 until points.size / 2).map { points[it * 2] to points[it * 2 + 1] }.toSet()

    private fun pairs(points: DoubleArray): List<Pair<Double, Double>> = (0 until points.size / 2).map { points[it * 2] to points[it * 2 + 1] }

    private fun assertCorners(expected: List<Pair<Double, Double>>, actual: DoubleArray, tolerance: Double = 1e-3) {
        val left = pairs(actual).toMutableList()
        for (e in expected) {
            val match = left.firstOrNull { abs(it.first - e.first) <= tolerance && abs(it.second - e.second) <= tolerance }
            assertNotNull(match, "corner $e missing in ${pairs(actual)}")
            left.remove(match)
        }
    }

    // ---- detector post-processing ------------------------------------------------------------

    @Test
    fun bordersAreTheOutlineAndEachHole() {
        // A 6 x 5 block with a two-pixel hole, and a separate single pixel.
        val m = map(12, 8) { x, y -> if ((x in 1..6 && y in 1..5 && !(y == 3 && x in 3..4)) || (x == 10 && y == 6)) 0.9f else 0.1f }
        val borders = DbPostProcess.borders(m, 12, 8, 0.3f)
        assertEquals(3, borders.size)
        // Regions found later come first; of one region the outline comes before its holes.
        assertEquals(setOf(10 to 6), pairs(borders[0]))
        val hole = pairs(borders[1])
        val outline = pairs(borders[2])
        assertEquals(setOf(3 to 2, 4 to 2, 2 to 3, 5 to 3, 3 to 4, 4 to 4), hole)
        assertEquals(18, outline.size)
        assertTrue(outline.all { (x, y) -> x == 1 || x == 6 || y == 1 || y == 5 })
    }

    @Test
    fun backgroundTouchingTheEdgeIsOneRegion() {
        // Two notches open to the map's edge are part of the outside: one border, no holes.
        val m = map(8, 6) { x, y -> if (y in 1..4 && !(x == 0 && y == 2) && !(x == 7 && y == 3)) 0.9f else 0f }
        assertEquals(1, DbPostProcess.borders(m, 8, 6, 0.3f).size)
        assertEquals(1, DbPostProcess.borders(m, 8, 6, 0.3f, limit = 1).size)
    }

    @Test
    fun minimumAreaRectangleMatchesOpenCv() {
        // A text kernel's hole from a real page: the upright 5 x 3 rectangle has the same area (15) as
        // this tilted one, which OpenCV returns.
        val hole = intArrayOf(1291, 466, 1292, 465, 1294, 465, 1296, 467, 1295, 468, 1292, 468, 1291, 467)
        val rect = assertNotNull(DbPostProcess.minAreaRect(hole))
        assertCorners(listOf(1293.5 to 469.5, 1290.5 to 466.5, 1293.0 to 464.0, 1296.0 to 467.0), rect.corners)
        assertEquals(3.5355, rect.shortSide, 1e-3)

        val upright = assertNotNull(DbPostProcess.minAreaRect(intArrayOf(0, 0, 4, 0, 4, 2, 0, 2, 2, 1)))
        assertCorners(listOf(0.0 to 0.0, 4.0 to 0.0, 4.0 to 2.0, 0.0 to 2.0), upright.corners)
        assertEquals(2.0, upright.shortSide, 1e-6)
        // Corner order: upper left, upper right, lower right, lower left.
        assertEquals(listOf(0 to 0, 4 to 0, 4 to 2, 0 to 2), pairs(upright.corners).map { (x, y) -> Pair(kotlin.math.round(x).toInt(), kotlin.math.round(y).toInt()) })

        val tilted = assertNotNull(DbPostProcess.minAreaRect(intArrayOf(0, 2, 2, 0, 5, 3, 3, 5)))
        assertCorners(listOf(3.0 to 5.0, 0.0 to 2.0, 2.0 to 0.0, 5.0 to 3.0), tilted.corners)

        val segment = assertNotNull(DbPostProcess.minAreaRect(intArrayOf(0, 0, 10, 0)))
        assertEquals(0.0, segment.shortSide)
        assertEquals(0.0, assertNotNull(DbPostProcess.minAreaRect(intArrayOf(7, 7))).shortSide)
        assertEquals(null, DbPostProcess.minAreaRect(IntArray(0)))
    }

    @Test
    fun polygonFillMatchesOpenCv() {
        fun filled(px: IntArray, py: IntArray): List<String> {
            val mask = BooleanArray(11 * 9)
            DbPostProcess.fillPolygon(mask, 11, 9, px, py)
            return List(9) { y -> CharArray(11) { x -> if (mask[y * 11 + x]) '1' else '0' }.concatToString() }
        }
        assertEquals(
            listOf("00010000000", "01111000000", "11111100000", "01111110000", "00111100000", "00010000000", "00000000000", "00000000000", "00000000000"),
            filled(intArrayOf(3, 6, 3, 0), intArrayOf(0, 3, 5, 2)),
        )
        assertEquals(
            listOf("11110000000", "11111111000", "01111111000", "01111110000", "00001110000", "00000000000", "00000000000", "00000000000", "00000000000"),
            filled(intArrayOf(0, 7, 6, 1), intArrayOf(0, 1, 4, 3)),
        )
        assertEquals(
            listOf("00110000000", "01111111000", "01111111110", "11111111110", "11111111110", "00111111100", "00000111100", "00000001100", "00000000000"),
            filled(intArrayOf(2, 9, 8, 0), intArrayOf(0, 2, 7, 4)),
        )
    }

    @Test
    fun boxScoreIsTheMeanInsideTheBox() {
        val m = map(20, 10) { x, y -> if (x in 5..9 && y in 2..4) 1f else 0f }
        assertEquals(1.0, DbPostProcess.boxScore(m, 20, 10, doubleArrayOf(5.0, 2.0, 9.0, 2.0, 9.0, 4.0, 5.0, 4.0)), 1e-9)
        // Twice as wide: half of the counted pixels are text (5 of 10 columns, the boundary included).
        assertEquals(0.5, DbPostProcess.boxScore(m, 20, 10, doubleArrayOf(5.0, 2.0, 14.0, 2.0, 14.0, 4.0, 5.0, 4.0)), 1e-9)
        // A box partly outside the map is clipped to it.
        assertEquals(1.0, DbPostProcess.boxScore(map(4, 4) { _, _ -> 1f }, 4, 4, doubleArrayOf(-2.0, -2.0, 8.0, -2.0, 8.0, 8.0, -2.0, 8.0)), 1e-9)
    }

    @Test
    fun unclipMatchesClipperRoundOffset() {
        // Offset 11.9555 = area x 1.5 / perimeter; 20 outline points, as from pyclipper.
        assertEquals(
            setOf(
                -2 to 30, -2 to 49, -1 to 25, -1 to 53, 1 to 57, 2 to 21, 5 to 60, 6 to 19, 10 to 18, 10 to 61,
                109 to 18, 109 to 61, 113 to 60, 114 to 19, 117 to 58, 118 to 22, 120 to 26, 120 to 54, 121 to 30, 121 to 49,
            ),
            pairs(DbPostProcess.unclip(doubleArrayOf(10.0, 30.0, 109.0, 30.0, 109.0, 49.0, 10.0, 49.0), 1.5)),
        )
        // Fractional corners are truncated before offsetting; either winding gives the same outline.
        val tilted = setOf(
            229 to 401, 229 to 403, 231 to 390, 231 to 406, 233 to 387, 234 to 408, 236 to 385, 238 to 385,
            259 to 413, 261 to 413, 263 to 390, 264 to 411, 266 to 392, 266 to 408, 268 to 395, 268 to 397,
        )
        assertEquals(tilted, pairs(DbPostProcess.unclip(doubleArrayOf(237.3, 391.3, 262.3, 396.3, 260.0, 407.8, 235.0, 402.8), 1.5)))
        assertEquals(tilted, pairs(DbPostProcess.unclip(doubleArrayOf(235.0, 402.8, 260.0, 407.8, 262.3, 396.3, 237.3, 391.3), 1.5)))
        assertEquals(
            setOf(-1 to 1, -1 to 3, 2 to -1, 2 to 6, 4 to -1, 4 to 6, 7 to 2, 7 to 4),
            pairs(DbPostProcess.unclip(doubleArrayOf(3.0, 0.0, 6.0, 3.0, 3.5, 5.5, 0.5, 2.5), 1.5)),
        )
    }

    @Test
    fun aTextKernelBecomesAGrownLineBox() {
        // The detector marks a line's shrunk kernel: 100 x 20 map pixels of a 2x smaller map.
        val m = map(160, 80) { x, y -> if (x in 10..109 && y in 30..49) 0.95f else 0.02f }
        val boxes = DbPostProcess.boxes(m, 160, 80, 320, 160, 0.3f, 0.6f, 1.5, 1000)
        val box = boxes.single()
        assertEquals(0.95f, box.score, 1e-4f)
        // Grown by 11.96 map pixels on every side (OpenCV: -2..121 x 18..61), scaled by 2, clipped to the image.
        assertContentEquals(floatArrayOf(0f, 36f, 242f, 36f, 242f, 122f, 0f, 122f), box.points)
        // Weak or tiny kernels give nothing.
        assertTrue(DbPostProcess.boxes(map(160, 80) { x, y -> if (x in 10..109 && y in 30..49) 0.5f else 0.02f }, 160, 80, 320, 160, 0.3f, 0.6f, 1.5, 1000).isEmpty())
        assertTrue(DbPostProcess.boxes(map(160, 80) { x, y -> if (x in 10..109 && y in 30..31) 0.95f else 0.02f }, 160, 80, 320, 160, 0.3f, 0.6f, 1.5, 1000).isEmpty())
    }

    @Test
    fun boxesAreSortedByRowsThenLeftToRight() {
        fun box(x: Float, y: Float) = TextBox(floatArrayOf(x, y, x + 50, y, x + 50, y + 20, x, y + 20), 1f)
        val sorted = DbPostProcess.sort(listOf(box(300f, 12f), box(10f, 100f), box(10f, 18f), box(200f, 5f), box(120f, 104f)))
        // 5, 12 and 18 chain into one row (steps under 10); 100 and 104 are the next.
        assertEquals(listOf(10f, 200f, 300f, 10f, 120f), sorted.map { it.points[0] })
        assertContentEquals(floatArrayOf(1f, 2f, 9f, 3f, 8f, 7f, 0f, 6f), DbPostProcess.clockwise(doubleArrayOf(8.0, 7.0, 1.0, 2.0, 0.0, 6.0, 9.0, 3.0)))
    }

    // ---- images and crops --------------------------------------------------------------------

    private fun image(width: Int, height: Int, pixel: (Int, Int) -> Int): BgrImage {
        val data = ByteArray(width * height * 3)
        for (y in 0 until height) for (x in 0 until width) {
            val v = pixel(x, y)
            data[(y * width + x) * 3] = v.toByte()
            data[(y * width + x) * 3 + 1] = (v shr 8).toByte()
            data[(y * width + x) * 3 + 2] = (v shr 16).toByte()
        }
        return BgrImage(width, height, data)
    }

    private fun BgrImage.blue(x: Int, y: Int) = data[(y * width + x) * 3].toInt() and 0xFF

    @Test
    fun argbBecomesBgr() {
        val bgr = BgrImage.fromArgb(OcrImage(2, 1, intArrayOf(0xFF112233.toInt(), 0x00AABBCC)))
        assertContentEquals(byteArrayOf(0x33, 0x22, 0x11, 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte()), bgr.data)
    }

    @Test
    fun resizeIsBilinearWithAlignedPixelCentres() {
        val source = image(4, 2) { x, y -> x * 40 + y * 100 }
        assertTrue(source.resize(4, 2) === source)
        // Halving averages pairs; doubling interpolates at quarter positions and repeats the edges.
        val half = source.resize(2, 1)
        assertEquals(listOf(70, 150), listOf(half.blue(0, 0), half.blue(1, 0)))
        val row = image(2, 1) { x, _ -> x * 200 }.resize(4, 1)
        assertEquals(listOf(0, 50, 150, 200), List(4) { row.blue(it, 0) })
    }

    @Test
    fun rotationsMovePixelsAsExpected() {
        val source = image(3, 2) { x, y -> y * 3 + x }
        val quarter = source.rotate90()
        assertEquals(2 to 3, quarter.width to quarter.height)
        // Counter-clockwise: the right column becomes the top row.
        assertEquals(listOf(2, 5, 1, 4, 0, 3), List(6) { quarter.blue(it % 2, it / 2) })
        val half = source.rotate180()
        assertEquals(listOf(5, 4, 3, 2, 1, 0), List(6) { half.blue(it % 3, it / 3) })
        val padded = source.padVertically(2)
        assertEquals(6, padded.height)
        assertEquals(listOf(0, 0, 4, 0), listOf(padded.blue(1, 0), padded.blue(1, 1), padded.blue(1, 3), padded.blue(1, 5)))
    }

    @Test
    fun cropTransformMapsTheBoxOntoTheCropAndBack() {
        val quad = floatArrayOf(10f, 20f, 110f, 30f, 108f, 60f, 8f, 50f)
        val forward = Homography.toRectangle(quad, 100.0, 30.0)
        val out = DoubleArray(2)
        val corners = listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 30.0, 0.0 to 30.0)
        for (i in 0 until 4) {
            forward.apply(quad[i * 2].toDouble(), quad[i * 2 + 1].toDouble(), out)
            assertEquals(corners[i].first, out[0], 1e-6)
            assertEquals(corners[i].second, out[1], 1e-6)
            forward.inverse().apply(corners[i].first, corners[i].second, out)
            assertEquals(quad[i * 2].toDouble(), out[0], 1e-6)
            assertEquals(quad[i * 2 + 1].toDouble(), out[1], 1e-6)
        }
    }

    @Test
    fun anUprightBoxIsCutOutPixelForPixel() {
        val source = image(40, 30) { x, y -> (x * 5 + y * 3) and 0xFF }
        val box = floatArrayOf(4f, 6f, 24f, 6f, 24f, 16f, 4f, 16f)
        val crop = source.warp(Homography.toRectangle(box, 20.0, 10.0).inverse(), 20, 10)
        for (y in 0 until 10) for (x in 0 until 20) assertEquals(source.blue(x + 4, y + 6), crop.blue(x, y), "pixel $x, $y")
        // Outside the image the edge pixel repeats.
        val over = source.warp(Homography.toRectangle(floatArrayOf(-5f, 0f, 5f, 0f, 5f, 4f, -5f, 4f), 10.0, 4.0).inverse(), 10, 4)
        assertEquals(source.blue(0, 2), over.blue(0, 2))
    }

    // ---- decoding ----------------------------------------------------------------------------

    /** Frames of class probabilities: each frame lists (class, probability); the rest is spread thinly. */
    private fun frames(classes: Int, vararg frames: List<Pair<Int, Float>>): FloatArray {
        val out = FloatArray(frames.size * classes)
        for ((t, frame) in frames.withIndex()) {
            val rest = (1f - frame.sumOf { it.second.toDouble() }.toFloat()) / (classes - frame.size)
            for (c in 0 until classes) out[t * classes + c] = rest
            for ((c, p) in frame) out[t * classes + c] = p
        }
        return out
    }

    @Test
    fun greedyCtcKeepsTheAlternativesOfTheEmissionFrame() {
        val data = frames(
            10,
            listOf(0 to 0.9f),
            listOf(3 to 0.6f, 4 to 0.3f, 0 to 0.05f),
            listOf(3 to 0.9f, 5 to 0.05f), // the same symbol continues: no new emission
            listOf(0 to 0.8f, 3 to 0.1f),
            listOf(3 to 0.5f, 7 to 0.4f), // after a blank the symbol repeats
            listOf(7 to 0.7f, 3 to 0.2f), // a different symbol directly after
        )
        val symbols = CtcAlternatives.decode(data, 0, 6, 10, 3)
        assertEquals(listOf(3, 3, 7), symbols.map { it.label })
        assertEquals(listOf(1, 4, 5), symbols.map { it.frame })
        val first = symbols[0]
        assertEquals(0.6f, first.probability)
        assertEquals(listOf(3, 4), first.labels.take(2))
        assertEquals(3, first.labels.size)
        // Renormalised over the symbols: the blank's 0.05 is left out.
        assertEquals(0.6f / 0.95f, first.scores[0], 1e-6f)
        assertEquals(0.3f / 0.95f, first.scores[1], 1e-6f)
        assertTrue(first.scores.toList().zipWithNext().all { (a, b) -> a >= b })
        assertEquals(listOf(7, 3), symbols[2].labels.take(2))
        // Never the blank among the candidates, and no more candidates than symbols exist.
        assertFalse(symbols.any { 0 in it.labels })
        assertEquals(2, CtcAlternatives.decode(frames(3, listOf(1 to 0.7f, 2 to 0.2f, 0 to 0.1f)), 0, 1, 3, 8).single().labels.size)
        assertTrue(CtcAlternatives.decode(frames(4, listOf(0 to 0.9f), listOf(0 to 0.8f)), 0, 2, 4, 8).isEmpty())
        // An offset selects one line of a batch.
        assertEquals(listOf(7), CtcAlternatives.decode(data, 5 * 10, 1, 10, 3).map { it.label })
    }

    @Test
    fun characterSpansFollowEmissionFrames() {
        // Four Hanzi emitted every 5 frames of a 20-frame line 200 pixels wide: 10 px per frame.
        val spans = CharSpans.compute(listOf("你", "好", "世", "界"), intArrayOf(2, 7, 12, 17), 20.0, 200)
        assertEquals(listOf(0.0 to 50.0, 50.0 to 100.0, 100.0 to 150.0, 150.0 to 200.0), spans.map { it[0] to it[1] })
        // The line occupies only half of the padded batch tensor: frames are twice as wide.
        val padded = CharSpans.compute(listOf("你", "好"), intArrayOf(2, 7), 10.0, 200)
        assertEquals(listOf(0.0 to 100.0, 100.0 to 200.0), padded.map { it[0] to it[1] })
        // A single character has no spacing to measure: it is as wide as the line, about its frame.
        assertEquals(0.0 to 195.0, CharSpans.compute(listOf("好"), intArrayOf(9), 20.0, 200).single().let { it[0] to it[1] })
        // Latin letters are narrower; the space between two words gets the gap between them.
        val mixed = CharSpans.compute(listOf("a", "b", " ", "c", "d"), intArrayOf(1, 3, 5, 9, 11), 20.0, 200)
        assertEquals(25.0 to 45.0, mixed[1][0] to mixed[1][1])
        assertEquals(45.0 to 85.0, mixed[2][0] to mixed[2][1])
        assertEquals(85.0 to 105.0, mixed[3][0] to mixed[3][1])
        assertTrue(mixed.all { it[0] <= it[1] })
        assertTrue(CharSpans.compute(emptyList(), IntArray(0), 20.0, 200).isEmpty())
    }
}
