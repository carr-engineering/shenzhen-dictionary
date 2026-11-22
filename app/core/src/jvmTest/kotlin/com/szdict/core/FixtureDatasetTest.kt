package com.szdict.core

import com.szdict.core.api.DictError
import com.szdict.core.api.DictException
import com.szdict.core.api.DictMode
import com.szdict.core.api.Dictionary
import com.szdict.core.api.DictionaryFactory
import com.szdict.core.api.EntryCard
import com.szdict.core.api.QueryKind
import com.szdict.core.api.RelatedKind
import com.szdict.core.api.Script
import com.szdict.core.api.SearchResult
import com.szdict.core.api.SearchSide
import com.szdict.core.api.SectionKind
import com.szdict.core.api.SegmentationMode
import com.szdict.core.api.SpanKind
import com.szdict.core.api.UiLang
import com.szdict.core.api.dictResult
import com.szdict.core.api.DictResult
import com.szdict.core.api.DailyPick
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Engine contract tests against a fixture dataset written in the contract/SCHEMA.md format. */
class FixtureDatasetTest {
    private val s = Script.SIMPLIFIED

    private fun with(dir: File = standardDir, block: suspend (Dictionary) -> Unit) = runBlocking {
        val dict = DictionaryFactory.open(dir.absolutePath, DictMode.MANDARIN)
        try {
            block(dict)
        } finally {
            dict.close()
        }
    }

    private fun SearchResult.zh(kind: SectionKind) = chinese.sections.firstOrNull { it.kind == kind }
    private fun SearchResult.heads(kind: SectionKind): List<String> = zh(kind)?.cards.orEmpty().map { it.headword }
    private fun SearchResult.groups() = zh(SectionKind.GROUPS)?.groups.orEmpty()
    private fun SearchResult.combos(): List<List<String>> = zh(SectionKind.COMBINATIONS)?.combinations.orEmpty().map { c -> c.words.map { it.headword } }

    @Test
    fun openAndMetadata() = with { dict ->
        val info = dict.info()
        assertEquals("fixture+1", info.datasetVersion)
        assertEquals(5, info.toneCount)
        assertTrue(info.hasLanguageModel && info.hasBigramModel && !info.hasSentences && !info.hasDecomposition)
        assertEquals("Fixture", info.sources.single().name)
        assertEquals("Taiwan pronunciation", dict.label("taiwan_pr").text(UiLang.EN))
        assertEquals("台湾读音", dict.label("taiwan_pr").text(UiLang.ZH_HANS))
        assertEquals("nope", dict.label("nope").en)
        assertEquals(QueryKind.EMPTY, dict.search("  ！", s).kind)
    }

    @Test
    fun typedErrors() {
        val missing = assertFailsWith<DictException> { runBlocking { DictionaryFactory.open("/nonexistent/dir", DictMode.MANDARIN) } }
        assertTrue(missing.error is DictError.DatasetUnavailable)
        val wrong = Fixture(schemaVersion = 3).apply { entry("你", "你", "ni3", "you") }.build()
        val mismatch = assertFailsWith<DictException> { runBlocking { DictionaryFactory.open(wrong.absolutePath, DictMode.MANDARIN) } }
        assertEquals(3, (mismatch.error as DictError.SchemaMismatch).found)
        with { dict ->
            assertNull(dict.idForKey("0000000000000000"))
            val r = dictResult { dict.entryDetailByKey("0000000000000000", s) }
            assertTrue((r as DictResult.Err).error is DictError.RecordMissing)
            assertTrue((dictResult { dict.decompose(listOf("你"), s) } as DictResult.Err).error is DictError.AssetMissing)
            assertEquals(0, dict.examples(1, s).total)
            dict.close()
            assertTrue((dictResult { dict.search("你", s) } as DictResult.Err).error is DictError.DatasetUnavailable)
        }
    }

    @Test
    fun hanziExactGroup() = with { dict ->
        val r = dict.search("你好", s)
        assertEquals(QueryKind.HANZI, r.kind)
        assertEquals(SearchSide.CHINESE, r.forcedSide)
        val g = r.groups().single()
        val card = g.cards.single()
        assertEquals("你好", card.headword)
        assertEquals(listOf("hello", "hi"), card.definitions)
        assertEquals(listOf(1), card.hskLevels)
        assertEquals(listOf(3, 3), card.reading.tones)
        assertTrue(card.toneColourable && !r.chinese.isSentence)
        assertEquals(3, g.insideCount) // 你, 好 hǎo, 好 hào
        assertEquals(listOf("你", "好", "好"), dict.insideWords(g.insideOf!!, s).map { it.card.headword })
        assertEquals("ni3_hao3", card.audio.wordKey)
        // Every reading of a headword, common reading before the proper noun (SRCH-075).
        assertEquals(listOf(false, true), dict.search("王", s).groups().single().cards.map { it.isProperNoun })
        // Traditional input matches too (SRCH-031).
        assertEquals("我们", dict.search("我們", s).groups().single().cards.single().headword)
        assertEquals(listOf("我們"), dict.search("我们", Script.TRADITIONAL).groups().single().cards.map { it.headword })
    }

