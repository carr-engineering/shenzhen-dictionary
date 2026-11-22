package com.szdict.core.domain

import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.AudioPlan
import com.szdict.core.api.BlockKind
import com.szdict.core.api.CharacterDecomposition
import com.szdict.core.api.Combination
import com.szdict.core.api.CombinationSlot
import com.szdict.core.api.DailyPick
import com.szdict.core.api.DatasetInfo
import com.szdict.core.api.DictError
import com.szdict.core.api.DictException
import com.szdict.core.api.DictMode
import com.szdict.core.api.Dictionary
import com.szdict.core.api.EntryCard
import com.szdict.core.api.EntryDetail
import com.szdict.core.api.Example
import com.szdict.core.api.ExamplePage
import com.szdict.core.api.HskBadge
import com.szdict.core.api.HskCard
import com.szdict.core.api.HskCharCard
import com.szdict.core.api.HskLevelInfo
import com.szdict.core.api.InsideWord
import com.szdict.core.api.Label
import com.szdict.core.api.MemberSection
import com.szdict.core.api.Note
import com.szdict.core.api.QueryKind
import com.szdict.core.api.ReadingGroup
import com.szdict.core.api.Redirect
import com.szdict.core.api.RedirectKind
import com.szdict.core.api.ReferenceCandidates
import com.szdict.core.api.RelatedKind
import com.szdict.core.api.RelatedPage
import com.szdict.core.api.Romanisation
import com.szdict.core.api.RowRef
import com.szdict.core.api.ScoredText
import com.szdict.core.api.Script
import com.szdict.core.api.SearchOptions
import com.szdict.core.api.SearchResult
import com.szdict.core.api.SearchSection
import com.szdict.core.api.SearchSide
import com.szdict.core.api.SectionKind
import com.szdict.core.api.SegmentationMode
import com.szdict.core.engine.TextClass
import com.szdict.core.api.Sense
import com.szdict.core.api.SettingsCodec
import com.szdict.core.api.SideResult
import com.szdict.core.api.SourceBlock
import com.szdict.core.api.SourceInfo
import com.szdict.core.api.SpanCandidate
import com.szdict.core.api.SpanCandidates
import com.szdict.core.api.SpanKind
import com.szdict.core.api.TextRange
import com.szdict.core.api.WordGroup
import com.szdict.core.engine.Candidate
import com.szdict.core.engine.ChineseOut
import com.szdict.core.engine.Classified
import com.szdict.core.engine.ComboOut
import com.szdict.core.engine.CombosOut
import com.szdict.core.engine.EnglishOut
import com.szdict.core.engine.Ranking
import com.szdict.core.engine.SearchEngine
import com.szdict.core.engine.SectionOut
import com.szdict.core.engine.Segmenter
import com.szdict.core.engine.codePointAtIndex
import com.szdict.core.engine.cps
import com.szdict.core.engine.containsHanzi
import com.szdict.core.engine.cpLen
import com.szdict.core.engine.cpString
import com.szdict.core.engine.cpToString
import com.szdict.core.engine.isHanzi
import com.szdict.core.store.Body
import com.szdict.core.store.Codec
import com.szdict.core.store.DictionaryStore
import com.szdict.core.store.EntryRow
import com.szdict.core.store.HeadHit
import com.szdict.core.store.Proj
import com.szdict.core.store.SenseJ
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.log10

private val generationLock = Mutex()
private var generationCounter = 0L

internal suspend fun openDictionary(datasetDir: String, mode: DictMode): Dictionary {
    val store = DictionaryStore.open(datasetDir.trimEnd('/'), mode)
    val generation = generationLock.withLock { ++generationCounter }
    return DictionaryImpl(store, generation)
}

/** The facade implementation: orchestrates store, engine and card building on background dispatchers (ARCH-130). */
internal class DictionaryImpl(private val store: DictionaryStore, override val generation: Long) : Dictionary {
    override val mode: DictMode = store.mode
    private val cantonese = mode == DictMode.CANTONESE
    private val cpu = Dispatchers.Default
    private val segmenter = Segmenter(store)
    private val engine = SearchEngine(store, segmenter)
    private val audio = AudioPlanner(store)
    private val cards = CardBuilder(store, audio)
    private val decomposer = Decomposer(store, cards)
    private val parts = PartsIndex(store, cards, decomposer, cantonese)
    private var closed = false

    // Small bounded memos, valid for this generation only.
    private val memoLock = Mutex()
    private val passageMemo = LinkedHashMap<String, AnalysedPassage>()
    private val relatedMemo = LinkedHashMap<String, IntArray>()
    private var memoCompanion = 0

    override val companionGeneration: kotlinx.coroutines.flow.StateFlow<Int> get() = store.lmGeneration

    /** Passages analysed before the language model arrived are analysed again with it (ARCH-143). */
    private fun dropStaleMemos() {
        val now = store.lmGeneration.value
        if (now != memoCompanion) {
            memoCompanion = now
            passageMemo.clear()
        }
    }

