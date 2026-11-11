package com.szdict.core.engine

import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.PassageSegment
import com.szdict.core.api.Reading
import com.szdict.core.api.SegmentationMode
import com.szdict.core.store.DictionaryStore
import com.szdict.core.store.EntryRow
import com.szdict.core.store.HeadHit
import com.szdict.core.store.NO_ULP
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ln

/** An analysis plus the headword hits it found, so Search can build its groups without re-querying. */
internal class Analysis(val passage: AnalysedPassage, val hits: Map<String, List<HeadHit>>) {
    fun hitsFor(text: String): List<HeadHit> = hits[Segmenter.lookupKey(text)].orEmpty()
}

/**
 * Text segmentation (TXT-010…TXT-016): runs, then per run the shared lattice decoder with the
 * bigram model when the dataset carries one (TXT-012, TXT-026; k = 1), a unigram DAG
 * maximum-likelihood segmentation when it only has frequency data, greedy longest-match otherwise.
 * All candidate substrings of a batch are looked up in a constant number of batched queries
 * (TXT-014); the lattice adds one read of the language-model words and one of the bigram blocks.
 */
internal class Segmenter(private val store: DictionaryStore) {
    /** The entry that stands for a matched span: a segmentable word, an exact-form key, then the primary reading. */
    private val headComparator = Comparator<HeadHit> { a, b ->
        val sa = a.proj.segmentable
        val sb = b.proj.segmentable
        if (sa != sb) return@Comparator if (sa) -1 else 1
        if ((a.kind == 0) != (b.kind == 0)) return@Comparator if (a.kind == 0) -1 else 1
        Ranking.primary.compare(a.proj, b.proj)
    }

    /** Strategy override for tests: 0 = by dataset, 1 = unigram DAG, 2 = greedy. */
    internal var forceStrategy = 0

