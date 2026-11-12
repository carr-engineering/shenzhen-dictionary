package com.szdict.core.engine

import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.DictMode
import com.szdict.core.api.QueryKind
import com.szdict.core.api.Script
import com.szdict.core.api.SectionKind
import com.szdict.core.api.SegmentationMode
import com.szdict.core.store.AbbrPosting
import com.szdict.core.store.DictionaryStore
import com.szdict.core.store.Inventory
import com.szdict.core.store.Proj
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class SectionOut(val kind: SectionKind, val key: String, val labelArg: String?, val rows: List<Candidate>)

internal class GroupOut(val text: String, val start: Int, val length: Int, val inert: Boolean, val ids: List<Int>)

internal class ChineseOut(
    val groups: List<GroupOut>,
    val sections: List<SectionOut>,
    val isSentence: Boolean,
    val passage: AnalysedPassage?,
    val capped: Boolean,
)

internal class EnglishOut(val sections: List<SectionOut>, val capped: Boolean)

internal class ComboOut(val entryIds: IntArray, val score: Double)

/**
 * The search pipeline (ARCH-030): plan → batched store reads → match classes → rank → cap. Works on
 * ranking projections only; nothing here hydrates an entry except the bounded phrase check of the
 * English side. Language knowledge comes from the dataset's tables (GLB-010).
 */
internal class SearchEngine(private val store: DictionaryStore, private val segmenter: Segmenter) {
    private val cantonese = store.mode == DictMode.CANTONESE

    suspend fun classify(raw: String): Classified =
        Classifier.classify(raw, store.inventory(), store.toneCount, store.abbrevPenalty, store.partialPenalty)

    // ------------------------------------------------------------------ Chinese side

    suspend fun chinese(c: Classified, script: Script, generation: Long, cap: Int): ChineseOut = when (c.kind) {
        QueryKind.EMPTY -> ChineseOut(emptyList(), emptyList(), false, null, false)
        QueryKind.ROMANISATION, QueryKind.LATIN -> pronunciation(c, script, cap)
        QueryKind.HANZI, QueryKind.MIXED -> hanzi(c, script, generation, cap)
    }

    private suspend fun pronunciation(c: Classified, script: Script, cap: Int): ChineseOut {
        val best = HashMap<Int, Candidate>()
        fun offer(cand: Candidate) {
            val old = best[cand.proj.id]
            if (old == null || cand.betterThan(old)) best[cand.proj.id] = cand
        }
        c.roman?.let { parse -> romanCandidates(parse).forEach(::offer) }
        // SRCH-044: Latin-containing headwords by the compacted query.
        val compact = c.compact
        if (compact.isNotEmpty() && compact.cpCount() <= store.maxHeadwordLen) {
            store.headwordExact(listOf(compact))[compact]?.forEach { offer(Candidate(it.proj, MatchClass.EXACT)) }
            store.headwordPrefix(compact).forEach { offer(Candidate(it.proj, MatchClass.PREFIX)) }
        }
        var ranked = Ranking.dedupeMerged(best.values.sortedWith(Ranking.comparator(cantonese)), script) { it.proj }
        val capped = ranked.size > cap
        if (capped) ranked = ranked.subList(0, cap)
        val sections = ArrayList<SectionOut>(2)
        if (c.kind == QueryKind.ROMANISATION) {
            val split = ranked.indexOfFirst { it.cls > MatchClass.EXACT_TONELESS || it.proj.isUnihan }.let { if (it < 0) ranked.size else it }
            if (split > 0) sections.add(SectionOut(SectionKind.MATCHES, "m", null, ranked.subList(0, split)))
            if (split < ranked.size) sections.add(SectionOut(SectionKind.MORE_MATCHES, "p", null, ranked.subList(split, ranked.size)))
        } else if (ranked.isNotEmpty()) {
            sections.add(SectionOut(SectionKind.MATCHES, "m", null, ranked))
        }
        return ChineseOut(emptyList(), sections, false, null, capped)
    }

    private class AbbrHit(val entryId: Int, val cls: Int, val alt: Boolean, val cost: Double, val altKey: String?, val path: RomanPath)

