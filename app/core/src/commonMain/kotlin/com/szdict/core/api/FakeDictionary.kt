package com.szdict.core.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A tiny hard-coded [Dictionary] (about 35 Mandarin entries) so UI code can run and be previewed
 * before any dataset exists. Behaviour is a rough approximation of the real engine: greedy
 * segmentation, exact/prefix/contains Hanzi search, toneless/initial Pinyin matching and naive
 * English token matching. It performs no I/O.
 */
class FakeDictionary(override val mode: DictMode = DictMode.MANDARIN) : Dictionary {
    override val generation: Long = -1

    private class E(val id: Int, val simp: String, val trad: String, val pinyin: String, val defs: List<String>, val hsk: List<Int>, val freq: Int) {
        val syllables: List<String>
        val tones: List<Int>
        init { Romanisation.parseCanonical(pinyin).let { syllables = it.first; tones = it.second } }
        val joined = syllables.joinToString("")
        val initials = syllables.joinToString("") { it.take(1) }
        val key = id.toString(16).padStart(16, '0')
    }

    private val entries: List<E> = run {
        var n = 0
        fun e(simp: String, trad: String, pinyin: String, defs: String, freq: Int, vararg hsk: Int) =
            E(++n, simp, trad, pinyin, defs.split("; "), hsk.toList(), freq)
        listOf(
            e("你好", "你好", "ni3 hao3", "hello; hi", 120, 1), e("你", "你", "ni3", "you (informal)", 3, 1),
            e("好", "好", "hao3", "good; well; proper", 15, 1), e("好", "好", "hao4", "to be fond of; to have a tendency to", 15, 3),
            e("我", "我", "wo3", "I; me; my", 2, 1), e("我们", "我們", "wo3 men5", "we; us; ourselves", 20, 1),
            e("研究", "研究", "yan2 jiu1", "research; a study; to research; to look into", 400, 4),
            e("研究生", "研究生", "yan2 jiu1 sheng1", "graduate student; postgraduate student", 3200, 4),
            e("生命", "生命", "sheng1 ming4", "life (as the characteristic of living beings)", 500, 3),
            e("科学", "科學", "ke1 xue2", "science; scientific knowledge; scientific", 700, 2),
            e("喜欢", "喜歡", "xi3 huan5", "to like; to be fond of", 90, 1), e("中国", "中國", "Zhong1 guo2", "China", 150, 1),
            e("学生", "學生", "xue2 sheng5", "student; schoolchild", 300, 1), e("学", "學", "xue2", "to learn; to study; learning; science", 250, 1),
            e("生", "生", "sheng1", "to be born; to give birth; life; raw; student", 280, 2),
            e("谢谢", "謝謝", "xie4 xie5", "to thank; thanks; thank you", 60, 1), e("再见", "再見", "zai4 jian4", "goodbye; see you again later", 350, 1),
            e("朋友", "朋友", "peng2 you5", "friend", 110, 1), e("老师", "老師", "lao3 shi1", "teacher", 380, 1),
            e("今天", "今天", "jin1 tian1", "today; at the present; now", 100, 1), e("明天", "明天", "ming2 tian1", "tomorrow", 180, 1),
            e("水", "水", "shui3", "water; river; liquid", 420, 1), e("吃", "吃", "chi1", "to eat; to consume", 200, 1),
            e("饭", "飯", "fan4", "cooked rice; meal", 800, 1), e("吃饭", "吃飯", "chi1 fan4", "to have a meal; to eat", 600, 1),
            e("银行", "銀行", "yin2 hang2", "bank", 900, 2), e("行", "行", "xing2", "to walk; to go; OK; capable", 130, 1),
            e("行", "行", "hang2", "row; line; profession; business firm", 130, 3),
            e("发", "發", "fa1", "to send out; to show (one's feeling); to issue; to develop", 210, 2),
            e("发", "髮", "fa4", "hair", 210, 4), e("命", "命", "ming4", "life; fate; order or command", 450, 5),
            e("科", "科", "ke1", "branch of study; administrative section; family (taxonomy)", 1500, 2),
            e("们", "們", "men5", "plural marker for pronouns and a few animate nouns", 1000, 1),
            e("T恤", "T恤", "T xu4", "T-shirt", 5000, 3), e("上海", "上海", "Shang4 hai3", "Shanghai", 330, 1),
            e("海上", "海上", "hai3 shang4", "maritime; at sea", 2100, 6),
        )
    }
    private val byId = entries.associateBy { it.id }
    private val rank = compareBy<E>({ it.freq }, { it.simp.length }, { it.pinyin.first().isUpperCase() }, { it.id })

