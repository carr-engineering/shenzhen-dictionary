package com.szdict.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.szdict.core.api.DictError
import com.szdict.core.api.DictMode
import com.szdict.core.api.DictResult
import com.szdict.core.api.Dictionary
import com.szdict.core.api.DictionaryFactory
import com.szdict.core.api.PartKind
import com.szdict.core.api.PartNode
import com.szdict.core.api.Script
import com.szdict.core.api.dictResult
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PARTS tab engine (`characterParts`, `appearsIn`): a fixture dataset with a hand-written decomposition pack, then the real data. */
class PartsTest {
    private val s = Script.SIMPLIFIED
    private val t = Script.TRADITIONAL

    private fun fixture(): Fixture = Fixture().apply {
        entry("好", "好", "hao3", "good; well", 15, 300000)
        entry("你", "你", "ni3", "you (informal)", 3, 900000)
        entry("女", "女", "nu:3", "female; woman", 300, 50000)
        entry("子", "子", "zi3", "son; child", 200, 60000)
        entry("媽", "妈", "ma1", "mum; mother", 900, 5000)
        entry("她", "她", "ta1", "she", 40, 200000)
        entry("孬", "孬", "nao1", "bad; cowardly", 30000, 10)
        entry("亻", "亻", "ren2", "\"person\" radical in Chinese characters", 60000, 1)
        entry("贏", "赢", "ying2", "to beat; to win", 1200, 3000)
        entry("貝", "贝", "bei4", "cowrie; shell", 4000, 900)
        entry("月", "月", "yue4", "moon; month", 250, 40000)
    }

    private val decompRows = listOf(
        // ch, ids, parts (JSON), radical, strokes, name
        listOf("好", "⿰女子", "[\"女\",\"子\"]", "女", 6, null),
        listOf("你", "⿰亻尔", "[\"亻\",\"尔\"]", "人", 7, null),
        listOf("尔", "⿱⺈小", "[\"⺈\",\"小\"]", "小", 5, null),
        listOf("女", "", "[]", "女", 3, "woman"),
        listOf("子", "", "[]", "子", 3, "child"),
        listOf("亻", "", "[]", "人", 2, "person (left form)"),
        listOf("⺈", "", "[]", "刀", 2, "knife (top form)"),
        listOf("小", "", "[]", "小", 3, "small"),
        listOf("妈", "⿰女马", "[\"女\",\"马\"]", "女", 6, null),
        listOf("媽", "⿰女馬", "[\"女\",\"馬\"]", "女", 13, null),
        listOf("她", "⿰女也", "[\"女\",\"也\"]", "女", 6, null),
        listOf("㚢", "⿰女乚", "[\"女\",\"乚\"]", "女", 4, null),
        listOf("孬", "⿱不好", "[\"不\",\"好\"]", "子", 10, null),
        listOf("赢", "⿱吂⿲月贝凡", "[\"吂\",\"⿲月贝凡\"]", "贝", 17, null),
        listOf("吂", "⿱亡口", "[\"亡\",\"口\"]", "口", 6, null),
        listOf("贝", "", "[]", "贝", 4, "shell"),
        listOf("月", "", "[]", "月", 4, "moon / meat"),
        listOf("凡", "⿻几丶", "[\"几\",\"丶\"]", "几", 3, null),
        listOf("&U-i001;", "", "[]", null, null, "bottom of 廌焉舃"),
    )

    /** The fixture plus `packs/decomposition.db`; the reverse index is written the way the pipeline writes it. */
    private fun build(withPartOf: Boolean = true, withPack: Boolean = true): File {
        val dir = fixture().build()
        if (!withPack) return dir
        File(dir, "packs").mkdirs()
        val db = BundledSQLiteDriver().open(File(dir, "packs/decomposition.db").absolutePath)
        db.execSQL("CREATE TABLE decomp(ch TEXT PRIMARY KEY, ids TEXT, parts TEXT, radical TEXT, strokes INTEGER, name TEXT, svg TEXT, matches TEXT) WITHOUT ROWID")
        val users = sortedMapOf<String, java.util.TreeSet<String>>()
        for (r in decompRows) {
            val st = db.prepare("INSERT INTO decomp VALUES(?,?,?,?,?,?,NULL,NULL)")
            r.forEachIndexed { i, v -> if (v == null) st.bindNull(i + 1) else if (v is Int) st.bindLong(i + 1, v.toLong()) else st.bindText(i + 1, v.toString()) }
            st.step(); st.close()
            val ch = r[0] as String
            Regex("&[^;]+;|.", RegexOption.DOT_MATCHES_ALL).findAll(r[1] as String).map { it.value }
                .filter { it.codePointAt(0) !in 0x2FF0..0x2FFF && it != "？" && it != ch }
                .forEach { users.getOrPut(it) { java.util.TreeSet() }.add(ch) }
        }
        if (withPartOf) {
            db.execSQL("CREATE TABLE part_of(part TEXT PRIMARY KEY, chars TEXT NOT NULL) WITHOUT ROWID")
            for ((part, chars) in users) {
                val st = db.prepare("INSERT INTO part_of VALUES(?,?)")
                st.bindText(1, part); st.bindText(2, chars.joinToString(""))
                st.step(); st.close()
            }
        }
        db.execSQL("PRAGMA user_version = 1")
        db.close()
        return dir
    }

