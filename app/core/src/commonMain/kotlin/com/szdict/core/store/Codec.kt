package com.szdict.core.store

import com.szdict.core.engine.inflateRaw
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Unsigned LEB128 reader over a blob (contract/SCHEMA.md "Primitive encodings"). */
internal class VarintReader(private val bytes: ByteArray, var pos: Int = 0) {
    val hasMore: Boolean get() = pos < bytes.size

    fun next(): Int {
        var result = 0
        var shift = 0
        while (pos < bytes.size) {
            val b = bytes[pos++].toInt()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    fun byte(): Int = bytes[pos++].toInt() and 0xFF
}

internal object Codec {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** id list (delta): ascending ids, first absolute then deltas. */
    fun deltaIds(blob: ByteArray?): IntArray {
        if (blob == null || blob.isEmpty()) return IntArray(0)
        val out = IntArray(blob.size)
        val r = VarintReader(blob)
        var n = 0
        var prev = 0
        while (r.hasMore) {
            prev = if (n == 0) r.next() else prev + r.next()
            out[n++] = prev
        }
        return out.copyOf(n)
    }

    /** id list (plain): varints in stored order. */
    fun plainIds(blob: ByteArray?, limit: Int = Int.MAX_VALUE): IntArray {
        if (blob == null || blob.isEmpty()) return IntArray(0)
        val out = IntArray(minOf(blob.size, limit))
        val r = VarintReader(blob)
        var n = 0
        while (r.hasMore && n < limit) out[n++] = r.next()
        return out.copyOf(n)
    }

    fun body(blob: ByteArray, dictionary: ByteArray? = null): Body = try {
        if (blob.isEmpty()) Body() else json.decodeFromString(Body.serializer(), inflateRaw(blob, dictionary).decodeToString())
    } catch (e: Exception) {
        Body()
    }

    private const val HEX = "0123456789abcdef"

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(HEX[(b.toInt() shr 4) and 15]).append(HEX[b.toInt() and 15])
        return sb.toString()
    }

    /** Bytes of a lowercase / uppercase hex string, or null when it is not one. */
    fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = s[2 * i].digitToIntOrNull(16) ?: -1
            val lo = s[2 * i + 1].digitToIntOrNull(16) ?: -1
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun jsonArray(values: Collection<String>): String {
        val sb = StringBuilder(values.size * 8 + 2).append('[')
        var first = true
        for (v in values) {
            if (!first) sb.append(',')
            first = false
            sb.append('"')
            for (c in v) when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
            sb.append('"')
        }
        return sb.append(']').toString()
    }

    fun jsonIntArray(values: Collection<Int>): String = values.joinToString(",", "[", "]")
    fun jsonIntArray(values: IntArray): String = values.joinToString(",", "[", "]")
    fun jsonLongArray(values: Collection<Long>): String = values.joinToString(",", "[", "]")

    /**
     * A successor list (DATA-153 / DATA-156) at the reader's position: byte backoff code, varint count,
     * count × varint successor id (first absolute, then deltas), count × byte value code.
     */
    fun successorBlock(r: VarintReader, values: DoubleArray, backoffs: DoubleArray): BigramBlock {
        val backoff = backoffs[r.byte()]
        val n = r.next()
        val succ = IntArray(n)
        var prev = 0
        for (i in 0 until n) {
            prev = if (i == 0) r.next() else prev + r.next()
            succ[i] = prev
        }
        val vals = DoubleArray(n) { values[r.byte()] }
        return BigramBlock(backoff, succ, vals)
    }

    /**
     * A trigram page (DATA-156): key `(w1 shl 17) or w2` of its first context, then per context in
     * ascending w2: varint w2 delta (0 for the first), and a successor list. Calls [each] with every
     * context's key and its row.
     */
    inline fun trigramPage(key: Long, blob: ByteArray, values: DoubleArray, backoffs: DoubleArray, each: (Long, BigramBlock) -> Unit) {
        val w1 = key ushr 17
        var w2 = key and 0x1FFFFL
        val r = VarintReader(blob)
        while (r.hasMore) {
            w2 += r.next()
            each((w1 shl 17) or w2, successorBlock(r, values, backoffs))
        }
    }
}

// ---- entry.body (zjson) ----

@Serializable
internal class Body(
    val senses: List<SenseJ> = emptyList(),
    val blocks: List<BlockJ> = emptyList(),
    val notes: List<NoteJ> = emptyList(),
    val redirect: RedirectJ? = null,
    val alt: List<AltJ> = emptyList(),
)

@Serializable
internal class SenseJ(
    val t: String = "",
    val src: String = "",
    val tags: List<String> = emptyList(),
    val tok: List<String> = emptyList(),
    val seg: List<SegJ> = emptyList(),
    val evr: JsonElement? = null,
)

@Serializable
internal class SegJ(
    val k: String = "ref",
    val t: String? = null,
    val d: String? = null,
    val rom: String? = null,
    val tr: String? = null,
    val si: String? = null,
    val c: String? = null,
    val id: Int? = null,
)

@Serializable
internal class BlockJ(
    val kind: String = "mandarin",
    val label: String = "",
    val first: Int = 0,
    val count: Int = 0,
    val collapsed: Boolean = false,
    val readings: List<BlockReadingJ> = emptyList(),
)

@Serializable
internal class BlockReadingJ(val d: String = "", val c: String = "", val first: Int = 0, val count: Int = 0)

@Serializable
internal class NoteJ(
    val kind: String = "",
    val label: String = "",
    val refs: List<SegJ> = emptyList(),
    val reading: String? = null,
    val extra: String? = null,
)

@Serializable
internal class RedirectJ(
    val kind: String = "variant",
    val text: String = "",
    val seg: List<SegJ> = emptyList(),
    val target: Int? = null,
    val resolved: Boolean = false,
)

@Serializable
internal class AltJ(val kind: String = "", val d: String = "", val c: String = "")

@Serializable
internal class HskLevelJ(val l: Int = 0, val pos: List<String> = emptyList(), val x: Int = 0)

// ---- rows ----

internal const val FLAG_PROPER = 1
internal const val FLAG_SEGMENTABLE = 2
internal const val FLAG_UNIHAN = 4
internal const val FLAG_CANTO = 8
internal const val UNRANKED = 1_000_000
internal const val NO_ULP = Int.MIN_VALUE

/** Ranking projection (DATA-078, ARCH-031): no definitions, no body. */
internal class Proj(
    val id: Int,
    val flags: Int,
    val freqRank: Int,
    val charCount: Int,
    val mergeSimp: Int,
    val mergeTrad: Int,
    /** Unigram log-probability × 100 of the entry's word (`entry.ulp`, schema version 2), or [NO_ULP]. */
    val ulp: Int = NO_ULP,
    /** The entry's lowest HSK level (the first level of `entry.hsk`; 7 = band 7–9), 0 = none. */
    val hskLevel: Int = 0,
) {
    val isProper get() = flags and FLAG_PROPER != 0
    val isUnihan get() = flags and FLAG_UNIHAN != 0
    val hasCanto get() = flags and FLAG_CANTO != 0
    val segmentable get() = flags and FLAG_SEGMENTABLE != 0

    /** Position among the entries sharing the headword, 0 = primary reading (`flags` bits 4–7, SCHEMA note 18). */
    val readingRank get() = (flags shr 4) and 15
}

/** A hydrated entry; the body blob is decoded lazily, only when rendered (ARCH-025). */
internal class EntryRow(
    val id: Int,
    val trad: String,
    val simp: String,
    val readingDisplay: String,
    val readingCanonical: String,
    val readingSource: Int,
    val syllables: List<String>,
    val tones: IntArray,
    val flags: Int,
    val freqRank: Int,
    val charCount: Int,
    val naturalKey: String,
    val mergeSimp: Int,
    val mergeTrad: Int,
    val hskJson: String,
    val hskViaJson: String,
    val audioKey: String?,
    private val bodyBlob: ByteArray,
    private val bodyDict: ByteArray? = null,
    val ulp: Int = NO_ULP,
) {
    val body: Body by lazy { Codec.body(bodyBlob, bodyDict) }
    val proj: Proj get() = Proj(id, flags, freqRank, charCount, mergeSimp, mergeTrad, ulp, hskLevelOf(hskJson))
    val isProper get() = flags and FLAG_PROPER != 0
    val isUnihan get() = flags and FLAG_UNIHAN != 0
    val hasCanto get() = flags and FLAG_CANTO != 0
    val hsk: List<HskLevelJ> by lazy { parseHsk(hskJson) }
    val hskVia: List<HskLevelJ> by lazy { parseHsk(hskViaJson) }

    private fun parseHsk(s: String): List<HskLevelJ> = if (s.length <= 2) emptyList() else try {
        Codec.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(HskLevelJ.serializer()), s)
    } catch (e: Exception) {
        emptyList()
    }
}

