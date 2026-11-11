package com.szdict.recognition.ocr

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A rotated rectangle in image pixels: centre, size along/across the baseline, angle in radians. */
internal data class Region(
    val cx: Double,
    val cy: Double,
    val w: Double,
    val h: Double,
    val angle: Double,
    val score: Double,
    val proposals: Int = 1,
    val vertical: Boolean = false,
    val charHeight: Double = 0.0,
)

/** Axis-aligned box `[x0, y0, x1, y1]`. */
internal class Box(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    val width get() = x1 - x0
    val height get() = y1 - y0
    fun union(o: Box) = Box(min(x0, o.x0), min(y0, o.y0), max(x1, o.x1), max(y1, o.y1))
}

/**
 * Proposal decoding, line merging and reading order around the detector network. A Kotlin port of
 * the reference `geometry.js`; the rules replace Google's closed native layout stage.
 */
internal object Geometry {
    private val unit = arrayOf(doubleArrayOf(-1.0, -1.0), doubleArrayOf(1.0, -1.0), doubleArrayOf(1.0, 1.0), doubleArrayOf(-1.0, 1.0))

    /** Corners in order top-left, top-right, bottom-right, bottom-left (relative to the baseline). */
    fun corners(r: Region): Array<DoubleArray> {
        val c = cos(r.angle)
        val s = sin(r.angle)
        return Array(4) { i ->
            val x = unit[i][0]
            val y = unit[i][1]
            doubleArrayOf(r.cx + x * r.w * c / 2 - y * r.h * s / 2, r.cy + x * r.w * s / 2 + y * r.h * c / 2)
        }
    }

    fun bounds(r: Region): Box {
        val p = corners(r)
        return Box(p.minOf { it[0] }, p.minOf { it[1] }, p.maxOf { it[0] }, p.maxOf { it[1] })
    }

    /** Intersection area over the smaller box's area. */
    fun overlap(a: Box, b: Box): Double {
        val intersection = max(0.0, min(a.x1, b.x1) - max(a.x0, b.x0)) * max(0.0, min(a.y1, b.y1) - max(a.y0, b.y0))
        return intersection / max(1.0, min(a.width * a.height, b.width * b.height))
    }

    /**
     * Decodes the RPN head: per 16-px cell `[objectness, dx, dy, log w, log h, cos, sin]` against
     * 32-px anchors. [sx]/[sy] map detector pixels back to image pixels.
     */
    fun decodeDetector(
        raw: ByteArray,
        rows: Int,
        cols: Int,
        scale: Double,
        zeroPoint: Int,
        sx: Double,
        sy: Double,
        threshold: Double = 0.65,
    ): List<Region> {
        require(raw.size >= rows * cols * 7) { "Unexpected detector output contract" }
        val result = ArrayList<Region>()
        val v = DoubleArray(7)
        for (y in 0 until rows) for (x in 0 until cols) {
            val k = (y * cols + x) * 7
            for (j in 0 until 7) v[j] = ((raw[k + j].toInt() and 0xFF) - zeroPoint) * scale
            val score = 1 / (1 + exp(-v[0]))
            if (score < threshold) continue
            val cx = ((x + .5) * 16 + v[1] * 32) * sx
            val cy = ((y + .5) * 16 + v[2] * 32) * sy
            val angle = atan2(v[6] * sy, v[5] * sx)
            result.add(
                Region(cx, cy, 32 * exp(v[3].coerceIn(-3.0, 4.0)) * sx, 32 * exp(v[4].coerceIn(-3.0, 4.0)) * sy, angle, score),
            )
        }
        return result
    }