    @Test
    fun startsWithAndContains() = with { dict ->
        val r = dict.search("研究", s)
        assertEquals(listOf("研究生"), r.heads(SectionKind.STARTS_WITH))
        assertEquals("研究", r.zh(SectionKind.STARTS_WITH)!!.labelArg)
        val sheng = dict.search("生", s)
        assertEquals(listOf("学生", "生命", "研究生", "好学生").sorted(), (sheng.heads(SectionKind.CONTAINS) + sheng.heads(SectionKind.STARTS_WITH)).sorted())
        assertEquals(listOf("学生", "研究生", "好学生"), sheng.heads(SectionKind.CONTAINS)) // frequency order
        assertEquals(sheng.chinese.sections.sumOf { it.count }, sheng.chinese.count)
    }

    @Test
    fun pronunciationSearch() = with { dict ->
        for (q in listOf("nihao", "ni hao", "ni3hao3", "nǐ hǎo", "NI3 HAO3!", "nh", "nhao", "nih")) {
            assertEquals("你好", dict.search(q, s).heads(SectionKind.MATCHES).firstOrNull(), q)
        }
        assertEquals(SearchSide.CHINESE, dict.search("nh", s).forcedSide)      // no English match
        assertEquals(SearchSide.CHINESE, dict.search("ni3hao3", s).forcedSide) // explicit tone
        assertEquals(SearchSide.ENGLISH, dict.search("hi", s).forcedSide)      // English results only
        // Tone-only differences (SRCH-165).
        assertEquals(listOf(3), dict.search("hao3", s).zh(SectionKind.MATCHES)!!.cards.map { it.reading.tones[0] })
        assertEquals(setOf(3, 4), dict.search("hao", s).zh(SectionKind.MATCHES)!!.cards.map { it.reading.tones[0] }.toSet())
        assertEquals(listOf("好学生"), dict.search("hao3", s).heads(SectionKind.MORE_MATCHES))
        // Proper-noun tie (GLB-013).
        assertEquals(listOf("wang2", "Wang2".lowercase()), dict.search("wang2", s).zh(SectionKind.MATCHES)!!.cards.map { it.reading.canonical })
        assertEquals(listOf(false, true), dict.search("wang2", s).zh(SectionKind.MATCHES)!!.cards.map { it.isProperNoun })
        // A single token is a partial syllable: prefix matches only, ranked before limiting (SRCH-176).
        val x = dict.search("x", s)
        assertNull(x.zh(SectionKind.MATCHES))
        assertEquals(listOf("想", "喜欢", "学生", "想去"), x.heads(SectionKind.MORE_MATCHES))
        assertNull(x.zh(SectionKind.COMBINATIONS))
    }

    @Test
    fun literalInitialsAndLatinHeadwords() = with { dict ->
        for (q in listOf("Txu", "T xu", "t xu4")) assertEquals(listOf("T恤", "体恤"), dict.search(q, s).heads(SectionKind.MATCHES).sorted(), q)
        assertEquals("T恤", dict.search("t恤", s).groups().single().cards.single().headword)
        assertEquals("T恤", dict.search("Ｔ恤", s).groups().single().cards.single().headword)
        assertEquals(0, dict.search("T恤", s).groups().single().cards.single().insideCount) // one Hanzi: no inside-words button
    }

    @Test
    fun alternativeReadings() = with { dict ->
        val r = dict.search("lese", s)
        val cards = r.zh(SectionKind.MATCHES)!!.cards
        assertEquals(listOf("乐色", "垃圾"), cards.map { it.headword }) // main reading before alternative (SRCH-078)
        assertNull(cards[0].matchedAlt)
        val alt = assertNotNull(cards[1].matchedAlt)
        assertEquals("Taiwan pronunciation", alt.label.en)
        assertEquals("lè sè", alt.reading.display)
        assertEquals(listOf(4, 4), alt.reading.tones)
        assertEquals("lā jī", cards[1].reading.display)
        // Abbreviated and toned input reach it as well; other paths never show the line.
        assertNotNull(dict.search("ls", s).zh(SectionKind.MATCHES)!!.cards.first { it.headword == "垃圾" }.matchedAlt)
        assertNotNull(dict.search("le4 se4", s).zh(SectionKind.MATCHES)!!.cards.first { it.headword == "垃圾" }.matchedAlt)
        assertNull(dict.search("laji", s).zh(SectionKind.MATCHES)!!.cards.single().matchedAlt)
        assertNull(dict.search("垃圾", s).groups().single().cards.single().matchedAlt)
        // Re-hydrating from refs keeps the alternative-reading line.
        val again = dict.cards(r.zh(SectionKind.MATCHES)!!.refs, s)
        assertEquals("lè sè", again[1].matchedAlt?.reading?.display)
        // Entry detail: header alternative reading, and the note is not repeated in the notes row.
        val d = dict.entryDetail(cards[1].id, s)
        assertEquals(listOf("taiwan_pr"), d.altReadings.map { it.kind })
        assertEquals(listOf("classifier"), d.members.single().notes.map { it.kind })
        assertEquals("个", d.members.single().notes.single().refs.single().form)
    }

