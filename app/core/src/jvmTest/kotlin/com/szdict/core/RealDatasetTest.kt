package com.szdict.core

import com.szdict.core.api.DictMode
import com.szdict.core.api.Dictionary
import com.szdict.core.api.DictionaryFactory
import com.szdict.core.api.QueryKind
import com.szdict.core.api.RelatedKind
import com.szdict.core.api.Script
import com.szdict.core.api.SearchSide
import com.szdict.core.api.SectionKind
import com.szdict.core.api.SegmentationMode
import com.szdict.core.api.SpanKind
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Golden queries against the real Mandarin dataset (`szdict.datasetDir`, = pipeline/out). Skipped —
 * not failed — while the pipeline output is absent.
 */
class RealDatasetTest {
    private val dir: String? = System.getProperty("szdict.datasetDir")?.takeIf { File(it, "mandarin.db").isFile }

    private fun real(mode: DictMode = DictMode.MANDARIN, block: suspend (Dictionary) -> Unit) {
        val d = dir?.takeIf { File(it, "${mode.datasetName}.db").isFile } ?: return println("SKIPPED: no ${mode.datasetName}.db under szdict.datasetDir")
        runBlocking {
            val dict = DictionaryFactory.open(d, mode)
            try {
                block(dict)
            } finally {
                dict.close()
            }
        }
    }

    private val s = Script.SIMPLIFIED

    @Test
    fun hanziExact() = real { dict ->
        val r = dict.search("你好", s)
        assertEquals(QueryKind.HANZI, r.kind)
        assertEquals(SearchSide.CHINESE, r.forcedSide)
        val g = r.chinese.sections.first { it.kind == SectionKind.GROUPS }.groups
        assertEquals(1, g.size)
        assertEquals("你好", g[0].cards[0].headword)
        assertEquals("nǐ hǎo", g[0].cards[0].reading.display)
        assertTrue(g[0].cards[0].definitions.any { "hello" in it })
        assertTrue(1 in g[0].cards[0].hskLevels)
        assertTrue(g[0].insideCount >= 2, "inside words of 你好")
        val yj = dict.search("研究", s)
        val starts = yj.chinese.sections.first { it.kind == SectionKind.STARTS_WITH }
        assertTrue(dict.cards(starts.refs, s).any { it.headword == "研究生" })
        assertEquals("研究", starts.labelArg)
        assertTrue(yj.chinese.sections.any { it.kind == SectionKind.CONTAINS })
        assertEquals(yj.chinese.sections.sumOf { it.count }, yj.chinese.count)
    }

    @Test
    fun pinyinForms() = real { dict ->
        for (q in listOf("nihao", "ni3hao3", "ni hao", "nǐ hǎo", "nh", "nhao", "nih")) {
            val r = dict.search(q, s)
            assertEquals(QueryKind.ROMANISATION, r.kind, q)
            val first = r.chinese.sections.first { it.kind == SectionKind.MATCHES }.cards.first()
            assertEquals("你好", first.headword, "first match for $q: ${r.chinese.sections.first().cards.take(5).map { it.headword }}")
        }
        assertEquals(SearchSide.CHINESE, dict.search("wxhn", s).forcedSide)
        assertEquals(null, dict.search("hen", s).forcedSide)
        assertEquals(SearchSide.CHINESE, dict.search("ni3hao3", s).forcedSide)
    }

    @Test
    fun sideSelection() = real { dict ->
        // SRCH-024 / SRCH-027 / SCHEMA note 19: romanisation whose English matches are incidental
        // (CO-NH, Dian Hong tea, Xuexi Qiangguo, Ren / rent) lands on the Chinese page …
        for (q in listOf("nh", "nhao", "zg", "bj", "dian", "xuexi", "ren", "hao", "zhongguo", "pengyou")) {
            assertEquals(SearchSide.CHINESE, dict.search(q, s).forcedSide, q)
        }
        // … while English words that are also pinyin, and the start of common English words, keep the remembered side.
        for (q in listOf("long", "can", "men", "sang", "he", "ban", "pan", "fan", "sun", "song", "hen", "ca")) {
            assertEquals(null, dict.search(q, s).forcedSide, q)
        }
        // A side with no result never wins over the side that answers the query.
        for (q in listOf("love", "hello", "graduate student")) {
            val r = dict.search(q, s)
            assertTrue(r.chinese.isEmpty && r.english.count > 0, q)
            assertEquals(SearchSide.ENGLISH, r.forcedSide, q)
        }
        assertTrue(dict.search("nh", s).english.count > 0 && dict.search("ren", s).english.count > 0)
    }

