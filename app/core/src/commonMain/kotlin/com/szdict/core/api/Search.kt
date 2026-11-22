package com.szdict.core.api

/** Query classification (SRCH-020). */
enum class QueryKind { EMPTY, HANZI, MIXED, ROMANISATION, LATIN }

enum class SearchSide { CHINESE, ENGLISH }

/**
 * Section kinds, in the order they can appear on a side (SRCH-033, SRCH-064, SRCH-076, SRCH-102).
 *
 * Chinese side, HANZI / MIXED query: [GROUPS], [PATTERN], [STARTS_WITH], [CONTAINS], [RELATED].
 * Chinese side, ROMANISATION query: [MATCHES] (whole-query matches, unlabelled), [COMBINATIONS],
 * [MORE_MATCHES] (longer/prefix matches then the Unicode-only tier; an unlabelled continuation of
 * the ranked list — the split exists only to place Combinations per SRCH-064).
 * Chinese side, LATIN query: [MATCHES] (Latin-headword prefix search, SRCH-044).
 * English side: [ENGLISH] (Mandarin mode, one flat list) or [ENGLISH_CC_CANTO],
 * [ENGLISH_CC_CEDICT], [ENGLISH_UNIHAN] (Cantonese mode, SRCH-076).
 */
enum class SectionKind {
    GROUPS, MATCHES, COMBINATIONS, MORE_MATCHES, PATTERN, STARTS_WITH, CONTAINS, RELATED,
    ENGLISH, ENGLISH_CC_CANTO, ENGLISH_CC_CEDICT, ENGLISH_UNIHAN,
}

/**
 * One exact or segmented group of a Hanzi search (SRCH-033). A matched group lists every entry
 * (reading) of its headword, already ordered (SRCH-075); an [inert] group is an unmatched text run
 * rendered as a NO MATCH row (SRCH-098). [insideOf]/[insideCount] drive the single inside-words
 * button after the group's last card (SRCH-103/105): rows come from `insideWords(insideOf)`.
 */
data class WordGroup(
    val text: String,
    /** UTF-16 offset and length in `SearchResult.displayText`. */
    val start: Int,
    val length: Int,
    val inert: Boolean,
    val cards: List<EntryCard>,
    val insideOf: Int?,
    val insideCount: Int,
    /**
     * A foreign word without a headword whose [cards] are English-side whole-word matches
     * (SRCH-028): shown under the typed word with an `English match` label.
     */
    val english: Boolean = false,
    /** A run of punctuation or symbols (SRCH-029): [inert], rendered compact and muted without `NO MATCH`. */
    val punctuation: Boolean = false,
    /** A foreign-word group (SRCH-028), whatever it resolved to; left out when combinations read the word as romanisation (SRCH-069). */
    val foreignWord: Boolean = false,
)

/**
 * One slot of a combination row (SRCH-065): 1…4 alternative words for the same syllables, each its
 * own tap target; the first is the word of the row's best sequence.
 */
data class CombinationSlot(val alternatives: List<EntryCard>)

/**
 * One combination row (SRCH-063/065): sequences with the same word boundaries grouped into slots
 * (`就/九 · 点`); a row offers the cross-product of its slots. [score] is its best sequence's.
 */
data class Combination(val slots: List<CombinationSlot>, val score: Double) {
    /** The row's best sequence, one word per slot. */
    val words: List<EntryCard> get() = slots.map { it.alternatives[0] }
}

/**
 * A section of one side. [count] is the section's total row count (chip count). [cards] holds the
 * first page; [refs] is the complete ranked row list (rank-before-limit, SRCH-071) — hydrate further
 * windows with `Dictionary.cards(refs.subList(...), script)` (ARCH-034). Card keys are
 * `key + entry id` (SRCH-169). [groups] is set for [SectionKind.GROUPS], [combinations] for
 * [SectionKind.COMBINATIONS]; both are fully hydrated.
 */
data class SearchSection(
    val kind: SectionKind,
    val key: String,
    /** The 「X」 of `Starts with 「X」` / `Contains 「X」 elsewhere`, else null. */
    val labelArg: String?,
    val count: Int,
    val cards: List<EntryCard> = emptyList(),
    val refs: List<RowRef> = emptyList(),
    val groups: List<WordGroup> = emptyList(),
    val combinations: List<Combination> = emptyList(),
) {
    val hasMore: Boolean get() = refs.size > cards.size
}

/** One side (page) of a search response (SRCH-002). */
data class SideResult(
    val side: SearchSide,
    /** Result count for the section indicator; combinations and inside-words rows excluded (SRCH-063). */
    val count: Int,
    val sections: List<SearchSection>,
    /** Chinese side only: sentence mode (SRCH-035) — show the passage card and `Words` label. */
    val isSentence: Boolean = false,
    /** Chinese side, sentence mode: the analysed display text for the passage card (SRCH-100). */
    val passage: AnalysedPassage? = null,
    /** True when the ranked list hit the result cap (SRCH-073); the cap was applied after ranking. */
    val capped: Boolean = false,
) {
    val isEmpty: Boolean get() = count == 0 && sections.none { it.combinations.isNotEmpty() || it.groups.isNotEmpty() }
}

/**
 * Response for one query (docs/03 §7). Both sides are always computed (SRCH-002).
 *
 * Side selection (SRCH-024): when [forcedSide] is non-null the UI selects it and must not persist
 * it as the remembered side (SRCH-026); when null the remembered side applies. Besides the rules
 * of SRCH-024 a side is forced when the other one has no result at all for the query (`love` opens
 * on the English page, whatever side is remembered).
 */
data class SearchResult(
    /** The query as given (after line-break normalisation, SRCH-011). */
    val query: String,
    /** Matching key (SRCH-010). */
    val matchingKey: String,
    /** Gentle display normalisation (SRCH-012), used by the passage card and section labels. */
    val displayText: String,
    val kind: QueryKind,
    val script: Script,
    val forcedSide: SearchSide?,
    val chinese: SideResult,
    val english: SideResult,
    /** True on the first emission of `searchProgressive` while the lattice decoder still runs (SRCH-066). */
    val combinationsPending: Boolean = false,
    /** Data generation of the dictionary that produced this response (GLB-044). */
    val generation: Long = 0,
) {
    val isBlank: Boolean get() = kind == QueryKind.EMPTY
    fun side(side: SearchSide): SideResult = if (side == SearchSide.CHINESE) chinese else english

    companion object {
        fun empty(query: String, script: Script, generation: Long = 0) = SearchResult(
            query, "", "", QueryKind.EMPTY, script, null,
            SideResult(SearchSide.CHINESE, 0, emptyList()), SideResult(SearchSide.ENGLISH, 0, emptyList()),
            false, generation,
        )
    }
}

/** Tuning knobs; the defaults are the spec values. */
data class SearchOptions(
    /** Cards hydrated per ranked section in the response (the rest via `cards(refs)`). */
    val pageSize: Int = 40,
    /** Result cap per side, applied after ranking (SRCH-073). */
    val resultCap: Int = 1200,
    /** Compute multi-word combinations (SRCH-060). */
    val combinations: Boolean = true,
)
