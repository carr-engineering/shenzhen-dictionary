package com.szdict.core

import com.szdict.core.api.DailyPick
import com.szdict.core.api.DictMode
import com.szdict.core.api.FakeDictionary
import com.szdict.core.api.InputMethod
import com.szdict.core.api.LatestOnly
import com.szdict.core.api.QueryKind
import com.szdict.core.api.Reading
import com.szdict.core.api.Romanisation
import com.szdict.core.api.Route
import com.szdict.core.api.Routes
import com.szdict.core.api.Script
import com.szdict.core.api.SearchSide
import com.szdict.core.api.SectionKind
import com.szdict.core.api.SegmentationMode
import com.szdict.core.api.Settings
import com.szdict.core.api.SettingsCodec
import com.szdict.core.api.SettingsKeys
import com.szdict.core.api.ThemeMode
import com.szdict.core.api.UiLang
import com.szdict.core.api.UiLanguageSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutesTest {
    @Test
    fun parseTable() {
        assertEquals(Route.Home(null), Routes.parse("/"))
        assertEquals(Route.Home(DictMode.MANDARIN), Routes.parse("/zh"))
        assertEquals(Route.Home(DictMode.CANTONESE), Routes.parse("/yue/"))
        assertEquals(Route.Search(DictMode.MANDARIN, "你好 nh", SearchSide.CHINESE), Routes.parse("/zh/search?q=%E4%BD%A0%E5%A5%BD+nh&side=zh"))
        assertEquals(Route.Search(DictMode.CANTONESE, "walk", null), Routes.parse("https://szdict.com/yue/search?q=walk&side=bogus#frag"))
        assertEquals(Route.Word(DictMode.MANDARIN, "528f8af3bfdae027"), Routes.parse("https://szdict.com/zh/word/528f8af3bfdae027"))
        assertEquals(Route.Hsk(DictMode.MANDARIN, null), Routes.parse("/zh/hsk"))
        assertEquals(Route.Hsk(DictMode.CANTONESE, 7), Routes.parse("/yue/hsk/7"))
        assertEquals(Route.Reading, Routes.parse("/reading"))
        assertEquals(Route.Ocr, Routes.parse("/ocr"))
        assertEquals(Route.Voice, Routes.parse("/voice"))
        assertEquals(Route.Settings, Routes.parse("/settings"))
        assertEquals(Route.Privacy, Routes.parse("https://szdict.com/privacy"))
    }

    @Test
    fun fallbacks() {
        // GLB-112: unknown under a valid mode → that mode's Search; anything else → "/".
        assertEquals(Route.Home(DictMode.MANDARIN), Routes.parse("/zh/unknown/thing"))
        assertEquals(Route.Home(DictMode.MANDARIN), Routes.parse("/zh/word/NOT-A-KEY"))
        assertEquals(Route.Home(DictMode.MANDARIN), Routes.parse("/zh/hsk/9"))
        assertEquals(Route.Home(DictMode.MANDARIN), Routes.parse("/zh/search"))
        assertEquals(Route.Home(DictMode.MANDARIN), Routes.parse("/zh/search?q=%20"))
        assertEquals(Route.Home(null), Routes.parse("/fr/word/528f8af3bfdae027"))
        assertEquals(Route.Home(null), Routes.parse("/reading/extra"))
        assertEquals(Route.Home(null), Routes.parse("/voice", voiceAvailable = false))
        assertEquals(Route.Home(null), Routes.parse(""))
        assertEquals("a b", (Routes.parse("/zh/search?q=a%0Ab") as Route.Search).query)
    }

    @Test
    fun buildRoundTrips() {
        val routes = listOf(
            Route.Home(null), Route.Home(DictMode.CANTONESE), Route.Search(DictMode.MANDARIN, "你好 & nǐ=hǎo?", SearchSide.ENGLISH),
            Route.Search(DictMode.CANTONESE, "nei5 hou2"), Route.Word(DictMode.CANTONESE, "0123456789abcdef"),
            Route.Hsk(DictMode.MANDARIN), Route.Hsk(DictMode.MANDARIN, 3), Route.Reading, Route.Ocr, Route.Voice, Route.Settings, Route.Privacy,
        )
        for (r in routes) assertEquals(r, Routes.parse(Routes.build(r)), Routes.build(r))
        assertEquals("/zh/search?q=%E4%BD%A0%E5%A5%BD&side=zh", Routes.build(Route.Search(DictMode.MANDARIN, "你好", SearchSide.CHINESE)))
        assertEquals("https://szdict.com/yue/word/0123456789abcdef", Routes.url(Route.Word(DictMode.CANTONESE, "0123456789abcdef")))
    }
}