    @Test
    fun unicodeOnlyTier() = with { dict ->
        val q = dict.search("q", s)
        assertEquals(listOf("去", "㐀"), q.heads(SectionKind.MORE_MATCHES)) // after every dictionary entry
        val card = dict.search("㐀", s).groups().single().cards.single()
        assertTrue(card.isUnihanOnly)
        assertEquals("Unicode (Unihan)", card.provenance?.en)
        val en = dict.search("mound", s).english.sections.single().cards.single()
        assertEquals("㐀", en.headword)
        // Segmentation: a matched word with the lowest score (TXT-012).
        val p = dict.analyse("我㐀", SegmentationMode.HANZI)
        assertEquals(listOf(true, true), p.segments.map { it.matched })
        assertEquals(listOf(0, 1), p.coveredOffsets)
    }

    @Test
    fun combinationsUseTheBigramModel() = with { dict ->
        for (q in listOf("wxhn", "woxihuanni", "wo xi huan ni", "wxihuann")) {
            val r = dict.search(q, s)
            assertEquals(listOf("我", "喜欢", "你"), r.combos().firstOrNull(), q)
            assertTrue(r.combos().size <= 3)
            assertEquals(0, r.chinese.count, "combinations are never counted") // SRCH-063
        }
        // 我 想去 (bigram) beats 我 想 去.
        assertEquals(listOf("我", "想去"), dict.search("wxq", s).combos().first())
        // A sequence equal to a whole-query match is dropped: nh does not repeat 你好 as 你 + 好.
        val nh = dict.search("nh", s)
        assertTrue(nh.combos().none { it.joinToString("") == "你好" }, nh.combos().toString())
        // The Combinations section sits after whole-query matches and before longer ones (SRCH-064).
        val kinds = dict.search("hx", s).chinese.sections.map { it.kind }
        assertEquals(kinds.sortedBy { it.ordinal }, kinds)
        // Progressive delivery (SRCH-066): single-word results first, then the same response plus combinations.
        val emissions = dict.searchProgressive("wxhn", s).toList()
        assertEquals(listOf(true, false), emissions.map { it.combinationsPending })
        assertTrue(emissions[0].combos().isEmpty() && emissions[1].combos().isNotEmpty())
        assertEquals(1, dict.searchProgressive("你好", s).toList().size)
    }

    @Test
    fun withoutLanguageModelSingleWordResultsStillWork() = with(Fixture.standard().build(withLm = false)) { dict ->
        assertEquals("你好", dict.search("nihao", s).heads(SectionKind.MATCHES).first())
        assertTrue(dict.search("wxhn", s).combos().isEmpty())
        assertTrue(dict.search("nh", s).heads(SectionKind.MATCHES).isEmpty()) // abbreviated paths yield no candidates (SRCH-054)
        assertEquals(1, dict.searchProgressive("wxhn", s).toList().size)
    }

    @Test
    fun combinationModelWithTrigramRescoring() {
        // DATA-156 / SRCH-062: with bigram2 alone 我 想去 (-1.0) beats 我 想 去 (-0.125 - 3.0); the trigram
        // 我 想 去 (-0.125) replaces the bigram 想 → 去 in the rescoring and turns the order round.
        fun fixture(withTrigram: Boolean) = Fixture.standard().apply {
            bigram2("我", "想", -0.125)
            bigram2("我", "想去", -1.0)
            bigram2("想", "去", -3.0)
            bigram2("喜欢", "你", -0.5)
            if (withTrigram) trigram("我", "想", "去", -0.125) else trigram("我", "喜欢", "你", -0.5)
        }.build()
        with(fixture(withTrigram = false)) { dict ->
            assertEquals(listOf("我", "想去"), dict.search("wxq", s).combos().first())
        }
        with(fixture(withTrigram = true)) { dict ->
            val r = dict.search("wxq", s)
            assertEquals(listOf("我", "想", "去"), r.combos().first(), r.combos().toString())
            // The legacy segmentation model of the same file is untouched (SCHEMA note 20).
            assertEquals(listOf("我们", "研究", "生命", "科学"), dict.search("我们研究生命科学", s).groups().map { it.text })
        }
        // A companion without the combination tables (the standard fixture, a file built before DATA-156)
        // scores with `bigram`: 我 想去 (bigram -0.25) first, as before.
        with { dict -> assertEquals(listOf("我", "想去"), dict.search("wxq", s).combos().first()) }
    }

    @Test
    fun unigramOnlyCombinations() = with(Fixture.standard(hasBigram = false).build()) { dict ->
        assertTrue(dict.search("wxhn", s).combos().any { it == listOf("我", "喜欢", "你") })
    }

    @Test
    fun englishSearch() = with { dict ->
        val phrase = dict.search("graduate student", s)
        assertEquals(QueryKind.ROMANISATION.let { phrase.kind }, phrase.kind)
        // EN_PHRASE (研究生) before EN_SENSE (生: "student of graduate school") despite its lower frequency.
        assertEquals(listOf("研究生", "生"), phrase.english.sections.single().cards.map { it.headword })
        assertEquals(listOf("生", "研究生"), dict.search("student graduate", s).english.sections.single().cards.map { it.headword })
        // Last token matches by prefix; plural stem (SRCH-013, SRCH-045).
        assertTrue("研究生" in dict.search("graduate stud", s).english.sections.single().cards.map { it.headword })
        assertEquals(dict.search("students", s).english.count, dict.search("student", s).english.count)
        // Single token: exact hit (EN_PHRASE) before prefix-only hit (EN_SENSE).
        val go = dict.search("go", s).english.sections.single().cards.map { it.headword }
        assertEquals("去", go.first())
        assertTrue(dict.search("good students graduate", s).english.count == 0) // a non-final token without postings
        assertEquals(0, dict.search("你好", s).english.count)
        // EN_ENTRY: tokens spread across senses.
        assertEquals(listOf("生"), dict.search("born student", s).english.sections.single().cards.map { it.headword })
    }