    /** Reading-index and first-letter-index retrieval for all kept paths, in two batched reads (SRCH-053). */
    private suspend fun romanCandidates(parse: RomanParse): Collection<Candidate> = coroutineScope {
        val inv = store.inventory()
        val specs = ArrayList<DictionaryStore.ReadSpec>()
        val specPath = ArrayList<RomanPath>()
        val specKind = ArrayList<Int>() // 0 exact, 1 longer, 2 partial
        val abbrPaths = ArrayList<Pair<RomanPath, String>>()
        for (path in parse.paths) {
            if (path.hasAbbr) {
                if (store.hasLm) abbrPaths.add(path to path.tokens.joinToString("") { it.initial.toString() })
                continue
            }
            val full = path.tokens.filter { it.kind != TokKind.PARTIAL }.joinToString(" ") { it.syllable }
            if (path.endsPartial) {
                val lo = if (full.isEmpty()) path.tokens.last().text else full + " " + path.tokens.last().text
                specs.add(DictionaryStore.ReadSpec(lo, DictionaryStore.upperBound(lo))); specPath.add(path); specKind.add(2)
            } else {
                specs.add(DictionaryStore.ReadSpec(full, null)); specPath.add(path); specKind.add(0)
                specs.add(DictionaryStore.ReadSpec("$full ", "$full!")); specPath.add(path); specKind.add(1)
            }
        }
        val readingsJob = async { store.readings(specs) }
        val abbrJob = async {
            if (abbrPaths.isEmpty()) emptyMap() else {
                val keys = abbrPaths.map { it.second }.toSet()
                store.abbrevPostings(keys, keys)
            }
        }
        val best = HashMap<Int, Candidate>()
        fun offer(cand: Candidate) {
            val old = best[cand.proj.id]
            if (old == null || cand.betterThan(old)) best[cand.proj.id] = cand
        }
        val readings = readingsJob.await()
        for (i in specs.indices) {
            val path = specPath[i]
            val toks = path.tokens
            val allToned = toks.all { it.kind != TokKind.FULL || it.tone != 0 }
            val cls = when (specKind[i]) {
                0 -> if (allToned) MatchClass.EXACT else MatchClass.EXACT_TONELESS
                else -> MatchClass.PREFIX
            }
            var n = 0
            for (hit in readings[i]) {
                if (++n and 2047 == 0) currentCoroutineContext().ensureActive()
                if (path.hasTone) {
                    val tones = hit.tones ?: continue
                    var ok = true
                    for (t in toks.indices) {
                        val want = toks[t].tone
                        if (want != 0 && (t >= tones.size || tones[t].toInt() != want)) {
                            ok = false
                            break
                        }
                    }
                    if (!ok) continue
                }
                offer(Candidate(hit.proj, cls, hit.alt != 0, path.cost, if (hit.alt != 0) hit.altNote else -1))
            }
        }
        val postings = abbrJob.await()
        if (postings.isNotEmpty()) {
            val hits = ArrayList<AbbrHit>()
            for ((path, key) in abbrPaths) {
                for ((k, list) in postings) {
                    if (!k.startsWith(key)) continue
                    currentCoroutineContext().ensureActive()
                    for (p in list) {
                        if (!matches(p, path.tokens, inv, allowLonger = true)) continue
                        if (p.alt != 0 && path.hasTone) continue // tones of alternative postings are not in the index
                        val cls = if (p.syl.size == path.tokens.size && !path.endsPartial) MatchClass.EXACT_TONELESS else MatchClass.PREFIX
                        val altKey = if (p.alt != 0) p.syl.joinToString(" ") { inv.syllableAt(it) } else null
                        hits.add(AbbrHit(p.entryId, cls, p.alt != 0, path.cost, altKey, path))
                    }
                }
            }
            if (hits.isNotEmpty()) {
                val ids = hits.map { it.entryId }.toSet().toIntArray()
                if (hits.any { it.path.hasTone }) {
                    val forms = store.forms(ids)
                    for (h in hits) {
                        val f = forms[h.entryId] ?: continue
                        if (h.path.hasTone && !tonesMatch(h.path.tokens, f.tones)) continue
                        offer(Candidate(f.proj, h.cls, h.alt, h.cost, -1, h.altKey))
                    }
                } else {
                    val projs = store.projections(ids)
                    for (h in hits) projs[h.entryId]?.let { offer(Candidate(it, h.cls, h.alt, h.cost, -1, h.altKey)) }
                }
            }
        }
        best.values
    }

