package com.szdict.app.feature.ocr

import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.DictMode
import com.szdict.core.api.PassageSegment
import com.szdict.core.api.SegmentationMode
import com.szdict.recognition.OcrLine

/**
 * One recognised line with the user's corrections laid over it. The recognition result is never
 * modified: [picks] maps a symbol index to the alternative the user chose there, and picking the
 * recogniser's own best reading removes the entry again (undo). Symbols are addressed by index, so
 * a correction whose text has another UTF-16 length cannot shift anything else.
 */
class CorrectedLine(val original: OcrLine, val picks: Map<Int, String> = emptyMap()) {
    /** The displayed text of every symbol. */
    val chars: List<String> = original.chars.mapIndexed { i, c -> picks[i] ?: c.text }

    private val starts: IntArray = IntArray(chars.size + 1).also { s ->
        for (i in chars.indices) s[i + 1] = s[i] + chars[i].length
    }

    /** Exactly the concatenation of [chars] (CAM-052). */
    val text: String = chars.joinToString("")

    val hasHanzi: Boolean get() = text.any { isHanzi(it) }
    val corrected: Boolean get() = picks.isNotEmpty()

    /** UTF-16 start of symbol [index] in [text]. */
    fun charStart(index: Int): Int = starts[index]

    /** The offset a tap on symbol [index] selects: its first non-whitespace code point, or null. */
    fun selectableOffset(index: Int): Int? = firstSelectableOffset(chars[index])?.let { starts[index] + it }

    /** Index of the symbol containing UTF-16 [offset] of [text], or null. */
    fun charIndexAt(offset: Int): Int? {
        if (offset < 0 || offset >= text.length) return null
        var lo = 0
        var hi = chars.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun isCorrected(index: Int): Boolean = picks.containsKey(index)

    /**
     * Replaces symbol [index] with [text], which must be one of the readings the recogniser offered
     * there; anything else (or an index out of range) returns this line unchanged.
     */
    fun withPick(index: Int, text: String): CorrectedLine {
        val char = original.chars.getOrNull(index) ?: return this
        if (text == chars[index]) return this
        if (text == char.text) return CorrectedLine(original, picks - index)
        if (text.isEmpty() || char.alternatives.none { it.text == text }) return this
        return CorrectedLine(original, picks + (index to text))
    }

    companion object {
        fun isHanzi(c: Char): Boolean = c in '㐀'..'䶿' || c in '一'..'鿿' || c in '豈'..'﫿' || c.isSurrogate()
    }
}

/**
 * An immutable snapshot of the text under review: the lines as displayed, their analyses, and one
 * passage over all of them (lines joined by `\n`) that the word sheet works on, so stepping crosses
 * line boundaries (CAM-067, CAM-070). A line whose analysis failed has a null passage and
 * contributes no segments (CAM-055).
 */
class ReviewText(
    val texts: List<String>,
    val linePassages: List<AnalysedPassage?>,
    mode: DictMode,
    generation: Long,
) {
    private val starts: IntArray = IntArray(texts.size).also { s ->
        var at = 0
        for (i in texts.indices) {
            s[i] = at
            at += texts[i].length + 1
        }
    }

    val passage: AnalysedPassage = run {
        val segments = ArrayList<PassageSegment>()
        val covered = ArrayList<Int>()
        for ((i, part) in linePassages.withIndex()) {
            if (part == null || part.text != texts[i]) continue
            for (s in part.segments) segments += s.copy(start = s.start + starts[i])
            for (o in part.coveredOffsets) covered += o + starts[i]
        }
        AnalysedPassage(texts.joinToString("\n"), mode, SegmentationMode.HANZI, generation, segments, covered)
    }

    val text: String get() = passage.text

    fun lineStart(line: Int): Int = starts[line]

    /** Line containing passage [offset] (a newline belongs to the line before it). */
    fun lineAt(offset: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Offset within its line of passage [offset]. */
    fun offsetInLine(offset: Int): Int = offset - starts[lineAt(offset)]
}
