package com.szdict.core.api

/** Run definition for segmentation (TXT-011). Search uses FULL; Reading, OCR and Voice use HANZI. */
enum class SegmentationMode { HANZI, FULL }

/**
 * One segment of an analysed passage (TXT-010). Offsets are UTF-16 offsets into the passage text
 * (GLB-002). [matched] segments carry the entry chosen for them and its reading (for tone colouring
 * and ruby); unmatched text inside a run is an unmatched segment; text outside runs has no segment.
 */
data class PassageSegment(
    val start: Int,
    val length: Int,
    val matched: Boolean,
    val entryId: Int,
    val reading: Reading,
    /** True when the reading aligns one syllable per code point (GLB-066). */
    val toneColourable: Boolean,
) {
    val end: Int get() = start + length
}

/**
 * Immutable analysis of a text (ARCH-070, TXT-016/017): passed by value, never by engine-side id.
 * It records the dictionary [generation] and [mode] it was produced in; a span lookup against a
 * different generation returns an empty result (TXT-075).
 */
data class AnalysedPassage(
    val text: String,
    val mode: DictMode,
    val segmentation: SegmentationMode,
    val generation: Long,
    val segments: List<PassageSegment>,
    /** UTF-16 start offsets of every covered character (TXT-020), ascending — drives stepping (GLB-058). */
    val coveredOffsets: List<Int>,
) {
    fun isCovered(offset: Int): Boolean = coveredOffsets.binarySearch(normaliseOffset(offset)) >= 0

    /** Segment containing [offset], or null. */
    fun segmentAt(offset: Int): PassageSegment? {
        var lo = 0
        var hi = segments.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = segments[mid]
            when {
                offset < s.start -> hi = mid - 1
                offset >= s.end -> lo = mid + 1
                else -> return s
            }
        }
        return null
    }

    /** Nearest covered character before / after [offset] (TXT-033, TXT-070), or null. */
    fun previousCovered(offset: Int): Int? {
        val i = coveredOffsets.binarySearch(normaliseOffset(offset))
        val idx = if (i >= 0) i - 1 else -i - 2
        return coveredOffsets.getOrNull(idx)
    }

    fun nextCovered(offset: Int): Int? {
        val i = coveredOffsets.binarySearch(normaliseOffset(offset))
        val idx = if (i >= 0) i + 1 else -i - 1
        return coveredOffsets.getOrNull(idx)
    }

    /** A tap inside a surrogate pair normalises to the start of the code point (GLB-002). */
    fun normaliseOffset(offset: Int): Int =
        if (offset in 1 until text.length && text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate()) offset - 1 else offset

    companion object {
        fun empty(text: String, mode: DictMode, segmentation: SegmentationMode, generation: Long) =
            AnalysedPassage(text, mode, segmentation, generation, emptyList(), emptyList())
    }
}

/** Header variant of the word-candidate sheet (TXT-020…023). */
enum class SpanKind {
    /** `Words at 「X」` */
    WORDS_AT,

    /** `「X」 appears in` */
    APPEARS_IN,

    /** `At 「X」` — not part of a run, or nothing found: body `No dictionary entry`. */
    NONE,
}

/** One sheet row: a word covering the tapped character. Offsets are UTF-16 into the passage text. */
data class SpanCandidate(
    val start: Int,
    val length: Int,
    val card: EntryCard,
    /** The segment the segmenter chose for this position (TXT-021 item 1). */
    val isDefault: Boolean,
)

data class SpanCandidates(
    val kind: SpanKind,
    /** The tapped character (one code point), or empty when the offset is out of range. */
    val character: String,
    /** Normalised UTF-16 offset of the tapped character. */
    val offset: Int,
    val candidates: List<SpanCandidate>,
    val previousCovered: Int?,
    val nextCovered: Int?,
) {
    companion object {
        fun none(character: String = "", offset: Int = 0) = SpanCandidates(SpanKind.NONE, character, offset, emptyList(), null, null)
    }
}

// ---- HSK (docs/06) ----

/** Level 7 is the 7-9 band. [wordCount] is the syllabus row count shown in the header (HSK-021). */
data class HskLevelInfo(val level: Int, val wordCount: Int, val cardCount: Int, val charCount: Int)

/**
 * One HSK grid card (HSK-022/026/027): [key] is unique and stable (from the dataset row id).
 * Homograph rows of one level linking to the same entry are merged into one card ([rowIds]).
 * Merge grouping is not applied to [card].
 */
data class HskCard(val key: Long, val level: Int, val rowIds: List<Int>, val card: EntryCard)

/** One recognition-character card (HSK-098). [card] carries no HSK levels (never a badge). */
data class HskCharCard(val character: String, val level: Int, val writing: Boolean, val card: EntryCard)