    private fun tonesMatch(toks: List<Tok>, tones: ByteArray): Boolean {
        for (t in toks.indices) {
            val want = toks[t].tone
            if (want != 0 && (t >= tones.size || tones[t].toInt() != want)) return false
        }
        return true
    }

    /** SRCH-042 per-position match of a first-letter posting against tokens. */
    private fun matches(p: AbbrPosting, toks: List<Tok>, inv: Inventory, allowLonger: Boolean): Boolean {
        if (p.syl.size < toks.size || (!allowLonger && p.syl.size != toks.size)) return false
        for (t in toks.indices) {
            val syl = inv.syllableAt(p.syl[t])
            val tok = toks[t]
            val ok = when (tok.kind) {
                TokKind.FULL -> syl == tok.syllable
                TokKind.ABBR -> inv.isAbbrevOf(tok.text, syl)
                TokKind.PARTIAL -> syl.length > tok.text.length && syl.startsWith(tok.text)
                TokKind.LITERAL -> false
            }
            if (!ok) return false
        }
        return true
    }

    private suspend fun hanzi(c: Classified, script: Script, generation: Long, cap: Int): ChineseOut = coroutineScope {
        val projOrder = Ranking.projComparator(cantonese)
        val compact = c.compact
        val patternValid = c.kind == QueryKind.MIXED && c.patterns.isNotEmpty()
        // Hanzi-only fallback text for a mixed query whose Latin part is noise (SRCH-022, SRCH-162).
        val hanziOnly = if (c.kind == QueryKind.MIXED && !patternValid) c.key.filter { it.isSurrogate() || isHanzi(it.code) || it == '·' } else null
        val exactKeys = listOfNotNull(c.key, compact, hanziOnly).distinct()
        val exactHits = store.headwordExact(exactKeys)
        val exact = (exactHits[c.key] ?: exactHits[compact] ?: hanziOnly?.let { exactHits[it] }).orEmpty()
            .map { it.proj }.distinctBy { it.id }.sortedWith(projOrder)

        val groups = ArrayList<GroupOut>()
        var passage: AnalysedPassage? = null
        var isSentence = false
        if (exact.isNotEmpty()) {
            groups.add(GroupOut(c.display, 0, c.display.length, false, Ranking.dedupeMerged(exact, script) { it }.map { it.id }))
        } else {
            val mode = if (c.kind == QueryKind.HANZI || !patternValid) SegmentationMode.FULL else SegmentationMode.HANZI
            val analysis = segmenter.analyse(c.display, mode, generation)
            passage = analysis.passage
            var inertStart = -1
            var inertEnd = -1
            fun flushInert() {
                if (inertStart >= 0) groups.add(GroupOut(c.display.substring(inertStart, inertEnd), inertStart, inertEnd - inertStart, true, emptyList()))
                inertStart = -1
            }
            for (seg in analysis.passage.segments) {
                if (!seg.matched) {
                    if (inertStart < 0) inertStart = seg.start
                    inertEnd = seg.end
                    continue
                }
                flushInert()
                val text = c.display.substring(seg.start, seg.end)
                val hits = analysis.hitsFor(text).map { it.proj }.distinctBy { it.id }.sortedWith(projOrder)
                groups.add(GroupOut(text, seg.start, seg.length, false, Ranking.dedupeMerged(hits, script) { it }.map { it.id }))
            }
            flushInert()
            isSentence = groups.size > 1 || (groups.size == 1 && groups[0].inert)
        }

        val sections = ArrayList<SectionOut>()
        var budget = cap
        fun section(kind: SectionKind, key: String, arg: String?, rows: List<Candidate>): Boolean {
            if (rows.isEmpty() || budget <= 0) return false
            val take = if (rows.size > budget) rows.subList(0, budget) else rows
            budget -= take.size
            sections.add(SectionOut(kind, key, arg, take))
            return take.size < rows.size
        }
        var capped = false
        if (patternValid) capped = section(SectionKind.PATTERN, "x", null, pattern(c, script)) || capped
        val needle = hanziOnly ?: compact
        if (!patternValid && needle.isNotEmpty() && needle.cpCount() <= store.maxHeadwordLen) {
            val startsJob = async {
                val rows = store.headwordPrefix(needle).map { it.proj }.distinctBy { it.id }.sortedWith(projOrder)
                Ranking.dedupeMerged(rows, script) { it }.map { Candidate(it, MatchClass.PREFIX) }
            }
            val containsJob = async { contains(needle, script).map { Candidate(it, MatchClass.CONTAINS) } }
            capped = section(SectionKind.STARTS_WITH, "s", c.display, startsJob.await()) || capped
            capped = section(SectionKind.CONTAINS, "c", c.display, containsJob.await()) || capped
        }
        ChineseOut(groups, sections, isSentence, if (isSentence) passage else null, capped)
    }

