package com.szdict.recognition.ocr2

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** A detected text line: corners top-left, top-right, bottom-right, bottom-left (x0, y0, ... x3, y3). */
internal class TextBox(val points: FloatArray, val score: Float)

/**
 * Turns the detector's text probability map into line boxes (the post-processing of Differentiable
 * Binarization as PaddleOCR and RapidOCR do it with OpenCV, pyclipper and Shapely):
 *
 *  1. pixels above the pixel threshold form regions; every border of a region (its outline and the
 *     outline of each hole) is a candidate, exactly the contours OpenCV's `findContours` lists;
 *  2. the candidate's minimum-area rectangle is scored by the mean probability inside it and dropped
 *     under the box threshold ("fast" scoring);
 *  3. the rectangle is grown by `area x unclipRatio / perimeter` with round corners (the detector is
 *     trained on shrunk text kernels) and the minimum-area rectangle of the result is the line box;
 *  4. boxes are scaled to the image, ordered clockwise from the top-left corner, and sorted top to
 *     bottom, left to right within a row.
 */
internal object DbPostProcess {
    private const val MIN_SIZE = 3.0

    /**
     * @param map `height x width` probabilities.
     * @param imageWidth size of the image the map was computed from (the map is that image resized).
     */
    fun boxes(
        map: FloatArray,
        width: Int,
        height: Int,
        imageWidth: Int,
        imageHeight: Int,
        pixelThreshold: Float,
        boxThreshold: Float,
        unclipRatio: Double,
        maxCandidates: Int,
    ): List<TextBox> {
        val out = ArrayList<TextBox>()
        for (border in borders(map, width, height, pixelThreshold, maxCandidates)) {
            val kernel = minAreaRect(border) ?: continue
            if (kernel.shortSide < MIN_SIZE) continue
            val score = boxScore(map, width, height, kernel.corners)
            if (boxThreshold > score) continue
            val grown = unclip(kernel.corners, unclipRatio)
            val box = minAreaRect(grown) ?: continue
            if (box.shortSide < MIN_SIZE + 2) continue
            val p = box.corners
            for (i in 0 until 4) {
                p[i * 2] = round(p[i * 2] / width * imageWidth).coerceIn(0.0, imageWidth.toDouble())
                p[i * 2 + 1] = round(p[i * 2 + 1] / height * imageHeight).coerceIn(0.0, imageHeight.toDouble())
            }
            val ordered = clockwise(p)
            for (i in 0 until 4) {
                ordered[i * 2] = ordered[i * 2].coerceIn(0f, (imageWidth - 1).toFloat())
                ordered[i * 2 + 1] = ordered[i * 2 + 1].coerceIn(0f, (imageHeight - 1).toFloat())
            }
            if (distance(ordered, 0, 1).toInt() <= 3 || distance(ordered, 0, 3).toInt() <= 3) continue
            out.add(TextBox(ordered, score.toFloat()))
        }
        return sort(out)
    }

