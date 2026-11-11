package com.szdict.recognition.ocr

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Cuts a vertical text column into glyph cells using the row profile of its ink.
 *
 * Rows without ink separate bands. A Han glyph is roughly as tall as the column is wide, so bands are
 * merged while the result still fits one glyph and the gap between them looks like a gap inside a
 * glyph (二, 三, 昌) rather than between glyphs; over-tall runs of touching glyphs are divided evenly.
 */
internal object ColumnSlicer {
    /** @return `[top, bottom)` row ranges of the glyph cells, top to bottom. */
    fun slice(mask: ByteArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int): List<IntArray> {
        val width = (x1 - x0).toDouble()
        val bands = ArrayList<IntArray>() // top, bottom, ink pixels
        var start = -1
        var pixels = 0
        for (y in y0..y1) {
            var count = 0
            if (y < y1) for (x in x0 until x1) count += mask[y * stride + x]
            if (count >= 1) {
                if (start < 0) { start = y; pixels = 0 }
                pixels += count
            } else if (start >= 0) {
                // Specks are noise, not strokes.
                if (pixels >= max(4.0, width * .2)) bands.add(intArrayOf(start, y, pixels))
                start = -1
            }
        }
        if (bands.isEmpty()) return emptyList()
        val cells = ArrayList<IntArray>()
        var current = bands[0].copyOf(2)
        for (band in bands.drop(1)) {
            val gap = band[0] - current[1]
            val merged = band[1] - current[0]
            val small = current[1] - current[0] < width * .45 || band[1] - band[0] < width * .45
            if (merged <= width * 1.15 && (gap <= width * .22 || small)) {
                current[1] = band[1]
            } else {
                cells.add(current)
                current = band.copyOf(2)
            }
        }
        cells.add(current)
        // Glyphs set without leading touch each other: divide what is too tall for one glyph.
        val normal = cells.map { it[1] - it[0] }.filter { it >= width * .6 && it <= width * 1.3 }.sorted()
        val pitch = if (normal.isEmpty()) width * 1.05 else normal[normal.size / 2].toDouble()
        val out = ArrayList<IntArray>()
        for (cell in cells) {
            val height = cell[1] - cell[0]
            val parts = if (height > width * 1.5) max(2, (height / pitch).roundToInt()) else 1
            for (i in 0 until parts) out.add(intArrayOf(cell[0] + height * i / parts, cell[0] + height * (i + 1) / parts))
        }
        return out
    }
}
