package com.szdict.core.engine

import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.PassageSegment
import com.szdict.core.api.Reading
import com.szdict.core.api.SegmentationMode
import com.szdict.core.store.DictionaryStore
import com.szdict.core.store.EntryRow
import com.szdict.core.store.HeadHit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ln

/** An analysis plus the headword hits it found, so Search can build its groups without re-querying. */
internal class Analysis(val passage: AnalysedPassage, val hits: Map<String, List<HeadHit>>) {
    fun hitsFor(text: String): List<HeadHit> = hits[Segmenter.lookupKey(text)].orEmpty()
}

/**
 * Text segmentation (TXT-010…TXT-016): runs, a unigram DAG maximum-likelihood segmentation when the
 * dataset has frequency data, greedy longest-match otherwise. All candidate substrings of a batch
 * are looked up in a constant number of batched queries (TXT-014).
 */
internal class Segmenter(private val store: DictionaryStore) {
    private val headComparator = Comparator<HeadHit> { a, b ->
        val sa = a.proj.segmentable
        val sb = b.proj.segmentable
        if (sa != sb) return@Comparator if (sa) -1 else 1
        if ((a.kind == 0) != (b.kind == 0)) return@Comparator if (a.kind == 0) -1 else 1
        Ranking.projComparator(false).compare(a.proj, b.proj)
    }

    suspend fun analyse(text: String, mode: SegmentationMode, generation: Long): Analysis {
        val cps = text.cps()
        val offsets = IntArray(cps.size + 1)
        for (i in cps.indices) offsets[i + 1] = offsets[i] + cpLen(cps[i])
        val maxLen = store.maxWordLen.coerceAtLeast(1)
        val runs = runs(cps, mode)
        val allHits = HashMap<String, List<HeadHit>>()
        val logp = HashMap<String, Double>()

        // Batched lookups: every candidate substring of every run, in batches bounded by key count.
        val pending = LinkedHashSet<String>()
        suspend fun flush() {
            if (pending.isEmpty()) return
            currentCoroutineContext().ensureActive()
            val hits = store.headwordExact(pending)
            pending.clear()
            if (hits.isEmpty()) return
            allHits.putAll(hits)
            if (store.hasFrequency) {
                val found = store.wordLogProbs(hits.keys)
                logp.putAll(found)
                // A form absent from the frequency table may be present under its other script form.
                val missing = hits.filter { it.key !in found && it.key.cpCount() > 1 }
                if (missing.isNotEmpty()) {
                    val forms = store.forms(missing.values.map { it.first().proj.id }.toIntArray())
                    val alt = HashMap<String, String>()
                    for ((key, hs) in missing) {
                        val f = forms[hs.first().proj.id] ?: continue
                        val other = if (f.simp == key) f.trad else f.simp
                        if (other != key) alt[key] = other
                    }
                    if (alt.isNotEmpty()) {
                        val more = store.wordLogProbs(alt.values.toSet())
                        for ((key, other) in alt) more[other]?.let { logp[key] = it }
                    }
                }
            }
        }
        val keys = arrayOfNulls<Array<String>>(cps.size) // keys[i][len - 1]
        for ((rs, re) in runs) {
            for (i in rs until re) {
                val lim = minOf(maxLen, re - i)
                val row = Array(lim) { lookupKey(cps.cpString(i, i + it + 1)) }
                keys[i] = row
                pending.addAll(row)
                if (pending.size >= BATCH_KEYS) flush()
            }
        }
        flush()

        val unknown = UNKNOWN_SCORE
        val defaultLp = -ln(store.totalFrequency)
        val segments = ArrayList<PassageSegment>()
        val chosen = ArrayList<IntArray>() // (segment index, entry id)
        val covered = BooleanArray(cps.size)

        fun wordHits(i: Int, len: Int): List<HeadHit>? {
            val hs = allHits[keys[i]!![len - 1]] ?: return null
            return if (len == 1 || hs.any { it.proj.segmentable }) hs else null
        }

        for ((rs, re) in runs) {
            currentCoroutineContext().ensureActive()
            val n = re - rs
            val wordLen = IntArray(n) // chosen length at each position; negative = unmatched single
            if (store.hasFrequency) {
                val score = DoubleArray(n + 1)
                for (p in n - 1 downTo 0) {
                    var best = Double.NEGATIVE_INFINITY
                    var bestLen = -1
                    val lim = minOf(maxLen, n - p)
                    for (len in 1..lim) {
                        val hs = wordHits(rs + p, len)
                        val s: Double
                        var matched = true
                        if (hs == null) {
                            if (len > 1) continue
                            s = unknown
                            matched = false
                        } else if (hs.all { it.proj.isUnihan }) {
                            s = unknown
                        } else {
                            s = logp[keys[rs + p]!![len - 1]] ?: defaultLp
                        }
                        val total = s + score[p + len]
                        if (total >= best) { // ties prefer the longer word
                            best = total
                            bestLen = if (matched) len else -1
                        }
                    }
                    score[p] = best
                    wordLen[p] = bestLen
                }
            } else {
                var p = 0
                while (p < n) {
                    var len = minOf(maxLen, n - p)
                    while (len >= 2 && wordHits(rs + p, len) == null) len--
                    if (len >= 2) {
                        wordLen[p] = len
                        p += len
                    } else {
                        wordLen[p] = if (wordHits(rs + p, 1) != null) 1 else -1
                        p++
                    }
                }
            }
            var p = 0
            while (p < n) {
                val len = wordLen[p]
                if (len > 0) {
                    val hs = wordHits(rs + p, len)!!
                    val pick = hs.minWithOrNull(headComparator)!!
                    chosen.add(intArrayOf(segments.size, pick.proj.id))
                    segments.add(PassageSegment(offsets[rs + p], offsets[rs + p + len] - offsets[rs + p], true, pick.proj.id, Reading.EMPTY, false))
                    p += len
                } else {
                    var q = p + 1
                    while (q < n && wordLen[q] < 0) q++
                    segments.add(PassageSegment(offsets[rs + p], offsets[rs + q] - offsets[rs + p], false, -1, Reading.EMPTY, false))
                    p = q
                }
            }
            // Coverage (TXT-020): every character inside any dictionary headword of the run.
            for (i in rs until re) {
                val lim = minOf(maxLen, re - i)
                for (len in lim downTo 1) {
                    if (allHits[keys[i]!![len - 1]] != null) {
                        for (c in i until i + len) covered[c] = true
                        break // shorter words at this start add no further coverage
                    }
                }
            }
        }

        if (chosen.isNotEmpty()) {
            val entries = store.entries(chosen.map { it[1] }.toSet())
            for ((idx, id) in chosen.map { it[0] to it[1] }) {
                val e = entries[id] ?: continue
                val seg = segments[idx]
                segments[idx] = seg.copy(reading = readingOf(e), toneColourable = e.syllables.isNotEmpty() && e.syllables.size == text.substring(seg.start, seg.end).cpCount())
            }
        }
        val coveredOffsets = ArrayList<Int>()
        for (i in cps.indices) if (covered[i]) coveredOffsets.add(offsets[i])
        return Analysis(AnalysedPassage(text, store.mode, mode, generation, segments, coveredOffsets), allHits)
    }

