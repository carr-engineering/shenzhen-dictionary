package com.szdict.recognition.ink

import com.szdict.recognition.InkStroke
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Trajectory-to-curve encoding for the Digital Ink network: a Kotlin port of the reference
 * `features.py`, which reconstructs Google's `LabeledInkCurveProcessor` from measured input tensors.
 *
 * The ink is normalised by its bounding-box height, each stroke is fitted with one or more cubic
 * Bezier curves in (x, y, t) and every curve becomes ten numbers: pen state, end-to-start dx and dy,
 * signed angle and relative length of the start and end tangents, time span and the two time-control
 * offsets. A straight pen-up curve joins consecutive strokes. Time is synthetic (arc length), as in
 * the reference: real timestamps are not used, so pen speed does not affect recognition.
 */
internal object InkFeatures {
    const val CHANNELS = 10

    /** A point with synthetic time: `[x, y, t]`. */
    private class Pts(val x: DoubleArray, val y: DoubleArray, val t: DoubleArray) {
        val size get() = x.size
        fun slice(from: Int, to: Int) = Pts(x.copyOfRange(from, to), y.copyOfRange(from, to), t.copyOfRange(from, to))
    }

    /** Cubic Bezier control points, `[4][3]` as (x, y, t). */
    private class Fit(val control: Array<DoubleArray>, val error: Double, val worst: Int)

    /** @return `rows x 10` features, row-major; empty when there is no ink. */
    fun encode(strokes: List<InkStroke>): FloatArray {
        val arrays = strokes.filter { it.points.isNotEmpty() }
        if (arrays.isEmpty()) return FloatArray(0)
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (s in arrays) for (p in s.points) {
            minX = min(minX, p.x.toDouble()); maxX = max(maxX, p.x.toDouble())
            minY = min(minY, p.y.toDouble()); maxY = max(maxY, p.y.toDouble())
        }
        val scale = max(maxY - minY, max((maxX - minX) * .01, 1e-6))
        val originX = arrays[0].points[0].x.toDouble()
        val originY = arrays[0].points[0].y.toDouble()
        val rows = ArrayList<DoubleArray>()
        var previous: DoubleArray? = null
        for (stroke in arrays) {
            val n = stroke.points.size
            val px = DoubleArray(n) { (stroke.points[it].x - originX) / scale }
            val py = DoubleArray(n) { (stroke.points[it].y - originY) / scale }
            var arc = 0.0
            for (i in 1 until n) arc += hypot(px[i] - px[i - 1], py[i] - py[i - 1])
            // Evenly spaced sample times over the arc length; coincident consecutive points are then
            // removed while the remaining points keep their times.
            val keep = ArrayList<Int>(n)
            for (i in 0 until n) if (i == 0 || px[i] != px[i - 1] || py[i] != py[i - 1]) keep.add(i)
            fun time(i: Int) = if (n == 1) 0.0 else arc * i / (n - 1)
            var points = Pts(
                DoubleArray(keep.size) { px[keep[it]] },
                DoubleArray(keep.size) { py[keep[it]] },
                DoubleArray(keep.size) { time(keep[it]) },
            )
            if (points.size == 1) points = Pts(doubleArrayOf(points.x[0], points.x[0]), doubleArrayOf(points.y[0], points.y[0]), doubleArrayOf(points.t[0], points.t[0]))
            if (previous != null) {
                val length = hypot(points.x[0] - previous[0], points.y[0] - previous[1])
                val start = doubleArrayOf(previous[0], previous[1], 0.0)
                val end = doubleArrayOf(points.x[0], points.y[0], length)
                val control = Array(4) { k -> DoubleArray(3) { start[it] + (end[it] - start[it]) * k / 3.0 } }
                rows.add(row(control, 0.0))
            }
            for (control in segments(points, 0)) rows.add(row(control, 1.0))
            val last = points.size - 1
            previous = doubleArrayOf(points.x[last], points.y[last], points.t[last])
        }
        val out = FloatArray(rows.size * CHANNELS)
        for (i in rows.indices) for (j in 0 until CHANNELS) out[i * CHANNELS + j] = rows[i][j].toFloat()
        return out
    }

    /**
     * Least-squares cubic through the points: alternates a polynomial fit over the current curve
     * parameters with a Newton step that re-projects each point onto the curve.
     */
    private fun fit(p: Pts): Fit {
        val n = p.size
        val arc = DoubleArray(n)
        for (i in 1 until n) arc[i] = arc[i - 1] + hypot(p.x[i] - p.x[i - 1], p.y[i] - p.y[i - 1])
        val total = arc[n - 1]
        if (total < 1e-8) return Fit(Array(4) { doubleArrayOf(p.x[0], p.y[0], p.t[0]) }, 0.0, 0)
        var s = DoubleArray(n) { arc[it] / total }
        val degree = min(3, n - 1)
        // coefficient[k] = (x, y, t) coefficient of s^k
        var coefficient = Array(4) { DoubleArray(3) }
        val predX = DoubleArray(n)
        val predY = DoubleArray(n)
        for (iteration in 0 until 20) {
            coefficient = polyFit(s, p, degree)
            var change = 0.0
            val next = DoubleArray(n)
            for (i in 0 until n) {
                val u = s[i]
                val c = coefficient
                predX[i] = c[0][0] + u * (c[1][0] + u * (c[2][0] + u * c[3][0]))
                predY[i] = c[0][1] + u * (c[1][1] + u * (c[2][1] + u * c[3][1]))
                val firstX = c[1][0] + 2 * u * c[2][0] + 3 * u * u * c[3][0]
                val firstY = c[1][1] + 2 * u * c[2][1] + 3 * u * u * c[3][1]
                val secondX = 2 * c[2][0] + 6 * u * c[3][0]
                val secondY = 2 * c[2][1] + 6 * u * c[3][1]
                val rx = predX[i] - p.x[i]
                val ry = predY[i] - p.y[i]
                val numerator = rx * firstX + ry * firstY
                val denominator = firstX * firstX + firstY * firstY + rx * secondX + ry * secondY
                next[i] = (u - numerator / max(denominator, 1e-12)).coerceIn(0.0, 1.0)
            }
            // Endpoint parameters stay fixed; endpoint positions are fitted freely.
            next[0] = 0.0
            next[n - 1] = 1.0
            for (i in 0 until n) change = max(change, abs(next[i] - s[i]))
            if (change < 1e-5) break
            s = next
        }
        val c = coefficient
        val control = Array(4) { DoubleArray(3) }
        for (d in 0 until 3) {
            control[0][d] = c[0][d]
            control[1][d] = c[0][d] + c[1][d] / 3
            control[2][d] = c[0][d] + 2 * c[1][d] / 3 + c[2][d] / 3
            control[3][d] = c[0][d] + c[1][d] + c[2][d] + c[3][d]
        }
        var error = 0.0
        var worst = 0
        var worstError = -1.0
        for (i in 0 until n) {
            val e = (predX[i] - p.x[i]) * (predX[i] - p.x[i]) + (predY[i] - p.y[i]) * (predY[i] - p.y[i])
            error += e
            if (e > worstError) { worstError = e; worst = i }
        }
        return Fit(control, error, worst)
    }

