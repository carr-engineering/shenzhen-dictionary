package com.szdict.core.engine

import com.szdict.core.store.BigramBlock
import kotlin.math.exp

/**
 * The pure parts of the combinations (SRCH-062…SRCH-065): trigram rescoring of the decoder's k-best
 * list, the show policy and the grouping of the kept sequences into rows. No I/O.
 */
internal object Combinations {
    /** Distinct sequences considered by the policy (by headword list, whole-query words left out). */
    const val POOL = 12
    /** Natural-log distance from the best sequence beyond which a sequence is not shown. */
    const val MARGIN = 4.0
    /** Lowest log-probability per syllable of a shown sequence. */
    const val FLOOR_PER_SYLLABLE = -6.5
    /**
     * Mixed queries (SRCH-069): a Latin run that is one English word is combined only when a word
     * spelling the run is at least this many times as likely (its unigram) as the English word's share
     * of the English corpus. Measured on contract/combinations-eval.json `mixed` / `mixed_neg`: 1 keeps
     * 6.7 % of the negatives, 3 keeps 3.9 % at the same recall, 10 costs recall.
     */
    const val ENGLISH_RATIO = 3.0
    /**
     * Lowest share of a sequence in the probability mass of the candidates (exp(score - best) / sum).
     * 0 = off. A general junk reducer measured at 0.05 (fewer single-word lookups with combinations,
     * slightly lower recall); not applied.
     */
    const val MIN_SHARE = 0.0
    /** Grouped rows shown and alternatives per slot (SRCH-065). */
    const val MAX_ROWS = 3
    const val MAX_ALTS = 4
    /** Multi-word paths of the k-best list that are rescored with trigrams. */
    const val RESCORE = 300

    /** A trigram context key: `(w1 shl 17) or w2` (DATA-156). */
    fun contextKey(w1: Int, w2: Int): Long = (w1.toLong() shl 17) or w2.toLong()

    /** The decoder's conditional log-probability of [e] after the word whose block is [block]. */
    fun conditional(block: BigramBlock?, e: LatticeEdge): Double {
        if (block == null || e.word < 0) return e.unigram
        val v = block.lookup(e.word)
        return if (v.isNaN()) block.backoff + e.unigram else v
    }

    /** Contexts `(w[i-2], w[i-1])` of the paths, for one batched trigram read. */
    fun contexts(paths: List<LatticePath>): Set<Long> {
        val out = HashSet<Long>()
        for (p in paths) for (i in 2 until p.edges.size) {
            val a = p.edges[i - 2].word
            val b = p.edges[i - 1].word
            if (a >= 0 && b >= 0 && p.edges[i].word >= 0) out.add(contextKey(a, b))
        }
        return out
    }

    /**
     * Trigram rescoring (SRCH-062): for every word from the third on, the bigram conditional the
     * decoder used is replaced by the trigram one — the kept value, else the context's backoff plus
     * the bigram conditional (backoff 0 for a context without a row). Re-sorted in decoder order.
     */
    fun rescore(paths: List<LatticePath>, bigrams: Map<Int, BigramBlock>, trigrams: Map<Long, BigramBlock>): List<LatticePath> {
        if (trigrams.isEmpty()) return paths
        val out = ArrayList<LatticePath>(paths.size)
        for (p in paths) {
            var delta = 0.0
            for (i in 2 until p.edges.size) {
                val e = p.edges[i]
                val a = p.edges[i - 2].word
                val b = p.edges[i - 1].word
                if (a < 0 || b < 0 || e.word < 0) continue
                val row = trigrams[contextKey(a, b)] ?: continue
                val tri = row.lookup(e.word)
                delta += if (tri.isNaN()) row.backoff else tri - conditional(bigrams[b], e)
            }
            out.add(if (delta == 0.0) p else LatticePath(p.edges, p.score + delta))
        }
        out.sortWith(LatticeDecoder.ORDER)
        return out
    }

    /**
     * The show policy (SRCH-063): [candidates] are the first [POOL] distinct sequences in rank order
     * (whole-query words already dropped); a sequence is kept when it is within [MARGIN] of the best
     * one, at or above [FLOOR_PER_SYLLABLE] per syllable and, when [minShare] > 0, holds at least that
     * share of the candidates' probability mass. No whole-query word takes part.
     */
    fun <T> select(
        candidates: List<T>, score: (T) -> Double, syllables: (T) -> Int, minShare: Double = MIN_SHARE, floor: Double = FLOOR_PER_SYLLABLE,
    ): List<T> {
        if (candidates.isEmpty()) return emptyList()
        val best = score(candidates[0])
        val mass = if (minShare > 0.0) candidates.sumOf { exp(score(it) - best) } else 1.0
        return candidates.filter { c ->
            val s = score(c)
            s >= best - MARGIN && s >= floor * syllables(c) && (minShare <= 0.0 || exp(s - best) / mass >= minShare)
        }
    }

    /** One word of a kept sequence: its lattice span, entry and headword (the alternatives' identity). */
    class Word(val from: Int, val to: Int, val entryId: Int, val head: String)

    /** A grouped row: slots of alternative entry ids (first = the best sequence's word) and its best score. */
    class Row(val slots: List<IntArray>, val score: Double)

    /**
     * Grouping (SRCH-065): sequences with the same word boundaries form one row, ranked by its best
     * sequence; each slot collects the distinct headwords of the row's sequences at that position, up
     * to [maxAlts]. A row offers the cross-product of its slots.
     */
    fun group(kept: List<Pair<List<Word>, Double>>, maxRows: Int = MAX_ROWS, maxAlts: Int = MAX_ALTS): List<Row> {
        class Acc(n: Int, val score: Double) {
            val ids = List(n) { ArrayList<Int>() }
            val heads = List(n) { HashSet<String>() }
        }
        val rows = LinkedHashMap<List<Long>, Acc>()
        for ((words, score) in kept) {
            if (words.isEmpty()) continue
            val key = words.map { (it.from.toLong() shl 32) or it.to.toLong() }
            val acc = rows.getOrPut(key) { Acc(words.size, score) }
            for ((i, w) in words.withIndex()) {
                if (w.head in acc.heads[i] || acc.ids[i].size >= maxAlts) continue
                acc.heads[i].add(w.head)
                acc.ids[i].add(w.entryId)
            }
        }
        return rows.values.take(maxRows).map { a -> Row(a.ids.map { it.toIntArray() }, a.score) }
    }
}