    @Test
    fun sentenceSegmentationWithFrequencies() = with { dict ->
        val r = dict.search("我们研究生命科学", s)
        assertTrue(r.chinese.isSentence)
        assertEquals(listOf("我们", "研究", "生命", "科学"), r.groups().map { it.text })
        val p = assertNotNull(r.chinese.passage)
        assertEquals(listOf(0, 2, 4, 6), p.segments.map { it.start })
        assertEquals("yán jiū", p.segments[1].reading.display)
        assertTrue(p.segments.all { it.toneColourable })
        // Punctuation and unknown text become inert groups; adjacent unmatched runs merge (TXT-073).
        val mixed = dict.search("你好，世界！", s)
        assertEquals(listOf("你好" to false, "，" to true, "世界" to false, "！" to true), mixed.groups().map { it.text to it.inert })
        assertTrue(mixed.chinese.isSentence)
        val onlyPunct = dict.search("龘龘", s)
        assertEquals(listOf(true), onlyPunct.groups().map { it.inert })
        assertTrue(onlyPunct.chinese.isSentence)
        assertEquals(0, onlyPunct.chinese.count)
    }

    @Test
    fun sideSelectionForRomanisationWithEnglishMatches() {
        // SRCH-024 / SCHEMA note 19. Fixture: 很 hěn (very common), 好 hǎo, 你好; English senses hold
        // "hello; hi", "good; well", "ocean; sea", "to go; to leave" …
        fun fixture(words: Map<String, Int>) = Fixture.standard().apply {
            entry("人", "人", "ren2", "person; people; Ren (surname)", 10, 800000)
            entry("擦", "擦", "ca1", "to wipe; CA (abbr.)", 3900, 300)
            entry("肽键", "肽键", "tai4 jian4", "peptide bond CO-NH", 9000)
            entry("母雞", "母鸡", "mu3 ji1", "hen", 7000)
            entry("KV車", "KV车", "K V che1", "", 8000)
            englishWords.putAll(words)
        }.build()
        val english = mapOf("hen" to 3, "rent" to 20, "render" to 5, "can" to 3000, "call" to 900, "cat" to 100, "go" to 2000, "good" to 1500)
        with(fixture(english)) { dict ->
            // Abbreviated romanisation with a stray English token match (CO-NH): pronunciation only.
            val nh = dict.search("nh", s)
            assertTrue(nh.english.count > 0)
            assertEquals(SearchSide.CHINESE, nh.forcedSide)
            // A full syllable whose only English matches are a proper noun and the start of rarer words (rent, render).
            val ren = dict.search("ren", s)
            assertTrue(ren.english.count > 0)
            assertEquals(SearchSide.CHINESE, ren.forcedSide)
            // An English word that is also pinyin keeps the remembered side, however common the Chinese word.
            val hen = dict.search("hen", s)
            assertTrue(hen.english.count > 0)
            assertEquals("很", hen.heads(SectionKind.MATCHES).first())
            assertNull(hen.forcedSide, "hen")
            // The start of far more common English words than the Chinese match: typing `cat` never flips the page.
            val ca = dict.search("ca", s)
            assertEquals("擦", ca.heads(SectionKind.MATCHES).first())
            assertNull(ca.forcedSide, "ca: en=${ca.english.count}")
            // A side with no result never wins: English results only open the English page, and the
            // other way round, whatever side is remembered (forced, so not persisted).
            val goo = dict.search("goo", s)
            assertTrue(goo.chinese.isEmpty && goo.english.count > 0)
            assertEquals(SearchSide.ENGLISH, goo.forcedSide)
            assertEquals(SearchSide.ENGLISH, dict.search("graduate student", s).forcedSide)
            val kv = dict.search("kv", s)                                   // LATIN: a Latin headword, no English match
            assertEquals(QueryKind.LATIN, kv.kind)
            assertTrue(kv.english.isEmpty && kv.chinese.count > 0)
            assertEquals(SearchSide.CHINESE, kv.forcedSide)
            assertNull(dict.search("qqqq", s).let { r -> r.forcedSide.takeIf { r.kind == QueryKind.LATIN } }) // nothing anywhere: remembered side
            // Tones and Hanzi always force.
            assertEquals(SearchSide.CHINESE, dict.search("hen3", s).forcedSide)
        }
        // A dataset without the table keeps the plain rule: any English match keeps the remembered side.
        with(fixture(emptyMap())) { dict ->
            assertNull(dict.search("nh", s).forcedSide, "nh without the table")
            assertNull(dict.search("ren", s).forcedSide, "ren without the table")
        }
    }

