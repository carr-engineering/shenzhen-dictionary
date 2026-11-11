package com.szdict.core

import com.szdict.core.api.PartPosition
import com.szdict.core.api.QueryKind
import com.szdict.core.api.Region
import com.szdict.core.api.Script
import com.szdict.core.domain.Ids
import com.szdict.core.engine.Candidate
import com.szdict.core.engine.Classifier
import com.szdict.core.engine.LatticeDecoder
import com.szdict.core.engine.LatticeEdge
import com.szdict.core.engine.MatchClass
import com.szdict.core.engine.Normaliser
import com.szdict.core.engine.Ranking
import com.szdict.core.engine.RomanParser
import com.szdict.core.engine.RomanPath
import com.szdict.core.engine.TokKind
import com.szdict.core.engine.isHanzi
import com.szdict.core.store.BigramBlock
import com.szdict.core.store.Codec
import com.szdict.core.store.Inventory
import com.szdict.core.store.Proj
import com.szdict.core.store.VarintReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A small Mandarin-like inventory; the parser itself has no language knowledge (GLB-010). */
internal fun testInventory(): Inventory {
    val syllables = listOf(
        "a", "ai", "an", "e", "o", "ni", "hao", "he", "hen", "xi", "xian", "xiang", "xu", "wo", "huan", "zhong", "zhang", "zhi", "zi",
        "guo", "ge", "shi", "shang", "hai", "sheng", "ti", "tian", "men", "ma", "lü", "lüe", "qu", "fan", "fang", "de", "n", "yi", "dian",
    )
    val abbr = ArrayList<Pair<String, String>>()
    for (s in syllables) {
        if (s[0] !in "iuvü") abbr.add(s.substring(0, 1) to s)
        if (s.length > 2 && s.substring(0, 2) in setOf("zh", "ch", "sh")) abbr.add(s.substring(0, 2) to s)
    }
    return Inventory(syllables, mapOf("lue" to "lüe", "lv" to "lü"), abbr)
}

class NormaliseTest {
    @Test
    fun matchingKeyExamples() {
        assertEquals("ni3 hao3", Normaliser.matchingKey("  NI3   HAO3!! "))
        assertEquals("t恤", Normaliser.matchingKey("Ｔ恤"))
        assertEquals("xi'an", Normaliser.matchingKey("xi’an"))
        assertEquals("马丁·路德·金", Normaliser.matchingKey("马丁・路德•金"))
        assertEquals("", Normaliser.matchingKey("，。！？🙂"))
        assertEquals("ni hao", Normaliser.matchingKey("ni,hao"))
        assertEquals("graduate student", Normaliser.matchingKey("graduate-student"))
        assertEquals("你好", Normaliser.matchingKey("你好！"))
        assertEquals("hello", Normaliser.matchingKey("🙂 hello"))
        assertEquals("ni3", Normaliser.matchingKey("ｎｉ３"))
        assertEquals("lü", Normaliser.matchingKey("LU:"))
        // A combining caron composes instead of becoming a separator.
        assertEquals("nǐ", Normaliser.matchingKey("nǐ"))
        assertEquals("", Normaliser.matchingKey("   "))
    }

    @Test
    fun displayTextIsGentle() {
        assertEquals("Ｔ恤 很好！", Normaliser.displayText("  Ｔ恤　　很好！ "))
    }

    @Test
    fun englishTokens() {
        assertEquals(listOf("graduate", "student"), Normaliser.englishTokens("Graduate students"))
        assertEquals(listOf("glass", "bus", "cds", "its", "student"), Normaliser.englishTokens("glass bus CDs its Students"))
        assertEquals(emptyList(), Normaliser.englishTokens("你好"))
    }

    @Test
    fun hanziSet() {
        assertTrue(isHanzi('你'.code) && isHanzi(0x3007) && isHanzi(0x20000) && isHanzi(0xFA0E))
        assertFalse(isHanzi('a'.code) || isHanzi(0x3002) || isHanzi(0xF900) || isHanzi(0x2F00))
    }

    @Test
    fun varints() {
        assertEquals(listOf(5, 300, 301, 70000), Codec.deltaIds(byteArrayOf(5, 0xA7.toByte(), 2, 1, 0xC3.toByte(), 0xA0.toByte(), 4)).toList())
        assertEquals(listOf(300, 2), Codec.plainIds(byteArrayOf(0xAC.toByte(), 2, 2)).toList())
        val r = VarintReader(byteArrayOf(1, 0xFF.toByte()))
        assertEquals(1, r.next())
        assertEquals(255, r.byte())
        assertEquals("[\"a\\\"b\",\"你\"]", Codec.jsonArray(listOf("a\"b", "你")))
    }
}