    @Test
    fun segmentationRegression() = real { dict ->
        // TXT-012 with the bigram lattice (SCHEMA note 20). Expected segmentations and readings.
        val cases = listOf(
            "我们研究生命科学" to "我们/研究/生命/科学",
            "银行行长" to "银行/行长",
            "他在银行工作" to "他/在/银行/工作",
            "今天天气很好" to "今天/天气/很/好",
            "南京市长江大桥" to "南京市/长江/大桥",
            "结婚的和尚未结婚的" to "结婚/的/和/尚未/结婚/的",
            "乒乓球拍卖完了" to "乒乓球拍/卖完/了",
            "我喜欢你" to "我/喜欢/你",
            "他说的确实在理" to "他/说/的/确实/在理",
            "欢迎新老师生前来就餐" to "欢迎/新/老/师生/前来/就餐",
            "请把手拿开" to "请/把/手/拿/开",
            "中华人民共和国成立了" to "中华人民共和国/成立/了",
            "他是研究生物的" to "他/是/研究/生物/的",
            "将军任命了一名中将" to "将军/任命/了/一/名/中将",
            "你喜欢吃中国菜吗" to "你/喜欢/吃/中国菜/吗",
        )
        for ((text, want) in cases) {
            val p = dict.analyse(text, SegmentationMode.HANZI)
            assertEquals(want, p.segments.joinToString("/") { text.substring(it.start, it.end) }, text)
            assertTrue(p.segments.all { it.matched })
        }
        // The chosen entry is the word's primary reading: 行长 háng zhǎng, 说 shuō (not shuì), 吗 ma, 要 yào.
        fun readings(text: String) = runBlocking { dict.analyse(text, SegmentationMode.HANZI).segments.map { it.reading.display } }
        assertEquals(listOf("yín háng", "háng zhǎng"), readings("银行行长"))
        assertEquals(listOf("tā", "shuō", "de", "què shí", "zài lǐ"), readings("他说的确实在理"))
        assertEquals(listOf("nǐ", "yào", "qù", "ma"), readings("你要去吗"))
    }

    @Test
    fun segmentationRegressionCantonese() = real(DictMode.CANTONESE) { dict ->
        val cases = listOf(
            "佢哋喺邊度食飯" to "佢哋/喺/邊度/食飯",
            "我哋聽日去睇戲" to "我哋/聽日/去/睇戲",
            "我唔知佢喺邊度" to "我/唔/知/佢/喺/邊度",
            "佢話佢唔鍾意飲咖啡" to "佢/話/佢/唔/鍾意/飲/咖啡",
            "我哋一齊去飲茶啦" to "我哋/一齊/去/飲茶/啦",
            "你識唔識講廣東話" to "你/識/唔/識/講/廣東話",
            "銀行行長" to "銀行/行長",
        )
        for ((text, want) in cases) {
            val p = dict.analyse(text, SegmentationMode.HANZI)
            assertEquals(want, p.segments.joinToString("/") { text.substring(it.start, it.end) }, text)
        }
        // 佢 is keoi5 (Unihan's customary reading), not the rarer heoi5 that precedes it in CC-Canto.
        val p = dict.analyse("佢話佢唔鍾意飲咖啡", SegmentationMode.HANZI)
        assertEquals("keoi5", p.segments.first().reading.display)
        // Data: the simplified form of 佢哋 is 佢哋 (CC-Canto writes 渠哋); a Pleco-style reference is a link.
        val keoi = dict.search("佢哋", Script.SIMPLIFIED).chinese.sections.first().groups[0].cards
        assertEquals(listOf("佢哋"), keoi.map { it.headword })
        val wan = dict.search("皖", Script.TRADITIONAL).chinese.sections.first().groups[0].cards
        assertTrue(wan.all { c -> c.definitions.none { "Ānhuī" in it || "[" in it } }, wan.map { it.definitions }.toString())
    }

