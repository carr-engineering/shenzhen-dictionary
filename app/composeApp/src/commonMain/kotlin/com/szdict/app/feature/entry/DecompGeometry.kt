package com.szdict.app.feature.entry

import com.szdict.core.api.DecompNode
import com.szdict.core.api.PartHighlight
import com.szdict.core.api.Region
import engineering.carr.hanzianimator.core.OutlineCommand
import engineering.carr.hanzianimator.core.StrokeOrder
import engineering.carr.hanzianimator.core.StrokeOutline
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Pure geometry for the Characters section (ENT-128, ENT-129, ARCH-200). The engine decides which
 * strokes or region a part occupies; this file maps that onto the drawn glyph: saved-state keys,
 * the parts currently shown in the tree, tap hit-testing and the connector anchor.
 */

/** Saved-state key of a part: its position path (ENT-120). The top-level character is `""`. */
internal fun pathKey(path: List<Int>): String = path.joinToString(".")

/** Part colour slot (`part1`…`part3`): the component's position among its siblings (ENT-128). */
internal fun partColorIndex(node: DecompNode): Int = node.path.lastOrNull() ?: 0

/** Components currently shown as rows: the immediate ones always, deeper ones under expanded parts. */
internal fun shownParts(root: DecompNode, expanded: Set<String>): List<DecompNode> {
    val out = ArrayList<DecompNode>()
    fun walk(node: DecompNode) {
        for (child in node.children) {
            out += child
            if (pathKey(child.path) in expanded) walk(child)
        }
    }
    walk(root)
    return out
}

internal fun findPart(root: DecompNode, key: String): DecompNode? {
    if (pathKey(root.path) == key) return root
    for (child in root.children) findPart(child, key)?.let { return it }
    return null
}

/** Drops saved paths that no longer exist after a script change (ENT-124). */
internal fun pruneKeys(keys: Set<String>, root: DecompNode?): Set<String> {
    if (root == null) return emptySet()
    return keys.filterTo(LinkedHashSet()) { findPart(root, it) != null }
}

/** A selection survives only while its row is still shown. */
internal fun pruneSelection(selected: String?, root: DecompNode?, expanded: Set<String>): String? {
    if (selected == null || root == null) return null
    return selected.takeIf { key -> shownParts(root, expanded).any { pathKey(it.path) == key } }
}

internal fun regionContains(region: Region, x: Float, y: Float): Boolean =
    x >= region.left && x <= region.right && y >= region.top && y <= region.bottom

/** Approximate region of a part: its box minus the cut-out of an enclosing part (ENT-129). */
internal fun highlightContains(highlight: PartHighlight, x: Float, y: Float): Boolean =
    regionContains(highlight.region, x, y) && highlight.cutout?.let { !regionContains(it, x, y) } != false

/**
 * The deepest shown part under a tap (ENT-128). [x]/[y] are in the glyph's unit box. A part whose
 * stroke indices are usable ([exact]) is hit through [strokeHit] (is the tap on stroke `index`?),
 * any other part through its approximate region. Returns null when the point is inside no part.
 */
internal fun deepestPartAt(
    parts: List<DecompNode>,
    x: Float,
    y: Float,
    exact: (List<Int>?) -> Boolean,
    strokeHit: (Int) -> Boolean,
): DecompNode? {
    var best: DecompNode? = null
    for (part in parts) {
        val strokes = part.highlight.strokes
        val hit = if (exact(strokes)) strokes!!.any(strokeHit) else highlightContains(part.highlight, x, y)
        if (hit && (best == null || part.path.size > best.path.size)) best = part
    }
    return best
}

/** Tapping the selected part again, or a point inside no part, clears the selection (ENT-128). */
internal fun nextSelection(current: String?, tapped: DecompNode?): String? {
    val key = tapped?.let { pathKey(it.path) } ?: return null
    return if (key == current) null else key
}

internal data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** A [Region] (unit box) placed in a square of [side] at [left]/[top]. */
internal fun regionBox(region: Region, left: Float, top: Float, side: Float): Box =
    Box(left + region.left * side, top + region.top * side, left + region.right * side, top + region.bottom * side)

/** Point of [box] nearest to ([x], [y]) — where the connector meets the region (ENT-128). */
internal fun nearestPointOn(box: Box, x: Float, y: Float): Pair<Float, Float> =
    x.coerceIn(box.left, box.right) to y.coerceIn(box.top, box.bottom)

/** A stroke outline flattened to closed polygons in glyph units (y-down, 1024 box). */
internal class FlatStroke(val contours: List<FloatArray>) {
    val bounds: Box? = run {
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (c in contours) {
            var i = 0
            while (i + 1 < c.size) {
                l = min(l, c[i]); r = max(r, c[i])
                t = min(t, c[i + 1]); b = max(b, c[i + 1])
                i += 2
            }
        }
        if (l <= r && t <= b) Box(l, t, r, b) else null
    }