    private fun with(dir: File, mode: DictMode = DictMode.MANDARIN, block: suspend (Dictionary) -> Unit) = runBlocking {
        val dict = DictionaryFactory.open(dir.absolutePath, mode)
        try { block(dict) } finally { dict.close() }
    }

    private fun PartNode.glyphs() = children.map { it.node.glyph }

    @Test
    fun madeOfMarksRadicalsAndRecursesToIndivisibleParts() = with(build()) { dict ->
        val (hao, ni, nope) = dict.characterParts(listOf("好", "你", "龘"), s)
        assertEquals("女", hao.radical)
        val root = assertNotNull(hao.root)
        assertEquals("⿰", root.node.operator)
        assertEquals(listOf("女", "子"), root.glyphs())
        val (nu, zi) = root.children
        assertTrue(nu.isRadical && nu.isParentRadical, "女 is the radical of 好")
        assertTrue(zi.isRadical && !zi.isParentRadical)
        assertEquals("nǚ", nu.node.card?.reading?.display)
        assertEquals(0, nu.partCount)
        // 你: 亻 is a form of 人, the radical of 你; 尔 decomposes one level further.
        val niRoot = assertNotNull(ni.root)
        val (ren, er) = niRoot.children
        assertTrue(ren.isRadical && ren.isParentRadical)
        assertFalse(er.isRadical || er.isParentRadical)
        assertEquals(2, er.partCount)
        assertEquals(listOf("⺈", "小"), er.glyphs())
        assertTrue(er.children[1].isParentRadical, "小 is the radical of 尔")
        assertEquals(PartKind.RADICAL_FORM, er.children[0].node.kind)
        assertNull(er.node.card, "尔 has no entry in the fixture")
        assertFalse(nope.available)
        assertNull(nope.root)
    }

    @Test
    fun inlineGroupsKeepTheirCharactersRadical() = with(build()) { dict ->
        val ying = assertNotNull(dict.characterParts(listOf("赢"), s).single().root)
        assertEquals(listOf("吂", "⿲月贝凡"), ying.glyphs())
        val group = ying.children[1]
        assertTrue(group.isGroup && !group.isRadical && !group.isParentRadical)
        assertEquals(listOf("月", "贝", "凡"), group.glyphs())
        assertTrue(group.children[1].isParentRadical, "贝, inside the group, is the radical of 赢")
        assertFalse(group.children[0].isParentRadical)
        assertEquals(listOf("几", "丶"), group.children[2].glyphs())
        assertFalse(ying.children[0].isGroup)
    }

    @Test
    fun appearsInFollowsTheScriptAndTheProjectionOrder() = with(build()) { dict ->
        val simp = dict.appearsIn("女", s)
        // Projection order: frequency rank (好 15, 她 40, 妈 900), the entry-less 㚢 last.
        assertEquals(listOf("好", "她", "妈", "㚢"), simp.items.map { it.character })
        assertEquals(4, simp.total)
        assertNull(simp.items.last().card, "㚢 has no entry: listed last, not tappable")
        assertEquals("hǎo", simp.items[0].card?.reading?.display)
        assertEquals(listOf("好", "她", "媽", "㚢"), dict.appearsIn("女", t).items.map { it.character })
        assertEquals("媽", dict.appearsIn("女", t).items[2].card?.headword)
        // Paging.
        val page = dict.appearsIn("女", s, offset = 1, limit = 2)
        assertEquals(listOf("她", "妈"), page.items.map { it.character })
        assertEquals(4, page.total)
        assertTrue(page.hasMore)
        assertFalse(dict.appearsIn("女", s, offset = 2, limit = 2).hasMore)
        assertTrue(dict.appearsIn("女", s, offset = 10).items.isEmpty())
        // Direct components only: 孬 = ⿱不好 is listed under 好, not under 女.
        assertEquals(listOf("孬"), dict.appearsIn("好", s).items.map { it.character })
        // Parts of an inline group count as direct parts of the character.
        assertEquals(listOf("赢"), dict.appearsIn("贝", s).items.map { it.character })
        assertEquals(0, dict.appearsIn("她", s).total)
        assertEquals(0, dict.appearsIn("龘", s).total)
    }

