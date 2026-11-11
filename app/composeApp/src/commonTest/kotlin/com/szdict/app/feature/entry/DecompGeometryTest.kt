package com.szdict.app.feature.entry

import com.szdict.core.api.DecompNode
import com.szdict.core.api.PartHighlight
import com.szdict.core.api.PartKind
import com.szdict.core.api.PartPosition
import com.szdict.core.api.Region
import engineering.carr.hanzianimator.core.GlyphPoint
import engineering.carr.hanzianimator.core.Stroke
import engineering.carr.hanzianimator.core.StrokeOrder
import engineering.carr.hanzianimator.core.StrokeOutline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecompGeometryTest {
    private val full = Region(0f, 0f, 1f, 1f)

    private fun node(
        glyph: String,
        path: List<Int>,
        region: Region,
        strokes: List<Int>? = null,
        cutout: Region? = null,
        position: PartPosition? = null,
        children: List<DecompNode> = emptyList(),
    ) = DecompNode(
        path = path, kind = PartKind.CHARACTER, glyph = glyph, mapsTo = null, position = position,
        operator = if (children.isEmpty()) null else "⿰", ids = "", isRoot = children.isEmpty(), card = null, entryIds = emptyList(),
        radicalName = null, svgPath = null, strokeCount = null, highlight = PartHighlight(strokes, region, cutout), children = children,
    )

    /** 想 → ⿱ 相 心; 相 → ⿰ 木 目 (approximate regions only). */
    private val xiang = node(
        "想", emptyList(), full,
        children = listOf(
            node(
                "相", listOf(0), Region(0f, 0f, 1f, 0.5f), position = PartPosition.TOP,
                children = listOf(
                    node("木", listOf(0, 0), Region(0f, 0f, 0.5f, 0.5f), position = PartPosition.LEFT),
                    node("目", listOf(0, 1), Region(0.5f, 0f, 1f, 0.5f), position = PartPosition.RIGHT),
                ),
            ),
            node("心", listOf(1), Region(0f, 0.5f, 1f, 1f), position = PartPosition.BOTTOM),
        ),
    )

    private val none: (List<Int>?) -> Boolean = { false }

    @Test
    fun pathKeysAndColourSlots() {
        assertEquals("", pathKey(emptyList()))
        assertEquals("0.1", pathKey(listOf(0, 1)))
        assertEquals(1, partColorIndex(xiang.children[0].children[1]))   // 目 takes part2 among its siblings
        assertEquals(0, partColorIndex(xiang))
    }

    @Test
    fun shownPartsFollowExpansion() {
        assertEquals(listOf("相", "心"), shownParts(xiang, emptySet()).map { it.glyph })
        assertEquals(listOf("相", "木", "目", "心"), shownParts(xiang, setOf("0")).map { it.glyph })
    }

    @Test
    fun tapSelectsDeepestShownPartByRegion() {
        // Collapsed: a tap in the upper-left quadrant selects 相; expanded: 木 (ENT-128).
        assertEquals("相", deepestPartAt(shownParts(xiang, emptySet()), 0.2f, 0.2f, none) { false }?.glyph)
        assertEquals("木", deepestPartAt(shownParts(xiang, setOf("0")), 0.2f, 0.2f, none) { false }?.glyph)
        assertEquals("目", deepestPartAt(shownParts(xiang, setOf("0")), 0.8f, 0.2f, none) { false }?.glyph)
        assertEquals("心", deepestPartAt(shownParts(xiang, setOf("0")), 0.5f, 0.9f, none) { false }?.glyph)
        assertNull(deepestPartAt(shownParts(xiang, emptySet()), 1.2f, 0.5f, none) { false })
    }

    @Test
    fun enclosureExcludesItsCutout() {
        // 国 → ⿴ 囗 玉: the enclosing part is the box minus the inner rectangle (ENT-129).
        val inner = Region(0.2f, 0.2f, 0.8f, 0.8f)
        val guo = node(
            "国", emptyList(), full,
            children = listOf(
                node("囗", listOf(0), full, cutout = inner, position = PartPosition.OUTER),
                node("玉", listOf(1), inner, position = PartPosition.INNER),
            ),
        )
        val parts = shownParts(guo, emptySet())
        assertEquals("玉", deepestPartAt(parts, 0.5f, 0.5f, none) { false }?.glyph)
        assertEquals("囗", deepestPartAt(parts, 0.05f, 0.5f, none) { false }?.glyph)
        assertTrue(highlightContains(guo.children[0].highlight, 0.1f, 0.1f))
        assertFalse(highlightContains(guo.children[0].highlight, 0.5f, 0.5f))
    }

    @Test
    fun exactStrokesWinOverRegionsWhenUsable() {
        // 休 → ⿰ 亻 木 with stroke matches: strokes 0-1 are 亻, 2-5 are 木.
        val xiu = node(
            "休", emptyList(), full,
            children = listOf(
                node("亻", listOf(0), Region(0f, 0f, 0.5f, 1f), strokes = listOf(0, 1), position = PartPosition.LEFT),
                node("木", listOf(1), Region(0.5f, 0f, 1f, 1f), strokes = listOf(2, 3, 4, 5), position = PartPosition.RIGHT),
            ),
        )
        val parts = shownParts(xiu, emptySet())
        val all: (List<Int>?) -> Boolean = { it != null }
        // The tap is geometrically in the left half but lands on stroke 3 (木 reaching left).
        assertEquals("木", deepestPartAt(parts, 0.4f, 0.5f, all) { it == 3 }?.glyph)
        // On no stroke at all: inside no part, which clears the selection.
        assertNull(deepestPartAt(parts, 0.4f, 0.5f, all) { false })
        // Without usable stroke graphics the region fallback applies.
        assertEquals("亻", deepestPartAt(parts, 0.4f, 0.5f, none) { it == 3 }?.glyph)

        assertEquals(
            listOf(StrokeTint.Part(0), StrokeTint.Part(0), StrokeTint.Part(1), StrokeTint.Part(1), StrokeTint.Part(1), StrokeTint.Part(1), StrokeTint.Ink),
            strokeTints(7, xiu, null, all),   // rest state: immediate components coloured, unmatched strokes ink
        )
        assertEquals(
            listOf(StrokeTint.Dimmed, StrokeTint.Dimmed, StrokeTint.Part(1), StrokeTint.Part(1), StrokeTint.Part(1), StrokeTint.Part(1), StrokeTint.Dimmed),
            strokeTints(7, xiu, xiu.children[1], all),   // selected part at full strength, the rest at 30 %
        )
        assertTrue(strokeTints(7, xiu, xiu.children[1], none).all { it == StrokeTint.Dimmed })
    }

    @Test
    fun selectionTogglesAndIsPruned() {
        val xiangTop = xiang.children[0]
        assertEquals("0", nextSelection(null, xiangTop))
        assertNull(nextSelection("0", xiangTop))     // tapping the selected part again clears
        assertNull(nextSelection("0", null))         // a point inside no part clears
        assertEquals(setOf("0"), pruneKeys(setOf("0", "0.7", "3"), xiang))   // ENT-124
        assertEquals(emptySet(), pruneKeys(setOf("0"), null))
        assertEquals("0.1", pruneSelection("0.1", xiang, setOf("0")))
        assertNull(pruneSelection("0.1", xiang, emptySet()))   // its row is no longer shown
    }

    @Test
    fun regionBoxAndConnectorAnchor() {
        val box = regionBox(Region(0.5f, 0f, 1f, 0.5f), left = 10f, top = 20f, side = 100f)
        assertEquals(Box(60f, 20f, 110f, 70f), box)
        // The connector meets the nearest edge of the region (ENT-128).
        assertEquals(60f to 70f, nearestPointOn(box, 0f, 200f))
        assertEquals(80f to 70f, nearestPointOn(box, 80f, 200f))
        assertEquals(80f to 40f, nearestPointOn(box, 80f, 40f))
    }

    @Test
    fun flattenedOutlineHitTesting() {
        // A square with a curved top edge, in y-down glyph units.
        val outline = StrokeOutline.Builder()
            .moveTo(100f, 100f).quadTo(200f, 60f, 300f, 100f).lineTo(300f, 300f).lineTo(100f, 300f).close().build()
        val flat = flattenOutline(outline)
        assertEquals(1, flat.contours.size)
        assertTrue(flat.contains(200f, 200f))
        assertTrue(flat.contains(200f, 90f))       // inside the bulge of the curve
        assertFalse(flat.contains(200f, 70f))
        assertFalse(flat.contains(50f, 200f))
        assertEquals(0f, flat.distanceTo(200f, 200f))
        assertEquals(50f, flat.distanceTo(50f, 200f), 0.01f)
        val bounds = flat.bounds!!
        assertEquals(100f, bounds.left); assertEquals(300f, bounds.right); assertEquals(300f, bounds.bottom)
        assertTrue(bounds.top in 79f..81f)

        val median = listOf(GlyphPoint(110f, 200f), GlyphPoint(290f, 200f))
        val order = StrokeOrder("一", listOf(Stroke(0, outline, median, isRadical = false)))
        val strokes = GlyphStrokes(order)
        assertTrue(strokes.hit(0, 200f, 200f, tolerance = 0f))
        assertTrue(strokes.hit(0, 80f, 200f, tolerance = 30f))     // finger-sized tolerance
        assertFalse(strokes.hit(0, 40f, 200f, tolerance = 30f))
        assertFalse(strokes.hit(5, 200f, 200f, tolerance = 30f))
        assertTrue(strokes.covers(listOf(0)))
        assertFalse(strokes.covers(listOf(0, 1)))   // indices beyond the graphics → region fallback
        assertFalse(strokes.covers(null))
        assertEquals(bounds, strokes.boundsOf(listOf(0)))
    }
}