    /** Joins proposals lying on one baseline into line rectangles and drops contained duplicates. */
    fun mergeProposals(proposals: List<Region>): List<Region> {
        val list = proposals.filter { it.w > 2 && it.h > 3 && it.angle.isFinite() }
        val parent = IntArray(list.size) { it }
        fun find(start: Int): Int {
            var i = start
            while (parent[i] != i) {
                parent[i] = parent[parent[i]]
                i = parent[i]
            }
            return i
        }
        for (i in list.indices) for (j in i + 1 until list.size) {
            val a = list[i]
            val b = list[j]
            if (abs(a.angle - b.angle) > .22) continue
            val angle = (a.angle + b.angle) / 2
            val c = cos(angle)
            val s = sin(angle)
            val dx = b.cx - a.cx
            val dy = b.cy - a.cy
            val along = abs(dx * c + dy * s)
            val normal = abs(-dx * s + dy * c)
            if (normal < min(a.h, b.h) * .38 && along < (a.w + b.w) / 2 + min(a.h, b.h) * .7) parent[find(j)] = find(i)
        }
        val groups = LinkedHashMap<Int, MutableList<Region>>()
        for (i in list.indices) groups.getOrPut(find(i)) { ArrayList() }.add(list[i])
        val merged = groups.values.map { items ->
            val mass = items.sumOf { it.score }
            val cx = items.sumOf { it.cx * it.score } / mass
            val cy = items.sumOf { it.cy * it.score } / mass
            var angle = items.sumOf { it.angle * it.score } / mass
            // Fit the baseline to centres to improve on the quantised angle resolution.
            if (items.size >= 4) {
                var xx = 0.0
                var xy = 0.0
                var yy = 0.0
                for (p in items) {
                    xx += (p.cx - cx) * (p.cx - cx) * p.score
                    xy += (p.cx - cx) * (p.cy - cy) * p.score
                    yy += (p.cy - cy) * (p.cy - cy) * p.score
                }
                val fitted = .5 * atan2(2 * xy, xx - yy)
                if (xx > yy * 2 && abs(fitted - angle) < .18) angle = fitted
            }
            val c = cos(angle)
            val s = sin(angle)
            var lo = Double.POSITIVE_INFINITY
            var hi = Double.NEGATIVE_INFINITY
            var top = Double.POSITIVE_INFINITY
            var bottom = Double.NEGATIVE_INFINITY
            for (p in items) for (corner in corners(p)) {
                val u = (corner[0] - cx) * c + (corner[1] - cy) * s
                val v = -(corner[0] - cx) * s + (corner[1] - cy) * c
                lo = min(lo, u); hi = max(hi, u); top = min(top, v); bottom = max(bottom, v)
            }
            val u = (lo + hi) / 2
            val v = (top + bottom) / 2
            Region(cx + u * c - v * s, cy + u * s + v * c, hi - lo, bottom - top, angle, mass / items.size, items.size)
        }.sortedByDescending { it.score }
        val kept = ArrayList<Region>()
        val keptBounds = ArrayList<Box>()
        merged.forEachIndexed { i, r ->
            val rb = bounds(r)
            // The reference compares against every higher-scored region, kept or not.
            var dominated = false
            for (j in 0 until i) {
                val p = merged[j]
                if (abs(p.angle - r.angle) < .2 && overlap(bounds(p), rb) > .75) { dominated = true; break }
            }
            if (!dominated) { kept.add(r); keptBounds.add(rb) }
        }
        return kept
    }