    @Test
    fun primaryReadingRank() {
        // SCHEMA note 18: flags bits 4-7. 石 shí (rank 0) is listed before dàn (lower id) and stands for the bare character.
        val dir = Fixture.standard().apply {
            entry("石", "石", "dan4", "dry measure for grain", 800, 5000, flags = 2 or (1 shl 4))
            entry("石", "石", "shi2", "rock; stone", 800, 5000, flags = 2)
            entry("石", "石", "Shi2", "surname Shi", 800, 5000, flags = 2 or (2 shl 4))
        }.build()
        with(dir) { dict ->
            assertEquals(listOf("shi2", "dan4", "shi2"), dict.search("石", s).groups().single().cards.map { it.reading.canonical })
            assertEquals("shí", dict.analyse("石", SegmentationMode.HANZI).segments.single().reading.display)
        }
    }

    @Test
    fun unigramSegmentationWithoutLanguageModel() = with(Fixture.standard().build(withLm = false)) { dict ->
        // TXT-012 fallback: the unigram DAG over word_freq when the language-model file is absent.
        assertEquals(listOf("我们", "研究", "生命", "科学"), dict.search("我们研究生命科学", s).groups().map { it.text })
    }

    @Test
    fun combinationsArePlausibleOnly() = with { dict ->
        // SCHEMA note 22: a far less likely split of a whole-query word is not offered (nihao: 你好, not 你 + 号 …).
        assertEquals("你好", dict.search("nihao", s).heads(SectionKind.MATCHES).first())
        assertTrue(dict.search("nihao", s).combos().isEmpty(), dict.search("nihao", s).combos().toString())
    }

    @Test
    fun greedySegmentationWithoutFrequencies() = with(Fixture.standard(hasFrequency = false).build()) { dict ->
        assertEquals(listOf("我们", "研究生", "命", "科学"), dict.search("我们研究生命科学", s).groups().map { it.text })
    }

    @Test
    fun analysisAndSpanCandidates() = with { dict ->
        val text = "我们研究生命科学。Hello 𠀀你好"
        val p = dict.analyse(text, SegmentationMode.HANZI)
        assertEquals(listOf("我们", "研究", "生命", "科学", "𠀀", "你好"), p.segments.map { text.substring(it.start, it.end) })
        assertEquals(listOf(true, true, true, true, false, true), p.segments.map { it.matched })
        assertEquals(dict.generation, p.generation)
        assertEquals(p, dict.analyse(text, SegmentationMode.HANZI)) // equivalent on re-analysis (TXT-016)
        // Tap 究 (offset 3): default segment first, then longer spans, then frequency (TXT-021).
        val spans = dict.spansAt(p, 3, s)
        assertEquals(SpanKind.WORDS_AT, spans.kind)
        assertEquals("究", spans.character)
        assertEquals(listOf("研究", "研究生"), spans.candidates.map { it.card.headword })
        assertEquals(listOf(true, false), spans.candidates.map { it.isDefault })
        assertEquals(2 to 2, spans.candidates[0].start to spans.candidates[0].length)
        assertEquals(2, spans.previousCovered)
        assertEquals(4, spans.nextCovered)
        // 生 (offset 4) is covered by 研究生, 生命 and 生.
        assertEquals(listOf("生命", "研究生", "生"), dict.spansAt(p, 4, s).candidates.map { it.card.headword })
        // Punctuation is not part of a run (TXT-023); a supplementary-plane tap normalises to the code point start.
        assertEquals(SpanKind.NONE, dict.spansAt(p, 8, s).kind)
        val sup = text.indexOf("𠀀")
        assertEquals(sup, dict.spansAt(p, sup + 1, s).offset)
        assertEquals("𠀀", dict.spansAt(p, sup + 1, s).character)
        assertTrue(!p.isCovered(sup) && p.isCovered(sup + 2))
        assertEquals(7, p.previousCovered(sup + 2))
        assertNull(p.previousCovered(0))
        // "Appears in" fallback (TXT-022): 恤 has no entry of its own.
        val xu = dict.spansAt(dict.analyse("恤", SegmentationMode.HANZI), 0, s)
        assertEquals(SpanKind.APPEARS_IN, xu.kind)
        assertEquals(setOf("T恤", "体恤"), xu.candidates.map { it.card.headword }.toSet())
        // A passage from another data generation answers empty, never throws (TXT-075).
        assertEquals(SpanKind.NONE, dict.spansAt(p.copy(generation = -5), 3, s).kind)
        assertEquals(SpanKind.NONE, dict.spansAt(p, 999, s).kind)
    }