    /** Headwords containing [needle] at position 1 or later, ranked (SRCH-151, DATA-074). */
    suspend fun contains(needle: String, script: Script): List<Proj> {
        val cps = needle.cps()
        if (cps.isEmpty()) return emptyList()
        val ids = store.substrPostings(if (cps.size <= 4) needle else cps.cpString(0, 4))
        if (ids.isEmpty()) return emptyList()
        val rows: List<Proj> = if (cps.size <= 4) store.projections(ids).values.toList() else {
            store.forms(ids).values.filter { f -> f.simp.indexOf(needle) > 0 || f.trad.indexOf(needle) > 0 ||
                f.simp.lowercase().indexOf(needle) > 0 || f.trad.lowercase().indexOf(needle) > 0 }.map { it.proj }
        }
        return Ranking.dedupeMerged(rows.sortedWith(Ranking.projComparator(cantonese)), script) { it }
    }

    /** Headwords strictly longer than [word] that begin with it, ranked. */
    suspend fun startsWith(words: Collection<String>, script: Script): List<Proj> {
        val rows = HashMap<Int, Proj>()
        for (w in words) if (w.isNotEmpty()) store.headwordPrefix(w).forEach { rows[it.proj.id] = it.proj }
        return Ranking.dedupeMerged(rows.values.sortedWith(Ranking.projComparator(cantonese)), script) { it }
    }

    /** Mixed Hanzi + pronunciation pattern search (SRCH-036…SRCH-039). */
    private suspend fun pattern(c: Classified, script: Script): List<Candidate> {
        val inv = store.inventory()
        val best = HashMap<Int, Candidate>()
        val firstChars = c.patterns.mapNotNull { p -> p.first.firstOrNull { it.tok == null }?.hanzi }.toSet()
        val postings = store.charPostings(firstChars.map { cpToString(it) })
        for (cp in firstChars) {
            val ids = postings[cpToString(cp)] ?: continue
            val forms = store.forms(ids)
            var n = 0
            for (f in forms.values) {
                if (++n and 1023 == 0) currentCoroutineContext().ensureActive()
                val syl = if (f.syllables.isEmpty()) emptyList() else f.syllables.split(' ')
                if (syl.size != f.proj.charCount) continue
                val simp = f.simp.cps()
                val trad = f.trad.cps()
                for ((items, cost) in c.patterns) {
                    if (items.firstOrNull { it.tok == null }?.hanzi != cp) continue
                    if (simp.size < items.size || trad.size != simp.size) continue
                    var ok = true
                    for (pos in items.indices) {
                        val item = items[pos]
                        val tok = item.tok
                        ok = if (tok == null) simp[pos] == item.hanzi || trad[pos] == item.hanzi else when (tok.kind) {
                            TokKind.FULL -> syl[pos] == tok.syllable && (tok.tone == 0 || (pos < f.tones.size && f.tones[pos].toInt() == tok.tone))
                            TokKind.ABBR -> inv.isAbbrevOf(tok.text, syl[pos])
                            TokKind.PARTIAL -> pos == items.size - 1 && syl[pos].length > tok.text.length && syl[pos].startsWith(tok.text)
                            TokKind.LITERAL -> syl[pos] == tok.text
                        }
                        if (!ok) break
                    }
                    if (!ok) continue
                    val cand = Candidate(f.proj, if (simp.size == items.size) MatchClass.EXACT else MatchClass.PREFIX, false, cost)
                    val old = best[f.proj.id]
                    if (old == null || cand.betterThan(old)) best[f.proj.id] = cand
                }
            }
        }
        return Ranking.dedupeMerged(best.values.sortedWith(Ranking.comparator(cantonese)), script) { it.proj }
    }