    @Test
    fun primaryReadings() = real { dict ->
        // SCHEMA note 18: the entry standing for a bare character (decomposition row, segment, HSK character card).
        val want = mapOf(
            "石" to "shí", "乐" to "lè", "行" to "xíng", "长" to "cháng", "重" to "zhòng", "好" to "hǎo", "着" to "zhe", "了" to "le",
            "得" to "de", "地" to "de", "的" to "de", "还" to "hái", "都" to "dōu", "为" to "wèi", "儿" to "ér", "说" to "shuō", "要" to "yào",
        )
        for ((ch, reading) in want) {
            assertEquals(reading, dict.decompose(listOf(ch), s).single().root?.card?.reading?.display, "decomposition row of $ch")
            assertEquals(reading, dict.analyse(ch, SegmentationMode.HANZI).segments.single().reading.display, "segment of $ch")
        }
        val chars = dict.hskChars(1..7, s).associate { it.character to it.card.reading.display }
        for (ch in listOf("石", "乐", "行", "长", "重", "好", "了", "的", "还", "都")) assertEquals(want[ch], chars[ch], "HSK character $ch")
        // Ranked lists keep GLB-013 and use the rank before the id: 石 shí, dàn, then the surname.
        assertEquals(listOf("shí", "dàn", "Shí"), dict.search("石", s).chinese.sections.first().groups[0].cards.map { it.reading.display })
        // A multi-clause reading attaches its comma (SCHEMA note 21).
        assertEquals("yī bù zuò, èr bù xiū", dict.search("一不做，二不休", s).chinese.sections.first().groups[0].cards[0].reading.display)
    }

    @Test
    fun plausibleCombinations() = real { dict ->
        suspend fun combos(q: String) = dict.search(q, s).chinese.sections.firstOrNull { it.kind == SectionKind.COMBINATIONS }?.combinations.orEmpty().map { c -> c.words.joinToString(" ") { it.headword } }
        // A whole-query word leaves no room for unlikely splits of it (朋友 is not 碰 有, 你好 is not 呢 好).
        for (q in listOf("pengyou", "peng you", "nihao", "ni hao", "xiexie", "zhongguo", "xuexi", "dian", "ren", "hello", "tofu")) assertEquals(emptyList(), combos(q), q)
        // Syllables typed apart always get their best sequence, although 软 alone is below the floor of
        // unsplit letters; further sequences on those syllables are held to a lower floor.
        val rxtz = combos("ruan xing tiao zheng")
        assertEquals("软 行 调整", rxtz.first())
        assertTrue("软 性 调整" in rxtz, rxtz.toString())
        assertTrue(combos("ruan xing").let { it.first() == "软 行" && "软 性" in it }, combos("ruan xing").toString())
        assertEquals("软 行 条", combos("ruan xing tiao").first())
        assertEquals(listOf("软 调整"), combos("ruan tiao zheng"))
        assertEquals("行 调整", combos("xing tiao zheng").first())
        assertEquals(listOf("弹性 调整"), combos("tan xing tiao zheng"))
        // Unspaced with a single shortest syllable reading: the best sequence only.
        assertEquals(listOf("软 行 调整"), combos("ruanxingtiaozheng"))
        // A trailing partial or abbreviated syllable typed as its own word behaves like the unspaced
        // form, also when every word is an English word (`wo`, `shi`, `yi`, `g`; SCHEMA note 19).
        assertEquals(combos("woshiyig"), combos("wo shi yi g"))
        assertEquals("我 是 一 个", combos("wo shi yi g").first())
        assertEquals("我 是 一", combos("wo shi y").first())
        assertEquals("我 是 一 个", combos("wo shi yi ge").first())
        assertEquals("你好 吗", combos("ni hao m").first())
        assertEquals("我 喜欢 你", combos("wxhn").first())
        assertEquals(listOf("我 爱 你"), combos("woaini"))
        assertEquals(listOf("今天 天气 很 好"), combos("jintiantianqihenhao"))
        assertEquals("这个 人", combos("zgr").first())
        assertTrue(combos("nh").let { it.isNotEmpty() && it.all { c -> c.startsWith("你 ") } && "你 好" !in it }, combos("nh").toString())
        // No rare or Unicode-only single characters surface.
        for (q in listOf("wxhn", "zgr", "nh", "bj", "nhk")) for (c in combos(q)) assertTrue(c.none { it in "掽砰龢伱瞭" }, "$q: $c")
    }