    @Test
    fun variantMerging() = with { dict ->
        // Simplified: 發/发 and 髮/发 share headword and reading → one card (SRCH-080).
        val card = dict.search("发", s).groups().single().cards.single()
        assertEquals(listOf("發", "髮"), card.alternateForms)
        assertEquals(listOf("to send out", "to issue", "to develop", "hair"), card.definitions) // de-duplicated, "(variant of 發)" stripped
        assertEquals(2, card.memberIds.size)
        assertEquals(1, dict.search("fa", s).zh(SectionKind.MATCHES)!!.count)
        // Traditional: two separate cards (SRCH-081).
        assertEquals(listOf("發", "髮"), dict.search("发", Script.TRADITIONAL).groups().single().cards.map { it.headword })
        assertEquals(2, dict.search("fa", Script.TRADITIONAL).zh(SectionKind.MATCHES)!!.count)
        // Entry detail agrees on membership; sense numbering is continuous across members (ENT-042/043).
        val d = dict.entryDetail(card.id, s)
        assertTrue(d.merged)
        assertEquals(listOf("發", "髮"), d.members.map { it.alternateForm })
        assertEquals(listOf(1, 2, 3, 4), d.members.flatMap { m -> m.blocks.flatMap { b -> b.senses.map { it.number } } })
        assertTrue(!dict.entryDetail(card.id, Script.TRADITIONAL).merged)
    }

    @Test
    fun traditionalFormsIncludeTheHeadwordOnlyAsPartOfAMixedList() {
        val dir = Fixture().apply {
            val face = entry("面", "面", "mian4", "face", mergeSimp = 1)
            val flour = entry("麵", "面", "mian4", "flour", mergeSimp = 1)
            val variant = entry("麪", "面", "mian4", "noodles", mergeSimp = 1)
            // Legacy data lists only forms that differ from the Simplified headword.
            mergeGroup(1, listOf(face, flour, variant), listOf("麵", "麪"))
            entry("山", "山", "shan1", "mountain")
        }.build()

        with(dir) { dict ->
            val merged = dict.search("面", s).groups().single().cards.single()
            assertEquals(3, merged.memberIds.size)
            assertEquals(listOf("面", "麵", "麪"), merged.alternateForms)
            assertEquals(merged.alternateForms, dict.entryDetail(merged.id, s).card.alternateForms)
            assertTrue(dict.search("山", s).groups().single().cards.single().alternateForms.isEmpty())
        }
    }

    @Test
    fun redirectsShowTheTargetSenses() = with { dict ->
        val t = Script.TRADITIONAL
        val card = dict.search("裏", t).groups().single().cards.single()
        assertEquals(listOf("variant of 里"), card.redirectHeaders)
        assertEquals(listOf("inside", "lining", "interior"), card.definitions)
        val d = dict.entryDetail(card.id, t)
        val m = d.members.single()
        assertTrue(m.redirect!!.resolved)
        assertEquals(listOf("inside", "lining", "interior"), m.blocks.single().senses.map { it.text })
        assertEquals(listOf("里"), card.alternateForms)
    }

    @Test
    fun mixedPatternSearch() = with { dict ->
        val r = dict.search("想q", s)
        assertEquals(QueryKind.MIXED, r.kind)
        assertEquals(listOf("想去"), r.heads(SectionKind.PATTERN))
        assertEquals(listOf("好学生"), dict.search("h学生", s).heads(SectionKind.PATTERN))
        assertEquals(listOf("好学生"), dict.search("hao3学sheng", s).heads(SectionKind.PATTERN))
        assertTrue(dict.search("hao4学sheng", s).heads(SectionKind.PATTERN).isEmpty())
        // Unparseable Latin: pattern skipped, falls back to the Hanzi (SRCH-162).
        val noise = dict.search("我uiu", s)
        assertNull(noise.zh(SectionKind.PATTERN))
        assertEquals("我", noise.groups().first().cards.first().headword)
    }

    /** A fixture with Latin-containing headwords and English-side words for sentence mode (SRCH-028/b). */
    private val foreignDir: File by lazy {
        Fixture.standard().apply {
            entry("用", "用", "yong4", "to use", 60, 300000)
            entry("和", "和", "he2", "and; with", 20, 600000)
            entry("穿", "穿", "chuan1", "to wear; to put on", 900, 20000)
            entry("看", "看", "kan4", "to look at; to see", 50, 300000)
            entry("表", "表", "biao3", "table; chart; list", 700, 30000)
            entry("上網", "上网", "shang4 wang3", "to go online", 1200, 9000)
            entry("IP", "IP", "I P", "Internet Protocol address", 3000, 500)
            entry("K", "K", "K", "thousand (abbr.)", 9000)
            entry("OK", "OK", "O K", "okay", 4000, 200)
            entry("卡拉", "卡拉", "ka3 la1", "Kara (name)", 8000)
            entry("卡拉OK", "卡拉OK", "ka3 la1 O K", "karaoke", 5000)
            entry("物料清單", "物料清单", "wu4 liao4 qing1 dan1", "bill of materials (BOM)", 7000)
            entry("炸彈", "炸弹", "zha4 dan4", "bomb", 3000)
            entry("蘋果手機", "苹果手机", "ping2 guo3 shou3 ji1", "iPhone", 6000)
            entry("無線網", "无线网", "wu2 xian4 wang3", "wireless network; Wi-Fi", 6500)
        }.build()
    }

