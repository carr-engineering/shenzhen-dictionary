package com.szdict.core.engine

/** GLB-001: Unified_Ideograph=Yes (URO, Ext A–J, the twelve unified compatibility ideographs) or U+3007. */
internal fun isHanzi(cp: Int): Boolean = when {
    cp < 0x3007 -> false
    cp == 0x3007 -> true
    cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF -> true
    cp < 0xFA0E -> false
    cp <= 0xFA29 -> cp == 0xFA0E || cp == 0xFA0F || cp == 0xFA11 || cp == 0xFA13 || cp == 0xFA14 || cp == 0xFA1F ||
        cp == 0xFA21 || cp == 0xFA23 || cp == 0xFA24 || cp == 0xFA27 || cp == 0xFA28 || cp == 0xFA29
    cp < 0x20000 -> false
    else -> cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2B81D || cp in 0x2B820..0x2CEAD || cp in 0x2CEB0..0x2EBE0 ||
        cp in 0x2EBF0..0x2EE5D || cp in 0x30000..0x3134A || cp in 0x31350..0x323AF || cp in 0x323B0..0x33479
}

internal fun String.codePointAtIndex(i: Int): Int {
    val c = this[i]
    if (c.isHighSurrogate() && i + 1 < length) {
        val d = this[i + 1]
        if (d.isLowSurrogate()) return ((c.code - 0xD800) shl 10) + (d.code - 0xDC00) + 0x10000
    }
    return c.code
}

internal fun cpLen(cp: Int): Int = if (cp >= 0x10000) 2 else 1