    /** Even-odd containment across all contours. */
    fun contains(x: Float, y: Float): Boolean {
        var inside = false
        for (c in contours) {
            val n = c.size / 2
            if (n < 3) continue
            var j = n - 1
            for (i in 0 until n) {
                val xi = c[i * 2]
                val yi = c[i * 2 + 1]
                val xj = c[j * 2]
                val yj = c[j * 2 + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }

    /** Distance from the point to the nearest outline edge (0 when inside). */
    fun distanceTo(x: Float, y: Float): Float {
        if (contains(x, y)) return 0f
        var best = Float.POSITIVE_INFINITY
        for (c in contours) {
            val n = c.size / 2
            if (n < 2) continue
            var j = n - 1
            for (i in 0 until n) {
                best = min(best, segmentDistance(x, y, c[j * 2], c[j * 2 + 1], c[i * 2], c[i * 2 + 1]))
                j = i
            }
        }
        return best
    }
}

private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 <= 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
    val cx = ax + dx * t - px
    val cy = ay + dy * t - py
    return sqrt(cx * cx + cy * cy)
}

/** Flattens an outline command buffer; curves are sampled with [steps] segments. */
internal fun flattenOutline(outline: StrokeOutline, steps: Int = 6): FlatStroke {
    val contours = ArrayList<FloatArray>()
    var current = ArrayList<Float>()
    var at = 0
    var x = 0f
    var y = 0f
    fun next(): Float = outline.coordinateAt(at++)
    fun flush() {
        if (current.size >= 6) contours += current.toFloatArray()
        current = ArrayList()
    }
    for (index in 0 until outline.commandCount) {
        when (outline.commandAt(index)) {
            OutlineCommand.MOVE -> {
                flush()
                x = next(); y = next()
                current += x; current += y
            }
            OutlineCommand.LINE -> {
                x = next(); y = next()
                current += x; current += y
            }
            OutlineCommand.QUAD -> {
                val cx = next(); val cy = next(); val ex = next(); val ey = next()
                for (s in 1..steps) {
                    val t = s / steps.toFloat()
                    val u = 1f - t
                    current += u * u * x + 2f * u * t * cx + t * t * ex
                    current += u * u * y + 2f * u * t * cy + t * t * ey
                }
                x = ex; y = ey
            }
            OutlineCommand.CUBIC -> {
                val c1x = next(); val c1y = next(); val c2x = next(); val c2y = next(); val ex = next(); val ey = next()
                for (s in 1..steps) {
                    val t = s / steps.toFloat()
                    val u = 1f - t
                    current += u * u * u * x + 3f * u * u * t * c1x + 3f * u * t * t * c2x + t * t * t * ex
                    current += u * u * u * y + 3f * u * u * t * c1y + 3f * u * t * t * c2y + t * t * t * ey
                }
                x = ex; y = ey
            }
            OutlineCommand.CLOSE -> flush()
        }
    }
    flush()
    return FlatStroke(contours)
}

/** Hit-test data of a character's stroke graphics, built once per character. */
internal class GlyphStrokes(val order: StrokeOrder) {
    val flat: List<FlatStroke> = order.strokes.map { flattenOutline(it.outline) }

    /** True when ([x], [y]) in glyph units is on stroke [index] or within [tolerance] of it. */
    fun hit(index: Int, x: Float, y: Float, tolerance: Float): Boolean {
        val stroke = flat.getOrNull(index) ?: return false
        val b = stroke.bounds ?: return false
        if (x < b.left - tolerance || x > b.right + tolerance || y < b.top - tolerance || y > b.bottom + tolerance) return false
        return stroke.distanceTo(x, y) <= tolerance
    }

    /** Bounding box (glyph units) of the given strokes, or null when none is drawable. */
    fun boundsOf(indexes: List<Int>): Box? {
        var out: Box? = null
        for (i in indexes) {
            val b = flat.getOrNull(i)?.bounds ?: continue
            out = out?.let { Box(min(it.left, b.left), min(it.top, b.top), max(it.right, b.right), max(it.bottom, b.bottom)) } ?: b
        }
        return out
    }

    /** Exact stroke indices are usable only when they index into these graphics (ENT-129). */
    fun covers(indexes: List<Int>?): Boolean = indexes != null && indexes.isNotEmpty() && indexes.all { it in flat.indices }
}

/** Fill colour role of one stroke of the glyph (ENT-128). */
internal sealed interface StrokeTint {
    /** `fg` at full strength. */
    data object Ink : StrokeTint

    /** `fg` at 30 % — everything outside the active part. */
    data object Dimmed : StrokeTint

    /** Part colour [slot] (0…2) at full strength. */
    data class Part(val slot: Int) : StrokeTint
}

/**
 * Tint of every stroke: with an [active] part its strokes take its colour and the rest is dimmed;
 * at rest the immediate components show their colours and unmatched strokes stay ink.
 */
internal fun strokeTints(strokeCount: Int, root: DecompNode, active: DecompNode?, usable: (List<Int>?) -> Boolean): List<StrokeTint> {
    val out = MutableList<StrokeTint>(strokeCount) { if (active != null) StrokeTint.Dimmed else StrokeTint.Ink }
    if (active != null) {
        val strokes = active.highlight.strokes
        if (usable(strokes)) strokes!!.forEach { if (it in out.indices) out[it] = StrokeTint.Part(partColorIndex(active)) }
        return out
    }
    for (child in root.children) {
        val strokes = child.highlight.strokes
        if (usable(strokes)) strokes!!.forEach { if (it in out.indices) out[it] = StrokeTint.Part(partColorIndex(child)) }
    }
    return out
}