class RomanParserTest {
    private val inv = testInventory()
    private val a = 0.693
    private val p = 0.357
    private fun parse(q: String) = RomanParser.parse(Normaliser.matchingKey(q), inv, 5, a, p)
    private fun paths(q: String): List<String> = parse(q)?.paths.orEmpty().map { it.toString() }
    private fun kinds(path: RomanPath) = path.tokens.map { it.kind }

    @Test
    fun fullSyllables() {
        val best = parse("nihao")!!.paths.first()
        assertEquals(listOf("ni", "hao"), best.tokens.map { it.syllable })
        assertEquals(0.0, best.cost)
        assertEquals("[FULL:ni FULL:hao]", paths("ni hao").first())
        assertEquals("[FULL:ni3 FULL:hao3]", paths("ni3hao3").first())
        assertEquals("[FULL:ni3 FULL:hao3]", paths("nǐhǎo").first())
        assertEquals(listOf(3, 0), parse("ni3 hao")!!.paths.first().tokens.map { it.tone })
    }

    @Test
    fun apostropheIsABoundary() {
        assertEquals("[FULL:xian]", paths("xian").first())
        assertTrue(paths("xi'an").all { it.startsWith("[FULL:xi FULL:an") || !it.contains("xian") })
        assertEquals("[FULL:xi FULL:an]", paths("xi’an").first())
    }

    @Test
    fun abbreviations() {
        assertTrue("[ABBR:n ABBR:h]" in paths("nh"), paths("nh").toString())
        assertTrue("[ABBR:w ABBR:x ABBR:h ABBR:n]" in paths("wxhn"))
        assertEquals("[ABBR:w FULL:hao]", paths("whao").first())
        val nih = paths("nih")
        assertTrue("[FULL:ni PARTIAL:h]" in nih && "[FULL:ni ABBR:h]" in nih, nih.toString())
        // partial_penalty < abbrev_penalty here, so the partial reading is the cheaper one.
        assertEquals("[FULL:ni PARTIAL:h]", nih.first())
    }

    @Test
    fun singleTokenIsNeverAnAbbreviation() {
        val x = parse("x")!!.paths
        assertEquals(1, x.size)
        assertEquals(listOf(TokKind.PARTIAL), kinds(x[0]))
        val sh = paths("sh")
        assertTrue("[PARTIAL:sh]" in sh && "[ABBR:s ABBR:h]" in sh, sh.toString())
        assertFalse(sh.any { it == "[ABBR:sh]" })
    }

    @Test
    fun costOrdersFullerSpellingsFirst() {
        val zhg = parse("zhg")!!.paths
        val two = zhg.indexOfFirst { it.toString() == "[ABBR:zh ABBR:g]" }
        val three = zhg.indexOfFirst { it.toString() == "[ABBR:z ABBR:h ABBR:g]" }
        assertTrue(two in 0 until three, zhg.toString())
        assertTrue(zhg.zipWithNext().all { (x, y) -> x.cost <= y.cost })
        assertTrue(zhg.size <= RomanParser.MAX_PATHS)
    }

    @Test
    fun tonesAndInvalidTokens() {
        // Two diacritics in one syllable invalidate that token (SRCH-166).
        assertTrue(parse("hǎó")!!.paths.none { p -> p.tokens.any { it.text == "hao" } })
        // A tone digit on an abbreviation is not a token.
        assertTrue(paths("z3h").isEmpty())
        assertNull(parse("110"))
        assertNull(parse("ni9"))
        assertEquals(listOf(3, 0), parse("ni3h")!!.paths.first().tokens.map { it.tone })
    }

    @Test
    fun literalInitials() {
        for (q in listOf("Txu", "T xu", "t xu4")) {
            val ps = parse(q)!!.paths
            assertTrue(ps.any { kinds(it) == listOf(TokKind.LITERAL, TokKind.FULL) && it.tokens[1].syllable == "xu" }, "$q: $ps")
            assertTrue(ps.any { kinds(it) == listOf(TokKind.ABBR, TokKind.FULL) }, "$q: $ps")
            assertTrue(ps.none { it.hasLiteral && it.hasAbbr })
        }
    }

    @Test
    fun bounds() {
        val z = parse("zzzz")!!.paths
        assertTrue(z.any { kinds(it) == listOf(TokKind.ABBR, TokKind.ABBR, TokKind.ABBR, TokKind.ABBR) })
        assertTrue(z.all { it.tokens.size == 4 })
        // More than 12 abbreviated tokens: no path survives.
        assertTrue(parse("z".repeat(13))!!.paths.isEmpty())
        // Paths of full syllables are not length-limited.
        assertEquals(40, parse("nihao".repeat(20))!!.paths.first().tokens.size)
        assertEquals("[FULL:lüe]", paths("lue").first())
        assertEquals("[FULL:lü]", paths("lv").first())
    }