class DailyPickTest {
    @Test
    fun specVectors() {
        assertEquals(0xE220A8397B1DCDAFuL, DailyPick.splitmix64(0uL))
        assertEquals(0x0000007F00005100uL, DailyPick.seed(20736, 0x7F))
        assertEquals(0x67C657EB0895804CuL, DailyPick.splitmix64(DailyPick.seed(20736, 0x7F)))
        assertEquals(0xFFFFFFFEFFFFFFFFuL, DailyPick.seed(-1, 0x01))
        assertEquals(0x19BA2418AFBBFAE1uL, DailyPick.splitmix64(DailyPick.seed(-1, 0x01)))
    }

    @Test
    fun indexIsStableAndBounded() {
        assertEquals(-1, DailyPick.index(1, 0x7F, 0))
        val n = 11000
        val a = DailyPick.index(20736, 0x7F, n)
        assertEquals(a, DailyPick.index(20736, 0x7F, n))
        assertEquals((0x67C657EB0895804CuL % n.toULong()).toInt(), a)
        assertTrue((-5L..5L).all { DailyPick.index(it, 0x7F, n) in 0 until n })
        // Changing the mask changes the sequence.
        assertTrue((0L..20L).any { DailyPick.index(it, 0x7F, n) != DailyPick.index(it, 0x01, n) })
    }
}

class SettingsTest {
    @Test
    fun defaults() {
        val s = SettingsCodec.decode(emptyMap())
        assertEquals(Settings(), s)
        assertEquals(DictMode.MANDARIN, s.dictionaryMode)
        assertEquals(Script.SIMPLIFIED, s.script)
        assertEquals(Script.TRADITIONAL, s.script(DictMode.CANTONESE))
        assertEquals(SearchSide.ENGLISH, s.searchSide)
        assertTrue(s.toneColors && s.showPronunciation && s.showBoundaries && !s.cameraAutoSave)
    }

    @Test
    fun roundTrip() {
        val s = Settings(
            DictMode.CANTONESE, Script.TRADITIONAL, Script.SIMPLIFIED, UiLanguageSetting.ZH_HANT, ThemeMode.DARK, false, false, false,
            InputMethod.HANDWRITING, SearchSide.CHINESE, "你好", "nei5 hou2", true,
        )
        assertEquals(s, SettingsCodec.decode(SettingsCodec.encode(s)))
        assertEquals(SettingsKeys.all.toSet(), SettingsCodec.encode(s).keys)
        assertEquals(mapOf(SettingsKeys.TONE_COLORS to "true"), SettingsCodec.diff(s, s.copy(toneColors = true)))
    }

    @Test
    fun corruptValuesFallBackPerKey() {
        val stored = mapOf(
            SettingsKeys.DICTIONARY_MODE to "klingon", SettingsKeys.script(DictMode.MANDARIN) to "traditional", SettingsKeys.TONE_COLORS to "maybe",
            SettingsKeys.SHOW_BOUNDARIES to "false", SettingsKeys.THEME_MODE to "", SettingsKeys.SEARCH_SIDE to null,
            SettingsKeys.lastSearchQuery(DictMode.MANDARIN) to "a\r\nb c", "unknown_key" to "x",
        )
        val s = SettingsCodec.decode(stored)
        assertEquals(DictMode.MANDARIN, s.dictionaryMode)   // corrupt → default
        assertEquals(Script.TRADITIONAL, s.scriptMandarin)  // valid neighbours survive
        assertTrue(s.toneColors)
        assertEquals(false, s.showBoundaries)
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
        assertEquals(SearchSide.ENGLISH, s.searchSide)
        assertEquals("a b c", s.lastQueryMandarin)
    }

