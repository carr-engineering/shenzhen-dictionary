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

    fun resize(dw: Int, dh: Int): Gray = if (dw == w && dh == h) Gray(w, h, px.copyOf()) else resample(0.0, 0.0, w.toDouble(), h.toDouble(), dw, dh)

    /** 1-D resampling taps: for destination i, source indices and weights (clamped to the raster edge). */
    private class Taps(size: Int, s0: Double, s1: Double, n: Int) {
        val start = IntArray(n + 1)
        val index: IntArray
        val weight: FloatArray
        var lo = Int.MAX_VALUE
        var hi = 0

        init {
            val step = (s1 - s0) / n
            // At most ceil(step) + 1 taps per destination when shrinking, two when growing.
            val capacity = n * (if (step > 1.0) step.toInt() + 2 else 2)
            val idx = IntArray(capacity)
            val wts = FloatArray(capacity)
            var count = 0
            for (i in 0 until n) {
                start[i] = count
                if (step > 1.0) {
                    val a = s0 + i * step
                    val b = a + step
                    var k = floor(a).toInt()
                    while (k < b) {
                        val cover = min(b, k + 1.0) - max(a, k.toDouble())
                        if (cover > 1e-9) {
                            idx[count] = k.coerceIn(0, size - 1)
                            wts[count++] = (cover / step).toFloat()
                        }
                        k++
                    }
                } else {
                    val c = s0 + (i + .5) * step - .5
                    val k = floor(c).toInt()
                    val f = (c - k).toFloat()
                    idx[count] = k.coerceIn(0, size - 1); wts[count++] = 1f - f
                    idx[count] = (k + 1).coerceIn(0, size - 1); wts[count++] = f
                }
            }
            start[n] = count
            index = idx.copyOf(count)
            weight = wts.copyOf(count)
            for (v in index) { lo = min(lo, v); hi = max(hi, v + 1) }
            if (count == 0) { lo = 0; hi = 0 }
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
        val p00: Int
        val p10: Int
        val p01: Int
        val p11: Int
        if (x0 >= 0 && y0 >= 0 && x0 + 1 < w && y0 + 1 < h) {
            val k = y0 * w + x0
            p00 = px[k].toInt() and 0xFF
            p10 = px[k + 1].toInt() and 0xFF
            p01 = px[k + w].toInt() and 0xFF
            p11 = px[k + w + 1].toInt() and 0xFF
        } else {
            p00 = at(x0, y0); p10 = at(x0 + 1, y0); p01 = at(x0, y0 + 1); p11 = at(x0 + 1, y0 + 1)
        }
        val top = p00 * (1 - fx) + p10 * fx
        val bottom = p01 * (1 - fx) + p11 * fx
        return (top * (1 - fy) + bottom * fy + .5).toInt().coerceIn(0, 255)
    }

    /** Pixel value, white outside the raster. */
    private fun at(xx: Int, yy: Int): Int = if (xx < 0 || yy < 0 || xx >= w || yy >= h) 255 else px[yy * w + xx].toInt() and 0xFF

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
        val px = src.px
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
        // A second, stroke-sized neighbourhood: a stroke stands out from it as well, whereas the
        // inside of a shadow, a dark panel or a wall beside a sign, and any soft lighting edge, do not.
        val near = max(3, (h * .15).roundToInt())
        // Signed deviation from the local mean, clamped to a byte; zero where the pixel does not
        // also stand out from its stroke-sized neighbourhood. Integer arithmetic on window sums:
        // this loop runs over every pixel of every crop.
        val deviation = ByteArray(w * h)
        var skew = 0.0
        val half = threshold / 2
        for (y in 0 until h) {
            val c = max(0, y - radius)
            val d = min(h, y + radius + 1)
            val nc = max(0, y - near)
            val nd = min(h, y + near + 1)
            for (x in 0 until w) {
                val a = max(0, x - radius)
                val b = min(w, x + radius + 1)
                val area = (b - a) * (d - c)
                val g = px[y * w + x].toInt() and 0xFF
                // (g - mean) * area
                val scaled = g * area - (integral[d * iw + b] - integral[c * iw + b] - integral[d * iw + a] + integral[c * iw + a])
                // |g - mean| must round to more than the threshold.
                val limit = (2 * threshold + 1) * area
                if (2 * scaled > -limit && 2 * scaled < limit) continue
                val na = max(0, x - near)
                val nb = min(w, x + near + 1)
                val nearArea = (nb - na) * (nd - nc)
                val local = g * nearArea - (integral[nd * iw + nb] - integral[nc * iw + nb] - integral[nd * iw + na] + integral[nc * iw + na])
                if ((scaled < 0 && local > -half * nearArea) || (scaled > 0 && local < half * nearArea)) continue
                val dev = ((scaled + (if (scaled < 0) -area else area) / 2) / area).coerceIn(-127, 127)
                deviation[y * w + x] = dev.toByte()
                skew += dev.toDouble() * dev * dev
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
     * Rows `[top, bottom)` of the text line whose rows `[coreTop, coreBottom)` the detector
     * reported, in a crop cut with headroom above and below. Only columns `[colLo, colHi)` - where
     * the detector saw the text - are consulted. The band grows from the core over rows that carry
     * ink and stops at the first blank band, so glyph tops and bottoms the detector cut off are
     * recovered while the neighbouring lines stay out.
     */
    fun textRows(mask: ByteArray, w: Int, h: Int, coreTop: Int, coreBottom: Int, colLo: Int, colHi: Int): IntArray {
        val lo = colLo.coerceIn(0, w)
        val hi = colHi.coerceIn(lo, w)
        val rows = IntArray(h)
        for (y in 0 until h) {
            var count = 0
            for (x in lo until hi) count += mask[y * w + x]
            rows[y] = count
        }
        // A row of text carries far more ink than the trace of a slanted page edge crossing it, or
        // than the valley between two lines set close together.
        val typical = rows.copyOfRange(coreTop.coerceIn(0, h), coreBottom.coerceIn(coreTop.coerceIn(0, h), h)).sorted().let { if (it.isEmpty()) 0 else it[it.size * 3 / 4] }
        val inked = max(max(2, ((hi - lo) * .015).toInt()), (typical * .12).toInt())
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
        // Shrink to rows that actually carry ink.
        while (top < bottom - 1 && rows[top] < inked) top++
        while (bottom > top + 1 && rows[bottom - 1] < inked) bottom--
        return intArrayOf(top, bottom)
    }

    /**
     * Whether the run of inked columns `[from, to)` sticks out of the text band `[top, bottom)` both
     * above and below. A glyph of the line stays within the band; the edge of a page or panel, a
     * frame, a cast shadow or the wall beside a sign crosses it.
     */
    fun crossesBand(mask: ByteArray, w: Int, h: Int, from: Int, to: Int, top: Int, bottom: Int): Boolean {
        val gap = max(2, ((bottom - top) * .12).roundToInt())
        val reach = max(gap + 2, ((bottom - top) * .4).roundToInt())
        fun ink(rowFrom: Int, rowTo: Int): Int {
            var count = 0
            for (y in max(0, rowFrom) until min(h, rowTo)) for (x in from until to) count += mask[y * w + x]
            return count
        }
        return ink(top - reach, top - gap) >= 2 && ink(bottom + gap, bottom + reach) >= 2
    }

    /**
     * Row ranges of the text lines stacked inside one crop: maximal runs of inked rows (judged on
     * columns `[colLo, colHi)`) separated by blank bands, ignoring runs much thinner than the
     * tallest (dots, accents, a rule). One range for an ordinary single line.
     */
    fun stackedLines(mask: ByteArray, w: Int, h: Int, colLo: Int, colHi: Int): List<IntArray> {
        val lo = colLo.coerceIn(0, w)
        val hi = colHi.coerceIn(lo, w)
        val rows = IntArray(h)
        for (y in 0 until h) for (x in lo until hi) rows[y] += mask[y * w + x]
        val typical = rows.sorted()[h * 3 / 4]
        val inked = max(max(2, ((hi - lo) * .015).toInt()), (typical * .12).toInt())
        val gap = max(2, (h * .04).roundToInt())
        val bands = ArrayList<IntArray>()
        var start = -1
        var blank = 0
        for (y in 0..h) {
            val count = if (y < h) rows[y] else 0
            if (count >= inked) {
                if (start < 0) start = y
                blank = 0
            } else if (start >= 0 && (++blank >= gap || y == h)) {
                bands.add(intArrayOf(start, y - blank + 1))
                start = -1
                blank = 0
            }
        }
        val tallest = bands.maxOfOrNull { it[1] - it[0] } ?: 0
        return bands.filter { it[1] - it[0] >= max(8.0, tallest * .25) }
    }

    /**
     * Ink box of a text line in a crop cut with headroom: the rows found by [textRows], and the
     * columns holding ink in those rows, without runs at either end that cross the band
     * ([crossesBand]). Cleared runs are also removed from [mask].
     */
    fun lineBand(mask: ByteArray, w: Int, h: Int, coreTop: Int, coreBottom: Int, colLo: Int = 0, colHi: Int = w): IntArray? {
        val (top, bottom) = textRows(mask, w, h, coreTop, coreBottom, colLo, colHi)
        val inked = BooleanArray(w) { x ->
            var any = false
            for (y in top until bottom) if (mask[y * w + x].toInt() != 0) { any = true; break }
            any
        }
        fun trim(step: Int) {
            var x = if (step > 0) 0 else w - 1
            val stop = if (step > 0) colLo.coerceIn(0, w) else colHi.coerceIn(0, w) - 1
            while (x != stop && x in 0 until w) {
                if (!inked[x]) { x += step; continue }
                var end = x
                while (end in 0 until w && inked[end]) end += step
                val from = min(x, end + 1 - (if (step > 0) 1 else 0))
                val lo = if (step > 0) x else end + 1
                val hi = if (step > 0) end else x + 1
                if (!crossesBand(mask, w, h, lo, hi, top, bottom)) return
                for (xx in lo until hi) { inked[xx] = false; for (y in 0 until h) mask[y * w + xx] = 0 }
                x = end
                if (from < 0) return
            }
        }
        trim(1)
        trim(-1)
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
        val radius = max(6, (h * .25).roundToInt())
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