    @Test
    fun missingPackOrIndexIsAnAssetError() {
        with(build(withPartOf = false)) { dict ->
            assertTrue(dict.characterParts(listOf("好"), s).single().available, "the tree does not need the index")
            assertTrue((dictResult { dict.appearsIn("女", s) } as DictResult.Err).error is DictError.AssetMissing)
        }
        with(build(withPack = false)) { dict ->
            assertTrue((dictResult { dict.characterParts(listOf("好"), s) } as DictResult.Err).error is DictError.AssetMissing)
            assertTrue((dictResult { dict.appearsIn("女", s) } as DictResult.Err).error is DictError.AssetMissing)
        }
    }

    // ---- the real datasets (skipped while pipeline/out is absent) ----

    private val realDir: String? = System.getProperty("szdict.datasetDir")
        ?.takeIf { File(it, "mandarin.db").isFile && File(it, "packs/decomposition.db").isFile }

    private fun real(mode: DictMode = DictMode.MANDARIN, block: suspend (Dictionary) -> Unit) {
        val d = realDir?.takeIf { File(it, "${mode.datasetName}.db").isFile } ?: return println("SKIPPED: no ${mode.datasetName}.db / decomposition pack")
        with(File(d), mode, block)
    }

    @Test
    fun realMadeOf() = real { dict ->
        val (hao, ni) = dict.characterParts(listOf("好", "你"), s)
        val h = assertNotNull(hao.root)
        assertEquals(listOf("女", "子"), h.glyphs())
        assertTrue(h.children[0].isParentRadical && h.children[0].isRadical)
        assertTrue(h.children.all { it.node.card != null && it.partCount == 0 })
        assertTrue(assertNotNull(ni.root).children[0].isParentRadical, "亻 in 你")
        val ying = assertNotNull(dict.characterParts(listOf("赢"), s).single().root)
        assertEquals("吂", ying.children[0].node.glyph)
        val group = ying.children[1]
        assertTrue(group.isGroup)
        assertEquals(listOf("月", "贝", "凡"), group.glyphs())
        assertTrue(group.children[1].isParentRadical)
        // 懂 = ⿰忄董, and 董 goes on down to radicals.
        val dong = assertNotNull(dict.characterParts(listOf("懂"), s).single().root)
        assertEquals(listOf("忄", "董"), dong.glyphs())
        assertTrue(dong.children[0].isParentRadical)
        fun leaves(n: PartNode): List<PartNode> = if (n.children.isEmpty()) listOf(n) else n.children.flatMap(::leaves)
        assertTrue(dong.children[1].partCount >= 2)
        assertTrue(leaves(dong).all { it.node.isRoot })
    }

    @Test
    fun realAppearsIn() = real { dict ->
        val nu = dict.appearsIn("女", s, 0, 30)
        val chars = nu.items.map { it.character }
        assertTrue(nu.total > 100, "女 builds hundreds of characters: ${nu.total}")
        assertTrue("好" in chars.take(5) && "她" in chars.take(5), chars.take(10).toString())
        assertTrue("媽" !in chars)
        assertEquals(chars.size, chars.toSet().size)
        assertTrue(nu.items.all { it.card != null }, "the first page has entries")
        // All pages together cover the total once.
        var offset = 0
        val seen = HashSet<String>()
        while (true) {
            val p = dict.appearsIn("女", s, offset, 200)
            p.items.forEach { assertTrue(seen.add(it.character)) }
            offset += p.items.size
            if (!p.hasMore) break
        }
        assertEquals(nu.total, seen.size)
        assertTrue("媽" in dict.appearsIn("女", t, 0, 30).items.map { it.character })
        assertTrue("孬" in dict.appearsIn("好", s).items.map { it.character })

        suspend fun time(label: String, block: suspend () -> Unit) {
            val start = System.nanoTime()
            block()
            println("TIMING parts $label: %.1f ms".format((System.nanoTime() - start) / 1e6))
        }
        time("appearsIn 口 cold") { dict.appearsIn("口", t, 0, 30) }
        time("appearsIn 口 warm") { dict.appearsIn("口", t, 30, 30) }
        time("characterParts 懂赢") { dict.characterParts(listOf("懂", "赢"), s) }
    }

    @Test
    fun realCantonese() = real(DictMode.CANTONESE) { dict ->
        val items = dict.appearsIn("女", t).items.map { it.character }
        assertTrue("媽" in items && "妈" !in items, items.toString())
        assertTrue(assertNotNull(dict.characterParts(listOf("好"), t).single().root).children[0].isParentRadical)
    }
}