    @Test
    fun classification() {
        fun kind(q: String) = Classifier.classify(q, inv, 5, a, p)
        assertEquals(QueryKind.EMPTY, kind(" ！ ").kind)
        assertEquals(QueryKind.HANZI, kind("你好 世界").kind)
        assertEquals(QueryKind.ROMANISATION, kind("nh").kind)
        assertEquals(QueryKind.ROMANISATION, kind("zhg").kind)
        assertEquals(QueryKind.LATIN, kind("uiu").kind)
        val mixed = kind("我xh你")
        assertEquals(QueryKind.MIXED, mixed.kind)
        assertEquals(4, mixed.patterns.first().first.size)
        assertEquals('我'.code, mixed.patterns.first().first[0].hanzi)
        assertEquals(TokKind.ABBR, mixed.patterns.first().first[1].tok!!.kind)
        // Unparseable Latin: the pattern is skipped (SRCH-162).
        val noise = kind("我uiu")
        assertEquals(QueryKind.MIXED, noise.kind)
        assertTrue(noise.patterns.isEmpty())
        assertTrue(kind("ni3hao").hasTone)
        assertFalse(kind("nihao").hasTone)
        assertTrue(kind("我xiang qu").patterns.any { alt -> alt.first.map { it.tok?.syllable } == listOf(null, "xiang", "qu") })
    }
}

class LatticeDecoderTest {
    // Words: 1 我, 2 喜欢, 3 你, 4 西, 5 换, 6 泥.
    private val edges = listOf(
        LatticeEdge(0, 1, 1, -3.0, 0.0, 101),
        LatticeEdge(1, 3, 2, -6.0, 0.0, 102),
        LatticeEdge(1, 2, 4, -5.0, 0.0, 104),
        LatticeEdge(2, 3, 5, -5.5, 0.0, 105),
        LatticeEdge(3, 4, 3, -3.5, 0.0, 103),
        LatticeEdge(3, 4, 6, -3.4, 0.0, 106),
    )