    private suspend fun <T> guard(block: suspend () -> T): T {
        if (closed) throw DictException(DictError.DatasetUnavailable(mode.datasetName, "closed"))
        return try {
            withContext(cpu) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DictException) {
            throw e
        } catch (e: Exception) {
            throw DictException(DictError.IoFailed(e.message ?: e.toString()), e)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        store.close()
    }

    // ------------------------------------------------------------------ metadata

    override suspend fun info(): DatasetInfo = guard {
        val m = store.meta
        val sources = try {
            (Codec.json.parseToJsonElement(m["sources"] ?: "[]") as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                fun s(vararg keys: String) = keys.firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.content } ?: ""
                SourceInfo(s("name"), s("version", "commit"), s("date"), s("licence", "license"), s("url"), s("role"))
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        DatasetInfo(
            mode = mode, schemaVersion = m["schema_version"]?.toIntOrNull() ?: 1, datasetId = m["dataset_id"] ?: mode.datasetName,
            datasetVersion = m["dataset_version"] ?: "", builtAt = m["built_at"] ?: "", language = m["language"] ?: "",
            romanization = m["romanization"] ?: "", toneCount = store.toneCount, entryCount = m["entry_count"]?.toIntOrNull() ?: 0,
            maxHeadwordLen = store.maxHeadwordLen, maxWordLen = store.maxWordLen, hasFrequencyData = store.hasFrequency,
            hasHsk = store.metaBool("has_hsk", true), hasBigramModel = store.hasBigram && store.hasLm, hasLanguageModel = store.hasLm,
            hasSentences = store.sentencesInstalled, hasDecomposition = store.decompositionInstalled, sources = sources, raw = m,
        )
    }

    override suspend fun labels(): Map<String, Label> = guard { store.labels() }
    override suspend fun label(key: String): Label = guard { store.labels()[key] ?: Label.missing(key) }

    // ------------------------------------------------------------------ search

    private class Base(
        val c: Classified, val script: Script, val options: SearchOptions, val result: SearchResult, val wholeIds: IntArray, val needsCombos: Boolean,
        /** Every word of the query is a common English word (SCHEMA note 19). */
        val englishWords: Boolean = false,
        /** Mixed query: exact-length rows at the head of the PATTERN section, after which combinations go (SRCH-069). */
        val patternExact: Int = 0,
    )

    private fun refOf(c: Candidate) = RowRef(c.proj.id, if (c.alt) c.altNote else -1, if (c.alt && c.altNote < 0) c.altKey else null, c.mandarinRow, if (c.mandarinRow) c.sense else -1)

    private suspend fun searchBase(rawQuery: String, script: Script, options: SearchOptions): Base {
        val query = SettingsCodec.normaliseLineBreaks(rawQuery)
        val c = engine.classify(query)
        if (c.kind == QueryKind.EMPTY) return Base(c, script, options, SearchResult.empty(query, script, generation), IntArray(0), false)
        val (zh, en) = coroutineScope {
            val a = async { engine.chinese(c, script, generation, options.resultCap) }
            val b = async { engine.english(c, script, options.resultCap) }
            a.await() to b.await()
        }
        // One batched hydration for every first page of both sides (SRCH-152).
        val wanted = LinkedHashSet<RowRef>()
        for (g in zh.groups) g.ids.forEach { wanted.add(RowRef(it)) }
        val pages = HashMap<SectionOut, List<RowRef>>()
        for (s in zh.sections + en.sections) {
            val refs = s.rows.map(::refOf)
            pages[s] = refs
            refs.take(options.pageSize).forEach { wanted.add(it) }
        }
        val list = wanted.toList()
        val built = cards.build(list, script)
        val byRef = HashMap<RowRef, EntryCard>()
        run {
            var i = 0
            for (ref in list) if (i < built.size && built[i].id == ref.entryId) byRef[ref] = built[i++]
        }
        fun section(s: SectionOut): SearchSection {
            val refs = pages[s]!!
            return SearchSection(s.kind, s.key, s.labelArg, refs.size, refs.take(options.pageSize).mapNotNull { byRef[it] }, refs)
        }
        val zhSections = ArrayList<SearchSection>()
        if (zh.groups.isNotEmpty()) {
            val groups = zh.groups.map { g ->
                val cs = g.ids.mapNotNull { byRef[RowRef(it)] }
                // An English-side group is not one headword: its cards keep their own inside-words buttons.
                WordGroup(
                    g.text, g.start, g.length, g.inert || cs.isEmpty(), cs, cs.firstOrNull()?.id?.takeIf { !g.english },
                    if (g.english) 0 else cs.firstOrNull()?.insideCount ?: 0, english = g.english && cs.isNotEmpty(), punctuation = g.punctuation, foreignWord = g.foreign,
                )
            }
            zhSections.add(SearchSection(SectionKind.GROUPS, "g", null, groups.sumOf { it.cards.size }, groups = groups))
        }
        zh.sections.mapTo(zhSections, ::section)
        val enSections = en.sections.map(::section)
        val chinese = SideResult(SearchSide.CHINESE, zhSections.sumOf { it.count }, zhSections, zh.isSentence, zh.passage, zh.capped)
        val english = SideResult(SearchSide.ENGLISH, enSections.sumOf { it.count }, enSections, capped = en.capped)
        // SRCH-024: Hanzi or an explicit tone forces Chinese; so does pronunciation with no English
        // match, and pronunciation whose English matches do not make it English (see pronunciationOnly).
        val words = if (c.kind == QueryKind.ROMANISATION) c.key.split(' ').filter { it.isNotEmpty() } else emptyList()
        val known = if (words.isEmpty()) emptyMap() else store.englishWordCounts(words.toSet())
        val allEnglish = words.isNotEmpty() && words.all { it in known }
        val forced = when {
            c.kind == QueryKind.HANZI || c.kind == QueryKind.MIXED || c.hasTone -> SearchSide.CHINESE
            c.kind == QueryKind.ROMANISATION && (english.count == 0 || pronunciationOnly(words, known, zh)) -> SearchSide.CHINESE
            else -> null
        }
        // A side without any result never wins over one that has some: the query opens on the side that
        // answers it, as a forced (not remembered, SRCH-026) choice like the other automatic switches.
        val side = when {
            forced != SearchSide.ENGLISH && chinese.isEmpty && !english.isEmpty -> SearchSide.ENGLISH
            forced == null && english.isEmpty && !chinese.isEmpty -> SearchSide.CHINESE
            else -> forced
        }
        val result = SearchResult(query, c.key, c.display, c.kind, script, side, chinese, english, false, generation)
        val needs = options.combinations && store.lmReady() &&
            ((c.kind == QueryKind.ROMANISATION && c.roman?.paths?.any { it.tokens.size >= 2 } == true) || engine.mixedCombinable(c))
        val whole = if (c.kind == QueryKind.MIXED) {
            // SRCH-069: the exact group and the exact-length pattern matches.
            val pattern = zh.sections.firstOrNull { it.kind == SectionKind.PATTERN }?.rows.orEmpty().take(zh.patternExact)
            val exactGroup = if (zh.isSentence) emptyList() else zh.groups.flatMap { it.ids }
            (exactGroup + pattern.map { it.proj.id }).distinct().toIntArray()
        } else zh.sections.firstOrNull { it.kind == SectionKind.MATCHES }?.rows?.let { rows -> IntArray(rows.size) { rows[it].proj.id } } ?: IntArray(0)
        return Base(c, script, options, result, whole, needs, allEnglish, zh.patternExact)
    }

    /**
     * SRCH-024 / SRCH-027 for romanised input that also has English-side matches. Those matches are
     * often incidental — a proper noun (`Dian Hong tea`), an abbreviation (`CO-NH`), the start of a
     * longer token (`ren` in `rent`) — and must not keep `nh`, `dian` or `ren` on the English page
     * when the Chinese side answers the query as a whole. The input is pronunciation only when
     *  1. the Chinese side has a whole-query match (a dictionary entry in the MATCHES section), and
     *  2. it does not read as English:
     *     a. its words are not all English words (the dataset's `english_word` table — common word
     *        forms, plus words the definitions themselves use: `hen`, `fan`, `long`, `can`, `sang`
     *        are, so they keep the remembered side), and
     *     b. it is not likely the start of one: the common English words beginning with its last
     *        word (the earlier ones being English words) are, by corpus frequency, at least
     *        [PRONUNCIATION_RATIO] times rarer than the best whole-query Chinese match is by its
     *        language-model unigram — `ca` is far more often the start of `can` or `call` than 擦,
     *        so typing `cat` never flips the page, while 人 outweighs every `ren…` word.
     * A dataset without the table keeps the plain rule (no English match).
     */
    private suspend fun pronunciationOnly(words: List<String>, known: Map<String, Long>, zh: ChineseOut): Boolean {
        if (!store.hasEnglishWords || words.isEmpty()) return false
        val whole = zh.sections.firstOrNull { it.kind == SectionKind.MATCHES }?.rows.orEmpty()
            .filter { !it.proj.isUnihan && it.cls <= com.szdict.core.engine.MatchClass.EXACT_TONELESS }.take(8)
        if (whole.isEmpty()) return false
        if (words.all { it in known }) return false
        if (words.dropLast(1).any { it !in known }) return true
        val english = store.englishPrefixShare(words.last())
        if (english <= 0.0) return true
        val lm = store.lmEntries(whole.map { it.proj.id })
        var best = Double.NEGATIVE_INFINITY
        for (m in whole) {
            // Without the language-model file: a Zipf estimate from the frequency rank.
            val unigram = lm[m.proj.id]?.second ?: if (m.proj.freqRank < com.szdict.core.store.UNRANKED) kotlin.math.ln(0.1 / m.proj.freqRank) else -17.0
            if (unigram - m.cost > best) best = unigram - m.cost
        }
        return kotlin.math.exp(best) >= PRONUNCIATION_RATIO * english
    }

    /** The engine's combinations of [query] (grouped rows and the flat kept list), for evaluation. */
    internal suspend fun combinationsOf(query: String, script: Script): CombosOut {
        val base = searchBase(query, script, SearchOptions())
        return if (base.needsCombos) combos(base) else CombosOut.EMPTY
    }

    private suspend fun combos(base: Base): CombosOut =
        if (base.c.kind == QueryKind.MIXED) engine.mixedCombinations(base.c, base.script, base.wholeIds)
        else engine.combinations(base.c.roman!!, base.script, base.wholeIds, base.englishWords)

    private suspend fun withCombinations(base: Base): SearchResult {
        // SRCH-066: a decoder failure omits the group silently.
        val combos: List<ComboOut> = try {
            combos(base).rows
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (combos.isEmpty()) return base.result
        val refs = combos.flatMap { c -> c.slots.flatMap { s -> s.map { RowRef(it) } } }.distinct()
        val byId = cards.build(refs, base.script, merge = false).associateBy { it.id }
        val list = combos.mapNotNull { c ->
            // An alternative whose card is missing is left out; a slot without any drops the row.
            val slots = c.slots.map { s -> s.toList().mapNotNull { byId[it] } }
            if (slots.any { it.isEmpty() }) null else Combination(slots.map(::CombinationSlot), c.score)
        }
        if (list.isEmpty()) return base.result
        val zh = base.result.chinese
        val sections = ArrayList(zh.sections)
        var hidden = 0
        if (base.c.kind == QueryKind.MIXED) {
            // SRCH-069: the combinations read every foreign word as romanisation, so their groups are left out.
            val g = sections.indexOfFirst { it.kind == SectionKind.GROUPS }
            if (g >= 0 && sections[g].groups.any { it.foreignWord }) {
                val kept = sections[g].groups.filter { !it.foreignWord }
                hidden = sections[g].count - kept.sumOf { it.cards.size }
                if (kept.isEmpty()) sections.removeAt(g) else sections[g] = sections[g].copy(count = kept.sumOf { it.cards.size }, groups = kept)
            }
        }
        val combinations = SearchSection(SectionKind.COMBINATIONS, "k", null, list.size, combinations = list)
        if (base.c.kind == QueryKind.MIXED) {
            // SRCH-069: after the groups and the exact-length pattern matches, before the longer ones,
            // which continue as `More matches`.
            val p = sections.indexOfFirst { it.kind == SectionKind.PATTERN }
            when {
                p < 0 -> sections.add(sections.indexOfFirst { it.kind == SectionKind.GROUPS } + 1, combinations)
                base.patternExact <= 0 -> sections.add(p, combinations)
                base.patternExact >= sections[p].refs.size -> sections.add(p + 1, combinations)
                else -> {
                    val s = sections[p]
                    val k = base.patternExact
                    val head = s.copy(count = k, cards = s.cards.take(k), refs = s.refs.take(k))
                    val tail = SearchSection(SectionKind.MORE_MATCHES, "xm", null, s.refs.size - k, s.cards.drop(k), s.refs.drop(k))
                    sections[p] = head
                    sections.add(p + 1, combinations)
                    sections.add(p + 2, tail)
                }
            }
        } else {
            val at = sections.indexOfFirst { it.kind == SectionKind.MATCHES }.let { if (it < 0) 0 else it + 1 }
            sections.add(at, combinations)
        }
        return base.result.copy(chinese = zh.copy(count = zh.count - hidden, sections = sections))
    }

    override suspend fun search(query: String, script: Script, options: SearchOptions): SearchResult = guard {
        val base = searchBase(query, script, options)
        if (base.needsCombos) withCombinations(base) else base.result
    }

    override fun searchProgressive(query: String, script: Script, options: SearchOptions): Flow<SearchResult> = flow {
        val base = guard { searchBase(query, script, options) }
        if (!base.needsCombos) {
            emit(base.result)
        } else {
            emit(base.result.copy(combinationsPending = true))
            emit(guard { withCombinations(base) })
        }
    }

    override suspend fun cards(refs: List<RowRef>, script: Script): List<EntryCard> = guard { cards.build(refs, script) }

    override suspend fun entryCards(ids: List<Int>, script: Script): List<EntryCard> = guard { cards.build(ids.map { RowRef(it) }, script) }

    override suspend fun popular(script: Script): List<EntryCard> = guard { cards.build(store.popular().map { RowRef(it) }, script) }

    override suspend fun wordOfTheDay(epochDay: Long, script: Script, levelMask: Int): EntryCard? = guard {
        val id = store.dailyEntryId(levelMask) { count -> DailyPick.index(epochDay, levelMask, count) }
        id?.let { cards.build(listOf(RowRef(it)), script, merge = false).firstOrNull() }
    }

    // ------------------------------------------------------------------ entry detail

    private suspend fun entryOrThrow(id: Int): EntryRow = store.entry(id) ?: throw DictException(DictError.RecordMissing("entry $id"))

    override suspend fun idForKey(naturalKey: String): Int? = guard { store.idForNaturalKey(naturalKey) }

    override suspend fun entryDetailByKey(naturalKey: String, script: Script): EntryDetail = guard {
        val id = store.idForNaturalKey(naturalKey) ?: throw DictException(DictError.RecordMissing("key $naturalKey"))
        detail(id, script)
    }

    override suspend fun entryDetail(id: Int, script: Script): EntryDetail = guard { detail(id, script) }

    override suspend fun entryOverview(id: Int, script: Script): EntryDetail = guard {
        detail(id, script, includeSupplementary = false)
    }

    private fun blockKind(s: String): BlockKind = when (s) {
        "cantonese" -> BlockKind.CANTONESE
        "cantonese_adapted" -> BlockKind.CANTONESE_ADAPTED
        "hsk_patch" -> BlockKind.HSK_PATCH
        "unihan" -> BlockKind.UNIHAN
        else -> BlockKind.MANDARIN
    }

    private suspend fun detail(id: Int, script: Script, includeSupplementary: Boolean = true): EntryDetail = coroutineScope {
        val e = entryOrThrow(id)
        val labels = store.labels()
        val pos = store.posLabels()
        val card = cards.build(listOf(RowRef(id)), script).firstOrNull() ?: throw DictException(DictError.RecordMissing("entry $id"))
        val relatedJob = if (includeSupplementary) {
            async { RelatedKind.entries.map { relatedPage(e, it, script, 0, 25) }.filter { it.total > 0 } }
        } else null
        val examplesJob = if (includeSupplementary) async { exampleIds(e).size } else null

        val entries = HashMap(store.entries(card.memberIds))
        val targets = entries.values.mapNotNull { m -> m.body.redirect?.takeIf { it.resolved }?.target }.filter { it !in entries }
        if (targets.isNotEmpty()) entries.putAll(store.entries(targets))
        val group = store.mergeGroups(listOf(e.mergeId(script)).filter { it >= 0 })[e.mergeId(script)]
        val members = (group?.members?.asList()?.mapNotNull { entries[it] }?.takeIf { it.size > 1 && it.any { m -> m.id == id } }) ?: listOf(e)
        val merged = members.size > 1
        val memberIds = members.mapTo(HashSet()) { it.id }

        var number = 0
        val seenByOthers = HashSet<String>() // SRCH-082: identical definitions are de-duplicated across members only
        val altShown = e.body.alt.flatMapTo(HashSet()) { listOf(it.d, it.c) }
        val sections = ArrayList<MemberSection>()
        for (m in members) {
            val view = memberView(m, memberIds, entries)
            val src = view.source
            val body: Body = src.body
            val own = ArrayList<String>()
            fun sense(s: SenseJ): Sense? {
                val text = s.textIn(merged)
                if (text.isEmpty() || text in seenByOthers) return null
                own.add(text)
                val segs = if (text == s.t) s.seg.map { it.toSegment(script) } else listOf(com.szdict.core.api.Segment.Text(text))
                return Sense(++number, text, s.tags.map { labels[it] ?: Label.missing(it) }, segs.ifEmpty { listOf(com.szdict.core.api.Segment.Text(text)) })
            }
            val blocks = ArrayList<SourceBlock>()
            if (!view.suppressed) {
                val raw = body.blocks.ifEmpty {
                    if (body.senses.isEmpty()) emptyList() else listOf(com.szdict.core.store.BlockJ("mandarin", "block_mandarin", 0, body.senses.size))
                }
                val hasCanto = raw.any { it.kind == "cantonese" || it.kind == "cantonese_adapted" }
                for (b in raw) {
                    val kind = blockKind(b.kind)
                    val range = body.senses.drop(b.first).take(b.count)
                    val mapped = HashMap<SenseJ, Sense>()
                    val senses = range.mapNotNull { s -> sense(s)?.also { mapped[s] = it } }
                    if (senses.isEmpty()) continue
                    val groups = b.readings.map { r ->
                        val first = if (r.first >= b.first && r.first + r.count <= b.first + b.count) r.first else b.first + r.first
                        ReadingGroup(readingFromCanonical(r.d.ifEmpty { Romanisation.pinyinToMarked(r.c) }, r.c), body.senses.drop(first).take(r.count).mapNotNull { mapped[it] })
                    }
                    blocks.add(
                        SourceBlock(
                            kind, labels[b.label.ifEmpty { "block_${b.kind}" }] ?: Label.missing(b.label.ifEmpty { "block_${b.kind}" }),
                            showLabel = cantonese || kind == BlockKind.HSK_PATCH || kind == BlockKind.UNIHAN,
                            collapsedByDefault = kind == BlockKind.MANDARIN && (b.collapsed || hasCanto), collapsible = kind == BlockKind.MANDARIN && hasCanto,
                            senses = senses, readingGroups = groups,
                        ),
                    )
                }
            }
            val redirect = if (view.showRedirect) m.body.redirect?.let { r ->
                Redirect(
                    when (r.kind) { "old_variant", "old_variant_of" -> RedirectKind.OLD_VARIANT; "erhua", "erhua_variant_of" -> RedirectKind.ERHUA; else -> RedirectKind.VARIANT },
                    r.text, r.seg.map { it.toSegment(script) }.ifEmpty { listOf(com.szdict.core.api.Segment.Text(r.text)) }, r.target, r.resolved && r.target != null && entries[r.target] != null,
                )
            } else null
            // ENT-057: notes already shown as header alternative readings are not repeated.
            val notes = m.body.notes.filter { n -> !((n.kind == "also_pr" || n.kind == "taiwan_pr") && m.id == e.id && n.reading != null && n.reading in altShown) }
                .map { n -> Note(n.kind, labels[n.label.ifEmpty { n.kind }] ?: Label.missing(n.kind), n.refs.map { it.toRef(script) }, n.reading, n.extra) }
            seenByOthers.addAll(own)
            if (blocks.isEmpty() && redirect == null && notes.isEmpty() && merged) continue
            sections.add(MemberSection(m.id, if (merged) m.alternate(script) else null, redirect, blocks, notes))
        }
        if (sections.isEmpty()) sections.add(MemberSection(e.id, null, null, emptyList(), emptyList()))

        val badges = (e.hsk.sortedBy { it.x } + e.hskVia).groupBy { it.l }.entries.sortedBy { it.key }.map { (level, rows) ->
            val tokens = rows.flatMap { it.pos }.flatMap { it.split('、') }.map { it.trim().trim('（', '）', '(', ')') }.filter { it.isNotEmpty() }.distinct()
            HskBadge(level, tokens, tokens.map { pos[it] ?: Label.missing(it) })
        }
        val chars = card.headword.cps().filter { isHanzi(it) }.distinct().map { cpToString(it) }
        EntryDetail(mode, script, card, cards.altReadings(e, labels), badges, sections, merged, relatedJob?.await().orEmpty(), chars, examplesJob?.await() ?: 0)
    }

    // ------------------------------------------------------------------ related / inside words

    private suspend fun relatedIds(e: EntryRow, kind: RelatedKind, script: Script): IntArray {
        val key = "${e.id}/${kind.ordinal}/${script.ordinal}"
        memoLock.withLock { relatedMemo[key] }?.let { return it }
        val forms = listOf(e.simp, e.trad).distinct()
        val projs: List<Proj> = when (kind) {
            RelatedKind.WITHIN -> emptyList()
            RelatedKind.STARTING_WITH -> engine.startsWith(forms.flatMap { listOf(it, Segmenter.lookupKey(it)) }.distinct(), script)
            RelatedKind.CONTAINING -> mergeContains(forms, script)
            RelatedKind.CONTAINING_REVERSED -> {
                val rev = forms.map { f -> f.cps().reversedArray().cpString() }.filter { it !in forms }.distinct()
                if (rev.isEmpty()) emptyList() else mergeContainsAnywhere(rev, script)
            }
        }
        val ids = IntArray(projs.size) { projs[it].id }
        memoLock.withLock {
            relatedMemo[key] = ids
            if (relatedMemo.size > 24) relatedMemo.remove(relatedMemo.keys.first())
        }
        return ids
    }

    private suspend fun mergeContains(forms: List<String>, script: Script): List<Proj> {
        if (forms.size == 1) return engine.contains(forms[0], script)
        val all = LinkedHashMap<Int, Proj>()
        for (f in forms) engine.contains(f, script).forEach { all[it.id] = it }
        return Ranking.dedupeMerged(all.values.sortedWith(Ranking.projComparator(cantonese)), script) { it }
    }

    /** Headwords containing the reversed word at any position (ENT-060 item 4): exact, prefix and inner occurrences. */
    private suspend fun mergeContainsAnywhere(words: List<String>, script: Script): List<Proj> {
        val all = LinkedHashMap<Int, Proj>()
        for (w in words) {
            store.headwordExact(listOf(w))[w]?.forEach { all[it.proj.id] = it.proj }
            engine.startsWith(listOf(w), script).forEach { all[it.id] = it }
            engine.contains(w, script).forEach { all[it.id] = it }
        }
        return Ranking.dedupeMerged(all.values.sortedWith(Ranking.projComparator(cantonese)), script) { it }
    }

    private suspend fun relatedPage(e: EntryRow, kind: RelatedKind, script: Script, offset: Int, limit: Int): RelatedPage {
        val word = e.headword(script)
        if (kind == RelatedKind.WITHIN) {
            // ENT-062: de-duplicated by entry id after merge grouping.
            val rows = inside(e, script).distinctBy { it.card.id }
            return RelatedPage(kind, word, rows.size, offset, rows.drop(offset).take(limit).map { it.card })
        }
        val ids = relatedIds(e, kind, script)
        val page = ids.drop(offset).take(limit)
        return RelatedPage(kind, word, ids.size, offset, cards.build(page.map { RowRef(it) }, script))
    }

    override suspend fun related(id: Int, kind: RelatedKind, script: Script, offset: Int, limit: Int): RelatedPage =
        guard { relatedPage(entryOrThrow(id), kind, script, maxOf(0, offset), limit) }

    /** Inside-words rows from flat (start, length, entry id) triples, with promotion and merging (SRCH-104/105). */
    private suspend fun insideRows(rows: IntArray, parent: EntryRow?, script: Script): List<InsideWord> {
        if (rows.isEmpty()) return emptyList()
        val ids = LinkedHashSet<Int>()
        for (i in 2 until rows.size step 3) ids.add(rows[i])
        val entries = store.entries(ids)
        class Row(val start: Int, val len: Int, val e: EntryRow)
        val list = ArrayList<Row>()
        var i = 0
        while (i + 2 < rows.size) {
            entries[rows[i + 2]]?.let { list.add(Row(rows[i], rows[i + 1], it)) }
            i += 3
        }
        // Parent-reading promotion: the candidate matching the parent's reading slice leads its span group.
        val parentSyl = parent?.readingCanonical?.split(' ')?.filter { it.isNotEmpty() }?.takeIf { it.size == parent.charCount }
        val ordered = ArrayList<Row>(list.size)
        var a = 0
        while (a < list.size) {
            var b = a
            while (b < list.size && list[b].start == list[a].start && list[b].len == list[a].len) b++
            val span = list.subList(a, b)
            if (parentSyl != null && span.size > 1) {
                val slice = parentSyl.subList(span[0].start, minOf(parentSyl.size, span[0].start + span[0].len)).joinToString(" ")
                val hit = span.firstOrNull { it.e.readingCanonical == slice && it.e.isProper == parent.isProper }
                    ?: span.firstOrNull { it.e.readingCanonical == slice }
                    ?: span.firstOrNull { it.e.readingCanonical.equals(slice, ignoreCase = true) }
                if (hit != null) ordered.add(hit)
                span.filterTo(ordered) { it !== hit }
            } else ordered.addAll(span)
            a = b
        }
        val seen = HashSet<String>()
        val kept = ordered.filter { r ->
            val g = r.e.mergeId(script)
            seen.add("${r.start}/${r.len}/" + if (g >= 0) "g$g" else "e${r.e.id}")
        }
        val built = cards.build(kept.map { RowRef(it.e.id) }.distinct(), script, exactInside = false).associateBy { it.id }
        return kept.mapNotNull { r -> built[r.e.id]?.let { InsideWord(r.start, r.len, it.copy(insideCount = 0)) } }
    }

    private suspend fun inside(e: EntryRow, script: Script): List<InsideWord> =
        insideRows(cards.withinRows(listOf(e))[e.id] ?: IntArray(0), e, script)

    override suspend fun insideWords(id: Int, script: Script): List<InsideWord> = guard { inside(entryOrThrow(id), script) }

    // ------------------------------------------------------------------ examples, references, audio, decomposition

    private suspend fun exampleIds(e: EntryRow): IntArray {
        if (!store.hasSentences()) return IntArray(0)
        val ids = store.sentenceIds(if (cantonese) e.trad else e.simp)
        return if (ids.size > 20) ids.copyOf(20) else ids
    }

    override suspend fun examples(id: Int, script: Script, offset: Int, limit: Int): ExamplePage = guard {
        val e = entryOrThrow(id)
        val ids = exampleIds(e)
        val page = ids.drop(maxOf(0, offset)).take(limit)
        val rows = store.sentences(page.toIntArray())
        // zh is simplified in the Mandarin pack and traditional in the Cantonese pack; zh_alt is the other script.
        val primarySimplified = !cantonese
        val useZh = (script == Script.SIMPLIFIED) == primarySimplified
        val word = e.headword(script)
        ExamplePage(ids.size, maxOf(0, offset), page.mapNotNull { sid ->
            val r = rows[sid] ?: return@mapNotNull null
            val text = if (useZh) r.zh else r.zhAlt
            val ranges = ArrayList<TextRange>()
            var at = text.indexOf(word)
            while (at >= 0 && word.isNotEmpty()) {
                ranges.add(TextRange(at, at + word.length))
                at = text.indexOf(word, at + word.length)
            }
            Example(sid, text, r.en, ranges)
        })
    }

    override suspend fun resolveReference(traditional: String, simplified: String, readingCanonical: String?, script: Script): ReferenceCandidates = guard {
        val form = if (script == Script.SIMPLIFIED) simplified else traditional
        val keys = listOf(traditional, simplified).flatMap { listOf(it, Segmenter.lookupKey(it)) }.filter { it.isNotEmpty() }.distinct()
        val hits = store.headwordExact(keys)
        val entries = store.entries(hits.values.flatten().map { it.proj.id }.toSet())
        fun norm(s: String) = s.lowercase().replace("u:", "ü").replace('v', 'ü').split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        val want = readingCanonical?.let(::norm)
        fun tier(e: EntryRow): Int {
            val both = e.trad == traditional && e.simp == simplified
            val reading = want != null && norm(e.readingCanonical) == want
            return when {
                both && reading -> 1
                both -> 2
                reading -> 3
                else -> 4
            }
        }
        val order = Ranking.projComparator(cantonese)
        val sorted = entries.values.sortedWith { a, b -> tier(a).compareTo(tier(b)).takeIf { it != 0 } ?: order.compare(a.proj, b.proj) }
        val matchCards = cards.build(Ranking.dedupeMerged(sorted, script) { it.proj }.map { RowRef(it.id) }, script)
        val text = if (simplified.isNotEmpty()) simplified else traditional
        val insideRows = if (text.cps().size >= 2) insideRows(cards.withinOnDemand(text), null, script) else emptyList()
        ReferenceCandidates(form, matchCards, matchCards.firstOrNull()?.id, insideRows)
    }

    override suspend fun audioPlan(id: Int): AudioPlan = guard { audio.plan(entryOrThrow(id), if (cantonese) Script.TRADITIONAL else Script.SIMPLIFIED) }

    override suspend fun decompose(characters: List<String>, script: Script, headwordEntryId: Int?): List<CharacterDecomposition> = guard {
        decomposer.decompose(characters, script, headwordEntryId?.let { store.entry(it) })
    }

    override suspend fun characterParts(characters: List<String>, script: Script, headwordEntryId: Int?) = guard { parts.parts(characters, script, headwordEntryId) }

    override suspend fun appearsIn(character: String, script: Script, offset: Int, limit: Int) = guard { parts.appearsIn(character, script, offset, limit) }

    // ------------------------------------------------------------------ text analysis

    override suspend fun analyse(text: String, segmentation: SegmentationMode): AnalysedPassage = guard {
        val key = "${segmentation.ordinal}:$text"
        memoLock.withLock { dropStaleMemos(); passageMemo[key] } ?: segmenter.analyse(text, segmentation, generation).passage.also { p ->
            if (text.length <= 4000) memoLock.withLock {
                passageMemo[key] = p
                if (passageMemo.size > 8) passageMemo.remove(passageMemo.keys.first())
            }
        }
    }

    override suspend fun spansAt(passage: AnalysedPassage, offset: Int, script: Script): SpanCandidates = guard {
        val text = passage.text
        if (offset !in text.indices) return@guard SpanCandidates.none()
        val at = passage.normaliseOffset(offset)
        val cp = text.codePointAtIndex(at)
        val ch = cpToString(cp)
        // TXT-075: an analysis from another generation or mode answers empty, never throws.
        if (passage.generation != generation || passage.mode != mode) return@guard SpanCandidates.none(ch, at)
        val prev = passage.previousCovered(at)
        val next = passage.nextCovered(at)
        if (!Segmenter.inRun(cp, passage.segmentation)) return@guard SpanCandidates(SpanKind.NONE, ch, at, emptyList(), prev, next)

        // Code points of the run around the tap, bounded by the segmentation window (TXT-020).
        val maxLen = store.maxWordLen
        val starts = ArrayList<Int>() // UTF-16 offsets of code points, from the window start
        var s = at
        var back = 0
        while (s > 0 && back < maxLen - 1) {
            val p = if (s >= 2 && text[s - 1].isLowSurrogate() && text[s - 2].isHighSurrogate()) s - 2 else s - 1
            if (!Segmenter.inRun(text.codePointAtIndex(p), passage.segmentation)) break
            s = p
            back++
        }
        var e = s
        var count = 0
        while (e < text.length && count < back + maxLen) {
            val c = text.codePointAtIndex(e)
            if (!Segmenter.inRun(c, passage.segmentation)) break
            starts.add(e)
            e += cpLen(c)
            count++
        }
        starts.add(e)
        val tapIndex = starts.indexOf(at)
        class Span(val start: Int, val end: Int, val key: String)
        val spans = ArrayList<Span>()
        // TXT-018: in FULL mode no span starts or ends strictly inside a foreign word.
        val window = text.substring(starts.first(), starts.last()).cps()
        val inner = if (passage.segmentation == SegmentationMode.FULL && TextClass.hasWordChar(window)) {
            // Classified over the window plus one code point on each side, so the edges are judged in context.
            val before = if (starts.first() > 0) text.substring(0, starts.first()).cps().lastOrNull() else null
            val after = if (starts.last() < text.length) text.codePointAtIndex(starts.last()) else null
            val ctx = (listOfNotNull(before) + window.toList() + listOfNotNull(after)).toIntArray()
            val all = TextClass.innerBoundaries(ctx)
            val off = if (before != null) 1 else 0
            BooleanArray(window.size + 1) { all[it + off] }
        } else null
        for (i in 0..tapIndex) for (j in tapIndex + 1 until starts.size) {
            if (j - i > maxLen) break
            if (inner != null && (inner[i] || inner[j])) continue
            spans.add(Span(starts[i], starts[j], Segmenter.lookupKey(text.substring(starts[i], starts[j]))))
        }
        val hits = store.headwordExact(spans.map { it.key }.toSet())
        val def = passage.segmentAt(at)?.takeIf { it.matched }
        class Cand(val span: Span, val hit: HeadHit, val cps: Int) {
            val isDefault = def != null && def.start == span.start && def.end == span.end && def.entryId == hit.proj.id
        }
        val cands = ArrayList<Cand>()
        for (sp in spans) {
            val seenIds = HashSet<Int>()
            for (h in hits[sp.key].orEmpty()) if (seenIds.add(h.proj.id)) cands.add(Cand(sp, h, sp.key.cps().size))
        }
        if (cands.isEmpty()) {
            // TXT-022: entries in which the character appears.
            // char_idx is in plain frequency order: rank the whole list (SRCH-070, HSK-aware) before taking 50.
            val all = store.charPostings(listOf(ch))[ch] ?: IntArray(0)
            val ids = store.projections(all).values.sortedWith(Ranking.projComparator(cantonese)).take(50).map { it.id }
            val list = cards.build(ids.map { RowRef(it) }, script)
            return@guard if (list.isEmpty()) SpanCandidates(SpanKind.NONE, ch, at, emptyList(), prev, next)
            else SpanCandidates(SpanKind.APPEARS_IN, ch, at, list.map { SpanCandidate(at, ch.length, it, false) }, prev, next)
        }
        // TXT-021 ordering; Unicode-only entries after dictionary entries at the same span.
        cands.sortWith(
            compareBy<Cand>({ !it.isDefault }, { -(it.span.end - it.span.start) }, { it.hit.proj.isUnihan }, { cantonese && !it.hit.proj.hasCanto },
                { Ranking.effectiveRank(it.hit.proj) }, { it.hit.proj.freqRank }, { it.hit.proj.isProper }, { it.span.start }, { it.hit.proj.id }),
        )
        // TXT-024: merge variants keyed by (start, length, group).
        val seen = HashSet<String>()
        val kept = cands.filter { c ->
            val g = Ranking.mergeId(c.hit.proj, script)
            seen.add("${c.span.start}/${c.span.end}/" + if (g >= 0) "g$g" else "e${c.hit.proj.id}")
        }
        val built = cards.build(kept.map { RowRef(it.hit.proj.id) }.distinct(), script).associateBy { it.id }
        SpanCandidates(
            SpanKind.WORDS_AT, ch, at,
            kept.mapNotNull { c -> built[c.hit.proj.id]?.let { SpanCandidate(c.span.start, c.span.end - c.span.start, it, c.isDefault) } },
            prev, next,
        )
    }

    // ------------------------------------------------------------------ HSK

    override suspend fun hskLevels(): List<HskLevelInfo> = guard {
        store.hskCounts().entries.sortedBy { it.key }.map { (level, c) -> HskLevelInfo(level, c[0], c[1], c[2]) }.filter { it.wordCount > 0 || it.charCount > 0 }
    }

    override suspend fun hsk(levels: IntRange, script: Script): List<HskCard> = guard {
        val rows = store.hskRows(levels)
        // HSK-027: rows of one level linking to the same entry render as one card.
        val order = LinkedHashMap<Long, ArrayList<Int>>()
        val entryOf = HashMap<Long, Int>()
        for (r in rows) {
            val k = r.level.toLong() shl 32 or (r.entryId.toLong() and 0xFFFFFFFFL)
            order.getOrPut(k) { ArrayList(1) }.add(r.rowId)
            entryOf[k] = r.entryId
        }
        val out = ArrayList<HskCard>(order.size)
        val keys = order.keys.toList()
        // Hydrate in chunks so a full-syllabus request never builds one giant batch.
        for (chunk in keys.chunked(500)) {
            val built = cards.build(chunk.map { RowRef(entryOf[it]!!) }.distinct(), script, merge = false, exactInside = false).associateBy { it.id }
            for (k in chunk) {
                val card = built[entryOf[k]!!] ?: continue // ARCH-124: a missing record degrades that one row
                val rowIds = order[k]!!
                val level = (k ushr 32).toInt()
                val key = (rowIds[0].toLong() shl 3) or level.toLong()
                out.add(HskCard(key, level, rowIds, card))
            }
        }
        out
    }

    override suspend fun hskChars(levels: IntRange, script: Script): List<HskCharCard> = guard {
        val rows = store.hskCharRows(levels)
        val out = ArrayList<HskCharCard>(rows.size)
        for (chunk in rows.chunked(500)) {
            val built = cards.build(chunk.filter { it.entryId >= 0 }.map { RowRef(it.entryId) }.distinct(), script, merge = false, exactInside = false).associateBy { it.id }
            for (r in chunk) {
                val card = built[r.entryId] ?: continue
                out.add(HskCharCard(r.ch, r.level, r.writing, card.copy(hskLevels = emptyList())))
            }
        }
        out
    }

    // ------------------------------------------------------------------ handwriting / OCR helpers

    /** Best (lowest) frequency rank of any entry containing each character; absent = not in the dataset. */
    private suspend fun charRanks(texts: Collection<String>): Map<String, Int> {
        val chars = HashSet<String>()
        for (t in texts) for (cp in t.cps()) chars.add(cpToString(cp))
        if (chars.isEmpty()) return emptyMap()
        val postings = store.charPostings(chars, 1)
        val firstIds = postings.values.filter { it.isNotEmpty() }.map { it[0] }.toIntArray()
        val projs = store.projections(firstIds)
        val out = HashMap<String, Int>()
        for ((ch, ids) in postings) if (ids.isNotEmpty()) out[ch] = projs[ids[0]]?.freqRank ?: 1_000_000
        return out
    }

    private fun worstRank(text: String, ranks: Map<String, Int>, allowDigits: Boolean): Int? {
        // Digits have no dictionary frequency. Keep their recogniser order without a frequency penalty.
        if (allowDigits && text.singleOrNull()?.isDigit() == true) return 1
        var worst = 0
        val cps = text.cps()
        if (cps.isEmpty()) return null
        for (cp in cps) {
            if (!isHanzi(cp)) return null
            val r = ranks[cpToString(cp)] ?: return null
            if (r > worst) worst = r
        }
        return worst
    }

    override suspend fun filterHandwritingCandidates(raw: List<String>): List<String> = guard {
        // Without scores the recogniser's order is the confidence signal.
        rerank(raw.mapIndexed { i, t -> ScoredText(t, -i.toDouble()) }, allowDigits = true).map { it.text }
    }

    override suspend fun rankHandwritingCandidates(raw: List<ScoredText>): List<ScoredText> = guard { rerank(raw, allowDigits = true) }

    override suspend fun characterAlternatives(alternatives: List<ScoredText>): List<ScoredText> = guard { rerank(alternatives) }

    /**
     * HW-032: survivors keep the recogniser's order as the primary signal; dataset frequency pulls
     * common characters ahead of rare look-alikes; the recogniser's top survivor stays in the first three.
     */
    private suspend fun rerank(raw: List<ScoredText>, allowDigits: Boolean = false): List<ScoredText> {
        val ranks = charRanks(raw.map { it.text })
        val survivors = raw.distinctBy { it.text }.mapNotNull { c -> worstRank(c.text, ranks, allowDigits)?.let { c to it } }
        if (survivors.size <= 1) return survivors.map { it.first }
        val byScore = survivors.sortedByDescending { it.first.score }
        val scored = byScore.mapIndexed { i, (c, rank) -> Triple(c, i + FREQ_WEIGHT * log10(rank.coerceAtLeast(1).toDouble()), i) }
        val out = scored.sortedWith(compareBy({ it.second }, { it.third })).map { it.first }.toMutableList()
        val top = byScore[0].first
        val at = out.indexOf(top)
        if (at > 2) {
            out.removeAt(at)
            out.add(2, top)
        }
        return out
    }

    private companion object {
        const val FREQ_WEIGHT = 1.0
        const val PRONUNCIATION_RATIO = 10.0
    }
}