    @Test
    fun perModeValues() {
        val s = Settings().withScript(DictMode.CANTONESE, Script.SIMPLIFIED).withLastQuery(DictMode.CANTONESE, "ngo5")
        assertEquals(Script.SIMPLIFIED, s.script(DictMode.CANTONESE))
        assertEquals(Script.SIMPLIFIED, s.script(DictMode.MANDARIN))
        assertEquals("", s.lastQuery(DictMode.MANDARIN))
        assertEquals("ngo5", s.copy(dictionaryMode = DictMode.CANTONESE).lastSearchQuery)
        assertEquals(UiLang.ZH_HANT, UiLanguageSetting.SYSTEM.resolve("zh-HK"))
        assertEquals(UiLang.ZH_HANS, UiLanguageSetting.SYSTEM.resolve("zh_CN"))
        assertEquals(UiLang.ZH_HANS, UiLanguageSetting.SYSTEM.resolve("zh-Hans-HK"))
        assertEquals(UiLang.EN, UiLanguageSetting.SYSTEM.resolve("ja-JP"))
        assertEquals(UiLang.EN, UiLanguageSetting.EN.resolve("zh-TW"))
    }
}

class RomanisationTest {
    @Test
    fun toneMarkPlacement() {
        val cases = mapOf(
            "hao3" to "hǎo", "mei2" to "méi", "xie3" to "xiě", "lüe4" to "lüè", "lu:e4" to "lüè", "dou1" to "dōu", "gui4" to "guì",
            "liu2" to "liú", "duo1" to "duō", "jiong3" to "jiǒng", "nü3" to "nǚ", "nv3" to "nǚ", "ma5" to "ma", "Ou1" to "Ōu",
            "m2" to "\u1E3F", "n3" to "\u0148", "ng2" to "\u0144g", "m4" to "m\u0300", "n1" to "n\u0304", "ng4" to "\u01F9g", "r5" to "r", "xx" to "xx",
        )
        for ((numbered, marked) in cases) assertEquals(marked, Romanisation.pinyinSyllableToMarked(numbered), numbered)
        assertEquals("nǐ hǎo", Romanisation.pinyinToMarked("ni3 hao3"))
        assertEquals("Zhōng guó", Romanisation.pinyinToMarked("Zhong1 guo2"))
        assertEquals("nǐhǎo", Romanisation.pinyinToMarked("ni3hao3"))
        assertEquals("nei5 hou2", Romanisation.display(DictMode.CANTONESE, "nei5 hou2"))
    }

    @Test
    fun toneColours() {
        assertEquals(listOf("ni", "hao") to listOf(3, 3), Romanisation.parseCanonical("ni3 hao3"))
        assertEquals(listOf("lü", "t") to listOf(4, 0), Romanisation.parseCanonical("lu:4 T"))
        assertEquals(2, Romanisation.toneColourIndex(3, DictMode.MANDARIN))
        assertEquals(-1, Romanisation.toneColourIndex(6, DictMode.MANDARIN))
        assertEquals(5, Romanisation.toneColourIndex(6, DictMode.CANTONESE))
        assertEquals(-1, Romanisation.toneColourIndex(0, DictMode.CANTONESE))
        val r = Reading("nǐ hǎo", "ni3 hao3", listOf("ni", "hao"), listOf(3, 3))
        assertEquals(listOf(2, 2), Romanisation.toneColourSlots("你好", r, DictMode.MANDARIN))
        assertNull(Romanisation.toneColourSlots("T恤衫", r, DictMode.MANDARIN))
        assertEquals(listOf(2, 2), Romanisation.toneColourSlots("𠀀好", r, DictMode.MANDARIN))
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LatestOnlyTest {
    @Test
    fun slowSupersededQueryNeverOverwritesNewerResult() = runTest {
        val applied = ArrayList<String>()
        val runner = LatestOnly<String, String>(backgroundScope, work = { q -> delay(if (q == "slow") 1000 else 10); "r:$q" }) { _, r -> applied.add(r.getOrThrow()) }
        runner.submit("slow")
        advanceTimeBy(100)
        runner.submit("fast")
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf("r:fast"), applied)
    }

    @Test
    fun tenKeystrokesAndBackspacesCompleteExactlyOneSearch() = runTest {
        var started = 0
        var completed = 0
        val applied = ArrayList<String>()
        val runner = LatestOnly<String, String>(backgroundScope, work = { q -> started++; delay(50); completed++; q }) { _, r -> applied.add(r.getOrThrow()) }
        val typed = "nihaoshiji"
        for (i in 1..typed.length) { runner.submit(typed.take(i)); advanceTimeBy(5) }
        for (i in typed.length - 1 downTo 1) { runner.submit(typed.take(i)); advanceTimeBy(5) }
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, completed)
        assertEquals(listOf("n"), applied)
        assertTrue(started >= 1)
    }