    companion object {
        const val UNKNOWN_SCORE = -20.0
        private const val BATCH_KEYS = 6000

        fun readingOf(e: EntryRow): Reading = Reading(e.readingDisplay, e.readingCanonical, e.syllables, e.tones.asList())

        /**
         * Headword-index key for a text substring: Hanzi as-is, anything else matching-normalised
         * (DATA-071). A substring holding a separator (punctuation, symbols) is never a headword, so
         * it keeps its raw form, which matches nothing.
         */
        fun lookupKey(sub: String): String {
            for (c in sub) if (c.code < 0x3000 || c.code in 0xFF00..0xFFEF || c.code in 0x2000..0x2FFF) {
                val key = Normaliser.matchingKey(sub)
                return if (key.isNotEmpty() && ' ' !in key && key.cpCount() == sub.cpCount()) key else sub
            }
            return sub
        }

        fun inRun(cp: Int, mode: SegmentationMode): Boolean =
            if (mode == SegmentationMode.HANZI) isHanzi(cp) else !(cp == 0x3000 || (cp < 0x10000 && cp.toChar().isWhitespace()))

        /** Runs as half-open code-point index ranges (TXT-011). */
        fun runs(cps: IntArray, mode: SegmentationMode): List<Pair<Int, Int>> {
            val out = ArrayList<Pair<Int, Int>>()
            var i = 0
            while (i < cps.size) {
                if (!inRun(cps[i], mode)) {
                    i++
                    continue
                }
                var j = i
                while (j < cps.size && inRun(cps[j], mode)) j++
                out.add(i to j)
                i = j
            }
            return out
        }
    }
}
