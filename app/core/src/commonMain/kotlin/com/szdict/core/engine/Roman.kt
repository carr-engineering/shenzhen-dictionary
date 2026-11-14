package com.szdict.core.engine

import com.szdict.core.api.QueryKind
import com.szdict.core.store.Inventory

/** Token kinds of the spelling stage (SRCH-050). Ordinal order is the enumeration preference. */
internal enum class TokKind { FULL, ABBR, PARTIAL, LITERAL }

/**
 * One token: [text] is the typed letters; [syllable] the inventory syllable of a FULL token (after
 * aliases) or the letter of a LITERAL; [tone] is 0 when none was typed.
 */
internal class Tok(val kind: TokKind, val start: Int, val end: Int, val text: String, val syllable: String, val tone: Int) {
    /** First-letter index key letter (DATA-151). */
    val initial: Char get() = (if (kind == TokKind.FULL) syllable else text)[0]
    override fun toString(): String = "$kind:$text" + if (tone != 0) "$tone" else ""
}

internal class RomanPath(val tokens: List<Tok>, val cost: Double) {
    val hasAbbr: Boolean = tokens.any { it.kind == TokKind.ABBR }
    val hasLiteral: Boolean = tokens.any { it.kind == TokKind.LITERAL }
    val endsPartial: Boolean = tokens.lastOrNull()?.kind == TokKind.PARTIAL
    val hasTone: Boolean = tokens.any { it.tone != 0 }
    override fun toString(): String = tokens.joinToString(" ", "[", "]")
}

/**
 * The kept paths of a romanised query (SRCH-051), best first. [breaks]: the letter offsets strictly
 * inside the query where the user marked a syllable boundary (space, apostrophe, tone digit), ascending.
 */
internal class RomanParse(val letters: String, val paths: List<RomanPath>, val breaks: IntArray = IntArray(0)) {
    val hasTone: Boolean = paths.any { it.hasTone }
}

/**
 * Romanisation parser (SRCH-040, SRCH-041, SRCH-050, SRCH-051): builds the token DAG over the
 * letter stream and keeps the 8 best paths. Driven entirely by the dataset's inventory, alias and
 * abbreviation tables (GLB-010).
 */
internal object RomanParser {
    const val MAX_PATHS = 8
    const val MAX_ABBR_TOKENS = 12

    private class Node(val tok: Tok, val prev: Node?, val cost: Double, val count: Int) {
        fun tokens(): List<Tok> {
            val out = ArrayList<Tok>(count)
            var n: Node? = this
            while (n != null) {
                out.add(n.tok)
                n = n.prev
            }
            out.reverse()
            return out
        }
    }

    // Path classes: pure FULL/PARTIAL, has ABBR, literal run only, literal run then FULL.
    private const val P = 0
    private const val A = 1
    private const val L = 2
    private const val LF = 3

    private val order = Comparator<Node> { a, b ->
        val c = a.cost.compareTo(b.cost)
        if (c != 0) return@Comparator c
        if (a.count != b.count) return@Comparator a.count.compareTo(b.count)
        val ta = a.tokens()
        val tb = b.tokens()
        for (i in 0 until minOf(ta.size, tb.size)) {
            if (ta[i].end != tb[i].end) return@Comparator tb[i].end.compareTo(ta[i].end) // longer token first
            if (ta[i].kind != tb[i].kind) return@Comparator ta[i].kind.ordinal.compareTo(tb[i].kind.ordinal)
        }
        0
    }

