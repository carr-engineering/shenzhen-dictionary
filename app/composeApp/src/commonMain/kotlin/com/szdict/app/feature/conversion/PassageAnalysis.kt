package com.szdict.app.feature.conversion

import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.Dictionary
import com.szdict.core.api.PassageSegment
import com.szdict.core.api.SegmentationMode
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Splits a long passage at natural boundaries so no single analysis exceeds its budget (TXT-015). */
object PassageChunker {
    const val CHUNK = 400
    private const val PUNCTUATION = "。！？；，、：…．.!?;,:\n\r"

    /** Half-open chunk ranges covering [text] in order; a single range when it is short. */
    fun chunks(text: String, limit: Int = CHUNK): List<IntRange> {
        if (text.length <= limit) return listOf(0 until text.length)
        val out = ArrayList<IntRange>()
        var start = 0
        while (start < text.length) {
            val hardEnd = minOf(text.length, start + limit)
            var end = hardEnd
            if (hardEnd < text.length) {
                // Prefer the last punctuation mark, then the last whitespace, in the second half of the window.
                val floor = start + limit / 2
                var cut = -1
                var i = hardEnd - 1
                while (i >= floor) {
                    if (text[i] in PUNCTUATION) { cut = i + 1; break }
                    i--
                }
                if (cut < 0) {
                    i = hardEnd - 1
                    while (i >= floor) {
                        if (text[i].isWhitespace()) { cut = i + 1; break }
                        i--
                    }
                }
                end = if (cut > 0) cut else hardEnd
                // Never split a surrogate pair at the hard cap.
                if (end < text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) end--
            }
            out += start until end
            start = end
        }
        return out
    }
}

/**
 * Analyses [text] chunk by chunk and returns one immutable passage over the whole text, with
 * segment and coverage offsets relative to it. Cancellable between chunks.
 */
suspend fun Dictionary.analyseChunked(text: String, segmentation: SegmentationMode = SegmentationMode.HANZI): AnalysedPassage {
    val ranges = PassageChunker.chunks(text)
    if (ranges.size == 1) return analyse(text, segmentation)
    val segments = ArrayList<PassageSegment>()
    val covered = ArrayList<Int>()
    var last: AnalysedPassage? = null
    for (range in ranges) {
        coroutineContext.ensureActive()
        val part = analyse(text.substring(range.first, range.last + 1), segmentation)
        val base = range.first
        for (s in part.segments) segments += s.copy(start = s.start + base)
        for (o in part.coveredOffsets) covered += o + base
        last = part
    }
    val head = last ?: return analyse(text, segmentation)
    return AnalysedPassage(text, head.mode, head.segmentation, head.generation, segments, covered)
}