    private fun hanziCount(s: String) = s.count { it in '㐀'..'鿿' }
    private fun head(e: E, script: Script) = if (script == Script.SIMPLIFIED) e.simp else e.trad
    private fun reading(e: E) = Reading(Romanisation.pinyinToMarked(e.pinyin), e.pinyin.lowercase(), e.syllables, e.tones)

    private fun card(e: E, script: Script): EntryCard {
        val head = head(e, script)
        val alt = if (script == Script.SIMPLIFIED) e.trad else e.simp
        val inside = if (hanziCount(head) >= 2) insideOf(e).size else 0
        val word = e.pinyin.lowercase().replace(' ', '_')
        return EntryCard(
            id = e.id, naturalKey = e.key, simplified = e.simp, traditional = e.trad, headword = head,
            alternateForms = if (alt != head) listOf(alt) else emptyList(), reading = reading(e),
            toneColourable = head.length == e.syllables.size, noReading = false, definitions = e.defs,
            redirectHeaders = emptyList(), notes = emptyList(), hskLevels = e.hsk, provenance = null,
            isProperNoun = e.pinyin.first().isUpperCase(), isUnihanOnly = false, hasCantoBlock = false,
            frequencyRank = e.freq, hanziCount = hanziCount(head), insideCount = inside, memberIds = listOf(e.id),
            matchedAlt = null, mandarinRow = null,
            audio = AudioPlan(listOf(AudioClip(AudioClipKind.WORD, word)), false, word, e.pinyin.lowercase().split(' '), false, head),
        )
    }

    private fun insideOf(e: E): List<Triple<Int, Int, E>> {
        val out = ArrayList<Triple<Int, Int, E>>()
        for (len in e.simp.length - 1 downTo 1) for (start in 0..e.simp.length - len) {
            val sub = e.simp.substring(start, start + len)
            entries.filter { it.simp == sub }.sortedWith(rank).forEach { out.add(Triple(start, len, it)) }
        }
        return out
    }

    private fun matchHead(text: String) = entries.filter { it.simp == text || it.trad == text }.sortedWith(rank)

    override suspend fun info() = DatasetInfo(
        mode, 1, "fake", "fake", "", "cmn", "pinyin", 5, entries.size, 3, 3, true, true, false, false, false, false,
        listOf(SourceInfo("Sample data", "0", "", "CC0", "", "hard-coded sample")), emptyMap(),
    )

    override suspend fun labels(): Map<String, Label> = emptyMap()
    override suspend fun label(key: String) = Label.missing(key)