    @Test
    fun nonCooperativeOperationCannotClobberNewerState() = runTest {
        val applied = ArrayList<String>()
        // The first operation ignores cancellation and still returns a value.
        val runner = LatestOnly<String, String>(backgroundScope, work = { q ->
            if (q == "stubborn") withContext(NonCancellable) { delay(500) } else delay(10)
            q
        }) { _, r -> applied.add(r.getOrThrow()) }
        runner.submit("stubborn")
        advanceTimeBy(50)
        runner.submit("new")
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf("new"), applied)
    }

    @Test
    fun invalidateDropsInFlightAndErrorsAreDelivered() = runTest {
        val applied = ArrayList<String>()
        val runner = LatestOnly<String, String>(backgroundScope, work = { q -> delay(20); if (q == "boom") error("failed") else q }) { q, r ->
            applied.add(r.fold({ it }, { "error:$q" }))
        }
        val g = runner.submit("ni")
        advanceTimeBy(5)
        assertTrue(runner.invalidate() > g) // blank query: the empty state is applied by the caller at once
        advanceTimeBy(60_000); runCurrent()
        assertTrue(applied.isEmpty())
        runner.submit("boom")
        advanceTimeBy(60_000); runCurrent()
        runner.submit("ok")
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf("error:boom", "ok"), applied) // a failure leaves the runner usable (SRCH-172)
        runner.close()
    }

    @Test
    fun cancellationIsNotAnError() = runTest {
        val applied = ArrayList<Result<String>>()
        val runner = LatestOnly<String, String>(backgroundScope, work = { throw CancellationException("stop") }) { _, r -> applied.add(r) }
        runner.submit("x")
        advanceTimeBy(60_000); runCurrent()
        assertTrue(applied.isEmpty())
    }
}

class FakeDictionaryTest {
    private val dict = FakeDictionary()
    private val s = Script.SIMPLIFIED

    @Test
    fun sampleEntries() = runTest {
        val r = dict.search("你好", s)
        assertEquals(QueryKind.HANZI, r.kind)
        assertEquals("你好", r.chinese.sections.first().groups.single().cards.single().headword)
        assertEquals("研究生", dict.search("graduate student", s).english.sections.single().cards.first().headword)
        assertEquals("我們", dict.search("women", Script.TRADITIONAL).chinese.sections.first().cards.first().headword)
        val sentence = dict.search("我们研究生命科学", s)
        assertTrue(sentence.chinese.isSentence)
        assertEquals(SectionKind.GROUPS, sentence.chinese.sections.first().kind)
        val p = dict.analyse("我们 你好", SegmentationMode.HANZI)
        assertEquals(2, p.segments.size)
        assertTrue(dict.spansAt(p, 3, s).candidates.isNotEmpty())
        val d = dict.entryDetail(r.chinese.sections.first().groups[0].cards[0].id, s)
        assertEquals(listOf("你", "好"), d.characters)
        assertEquals(d.id, dict.entryDetailByKey(d.naturalKey, s).id)
        assertTrue(dict.popular(s).isNotEmpty() && dict.hsk(1..7, s).isNotEmpty())
        assertEquals(dict.wordOfTheDay(100, s)?.id, dict.wordOfTheDay(100, s)?.id)
    }
}
