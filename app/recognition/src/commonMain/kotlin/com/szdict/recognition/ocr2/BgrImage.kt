package com.szdict.recognition.ocr2

import com.szdict.recognition.OcrImage
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An 8-bit colour image with interleaved B, G, R bytes (`data[(y * width + x) * 3 + channel]`), the
 * layout the models were trained on, with the image operations of the reference pipeline. They follow
 * OpenCV's arithmetic closely enough that results agree to within one grey level.
 */
internal class BgrImage(val width: Int, val height: Int, val data: ByteArray) {
    init {
        require(width > 0 && height > 0 && data.size >= width * height * 3)
    }

    /**
     * Bilinear resampling with pixel centres aligned (OpenCV `resize`, `INTER_LINEAR`), including its
     * 11-bit fixed-point weights.
     */
    fun resize(newWidth: Int, newHeight: Int): BgrImage {
        if (newWidth == width && newHeight == height) return this
        val xIndex = IntArray(newWidth)
        val xWeight = IntArray(newWidth * 2)
        taps(width, newWidth, xIndex, xWeight)
        val yIndex = IntArray(newHeight)
        val yWeight = IntArray(newHeight * 2)
        taps(height, newHeight, yIndex, yWeight)
        val out = ByteArray(newWidth * newHeight * 3)
        // Horizontally interpolated rows of the two source rows a destination row needs.
        val rowA = IntArray(newWidth * 3)
        val rowB = IntArray(newWidth * 3)
        var cachedA = -1
        var cachedB = -1
        var a = rowA
        var b = rowB
        for (y in 0 until newHeight) {
            val y0 = yIndex[y]
            val y1 = min(y0 + 1, height - 1)
            if (y0 == cachedB && y0 != cachedA) {
                val swap = a; a = b; b = swap
                cachedA = cachedB
                cachedB = -1
            }
            if (cachedA != y0) { horizontal(y0, xIndex, xWeight, newWidth, a); cachedA = y0 }
            if (cachedB != y1) {
                if (y1 == y0) a.copyInto(b) else horizontal(y1, xIndex, xWeight, newWidth, b)
                cachedB = y1
            }
            val w0 = yWeight[y * 2]
            val w1 = yWeight[y * 2 + 1]
            val base = y * newWidth * 3
            for (i in 0 until newWidth * 3) {
                val v = (((w0 * (a[i] shr 4)) shr 16) + ((w1 * (b[i] shr 4)) shr 16) + 2) shr 2
                out[base + i] = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
            }
        }
        return BgrImage(newWidth, newHeight, out)
    }

    private fun horizontal(y: Int, xIndex: IntArray, xWeight: IntArray, newWidth: Int, into: IntArray) {
        val row = y * width * 3
        for (x in 0 until newWidth) {
            val x0 = xIndex[x]
            val x1 = min(x0 + 1, width - 1)
            val w0 = xWeight[x * 2]
            val w1 = xWeight[x * 2 + 1]
            val p0 = row + x0 * 3
            val p1 = row + x1 * 3
            val o = x * 3
            into[o] = (data[p0].toInt() and 0xFF) * w0 + (data[p1].toInt() and 0xFF) * w1
            into[o + 1] = (data[p0 + 1].toInt() and 0xFF) * w0 + (data[p1 + 1].toInt() and 0xFF) * w1
            into[o + 2] = (data[p0 + 2].toInt() and 0xFF) * w0 + (data[p1 + 2].toInt() and 0xFF) * w1
        }
    }

