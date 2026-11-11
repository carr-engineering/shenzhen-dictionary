package com.szdict.recognition.ocr

import com.szdict.recognition.OcrImage
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** An 8-bit luminance raster, row-major. */
internal class Gray(val w: Int, val h: Int, val px: ByteArray = ByteArray(w * h)) {
    operator fun get(x: Int, y: Int): Int = px[y * w + x].toInt() and 0xFF

    fun invert() {
        for (i in px.indices) px[i] = (255 - (px[i].toInt() and 0xFF)).toByte()
    }

    /** Third-brightest of the four corner pixels; a dark value means light text on a dark page. */
    fun cornerLevel(): Int {
        val c = intArrayOf(this[0, 0], this[w - 1, 0], this[0, h - 1], this[w - 1, h - 1])
        c.sort()
        return c[2]
    }

    /**
     * Resamples the source rectangle `[sx0, sx1) x [sy0, sy1)` to [dw] x [dh]: area averaging along an
     * axis that shrinks, bilinear interpolation along an axis that grows.
     */
    fun resample(sx0: Double, sy0: Double, sx1: Double, sy1: Double, dw: Int, dh: Int): Gray {
        val horizontal = Taps(w, sx0, sx1, dw)
        val vertical = Taps(h, sy0, sy1, dh)
        // Horizontal pass over the rows the vertical pass needs.
        val rowLo = max(0, vertical.lo)
        val rowHi = min(h, vertical.hi)
        val rows = max(0, rowHi - rowLo)
        val tmp = FloatArray(rows * dw)
        for (y in 0 until rows) {
            val base = (y + rowLo) * w
            for (x in 0 until dw) {
                var acc = 0f
                for (k in horizontal.start[x] until horizontal.start[x + 1]) acc += horizontal.weight[k] * (px[base + horizontal.index[k]].toInt() and 0xFF)
                tmp[y * dw + x] = acc
            }
        }
        val out = Gray(dw, dh)
        for (y in 0 until dh) for (x in 0 until dw) {
            var acc = 0f
            for (k in vertical.start[y] until vertical.start[y + 1]) acc += vertical.weight[k] * tmp[(vertical.index[k] - rowLo) * dw + x]
            out.px[y * dw + x] = acc.roundToInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    fun resize(dw: Int, dh: Int): Gray = resample(0.0, 0.0, w.toDouble(), h.toDouble(), dw, dh)

    /** 1-D resampling taps: for destination i, source indices and weights (clamped to the raster edge). */
    private class Taps(size: Int, s0: Double, s1: Double, n: Int) {
        val start = IntArray(n + 1)
        val index: IntArray
        val weight: FloatArray
        var lo = Int.MAX_VALUE
        var hi = 0

        init {
            val step = (s1 - s0) / n
            val idx = ArrayList<Int>()
            val wts = ArrayList<Float>()
            for (i in 0 until n) {
                start[i] = idx.size
                if (step > 1.0) {
                    val a = s0 + i * step
                    val b = a + step
                    var k = floor(a).toInt()
                    while (k < b) {
                        val cover = min(b, k + 1.0) - max(a, k.toDouble())
                        if (cover > 1e-9) {
                            idx.add(k.coerceIn(0, size - 1))
                            wts.add((cover / step).toFloat())
                        }
                        k++
                    }
                } else {
                    val c = s0 + (i + .5) * step - .5
                    val k = floor(c).toInt()
                    val f = (c - k).toFloat()
                    idx.add(k.coerceIn(0, size - 1)); wts.add(1f - f)
                    idx.add((k + 1).coerceIn(0, size - 1)); wts.add(f)
                }
            }
            start[n] = idx.size
            index = idx.toIntArray()
            weight = wts.toFloatArray()
            for (v in index) { lo = min(lo, v); hi = max(hi, v + 1) }
            if (index.isEmpty()) { lo = 0; hi = 0 }
        }
    }

    /**
     * Cuts an upright [cw] x [ch] crop centred on the region centre, rotated by the region's angle
     * (bilinear, white outside the image). Crop pixel (u, v) samples image point
     * `centre + R(angle) * (u - cw/2, v - ch/2)`.
     */
    fun rotatedCrop(cx: Double, cy: Double, angle: Double, cw: Int, ch: Int): Gray {
        val out = Gray(cw, ch)
        val c = cos(angle)
        val s = sin(angle)
        for (v in 0 until ch) {
            val dv = v + .5 - ch / 2.0
            for (u in 0 until cw) {
                val du = u + .5 - cw / 2.0
                val x = cx + du * c - dv * s - .5
                val y = cy + du * s + dv * c - .5
                out.px[v * cw + u] = sample(x, y).toByte()
            }
        }
        return out
    }

    private fun sample(x: Double, y: Double): Int {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        fun at(xx: Int, yy: Int): Int = if (xx < 0 || yy < 0 || xx >= w || yy >= h) 255 else px[yy * w + xx].toInt() and 0xFF
        val top = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
        val bottom = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
        return (top * (1 - fy) + bottom * fy).roundToInt().coerceIn(0, 255)
    }

    /** Quarter turn clockwise: result pixel (x', y') is this raster's (y', h - 1 - x'). */
    fun rotate90(): Gray {
        val out = Gray(h, w)
        for (y in 0 until h) for (x in 0 until w) out.px[x * h + (h - 1 - y)] = px[y * w + x]
        return out
    }

    fun rotate180(): Gray {
        val out = Gray(w, h)
        val n = px.size
        for (i in 0 until n) out.px[n - 1 - i] = px[i]
        return out
    }

    companion object {
        fun fromArgb(image: OcrImage): Gray {
            val out = Gray(image.width, image.height)
            val src = image.argb
            for (i in 0 until image.width * image.height) {
                val p = src[i]
                out.px[i] = (.299 * ((p shr 16) and 0xFF) + .587 * ((p shr 8) and 0xFF) + .114 * (p and 0xFF)).roundToInt().toByte()
            }
            return out
        }
    }
}

/** Ink analysis of a line crop. [box] is `[x0, y0, x1, y1]` of the ink, or null for an empty crop. */
internal class Ink(val box: IntArray?, val gray: Gray, val mask: ByteArray, val dark: Boolean)

internal object InkAnalysis {
    /**
     * Finds ink against the local paper colour. Ink is anything clearly different from the local
     * mean, which keeps text visible under uneven lighting. Polarity is decided by the strokes
     * themselves: strokes cover less area than the paper between them, so they sit further from
     * the local mean than the paper does; the sign of the skew of the deviations says whether the
     * strokes are the dark or the light side, and light text is flipped to dark-on-light. This works on coloured panels and on a dark
     * sign set in a light wall, where the brightness of the crop border says nothing.
     *
     * @param threshold minimum difference from the local mean, raised on noisy pages.
     */
    fun inkBounds(src: Gray, threshold: Int = 18): Ink {
        val w = src.w
        val h = src.h
        val iw = w + 1
        val integral = IntArray(iw * (h + 1))
        for (y in 0 until h) {
            var sum = 0
            for (x in 0 until w) {
                sum += src[x, y]
                integral[(y + 1) * iw + x + 1] = integral[y * iw + x + 1] + sum
            }
        }
        val radius = max(8, (h * .45).roundToInt())
        // Signed deviation from the local mean, clamped to a byte.
        val deviation = ByteArray(w * h)
        var skew = 0.0
        for (y in 0 until h) {
            val c = max(0, y - radius)
            val d = min(h, y + radius + 1)
            for (x in 0 until w) {
                val a = max(0, x - radius)
                val b = min(w, x + radius + 1)
                val mean = (integral[d * iw + b] - integral[c * iw + b] - integral[d * iw + a] + integral[c * iw + a]).toDouble() / ((b - a) * (d - c))
                val dev = (src[x, y] - mean).roundToInt().coerceIn(-127, 127)
                deviation[y * w + x] = dev.toByte()
                if (dev < -threshold || dev > threshold) skew += dev.toDouble() * dev * dev
            }
        }
        val dark = skew > 0
        val gray = Gray(w, h, src.px.copyOf())
        if (dark) gray.invert()
        val mask = ByteArray(w * h)
        var x0 = w
        var y0 = h
        var x1 = 0
        var y1 = 0
        for (y in 0 until h) for (x in 0 until w) {
            val k = y * w + x
            val dev = if (dark) -deviation[k] else deviation[k].toInt()
            if (dev < -threshold && (gray.px[k].toInt() and 0xFF) < 220) {
                mask[k] = 1
                x0 = min(x0, x); x1 = max(x1, x + 1); y0 = min(y0, y); y1 = max(y1, y + 1)
            }
        }
        return Ink(if (x1 > x0) intArrayOf(x0, y0, x1, y1) else null, gray, mask, dark)
    }

    /**
     * Ink box of the text line whose rows `[coreTop, coreBottom)` the detector reported, in a crop
     * cut with headroom above and below. The band grows from the core over rows that carry ink and
     * stops at the first blank band, so glyph tops and bottoms the detector cut off are recovered
     * while the neighbouring lines stay out.
     */
    fun lineBand(mask: ByteArray, w: Int, h: Int, coreTop: Int, coreBottom: Int): IntArray? {
        val rows = IntArray(h)
        for (y in 0 until h) {
            var count = 0
            for (x in 0 until w) count += mask[y * w + x]
            rows[y] = count
        }
        // A row of text carries far more ink than the trace of a slanted page edge crossing it.
        val inked = max(2, (w * .015).toInt())
        // A blank band this tall ends the line; thinner ones are gaps inside glyphs (二, i, ；).
        val tolerance = max(1, ((coreBottom - coreTop) * .08).roundToInt())
        var top = coreTop
        var blank = 0
        var y = coreTop - 1
        while (y >= 0 && blank <= tolerance) {
            if (rows[y] >= inked) { top = y; blank = 0 } else blank++
            y--
        }
        var bottom = coreBottom
        blank = 0
        y = coreBottom
        while (y < h && blank <= tolerance) {
            if (rows[y] >= inked) { bottom = y + 1; blank = 0 } else blank++
            y++
        }
        var x0 = w
        var x1 = 0
        var y0 = h
        var y1 = 0
        for (yy in top until bottom) for (x in 0 until w) if (mask[yy * w + x].toInt() != 0) {
            x0 = min(x0, x); x1 = max(x1, x + 1); y0 = min(y0, yy); y1 = max(y1, yy + 1)
        }
        return if (x1 > x0) intArrayOf(x0, y0, x1, y1) else null
    }

    /**
     * Residual tilt of the text in an ink mask, in radians (positive = descending to the right),
     * found by maximising the sharpness of the row projection over small shears. Returns 0 when no
     * candidate is clearly better than leaving the crop as it is.
     */
    fun residualSkew(mask: ByteArray, w: Int, h: Int): Double {
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        // Every other ink pixel is plenty for a projection profile.
        for (y in 0 until h) for (x in (y and 1) until w step 2) if (mask[y * w + x].toInt() != 0) { xs.add(x); ys.add(y) }
        if (xs.size < 50) return 0.0
        val bins = IntArray(h + 2 * SKEW_MARGIN)
        fun sharpness(angle: Double): Double {
            bins.fill(0)
            val slope = kotlin.math.tan(angle)
            for (i in xs.indices) {
                val row = (ys[i] - (xs[i] - w / 2.0) * slope).roundToInt() + SKEW_MARGIN
                if (row >= 0 && row < bins.size) bins[row]++
            }
            var sum = 0.0
            for (b in bins) sum += b.toDouble() * b
            return sum
        }
        val base = sharpness(0.0)
        var best = 0.0
        var bestScore = base
        for (step in -8..8) {
            if (step == 0) continue
            val angle = step * .0075
            val score = sharpness(angle)
            if (score > bestScore) { bestScore = score; best = angle }
        }
        return if (bestScore > base * 1.03) best else 0.0
    }

    private const val SKEW_MARGIN = 16

    /** Value at quantile [q] (index `floor(n * q)` of the sorted values). */
    fun percentile(hist: IntArray, count: Int, q: Double): Int {
        val target = floor(count * q).toInt()
        var seen = 0
        for (v in 0 until 256) {
            seen += hist[v]
            if (seen > target) return v
        }
        return 255
    }

    /**
     * Clears mask columns (and, with [rows], rows) that are inked over almost the whole crop: the
     * edge of a page or panel, a frame line, the wall beside a sign. A stroke cannot do that, as
     * the crop is cut with headroom above and below the glyphs and a line has gaps between glyphs.
     */
    fun dropSolid(mask: ByteArray, w: Int, h: Int, rows: Boolean) {
        val columnLimit = (h * .85).toInt()
        for (x in 0 until w) {
            var count = 0
            for (y in 0 until h) count += mask[y * w + x]
            if (count >= columnLimit) for (y in 0 until h) mask[y * w + x] = 0
        }
        if (!rows) return
        val rowLimit = (w * .85).toInt()
        for (y in 0 until h) {
            var count = 0
            for (x in 0 until w) count += mask[y * w + x]
            if (count >= rowLimit) for (x in 0 until w) mask[y * w + x] = 0
        }
    }

    /**
     * The recogniser's view of a line: black ink on white paper. Each pixel is measured against
     * the local paper level (the mean of the surrounding non-ink pixels), so a lighting gradient,
     * a cast shadow or a coloured panel all come out as flat white, and the ink scale is set by the
     * line's own stroke contrast. Antialiased stroke edges are kept.
     *
     * @param floor differences from the paper level below this are paper texture or sensor noise.
     */
    fun normalizeContrast(ink: Ink, floor: Int = 6): Gray {
        val gray = ink.gray
        val w = gray.w
        val h = gray.h
        val iw = w + 1
        val sum = IntArray(iw * (h + 1))
        val cnt = IntArray(iw * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0
            var rowCnt = 0
            for (x in 0 until w) {
                if (ink.mask[y * w + x].toInt() == 0) { rowSum += gray[x, y]; rowCnt++ }
                sum[(y + 1) * iw + x + 1] = sum[y * iw + x + 1] + rowSum
                cnt[(y + 1) * iw + x + 1] = cnt[y * iw + x + 1] + rowCnt
            }
        }
        val radius = max(8, (h * .45).roundToInt())
        val depth = IntArray(w * h)
        val hist = IntArray(256)
        var inked = 0
        for (y in 0 until h) {
            val c = max(0, y - radius)
            val d = min(h, y + radius + 1)
            for (x in 0 until w) {
                val a = max(0, x - radius)
                val b = min(w, x + radius + 1)
                val n = cnt[d * iw + b] - cnt[c * iw + b] - cnt[d * iw + a] + cnt[c * iw + a]
                val paper = if (n > 0) (sum[d * iw + b] - sum[c * iw + b] - sum[d * iw + a] + sum[c * iw + a]) / n else 255
                val v = (paper - gray[x, y] - floor).coerceIn(0, 255)
                depth[y * w + x] = v
                if (ink.mask[y * w + x].toInt() != 0) { hist[v]++; inked++ }
            }
        }
        // Full black at the depth of solid strokes (upper part of the ink's depth distribution).
        val full = max(40, percentile(hist, inked, .7))
        val out = Gray(w, h)
        for (i in 0 until w * h) out.px[i] = (255 - min(255, depth[i] * 255 / full)).toByte()
        return out
    }
}