    // ------------------------------------------------------------------ English side

    private class EnCand(val id: Int, var cls: Int, val canto: Boolean, val senses: IntArray?, val sense: Int)

    suspend fun english(c: Classified, script: Script, cap: Int): EnglishOut = coroutineScope {
        val tokens = Normaliser.englishTokens(c.key)
        if (tokens.isEmpty() || c.kind == QueryKind.EMPTY) return@coroutineScope EnglishOut(emptyList(), false)
        val last = tokens.size - 1
        val fetched = tokens.mapIndexed { i, t -> async { store.englishPostings(t, prefix = i == last) } }.map { it.await() }
        // Packed postings per token: id << 32 | sense << 3 | exact << 2 | flags, sorted.
        val lists = ArrayList<LongArray>(tokens.size)
        for (i in tokens.indices) {
            val ps = fetched[i]
            if (ps.isEmpty()) return@coroutineScope EnglishOut(emptyList(), false)
            var size = 0
            for (p in ps) size += p.ids.size
            val arr = LongArray(size)
            var n = 0
            for (p in ps) {
                val exact = if (p.token == tokens[i]) 4L else 0L
                for (k in p.ids.indices) {
                    val sf = p.senses[k]
                    arr[n++] = (p.ids[k].toLong() shl 32) or ((sf ushr 2).toLong() shl 3) or exact or (sf and 3).toLong()
                }
            }
            if (ps.size > 1) arr.sort()
            lists.add(arr)
        }
        currentCoroutineContext().ensureActive()

        val cands = ArrayList<EnCand>()
        val ptr = IntArray(lists.size)
        val starts = IntArray(lists.size)
        val ends = IntArray(lists.size)
        val driver = lists.indices.minByOrNull { lists[it].size }!!
        var steps = 0
        outer@ while (ptr[driver] < lists[driver].size) {
            if (++steps and 4095 == 0) currentCoroutineContext().ensureActive()
            val id = (lists[driver][ptr[driver]] ushr 32).toInt()
            var all = true
            for (t in lists.indices) {
                val arr = lists[t]
                var p = ptr[t]
                if (t != driver) {
                    // Gallop to the first posting with entry id >= id.
                    var lo = p
                    var hi = arr.size
                    val target = id.toLong() shl 32
                    while (lo < hi) {
                        val mid = (lo + hi) ushr 1
                        if (arr[mid] < target) lo = mid + 1 else hi = mid
                    }
                    p = lo
                }
                starts[t] = p
                var e = p
                while (e < arr.size && (arr[e] ushr 32).toInt() == id) e++
                ends[t] = e
                ptr[t] = if (t == driver) e else p
                if (e == p) all = false
            }
            if (!all) continue@outer
            val cand = evaluate(id, lists, starts, ends, tokens.size, wantCanto = true)
                ?: evaluate(id, lists, starts, ends, tokens.size, wantCanto = false)
            if (cand != null) cands.add(cand)
        }
        if (cands.isEmpty()) return@coroutineScope EnglishOut(emptyList(), false)

        val projs = store.projections(IntArray(cands.size) { cands[it].id })
        val order = Ranking.comparator(false)
        fun toCandidates(src: List<EnCand>) = src.mapNotNull { e -> projs[e.id]?.let { Candidate(it, e.cls, mandarinRow = cantonese && !e.canto && !it.isUnihan, sense = e.sense) } }

        // Phrase refinement (SRCH-047): contiguous token sequence inside one sense — a local check
        // on the candidate's per-sense token lists (DATA-077), bounded to the best-ranked candidates.
        if (tokens.size > 1) {
            val pending = cands.filter { it.senses != null }
                .sortedWith(compareBy({ projs[it.id]?.isUnihan ?: true }, { projs[it.id]?.freqRank ?: Int.MAX_VALUE }, { it.id }))
                .take(PHRASE_CHECK)
            val entries = store.entries(pending.map { it.id })
            for (e in pending) {
                val senses = entries[e.id]?.body?.senses ?: continue
                if (e.senses!!.any { s -> senses.getOrNull(s)?.tok?.let { containsSequence(it, tokens) } == true }) e.cls = MatchClass.EN_PHRASE
            }
        }

        val sections = ArrayList<SectionOut>()
        var budget = cap
        var capped = false
        fun section(kind: SectionKind, key: String, rows: List<Candidate>) {
            val ranked = Ranking.dedupeMerged(rows.sortedWith(order), script) { it.proj }
            if (ranked.isEmpty() || budget <= 0) {
                if (ranked.isNotEmpty()) capped = true
                return
            }
            val take = if (ranked.size > budget) ranked.subList(0, budget) else ranked
            if (take.size < ranked.size) capped = true
            budget -= take.size
            sections.add(SectionOut(kind, key, null, take))
        }
        if (!cantonese) {
            section(SectionKind.ENGLISH, "e", toCandidates(cands))
        } else {
            val canto = cands.filter { it.canto }
            val cantoIds = canto.mapTo(HashSet()) { it.id }
            val rest = toCandidates(cands.filter { !it.canto && it.id !in cantoIds })
            section(SectionKind.ENGLISH_CC_CANTO, "ec", toCandidates(canto))
            section(SectionKind.ENGLISH_CC_CEDICT, "em", rest.filter { !it.proj.isUnihan })
            section(SectionKind.ENGLISH_UNIHAN, "eu", rest.filter { it.proj.isUnihan })
        }
        EnglishOut(sections, capped)
    }

