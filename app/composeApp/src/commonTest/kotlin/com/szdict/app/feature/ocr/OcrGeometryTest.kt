package com.szdict.app.feature.ocr

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.szdict.recognition.OcrAlt
import com.szdict.recognition.OcrChar
import com.szdict.recognition.OcrLine
import com.szdict.recognition.Point
import com.szdict.recognition.Quad
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OcrGeometryTest {
    private fun rotate(q: Quad, degrees: Double, cx: Float, cy: Float): Quad {
        val r = Math.toRadians(degrees)
        fun p(point: Point): Point {
            val dx = point.x - cx
            val dy = point.y - cy
            return Point((cx + dx * cos(r) - dy * sin(r)).toFloat(), (cy + dx * sin(r) + dy * cos(r)).toFloat())
        }
        return Quad(p(q.topLeft), p(q.topRight), p(q.bottomRight), p(q.bottomLeft))
    }

    private fun rotated(line: OcrLine, degrees: Double): OcrLine =
        line.copy(box = rotate(line.box, degrees, 0f, 0f), chars = line.chars.map { it.copy(box = rotate(it.box, degrees, 0f, 0f)) })

    @Test
    fun paddedLineEnclosesTheSelectedGlyphWithoutStraighteningTheLine() {
        val line = listOf(Offset(0f, 0f), Offset(80f, 40f), Offset(72f, 56f), Offset(-8f, 16f))
        val glyphOutline = Rect(-2f, -7f, 22f, 22f)
        val points = paddedLineOutline(line, glyphOutline, gap = 4f)
        val quad = points.map { Point(it.x, it.y) }.let { Quad(it[0], it[1], it[2], it[3]) }
        for (p in line + listOf(glyphOutline.topLeft, glyphOutline.topRight, glyphOutline.bottomRight, glyphOutline.bottomLeft)) {
            assertTrue(quad.contains(p.x, p.y))
        }
        val edge = points[1] - points[0]
        assertEquals(0.5f, edge.y / edge.x, 0.001f)
    }

    @Test
    fun aPointInsideAGlyphSelectsItOnWhateverLine() {
        val lines = listOf(line("你好", y = 0f), line("我们", y = 40f))
        assertEquals(CharRef(0, 1), hitTestChar(lines, 15f, 5f, 0f))
        assertEquals(CharRef(1, 0), hitTestChar(lines, 2f, 49f, 0f))
    }

    @Test
    fun aNearMissSelectsTheNearestCentreWithinSlopOnly() {
        val lines = listOf(line("你好", y = 0f), line("我们", y = 40f))
        // Between the lines, closer to the second (CAM-125).
        assertEquals(CharRef(1, 1), hitTestChar(lines, 16f, 33f, 15f))
        assertNull(hitTestChar(lines, 16f, 25f, 5f))
        assertNull(hitTestChar(lines, 200f, 200f, 24f))
    }

    @Test
    fun rotatedQuadsUseTheirOwnWinding() {
        for (degrees in listOf(30.0, 90.0, 180.0, 250.0)) {
            val lines = listOf(rotated(line("你好学"), degrees))
            for (index in 0..2) {
                val c = lines[0].chars[index].box.center
                assertEquals(CharRef(0, index), hitTestChar(lines, c.x, c.y, 0f), "centre of glyph $index at $degrees°")
            }
            // The axis-aligned position of the unrotated glyph no longer hits (except by slop).
            val r = Math.toRadians(degrees)
            val far = Point((45 * cos(r) - 30 * sin(r)).toFloat(), (45 * sin(r) + 30 * cos(r)).toFloat())
            assertNull(hitTestChar(lines, far.x, far.y, 0f))
        }
        // A mirrored (counter-clockwise) quad still contains its centre.
        val q = box(0f, 0f, 10f, 10f)
        val mirrored = Quad(q.topRight, q.topLeft, q.bottomLeft, q.bottomRight)
        val lines = listOf(OcrLine("你", mirrored, listOf(OcrChar("你", mirrored, 1f, listOf(OcrAlt("你", 1f))))))
        assertEquals(CharRef(0, 0), hitTestChar(lines, 5f, 5f, 0f))
    }

    @Test
    fun whitespaceAndGlyphsWithoutGeometryAreNeverSelected() {
        val spaced = line("你 好")
        assertNull(hitTestChar(listOf(spaced), 15f, 5f, 4f))
        assertEquals(CharRef(0, 2), hitTestChar(listOf(spaced), 17f, 5f, 12f))
        val flat = Quad(Point(5f, 5f), Point(5f, 5f), Point(5f, 5f), Point(5f, 5f))
        val noGeometry = OcrLine("你", flat, listOf(OcrChar("你", flat, 1f, listOf(OcrAlt("你", 1f)))))
        assertNull(hitTestChar(listOf(noGeometry), 5f, 5f, 24f))
        assertEquals(1, firstSelectableOffset(" 你"))
        assertNull(firstSelectableOffset(" \t"))
    }

    @Test
    fun viewAndImageCoordinatesRoundTripThroughZoomAndLetterbox() {
        // A 200x100 image in a 400x400 view: fit scale 2, letterboxed 100 px top and bottom.
        val viewport = ImageViewport(200, 100, 400f, 400f)
        assertEquals(2f, viewport.fitScale)
        assertEquals(Offset(0f, 100f), viewport.letterbox)
        assertEquals(Offset(100f, 200f), viewport.toView(Offset(50f, 50f), 1f, Offset.Zero))
        val translation = Offset(-120f, -300f)
        val view = viewport.toView(Offset(30f, 70f), 3f, translation)
        val back = viewport.toImage(view, 3f, translation)
        assertEquals(30f, back.x, 1e-3f)
        assertEquals(70f, back.y, 1e-3f)
        // Constant on-screen tolerance.
        assertEquals(4f, viewport.slop(24f, 3f))
    }

    @Test
    fun zoomKeepsTheCentroidAndNeverLeavesGaps() {
        val viewport = ImageViewport(200, 100, 400f, 400f)
        val centroid = Offset(300f, 200f)
        val before = viewport.toImage(centroid, 1f, Offset.Zero)
        val (zoom, translation) = viewport.transform(1f, Offset.Zero, centroid, Offset.Zero, 2f)
        assertEquals(2f, zoom)
        val after = viewport.toImage(centroid, zoom, translation)
        assertEquals(before.x, after.x, 1e-3f)
        assertEquals(before.y, after.y, 1e-3f)
        // Limits: 1x … 8x, and the content always covers the viewport.
        assertEquals(8f, viewport.transform(4f, Offset.Zero, centroid, Offset.Zero, 10f).first)
        assertEquals(1f to Offset.Zero, viewport.transform(1f, Offset.Zero, centroid, Offset(50f, 50f), 0.5f))
        assertEquals(Offset(-400f, 0f), viewport.clamp(Offset(-900f, 30f), 2f))
        // Double tap: 1x → 2.5x toward the tap, and back.
        val (z, t) = viewport.doubleTap(1f, Offset(100f, 100f))
        assertEquals(2.5f, z)
        assertEquals(Offset(-150f, -150f), t)
        assertEquals(1f to Offset.Zero, viewport.doubleTap(z, Offset(10f, 10f)))
    }

    @Test
    fun centringAGlyphClampsAtTheEdges() {
        val viewport = ImageViewport(200, 100, 400f, 400f)
        val centred = viewport.centreOn(Offset(100f, 50f), 2f)
        assertEquals(Offset(200f, 200f), viewport.toView(Offset(100f, 50f), 2f, centred))
        // A corner glyph cannot be centred without a gap: the translation clamps.
        assertEquals(Offset(0f, 0f), viewport.centreOn(Offset(0f, 0f), 2f))
        assertFalse(ImageViewport(0, 0, 400f, 400f).valid)
    }

    @Test
    fun shrinkingTheViewerKeepsTheZoomedCentreAndRevealsTheSelectedGlyphIfNeeded() {
        val wide = ImageViewport(200, 100, 400f, 400f)
        val narrow = ImageViewport(200, 100, 200f, 400f)
        val zoom = 2f
        val translation = Offset(-200f, -200f)
        val centre = wide.toImage(Offset(200f, 200f), zoom, translation)
        val resized = narrow.translationAfterResize(wide, zoom, translation, selected = null)
        assertEquals(centre, narrow.toImage(Offset(100f, 200f), zoom, resized))

        val glyph = Offset(149f, 50f)
        assertTrue(narrow.toView(glyph, zoom, resized).x > 190f)
        val showingGlyph = narrow.translationAfterResize(wide, zoom, translation, glyph)
        assertEquals(184f, narrow.toView(glyph, zoom, showingGlyph).x, 0.001f)
        assertEquals(resized.y, showingGlyph.y)
    }

    @Test
    fun aCloseRunnerUpMarksTheCharacterUncertain() {
        fun char(vararg scores: Float) = OcrChar("己", box(0f, 0f, 1f, 1f), scores[0], scores.mapIndexed { i, s -> OcrAlt("己已巳"[i].toString(), s) })
        assertTrue(char(0.5f, 0.4f).isUncertain())
        assertFalse(char(0.9f, 0.05f).isUncertain())
        assertFalse(char(1f).isUncertain())
    }
}