    /**
     * Cuts the quadrilateral [quad] (x0, y0, ... x3, y3: top-left, top-right, bottom-right,
     * bottom-left) out as a [cropWidth] x [cropHeight] image: bicubic sampling (a = -0.75) at
     * positions quantised to 1/32 pixel, edge pixels repeated outside the image (OpenCV
     * `warpPerspective` with `INTER_CUBIC` and `BORDER_REPLICATE`).
     *
     * @param toSource the homography from crop to image coordinates, see [Homography.inverse].
     */
    fun warp(toSource: Homography, cropWidth: Int, cropHeight: Int): BgrImage {
        val out = ByteArray(cropWidth * cropHeight * 3)
        val m = toSource.m
        val maxX = width - 1
        val maxY = height - 1
        val xs = IntArray(4)
        for (y in 0 until cropHeight) {
            for (x in 0 until cropWidth) {
                var w = m[6] * x + m[7] * y + m[8]
                w = if (w != 0.0) SUBPIXEL / w else 0.0
                val fx = ((m[0] * x + m[1] * y + m[2]) * w).coerceIn(-1e9, 1e9)
                val fy = ((m[3] * x + m[4] * y + m[5]) * w).coerceIn(-1e9, 1e9)
                val qx = roundHalfEven(fx)
                val qy = roundHalfEven(fy)
                val sx = qx shr 5
                val sy = qy shr 5
                val cx = (qx and 31) * 4
                val cy = (qy and 31) * 4
                for (i in 0 until 4) xs[i] = (sx - 1 + i).coerceIn(0, maxX) * 3
                var b = 0f
                var g = 0f
                var r = 0f
                for (j in 0 until 4) {
                    val row = (sy - 1 + j).coerceIn(0, maxY) * width * 3
                    val wy = CUBIC[cy + j]
                    for (i in 0 until 4) {
                        val weight = wy * CUBIC[cx + i]
                        val p = row + xs[i]
                        b += weight * (data[p].toInt() and 0xFF)
                        g += weight * (data[p + 1].toInt() and 0xFF)
                        r += weight * (data[p + 2].toInt() and 0xFF)
                    }
                }
                val o = (y * cropWidth + x) * 3
                out[o] = saturate(b)
                out[o + 1] = saturate(g)
                out[o + 2] = saturate(r)
            }
        }
        return BgrImage(cropWidth, cropHeight, out)
    }

    /** Turned a quarter counter-clockwise: the right edge becomes the top (NumPy `rot90`). */
    fun rotate90(): BgrImage {
        val out = ByteArray(width * height * 3)
        // out is height wide and width tall: out[i][j] = in[j][width - 1 - i].
        for (i in 0 until width) {
            val sx = width - 1 - i
            for (j in 0 until height) {
                val s = (j * width + sx) * 3
                val d = (i * height + j) * 3
                out[d] = data[s]; out[d + 1] = data[s + 1]; out[d + 2] = data[s + 2]
            }
        }
        return BgrImage(height, width, out)
    }

    fun rotate180(): BgrImage {
        val n = width * height
        val out = ByteArray(n * 3)
        for (i in 0 until n) {
            val s = i * 3
            val d = (n - 1 - i) * 3
            out[d] = data[s]; out[d + 1] = data[s + 1]; out[d + 2] = data[s + 2]
        }
        return BgrImage(width, height, out)
    }

    /** Adds [rows] black rows above and below. */
    fun padVertically(rows: Int): BgrImage {
        if (rows <= 0) return this
        val out = ByteArray(width * (height + 2 * rows) * 3)
        data.copyInto(out, rows * width * 3, 0, width * height * 3)
        return BgrImage(width, height + 2 * rows, out)
    }

    companion object {
        private const val COEF_SCALE = 2048
        private const val SUBPIXEL = 32.0

        /** Bicubic weights of the four taps for each of the 32 sub-pixel positions. */
        private val CUBIC = FloatArray(32 * 4).also { table ->
            val a = -0.75f
            for (i in 0 until 32) {
                val x = i / 32f
                val c0 = ((a * (x + 1) - 5 * a) * (x + 1) + 8 * a) * (x + 1) - 4 * a
                val c1 = ((a + 2) * x - (a + 3)) * x * x + 1
                val c2 = ((a + 2) * (1 - x) - (a + 3)) * (1 - x) * (1 - x) + 1
                table[i * 4] = c0
                table[i * 4 + 1] = c1
                table[i * 4 + 2] = c2
                table[i * 4 + 3] = 1f - c0 - c1 - c2
            }
        }

        private fun saturate(v: Float): Byte {
            val i = v.roundToInt()
            return (if (i < 0) 0 else if (i > 255) 255 else i).toByte()
        }

        internal fun roundHalfEven(v: Double): Int = kotlin.math.round(v).toInt()

        private fun taps(source: Int, target: Int, index: IntArray, weight: IntArray) {
            val scale = source.toDouble() / target
            for (d in 0 until target) {
                var f = ((d + 0.5) * scale - 0.5).toFloat()
                var s = floor(f).toInt()
                f -= s
                if (s < 0) { s = 0; f = 0f }
                if (s >= source - 1) { s = source - 1; f = 0f }
                index[d] = s
                weight[d * 2] = roundHalfEven(((1f - f) * COEF_SCALE).toDouble())
                weight[d * 2 + 1] = roundHalfEven((f * COEF_SCALE).toDouble())
            }
        }

        /** Drops alpha (the caller composites transparency on white, see [OcrImage]). */
        fun fromArgb(image: OcrImage): BgrImage {
            val n = image.width * image.height
            val out = ByteArray(n * 3)
            val argb = image.argb
            for (i in 0 until n) {
                val p = argb[i]
                out[i * 3] = p.toByte()
                out[i * 3 + 1] = (p shr 8).toByte()
                out[i * 3 + 2] = (p shr 16).toByte()
            }
            return BgrImage(image.width, image.height, out)
        }
    }
}

