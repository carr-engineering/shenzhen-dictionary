package com.szdict.app.feature.hsk

import com.szdict.core.api.DailyPick
import com.szdict.core.api.DictMode
import com.szdict.core.api.Route
import com.szdict.core.api.Routes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The widget projection (`<mode>-widget.json`, DATA-130, contract/SCHEMA.md): everything the
 * word-of-the-day widget shows, so it never opens the dictionary database (SET-066). Platform
 * neutral, so a later iOS widget reuses the reader and the pick unchanged (SET-068).
 */
@Serializable
data class WidgetProjection(
    @SerialName("dataset_version") val datasetVersion: String = "",
    val rows: List<WidgetRow> = emptyList(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Parses projection JSON; null when it is malformed (the widget then shows `Word unavailable`). */
        fun parse(text: String): WidgetProjection? = runCatching { json.decodeFromString<WidgetProjection>(text) }.getOrNull()

        fun assetName(mode: DictMode): String = "${mode.datasetName}-widget.json"
    }
}

@Serializable
data class WidgetRow(
    val id: Int,
    /** Natural key (DATA-014) — what the widget's deep link carries (SET-064). */
    val key: String = "",
    val levels: List<Int> = emptyList(),
    val simp: String = "",
    val trad: String = "",
    /** Display reading: marked Pinyin (`péng you`) or Jyutping (`pang4 jau5`); empty when none. */
    val reading: String = "",
    val gloss: String = "",
)

object WidgetPick {
    const val ALL_LEVELS: Int = DailyPick.ALL_LEVELS

    fun contains(mask: Int, level: Int): Boolean = level in 1..7 && mask and (1 shl (level - 1)) != 0

    fun toggle(mask: Int, level: Int): Int = if (level in 1..7) mask xor (1 shl (level - 1)) else mask

    /**
     * SET-063: rows in the selected levels, de-duplicated by entry id, sorted by entry id, indexed
     * by the shared [DailyPick]. Null when the mask is empty or no row matches (SET-061, SET-082).
     */
    fun pick(rows: List<WidgetRow>, levelMask: Int, epochDay: Long): WidgetRow? {
        val mask = levelMask and ALL_LEVELS
        if (mask == 0) return null
        val candidates = rows.asSequence()
            .filter { row -> row.levels.any { contains(mask, it) } }
            .distinctBy { it.id }
            .sortedBy { it.id }
            .toList()
        val index = DailyPick.index(epochDay, mask, candidates.size)
        return candidates.getOrNull(index)
    }

    /** The entry route the widget opens (SET-064): natural key, in the widget's dictionary mode. */
    fun url(mode: DictMode, row: WidgetRow): String = Routes.url(Route.Word(mode, row.key))
}

/**
 * Tones and audio keys derived from a projection row's display reading, so the widget can colour
 * tones and play the installed recording without the database (SET-062, SET-065).
 */
object WidgetReading {
    data class Syllable(val text: String, val tone: Int, val audioKey: String)

    private const val MARKED = "āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜ"
    private const val BASES = "aeiouü"

    fun syllables(mode: DictMode, reading: String): List<Syllable> =
        reading.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { if (mode == DictMode.MANDARIN) pinyin(it) else jyutping(it) }

    private fun pinyin(marked: String): Syllable {
        var tone = 5
        val base = StringBuilder()
        for (c in marked.lowercase()) {
            val i = MARKED.indexOf(c)
            when {
                i >= 0 -> { tone = i % 4 + 1; base.append(BASES[i / 4]) }
                c == 'ń' -> { tone = 2; base.append('n') }
                c == 'ň' -> { tone = 3; base.append('n') }
                c == 'ǹ' -> { tone = 4; base.append('n') }
                c == 'ḿ' -> { tone = 2; base.append('m') }
                c in 'a'..'z' || c == 'ü' -> base.append(c)
            }
        }
        // Audio keys write ü (and u:) as v (contract/SCHEMA.md, packs/audio).
        return Syllable(marked, tone, base.toString().replace('ü', 'v') + tone)
    }

    private fun jyutping(syllable: String): Syllable {
        val lower = syllable.lowercase()
        val tone = lower.lastOrNull()?.takeIf { it in '1'..'6' }?.let { it - '0' } ?: 0
        return Syllable(syllable, tone, lower.filter { it in 'a'..'z' || it in '0'..'9' })
    }

    /** Candidate clips in play order: the whole-word recording (Mandarin), else one clip per syllable. */
    fun clipCandidates(mode: DictMode, reading: String): List<List<String>> {
        val keys = syllables(mode, reading).map { it.audioKey }
        if (keys.isEmpty()) return emptyList()
        return if (mode == DictMode.MANDARIN) {
            listOf(listOf("cmn-word/" + keys.joinToString("_")), keys.map { "cmn-syl/$it" })
        } else {
            listOf(keys.map { "yue-syl/$it" })
        }
    }
}