    override suspend fun search(query: String, script: Script, options: SearchOptions): SearchResult {
        val q = query.trim()
        if (q.isEmpty()) return SearchResult.empty(query, script, generation)
        val hasHanzi = hanziCount(q) > 0
        val lower = q.lowercase().filter { it.isLetterOrDigit() || it == ' ' }
        val chinese: SideResult
        var forced: SearchSide? = null
        val kind: QueryKind
        if (hasHanzi) {
            kind = QueryKind.HANZI
            forced = SearchSide.CHINESE
            val exact = matchHead(q)
            val passage = analyse(q, SegmentationMode.FULL)
            val groups = if (exact.isNotEmpty()) {
                listOf(WordGroup(q, 0, q.length, false, exact.map { card(it, script) }, exact[0].id, insideOf(exact[0]).size))
            } else passage.segments.map { s ->
                val text = q.substring(s.start, s.end)
                val es = matchHead(text)
                WordGroup(text, s.start, s.length, es.isEmpty(), es.map { card(it, script) }, es.firstOrNull()?.id, es.firstOrNull()?.let { insideOf(it).size } ?: 0)
            }
            val starts = entries.filter { (it.simp.startsWith(q) || it.trad.startsWith(q)) && it.simp.length > q.length }.sortedWith(rank)
            val contains = entries.filter { it.simp.indexOf(q) > 0 || it.trad.indexOf(q) > 0 }.sortedWith(rank)
            val sections = listOf(SearchSection(SectionKind.GROUPS, "g", null, groups.sumOf { it.cards.size }, groups = groups)) +
                listOfNotNull(section(SectionKind.STARTS_WITH, "s", q, starts, script), section(SectionKind.CONTAINS, "c", q, contains, script))
            val sentence = groups.size > 1 || (groups.size == 1 && groups[0].inert)
            chinese = SideResult(SearchSide.CHINESE, sections.sumOf { it.count }, sections, sentence, if (sentence) passage else null)
        } else {
            val compact = lower.filter { it.isLetter() }
            val whole = entries.filter { it.joined == compact || (compact.length > 1 && it.initials == compact) }.sortedWith(rank)
            val longer = entries.filter { it !in whole && compact.isNotEmpty() && (it.joined.startsWith(compact) || it.initials.startsWith(compact)) }.sortedWith(rank)
            kind = if (whole.isNotEmpty() || longer.isNotEmpty()) QueryKind.ROMANISATION else QueryKind.LATIN
            val sections = listOfNotNull(section(SectionKind.MATCHES, "m", null, whole, script), section(SectionKind.MORE_MATCHES, "p", null, longer, script))
            chinese = SideResult(SearchSide.CHINESE, sections.sumOf { it.count }, sections)
        }
        val tokens = lower.split(' ').filter { it.isNotEmpty() }
        val en = if (hasHanzi || tokens.isEmpty()) emptyList() else entries.filter { e ->
            val words = e.defs.flatMap { d -> d.lowercase().split(Regex("[^a-z0-9]+")) }
            tokens.dropLast(1).all { it in words } && words.any { it.startsWith(tokens.last()) }
        }.sortedWith(rank)
        val english = SideResult(SearchSide.ENGLISH, en.size, listOfNotNull(section(SectionKind.ENGLISH, "e", null, en, script)))
        if (kind == QueryKind.ROMANISATION && en.isEmpty()) forced = SearchSide.CHINESE
        // A side without any result never wins over one that has some (as in the real engine).
        if (forced != SearchSide.ENGLISH && chinese.isEmpty && !english.isEmpty) forced = SearchSide.ENGLISH
        else if (forced == null && english.isEmpty && !chinese.isEmpty) forced = SearchSide.CHINESE
        return SearchResult(query, lower, q, kind, script, forced, chinese, english, false, generation)
    }

    private fun section(kind: SectionKind, key: String, arg: String?, es: List<E>, script: Script): SearchSection? =
        if (es.isEmpty()) null else SearchSection(kind, key, arg, es.size, es.map { card(it, script) }, es.map { RowRef(it.id) })

    override fun searchProgressive(query: String, script: Script, options: SearchOptions): Flow<SearchResult> =
        flow { emit(search(query, script, options)) }

    override suspend fun cards(refs: List<RowRef>, script: Script) = refs.mapNotNull { byId[it.entryId]?.let { e -> card(e, script) } }
    override suspend fun entryCards(ids: List<Int>, script: Script) = ids.mapNotNull { byId[it]?.let { e -> card(e, script) } }
    override suspend fun popular(script: Script) = entries.filter { it.simp.length > 1 }.sortedWith(rank).map { card(it, script) }

    override suspend fun wordOfTheDay(epochDay: Long, script: Script, levelMask: Int): EntryCard? {
        val c = entries.filter { e -> e.hsk.any { levelMask and (1 shl (it - 1)) != 0 } }
        val i = DailyPick.index(epochDay, levelMask, c.size)
        return if (i < 0) null else card(c[i], script)
    }

    private fun get(id: Int) = byId[id] ?: throw DictException(DictError.RecordMissing("entry $id"))

    override suspend fun entryDetail(id: Int, script: Script): EntryDetail {
        val e = get(id)
        val c = card(e, script)
        var n = 0
        val senses = e.defs.map { Sense(++n, it, emptyList(), listOf(Segment.Text(it))) }
        val block = SourceBlock(BlockKind.MANDARIN, Label.missing("block_mandarin"), false, false, false, senses, emptyList())
        val related = RelatedKind.entries.map { related(id, it, script) }.filter { it.total > 0 }
        val chars = c.headword.map { it.toString() }.filter { hanziCount(it) > 0 }.distinct()
        return EntryDetail(
            mode, script, c, emptyList(), e.hsk.map { HskBadge(it, emptyList(), emptyList()) },
            listOf(MemberSection(id, null, null, listOf(block), emptyList())), false, related, chars, 0,
        )
    }