    @Test
    fun unigramOnly() {
        val paths = LatticeDecoder.decode(5, edges, emptyMap(), 8, 20.0)
        assertEquals(listOf(101, 102, 106), paths.first().edges.map { it.payload })
        assertEquals(-12.4, paths.first().score, 1e-9)
        assertEquals(4, paths.size)
        assertTrue(paths.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test
    fun bigramsAndBackoff() {
        // 喜欢 → 你 is a strong bigram; 喜欢 has a backoff weight for every other successor.
        val bigrams = mapOf(2 to BigramBlock(-0.5, intArrayOf(3), doubleArrayOf(-0.2)), 1 to BigramBlock(-0.1, intArrayOf(2), doubleArrayOf(-1.0)))
        val paths = LatticeDecoder.decode(5, edges, bigrams, 8, 20.0)
        assertEquals(listOf(101, 102, 103), paths.first().edges.map { it.payload })
        assertEquals(-3.0 - 1.0 - 0.2, paths.first().score, 1e-9)
        // 我 喜欢 泥: unigram + bigram + (backoff + unigram).
        val other = paths.first { p -> p.edges.map { it.payload } == listOf(101, 102, 106) }
        assertEquals(-3.0 - 1.0 + (-0.5 - 3.4), other.score, 1e-9)
    }

    @Test
    fun penaltiesBeamAndK() {
        val penalised = edges.map { if (it.payload == 102) LatticeEdge(it.from, it.to, it.word, it.unigram, -10.0, it.payload) else it }
        assertEquals(listOf(101, 104, 105, 106), LatticeDecoder.decode(5, penalised, emptyMap(), 8, 20.0).first().edges.map { it.payload })
        assertEquals(1, LatticeDecoder.decode(5, edges, emptyMap(), 1, 0.01).size.coerceAtMost(1))
        assertTrue(LatticeDecoder.decode(5, edges.filter { it.to != 4 }, emptyMap(), 8, 20.0).isEmpty())
    }

    @Test
    fun tiesAreDeterministic() {
        val tie = listOf(
            LatticeEdge(0, 2, 9, -4.0, 0.0, 9), LatticeEdge(0, 1, 7, -2.0, 0.0, 7), LatticeEdge(1, 2, 8, -2.0, 0.0, 8),
        )
        val paths = LatticeDecoder.decode(3, tie, emptyMap(), 8, 20.0)
        // Equal scores: the path with the longer first word wins.
        assertEquals(listOf(9), paths.first().edges.map { it.payload })
    }
}

class RankingTest {
    private fun proj(id: Int, freq: Int = 100, len: Int = 2, flags: Int = 0, merge: Int = -1) = Proj(id, flags, freq, len, merge, -1)

    @Test
    fun canonicalComparatorKeyOrder() {
        val list = listOf(
            Candidate(proj(1, freq = 1, flags = 4), MatchClass.EXACT),               // Unicode-only: final tier
            Candidate(proj(2, freq = 500), MatchClass.PREFIX),
            Candidate(proj(3, freq = 900), MatchClass.EXACT_TONELESS, alt = true),   // alternative reading
            Candidate(proj(4, freq = 900), MatchClass.EXACT_TONELESS, cost = 0.7),
            Candidate(proj(5, freq = 900), MatchClass.EXACT_TONELESS),
            Candidate(proj(6, freq = 900, len = 3), MatchClass.EXACT_TONELESS),
            Candidate(proj(7, freq = 900, flags = 1), MatchClass.EXACT_TONELESS),    // proper noun
            Candidate(proj(8, freq = 900), MatchClass.EXACT_TONELESS),
            Candidate(proj(9, freq = 50), MatchClass.EXACT_TONELESS, cost = 0.7),
        )
        assertEquals(listOf(5, 8, 7, 6, 9, 4, 3, 2, 1), list.shuffled().sortedWith(Ranking.comparator(false)).map { it.proj.id })
    }

    @Test
    fun cantoneseSourceGroupInsideClass() {
        val list = listOf(Candidate(proj(1, freq = 1), MatchClass.EXACT), Candidate(proj(2, freq = 9, flags = 8), MatchClass.EXACT), Candidate(proj(3, flags = 8), MatchClass.PREFIX))
        assertEquals(listOf(2, 1, 3), list.sortedWith(Ranking.comparator(true)).map { it.proj.id })
        assertEquals(listOf(1, 2, 3), list.sortedWith(Ranking.comparator(false)).map { it.proj.id })
    }

    @Test
    fun bestMatchAndMergeDedupe() {
        assertTrue(Candidate(proj(1), MatchClass.EXACT, alt = true).betterThan(Candidate(proj(1), MatchClass.PREFIX)))
        assertTrue(Candidate(proj(1), MatchClass.PREFIX).betterThan(Candidate(proj(1), MatchClass.PREFIX, alt = true)))
        assertTrue(Candidate(proj(1), MatchClass.PREFIX, cost = 0.1).betterThan(Candidate(proj(1), MatchClass.PREFIX, cost = 0.5)))
        val rows = listOf(proj(1, merge = 7), proj(2), proj(3, merge = 7), proj(4, merge = 8))
        assertEquals(listOf(1, 2, 4), Ranking.dedupeMerged(rows, Script.SIMPLIFIED) { it }.map { it.id })
        assertEquals(listOf(1, 2, 3, 4), Ranking.dedupeMerged(rows, Script.TRADITIONAL) { it }.map { it.id })
    }
}

class DecompositionGeometryTest {
    private val full = Region(0f, 0f, 1f, 1f)

    @Test
    fun positionsAndRegions() {
        assertEquals(PartPosition.LEFT, Ids.position(0x2FF0, 0))
        assertEquals(PartPosition.BOTTOM, Ids.position(0x2FF1, 1))
        assertEquals(PartPosition.MIDDLE, Ids.position(0x2FF3, 1))
        assertEquals(PartPosition.INNER, Ids.position(0x2FF4, 1))
        assertEquals(PartPosition.PART, Ids.position(0x2FFB, 0))
        assertEquals(Region(0.5f, 0f, 1f, 1f), Ids.region(0x2FF0, 1, full).first)
        assertEquals(Region(0f, 0.5f, 1f, 1f), Ids.region(0x2FF1, 1, full).first)
        val (outer, cut) = Ids.region(0x2FF4, 0, full)
        assertEquals(full, outer)
        assertEquals(Region(0.25f, 0.25f, 0.75f, 0.75f), cut)
        assertEquals(cut, Ids.region(0x2FF4, 1, full).first)
        // Recursive: the right half's top half.
        assertEquals(Region(0.5f, 0f, 1f, 0.5f), Ids.region(0x2FF1, 0, Ids.region(0x2FF0, 1, full).first).first)
        assertEquals(full, Ids.region(0x2FFB, 1, full).first)
    }

    @Test
    fun idsParsing() {
        val t = assertNotNull(Ids.parse("⿱⿰木木土"))
        assertEquals(0x2FF1, t.op)
        assertEquals("⿰木木", t.children[0].text)
        assertEquals("土", t.children[1].leaf)
        assertEquals(listOf("⿰", "&U-i001;", "〾木"), Ids.tokens("⿰&U-i001;〾木"))
        assertEquals(3, Ids.parse("⿲彳亍亍")!!.children.size)
        assertNotNull(Ids.parse("木")!!.leaf)
    }
}
