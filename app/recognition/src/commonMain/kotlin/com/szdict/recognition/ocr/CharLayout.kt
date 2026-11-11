package com.szdict.recognition.ocr

import kotlin.math.max
import kotlin.math.min

/**
 * Turns recogniser emission frames into per-character x spans on the normalised line strip.
 *
 * The recogniser is a left-to-right LSTM over 4-px columns, so it emits a symbol when it has just
 * seen the glyph's right edge: measured on rendered text, the glyph ends about 3.5 px after the
 * start of its peak frame. Boundaries are placed there, snapped to a nearby ink-free column, and
 * each span is then tightened to the ink it contains.
 */
internal object CharLayout {
    private const val FRAME = 4
    private const val EMISSION_OFFSET = 3.5
    private const val INK_LEVEL = 150
    private const val MAX_GLYPH = 30

    /**
     * @param columns the strip, column-major, 24 rows per column.
     * @param peakFrames peak frame of each emitted symbol, ascending.
     * @param isSpace whether each symbol is whitespace (takes the gap between its neighbours).
     * @return for each symbol `[left, right)` in strip pixels.
     */
    fun spans(columns: ByteArray, width: Int, peakFrames: List<Int>, isSpace: List<Boolean>): Array<DoubleArray> {
        val n = peakFrames.size
        val ink = BooleanArray(width) { x ->
            var any = false
            for (y in 2 until 22) if ((columns[x * 24 + y].toInt() and 0xFF) < INK_LEVEL) { any = true; break }
            any
        }
        var start = ink.indexOfFirst { it }
        var end = ink.indexOfLast { it } + 1
        if (start < 0) { start = 0; end = width }
        val right = IntArray(n)
        for (i in 0 until n) {
            val guess = (peakFrames[i] * FRAME + EMISSION_OFFSET).toInt().coerceIn(start, end)
            right[i] = if (i == n - 1) end else snapToGap(ink, guess, start, end)
        }
        for (i in 1 until n) right[i] = max(right[i], right[i - 1])
        val out = Array(n) { DoubleArray(2) }
        for (i in 0 until n) {
            var l = if (i == 0) start else right[i - 1]
            var r = right[i]
            if (!isSpace[i]) {
                while (l < r && !ink[l]) l++
                while (r > l && !ink[r - 1]) r--
                if (r <= l) { l = if (i == 0) start else right[i - 1]; r = right[i] }
                if (r - l > MAX_GLYPH) {
                    // A glyph cannot be this wide: an unrecognised mark sits to its left.
                    l = r - 22
                    while (l < r && !ink[l]) l++
                }
            }
            out[i][0] = l.toDouble()
            out[i][1] = max(r, l + 1).toDouble()
        }
        // Whitespace fills the gap between the neighbouring glyphs' ink.
        for (i in 0 until n) if (isSpace[i]) {
            val l = if (i > 0) out[i - 1][1] else out[i][0]
            val r = if (i < n - 1) out[i + 1][0] else out[i][1]
            if (r > l) { out[i][0] = l; out[i][1] = r }
        }
        return out
    }

    private fun snapToGap(ink: BooleanArray, guess: Int, start: Int, end: Int): Int {
        for (d in 0..5) {
            val a = guess + d
            if (a in start until end && !ink[a]) return a
            val b = guess - d
            if (b in start until end && !ink[b]) return b
        }
        return min(guess, end)
    }
}