    override suspend fun entryDetailByKey(naturalKey: String, script: Script) =
        entryDetail(idForKey(naturalKey) ?: throw DictException(DictError.RecordMissing("key $naturalKey")), script)

    override suspend fun idForKey(naturalKey: String) = entries.firstOrNull { it.key == naturalKey }?.id

    override suspend fun related(id: Int, kind: RelatedKind, script: Script, offset: Int, limit: Int): RelatedPage {
        val e = get(id)
        val w = e.simp
        val all = when (kind) {
            RelatedKind.WITHIN -> insideOf(e).map { it.third }.distinct()
            RelatedKind.STARTING_WITH -> entries.filter { it.simp.startsWith(w) && it.simp.length > w.length }.sortedWith(rank)
            RelatedKind.CONTAINING -> entries.filter { it.simp.indexOf(w) > 0 }.sortedWith(rank)
            RelatedKind.CONTAINING_REVERSED -> w.reversed().let { r -> if (r == w) emptyList() else entries.filter { r in it.simp }.sortedWith(rank) }
        }
        return RelatedPage(kind, head(e, script), all.size, offset, all.drop(offset).take(limit).map { card(it, script) })
    }

    override suspend fun insideWords(id: Int, script: Script) = insideOf(get(id)).map { InsideWord(it.first, it.second, card(it.third, script)) }
    override suspend fun examples(id: Int, script: Script, offset: Int, limit: Int) = ExamplePage(0, offset, emptyList())

    override suspend fun decompose(characters: List<String>, script: Script, headwordEntryId: Int?): List<CharacterDecomposition> =
        throw DictException(DictError.AssetMissing("packs/decomposition.db"))

    // PARTS tab: a few hand-written trees (你 = ⿰亻尔, 尔 = ⿱⺈小; 好 = ⿰女子; 们 = ⿰亻门) and their reverse index.
    private val fakeParts = mapOf("你" to ("⿰" to listOf("亻", "尔")), "尔" to ("⿱" to listOf("⺈", "小")), "好" to ("⿰" to listOf("女", "子")), "们" to ("⿰" to listOf("亻", "门")))
    private val fakeRadicals = mapOf("你" to "人", "好" to "女", "们" to "人", "尔" to "小")

    private fun fakeNode(glyph: String, path: List<Int>, script: Script): PartNode {
        val split = fakeParts[glyph]
        val hit = entries.filter { it.simp == glyph }.sortedWith(rank)
        val children = split?.second.orEmpty().mapIndexed { i, c -> fakeNode(c, path + i, script) }
        val node = DecompNode(
            path = path, kind = PartKind.CHARACTER, glyph = glyph, mapsTo = null, position = null, operator = split?.first,
            ids = split?.let { it.first + it.second.joinToString("") }.orEmpty(), isRoot = split == null, card = hit.firstOrNull()?.let { card(it, script) },
            entryIds = hit.map { it.id }, radicalName = null, svgPath = null, strokeCount = null,
            highlight = PartHighlight(null, Region(0f, 0f, 1f, 1f), null), children = children.map { it.node },
        )
        return PartNode(node, split == null && glyph in setOf("亻", "女", "子", "小", "门"), false, false, children)
    }

    override suspend fun characterParts(characters: List<String>, script: Script, headwordEntryId: Int?): List<CharacterParts> = characters.map { c ->
        if (c !in fakeParts) CharacterParts(c, false, null, null)
        else {
            val root = fakeNode(c, emptyList(), script)
            val radical = fakeRadicals[c]
            CharacterParts(c, true, radical, root.copy(children = root.children.map { it.copy(isParentRadical = it.node.glyph == radical || (it.node.glyph == "亻" && radical == "人")) }))
        }
    }