    /** Class refinement for one entry within one source group (SRCH-047, SRCH-048, SRCH-077). */
    private fun evaluate(id: Int, lists: List<LongArray>, starts: IntArray, ends: IntArray, tokenCount: Int, wantCanto: Boolean): EnCand? {
        if (wantCanto && !cantonese) return null
        fun inGroup(v: Long) = !cantonese || ((v and 1L) != 0L) == wantCanto
        for (t in 0 until tokenCount) {
            var any = false
            for (k in starts[t] until ends[t]) if (inGroup(lists[t][k])) {
                any = true
                break
            }
            if (!any) return null
        }
        val lastTok = tokenCount - 1
        if (tokenCount == 1) {
            var exact = false
            var first = -1
            for (k in starts[0] until ends[0]) {
                val v = lists[0][k]
                if (!inGroup(v)) continue
                val isExact = (v and 4L) != 0L
                if (first < 0 || (isExact && !exact)) first = ((v and 0xFFFFFFFFL) ushr 3).toInt()
                if (isExact) exact = true
            }
            return EnCand(id, if (exact) MatchClass.EN_PHRASE else MatchClass.EN_SENSE, wantCanto, null, first)
        }
        var common: ArrayList<Int>? = null
        var firstSense = -1
        for (k in starts[0] until ends[0]) {
            val v = lists[0][k]
            if (!inGroup(v)) continue
            val sense = ((v and 0xFFFFFFFFL) ushr 3).toInt()
            if (firstSense < 0) firstSense = sense
            var ok = true
            for (t in 1..lastTok) {
                var found = false
                for (j in starts[t] until ends[t]) {
                    val w = lists[t][j]
                    if (inGroup(w) && ((w and 0xFFFFFFFFL) ushr 3).toInt() == sense) {
                        found = true
                        break
                    }
                }
                if (!found) {
                    ok = false
                    break
                }
            }
            if (ok) (common ?: ArrayList<Int>().also { common = it }).let { if (sense !in it) it.add(sense) }
        }
        val c = common
        return if (c != null) EnCand(id, MatchClass.EN_SENSE, wantCanto, c.toIntArray(), c[0]) else EnCand(id, MatchClass.EN_ENTRY, wantCanto, null, firstSense)
    }