    /** Polynomial least squares of the given degree for x, y and t against the parameter [s]. */
    private fun polyFit(s: DoubleArray, p: Pts, degree: Int): Array<DoubleArray> {
        val m = degree + 1
        val a = Array(m) { DoubleArray(m) }
        val b = Array(m) { DoubleArray(3) }
        val powers = DoubleArray(m)
        for (i in s.indices) {
            var v = 1.0
            for (k in 0 until m) { powers[k] = v; v *= s[i] }
            for (r in 0 until m) {
                for (c in 0 until m) a[r][c] += powers[r] * powers[c]
                b[r][0] += powers[r] * p.x[i]
                b[r][1] += powers[r] * p.y[i]
                b[r][2] += powers[r] * p.t[i]
            }
        }
        // A whisker of ridge keeps repeated parameters (rank deficiency) solvable, approximating the
        // minimum-norm solution of the reference solver.
        for (r in 0 until m) a[r][r] += 1e-12
        // Gaussian elimination with partial pivoting.
        for (col in 0 until m) {
            var pivot = col
            for (r in col + 1 until m) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (pivot != col) {
                val ta = a[col]; a[col] = a[pivot]; a[pivot] = ta
                val tb = b[col]; b[col] = b[pivot]; b[pivot] = tb
            }
            val diagonal = a[col][col]
            if (abs(diagonal) < 1e-300) continue
            for (r in col + 1 until m) {
                val f = a[r][col] / diagonal
                if (f == 0.0) continue
                for (c in col until m) a[r][c] -= f * a[col][c]
                for (d in 0 until 3) b[r][d] -= f * b[col][d]
            }
        }
        val out = Array(4) { DoubleArray(3) }
        for (r in m - 1 downTo 0) {
            for (d in 0 until 3) {
                var v = b[r][d]
                for (c in r + 1 until m) v -= a[r][c] * out[c][d]
                out[r][d] = if (abs(a[r][r]) < 1e-300) 0.0 else v / a[r][r]
            }
        }
        return out
    }

    /** Fits one curve, splitting recursively at a sharp turn (or the worst residual) when it fits poorly. */
    private fun segments(p: Pts, depth: Int): List<Array<DoubleArray>> {
        val f = fit(p)
        val n = p.size
        val chord = hypot(f.control[3][0] - f.control[0][0], f.control[3][1] - f.control[0][1])
        var length = 0.0
        for (i in 1 until n) length += hypot(p.x[i] - p.x[i - 1], p.y[i] - p.y[i - 1])
        if (n <= 4 || depth >= 12 || (f.error / n < 0.0001 && length <= max(3 * chord, .05))) return listOf(f.control)
        var split = 1
        var bestCos = Double.NEGATIVE_INFINITY
        for (i in 1 until n - 1) {
            val bx = p.x[i - 1] - p.x[i]
            val by = p.y[i - 1] - p.y[i]
            val ax = p.x[i + 1] - p.x[i]
            val ay = p.y[i + 1] - p.y[i]
            val norm = sqrt(bx * bx + by * by) * sqrt(ax * ax + ay * ay)
            val cos = (bx * ax + by * ay) / max(norm, 1e-12)
            if (cos > bestCos) { bestCos = cos; split = i }
        }
        if (bestCos < -.8) split = min(max(f.worst, 1), n - 2)
        return segments(p.slice(0, split + 1), depth + 1) + segments(p.slice(split, n), depth + 1)
    }

    private fun row(control: Array<DoubleArray>, pen: Double): DoubleArray {
        val dx = control[3][0] - control[0][0]
        val dy = control[3][1] - control[0][1]
        val ax = control[1][0] - control[0][0]
        val ay = control[1][1] - control[0][1]
        val bx = control[3][0] - control[2][0]
        val by = control[3][1] - control[2][1]
        val length = hypot(dx, dy)
        fun angle(vx: Double, vy: Double) = if (length > 1e-8) atan2(dx * vy - dy * vx, dx * vx + dy * vy) else 0.0
        val safe = max(length, 1e-8)
        return doubleArrayOf(
            pen, dx, dy,
            angle(ax, ay), hypot(ax, ay) / safe,
            angle(bx, by), hypot(bx, by) / safe,
            control[3][2] - control[0][2], control[1][2] - control[0][2], control[2][2] - control[3][2],
        )
    }
}
