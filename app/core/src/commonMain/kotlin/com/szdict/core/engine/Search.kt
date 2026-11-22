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

/**
 * One group of the Chinese side (SRCH-033): [english] when its entries were found through the
 * English side for a foreign word (SRCH-033a), [punctuation] for a punctuation run (SRCH-033b).
 */
internal class GroupOut(
    val text: String, val start: Int, val length: Int, val inert: Boolean, val ids: List<Int>,
    val english: Boolean = false, val punctuation: Boolean = false,
)

internal class ChineseOut(
    val groups: List<GroupOut>,
    val sections: List<SectionOut>,
    val isSentence: Boolean,
    val passage: AnalysedPassage?,
    val capped: Boolean,
    /** Leading rows of the PATTERN section that are exact-length matches (SRCH-038), where mixed combinations go (SRCH-069). */
    val patternExact: Int = 0,
)

internal class EnglishOut(val sections: List<SectionOut>, val capped: Boolean)

/**
 * One grouped combination row (SRCH-065): per slot the alternative entry ids, the first being the
 * best sequence's word; [score] is the row's best sequence.
 */
internal class ComboOut(val slots: List<IntArray>, val score: Double) {
    val entryIds: IntArray get() = IntArray(slots.size) { slots[it][0] }
}

/** The combinations of a query: grouped [rows] (shown) and the kept sequences in rank order ([flat], for evaluation). */
internal class CombosOut(val rows: List<ComboOut>, val flat: List<IntArray>) {
    companion object {
        val EMPTY = CombosOut(emptyList(), emptyList())
    }
}

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
                if (store.lmReady()) abbrPaths.add(path to path.tokens.joinToString("") { it.initial.toString() })
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
        // Hanzi-only needle of Starts with / Contains for a mixed query whose Latin part is noise (SRCH-022, SRCH-162).
        val hanziOnly = if (c.kind == QueryKind.MIXED && !patternValid) c.key.filter { it.isSurrogate() || isHanzi(it.code) || it == '·' } else null
        val exactKeys = listOf(c.key, compact).distinct()
        val exactHits = store.headwordExact(exactKeys)
        val exact = (exactHits[c.key] ?: exactHits[compact]).orEmpty()
            .map { it.proj }.distinctBy { it.id }.sortedWith(projOrder)

        val groups = ArrayList<GroupOut>()
        var passage: AnalysedPassage? = null
        var isSentence = false
        if (exact.isNotEmpty()) {
            groups.add(GroupOut(c.display, 0, c.display.length, false, Ranking.dedupeMerged(exact, script) { it }.map { it.id }))
        } else {
            val analysis = segmenter.analyse(c.display, SegmentationMode.FULL, generation)
            passage = analysis.passage
            groups.addAll(sentenceGroups(c.display, analysis, script))
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
        var patternExact = 0
        if (patternValid) {
            val rows = pattern(c, script)
            capped = section(SectionKind.PATTERN, "x", null, rows) || capped
            val shown = sections.lastOrNull()?.takeIf { it.kind == SectionKind.PATTERN }?.rows.orEmpty()
            while (patternExact < shown.size && shown[patternExact].cls == MatchClass.EXACT) patternExact++
        }
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
        ChineseOut(groups, sections, isSentence, if (isSentence) passage else null, capped, patternExact)
    }

    /**
     * The groups of sentence mode (SRCH-033 item 2, SRCH-033a, SRCH-033b): every non-whitespace
     * character in exactly one group, in text order. A matched segment is a group; inside unmatched
     * segments a foreign word is a group of its own (its headword, else up to three English-side
     * whole-word matches, else NO MATCH), a punctuation run is a punctuation group, and unmatched
     * text merges with adjacent unmatched text (TXT-073) but not across either of those.
     */
    private suspend fun sentenceGroups(text: String, analysis: Analysis, script: Script): List<GroupOut> = coroutineScope {
        val projOrder = Ranking.projComparator(cantonese)
        val cps = text.cps()
        val classes = TextClass.classify(cps)
        val cls16 = IntArray(text.length)
        run {
            var o = 0
            for (i in cps.indices) {
                val l = cpLen(cps[i])
                for (k in 0 until l) cls16[o + k] = classes[i]
                o += l
            }
        }
        val out = ArrayList<GroupOut>()
        val foreign = ArrayList<Int>() // indices into out of unresolved foreign-word groups
        var textStart = -1
        var textEnd = -1
        fun flushText() {
            if (textStart >= 0) out.add(GroupOut(text.substring(textStart, textEnd), textStart, textEnd - textStart, true, emptyList()))
            textStart = -1
        }
        for (seg in analysis.passage.segments) {
            if (seg.matched) {
                flushText()
                val sub = text.substring(seg.start, seg.end)
                val hits = analysis.hitsFor(sub).map { it.proj }.distinctBy { it.id }.sortedWith(projOrder)
                out.add(GroupOut(sub, seg.start, seg.length, false, Ranking.dedupeMerged(hits, script) { it }.map { it.id }))
                continue
            }
            var i = seg.start
            while (i < seg.end) {
                val k = cls16[i]
                var j = i + 1
                while (j < seg.end && cls16[j] == k) j++
                when (k) {
                    TextClass.TEXT -> {
                        if (textStart < 0) textStart = i
                        textEnd = j
                    }
                    TextClass.WORD -> {
                        flushText()
                        foreign.add(out.size)
                        out.add(GroupOut(text.substring(i, j), i, j - i, true, emptyList()))
                    }
                    TextClass.PUNCT -> {
                        flushText()
                        out.add(GroupOut(text.substring(i, j), i, j - i, true, emptyList(), punctuation = true))
                    }
                }
                i = j
            }
        }
        flushText()
        if (foreign.isEmpty()) return@coroutineScope out

        // SRCH-033a (a): a headword equal to the word ignoring case, or with its joiners removed. One batched read.
        fun folded(w: String) = normalizeNfkc(w).lowercase()
        fun bare(w: String) = folded(w).filter { !TextClass.isJoiner(it.code) }
        val keys = LinkedHashSet<String>()
        for (idx in foreign) out[idx].text.let { w -> keys.add(w); keys.add(folded(w)); keys.add(bare(w)) }
        val hits = store.headwordExact(keys)
        val english = HashMap<Int, kotlinx.coroutines.Deferred<List<Int>>>()
        for (idx in foreign) {
            val g = out[idx]
            val w = g.text
            val direct = (hits[w].orEmpty() + hits[folded(w)].orEmpty()).ifEmpty { hits[bare(w)].orEmpty() }
            if (direct.isNotEmpty()) {
                val ps = direct.map { it.proj }.distinctBy { it.id }.sortedWith(projOrder)
                out[idx] = GroupOut(w, g.start, g.length, false, Ranking.dedupeMerged(ps, script) { it }.map { it.id })
            } else if (w.cpCount() > 1 && folded(w).any { it.isLetter() }) {
                // (b) the English side, whole words only; a word of digits alone, or a single letter, skips it.
                english[idx] = async { englishWhole(folded(w), script, ENGLISH_MATCHES) }
            }
        }
        for ((idx, job) in english) {
            val ids = job.await()
            val g = out[idx]
            if (ids.isNotEmpty()) out[idx] = GroupOut(g.text, g.start, g.length, false, ids, english = true)
        }
        out
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

    private class EnMatch(val cands: List<EnCand>, val projs: Map<Int, Proj>)

    suspend fun english(c: Classified, script: Script, cap: Int): EnglishOut {
        val tokens = Normaliser.englishTokens(c.key)
        if (tokens.isEmpty() || c.kind == QueryKind.EMPTY) return EnglishOut(emptyList(), false)
        val m = englishMatch(tokens, prefixLast = true) ?: return EnglishOut(emptyList(), false)
        val cands = m.cands
        val projs = m.projs
        val order = Ranking.comparator(false)
        fun toCandidates(src: List<EnCand>) = src.mapNotNull { e -> projs[e.id]?.let { Candidate(it, e.cls, mandarinRow = cantonese && !e.canto && !it.isUnihan, sense = e.sense) } }

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
        return EnglishOut(sections, capped)
    }

    /**
     * SRCH-033a (b): the entries whose definitions hold [word] as whole words — every token exact
     * (no prefix match) and, for several tokens, contiguous in one sense (`EN_PHRASE`) — in the
     * English side's order (CC-Canto first in Cantonese mode), at most [limit].
     */
    suspend fun englishWhole(word: String, script: Script, limit: Int): List<Int> {
        val tokens = Normaliser.englishTokens(word)
        if (tokens.isEmpty()) return emptyList()
        val m = englishMatch(tokens, prefixLast = false) ?: return emptyList()
        val order = Ranking.comparator(false)
        val rows = m.cands.filter { it.cls == MatchClass.EN_PHRASE }
            .mapNotNull { e -> m.projs[e.id]?.let { Candidate(it, e.cls, sense = e.sense) to e.canto } }
        val ranked = rows.sortedWith(compareBy<Pair<Candidate, Boolean>> { if (cantonese && !it.second) 1 else 0 }.thenComparator { a, b -> order.compare(a.first, b.first) })
            .map { it.first }.distinctBy { it.proj.id }
        // The index tokenises at every non-ASCII letter (`Bomê` holds the token `bom`): keep the entries
        // whose sense text has the word between non-letters, checking a bounded head of the list.
        val head = Ranking.dedupeMerged(ranked, script) { it.proj }.take(WHOLE_WORD_CHECK)
        val bodies = store.entries(head.map { it.proj.id })
        val out = ArrayList<Int>(limit)
        for (cand in head) {
            val senses = bodies[cand.proj.id]?.body?.senses ?: continue
            if (senses.any { containsWholeWord(it.t, word) }) out.add(cand.proj.id)
            if (out.size >= limit) break
        }
        return out
    }

    private fun containsWholeWord(text: String, word: String): Boolean {
        val t = text.lowercase()
        var i = t.indexOf(word)
        while (i >= 0) {
            val before = if (i > 0) t[i - 1] else ' '
            val after = if (i + word.length < t.length) t[i + word.length] else ' '
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            i = t.indexOf(word, i + 1)
        }
        return false
    }

    /** Retrieval and class refinement of the English side (SRCH-045…SRCH-048); null when nothing matches. */
    private suspend fun englishMatch(tokens: List<String>, prefixLast: Boolean): EnMatch? = coroutineScope {
        val last = tokens.size - 1
        val fetched = tokens.mapIndexed { i, t -> async { store.englishPostings(t, prefix = prefixLast && i == last) } }.map { it.await() }
        // Packed postings per token: id << 32 | sense << 3 | exact << 2 | flags, sorted.
        val lists = ArrayList<LongArray>(tokens.size)
        for (i in tokens.indices) {
            val ps = fetched[i]
            if (ps.isEmpty()) return@coroutineScope null
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
        if (cands.isEmpty()) return@coroutineScope null

        val projs = store.projections(IntArray(cands.size) { cands[it].id })

        // Phrase refinement (SRCH-047): contiguous token sequence inside one sense — a local check
        // on the candidate's per-sense token lists (DATA-077), bounded to the best-ranked candidates.
        if (tokens.size > 1) {
            val pending = cands.filter { it.senses != null }
                .sortedWith(compareBy({ projs[it.id]?.isUnihan ?: true }, { projs[it.id]?.let(Ranking::effectiveRank) ?: Int.MAX_VALUE }, { projs[it.id]?.freqRank ?: Int.MAX_VALUE }, { it.id }))
                .take(PHRASE_CHECK)
            val entries = store.entries(pending.map { it.id })
            for (e in pending) {
                val senses = entries[e.id]?.body?.senses ?: continue
                if (e.senses!!.any { s -> senses.getOrNull(s)?.tok?.let { containsSequence(it, tokens) } == true }) e.cls = MatchClass.EN_PHRASE
            }
        }
        EnMatch(cands, projs)
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
     * batched first-letter read, reads the combination unigrams and bigram blocks of its words in two
     * more batches, runs the shared decoder, rescores its k-best list with the trigrams of the paths'
     * contexts (one batch), applies the show policy and groups the kept sequences into rows.
     * [wholeIds] are the entries already matching the whole query: a sequence spelling one of their
     * headwords is dropped (SRCH-063); they play no other part. A companion without the combination
     * model (DATA-156) falls back to the segmentation bigrams and unigrams, without rescoring.
     */
    suspend fun combinations(parse: RomanParse, script: Script, wholeIds: IntArray, englishWords: Boolean = false): CombosOut {
        if (parse.paths.none { it.tokens.size >= 2 } || !store.lmReady()) return CombosOut.EMPTY
        val end = parse.letters.length
        // Syllable boundaries the user typed (spaces, apostrophes, tone digits).
        val typed = parse.breaks.toHashSet()
        fun wholeWord(t: Tok) = (t.start == 0 || t.start in typed) && (t.end == end || t.end in typed)
        // A query made of common English words is combined only through its full-syllable readings —
        // `hello` as he·l·lo or `ban` as ba·n… is not what was typed (SCHEMA note 19) — or through a
        // letter typed as a word of its own (`wo shi yi g`).
        fun spelled(p: RomanPath) = !englishWords || p.tokens.all { it.kind == TokKind.FULL || wholeWord(it) }
        if (parse.paths.none { it.tokens.size >= 2 && spelled(it) }) return CombosOut.EMPTY
        val inv = store.inventory()
        val spans = LinkedHashMap<String, Span>()
        for (path in parse.paths) {
            val toks = path.tokens
            if (toks.size < 2 || !spelled(path)) continue
            for (a in toks.indices) for (len in 1..minOf(MAX_EDGE_SYLLABLES, toks.size - a)) {
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
        if (spans.isEmpty()) return CombosOut.EMPTY
        val postings = store.abbrevPostings(spans.values.map { it.key }.toSet(), emptyList())
        if (postings.isEmpty()) return CombosOut.EMPTY
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
        // Per (from, to): best edge per entry, then the M best words by the segmentation unigram.
        val perSpan = HashMap<Long, HashMap<Int, LatticeEdge>>()
        val order = HashMap<Int, Int>()
        for (r in raws) {
            if (toneCheck != null && r.span.tokens.any { it.tone != 0 }) {
                val f = toneCheck[r.p.entryId] ?: continue
                if (!tonesMatch(r.span.tokens, f.tones)) continue
            }
            val from = nodeIndex[r.span.from]!!
            val to = nodeIndex[r.span.to]!!
            // A word spelling the whole query is not a combination.
            if (from == 0 && to == nodes.size - 1) continue
            val bucket = perSpan.getOrPut(from.toLong() shl 32 or to.toLong()) { HashMap() }
            val edge = LatticeEdge(from, to, r.p.lmWord, books[0][r.p.unigram], -r.span.penalty, r.p.entryId, r.span.tokens.size)
            val old = bucket[r.p.entryId]
            if (old == null || edge.penalty > old.penalty) {
                bucket[r.p.entryId] = edge
                if (old == null) order[r.p.entryId] = r.seq
            }
        }
        var edges = ArrayList<LatticeEdge>()
        for (bucket in perSpan.values) {
            // One edge per word: among the entries of a word (its readings), the first in posting order.
            val seenWords = HashSet<Int>()
            bucket.values.sortedWith(compareByDescending<LatticeEdge> { it.unigram + it.penalty }.thenBy { order[it.payload] ?: 0 }.thenBy { it.payload })
                .filter { seenWords.add(it.word) }.take(MAX_WORDS_PER_SPAN).forEach { edges.add(it) }
        }
        return decodeCombos(nodes.size, edges, u2Applied = false, script, wholeIds, Combinations.FLOOR_PER_SYLLABLE)
    }

    /**
     * The shared back half of the combinations (SRCH-062, SRCH-063, SRCH-065): reads the bigram
     * blocks of the lattice's left words (and, unless [u2Applied], replaces the edges' unigrams by the
     * combination model's), runs the decoder, rescores with the trigrams, drops sequences spelling a
     * headword of [wholeIds], applies the show policy with [floor] nats per syllable and groups rows.
     */
    private suspend fun decodeCombos(
        nodeCount: Int, edgesIn: List<LatticeEdge>, u2Applied: Boolean, script: Script, wholeIds: IntArray, floor: Double,
    ): CombosOut {
        var edges = edgesIn
        if (edges.isEmpty()) return CombosOut.EMPTY
        val lastNode = nodeCount - 1
        val lefts = edges.filter { it.to != lastNode }.mapTo(HashSet()) { it.word }
        val model2 = store.hasNgram2()
        val bigrams = if (model2) {
            // The combination model scores every word with its own unigram (the first word, and the backoff).
            if (!u2Applied) {
                val u2 = store.unigram2(edges.mapTo(HashSet()) { it.word })
                edges = edges.map { e -> u2[e.word]?.let { LatticeEdge(e.from, e.to, e.word, it, e.penalty, e.payload, e.units) } ?: e }
            }
            store.bigram2Blocks(lefts)
        } else if (store.hasBigram) store.bigramBlocks(lefts) else emptyMap()
        val ctx = currentCoroutineContext()
        var paths = LatticeDecoder.decode(nodeCount, edges, bigrams, K_BEST, BEAM) { ctx.ensureActive() }
            .filter { it.edges.size >= 2 }.take(Combinations.RESCORE)
        if (paths.isEmpty()) return CombosOut.EMPTY
        if (model2 && store.hasTrigram()) paths = Combinations.rescore(paths, bigrams, store.trigramContexts(Combinations.contexts(paths)))
        ctx.ensureActive()

        // Candidates: the first POOL distinct sequences, a sequence spelling a whole-query headword left out.
        val forms = store.forms((paths.flatMap { p -> p.edges.map { it.payload } } + wholeIds.take(400)).toSet().toIntArray())
        fun head(id: Int): String = forms[id]?.let { if (script == Script.SIMPLIFIED) it.simp else it.trad } ?: ""
        val whole = HashSet<String>()
        for (id in wholeIds.take(400)) forms[id]?.let { whole.add(it.simp); whole.add(it.trad) }
        val seen = HashSet<String>()
        val cands = ArrayList<Pair<LatticePath, List<String>>>()
        for (p in paths) {
            val heads = p.edges.map { head(it.payload) }
            if (heads.joinToString("") in whole) continue
            if (!seen.add(heads.joinToString("\u0001"))) continue
            cands.add(p to heads)
            if (cands.size >= Combinations.POOL) break
        }
        val kept = Combinations.select(cands, { it.first.score }, { c -> c.first.edges.sumOf { it.units } }, floor = floor)
        if (kept.isEmpty()) return CombosOut.EMPTY
        val rows = Combinations.group(kept.map { (p, heads) ->
            p.edges.mapIndexed { i, e -> Combinations.Word(e.from, e.to, e.payload, heads[i]) } to p.score
        })
        return CombosOut(rows.map { ComboOut(it.slots, it.score) }, kept.map { (p, _) -> IntArray(p.edges.size) { p.edges[it].payload } })
    }

    /**
     * SRCH-069 trigger: a mixed query whose Latin runs all parse as romanisation ([Classified.parts]
     * is set only then) and whose foreign words were typed without an uppercase letter (`BOM`,
     * `KTV`, `iPhone` are words, not pinyin); digits other than tone digits already fail the parse.
     */
    fun mixedCombinable(c: Classified): Boolean {
        if (c.kind != QueryKind.MIXED || c.parts.isEmpty() || c.patterns.isEmpty()) return false
        val cps = c.display.cps()
        val classes = TextClass.classify(cps)
        for (i in cps.indices) if (classes[i] == TextClass.WORD && cps[i] < 0x10000 && cps[i].toChar().isUpperCase()) return false
        return true
    }

    /** One item of the mixed lattice (SRCH-069): a token of a Latin run, or a typed Hanzi ([tok] null), over positions `[from, to)`. */
    private class MItem(val tok: Tok?, val hanzi: Int, val from: Int, val to: Int) {
        val sig: String get() = if (tok == null) "h$hanzi@$from" else "${tok.kind.ordinal}${tok.text}${tok.tone}@$from"
    }

    private class MSpan(val items: List<MItem>, val penalty: Double) {
        val from: Int get() = items.first().from
        val to: Int get() = items.last().to
        val pinned: Int get() = items.count { it.tok == null }
    }

    /**
     * Mixed combinations (SRCH-069): the lattice's positions are the query's items — each typed Hanzi
     * pinned, each Latin run's kept token paths as in SRCH-061 — and an edge is a dictionary word of
     * 1…6 syllables, one per character, whose characters equal the pinned Hanzi (either script) and
     * whose syllables match the tokens (SRCH-042) at their positions. Token-only spans read the
     * first-letter index, spans with pinned Hanzi the posting list of their rarest pinned character,
     * spans of pinned Hanzi only the headword index; all in one batch each. Scoring, policy and rows
     * are those of [combinations]; a pinned Hanzi has no penalty and counts as one syllable.
     * [wholeIds]: the exact group and exact-length pattern matches, whose headwords no sequence repeats.
     */
    suspend fun mixedCombinations(c: Classified, script: Script, wholeIds: IntArray): CombosOut = coroutineScope {
        if (c.parts.isEmpty() || !store.lmReady()) return@coroutineScope CombosOut.EMPTY
        val inv = store.inventory()
        // A run made of common English words is combined only through its full-syllable readings or
        // letters typed as words of their own (SCHEMA note 19), as for a pure query.
        val words = c.parts.filter { it.hanzi == 0 }.flatMap { it.run.split(' ', '\'', '·') }.filter { it.isNotEmpty() }.toSet()
        val counts = if (store.hasEnglishWords && words.isNotEmpty()) store.englishWordCounts(words) else emptyMap()
        val known = counts.keys
        class Seg(val pinned: MItem?, val paths: List<List<MItem>>)
        val segs = ArrayList<Seg>()
        val segRun = HashMap<Int, String>() // run start position -> the run, when it is one word
        var base = 0
        for (part in c.parts) {
            if (part.hanzi > 0) {
                segs.add(Seg(MItem(null, part.hanzi, base, base + 1), emptyList()))
                base++
                continue
            }
            val len = part.paths.firstOrNull()?.tokens?.lastOrNull()?.end ?: return@coroutineScope CombosOut.EMPTY
            val english = part.run.split(' ', '\'', '·').filter { it.isNotEmpty() }.let { ws -> ws.isNotEmpty() && ws.all { it in known } }
            val typed = part.breaks.toHashSet()
            fun wholeWord(t: Tok) = (t.start == 0 || t.start in typed) && (t.end == len || t.end in typed)
            val paths = part.paths.filter { p -> !english || p.tokens.all { it.kind == TokKind.FULL || wholeWord(it) } }
                .map { p -> p.tokens.map { MItem(it, 0, base + it.start, base + it.end) } }
            if (paths.isEmpty()) return@coroutineScope CombosOut.EMPTY
            segs.add(Seg(null, paths))
            if (' ' !in part.run && '\'' !in part.run) segRun[base] = part.run
            base += len
        }
        val end = base

        // Spans: contiguous items of one reading, across run and Hanzi boundaries, 1…6 syllables.
        val spans = LinkedHashMap<String, MSpan>()
        fun emit(items: List<MItem>) {
            val sig = items.joinToString("|") { it.sig }
            if (sig in spans) return
            var pen = 0.0
            for (it in items) pen += when (it.tok?.kind) {
                TokKind.ABBR -> store.abbrevPenalty
                TokKind.PARTIAL -> store.partialPenalty
                else -> 0.0
            }
            spans[sig] = MSpan(items, pen)
        }
        fun extend(k: Int, acc: List<MItem>) {
            if (k >= segs.size) return
            val seg = segs[k]
            if (seg.pinned != null) {
                val next = acc + seg.pinned
                if (next.size > MAX_EDGE_SYLLABLES) return
                emit(next)
                extend(k + 1, next)
                return
            }
            for (path in seg.paths) for (b in path.indices) {
                if (acc.size + b + 1 > MAX_EDGE_SYLLABLES) break
                val next = acc + path.subList(0, b + 1)
                emit(next)
                if (b == path.lastIndex) extend(k + 1, next)
            }
        }
        for ((k, seg) in segs.withIndex()) {
            if (seg.pinned != null) extend(k, emptyList())
            else for (path in seg.paths) for (a in path.indices) for (b in a until minOf(path.size, a + MAX_EDGE_SYLLABLES)) {
                val sub = path.subList(a, b + 1)
                emit(sub)
                if (b == path.lastIndex) extend(k + 1, sub)
            }
        }
        // A word spelling the whole query is not a combination (it is a pattern match).
        spans.values.removeAll { it.from == 0 && it.to == end }
        if (spans.isEmpty()) return@coroutineScope CombosOut.EMPTY
        currentCoroutineContext().ensureActive()

        val tokenSpans = spans.values.filter { it.pinned == 0 }
        val pinnedSpans = spans.values.filter { it.pinned == it.items.size }
        val mixedSpans = spans.values.filter { it.pinned in 1 until it.items.size }
        val postingsJob = async {
            if (tokenSpans.isEmpty()) emptyMap()
            else store.abbrevPostings(tokenSpans.map { s -> s.items.joinToString("") { it.tok!!.initial.toString() } }.toSet(), emptyList())
        }
        val headJob = async {
            if (pinnedSpans.isEmpty()) emptyMap()
            else store.headwordExact(pinnedSpans.map { s -> s.items.joinToString("") { cpToString(it.hanzi) } }.toSet())
        }
        val charJob = async {
            if (mixedSpans.isEmpty()) emptyMap()
            else store.charPostings(mixedSpans.flatMap { s -> s.items.filter { it.tok == null }.map { cpToString(it.hanzi) } }.toSet())
        }
        val postings = postingsJob.await()
        val heads = headJob.await()
        val chars = charJob.await()

        // Candidate words per span: (span, entry id, LM word or -1 = to read, segmentation unigram, posting order).
        class Raw(val span: MSpan, val entryId: Int, var word: Int, var unigram: Double, val seq: Int)
        val raws = ArrayList<Raw>()
        val books = store.codebooks()
        for (span in tokenSpans) {
            val toks = span.items.map { it.tok!! }
            val list = postings[toks.joinToString("") { it.initial.toString() }] ?: continue
            for ((seq, p) in list.withIndex()) if (p.alt == 0 && p.lmWord >= 0 && matches(p, toks, inv, allowLonger = false)) raws.add(Raw(span, p.entryId, p.lmWord, books[0][p.unigram], seq))
        }
        // Spans holding pinned Hanzi are checked on the entries' forms, read once for all of them.
        val pending = ArrayList<Pair<MSpan, IntArray>>()
        for (span in pinnedSpans) {
            val hs = heads[span.items.joinToString("") { cpToString(it.hanzi) }] ?: continue
            pending.add(span to hs.filter { !it.proj.isUnihan }.map { it.proj.id }.distinct().toIntArray())
        }
        for (span in mixedSpans) {
            val ids = span.items.filter { it.tok == null }.mapNotNull { chars[cpToString(it.hanzi)] }.minByOrNull { it.size } ?: continue
            pending.add(span to ids)
        }
        val toneSpans = tokenSpans.any { s -> s.items.any { (it.tok?.tone ?: 0) != 0 } }
        val formIds = HashSet<Int>()
        for ((_, ids) in pending) for (id in ids) formIds.add(id)
        if (toneSpans) for (r in raws) formIds.add(r.entryId)
        val forms = store.forms(formIds.toIntArray())
        currentCoroutineContext().ensureActive()
        fun fits(f: DictionaryStore.Form, span: MSpan): Boolean {
            val n = span.items.size
            if (f.proj.charCount != n) return false
            val syl = if (f.syllables.isEmpty()) return false else f.syllables.split(' ')
            if (syl.size != n) return false
            val simp = f.simp.cps()
            val trad = f.trad.cps()
            if (simp.size != n || trad.size != n) return false
            for (pos in 0 until n) {
                val item = span.items[pos]
                val tok = item.tok
                val ok = if (tok == null) simp[pos] == item.hanzi || trad[pos] == item.hanzi else when (tok.kind) {
                    TokKind.FULL -> syl[pos] == tok.syllable && (tok.tone == 0 || (pos < f.tones.size && f.tones[pos].toInt() == tok.tone))
                    TokKind.ABBR -> inv.isAbbrevOf(tok.text, syl[pos])
                    TokKind.PARTIAL -> syl[pos].length > tok.text.length && syl[pos].startsWith(tok.text)
                    TokKind.LITERAL -> false
                }
                if (!ok) return false
            }
            return true
        }
        val viaForms = ArrayList<Raw>()
        for ((span, ids) in pending) for ((seq, id) in ids.withIndex()) {
            val f = forms[id] ?: continue
            if (fits(f, span)) viaForms.add(Raw(span, id, -1, 0.0, seq))
        }
        if (viaForms.isNotEmpty()) {
            val lm = store.lmEntries(viaForms.map { it.entryId }.toSet())
            for (r in viaForms) {
                val (w, u) = lm[r.entryId] ?: continue
                if (w < 0) continue
                r.word = w
                r.unigram = u
                raws.add(r)
            }
        }
        if (toneSpans) raws.removeAll { r -> r.span.pinned == 0 && r.span.items.any { (it.tok?.tone ?: 0) != 0 } && forms[r.entryId]?.let { !tonesMatch(r.span.items.map { i -> i.tok!! }, it.tones) } != false }
        if (raws.isEmpty()) return@coroutineScope CombosOut.EMPTY

        // A run that is one English word (the `english_word` table) is read as pinyin only when a word
        // spelling the whole run is, by its unigram, at least ENGLISH_RATIO times as likely as the English
        // word is common in the English corpus (SCHEMA note 19, weighed as SRCH-024 weighs pronunciation).
        for (seg in segs) {
            if (seg.pinned != null) continue
            val a = seg.paths.first().first().from
            val b = seg.paths.first().last().to
            val word = segRun[a] ?: continue
            val count = counts[word] ?: continue
            val share = count / store.englishTotal
            var best = Double.NEGATIVE_INFINITY
            for (r in raws) if (r.span.from == a && r.span.to == b && r.span.pinned == 0 && r.unigram > best) best = r.unigram
            if (kotlin.math.exp(best) < Combinations.ENGLISH_RATIO * share) return@coroutineScope CombosOut.EMPTY
        }

        // The combination model's unigrams, before the per-span cap (SRCH-069: M words by combination unigram).
        val model2 = store.hasNgram2()
        if (model2) {
            val u2 = store.unigram2(raws.mapTo(HashSet()) { it.word })
            for (r in raws) u2[r.word]?.let { r.unigram = it }
        }
        val nodes = (spans.values.flatMap { listOf(it.from, it.to) } + listOf(0, end)).distinct().sorted()
        val nodeIndex = nodes.withIndex().associate { it.value to it.index }
        val perSpan = HashMap<Long, HashMap<Int, Pair<LatticeEdge, Int>>>()
        for (r in raws) {
            val from = nodeIndex[r.span.from]!!
            val to = nodeIndex[r.span.to]!!
            val bucket = perSpan.getOrPut(from.toLong() shl 32 or to.toLong()) { HashMap() }
            val edge = LatticeEdge(from, to, r.word, r.unigram, -r.span.penalty, r.entryId, r.span.items.size)
            val old = bucket[r.entryId]
            if (old == null || edge.penalty > old.first.penalty) bucket[r.entryId] = edge to r.seq
        }
        val edges = ArrayList<LatticeEdge>()
        for (bucket in perSpan.values) {
            val seenWords = HashSet<Int>()
            bucket.values.sortedWith(compareByDescending<Pair<LatticeEdge, Int>> { it.first.unigram + it.first.penalty }.thenBy { it.second }.thenBy { it.first.payload })
                .filter { seenWords.add(it.first.word) }.take(MAX_WORDS_PER_SPAN).forEach { edges.add(it.first) }
        }
        decodeCombos(nodes.size, edges, u2Applied = true, script, wholeIds, Combinations.FLOOR_PER_SYLLABLE)
    }

    companion object {
        const val MAX_EDGE_SYLLABLES = 6
        const val MAX_WORDS_PER_SPAN = 40
        const val K_BEST = 8
        const val BEAM = 18.0
        const val PHRASE_CHECK = 600
        /** English-side entries shown for a foreign word without a headword (SRCH-033a). */
        const val ENGLISH_MATCHES = 3
        /** English-side entries whose sense text is checked for the whole word (SRCH-033a). */
        const val WHOLE_WORD_CHECK = 30
    }
}
