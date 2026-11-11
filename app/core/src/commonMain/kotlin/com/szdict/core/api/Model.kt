package com.szdict.core.api

/** Dictionary mode (PROD-040). One dataset per mode, identical schema (DATA-001). */
enum class DictMode(val routeSegment: String, val datasetName: String) {
    MANDARIN("zh", "mandarin"),
    CANTONESE("yue", "cantonese"),
}

/** Script mode (GLB-003): which form is the headword; the other is the alternate form. */
enum class Script { SIMPLIFIED, TRADITIONAL }

/** UI language (GLB-100). */
enum class UiLang(val tag: String) { EN("en"), ZH_HANS("zh-Hans"), ZH_HANT("zh-Hant") }

/** A row of the dataset's label table (DATA-060) in all three UI languages. */
data class Label(val key: String, val en: String, val zhHans: String, val zhHant: String) {
    fun text(lang: UiLang): String = when (lang) {
        UiLang.EN -> en
        UiLang.ZH_HANS -> zhHans.ifEmpty { en }
        UiLang.ZH_HANT -> zhHant.ifEmpty { zhHans.ifEmpty { en } }
    }

    companion object {
        /** Fallback for a key the dataset does not carry: the key is shown verbatim. */
        fun missing(key: String) = Label(key, key, key, key)
    }
}

/**
 * A pronunciation (GLB-010): display string, canonical numbered string, and parallel toneless
 * syllables / tone numbers (0 = unspecified) for tone colouring and ruby.
 */
data class Reading(
    val display: String,
    val canonical: String,
    val syllables: List<String>,
    val tones: List<Int>,
) {
    val isEmpty: Boolean get() = display.isEmpty() && syllables.isEmpty()

    companion object {
        val EMPTY = Reading("", "", emptyList(), emptyList())
    }
}

/** An alternative reading of an entry (`also pr.` / `Taiwan pr.`, ENT-020 item 3, SRCH-078). */
data class AltReading(
    /** `also_pr` or `taiwan_pr`. */
    val kind: String,
    val label: Label,
    val reading: Reading,
    /** Ordinal into the entry's alternative readings (note order). */
    val ordinal: Int,
)

/** One structured note, pre-joined for a notes row: `label: text` (ENT-057). */
data class NoteLine(val kind: String, val label: Label, val text: String)

enum class AudioClipKind { WORD, SYLLABLE }

/** One recorded clip, addressed by canonical key (MED-004/005). File = packs/audio/<dir>/<key>.<ext>. */
data class AudioClip(val kind: AudioClipKind, val key: String)

/**
 * Audio plan (MED-003/006/007). [clips] is the ordered playback list; empty means no recording —
 * the platform falls back to TTS on [ttsText] (MED-011). [verified] is true when the plan was
 * checked against `packs/audio/catalog.json`; otherwise the platform must probe the files itself.
 */
data class AudioPlan(
    val clips: List<AudioClip>,
    val gaps: Boolean,
    val wordKey: String?,
    val syllableKeys: List<String>,
    val verified: Boolean,
    val ttsText: String,
) {
    val hasRecording: Boolean get() = clips.isNotEmpty()

    companion object {
        fun ttsOnly(text: String) = AudioPlan(emptyList(), false, null, emptyList(), false, text)
    }
}

/**
 * Cantonese-mode English result matched only through Mandarin-block senses (SRCH-077): the card
 * shows this Pinyin after a muted `Mandarin` tag and these senses only.
 */
data class MandarinRow(val groupIndex: Int, val reading: Reading, val definitions: List<String>)

/**
 * Reference to one result row. Enough to hydrate the exact same card later (`Dictionary.cards`).
 * [altOrdinal] / [altKey] identify the alternative reading the row was matched through (SRCH-078);
 * [mandarinRow] marks a CC-CEDICT row (SRCH-077).
 */