    @Test
    fun plausibleCombinationsCantonese() = real(DictMode.CANTONESE) { dict ->
        val t = Script.TRADITIONAL
        suspend fun combos(q: String) = dict.search(q, t).chinese.sections.firstOrNull { it.kind == SectionKind.COMBINATIONS }?.combinations.orEmpty().map { c -> c.words.joinToString(" ") { it.headword } }
        assertEquals(listOf("我 鍾意 你"), combos("ngo5 zung1 ji3 nei5"))
        assertEquals("我 好 鍾意 你", combos("ngohouzungjinei").first())
        assertEquals("我 好 容易 你", combos("ngohoujungjinei").first()) // `jung` is 容 in Jyutping; 鍾 is zung
        assertEquals("我 都", combos("nd").first())
        // Typed-apart syllables and a trailing letter (SRCH-063 floor exemption), as in Mandarin.
        assertEquals(combos("ngohaijatg"), combos("ngo hai jat g"))
        assertEquals("我 係 一個", combos("ngo hai jat g").first())
        assertTrue(combos("ngo hai j").let { it.isNotEmpty() && it.all { c -> c.startsWith("我 係 ") } }, combos("ngo hai j").toString())
        assertEquals("完成 調整", combos("jyun sing tiu zing").first())
        assertTrue(combos("jyun tiu zing").let { it.isNotEmpty() && it.first().endsWith(" 調整") }, combos("jyun tiu zing").toString())
        assertEquals("聲 調整", combos("sing tiu zing").first())
        assertEquals(emptyList(), combos("jyun sing")) // 完成 spells the whole query
        assertEquals(SearchSide.CHINESE, dict.search("nd", t).forcedSide)
        assertEquals(SearchSide.CHINESE, dict.search("nh", t).forcedSide)
    }

    @Test
    fun toneFiltering() = real { dict ->
        val r = dict.search("hao3", s)
        val cards = r.chinese.sections.first { it.kind == SectionKind.MATCHES }.cards
        assertTrue(cards.isNotEmpty())
        assertTrue(cards.all { it.reading.tones == listOf(3) }, cards.map { it.reading.canonical }.toString())
        val both = dict.search("hao", s).chinese.sections.first { it.kind == SectionKind.MATCHES }
        val tones = dict.cards(both.refs, s).map { it.reading.tones.first() }.toSet()
        assertTrue(3 in tones && 4 in tones)
    }

    @Test
    fun combinations() = real { dict ->
        for (q in listOf("wxhn", "woxihuanni")) {
            val r = dict.search(q, s)
            val combos = r.chinese.sections.firstOrNull { it.kind == SectionKind.COMBINATIONS }?.combinations.orEmpty()
            assertTrue(combos.isNotEmpty(), "combinations for $q")
            assertTrue(combos.size <= 5)
            assertTrue(combos.any { c -> c.words.map { it.headword } == listOf("我", "喜欢", "你") }, combos.map { c -> c.words.map { it.headword } }.toString())
        }
        // nh never repeats 你好 as 你 + 好 (SRCH-063).
        val nh = dict.search("nh", s).chinese.sections.firstOrNull { it.kind == SectionKind.COMBINATIONS }?.combinations.orEmpty()
        assertTrue(nh.none { c -> c.words.joinToString("") { it.headword } == "你好" })
    }

    @Test
    fun english() = real { dict ->
        val r = dict.search("graduate student", s)
        assertTrue(r.english.count > 0)
        val heads = r.english.sections.first().cards.take(10).map { it.headword }
        assertTrue("研究生" in heads, heads.toString())
        assertEquals(SearchSide.ENGLISH, r.forcedSide) // English results only
        assertTrue(dict.search("stud", s).english.count > 0)
    }