    @Test
    fun sentenceModeForeignWords() = with(foreignDir) { dict ->
        suspend fun groups(q: String) = dict.search(q, s).groups().map { g ->
            g.text + when {
                g.punctuation -> "|p"
                g.inert -> "|-"
                g.english -> "|e:" + g.cards.joinToString(",") { it.headword }
                else -> ""
            }
        }
        // Every non-whitespace character in exactly one group; a foreign word is atomic (no iP + hone).
        assertEquals(listOf("我", "用", "iPhone|e:苹果手机", "和", "WiFi|-"), groups("我用iPhone和WiFi"))
        assertEquals(listOf("用", "Wi-Fi|e:无线网", "上网"), groups("用Wi-Fi上网"))
        // A headword covering the Latin part whole still matches (T恤, 卡拉OK); the word itself as a headword (IP).
        assertEquals(listOf("我", "穿", "T恤"), groups("我穿T恤"))
        assertEquals(listOf("我", "用", "ip"), groups("我用ip"))
        assertEquals(listOf("看", "卡拉OK"), groups("看卡拉OK"))
        // English match by whole word only: BOM finds the bill of materials, never `bomb`.
        assertEquals(listOf("我", "看", "BOM|e:物料清单", "表"), groups("我看BOM表"))
        val bom = dict.search("我看BOM表", s).groups()[2]
        assertTrue(!bom.inert && bom.english && bom.insideOf == null)
        // NO MATCH for a word with neither; digits and single letters skip the English side.
        assertEquals(listOf("我", "uiu|-"), groups("我uiu"))
        assertEquals(listOf("我", "3|-", "个|-"), groups("我3个"))
        assertEquals(listOf("我", "3.5|-"), groups("我3.5"))
        // Whitespace separates foreign words; joiners stay inside only between letters or digits.
        assertEquals(listOf("hello|e:你好", "world|e:世界", "你好"), groups("hello world 你好"))
        assertEquals(listOf("我", "don't|-"), groups("我don't"))
        assertEquals(listOf("我", "ok", "-|p"), groups("我ok-"))
        // Sub-spans never cut a foreign word: 卡拉OK has 卡拉 and OK inside, not K.
        val ktv = dict.search("卡拉OK", s).groups().single()
        assertEquals(listOf("卡拉OK"), ktv.cards.map { it.headword })
        assertEquals(listOf("卡拉", "OK"), dict.insideWords(ktv.insideOf!!, s).map { it.card.headword })
        // Passage taps in sentence mode offer no span inside a foreign word.
        val r = dict.search("我用ipx和", s)
        val spans = dict.spansAt(r.chinese.passage!!, 2, s)
        assertTrue(spans.candidates.none { it.card.headword == "IP" }, spans.candidates.map { it.card.headword }.toString())
    }

    @Test
    fun sentenceModePunctuation() = with(foreignDir) { dict ->
        suspend fun groups(q: String) = dict.search(q, s).groups().map { g -> g.text + if (g.punctuation) "|p" else if (g.inert) "|-" else "" }
        // Punctuation is its own group, never merged with a NO MATCH word or unmatched Hanzi.
        assertEquals(listOf("你好", "！|p", "BOM", "。|p"), groups("你好！BOM。"))
        assertEquals(listOf("hello", ",|p", "world", "你好"), groups("hello,world 你好"))
        assertEquals(listOf("你好", "！|p", "uiu|-", "。|p"), groups("你好！uiu。"))
        // Adjacent punctuation without whitespace is one card; whitespace separates.
        assertEquals(listOf("你好", "。」|p", "我"), groups("你好。」我"))
        assertEquals(listOf("你好", "。|p", "」|p", "我"), groups("你好。 」我"))
        // Unmatched Hanzi still merge (TXT-073), but not across punctuation or a foreign word.
        assertEquals(listOf("龘龘|-"), groups("龘龘"))
        assertEquals(listOf("龘|-", "，|p", "龘|-"), groups("龘，龘"))
        assertEquals(listOf("龘|-", "uiu|-", "龘|-"), groups("龘uiu龘"))
        assertTrue(dict.search("你好！", s).chinese.sections.first().groups.single().let { !it.inert && it.text == "你好！" }) // exact group as before
    }

    @Test
    fun mixedCombinations() = with { dict ->
        // SRCH-069: a typed Hanzi is a pinned item; the Latin runs give their token paths.
        val combos = dict.search("我xiang去", s).combos()
        assertTrue(listOf("我", "想去") in combos || listOf("我", "想", "去") in combos, combos.toString())
        assertTrue(combos.flatten().none { it.isEmpty() })
        val abbr = dict.search("我x去", s).combos()
        assertTrue(abbr.isNotEmpty() && abbr.all { it.first() == "我" && it.joinToString("") == "我想去" }, abbr.toString())
        assertEquals(dict.search("wo想qu", s).combos().map { it.joinToString("") }.toSet(), setOf("我想去"))
        // Uppercase Latin is a word, not pinyin: no combinations for the whole query.
        assertTrue(dict.search("我XIANG去", s).combos().isEmpty())
        assertTrue(dict.search("我Xiang去", s).combos().isEmpty())
        // The groups are unaffected by combinations; an exact-length pattern match is never repeated.
        assertEquals(listOf("想去"), dict.search("想q", s).heads(SectionKind.PATTERN))
        assertTrue(dict.search("想q", s).combos().none { it.joinToString("") == "想去" })
        // Combinations sit after the groups and the exact-length pattern matches.
        val kinds = dict.search("我xiang去", s).chinese.sections.map { it.kind }
        assertTrue(kinds.indexOf(SectionKind.COMBINATIONS) > kinds.indexOf(SectionKind.GROUPS), kinds.toString())
    }

