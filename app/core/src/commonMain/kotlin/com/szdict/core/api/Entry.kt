package com.szdict.core.api

/** Romanisation system of a bracketed reading (DATA-053). Tone marks are rendered only for PINYIN. */
enum class Romanization { PINYIN, JYUTPING, TAILO, POJ, TEOCHEW, WADE_GILES, UNKNOWN }

/** Parsed definition segment (DATA-050, ENT-051). The app never parses prose at runtime. */
sealed interface Segment {
    /** Literal prose, verbatim. */
    data class Text(val text: String) : Segment

    /** A bracketed reading; not a link. [display] is already in display form. */
    data class Pronunciation(val display: String, val romanization: Romanization) : Segment

    /**
     * A link (ENT-051/053): one tap range covering [form] (ENT-055). [readingDisplay] is shown in
     * parentheses after the form when non-null. [targetId] is set when the reference resolved to
     * exactly one entry (DATA-054); otherwise open the sheet via `resolveReference`.
     */
    data class Ref(
        val traditional: String,
        val simplified: String,
        /** The form under the active script. */
        val form: String,
        val readingDisplay: String?,
        val readingCanonical: String?,
        val targetId: Int?,
        val romanization: Romanization,
    ) : Segment
}

/** One displayable definition (DATA-020). [number] is continuous across blocks and members (ENT-043). */
data class Sense(
    val number: Int,
    val text: String,
    /** Domain / register tags as labels, source order (ENT-058). */
    val tags: List<Label>,
    val segments: List<Segment>,
)

enum class BlockKind { CANTONESE, CANTONESE_ADAPTED, MANDARIN, HSK_PATCH, UNIHAN }

/** A Pinyin sub-group of a Mandarin block (ENT-112): its Pinyin and its senses. */
data class ReadingGroup(val reading: Reading, val senses: List<Sense>)

/**
 * Provenance block (DATA-022, ENT-110…116, ENT-126). [showLabel] is false only for the single
 * `mandarin` block of a Mandarin-dataset entry. [collapsedByDefault] / [collapsible] follow ENT-111.
 * For a Mandarin block [readingGroups] partitions [senses] (may be empty in the Mandarin dataset).
 */
data class SourceBlock(
    val kind: BlockKind,
    val label: Label,
    val showLabel: Boolean,
    val collapsedByDefault: Boolean,
    val collapsible: Boolean,
    val senses: List<Sense>,
    val readingGroups: List<ReadingGroup>,
)

/** A structured note with link targets (ENT-057). */
data class Note(
    val kind: String,
    val label: Label,
    val refs: List<Segment.Ref>,
    val reading: String?,
    val extra: String?,
)

enum class RedirectKind { VARIANT, OLD_VARIANT, ERHUA }

/** Redirect header (ENT-045…048). When [resolved], the member's blocks hold the target's senses. */
data class Redirect(
    val kind: RedirectKind,
    val text: String,
    val segments: List<Segment>,
    val targetId: Int?,
    val resolved: Boolean,
)

/**
 * DICT-tab section for one merge-group member (ENT-042…044); a non-merged entry has exactly one.
 * [alternateForm] is the member's alternate-script form for the `Traditional: 髮` label (null when
 * not merged). [redirect] is null when absent or suppressed (SRCH-082).
 */
data class MemberSection(
    val entryId: Int,
    val alternateForm: String?,
    val redirect: Redirect?,
    val blocks: List<SourceBlock>,
    val notes: List<Note>,
)

/** One HSK header badge (ENT-020 item 4, HSK-041…043): level 7 is the 7-9 band. */
data class HskBadge(
    val level: Int,
    /** Syllabus part-of-speech tokens of this level, de-duplicated (e.g. 动, 名). */
    val partsOfSpeech: List<String>,
    /** Their labels in the three UI languages; unknown tokens pass through verbatim (HSK-044). */
    val partOfSpeechLabels: List<Label>,
) {
    /** `Verb & Adverb` (HSK-044), empty when the level has no part of speech. */
    fun partOfSpeechText(lang: UiLang): String = partOfSpeechLabels.joinToString(" & ") { it.text(lang) }
}

enum class RelatedKind { WITHIN, STARTING_WITH, CONTAINING, CONTAINING_REVERSED }

/** One page of a WORDS-tab section (ENT-060…064). [total] is the section count; 0 = omit the section. */
data class RelatedPage(
    val kind: RelatedKind,
    /** The word shown in the section label (`Within 「word」`). */
    val word: String,
    val total: Int,
    val offset: Int,
    val cards: List<EntryCard>,
)