    /**
     * Parses a matching-key fragment without Hanzi. Returns null when the input cannot be
     * romanisation at all (a stray digit, an unexpected character) and a parse with no paths when
     * no token path covers it. [allowPartial] is false for non-final Latin runs of a mixed query.
     */
    fun parse(key: String, inv: Inventory, toneCount: Int, abbrevPenalty: Double, partialPenalty: Double, allowPartial: Boolean = true): RomanParse? {
        val sb = StringBuilder(key.length)
        val dia = IntArray(key.length + 1)
        val digit = IntArray(key.length + 1)
        val hard = BooleanArray(key.length + 2)
        var prevLetter = false
        for (c in key) {
            when {
                c in 'a'..'z' || c == 'ü' -> {
                    sb.append(if (c == 'v' && inv.vIsUmlaut) 'ü' else c)
                    prevLetter = true
                }
                toneMarkOf(c) != 0 -> {
                    dia[sb.length] = toneMarkOf(c)
                    sb.append(toneBaseOf(c))
                    prevLetter = true
                }
                c in '0'..'9' -> {
                    val t = c - '0'
                    if (!prevLetter || t !in 1..toneCount) return null
                    digit[sb.length] = t
                    hard[sb.length] = true
                    prevLetter = false
                }
                c == ' ' || c == '\'' || c == '·' -> {
                    hard[sb.length] = true
                    prevLetter = false
                }
                else -> return null
            }
        }
        val letters = sb.toString()
        val n = letters.length
        if (n == 0) return null

        val best = Array(n + 1) { Array(4) { ArrayList<Node>(MAX_PATHS + 1) } }
        fun push(end: Int, cls: Int, node: Node) {
            if (cls != P && node.count > MAX_ABBR_TOKENS) return
            val list = best[end][cls]
            list.add(node)
            if (list.size > 1) list.sortWith(order)
            if (list.size > MAX_PATHS) list.removeAt(list.size - 1)
        }

        val maxLen = maxOf(inv.maxLen, inv.maxAbbrevLen, 1)
        for (i in 0 until n) {
            val atStart = i == 0
            if (!atStart && best[i].all { it.isEmpty() }) continue
            var len = 1
            while (len <= maxLen && i + len <= n) {
                val j = i + len
                if (len > 1 && hard[j - 1] && j - 1 > i) break // token would cross a hard boundary
                val sub = letters.substring(i, j)
                var diaCount = 0
                var diaTone = 0
                for (p in i until j) if (dia[p] != 0) {
                    diaCount++
                    diaTone = dia[p]
                }
                val dg = digit[j]
                val toks = ArrayList<Tok>(4)
                val syl = inv.syllableOf(sub)
                if (syl != null && diaCount <= 1) toks.add(Tok(TokKind.FULL, i, j, sub, syl, if (dg != 0) dg else diaTone))
                if (diaCount == 0 && dg == 0) {
                    if (sub in inv.abbrevs && !(atStart && j == n)) toks.add(Tok(TokKind.ABBR, i, j, sub, "", 0))
                    if (allowPartial && j == n && syl == null && sub in inv.properPrefixes) toks.add(Tok(TokKind.PARTIAL, i, j, sub, "", 0))
                    if (len == 1 && sub[0] in 'a'..'z') toks.add(Tok(TokKind.LITERAL, i, j, sub, sub, 0))
                }
                for (tok in toks) {
                    val penalty = when (tok.kind) {
                        TokKind.FULL -> 0.0
                        TokKind.ABBR, TokKind.LITERAL -> abbrevPenalty
                        TokKind.PARTIAL -> partialPenalty
                    }
                    if (atStart) {
                        val node = Node(tok, null, penalty, 1)
                        when (tok.kind) {
                            TokKind.FULL, TokKind.PARTIAL -> push(j, P, node)
                            TokKind.ABBR -> push(j, A, node)
                            TokKind.LITERAL -> push(j, L, node)
                        }
                    } else {
                        for (cls in 0..3) for (prev in best[i][cls].toList()) {
                            val target = when (cls) {
                                P -> when (tok.kind) { TokKind.FULL, TokKind.PARTIAL -> P; TokKind.ABBR -> A; TokKind.LITERAL -> -1 }
                                A -> if (tok.kind == TokKind.LITERAL) -1 else A
                                L -> when (tok.kind) { TokKind.LITERAL -> L; TokKind.FULL -> LF; else -> -1 }
                                else -> if (tok.kind == TokKind.FULL || tok.kind == TokKind.PARTIAL) LF else -1
                            }
                            if (target >= 0) push(j, target, Node(tok, prev, prev.cost + penalty, prev.count + 1))
                        }
                    }
                }
                if (hard[j]) break
                len++
            }
        }
        val finals = (best[n][P] + best[n][A] + best[n][LF]).sortedWith(order).take(MAX_PATHS)
        val breaks = (1 until n).filter { hard[it] }.toIntArray()
        return RomanParse(letters, finals.map { RomanPath(it.tokens(), it.cost) }, breaks)
    }
}