    @Test
    fun entryDetailAndRelated() = with { dict ->
        val id = dict.search("喜欢", s).groups().single().cards.single().id
        val d = dict.entryDetail(id, s)
        assertEquals(listOf(1, 4), d.card.hskLevels)
        assertEquals(listOf(1 to "Verb", 4 to "Noun"), d.hskBadges.map { it.level to it.partOfSpeechText(UiLang.EN) }) // per level, never aggregated
        assertEquals(listOf("喜", "欢"), d.characters)
        assertEquals(d.id, dict.entryDetailByKey(d.naturalKey, Script.TRADITIONAL).id)
        assertEquals(d.id, dict.idForKey(d.naturalKey))
        assertEquals("Idiom", dict.entryDetail(dict.search("世界", s).groups()[0].cards[0].id, s).members[0].blocks[0].senses[0].tags.single().en)

        val yj = dict.entryDetail(dict.search("研究", s).groups()[0].cards[0].id, s)
        assertEquals(listOf(RelatedKind.STARTING_WITH), yj.related.map { it.kind })
        val sh = dict.search("上海", s).groups()[0].cards[0].id
        val rel = dict.entryDetail(sh, s).related.associate { it.kind to it.cards.map { c -> c.headword } }
        assertEquals(listOf("海"), rel[RelatedKind.WITHIN])
        assertEquals(listOf("海上"), rel[RelatedKind.CONTAINING_REVERSED])
        val sheng = dict.search("生", s).groups()[0].cards[0].id
        val page = dict.related(sheng, RelatedKind.CONTAINING, s, offset = 1, limit = 1)
        assertEquals(3, page.total)
        assertEquals(listOf("研究生"), page.cards.map { it.headword })
        // Reference resolution (ENT-054): tiered matches, then the words inside the referenced text.
        val ref = dict.resolveReference("研究生", "研究生", "yan2 jiu1 sheng1", s)
        assertEquals("研究生", ref.matches.single().headword)
        assertEquals(ref.matches.single().id, ref.defaultId)
        assertEquals(listOf("研究", "生"), ref.inside.map { it.card.headword })
        assertEquals(listOf("Wang2".lowercase(), "wang2"), dict.resolveReference("王", "王", "Wang2", s).matches.map { it.reading.canonical }.let { it })
    }

    @Test
    fun hskPopularAndDailyWord() = with { dict ->
        val levels = dict.hskLevels()
        assertEquals(listOf(1, 4), levels.map { it.level })
        assertEquals(5, levels[0].wordCount) // syllabus rows
        val cards = dict.hsk(1..1, s)
        assertEquals(4, cards.size)          // the two 喜欢 rows of level 1 merge into one card (HSK-027)
        assertEquals(levels[0].cardCount, cards.size)
        assertEquals(cards.size, cards.map { it.key }.toSet().size)
        assertEquals(2, cards.first { it.card.headword == "喜欢" }.rowIds.size)
        assertEquals(5, dict.hsk(1..7, s).size)
        assertTrue(dict.hskChars(1..7, s).all { it.card.hskLevels.isEmpty() })
        assertEquals(listOf("我们", "喜欢", "中国"), dict.popular(s).take(3).map { it.headword })
        // SET-063: de-duplicated candidates sorted by entry id.
        val candidates = dict.hsk(1..7, s).map { it.card.id }.distinct().sorted()
        for (day in listOf(-3L, 0L, 20736L)) assertEquals(candidates[DailyPick.index(day, 0x7F, candidates.size)], dict.wordOfTheDay(day, s)!!.id)
        assertEquals("研究生", dict.wordOfTheDay(5, s, levelMask = 1 shl 3)!!.headword)
        assertNull(dict.wordOfTheDay(5, s, levelMask = 1 shl 6))
    }

    @Test
    fun handwritingCandidates() = with { dict ->
        assertEquals(listOf("你", "好学", "㐀"), dict.filterHandwritingCandidates(listOf("你", "abc", "好学", "龘", "㐀", "你x", "")))
        assertEquals(listOf("7", "０"), dict.filterHandwritingCandidates(listOf("7", "０", "A", "7x")))
        val digit = com.szdict.core.api.ScoredText("7", 0.9)
        assertEquals(listOf(digit), dict.rankHandwritingCandidates(listOf(digit)))
        assertTrue(dict.characterAlternatives(listOf(digit)).isEmpty())
        val ranked = dict.rankHandwritingCandidates(listOf(com.szdict.core.api.ScoredText("㐀", 0.9), com.szdict.core.api.ScoredText("我", 0.8), com.szdict.core.api.ScoredText("龘", 0.7), com.szdict.core.api.ScoredText("命", 0.6)))
        assertEquals(listOf("我", "命", "㐀"), ranked.map { it.text }) // common characters first; the top survivor stays in the first three
        assertEquals(listOf("我"), dict.characterAlternatives(listOf(com.szdict.core.api.ScoredText("龘", 0.9), com.szdict.core.api.ScoredText("我", 0.2))).map { it.text })
    }

    companion object {
        private val standardDir: File by lazy { Fixture.standard().build() }
    }
}