    private fun containsSequence(tok: List<String>, query: List<String>): Boolean {
        if (query.size > tok.size) return false
        for (i in 0..tok.size - query.size) {
            var ok = true
            for (j in query.indices) if (tok[i + j] != query[j]) {
                ok = false
                break
            }
            if (ok) return true
        }
        return false
    }

    // ------------------------------------------------------------------ Combinations

    private class Span(val from: Int, val to: Int, val tokens: List<Tok>, val key: String, val penalty: Double)

    /**
     * Multi-word combinations (SRCH-060…SRCH-068): builds the lattice from the kept paths with one
     * batched first-letter read, reads the bigram blocks of the distinct left words in a second
     * batch, and runs the shared decoder. [wholeHeadwords] are the headwords already matching the
     * whole query (dropped per SRCH-063).
     */
    suspend fun combinations(parse: RomanParse, script: Script, wholeIds: IntArray, englishWords: Boolean = false): List<ComboOut> {
        if (!store.hasLm || parse.paths.none { it.tokens.size >= 2 }) return emptyList()
        // A query made of common English words is combined only through its full-syllable readings:
        // `hello` as he·l·lo or `ban` as ba·n… is not what was typed (SCHEMA note 19).
        fun spelled(p: RomanPath) = !englishWords || p.tokens.all { it.kind == TokKind.FULL }
        if (parse.paths.none { it.tokens.size >= 2 && spelled(it) }) return emptyList()
        val inv = store.inventory()
        val end = parse.letters.length
        val spans = LinkedHashMap<String, Span>()
        for (path in parse.paths) {
            val toks = path.tokens
            // A one-token path forms no edge; its span only sets the bar of the whole-query words.
            val edges = spelled(path)
            for (a in toks.indices) for (len in 1..minOf(MAX_EDGE_SYLLABLES, toks.size - a)) {
                if (!edges && !(a == 0 && len == toks.size)) continue
                val sub = toks.subList(a, a + len)
                if (sub.any { it.kind == TokKind.LITERAL }) break
                val sig = sub.joinToString("|") { "${it.kind.ordinal}${it.text}${it.tone}@${it.start}" }
                if (sig in spans) continue
                var pen = 0.0
                for (t in sub) pen += when (t.kind) {
                    TokKind.ABBR -> store.abbrevPenalty
                    TokKind.PARTIAL -> store.partialPenalty
                    else -> 0.0
                }
                spans[sig] = Span(sub.first().start, sub.last().end, sub, sub.joinToString("") { it.initial.toString() }, pen)
            }
        }
        if (spans.isEmpty()) return emptyList()
        val postings = store.abbrevPostings(spans.values.map { it.key }.toSet(), emptyList())
        if (postings.isEmpty()) return emptyList()
        currentCoroutineContext().ensureActive()

        // `seq` is the posting's place in its key: unigram order, then the primary reading of a word first.
        class Raw(val span: Span, val p: AbbrPosting, val seq: Int)
        val raws = ArrayList<Raw>()
        for (span in spans.values) {
            val list = postings[span.key] ?: continue
            for ((seq, p) in list.withIndex()) if (p.alt == 0 && p.lmWord >= 0 && matches(p, span.tokens, inv, allowLonger = false)) raws.add(Raw(span, p, seq))
        }
        // Toned tokens constrain edges too; tones are not in the index, so read them once for the survivors.
        val toneCheck = if (spans.values.any { s -> s.tokens.any { it.tone != 0 } }) store.forms(raws.map { it.p.entryId }.toSet().toIntArray()) else null
        val books = store.codebooks()
        val nodes = (spans.values.flatMap { listOf(it.from, it.to) } + listOf(0, end)).distinct().sorted()
        val nodeIndex = nodes.withIndex().associate { it.value to it.index }
        // Per (from, to): best edge per entry, then the M best words.
        val perSpan = HashMap<Long, HashMap<Int, LatticeEdge>>()
        var wholeBest = Double.NEGATIVE_INFINITY
        val order = HashMap<Int, Int>()
        for (r in raws) {
            if (toneCheck != null && r.span.tokens.any { it.tone != 0 }) {
                val f = toneCheck[r.p.entryId] ?: continue
                if (!tonesMatch(r.span.tokens, f.tones)) continue
            }
            val from = nodeIndex[r.span.from]!!
            val to = nodeIndex[r.span.to]!!
            if (from == 0 && to == nodes.size - 1) {
                // A word spelling the whole query is not a combination; its score is the bar combinations are held to.
                val score = books[0][r.p.unigram] - r.span.penalty
                if (score > wholeBest) wholeBest = score
                continue
            }
            val bucket = perSpan.getOrPut(from.toLong() shl 32 or to.toLong()) { HashMap() }
            val edge = LatticeEdge(from, to, r.p.lmWord, books[0][r.p.unigram], -r.span.penalty, r.p.entryId, r.span.tokens.size)
            val old = bucket[r.p.entryId]
            if (old == null || edge.penalty > old.penalty) {
                bucket[r.p.entryId] = edge
                if (old == null) order[r.p.entryId] = r.seq
            }
        }
        val edges = ArrayList<LatticeEdge>()
        for (bucket in perSpan.values) {
            // One edge per word: among the entries of a word (its readings), the first in posting order.
            val seenWords = HashSet<Int>()
            bucket.values.sortedWith(compareByDescending<LatticeEdge> { it.unigram + it.penalty }.thenBy { order[it.payload] ?: 0 }.thenBy { it.payload })
                .filter { seenWords.add(it.word) }.take(MAX_WORDS_PER_SPAN).forEach { edges.add(it) }
        }
        if (edges.isEmpty()) return emptyList()
        val lastNode = nodes.size - 1
        val lefts = edges.filter { it.to != lastNode }.mapTo(HashSet()) { it.word }
        val bigrams = if (store.hasBigram) store.bigramBlocks(lefts) else emptyMap()
        val ctx = currentCoroutineContext()
        val paths = LatticeDecoder.decode(nodes.size, edges, bigrams, K_BEST, BEAM) { ctx.ensureActive() }
            .filter { it.edges.size >= 2 }
        if (paths.isEmpty()) return emptyList()

        // Plausible sequences only: within COMBO_MARGIN of the best sequence and of the best single
        // word spelling the whole query — a far less likely split of 朋友 (碰 + 有) is noise, not a
        // suggestion — and not a string of rare words forced onto the letters (`tofu` as 他 哦 副).
        val bar = maxOf(paths[0].score, wholeBest) - COMBO_MARGIN
        val top = paths.take(K_BEST * 4).filter { p -> p.score >= bar && p.score >= COMBO_FLOOR_PER_SYLLABLE * p.edges.sumOf { it.units } }
        if (top.isEmpty()) return emptyList()
        val forms = store.forms((top.flatMap { p -> p.edges.map { it.payload } } + wholeIds.take(400)).toSet().toIntArray())
        fun head(id: Int): String = forms[id]?.let { if (script == Script.SIMPLIFIED) it.simp else it.trad } ?: ""
        val whole = HashSet<String>()
        for (id in wholeIds.take(400)) forms[id]?.let { whole.add(it.simp); whole.add(it.trad) }
        val seen = HashSet<String>()
        val out = ArrayList<ComboOut>()
        for (p in top) {
            val heads = p.edges.map { head(it.payload) }
            if (heads.joinToString("") in whole) continue
            if (!seen.add(heads.joinToString("\u0001"))) continue
            out.add(ComboOut(IntArray(p.edges.size) { p.edges[it].payload }, p.score))
            if (out.size >= MAX_COMBINATIONS) break
        }
        return out
    }

    companion object {
        const val MAX_EDGE_SYLLABLES = 6
        const val MAX_WORDS_PER_SPAN = 40
        const val K_BEST = 8
        const val MAX_COMBINATIONS = 5
        const val BEAM = 18.0
        /** Natural-log distance from the best reading beyond which a combination is not offered. */
        const val COMBO_MARGIN = 2.5
        /** Lowest average log-probability per syllable of an offered combination. */
        const val COMBO_FLOOR_PER_SYLLABLE = -6.5
        const val PHRASE_CHECK = 600
    }
}