    @Test
    fun sentence() = real { dict ->
        val r = dict.search("我们研究生命科学", s)
        assertTrue(r.chinese.isSentence)
        val groups = r.chinese.sections.first { it.kind == SectionKind.GROUPS }.groups
        assertEquals(listOf("我们", "研究", "生命", "科学"), groups.map { it.text })
        val p = assertNotNull(r.chinese.passage)
        assertEquals(4, p.segments.size)
        assertEquals(listOf("yán", "jiū"), p.segments[1].reading.display.split(" "))
        val spans = dict.spansAt(p, 3, s)
        assertEquals(SpanKind.WORDS_AT, spans.kind)
        assertTrue(spans.candidates.first().isDefault)
        assertEquals("研究", spans.candidates.first().card.headword)
        assertTrue(spans.candidates.any { it.card.headword == "研究生" })
        val reading = dict.analyse("我们研究生命科学。Hello 你好", SegmentationMode.HANZI)
        assertEquals(5, reading.segments.count { it.matched })
    }

    @Test
    fun abbreviationRanking() = real { dict ->
        val zhg = dict.search("zhg", s).chinese.sections.first { it.kind == SectionKind.MATCHES }.cards
        // SRCH-175: two-syllable zh·g words (cost of two abbreviations) precede three-syllable z·h·g words.
        assertTrue(zhg.take(10).any { it.headword == "中国" }, zhg.take(10).map { it.headword }.toString())
        val counts = zhg.map { it.reading.syllables.size }
        assertTrue(counts.indexOf(3).let { it < 0 || it > counts.lastIndexOf(2) }, counts.toString())
        val zg = dict.search("zg", s).chinese.sections.first { it.kind == SectionKind.MATCHES }.cards
        assertTrue(zg.take(10).any { it.headword == "中国" })
        val x = dict.search("x", s)
        val more = x.chinese.sections.first { it.kind == SectionKind.MORE_MATCHES }
        val top = dict.cards(more.refs.take(200), s).map { it.headword }
        assertTrue("想" in top && "现在" in top && "先生" in top, top.take(60).toString())
        assertTrue(x.chinese.sections.none { it.kind == SectionKind.COMBINATIONS })
        val t = dict.search("txu", s).chinese.sections.flatMap { it.cards }.map { it.headword }
        assertTrue("T恤" in t, t.toString())
    }

    @Test
    fun entryDetailAndRedirect() = real { dict ->
        val id = dict.search("你好", s).chinese.sections.first().groups[0].cards[0].id
        val d = dict.entryDetail(id, s)
        assertEquals("你好", d.card.headword)
        assertEquals(listOf("你", "好"), d.characters)
        assertTrue(d.members.single().blocks.single().senses.isNotEmpty())
        assertEquals(1, d.hskBadges.first().level)
        assertTrue(d.related.any { it.kind == RelatedKind.WITHIN && it.total >= 2 })
        assertEquals(id, dict.entryDetailByKey(d.naturalKey, s).id)
        // A variant redirect: 裏 → 里 shows the target's senses under a redirect header.
        val li = dict.search("裏", s).chinese.sections.first().groups[0].cards
        val redirect = li.firstNotNullOfOrNull { c -> dict.entryDetail(c.id, Script.TRADITIONAL).members.firstNotNullOfOrNull { m -> m.redirect?.let { m } } }
        if (redirect != null) {
            assertTrue(redirect.redirect!!.text.contains("variant"))
            if (redirect.redirect!!.resolved) assertTrue(redirect.blocks.isNotEmpty(), "resolved redirect shows target senses")
        }
    }

    @Test
    fun hskAndDaily() = real { dict ->
        val levels = dict.hskLevels()
        assertEquals(300, levels.first { it.level == 1 }.wordCount)
        val l1 = dict.hsk(1..1, s)
        assertEquals(levels.first { it.level == 1 }.cardCount, l1.size)
        assertEquals(l1.size, l1.map { it.key }.toSet().size)
        assertTrue(dict.hskChars(1..1, s).isNotEmpty())
        val w1 = dict.wordOfTheDay(20736, s)
        assertEquals(w1?.id, dict.wordOfTheDay(20736, Script.TRADITIONAL)?.id)
        assertNotNull(w1)
        assertEquals(50, dict.popular(s).size)
    }