internal fun StringBuilder.appendCp(cp: Int): StringBuilder {
    if (cp >= 0x10000) {
        val v = cp - 0x10000
        append((0xD800 + (v shr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
    } else append(cp.toChar())
    return this
}

internal fun cpToString(cp: Int): String = StringBuilder(2).appendCp(cp).toString()

/** Code points of the string. */
internal fun String.cps(): IntArray {
    val out = IntArray(length)
    var n = 0
    var i = 0
    while (i < length) {
        val cp = codePointAtIndex(i)
        out[n++] = cp
        i += cpLen(cp)
    }
    return if (n == length) out else out.copyOf(n)
}

internal fun String.cpCount(): Int {
    var n = 0
    var i = 0
    while (i < length) {
        i += if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
        n++
    }
    return n
}

internal fun String.hanziCount(): Int {
    var n = 0
    var i = 0
    while (i < length) {
        val cp = codePointAtIndex(i)
        if (isHanzi(cp)) n++
        i += cpLen(cp)
    }
    return n
}

internal fun String.containsHanzi(): Boolean {
    var i = 0
    while (i < length) {
        val cp = codePointAtIndex(i)
        if (isHanzi(cp)) return true
        i += cpLen(cp)
    }
    return false
}

internal fun IntArray.cpString(from: Int = 0, to: Int = size): String {
    val sb = StringBuilder(to - from)
    for (i in from until to) sb.appendCp(this[i])
    return sb.toString()
}

/**
 * Character classes of sentence mode (TXT-018, SRCH-028, SRCH-029): whitespace, foreign-word characters
 * (Latin letters and digits, full-width forms included, plus the joiners `'` `’` `-` `.` `_` when
 * directly between two of them), text (Hanzi, radical and stroke forms, letters of other scripts)
 * and punctuation (everything else: punctuation, symbols, emoji).
 */
internal object TextClass {
    const val SPACE = 0
    const val WORD = 1
    const val TEXT = 2
    const val PUNCT = 3

    fun isSpace(cp: Int): Boolean = cp == 0x3000 || (cp < 0x10000 && cp.toChar().isWhitespace())

    /** A Latin letter or digit after NFKC folding (SRCH-010): ASCII, full-width forms, Latin letters with diacritics. */
    fun isWordChar(cp: Int): Boolean = when {
        cp < 0x80 -> cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '0'.code..'9'.code
        cp in 0xC0..0x24F -> cp != 0xD7 && cp != 0xF7
        cp in 0x1E00..0x1EFF -> true
        cp in 0xFF10..0xFF19 || cp in 0xFF21..0xFF3A || cp in 0xFF41..0xFF5A -> true
        else -> false
    }

    fun isJoiner(cp: Int): Boolean = cp == '\''.code || cp == 0x2019 || cp == '-'.code || cp == '.'.code || cp == '_'.code

    private fun base(cp: Int): Int = when {
        isSpace(cp) -> SPACE
        isWordChar(cp) -> WORD
        isHanzi(cp) || cp in 0x2E80..0x2EFF || cp in 0x31C0..0x31EF -> TEXT
        cp < 0x10000 && cp.toChar().isLetterOrDigit() -> TEXT
        else -> PUNCT
    }

    /** The class of every code point; a joiner directly between two word characters is [WORD]. */
    fun classify(cps: IntArray): IntArray {
        val out = IntArray(cps.size) { base(cps[it]) }
        for (i in 1 until cps.size - 1) {
            if (isJoiner(cps[i]) && isWordChar(cps[i - 1]) && isWordChar(cps[i + 1])) out[i] = WORD
        }
        return out
    }

    /**
     * Code-point boundaries strictly inside a foreign word: `inner[i]` (0..n) is true when code
     * points `i - 1` and `i` both belong to the same foreign word. No dictionary span may start or
     * end at such a boundary (TXT-018).
     */
    fun innerBoundaries(cps: IntArray, classes: IntArray = classify(cps)): BooleanArray {
        val inner = BooleanArray(cps.size + 1)
        for (i in 1 until cps.size) if (classes[i - 1] == WORD && classes[i] == WORD) inner[i] = true
        return inner
    }

    /** True when [cps] holds a foreign-word character at all (a fast path for the checks above). */
    fun hasWordChar(cps: IntArray): Boolean = cps.any { isWordChar(it) }

    /** Keeps the (start, length, id) triples of [rows] whose span starts and ends on admissible boundaries of [headword]. */
    fun admissibleRows(rows: IntArray, headword: String): IntArray {
        val cps = headword.cps()
        if (!hasWordChar(cps)) return rows
        val inner = innerBoundaries(cps)
        val out = ArrayList<Int>(rows.size)
        for (i in 0 until rows.size - 2 step 3) {
            val s = rows[i]
            val e = s + rows[i + 1]
            if (s in inner.indices && e in inner.indices && (inner[s] || inner[e])) continue
            out.add(rows[i]); out.add(rows[i + 1]); out.add(rows[i + 2])
        }
        return if (out.size == rows.size) rows else out.toIntArray()
    }
}

/** Tone carried by a tone-marked vowel (1–4), with its base letter; 0 when [c] is not one (SRCH-010 step 6). */
internal fun toneMarkOf(c: Char): Int {
    val i = TONE_VOWELS.indexOf(c)
    return if (i < 0) 0 else i % 4 + 1
}

internal fun toneBaseOf(c: Char): Char = TONE_BASES[TONE_VOWELS.indexOf(c) / 4]

private const val TONE_VOWELS = "āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜ"
private const val TONE_BASES = "aeiouü"

internal object Normaliser {
    /** SRCH-010: the matching key. */
    fun matchingKey(raw: String): String {
        var s = normalizeNfkc(raw).lowercase()
        if ("u:" in s) s = s.replace("u:", "ü")
        val sb = StringBuilder(s.length)
        var pendingSpace = false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAtIndex(i)
            i += cpLen(cp)
            val out: Int = when {
                cp == 0x2019 || cp == 0x2018 || cp == 0x02BC || cp == 0xFF07 -> '\''.code
                cp == 0x30FB || cp == 0x2027 || cp == 0xFF65 || cp == 0x2022 -> 0xB7
                cp in 'a'.code..'z'.code || cp in '0'.code..'9'.code || cp == '\''.code || cp == 0xB7 || cp == 0xFC -> cp
                cp < 0x250 && toneMarkOf(cp.toChar()) != 0 -> cp
                isHanzi(cp) -> cp
                // Radical and stroke forms are headword keys of their unified ideograph (DATA-071).
                cp in 0x2E80..0x2EFF || cp in 0x31C0..0x31EF -> cp
                else -> -1
            }
            if (out < 0) {
                pendingSpace = true
            } else {
                if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                pendingSpace = false
                sb.appendCp(out)
            }
        }
        return sb.toString()
    }

    /** SRCH-012: gentle display normalisation — NFC, whitespace collapse, trim. */
    fun displayText(raw: String): String {
        val s = normalizeNfc(raw)
        val sb = StringBuilder(s.length)
        var pendingSpace = false
        for (c in s) {
            if (c.isWhitespace() || c == '　') {
                pendingSpace = true
            } else {
                if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                pendingSpace = false
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /** SRCH-013: English tokens with the naive plural stem. */
    fun englishTokens(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isEmpty()) return
            var t = sb.toString()
            if (t.length > 3 && t.endsWith('s') && !t.endsWith("ss")) t = t.dropLast(1)
            out.add(t)
            sb.clear()
        }
        for (ch in text) {
            val c = ch.lowercaseChar()
            if (c in 'a'..'z' || c in '0'..'9') sb.append(c) else flush()
        }
        flush()
        return out
    }
}