/** One item of a mixed Hanzi + pronunciation pattern (SRCH-036). */
internal class PatternItem(val hanzi: Int, val tok: Tok?)

internal class Classified(
    val kind: QueryKind,
    val key: String,
    val display: String,
    /** ROMANISATION: the kept paths. */
    val roman: RomanParse?,
    /** MIXED with a valid pattern: up to 8 pattern alternatives with their token cost. */
    val patterns: List<Pair<List<PatternItem>, Double>>,
) {
    val hasTone: Boolean get() = roman?.hasTone == true || patterns.any { p -> p.first.any { it.tok?.tone ?: 0 != 0 } }
    /** The key with spaces removed. */
    val compact: String get() = key.replace(" ", "")
}

/** Query classification (SRCH-020…SRCH-023). */
internal object Classifier {
    fun classify(raw: String, inv: Inventory, toneCount: Int, abbrevPenalty: Double, partialPenalty: Double): Classified {
        val key = Normaliser.matchingKey(raw)
        val display = Normaliser.displayText(raw)
        if (key.isEmpty()) return Classified(QueryKind.EMPTY, key, display, null, emptyList())
        if (!key.containsHanzi()) {
            val parse = RomanParser.parse(key, inv, toneCount, abbrevPenalty, partialPenalty)
            return if (parse != null && parse.paths.isNotEmpty()) Classified(QueryKind.ROMANISATION, key, display, parse, emptyList())
            else Classified(QueryKind.LATIN, key, display, null, emptyList())
        }
        if (key.all { it == ' ' || it == '·' || it.isSurrogate() || isHanzi(it.code) }) {
            return Classified(QueryKind.HANZI, key, display, null, emptyList())
        }
        // MIXED: split into Hanzi items and Latin runs.
        val cps = key.cps()
        var alternatives: List<Pair<List<PatternItem>, Double>> = listOf(emptyList<PatternItem>() to 0.0)
        var valid = true
        var i = 0
        while (i < cps.size && valid) {
            val cp = cps[i]
            if (isHanzi(cp)) {
                alternatives = alternatives.map { (items, cost) -> (items + PatternItem(cp, null)) to cost }
                i++
                continue
            }
            var j = i
            while (j < cps.size && !isHanzi(cps[j])) j++
            val run = cps.cpString(i, j).trim().trim('·')
            if (run.isNotEmpty()) {
                val parse = RomanParser.parse(run, inv, toneCount, abbrevPenalty, partialPenalty, allowPartial = j == cps.size)
                // A single-token run may be an abbreviation here: the pattern as a whole has several items.
                val paths = parse?.paths.orEmpty().ifEmpty { singleAbbrev(run, inv, abbrevPenalty) }
                if (paths.isEmpty()) valid = false
                else {
                    val next = ArrayList<Pair<List<PatternItem>, Double>>()
                    for ((items, cost) in alternatives) for (p in paths) {
                        if (next.size >= 8) break
                        if (p.hasLiteral) continue
                        next.add((items + p.tokens.map { PatternItem(0, it) }) to cost + p.cost)
                    }
                    alternatives = next
                    if (next.isEmpty()) valid = false
                }
            }
            i = j
        }
        val patterns = if (valid) alternatives.filter { it.first.size >= 2 }.sortedBy { it.second }.take(8) else emptyList()
        return Classified(QueryKind.MIXED, key, display, null, patterns)
    }

    private fun singleAbbrev(run: String, inv: Inventory, abbrevPenalty: Double): List<RomanPath> =
        if (run in inv.abbrevs) listOf(RomanPath(listOf(Tok(TokKind.ABBR, 0, run.length, run, "", 0)), abbrevPenalty)) else emptyList()
}
