package com.szdict.recognition.ocr

import kotlin.math.max
import kotlin.math.min

/**
 * Turns recogniser emission frames into per-character x spans on the normalised line strip.
 *
 * The recogniser is a left-to-right LSTM over 4-px columns, so it emits a symbol when it has just
 * seen the glyph's right edge: measured on rendered text, the glyph's ink ends about 3.9 px (Chinese
 * recogniser) or 2 px (Latin recogniser) after the start of its peak frame. Boundaries are placed
 * there, snapped to a nearby ink-free column, and each span is then tightened to the ink it contains.
 */
internal object CharLayout {
    private const val FRAME = 4
    private const val INK_LEVEL = 150
    private const val MAX_GLYPH = 30

    /**
     * @param columns the strip, column-major, 24 rows per column.
     * @param peakFrames peak frame of each emitted symbol, ascending.
     * @param isSpace whether each symbol is whitespace (takes the gap between its neighbours).
     * @param emissionOffset pixels from the start of a symbol's peak frame to the end of its ink.
     * @return for each symbol `[left, right)` in strip pixels.
     */
    fun spans(columns: ByteArray, width: Int, peakFrames: List<Int>, isSpace: List<Boolean>, emissionOffset: Double): Array<DoubleArray> {
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
            val guess = (peakFrames[i] * FRAME + emissionOffset + .5).toInt().coerceIn(start, end)
            right[i] = if (i == n - 1) end else snapToGap(ink, guess, start, end)
        }
        for (i in 1 until n) right[i] = max(right[i], right[i - 1])
        val out = Array(n) { DoubleArray(2) }
        // A space is emitted late, when the next glyph has already begun, so its frame says nothing
        // about where that glyph starts: glyph spans are laid out from glyph boundaries only.
        var previousRight = start
        for (i in 0 until n) {
            var l = previousRight
            var r = max(right[i], l)
            if (!isSpace[i]) {
                val from = l
                while (l < r && !ink[l]) l++
                while (r > l && !ink[r - 1]) r--
                if (r <= l) { l = from; r = max(right[i], l) }
                if (r - l > MAX_GLYPH) {
                    // A glyph cannot be this wide: an unrecognised mark sits to its left.
                    l = r - 22
                    while (l < r && !ink[l]) l++
                }
                previousRight = max(right[i], l)
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
        for (d in 0..3) {
            val a = guess + d
            if (a in start until end && !ink[a]) return a
            val b = guess - d
            if (b in start until end && !ink[b]) return b
        }
        return min(guess, end)
    }
}