    override suspend fun appearsIn(character: String, script: Script, offset: Int, limit: Int): AppearsInPage {
        val all = fakeParts.filter { (c, split) -> character in split.second && entries.any { it.simp == c } }.keys
            .map { c -> entries.filter { it.simp == c }.sortedWith(rank).first() }.sortedWith(rank)
        return AppearsInPage(character, all.size, offset, all.drop(offset).take(limit).map { AppearsInItem(head(it, script), card(it, script)) })
    }

    override suspend fun resolveReference(traditional: String, simplified: String, readingCanonical: String?, script: Script): ReferenceCandidates {
        val m = entries.filter { it.simp == simplified || it.trad == traditional }.sortedWith(rank)
        return ReferenceCandidates(if (script == Script.SIMPLIFIED) simplified else traditional, m.map { card(it, script) }, m.firstOrNull()?.id, emptyList())
    }

    override suspend fun audioPlan(id: Int) = card(get(id), Script.SIMPLIFIED).audio

    override suspend fun analyse(text: String, segmentation: SegmentationMode): AnalysedPassage {
        val segs = ArrayList<PassageSegment>()
        val covered = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace() || (segmentation == SegmentationMode.HANZI && hanziCount(text[i].toString()) == 0)) { i++; continue }
            var hit: E? = null
            var len = minOf(3, text.length - i)
            while (len >= 1 && hit == null) { hit = matchHead(text.substring(i, i + len)).firstOrNull(); if (hit == null) len-- }
            if (hit != null) {
                segs.add(PassageSegment(i, len, true, hit.id, reading(hit), len == hit.syllables.size))
                for (k in i until i + len) covered.add(k)
                i += len
            } else {
                val last = segs.lastOrNull()
                if (last != null && !last.matched && last.end == i) segs[segs.size - 1] = last.copy(length = last.length + 1)
                else segs.add(PassageSegment(i, 1, false, -1, Reading.EMPTY, false))
                i++
            }
        }
        return AnalysedPassage(text, mode, segmentation, generation, segs, covered)
    }

    override suspend fun spansAt(passage: AnalysedPassage, offset: Int, script: Script): SpanCandidates {
        if (offset !in passage.text.indices) return SpanCandidates.none()
        val ch = passage.text[offset].toString()
        val def = passage.segmentAt(offset)
        val out = ArrayList<SpanCandidate>()
        for (len in 3 downTo 1) for (s in maxOf(0, offset - len + 1)..offset) {
            if (s + len > passage.text.length) continue
            matchHead(passage.text.substring(s, s + len)).forEach {
                out.add(SpanCandidate(s, len, card(it, script), def != null && def.matched && def.start == s && def.length == len && def.entryId == it.id))
            }
        }
        out.sortByDescending { it.isDefault }
        val kind = if (out.isEmpty()) SpanKind.NONE else SpanKind.WORDS_AT
        return SpanCandidates(kind, ch, offset, out, passage.previousCovered(offset), passage.nextCovered(offset))
    }

    override suspend fun hskLevels() = (1..7).map { l -> entries.count { l in it.hsk }.let { HskLevelInfo(l, it, it, it) } }.filter { it.wordCount > 0 }

    override suspend fun hsk(levels: IntRange, script: Script) = levels.flatMap { l ->
        entries.filter { l in it.hsk }.sortedBy { it.joined }.map { HskCard(l * 100000L + it.id, l, listOf(it.id), card(it, script)) }
    }

    override suspend fun hskChars(levels: IntRange, script: Script) = levels.flatMap { l ->
        entries.filter { l in it.hsk && it.simp.length == 1 }.distinctBy { it.simp }
            .map { HskCharCard(head(it, script), l, false, card(it, script).copy(hskLevels = emptyList())) }
    }

    private val chars: Set<Char> = entries.flatMap { (it.simp + it.trad).toList() }.toSet()
    override suspend fun filterHandwritingCandidates(raw: List<String>) = raw.filter { c -> c.isNotEmpty() && c.all { it in chars } }
    override suspend fun rankHandwritingCandidates(raw: List<ScoredText>) = raw.filter { c -> c.text.isNotEmpty() && c.text.all { it in chars } }
    override suspend fun characterAlternatives(alternatives: List<ScoredText>) = rankHandwritingCandidates(alternatives)
    override fun close() {}
}