/** One inside-words row (SRCH-034, SRCH-105): a dictionary word at [start]/[length] (code points) inside the parent. */
data class InsideWord(val start: Int, val length: Int, val card: EntryCard)

/** Everything the entry page needs in one read (ENT-080), except decomposition and examples. */
data class EntryDetail(
    val mode: DictMode,
    val script: Script,
    /** The opened entry as a card: headword, alternate forms, reading, flags, audio plan. */
    val card: EntryCard,
    /** Alternative readings for the header line (ENT-020 item 3), note order. */
    val altReadings: List<AltReading>,
    /** Header badges: one per level, ascending, each with its own parts of speech (HSK-041). */
    val hskBadges: List<HskBadge>,
    /** DICT tab: one section per member with definitions; continuous sense numbering. */
    val members: List<MemberSection>,
    /** True when [members] comes from a merge group of more than one entry (ENT-042). */
    val merged: Boolean,
    /** First page of each non-empty WORDS section, in ENT-060 order. */
    val related: List<RelatedPage>,
    /** Distinct Hanzi of the displayed headword, headword order (ENT-120) — input for `decompose`. */
    val characters: List<String>,
    /** Number of example sentences available (MED-040), 0 when the pack is absent. */
    val exampleCount: Int,
) {
    val id: Int get() = card.id
    val naturalKey: String get() = card.naturalKey
}

/** UTF-16 range `[start, end)`. */
data class TextRange(val start: Int, val end: Int)

/** One example sentence (MED-041/042) in the active script, with every headword occurrence. */
data class Example(
    val id: Int,
    val text: String,
    val translation: String,
    val highlights: List<TextRange>,
)

data class ExamplePage(val total: Int, val offset: Int, val items: List<Example>)

/** Candidates for a tapped definition reference (ENT-054): exact matches by tier, then inside words. */
data class ReferenceCandidates(
    val form: String,
    /** Exact matches ordered by the ENT-046 tiers, then GLB-013; the first is the default. */
    val matches: List<EntryCard>,
    val defaultId: Int?,
    /** Every dictionary word contained inside the referenced text, sub-span order (SRCH-034). */
    val inside: List<InsideWord>,
)

// ---- Decomposition (ENT-120…129, ARCH-200) ----

enum class PartPosition { LEFT, RIGHT, TOP, BOTTOM, MIDDLE, OUTER, INNER, PART }

enum class PartKind { CHARACTER, RADICAL_FORM, UNENCODED, UNKNOWN }

/** Normalised rectangle inside the glyph's unit box, origin top-left. */
data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Where a part sits in the top-level glyph (ENT-129): exact [strokes] (indices into the character's
 * stroke graphics) when the pack has matches for it, otherwise null; [region] is always set (the
 * approximate region from the IDS operators; for an enclosing part it is the parent box and
 * [cutout] the inner rectangle to exclude).
 */
data class PartHighlight(val strokes: List<Int>?, val region: Region, val cutout: Region?)

/** One row of a decomposition tree (ENT-121…123). */
data class DecompNode(
    /** Position path from the top-level character (`[]` for it, `[1, 0]` …): the saved-state key. */
    val path: List<Int>,
    val kind: PartKind,
    /** Glyph to draw (the component itself; a radical form keeps its own code point). */
    val glyph: String,
    /** Radical form / variation indicator: the unified ideograph it is linked as (ENT-123). */
    val mapsTo: String?,
    /** Null for the top-level character. */
    val position: PartPosition?,
    /** IDS operator of this node's own structure line (e.g. `⿰`), null for a root. */
    val operator: String?,
    /** Structure line as stored (`⿰亻木`), empty for a root. */
    val ids: String,
    val isRoot: Boolean,
    /** The entry shown on the row (reading + first sense), null when the part has no entry. */
    val card: EntryCard?,
    /** Every entry of the part, ENT-054 order; one → nested route, several → candidate sheet. */
    val entryIds: List<Int>,
    /** Curated radical/component name (English; other languages via the label table when present). */
    val radicalName: String?,
    /** SVG path data (1024 em box) for an unencoded component. */
    val svgPath: String?,
    val strokeCount: Int?,
    val highlight: PartHighlight,
    val children: List<DecompNode>,
)

/** Decomposition of one listed character. [available] false = no record (omit the block). */
data class CharacterDecomposition(val character: String, val available: Boolean, val root: DecompNode?)