    internal fun distance(p: FloatArray, a: Int, b: Int): Double {
        val dx = (p[a * 2] - p[b * 2]).toDouble()
        val dy = (p[a * 2 + 1] - p[b * 2 + 1]).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * The pixels of every border between a region of text pixels (8-connected) and a region of
     * background pixels (4-connected; everything outside the map is background): one border per pair
     * of touching regions, each given as x, y pairs of the text pixels on it. Later-scanned regions
     * come first, as from `findContours`.
     */
    internal fun borders(map: FloatArray, width: Int, height: Int, threshold: Float, limit: Int = Int.MAX_VALUE): List<IntArray> {
        val n = width * height
        // 0 = unvisited; text pixels get 1; background pixels get their region's number (>= 2).
        val label = IntArray(n)
        val text = BooleanArray(n) { map[it] > threshold }
        // Background regions touching the map's edge are one region with the outside.
        val outside = 2
        var regions = 2
        var pending = IntArray(4096)
        fun floodBackground(start: Int, id: Int) {
            var top = 0
            pending[top++] = start
            label[start] = id
            while (top > 0) {
                val p = pending[--top]
                if (top + 4 > pending.size) pending = pending.copyOf(pending.size * 2)
                val x = p % width
                if (x > 0 && !text[p - 1] && label[p - 1] == 0) { label[p - 1] = id; pending[top++] = p - 1 }
                if (x < width - 1 && !text[p + 1] && label[p + 1] == 0) { label[p + 1] = id; pending[top++] = p + 1 }
                if (p >= width && !text[p - width] && label[p - width] == 0) { label[p - width] = id; pending[top++] = p - width }
                if (p < n - width && !text[p + width] && label[p + width] == 0) { label[p + width] = id; pending[top++] = p + width }
            }
        }
        for (x in 0 until width) {
            if (!text[x] && label[x] == 0) floodBackground(x, outside)
            val b = n - width + x
            if (!text[b] && label[b] == 0) floodBackground(b, outside)
        }
        for (y in 0 until height) {
            val l = y * width
            if (!text[l] && label[l] == 0) floodBackground(l, outside)
            val r = l + width - 1
            if (!text[r] && label[r] == 0) floodBackground(r, outside)
        }
        val out = ArrayList<IntArray>()
        // Border pixels of the region being traced: (background region, x, y) triples.
        var found = IntArray(256)
        var stack = IntArray(4096)
        for (start in 0 until n) {
            if (!text[start] || label[start] != 0) continue
            var count = 0
            var top = 0
            stack[top++] = start
            label[start] = 1
            while (top > 0) {
                val p = stack[--top]
                if (top + 8 > stack.size) stack = stack.copyOf(stack.size * 2)
                val x = p % width
                val y = p / width
                for (side in 0 until 4) {
                    val q = when (side) {
                        0 -> if (x > 0) p - 1 else -1
                        1 -> if (x < width - 1) p + 1 else -1
                        2 -> if (y > 0) p - width else -1
                        else -> if (y < height - 1) p + width else -1
                    }
                    val region = when {
                        q < 0 -> outside
                        text[q] -> continue
                        else -> {
                            if (label[q] == 0) floodBackground(q, ++regions)
                            label[q]
                        }
                    }
                    if (count + 3 > found.size) found = found.copyOf(found.size * 2)
                    found[count++] = region
                    found[count++] = x
                    found[count++] = y
                }
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= height) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= width) continue
                        val q = yy * width + xx
                        if (text[q] && label[q] == 0) { label[q] = 1; stack[top++] = q }
                    }
                }
            }
            // One border per neighbouring background region, the outline first.
            val ids = ArrayList<Int>()
            for (i in 0 until count step 3) if (found[i] !in ids) ids.add(found[i])
            ids.sort()
            for (id in ids) {
                var size = 0
                for (i in 0 until count step 3) if (found[i] == id) size++
                val points = IntArray(size * 2)
                var k = 0
                for (i in 0 until count step 3) if (found[i] == id) { points[k++] = found[i + 1]; points[k++] = found[i + 2] }
                out.add(points)
            }
        }
        out.reverse()
        return if (out.size > limit) out.subList(0, limit) else out
    }

    /** @property corners x, y pairs; @property shortSide length of the shorter side. */
    internal class Rect(val corners: DoubleArray, val shortSide: Double)

    /**
     * The rectangle of least area around the points (x, y pairs), or null without points.
     *
     * Small text kernels often have several rectangles of exactly the same area (an upright one and
     * tilted ones), and which of them is taken decides the line box. This is therefore OpenCV's
     * `minAreaRect` operation for operation: rotating calipers in single precision over the hull in
     * OpenCV's order, the last of equally small rectangles winning.
     */
    internal fun minAreaRect(points: IntArray): Rect? {
        val hull = convexHull(points)
        val n = hull.size / 2
        if (n == 0) return null
        val x = FloatArray(n) { hull[it * 2].toFloat() }
        val y = FloatArray(n) { hull[it * 2 + 1].toFloat() }
        val centreX: Float
        val centreY: Float
        val width: Float
        val height: Float
        val angle: Float
        if (n > 2) {
            val out = rotatingCalipers(x, y)
            centreX = out[0] + (out[2] + out[4]) * 0.5f
            centreY = out[1] + (out[3] + out[5]) * 0.5f
            width = sqrt(out[2].toDouble() * out[2] + out[3].toDouble() * out[3]).toFloat()
            height = sqrt(out[4].toDouble() * out[4] + out[5].toDouble() * out[5]).toFloat()
            angle = atan2(out[3].toDouble(), out[2].toDouble()).toFloat()
        } else if (n == 2) {
            centreX = (x[0] + x[1]) * 0.5f
            centreY = (y[0] + y[1]) * 0.5f
            val dx = (x[1] - x[0]).toDouble()
            val dy = (y[1] - y[0]).toDouble()
            width = sqrt(dx * dx + dy * dy).toFloat()
            height = 0f
            angle = atan2(dy, dx).toFloat()
        } else {
            centreX = x[0]
            centreY = y[0]
            width = 0f
            height = 0f
            angle = 0f
        }
        // Corners as OpenCV derives them from centre, size and the angle in degrees.
        val degrees = (angle * 180 / PI).toFloat()
        val radians = degrees * PI / 180.0
        val b = cos(radians).toFloat() * 0.5f
        val a = sin(radians).toFloat() * 0.5f
        val x0 = centreX - a * height - b * width
        val y0 = centreY + b * height - a * width
        val x1 = centreX + a * height - b * width
        val y1 = centreY - b * height - a * width
        val corners = doubleArrayOf(
            x0.toDouble(), y0.toDouble(), x1.toDouble(), y1.toDouble(),
            (2 * centreX - x0).toDouble(), (2 * centreY - y0).toDouble(), (2 * centreX - x1).toDouble(), (2 * centreY - y1).toDouble(),
        )
        return Rect(arrange(corners), min(width, height).toDouble())
    }

    /**
     * @return the rectangle as a corner and its two side vectors: x, y, side 1 x, side 1 y, side 2 x, side 2 y.
     */
    private fun rotatingCalipers(px: FloatArray, py: FloatArray): FloatArray {
        val n = px.size
        val vx = FloatArray(n)
        val vy = FloatArray(n)
        val inverseLength = FloatArray(n)
        var left = 0
        var bottom = 0
        var right = 0
        var top = 0
        var leftX = px[0]
        var rightX = px[0]
        var topY = py[0]
        var bottomY = py[0]
        for (i in 0 until n) {
            if (px[i] < leftX) { leftX = px[i]; left = i }
            if (px[i] > rightX) { rightX = px[i]; right = i }
            if (py[i] > topY) { topY = py[i]; top = i }
            if (py[i] < bottomY) { bottomY = py[i]; bottom = i }
            val next = if (i + 1 < n) i + 1 else 0
            val dx = px[next].toDouble() - px[i]
            val dy = py[next].toDouble() - py[i]
            vx[i] = dx.toFloat()
            vy[i] = dy.toFloat()
            inverseLength[i] = (1.0 / sqrt(dx * dx + dy * dy)).toFloat()
        }
        var orientation = 0f
        var ax = vx[n - 1].toDouble()
        var ay = vy[n - 1].toDouble()
        for (i in 0 until n) {
            val bx = vx[i].toDouble()
            val by = vy[i].toDouble()
            val convexity = ax * by - ay * bx
            if (convexity != 0.0) { orientation = if (convexity > 0) 1f else -1f; break }
            ax = bx
            ay = by
        }
        // The four calipers have directions (a, b), (-b, a), (-a, -b), (b, -a).
        var baseA = orientation
        var baseB = 0f
        val seq = intArrayOf(bottom, right, top, left)
        var minArea = Float.MAX_VALUE
        var bestLeft = 0
        var bestBottom = 0
        var bestA = 0f
        var bestB = 0f
        var bestWidth = 0f
        var bestHeight = 0f
        for (k in 0 until n) {
            // The caliper that reaches its polygon edge first decides the next position.
            val d0 = +baseA * vx[seq[0]] + baseB * vy[seq[0]]
            val d1 = -baseB * vx[seq[1]] + baseA * vy[seq[1]]
            val d2 = -baseA * vx[seq[2]] - baseB * vy[seq[2]]
            val d3 = +baseB * vx[seq[3]] - baseA * vy[seq[3]]
            var maxCos = d0 * inverseLength[seq[0]]
            var main = 0
            val c1 = d1 * inverseLength[seq[1]]
            if (c1 > maxCos) { main = 1; maxCos = c1 }
            val c2 = d2 * inverseLength[seq[2]]
            if (c2 > maxCos) { main = 2; maxCos = c2 }
            val c3 = d3 * inverseLength[seq[3]]
            if (c3 > maxCos) { main = 3; maxCos = c3 }
            val edge = seq[main]
            val leadX = vx[edge] * inverseLength[edge]
            val leadY = vy[edge] * inverseLength[edge]
            when (main) {
                0 -> { baseA = leadX; baseB = leadY }
                1 -> { baseA = leadY; baseB = -leadX }
                2 -> { baseA = -leadX; baseB = -leadY }
                else -> { baseA = -leadY; baseB = leadX }
            }
            seq[main] = if (seq[main] + 1 == n) 0 else seq[main] + 1
            var dx = px[seq[1]] - px[seq[3]]
            var dy = py[seq[1]] - py[seq[3]]
            val width = dx * baseA + dy * baseB
            dx = px[seq[2]] - px[seq[0]]
            dy = py[seq[2]] - py[seq[0]]
            val height = -dx * baseB + dy * baseA
            val area = width * height
            if (area <= minArea) {
                minArea = area
                bestLeft = seq[3]
                bestBottom = seq[0]
                bestA = baseA
                bestB = baseB
                bestWidth = width
                bestHeight = height
            }
        }
        val a1 = bestA
        val b1 = bestB
        val a2 = -bestB
        val b2 = bestA
        val c1 = a1 * px[bestLeft] + py[bestLeft] * b1
        val c2 = a2 * px[bestBottom] + py[bestBottom] * b2
        val inverse = 1f / (a1 * b2 - a2 * b1)
        return floatArrayOf(
            (c1 * b2 - c2 * b1) * inverse, (a1 * c2 - a2 * c1) * inverse,
            a1 * bestWidth, b1 * bestWidth,
            a2 * bestHeight, b2 * bestHeight,
        )
    }

    /**
     * Orders a rectangle's corners as the reference does: of the two leftmost the upper one first,
     * then the upper and the lower of the two rightmost, then the lower left one.
     */
    private fun arrange(c: DoubleArray): DoubleArray {
        val order = intArrayOf(0, 1, 2, 3).sortedBy { c[it * 2] }
        val (l0, l1) = if (c[order[1] * 2 + 1] > c[order[0] * 2 + 1]) order[0] to order[1] else order[1] to order[0]
        val (r0, r1) = if (c[order[3] * 2 + 1] > c[order[2] * 2 + 1]) order[2] to order[3] else order[3] to order[2]
        val out = DoubleArray(8)
        for ((i, k) in intArrayOf(l0, r0, r1, l1).withIndex()) {
            out[i * 2] = c[k * 2]
            out[i * 2 + 1] = c[k * 2 + 1]
        }
        return out
    }

    /**
     * The convex hull (x, y pairs) in the order OpenCV's `convexHull` returns it: starting at the
     * rightmost point (the lower one of several), on through the lowest and the leftmost point and
     * back over the top; points on a hull edge are left out.
     */
    internal fun convexHull(points: IntArray): IntArray {
        val n = points.size / 2
        if (n <= 1) return points.copyOf()
        val order = (0 until n).sortedWith(compareBy<Int> { points[it * 2] }.thenBy { points[it * 2 + 1] })
        val hull = IntArray(2 * (n + 1))
        var k = 0
        fun cross(o: Int, a: Int, px: Int, py: Int): Long {
            val ox = hull[o]
            val oy = hull[o + 1]
            return (hull[a] - ox).toLong() * (py - oy) - (hull[a + 1] - oy).toLong() * (px - ox)
        }
        fun push(i: Int) { hull[k * 2] = points[i * 2]; hull[k * 2 + 1] = points[i * 2 + 1]; k++ }
        // Monotone chain: leftmost -> over small y -> rightmost -> over large y -> leftmost.
        for (i in order) {
            while (k >= 2 && cross((k - 2) * 2, (k - 1) * 2, points[i * 2], points[i * 2 + 1]) <= 0) k--
            push(i)
        }
        val turn = k - 1
        val lower = k + 1
        for (t in n - 2 downTo 0) {
            val i = order[t]
            while (k >= lower && cross((k - 2) * 2, (k - 1) * 2, points[i * 2], points[i * 2 + 1]) <= 0) k--
            push(i)
        }
        // The last point repeats the first.
        val size = (k - 1).coerceAtLeast(1)
        if (size < 3) return hull.copyOf(size * 2)
        // Start at the rightmost point, which the chain reached at `turn`.
        val out = IntArray(size * 2)
        for (i in 0 until size) {
            val from = (turn + i) % size
            out[i * 2] = hull[from * 2]
            out[i * 2 + 1] = hull[from * 2 + 1]
        }
        return out
    }

    /**
     * Mean probability inside the quadrilateral [corners], its corners truncated to whole pixels.
     *
     * Scores of small kernels sit close to the box threshold, so the pixels counted are exactly those
     * OpenCV's `fillPoly` sets: the four sides drawn as 8-connected lines, and on every row from the
     * top corner down to (not including) the bottom one the whole pixels between the two sides
     * crossing it, the sides followed in 16-bit fixed point.
     */
    internal fun boxScore(map: FloatArray, width: Int, height: Int, corners: DoubleArray): Double {
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (i in 0 until 4) {
            minX = min(minX, corners[i * 2]); maxX = max(maxX, corners[i * 2])
            minY = min(minY, corners[i * 2 + 1]); maxY = max(maxY, corners[i * 2 + 1])
        }
        val x0 = floor(minX).toInt().coerceIn(0, width - 1)
        val x1 = ceil(maxX).toInt().coerceIn(0, width - 1)
        val y0 = floor(minY).toInt().coerceIn(0, height - 1)
        val y1 = ceil(maxY).toInt().coerceIn(0, height - 1)
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val mask = BooleanArray(w * h)
        val px = IntArray(4) { (corners[it * 2].toFloat() - x0).toInt() }
        val py = IntArray(4) { (corners[it * 2 + 1].toFloat() - y0).toInt() }
        fillPolygon(mask, w, h, px, py)
        var sum = 0.0
        var count = 0
        for (y in 0 until h) {
            val row = (y + y0) * width + x0
            for (x in 0 until w) if (mask[y * w + x]) { sum += map[row + x]; count++ }
        }
        return if (count > 0) sum / count else 0.0
    }

    /** OpenCV's `fillPoly` for one polygon with whole-pixel vertices on a [w] x [h] mask. */
    internal fun fillPolygon(mask: BooleanArray, w: Int, h: Int, px: IntArray, py: IntArray) {
        val n = px.size
        // Edges that are not horizontal: top row, bottom row, x at the top (16-bit fixed point), x step per row.
        val top = IntArray(n)
        val bottom = IntArray(n)
        val startX = LongArray(n)
        val stepX = LongArray(n)
        var edges = 0
        for (i in 0 until n) {
            val j = (i + n - 1) % n
            line(mask, w, h, px[j], py[j], px[i], py[i])
            if (py[j] == py[i]) continue
            val ax = px[j].toLong() shl 16
            val bx = px[i].toLong() shl 16
            stepX[edges] = (bx - ax) / (py[i] - py[j])
            if (py[j] < py[i]) { top[edges] = py[j]; bottom[edges] = py[i]; startX[edges] = ax } else { top[edges] = py[i]; bottom[edges] = py[j]; startX[edges] = bx }
            edges++
        }
        if (edges < 2) return
        var first = Int.MAX_VALUE
        var last = Int.MIN_VALUE
        for (e in 0 until edges) { first = min(first, top[e]); last = max(last, bottom[e]) }
        val crossings = LongArray(edges)
        for (y in max(first, 0) until min(last, h)) {
            var active = 0
            for (e in 0 until edges) if (y >= top[e] && y < bottom[e]) crossings[active++] = startX[e] + (y - top[e]) * stepX[e]
            crossings.sort(0, active)
            var k = 0
            while (k + 1 < active) {
                val from = max(((crossings[k] + 0xFFFF) shr 16).toInt(), 0)
                val to = min((crossings[k + 1] shr 16).toInt(), w - 1)
                for (x in from..to) mask[y * w + x] = true
                k += 2
            }
        }
    }

    /** An 8-connected line as OpenCV draws it: Bresenham from the left end point. */
    private fun line(mask: BooleanArray, w: Int, h: Int, ax: Int, ay: Int, bx: Int, by: Int) {
        var x = ax
        var y = ay
        var dx = bx - ax
        var dy = by - ay
        if (dx < 0) { x = bx; y = by; dx = -dx; dy = -dy }
        val stepY = if (dy < 0) -1 else 1
        dy = abs(dy)
        val steep = dy > dx
        val major = if (steep) dy else dx
        val minor = if (steep) dx else dy
        var error = major - 2 * minor
        for (i in 0..major) {
            if (x in 0 until w && y in 0 until h) mask[y * w + x] = true
            val turn = error < 0
            error += -2 * minor + if (turn) 2 * major else 0
            if (steep) { y += stepY; if (turn) x++ } else { x++; if (turn) y += stepY }
        }
    }

    /**
     * Grows the quadrilateral outwards by `area x ratio / perimeter` with round corners and returns
     * the outline's points (x, y pairs). This is Clipper's polygon offsetting with round joins and
     * its default arc tolerance, on integer coordinates as pyclipper uses it: the corners are
     * truncated to whole pixels first and every outline point is rounded.
     */
    internal fun unclip(corners: DoubleArray, ratio: Double): IntArray {
        var area = 0.0
        var perimeter = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            area += corners[i * 2] * corners[j * 2 + 1] - corners[j * 2] * corners[i * 2 + 1]
            val dx = corners[j * 2] - corners[i * 2]
            val dy = corners[j * 2 + 1] - corners[i * 2 + 1]
            perimeter += sqrt(dx * dx + dy * dy)
        }
        val delta = abs(area) / 2 * ratio / perimeter
        return offset(IntArray(8) { corners[it].toInt() }, delta)
    }

    /** Clipper's `ClipperOffset` for one closed polygon with `jtRound` joins and a positive [delta]. */
    internal fun offset(polygon: IntArray, delta: Double): IntArray {
        // Consecutive duplicates carry no edge.
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        for (i in 0 until polygon.size / 2) {
            val x = polygon[i * 2]
            val y = polygon[i * 2 + 1]
            if (xs.isNotEmpty() && xs.last() == x && ys.last() == y) continue
            xs.add(x); ys.add(y)
        }
        while (xs.size > 1 && xs.first() == xs.last() && ys.first() == ys.last()) { xs.removeAt(xs.size - 1); ys.removeAt(ys.size - 1) }
        val n = xs.size
        if (n < 3 || delta <= 0.0) return polygon.copyOf()
        // Outward normals need the winding whose shoelace sum is positive.
        var twiceArea = 0L
        for (i in 0 until n) {
            val j = (i + 1) % n
            twiceArea += xs[i].toLong() * ys[j] - xs[j].toLong() * ys[i]
        }
        if (twiceArea < 0) { xs.reverse(); ys.reverse() }
        val nx = DoubleArray(n)
        val ny = DoubleArray(n)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val dx = (xs[j] - xs[i]).toDouble()
            val dy = (ys[j] - ys[i]).toDouble()
            val f = 1.0 / sqrt(dx * dx + dy * dy)
            nx[i] = dy * f
            ny[i] = -dx * f
        }
        val tolerance = 0.25
        var steps = PI / acos(1 - tolerance / delta)
        if (steps > delta * PI) steps = delta * PI
        val stepSin = sin(2 * PI / steps)
        val stepCos = cos(2 * PI / steps)
        val stepsPerRadian = steps / (2 * PI)
        val out = ArrayList<Int>()
        fun add(x: Double, y: Double) { out.add(clipperRound(x)); out.add(clipperRound(y)) }
        var k = n - 1
        for (j in 0 until n) {
            var sinA = nx[k] * ny[j] - nx[j] * ny[k]
            val cosA = nx[k] * nx[j] + ny[k] * ny[j]
            if (abs(sinA * delta) < 1.0 && cosA > 0) {
                // The two edges are almost in line.
                add(xs[j] + nx[k] * delta, ys[j] + ny[k] * delta)
                k = j
                continue
            }
            sinA = sinA.coerceIn(-1.0, 1.0)
            if (sinA * delta < 0) {
                // A reflex corner: both offset edges meet at the corner itself.
                add(xs[j] + nx[k] * delta, ys[j] + ny[k] * delta)
                out.add(xs[j]); out.add(ys[j])
                add(xs[j] + nx[j] * delta, ys[j] + ny[j] * delta)
            } else {
                val arc = max(clipperRound(stepsPerRadian * abs(atan2(sinA, cosA))), 1)
                var x = nx[k]
                var y = ny[k]
                for (i in 0 until arc) {
                    add(xs[j] + x * delta, ys[j] + y * delta)
                    val previous = x
                    x = x * stepCos - stepSin * y
                    y = previous * stepSin + y * stepCos
                }
                add(xs[j] + nx[j] * delta, ys[j] + ny[j] * delta)
            }
            k = j
        }
        return out.toIntArray()
    }

    private fun clipperRound(v: Double): Int = if (v < 0) (v - 0.5).toInt() else (v + 0.5).toInt()

    /**
     * Top-left, top-right, bottom-right, bottom-left: of the two leftmost corners the upper is the
     * top-left one, of the two rightmost the upper is the top-right one.
     */
    internal fun clockwise(p: DoubleArray): FloatArray {
        val order = intArrayOf(0, 1, 2, 3).sortedBy { p[it * 2] }
        val (tl, bl) = if (p[order[0] * 2 + 1] <= p[order[1] * 2 + 1]) order[0] to order[1] else order[1] to order[0]
        val (tr, br) = if (p[order[2] * 2 + 1] <= p[order[3] * 2 + 1]) order[2] to order[3] else order[3] to order[2]
        val out = FloatArray(8)
        for ((i, k) in intArrayOf(tl, tr, br, bl).withIndex()) {
            out[i * 2] = p[k * 2].toFloat()
            out[i * 2 + 1] = p[k * 2 + 1].toFloat()
        }
        return out
    }

    /**
     * Reading order: by the top-left corner's y; boxes whose y differs by less than 10 pixels from
     * the previous one are one row and go left to right.
     */
    internal fun sort(boxes: List<TextBox>): List<TextBox> {
        if (boxes.isEmpty()) return boxes
        val byY = boxes.sortedBy { it.points[1] }
        val rows = IntArray(byY.size)
        for (i in 1 until byY.size) rows[i] = rows[i - 1] + if (byY[i].points[1] - byY[i - 1].points[1] >= 10f) 1 else 0
        return byY.indices.sortedWith(compareBy<Int> { rows[it] }.thenBy { byY[it].points[0] }).map { byY[it] }
    }
}
