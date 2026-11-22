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

    /** Combination rows as text: slots separated by spaces, a slot's alternatives by `/`, rows by ` | `. */
    private suspend fun rows(dict: Dictionary, q: String, script: Script = s): List<String> =
        dict.search(q, script).chinese.sections.firstOrNull { it.kind == SectionKind.COMBINATIONS }?.combinations.orEmpty()
            .map { c -> c.slots.joinToString(" ") { slot -> slot.alternatives.joinToString("/") { it.headword } } }

    @Test
    fun plausibleCombinations() = real { dict ->
        suspend fun combos(q: String) = rows(dict, q)
        // A word spelling the whole query plays no part in the policy (SRCH-063); only a sequence that
        // spells it again is dropped, and the floor and the margin keep the rest plausible.
        for (q in listOf("nihao", "ni hao", "xiexie", "zhongguo", "xuexi", "hello", "tofu", "can you")) assertEquals(emptyList(), combos(q), q)
        // Known, reviewed side effect: a trailing letter read as a syllable of its own (`pengyou` as peng·you·o).
        assertEquals(listOf("朋友 欧/区"), combos("pengyou"))
        assertEquals(listOf("软 性/星 调整"), combos("ruan xing tiao zheng"))
        assertEquals(combos("ruan xing tiao zheng"), combos("ruanxingtiaozheng"))
        assertEquals(emptyList(), combos("ruan xing")) // below the floor of -6.5 per syllable
        assertEquals("性/行/型/省 调整", combos("xing tiao zheng").first())
        assertEquals(listOf("弹性 调整", "谈 性 调整"), combos("tan xing tiao zheng"))
        // A trailing partial or abbreviated syllable typed as its own word behaves like the unspaced
        // form, also when every word is an English word (`wo`, `shi`, `yi`, `g`; SCHEMA note 19).
        assertEquals(combos("woshiyig"), combos("wo shi yi g"))
        assertEquals(listOf("我 是 一个", "我 是 一 个"), combos("wo shi yi g"))
        assertEquals("我 是 一/要/有/用", combos("wo shi y").first())
        assertEquals("我 是 一个", combos("wo shi yi ge").first())
        assertEquals("你好 吗/么", combos("ni hao m").first())
        assertEquals("我 喜欢 你/那", combos("wxhn").first())
        assertEquals(listOf("我 爱 你"), combos("woaini"))
        assertEquals(listOf("今天 天气 很 好"), combos("jintiantianqihenhao"))
        assertTrue(combos("zgr").first().startsWith("这个/"), combos("zgr").toString())
        assertTrue(combos("nh").let { it.isNotEmpty() && it.first().startsWith("你/") }, combos("nh").toString())
        // Numerals and times (subtitle counts with digits read out, PIPE-086) and homophones grouped (SRCH-065).
        for (q in listOf("jiudian", "jiu dian", "jiu3 dian3")) assertEquals(listOf("九 点"), combos(q), q)
        assertEquals(listOf("八/吧 点"), combos("ba dian"))
        assertEquals(listOf("八/吧 点 半"), combos("ba dian ban"))
        assertEquals(listOf("九/就 点钟", "酒店 中/种"), combos("jiu dian zhong"))
        assertEquals(listOf("我/喔 酒店 去/区/取/曲", "我 九/就 点/店 去/取/曲"), combos("wo jiu dian qu"))
        assertEquals(listOf("三 个人", "三 个 人"), combos("san ge ren"))
        assertEquals(listOf("两 个"), combos("liang ge"))
        assertEquals(listOf("十二 点"), combos("shi er dian"))
        // At most three rows of at most four alternatives.
        for (q in listOf("wxhn", "zgr", "nh", "bj", "nhk", "wo jiu dian qu", "mei wenti")) {
            val r = combos(q)
            assertTrue(r.size <= 3 && r.all { row -> row.split(" ").all { it.split("/").size <= 4 } }, "$q: $r")
        }
        // No rare or Unicode-only single characters surface.
        for (q in listOf("wxhn", "zgr", "nh", "bj", "nhk")) for (c in combos(q)) assertTrue(c.none { it in "掽砰龢伱瞭" }, "$q: $c")
    }

    @Test
    fun plausibleCombinationsCantonese() = real(DictMode.CANTONESE) { dict ->
        val t = Script.TRADITIONAL
        suspend fun combos(q: String) = rows(dict, q, t)
        // The Cantonese companion has no combination model (DATA-156): the segmentation bigrams score
        // the sequences, with the same show policy and grouping as Mandarin.
        assertEquals(listOf("我 鍾意 你"), combos("ngo5 zung1 ji3 nei5"))
        assertEquals("我 好 鍾意 你/呢", combos("ngohouzungjinei").first())
        assertTrue(combos("ngohoujungjinei").first().startsWith("我 好 容易 你")) // `jung` is 容 in Jyutping; 鍾 is zung
        assertTrue(combos("nd").first().startsWith("我/"), combos("nd").toString())
        assertEquals(combos("ngohaijatg"), combos("ngo hai jat g"))
        assertEquals("我/哦 係 一個", combos("ngo hai jat g").first())
        assertTrue(combos("ngo hai j").first().startsWith("我/哦 係 "), combos("ngo hai j").toString())
        assertTrue(combos("jyun sing tiu zing").first().let { it.startsWith("完成/") && it.endsWith(" 調整") }, combos("jyun sing tiu zing").toString())
        assertTrue(combos("sing tiu zing").isNotEmpty())
        assertEquals(SearchSide.CHINESE, dict.search("nd", t).forcedSide)
        assertEquals(SearchSide.CHINESE, dict.search("nh", t).forcedSide)
    }

    @Test
    fun hskAwareOrdering() = real { dict ->
        // SRCH-070 item 4: the frequency rank is capped by the entry's lowest HSK level.
        val xing = dict.search("xing", s).chinese.sections.first { it.kind == SectionKind.MATCHES }.cards.map { it.headword }
        assertEquals("行", xing.first())
        assertTrue(xing.indexOf("姓") in 0 until xing.indexOf("性"), xing.toString()) // HSK 2 (rank 2473) before HSK 4 (1653)
        assertEquals("学习", dict.search("study", s).english.sections.first().cards.first().headword)
        val xue = dict.search("学", s).chinese.sections.first().groups[0].cards[0].id
        val contains = dict.related(xue, RelatedKind.CONTAINING, s, 0, 6).cards
        assertTrue(contains.all { 1 in it.hskLevels }, contains.map { it.headword }.toString())
        val starts = dict.related(xue, RelatedKind.STARTING_WITH, s, 0, 20).cards.map { it.headword }
        assertTrue(starts.indexOf("学期") in 0 until starts.indexOf("学会"), starts.toString()) // HSK 3 (7062) before HSK 6 (1549)
        // A non-HSK word more frequent than the cap keeps its place: 什 (875) before 史 (1395) and 室 (HSK 3, 1717).
        val shi = dict.search("shi", s).chinese.sections.first { it.kind == SectionKind.MATCHES }.cards.map { it.headword }
        assertEquals(listOf("是", "事", "时"), shi.take(3))
        assertTrue(shi.indexOf("什") < shi.indexOf("室"), shi.toString())
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
            assertTrue(combos.size <= 3)
            assertTrue(combos.any { c -> c.words.map { it.headword } == listOf("我", "喜欢", "你") }, combos.map { c -> c.words.map { it.headword } }.toString())
        }
        // nh never repeats 你好 as 你 + 好 (SRCH-063).
        val nh = dict.search("nh", s).chinese.sections.firstOrNull { it.kind == SectionKind.COMBINATIONS }?.combinations.orEmpty()
        assertTrue(nh.none { c -> c.words.joinToString("") { it.headword } == "你好" })
    }

    /** Groups as text: `|p` punctuation, `|-` NO MATCH, `|e` English match (SRCH-033a/b). */
    private suspend fun groups(dict: Dictionary, q: String): List<String> =
        dict.search(q, s).chinese.sections.first { it.kind == SectionKind.GROUPS }.groups.map { g ->
            g.text + when {
                g.punctuation -> "|p"
                g.inert -> "|-"
                g.english -> "|e"
                else -> ""
            }
        }

    @Test
    fun sentenceModeMixed() = real { dict ->
        // Every non-whitespace character in one group; foreign words atomic (SRCH-033, SRCH-033a/b).
        assertEquals(listOf("我", "要", "看", "BOM|-", "表"), groups(dict, "我要看BOM表"))
        assertEquals(listOf("他", "是", "CEO|e"), groups(dict, "他是CEO"))
        assertEquals(listOf("发", "个", "email|e", "给", "我"), groups(dict, "发个email给我"))
        assertEquals(listOf("请", "给", "我", "hello|e", "world|e", "的", "翻译"), groups(dict, "请给我hello world的翻译"))
        assertEquals(listOf("我", "用", "iPhone|e", "和", "WiFi|-"), groups(dict, "我用iPhone和WiFi"))
        assertEquals(listOf("今天", "去", "KTV|e", "唱歌"), groups(dict, "今天去KTV唱歌"))
        assertEquals(listOf("我", "穿", "T恤"), groups(dict, "我穿T恤"))
        assertEquals(listOf("你好", "！|p", "BOM|-", "。|p"), groups(dict, "你好！BOM。"))
        assertEquals(listOf("hello|e", ",|p", "world|e", "你好"), groups(dict, "hello,world 你好"))
        assertEquals(listOf("我", "想", "买", "3|-", "个", "苹果"), groups(dict, "我想买3个苹果"))
        assertEquals(listOf("用", "Wi-Fi|-", "上网"), groups(dict, "用Wi-Fi上网"))
        // English matches are whole words: an English-side group for iPhone holds 苹果手机, never a `bomb` for BOM.
        val iphone = dict.search("我用iPhone和WiFi", s).chinese.sections.first().groups[2]
        assertTrue(iphone.cards.size in 1..3 && iphone.cards.any { it.headword == "苹果手机" }, iphone.cards.map { it.headword }.toString())
        val r = dict.search("我要看BOM表", s)
        assertTrue(r.chinese.isSentence && r.chinese.passage != null)
        assertTrue(dict.search("卡拉OK", s).chinese.sections.first().groups.single().cards.any { it.headword == "卡拉OK" })
    }

    @Test
    fun mixedCombinations() = real { dict ->
        // SRCH-069: typed Hanzi pinned, Latin runs read as pinyin (full, abbreviated, partial).
        assertEquals(listOf("九 点"), rows(dict, "九dian"))
        assertEquals(listOf("九 点"), rows(dict, "jiu 点"))
        assertEquals(listOf("我 九/就 点 去/取/曲/娶"), rows(dict, "我jiu点qu"))
        assertEquals(listOf("我 想 去"), rows(dict, "我xiang去"))
        val jq = rows(dict, "我j点q")
        assertTrue(jq.isNotEmpty() && jq.all { it.startsWith("我 ") } && jq.any { "九" in it || "就" in it || "几点" in it }, jq.toString())
        // Uppercase, digits and English words are not pinyin here.
        for (q in listOf("我要看BOM表", "他是CEO", "今天去KTV唱歌", "我用iPhone和WiFi", "发个email给我", "我想买3个苹果", "我Jiu点qu")) assertEquals(emptyList(), rows(dict, q), q)
        // A run that is a common English word needs a far likelier Chinese reading (men, he), unlike women.
        assertEquals(emptyList(), rows(dict, "这条路很long"))
        assertEquals(listOf("我们 去 哪儿"), rows(dict, "women去哪儿"))
        // Placed after the groups (and exact-length pattern matches).
        val sections = dict.search("我jiu点qu", s).chinese.sections.map { it.kind }
        assertTrue(sections.indexOf(SectionKind.COMBINATIONS) > sections.indexOf(SectionKind.GROUPS), sections.toString())
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
    fun insideWordsForMixedScriptHeadwords() = real { dict ->
        for ((word, part, script) in listOf(
            Triple("U盘", "盘", Script.SIMPLIFIED),
            Triple("U盤", "盤", Script.TRADITIONAL),
        )) {
            val card = dict.search(word, script).chinese.sections.flatMap { it.cards + it.groups.flatMap { g -> g.cards } }
                .first { it.headword == word }
            assertTrue(card.insideCount > 0)
            assertTrue(dict.insideWords(card.id, script).any { it.card.headword == part })
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
    fun hskGridIncludesEveryAssignedLevel() {
        for (mode in DictMode.entries) real(mode) { dict ->
            val levels = dict.hskLevels()
            assertEquals(listOf(300, 204, 507, 1019, 1638, 1815, 5622), levels.map { it.wordCount })
            val cards = dict.hsk(1..2, s)
            assertEquals(cards.size, cards.map { it.key }.toSet().size)
            assertEquals(levels.take(2).map { it.cardCount }, (1..2).map { l -> cards.count { it.level == l } })
            for (word in listOf("多", "给", "好", "那")) {
                assertEquals(setOf(1, 2), cards.filter { it.card.simplified == word }.map { it.level }.toSet(), word)
            }
        }
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
        time("search wo jiu dian qu (combinations)", 20) { dict.search("wo jiu dian qu", s) }
        time("search jintiantianqihenhao (combinations)", 20) { dict.search("jintiantianqihenhao", s) }
        time("search 我jiu点qu (mixed combinations)", 20) { dict.search("我jiu点qu", s) }
        time("search 45-character mixed sentence", 20) { dict.search("我们下午三点在会议室开会，有问题请发email给我或者打电话给老王，谢谢大家的配合和支持！", s) }
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