data class RowRef(
    val entryId: Int,
    val altOrdinal: Int = -1,
    val altKey: String? = null,
    val mandarinRow: Boolean = false,
)

/**
 * Everything a result card / compact row / sheet row / HSK card needs (SRCH-094, ENT-070). A card
 * never needs a second call to render. Variant merging (SRCH-080) is already applied: a merged card
 * carries the group's alternate forms and concatenated, de-duplicated definitions.
 */
data class EntryCard(
    val id: Int,
    val naturalKey: String,
    val simplified: String,
    val traditional: String,
    /** Headword under the script the card was built for. */
    val headword: String,
    /** Alternate form (other script, when it differs) or the merged variant list (SRCH-082). */
    val alternateForms: List<String>,
    val reading: Reading,
    /** True when char count == syllable count, so per-character tone colouring is valid (GLB-066). */
    val toneColourable: Boolean,
    /** True when the entry has no reading in the active romanisation (ENT-116, SRCH-094). */
    val noReading: Boolean,
    /** Numbered definitions, in display order (redirect target senses already substituted). */
    val definitions: List<String>,
    /** Muted redirect header lines (ENT-047), empty when none or suppressed by merging. */
    val redirectHeaders: List<String>,
    /** Structured notes; cards show them only when [definitions] is empty (SRCH-094). */
    val notes: List<NoteLine>,
    /** HSK word levels to badge: direct, extra and via-variant, ascending, distinct (HSK-040). */
    val hskLevels: List<Int>,
    /** `Unicode (Unihan)` / `HSK parser patch` provenance tag, or null (SRCH-079, ENT-126). */
    val provenance: Label?,
    val isProperNoun: Boolean,
    val isUnihanOnly: Boolean,
    /** Cantonese mode: the entry has a CC-Canto block (SRCH-076). */
    val hasCantoBlock: Boolean,
    /** Frequency rank, 1 = most common, 1000000 = unranked. */
    val frequencyRank: Int,
    /** Number of Hanzi (GLB-001) in the headword; the inside-words button needs >= 2 (SRCH-105). */
    val hanziCount: Int,
    /** Rows behind the inside-words button (0 = no button). Fetch them with `insideWords(id)`. */
    val insideCount: Int,
    /** Ids of all merge-group members shown by this card (the card's own id first). */
    val memberIds: List<Int>,
    /** Set when the card was matched through an alternative reading (SRCH-078). */
    val matchedAlt: AltReading?,
    /** Set for a CC-CEDICT row in Cantonese mode (SRCH-077). */
    val mandarinRow: MandarinRow?,
    val audio: AudioPlan,
) {
    /** One-line summary of the definitions. */
    val gloss: String get() = definitions.joinToString("; ")
    val ref: RowRef get() = RowRef(id, matchedAlt?.ordinal ?: -1, null, mandarinRow != null)
}

/** A candidate with a recogniser score (higher = better), for handwriting / OCR re-ranking. */
data class ScoredText(val text: String, val score: Double)

/** Dataset source credit (SET-042). */
data class SourceInfo(
    val name: String,
    val version: String,
    val date: String,
    val licence: String,
    val url: String,
    val role: String,
)

/** Dataset metadata (DATA-002, DATA-155). */
data class DatasetInfo(
    val mode: DictMode,
    val schemaVersion: Int,
    val datasetId: String,
    val datasetVersion: String,
    val builtAt: String,
    val language: String,
    val romanization: String,
    val toneCount: Int,
    val entryCount: Int,
    val maxHeadwordLen: Int,
    val maxWordLen: Int,
    val hasFrequencyData: Boolean,
    val hasHsk: Boolean,
    val hasBigramModel: Boolean,
    /** Language-model companion present (abbreviated input + combinations available). */
    val hasLanguageModel: Boolean,
    val hasSentences: Boolean,
    val hasDecomposition: Boolean,
    val sources: List<SourceInfo>,
    /** Every metadata key as stored, for diagnostics. */
    val raw: Map<String, String>,
)
