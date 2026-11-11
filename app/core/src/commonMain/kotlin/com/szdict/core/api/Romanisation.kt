package com.szdict.core.api

/** Romanisation display helpers (GLB-011, GLB-015, GLB-066). Pure functions, no dataset access. */
object Romanisation {
    private const val VOWELS = "aeiouü"
    private val MARKED = mapOf(
        'a' to "āáǎà", 'e' to "ēéěè", 'i' to "īíǐì", 'o' to "ōóǒò", 'u' to "ūúǔù", 'ü' to "ǖǘǚǜ",
        'A' to "ĀÁǍÀ", 'E' to "ĒÉĚÈ", 'I' to "ĪÍǏÌ", 'O' to "ŌÓǑÒ", 'U' to "ŪÚǓÙ", 'Ü' to "ǕǗǙǛ",
    )
    private val COMBINING = charArrayOf('̄', '́', '̌', '̀')

    /**
     * Converts one numbered Pinyin syllable to tone marks (GLB-015): `hao3` → `hǎo`, `lu:e4` → `lüè`,
     * `ng2` → `ńg`, `ma5` → `ma`. A syllable without a trailing tone digit is returned with only the
     * `u:`/`v` → `ü` folding applied. An uppercase initial keeps its case.
     */
    fun pinyinSyllableToMarked(syllable: String): String {
        if (syllable.isEmpty()) return syllable
        var s = syllable.replace("u:", "ü").replace("U:", "Ü").replace('v', 'ü').replace('V', 'Ü')
        val last = s.last()
        if (last !in '0'..'9') return s
        val tone = last - '0'
        s = s.dropLast(1)
        if (tone !in 1..4 || s.isEmpty()) return s
        val lower = s.lowercase()
        var idx = lower.indexOf('a')
        if (idx < 0) idx = lower.indexOf('e')
        if (idx < 0) {
            val ou = lower.indexOf("ou")
            if (ou >= 0) idx = ou
        }
        if (idx < 0) idx = lower.indexOfLast { it in VOWELS }
        if (idx < 0) {
            // Syllabic nasal: combining mark on the m or n.
            val n = lower.indexOfFirst { it == 'm' || it == 'n' }
            if (n < 0) return s
            return s.substring(0, n + 1) + COMBINING[tone - 1] + s.substring(n + 1)
        }
        val marked = MARKED[s[idx]]?.get(tone - 1) ?: return s
        return s.substring(0, idx) + marked + s.substring(idx + 1)
    }

    /** Converts a numbered Pinyin string (`ni3 hao3`) to tone marks, syllable by syllable; separators are kept. */
    fun pinyinToMarked(numbered: String): String {
        val sb = StringBuilder()
        val token = StringBuilder()
        fun flush() {
            if (token.isNotEmpty()) {
                sb.append(pinyinSyllableToMarked(token.toString()))
                token.clear()
            }
        }
        for (c in numbered) {
            if (c.isLetterOrDigit() || c == ':') {
                token.append(c)
                if (c in '0'..'9') flush()
            } else {
                flush()
                sb.append(c)
            }
        }
        flush()
        return sb.toString()
    }

    /** Display form of a canonical numbered reading for [mode]: tone marks for Pinyin, verbatim Jyutping. */
    fun display(mode: DictMode, canonical: String): String =
        if (mode == DictMode.MANDARIN) pinyinToMarked(canonical) else canonical

    /** Display form of a bracketed reading inside a definition: tone marks only for Pinyin (ENT-052). */
    fun display(romanization: Romanization, reading: String): String =
        if (romanization == Romanization.PINYIN) pinyinToMarked(reading) else reading

    /** Splits a canonical numbered reading into toneless syllables and tones (0 = unspecified). */
    fun parseCanonical(canonical: String): Pair<List<String>, List<Int>> {
        val syllables = ArrayList<String>()
        val tones = ArrayList<Int>()
        for (raw in canonical.trim().split(' ')) {
            if (raw.isEmpty()) continue
            val last = raw.last()
            if (last in '0'..'9' && raw.length > 1) {
                syllables.add(raw.dropLast(1).lowercase().replace("u:", "ü").replace('v', 'ü'))
                tones.add(last - '0')
            } else {
                syllables.add(raw.lowercase().replace("u:", "ü"))
                tones.add(0)
            }
        }
        return syllables to tones
    }

    /** Number of tones in a mode: five for Mandarin (5 = neutral), six for Cantonese (GLB-011). */
    fun toneCount(mode: DictMode): Int = if (mode == DictMode.MANDARIN) 5 else 6

    /**
     * Zero-based tone-colour slot for [tone], or -1 when the tone is unspecified or out of range
     * (the text then keeps its normal colour). Mandarin: slots 0–4 (T1–T4, neutral); Cantonese: 0–5.
     */
    fun toneColourIndex(tone: Int, mode: DictMode): Int = if (tone in 1..toneCount(mode)) tone - 1 else -1

    /**
     * Per-code-point tone-colour slots for a headword (GLB-066), or null when colouring cannot be
     * applied correctly (syllable count differs from the code-point count).
     */
    fun toneColourSlots(headword: String, reading: Reading, mode: DictMode): List<Int>? {
        var count = 0
        var i = 0
        while (i < headword.length) {
            i += if (headword[i].isHighSurrogate() && i + 1 < headword.length) 2 else 1
            count++
        }
        if (count == 0 || count != reading.tones.size) return null
        return reading.tones.map { toneColourIndex(it, mode) }
    }
}