/** A 3 x 3 projective transform, row-major. */
internal class Homography(val m: DoubleArray) {
    fun apply(x: Double, y: Double, out: DoubleArray, offset: Int = 0) {
        val w = m[6] * x + m[7] * y + m[8]
        out[offset] = (m[0] * x + m[1] * y + m[2]) / w
        out[offset + 1] = (m[3] * x + m[4] * y + m[5]) / w
    }

    fun inverse(): Homography {
        val a = m
        val c0 = a[4] * a[8] - a[5] * a[7]
        val c1 = a[5] * a[6] - a[3] * a[8]
        val c2 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c0 + a[1] * c1 + a[2] * c2
        val d = if (det != 0.0) 1.0 / det else 0.0
        return Homography(
            doubleArrayOf(
                c0 * d, (a[2] * a[7] - a[1] * a[8]) * d, (a[1] * a[5] - a[2] * a[4]) * d,
                c1 * d, (a[0] * a[8] - a[2] * a[6]) * d, (a[2] * a[3] - a[0] * a[5]) * d,
                c2 * d, (a[1] * a[6] - a[0] * a[7]) * d, (a[0] * a[4] - a[1] * a[3]) * d,
            ),
        )
    }

    companion object {
        /**
         * The transform taking the four points [from] (x0, y0, ... x3, y3) to the corners of a
         * [width] x [height] rectangle: (0, 0), (width, 0), (width, height), (0, height).
         */
        fun toRectangle(from: FloatArray, width: Double, height: Double): Homography {
            val tx = doubleArrayOf(0.0, width, width, 0.0)
            val ty = doubleArrayOf(0.0, 0.0, height, height)
            // Eight equations in the eight unknowns of the matrix with m[8] = 1.
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val x = from[i * 2].toDouble()
                val y = from[i * 2 + 1].toDouble()
                a[i][0] = x; a[i][1] = y; a[i][2] = 1.0
                a[i][6] = -x * tx[i]; a[i][7] = -y * tx[i]; a[i][8] = tx[i]
                a[i + 4][3] = x; a[i + 4][4] = y; a[i + 4][5] = 1.0
                a[i + 4][6] = -x * ty[i]; a[i + 4][7] = -y * ty[i]; a[i + 4][8] = ty[i]
            }
            for (col in 0 until 8) {
                var pivot = col
                for (row in col + 1 until 8) if (kotlin.math.abs(a[row][col]) > kotlin.math.abs(a[pivot][col])) pivot = row
                val swap = a[col]; a[col] = a[pivot]; a[pivot] = swap
                val p = a[col][col]
                if (p == 0.0) continue
                for (row in 0 until 8) {
                    if (row == col) continue
                    val f = a[row][col] / p
                    if (f != 0.0) for (k in col until 9) a[row][k] -= f * a[col][k]
                }
            }
            val m = DoubleArray(9)
            for (i in 0 until 8) m[i] = if (a[i][i] != 0.0) a[i][8] / a[i][i] else 0.0
            m[8] = 1.0
            return Homography(m)
        }
    }
}