    /**
     * Folds a glyph-sized fragment into the line it continues. The detector often reports a trailing
     * full stop or comma as its own tiny region; read alone it has no context and is misrecognised.
     */
    fun absorbFragments(regions: List<Region>): List<Region> {
        val out = regions.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            search@ for (r in out) {
                for (p in out) {
                    if (p === r || r.proposals > 2 || p.w < r.w * 3 || r.w > p.h * .8 || r.h > p.h * 1.2 || abs(p.angle - r.angle) > .2) continue
                    val c = cos(p.angle)
                    val s = sin(p.angle)
                    val du = (r.cx - p.cx) * c + (r.cy - p.cy) * s
                    val dv = -(r.cx - p.cx) * s + (r.cy - p.cy) * c
                    val gap = abs(du) - (p.w + r.w) / 2
                    if (abs(dv) > p.h * .35 || gap > p.h * .9 || gap < -r.w) continue
                    val lo = min(-p.w / 2, du - r.w / 2)
                    val hi = max(p.w / 2, du + r.w / 2)
                    val shift = (lo + hi) / 2
                    out[out.indexOf(p)] = p.copy(cx = p.cx + shift * c, cy = p.cy + shift * s, w = hi - lo)
                    out.remove(r)
                    changed = true
                    break@search
                }
            }
        }
        return out
    }

    /**
     * Groups near-square upright regions stacked above each other into vertical columns. With
     * [explicit] false only clear columns (three or more glyph boxes, tall aspect) are converted.
     */
    fun verticalColumns(regions: List<Region>, explicit: Boolean = false): List<Region> {
        val candidates = regions.filter { it.w < it.h * 1.7 && abs(it.angle) < .18 }
        val used = HashSet<Region>()
        val columns = ArrayList<Region>()
        for (r in candidates) {
            if (r in used) continue
            val group = candidates.filter { it !in used && abs(it.cx - r.cx) < min(it.w, r.w) * .5 }.toMutableList()
            var x0 = group.minOf { it.cx - it.w / 2 }
            var y0 = group.minOf { it.cy - it.h / 2 }
            var x1 = group.maxOf { it.cx + it.w / 2 }
            var y1 = group.maxOf { it.cy + it.h / 2 }
            if (group.size >= 2) {
                // A glyph the detector only partly covered still belongs to the column: take in any
                // region lying mostly inside the column's x range and touching it vertically.
                val pitch = group.map { it.h }.sorted()[group.size / 2]
                var grew = true
                while (grew) {
                    grew = false
                    for (p in regions) {
                        if (p in used || p in group || abs(p.angle) >= .18 || p.w > (x1 - x0) * 1.3) continue
                        val inside = min(x1, p.cx + p.w / 2) - max(x0, p.cx - p.w / 2)
                        val gap = max(p.cy - p.h / 2 - y1, y0 - (p.cy + p.h / 2))
                        if (inside > p.w * .6 && gap < pitch * .6) {
                            group.add(p)
                            y0 = min(y0, p.cy - p.h / 2)
                            y1 = max(y1, p.cy + p.h / 2)
                            grew = true
                        }
                    }
                }
            }
            if (!explicit && (group.size < 3 || y1 - y0 < (x1 - x0) * 2.5)) continue
            used.addAll(group)
            columns.add(
                Region(
                    (x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0, 0.0,
                    group.sumOf { it.score } / group.size,
                    group.size, vertical = true,
                    charHeight = group.map { it.h }.sorted()[group.size / 2],
                ),
            )
        }
        return regions.filter { it !in used } + columns
    }

    /** Sorts lines into reading order, recursively splitting on clear column gutters. */
    fun <T> readingOrder(lines: List<T>, vertical: Boolean, box: (T) -> Box): List<T> {
        if (lines.size < 2) return lines
        if (vertical) {
            // Columns right to left, glyph runs top to bottom inside a column.
            val sorted = lines.sortedByDescending { box(it).x0 }
            val out = ArrayList<T>()
            var column = ArrayList<T>()
            for (item in sorted) {
                if (column.isNotEmpty()) {
                    val first = box(column.first())
                    val b = box(item)
                    if (abs(first.x0 - b.x0) > min(first.width, b.width) * .5) {
                        out.addAll(column.sortedBy { box(it).y0 })
                        column = ArrayList()
                    }
                }
                column.add(item)
            }
            out.addAll(column.sortedBy { box(it).y0 })
            return out
        }
        fun rows(items: List<T>): List<T> {
            // Row-major with a tolerance; done by bucketing so the ordering stays a consistent total order.
            val sorted = items.sortedBy { box(it).y0 }
            val out = ArrayList<T>()
            var row = ArrayList<T>()
            for (item in sorted) {
                if (row.isNotEmpty()) {
                    val first = box(row.first())
                    val b = box(item)
                    if (abs(first.y0 - b.y0) > min(first.height, b.height) * .45) {
                        out.addAll(row.sortedBy { box(it).x0 })
                        row = ArrayList()
                    }
                }
                row.add(item)
            }
            out.addAll(row.sortedBy { box(it).x0 })
            return out
        }
        fun split(items: List<T>, depth: Int): List<T> {
            if (items.size < 2 || depth > 8) return items
            val leftEdge = items.minOf { box(it).x0 }
            val rightEdge = items.maxOf { box(it).x1 }
            val wide = items.filter { box(it).width > (rightEdge - leftEdge) * .7 }
            val body = items.filter { it !in wide }
            if (wide.isNotEmpty() && body.size > 1) {
                val top = body.minOf { box(it).y0 }
                val bottom = body.maxOf { box(it).y1 }
                val headers = wide.filter { box(it).y1 <= top }
                val footers = wide.filter { box(it).y0 >= bottom }
                if (headers.size + footers.size == wide.size) {
                    return headers.sortedBy { box(it).y0 } + split(body, depth + 1) + footers.sortedBy { box(it).y0 }
                }
            }
            val spans = items.map { doubleArrayOf(box(it).x0, box(it).x1) }.sortedBy { it[0] }
            var edge = spans[0][1]
            var gapX = Double.NaN
            var gapSize = 0.0
            val height = items.sumOf { box(it).height } / items.size
            for (span in spans.drop(1)) {
                if (span[0] - edge > height * 1.2 && span[0] - edge > gapSize) {
                    gapX = (span[0] + edge) / 2
                    gapSize = span[0] - edge
                }
                edge = max(edge, span[1])
            }
            if (!gapX.isNaN()) {
                val left = items.filter { box(it).x1 <= gapX }
                val right = items.filter { box(it).x0 >= gapX }
                if (left.size + right.size == items.size) return split(left, depth + 1) + split(right, depth + 1)
            }
            return rows(items)
        }
        return split(lines, 0)
    }
}
