package com.szdict.recognition.ocr

import com.szdict.recognition.OcrAlt

/**
 * Re-ranks per-character candidates with what the rest of the line says about its script.
 *
 * The recogniser's alphabet mixes Han with kana, bopomofo, Latin letters, digits and symbols, and
 * many of those are near-copies of a Han character (エ for 工, ロ for 口, ー for 一, カ for 力, 7 for 了).
 * In a line that is otherwise Chinese such a reading is almost never right, so there:
 *  - below a Han top reading, candidates of other scripts are down-weighted;
 *  - a kana or bopomofo top reading gives way to a Han candidate with at least a tenth of its score.
 * Latin letters, digits and punctuation that the recogniser put first are left alone (prices, floor
 * numbers and names are common in Chinese text), and lines that are Japanese or Latin are not touched.
 *
 * Scores stay probabilities: the mass taken from down-weighted candidates is spread over the others
 * in proportion, so a list's total is unchanged.
 */
internal object ScriptPrior {
    private const val KANA_WEIGHT = .1f
    private const val OTHER_WEIGHT = .25f

    enum class Kind { HAN, KANA, LETTER, OTHER, SPACE }

    fun kind(text: String): Kind {
        if (text.isBlank()) return Kind.SPACE
        val c = text.firstCodePoint()
        return when {
            isHan(c) -> Kind.HAN
            // Hiragana, katakana (also half-width and the prolonged sound mark), bopomofo, hangul.
            c in 0x3040..0x30FF || c in 0x31F0..0x31FF || c in 0xFF66..0xFF9F || c in 0x3100..0x312F || c in 0xAC00..0xD7AF -> Kind.KANA
            (c in 0x41..0x5A) || (c in 0x61..0x7A) || (c in 0xC0..0x24F) || (c in 0x370..0x52F) || (c in 0xFF21..0xFF5A) -> Kind.LETTER
            else -> Kind.OTHER
        }
    }

    /** Whether a line with these top readings is Chinese text (possibly with some Latin or digits in it). */
    fun isChinese(top: List<String>): Boolean {
        var han = 0
        var kana = 0
        var letters = 0
        for (t in top) when (kind(t)) {
            Kind.HAN -> han++
            Kind.KANA -> kana++
            Kind.LETTER -> letters++
            else -> {}
        }
        // Japanese text is rarely less than a third kana; a stray kana look-alike does not make a line Japanese.
        return han >= 2 && han * 2 >= han + kana + letters && kana * 3 <= han + kana
    }

    /**
     * @param candidates for each character of one line, its candidates best first.
     * @param keep how many candidates to return per character.
     */
    fun rerank(candidates: List<List<OcrAlt>>, keep: Int): List<List<OcrAlt>> {
        if (!isChinese(candidates.map { it.first().text })) return candidates.map { it.take(keep) }
        return candidates.map { list ->
            val top = kind(list.first().text)
            if (top != Kind.HAN && top != Kind.KANA) return@map list.take(keep)
            val weighted = list.mapIndexed { i, alt ->
                val weight = when (kind(alt.text)) {
                    Kind.HAN -> 1f
                    Kind.KANA -> KANA_WEIGHT
                    else -> OTHER_WEIGHT
                }
                Triple(alt, alt.score * weight, i)
            }
            val before = list.sumOf { it.score.toDouble() }
            val after = weighted.sumOf { it.second.toDouble() }
            val scale = if (after > 0) (before / after).toFloat() else 1f
            // A Han top reading always stays first; ties keep the recogniser's order.
            val ordered = if (top == Kind.HAN) {
                listOf(weighted.first()) + weighted.drop(1).sortedWith(compareByDescending<Triple<OcrAlt, Float, Int>> { it.second }.thenBy { it.third })
            } else {
                weighted.sortedWith(compareByDescending<Triple<OcrAlt, Float, Int>> { it.second }.thenBy { it.third })
            }
            ordered.take(keep).map { OcrAlt(it.first.text, (it.second * scale).coerceAtMost(1f)) }
        }
    }
}