    @Test
    fun cantonese() = real(DictMode.CANTONESE) { dict ->
        val t = Script.TRADITIONAL
        assertEquals(6, dict.info().toneCount)
        for (q in listOf("neihou", "nei5 hou2", "nei5hou2")) {
            assertEquals("你好", dict.search(q, t).chinese.sections.first { it.kind == SectionKind.MATCHES }.cards.first().headword, q)
        }
        // Tone digits 1–6 are Jyutping tones; the reading is shown verbatim.
        assertEquals("nei5 hou2", dict.search("你好", t).chinese.sections.first().groups[0].cards[0].reading.display)
        val combos = dict.search("ngo5 zung1 ji3 nei5", t).chinese.sections.first { it.kind == SectionKind.COMBINATIONS }.combinations
        assertEquals(listOf("我", "鍾意", "你"), combos.first().words.map { it.headword })
        // Source groups on the English side (SRCH-076): CC-Canto, then CC-CEDICT rows, then Unihan.
        val walk = dict.search("walk", t).english.sections
        assertEquals(listOf(SectionKind.ENGLISH_CC_CANTO, SectionKind.ENGLISH_CC_CEDICT, SectionKind.ENGLISH_UNIHAN), walk.map { it.kind })
        assertTrue(walk[0].cards.all { it.hasCantoBlock && it.mandarinRow == null })
        assertTrue(walk[1].cards.all { it.mandarinRow != null })
        assertTrue(walk[2].cards.all { it.isUnihanOnly })
        val cantoIds = dict.cards(walk[0].refs, t).map { it.id }.toSet()
        assertTrue(walk[1].refs.none { it.entryId in cantoIds })
        // Chinese side: CC-Canto readings first inside a group (SRCH-075).
        val hang = dict.search("行", t).chinese.sections.first().groups[0].cards
        assertTrue(hang.size >= 5)
        assertEquals(hang.map { it.hasCantoBlock }, hang.map { it.hasCantoBlock }.sortedDescending())
        // Source blocks (ENT-110/111): Cantonese block(s) first, Mandarin block collapsed, its Pinyin groups attached.
        val blocks = dict.entryDetail(hang[0].id, t).members.single().blocks
        assertEquals(com.szdict.core.api.BlockKind.CANTONESE, blocks.first().kind)
        val mandarin = blocks.last()
        assertEquals(com.szdict.core.api.BlockKind.MANDARIN, mandarin.kind)
        assertTrue(blocks.all { it.showLabel } && mandarin.collapsedByDefault && mandarin.collapsible)
        assertTrue(mandarin.readingGroups.any { it.reading.display == "xíng" })
        assertEquals(mandarin.senses.size, mandarin.readingGroups.sumOf { it.senses.size })
        val numbers = blocks.flatMap { b -> b.senses.map { it.number } }
        assertEquals((1..numbers.size).toList(), numbers)
        // Segmentation with the Cantonese frequency table.
        val p = dict.analyse("我哋今日去邊度食飯", SegmentationMode.HANZI)
        assertEquals(listOf("我哋", "今日", "去", "邊度", "食飯"), p.segments.map { p.text.substring(it.start, it.end) })
        assertEquals(300, dict.hskLevels().first { it.level == 1 }.wordCount)
        assertNotNull(dict.wordOfTheDay(20736, t))
        assertTrue(dict.examples(hang[0].id, t).items.isNotEmpty())
    }