internal class HeadHit(val key: String, val kind: Int, val proj: Proj)

internal class ReadHit(val key: String, val alt: Int, val altNote: Int, val tones: ByteArray?, val proj: Proj)

internal class AbbrPosting(val entryId: Int, val lmWord: Int, val unigram: Int, val alt: Int, val syl: IntArray)

internal class BigramBlock(val backoff: Double, val successors: IntArray, val values: DoubleArray) {
    fun lookup(word: Int): Double {
        var lo = 0
        var hi = successors.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = successors[mid]
            if (v < word) lo = mid + 1 else if (v > word) hi = mid - 1 else return values[mid]
        }
        return Double.NaN
    }
}

internal class MergeGroup(val id: Int, val script: Int, val members: IntArray, val variants: List<String>)

internal class HskRow(val rowId: Int, val level: Int, val entryId: Int)

internal class HskCharRow(val ch: String, val level: Int, val writing: Boolean, val entryId: Int)

internal class SentenceRow(val id: Int, val zh: String, val zhAlt: String, val en: String)

internal class DecompRow(
    val ch: String, val ids: String, val parts: List<String>, val radical: String?, val strokes: Int?,
    val name: String?, val svg: String?, val matches: List<List<Int>?>?,
)

/** Syllable inventory, aliases and abbreviation table (DATA-070, DATA-150): all language knowledge is data. */
internal class Inventory(
    val syllables: List<String>,
    val aliases: Map<String, String>,
    abbrevRows: List<Pair<String, String>>,
) {
    val index: Map<String, Int> = HashMap<String, Int>(syllables.size * 2).also { m -> syllables.forEachIndexed { i, s -> m[s] = i } }
    val maxLen: Int = maxOf(syllables.maxOfOrNull { it.length } ?: 0, aliases.keys.maxOfOrNull { it.length } ?: 0)

    /** Abbreviation spelling → the syllables it may stand for. */
    val abbrevs: Map<String, Set<String>> = HashMap<String, MutableSet<String>>().also { m ->
        for ((a, s) in abbrevRows) m.getOrPut(a) { HashSet() }.add(s)
    }
    val maxAbbrevLen: Int = abbrevs.keys.maxOfOrNull { it.length } ?: 0

    /** Every proper prefix of an inventory syllable. */
    val properPrefixes: Set<String> = HashSet<String>().also { set ->
        for (s in syllables) for (i in 1 until s.length) set.add(s.substring(0, i))
    }

    /** `v` stands for `ü` when the inventory uses `ü` and never `v` (SRCH-040 aliases). */
    val vIsUmlaut: Boolean = syllables.any { 'ü' in it } && syllables.none { 'v' in it }

    fun syllableOf(letters: String): String? = if (letters in index) letters else aliases[letters]?.takeIf { it in index }
    fun isAbbrevOf(abbrev: String, syllable: String): Boolean = abbrevs[abbrev]?.contains(syllable) == true
    fun syllableAt(idx: Int): String = syllables.getOrElse(idx) { "" }
}

/**
 * The lowest HSK level of an `entry.hsk` value: its first `"l"` (main levels come first, ascending,
 * and no extra level is below them — SCHEMA.md), 0 when empty. Mirrors [HSK_LEVEL_SQL].
 */
internal fun hskLevelOf(hskJson: String): Int {
    val at = hskJson.indexOf("\"l\":")
    if (at < 0) return 0
    var i = at + 4
    var v = 0
    while (i < hskJson.length && hskJson[i] in '0'..'9') v = v * 10 + (hskJson[i++] - '0')
    return v
}

/** SQL for [hskLevelOf] over `e.hsk` (a projection column; no schema change). */
internal const val HSK_LEVEL_SQL = "CASE WHEN instr(e.hsk, '\"l\":') = 0 THEN 0 ELSE CAST(substr(e.hsk, instr(e.hsk, '\"l\":') + 4, 2) AS INTEGER) END"