    suspend fun analyse(text: String, mode: SegmentationMode, generation: Long): Analysis {
        val cps = text.cps()
        val offsets = IntArray(cps.size + 1)
        for (i in cps.indices) offsets[i + 1] = offsets[i] + cpLen(cps[i])
        val maxLen = store.maxWordLen.coerceAtLeast(1)
        val runs = runs(cps, mode)
        val allHits = HashMap<String, List<HeadHit>>()
        val logp = HashMap<String, Double>()
        val lattice = forceStrategy == 0 && store.hasFrequency && store.hasLm && store.hasBigram
        val dag = !lattice && forceStrategy != 2 && store.hasFrequency

        // Batched lookups: every candidate substring of every run, in batches bounded by key count.
        val pending = LinkedHashSet<String>()
        suspend fun flush() {
            if (pending.isEmpty()) return
            currentCoroutineContext().ensureActive()
            val hits = store.headwordExact(pending)
            pending.clear()
            if (hits.isEmpty()) return
            allHits.putAll(hits)
            if (dag && store.hasEntryUnigram) {
                // The unigram travels with the entry (schema version 2): the best word of each key.
                for ((key, hs) in hits) {
                    var best = NO_ULP
                    for (h in hs) if (!h.proj.isUnihan && h.proj.ulp > best) best = h.proj.ulp
                    if (best != NO_ULP) logp[key] = best / 100.0
                }
            } else if (dag) {
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

        // Lattice: the language-model word and unigram of every dictionary entry among the hits (one read).
        val lmOf: Map<Int, Pair<Int, Double>> = if (!lattice) emptyMap() else {
            val ids = HashSet<Int>()
            for (hs in allHits.values) for (h in hs) if (!h.proj.isUnihan) ids.add(h.proj.id)
            store.lmEntries(ids)
        }
        // Per run: chosen word length at each position (negative = unmatched single) and, from the
        // lattice, the language-model word of the chosen edge (to pick the entry that carries it).
        class Plan(val rs: Int, val wordLen: IntArray, val word: IntArray?)
        val plans = ArrayList<Plan>(runs.size)

        if (lattice) {
            class RunEdges(val rs: Int, val n: Int, val edges: ArrayList<LatticeEdge>, val start: IntArray, val length: IntArray, val matched: BooleanArray)
            val built = ArrayList<RunEdges>(runs.size)
            val lefts = HashSet<Int>()
            for ((rs, re) in runs) {
                currentCoroutineContext().ensureActive()
                val n = re - rs
                val edges = ArrayList<LatticeEdge>(n * 3)
                val meta = ArrayList<IntArray>(n * 3) // (start, len, matched)
                fun add(p: Int, len: Int, word: Int, unigram: Double, matched: Boolean) {
                    edges.add(LatticeEdge(p, p + len, word, unigram, -wordPenalty, edges.size))
                    meta.add(intArrayOf(p, len, if (matched) 1 else 0))
                    if (word >= 0 && p + len < n) lefts.add(word)
                }
                for (p in 0 until n) {
                    val lim = minOf(maxLen, n - p)
                    for (len in 1..lim) {
                        val hs = wordHits(rs + p, len)
                        if (hs == null) {
                            if (len == 1) add(p, 1, -1, unknown, false)
                            continue
                        }
                        // One edge per distinct language-model word of the span (乾: 干 / 乾).
                        var any = false
                        var w1 = -1
                        var w2 = -1
                        for (h in hs) {
                            if (len > 1 && !h.proj.segmentable) continue
                            val lm = lmOf[h.proj.id] ?: continue
                            if (lm.first < 0 || lm.first == w1 || lm.first == w2) continue
                            if (w1 < 0) w1 = lm.first else if (w2 < 0) w2 = lm.first else continue
                            add(p, len, lm.first, lm.second, true)
                            any = true
                        }
                        // Unicode-only entries (and words the model does not know) score as an unknown character (TXT-012).
                        if (!any) add(p, len, -1, if (len == 1) unknown else unknown * len, true)
                    }
                }
                val start = IntArray(meta.size) { meta[it][0] }
                val length = IntArray(meta.size) { meta[it][1] }
                val matched = BooleanArray(meta.size) { meta[it][2] != 0 }
                built.add(RunEdges(rs, n, edges, start, length, matched))
            }
            val bigrams = store.bigramBlocks(lefts)
            val ctx = currentCoroutineContext()
            for (r in built) {
                ctx.ensureActive()
                val wordLen = IntArray(r.n)
                val word = IntArray(r.n) { -1 }
                val best = LatticeDecoder.decode(r.n + 1, r.edges, bigrams, 1, LATTICE_BEAM) { ctx.ensureActive() }.firstOrNull()
                if (best == null) {
                    for (p in 0 until r.n) wordLen[p] = if (wordHits(r.rs + p, 1) != null) 1 else -1
                } else {
                    for (e in best.edges) {
                        val p = r.start[e.payload]
                        wordLen[p] = if (r.matched[e.payload]) r.length[e.payload] else -1
                        word[p] = e.word
                    }
                }
                plans.add(Plan(r.rs, wordLen, word))
            }
        } else for ((rs, re) in runs) {
            currentCoroutineContext().ensureActive()
            val n = re - rs
            val wordLen = IntArray(n)
            if (dag) {
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
            plans.add(Plan(rs, wordLen, null))
        }

        for ((index, plan) in plans.withIndex()) {
            val rs = plan.rs
            val re = runs[index].second
            val n = re - rs
            val wordLen = plan.wordLen
            var p = 0
            while (p < n) {
                val len = wordLen[p]
                if (len > 0) {
                    val hs = wordHits(rs + p, len)!!
                    val want = plan.word?.get(p) ?: -1
                    val pick = (if (want >= 0) hs.filter { lmOf[it.proj.id]?.first == want }.minWithOrNull(headComparator) else null)
                        ?: hs.minWithOrNull(headComparator)!!
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
        private const val LATTICE_BEAM = 40.0

        /**
         * Per-word penalty of the passage lattice (TXT-026 caller penalty): a near tie between a
         * dictionary word and its parts goes to the word (中华人民共和国, 中将) while a frequent
         * collocation of common words stays split (生命 / 科学).
         */
        internal var wordPenalty = 1.0

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