    @Test
    fun packs() = real { dict ->
        val id = dict.search("银行", s).chinese.sections.first().groups[0].cards[0].id
        val d = dict.entryDetail(id, s)
        assertTrue(d.exampleCount in 1..20)
        val ex = dict.examples(id, s, 0, 5)
        assertEquals(5, ex.items.size)
        assertTrue(ex.items.all { e -> e.highlights.isNotEmpty() && e.highlights.all { e.text.substring(it.start, it.end) == "银行" } })
        assertTrue(dict.examples(id, Script.TRADITIONAL, 0, 5).items.all { "銀行" in it.text })
        assertTrue(d.card.audio.verified && d.card.audio.hasRecording)
        val trees = dict.decompose(d.characters, s, id)
        assertEquals(listOf("银", "行"), trees.map { it.character })
        val yin = assertNotNull(trees[0].root)
        assertEquals("⿰", yin.operator)
        assertEquals(listOf(com.szdict.core.api.PartPosition.LEFT, com.szdict.core.api.PartPosition.RIGHT), yin.children.map { it.position })
        assertEquals(listOf("钅", "艮"), yin.children.map { it.glyph })
        assertTrue(yin.children.all { it.isRoot && it.card != null })
        // Exact strokes partition the glyph; regions follow the operator.
        val strokes = yin.children.map { assertNotNull(it.highlight.strokes) }
        assertEquals(yin.highlight.strokes, (strokes[0] + strokes[1]).sorted())
        assertEquals(0.5f, yin.children[1].highlight.region.left)
        // The top-level row of 行 follows the headword's reading (háng in 银行), ENT-122.
        assertEquals("háng", trees[1].root?.card?.reading?.display)
        val xiang = dict.decompose(listOf("想"), s).single().root!!
        assertEquals(listOf("相", "心"), xiang.children.map { it.glyph })
        assertEquals(listOf("木", "目"), xiang.children[0].children.map { it.glyph })
        assertEquals(listOf(0, 0), xiang.children[0].children[0].path)
    }

    @Test
    fun timingsCantonese() = real(DictMode.CANTONESE) { dict ->
        val t = Script.TRADITIONAL
        suspend fun time(label: String, reps: Int, block: suspend () -> Unit) {
            repeat(3) { block() }
            val start = System.nanoTime()
            repeat(reps) { block() }
            println("TIMING [yue] $label: %.2f ms (mean of $reps, warm)".format((System.nanoTime() - start) / reps / 1e6))
        }
        time("search neihou", 20) { dict.search("neihou", t) }
        time("search nh", 20) { dict.search("nh", t) }
        time("search ngo5 zung1 ji3 nei5", 20) { dict.search("ngo5 zung1 ji3 nei5", t) }
        time("search ngo hai jat g", 20) { dict.search("ngo hai jat g", t) }
        time("search walk", 20) { dict.search("walk", t) }
        time("search 行", 20) { dict.search("行", t) }
        val passage = "我哋今日去邊度食飯呀佢話唔知你鍾唔鍾意食點心我聽日想去睇戲".repeat(4).take(100)
        var n = 0
        time("analyse 100 characters (uncached)", 30) { dict.analyse(passage + " ".repeat(++n), SegmentationMode.HANZI) }
    }

    @Test
    fun timings() = real { dict ->
        fun ms(n: Long) = "%.2f ms".format(n / 1e6)
        suspend fun time(label: String, reps: Int, block: suspend () -> Unit) {
            repeat(3) { block() }
            val t = System.nanoTime()
            repeat(reps) { block() }
            println("TIMING $label: ${ms((System.nanoTime() - t) / reps)} (mean of $reps, warm)")
        }
        val cold = System.nanoTime()
        dict.search("nihao", s)
        println("TIMING search nihao (first call): ${ms(System.nanoTime() - cold)}")
        time("search nihao", 20) { dict.search("nihao", s) }
        time("search nh (abbr + combinations)", 20) { dict.search("nh", s) }
        time("search ruan xing tiao zheng (combinations)", 20) { dict.search("ruan xing tiao zheng", s) }
        time("search wo shi yi g (combinations)", 20) { dict.search("wo shi yi g", s) }
        time("search x", 10) { dict.search("x", s) }
        time("search 你好", 20) { dict.search("你好", s) }
        time("search graduate student", 20) { dict.search("graduate student", s) }
        time("search a (english prefix)", 5) { dict.search("a", s) }
        val base = "我们研究生命科学，今天天气很好，我想去图书馆看书。你喜欢吃中国菜吗？他们昨天去了北京，明天回上海。"
        val passage = (base + base).take(100)
        var n = 0
        time("analyse 100 characters (uncached)", 30) { dict.analyse(passage + " ".repeat(++n), SegmentationMode.HANZI) }
        val p = dict.analyse(passage, SegmentationMode.HANZI)
        time("spansAt", 20) { dict.spansAt(p, 3, s) }
        val id = dict.search("研究生", s).chinese.sections.first().groups[0].cards[0].id
        time("entryDetail 研究生", 20) { dict.entryDetail(id, s) }
        time("hsk level 1", 3) { dict.hsk(1..1, s) }
    }
}
