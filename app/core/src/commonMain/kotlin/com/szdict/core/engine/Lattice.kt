package com.szdict.core.engine

import com.szdict.core.store.BigramBlock

/**
 * An edge of the position lattice (TXT-026): a candidate word spanning `[from, to)` with its
 * language-model word id (negative = no LM word: scored by [unigram] alone, never a bigram
 * context), its unigram log-probability and a caller penalty (<= 0) added to the path score.
 */
internal class LatticeEdge(
    val from: Int, val to: Int, val word: Int, val unigram: Double, val penalty: Double, val payload: Int,
    /** Caller's unit count of the edge (syllables of a romanised word); not used by the decoder. */
    val units: Int = 1,
)

internal class LatticePath(val edges: List<LatticeEdge>, val score: Double)

/**
 * The shared lattice decoder (TXT-026): k-best Viterbi over a position lattice with recombination
 * on (node, last word) and a beam cutoff. Pure Kotlin, no I/O — the bigram blocks of the distinct
 * left words are read by the caller in one batch and passed in. Deterministic on every platform:
 * ties are broken by longer first word, then lower word ids.
 */
internal object LatticeDecoder {
    private class Hyp(val score: Double, val edge: LatticeEdge?, val prev: Hyp?)

    fun decode(
        nodeCount: Int,
        edges: List<LatticeEdge>,
        bigrams: Map<Int, BigramBlock>,
        k: Int,
        beam: Double,
        checkCancelled: () -> Unit = {},
    ): List<LatticePath> {
        if (nodeCount < 2) return emptyList()
        val out = arrayOfNulls<ArrayList<LatticeEdge>>(nodeCount)
        for (e in edges) {
            if (e.from < 0 || e.to >= nodeCount || e.to <= e.from) continue
            (out[e.from] ?: ArrayList<LatticeEdge>().also { out[e.from] = it }).add(e)
        }
        val states = arrayOfNulls<HashMap<Int, ArrayList<Hyp>>>(nodeCount)
        states[0] = hashMapOf(-1 to arrayListOf(Hyp(0.0, null, null)))
        var steps = 0
        for (node in 0 until nodeCount - 1) {
            val here = states[node] ?: continue
            val outgoing = out[node] ?: continue
            var bestHere = Double.NEGATIVE_INFINITY
            for (list in here.values) if (list[0].score > bestHere) bestHere = list[0].score
            for ((lastWord, list) in here) {
                val block = if (lastWord >= 0) bigrams[lastWord] else null
                for (hyp in list) {
                    if (hyp.score < bestHere - beam) continue
                    for (e in outgoing) {
                        if (++steps and 0xFFF == 0) checkCancelled()
                        var lp = e.unigram
                        if (block != null && e.word >= 0) {
                            val bi = block.lookup(e.word)
                            lp = if (bi.isNaN()) block.backoff + e.unigram else bi
                        }
                        val score = hyp.score + lp + e.penalty
                        val target = states[e.to] ?: HashMap<Int, ArrayList<Hyp>>().also { states[e.to] = it }
                        // Words without an LM id recombine per payload so distinct entries stay distinct.
                        val stateKey = if (e.word >= 0) e.word else -2 - e.payload
                        val bucket = target.getOrPut(stateKey) { ArrayList(k + 1) }
                        if (bucket.size >= k && score <= bucket[bucket.size - 1].score) continue
                        var pos = bucket.size
                        while (pos > 0 && bucket[pos - 1].score < score) pos--
                        bucket.add(pos, Hyp(score, e, hyp))
                        if (bucket.size > k) bucket.removeAt(bucket.size - 1)
                    }
                }
            }
        }
        val finals = states[nodeCount - 1] ?: return emptyList()
        val paths = ArrayList<LatticePath>()
        for (list in finals.values) for (h in list) {
            val es = ArrayList<LatticeEdge>()
            var cur: Hyp? = h
            while (cur?.edge != null) {
                es.add(cur.edge!!)
                cur = cur.prev
            }
            es.reverse()
            paths.add(LatticePath(es, h.score))
        }
        paths.sortWith(ORDER)
        return paths
    }

    /** Path order: score descending, then longer first word, lower word ids and payloads, fewer edges. */
    val ORDER: Comparator<LatticePath> = Comparator { a, b ->
        val c = b.score.compareTo(a.score)
        if (c != 0) return@Comparator c
        val la = a.edges.first().let { it.to - it.from }
        val lb = b.edges.first().let { it.to - it.from }
        if (la != lb) return@Comparator lb.compareTo(la)
        for (i in 0 until minOf(a.edges.size, b.edges.size)) {
            val w = a.edges[i].word.compareTo(b.edges[i].word)
            if (w != 0) return@Comparator w
            val p = a.edges[i].payload.compareTo(b.edges[i].payload)
            if (p != 0) return@Comparator p
        }
        a.edges.size.compareTo(b.edges.size)
    }
}
