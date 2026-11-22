package com.szdict.core

import com.szdict.core.api.DictMode
import com.szdict.core.api.Dictionary
import com.szdict.core.api.DictionaryFactory
import com.szdict.core.api.EntryCard
import com.szdict.core.api.Romanisation
import com.szdict.core.api.Script
import com.szdict.core.api.SectionKind
import com.szdict.core.api.SegmentationMode
import com.szdict.core.engine.Normaliser
import com.szdict.core.engine.codePointAtIndex
import com.szdict.core.engine.isHanzi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shared test vectors of contract/ (ARCH-154, PIPE-141): the normalisation rules pinned for both
 * the pipeline and the app, and the golden-query suite (PIPE-140) run through the public facade
 * against the fixture dataset and, when present, the full datasets. Skipped when the files are absent.
 */
class ContractVectorsTest {
    private val out: File? = System.getProperty("szdict.datasetDir")?.let(::File)
    private val contract: File? = out?.parentFile?.parentFile?.resolve("contract")?.takeIf { it.isDirectory }

    private fun load(name: String): JsonObject? =
        contract?.resolve(name)?.takeIf { it.isFile }?.let { Json.parseToJsonElement(it.readText()).jsonObject }
            ?: null.also { println("SKIPPED: contract/$name not found") }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonElement.strings(): List<String> = jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun normalisationVectors() {
        val v = load("normalisation-vectors.json") ?: return
        for (c in v["matching_key"]!!.jsonArray) assertEquals(c.jsonObject.str("out"), Normaliser.matchingKey(c.jsonObject.str("in")), "matching_key ${c.jsonObject.str("in")}")
        for (c in v["display_key"]!!.jsonArray) assertEquals(c.jsonObject.str("out"), Normaliser.displayText(c.jsonObject.str("in")), "display_key")
        for (c in v["en_tokens"]!!.jsonArray) assertEquals(c.jsonObject["out"]!!.strings(), Normaliser.englishTokens(c.jsonObject.str("in")), "en_tokens ${c.jsonObject.str("in")}")
        for (c in v["is_hanzi"]!!.jsonArray) assertEquals(c.jsonObject["out"]!!.jsonPrimitive.boolean, isHanzi(c.jsonObject.str("in").codePointAtIndex(0)), "is_hanzi ${c.jsonObject.str("codepoint")}")
        var checked = 0
        for (c in v["pinyin"]!!.jsonArray) {
            val o = c.jsonObject
            if (o["full_parse"]?.jsonPrimitive?.boolean != true) continue
            // GLB-015 tone-mark placement and the canonical → syllables/tones split the engine relies on.
            assertEquals(o.str("display"), Romanisation.pinyinToMarked(o.str("in")), "pinyin display of ${o.str("in")}")
            val (syl, tones) = Romanisation.parseCanonical(o.str("canonical"))
            assertEquals(o["syllables"]!!.strings(), syl, "syllables of ${o.str("canonical")}")
            assertEquals(o["tones"]!!.jsonArray.map { it.jsonPrimitive.int }, tones)
            checked++
        }
        for (c in v["jyutping"]!!.jsonArray) {
            val o = c.jsonObject
            assertEquals(o.str("display"), Romanisation.display(DictMode.CANTONESE, o.str("canonical")))
            assertEquals(o["syllables"]!!.strings(), Romanisation.parseCanonical(o.str("canonical")).first)
        }
        assertTrue(checked > 10)
    }

    private fun keys(a: JsonElement?): List<String> = (a as? JsonArray)?.map { it.jsonObject.str("key") } ?: emptyList()

    private suspend fun allCards(dict: Dictionary, query: String, script: Script, vararg kinds: SectionKind): List<EntryCard> {
        val r = dict.search(query, script)
        val out = ArrayList<EntryCard>()
        for (s in r.chinese.sections + r.english.sections) {
            if (s.kind !in kinds) continue
            s.groups.forEach { out.addAll(it.cards) }
            out.addAll(if (s.refs.size > s.cards.size) dict.cards(s.refs, script) else s.cards)
        }
        return out
    }

    /** Natural keys of every entry shown by the cards, merge-group members included. */
    private suspend fun memberKeys(dict: Dictionary, cards: List<EntryCard>, script: Script): List<String> =
        cards.flatMap { c -> c.memberIds.map { id -> if (id == c.id) c.naturalKey else dict.entryDetail(id, script).naturalKey } }

    private fun goldens(which: String, block: suspend (case: JsonObject, expected: JsonElement, dict: Dictionary, id: String) -> Unit) {
        val g = load("golden-queries.json") ?: return
        val dir = (if (which == "fixture") out?.resolve("fixture") else out) ?: return
        var ran = 0
        runBlocking {
            for (mode in DictMode.entries) {
                if (!File(dir, "${mode.datasetName}.db").isFile) {
                    println("SKIPPED: $which ${mode.datasetName}.db not built")
                    continue
                }
                val dict = DictionaryFactory.open(dir.absolutePath, mode)
                try {
                    for (c in g["cases"]!!.jsonArray) {
                        val o = c.jsonObject
                        if (o.str("mode") != mode.datasetName) continue
                        val expected = o[which] ?: continue
                        block(o, expected, dict, "${o.str("id")} [$which]")
                        ran++
                    }
                } finally {
                    dict.close()
                }
            }
        }
        println("GOLDEN $which: $ran cases checked")
    }

    private suspend fun check(o: JsonObject, expected: JsonElement, dict: Dictionary, id: String) {
        val main = if (dict.mode == DictMode.MANDARIN) Script.SIMPLIFIED else Script.TRADITIONAL
        val other = if (main == Script.SIMPLIFIED) Script.TRADITIONAL else Script.SIMPLIFIED
        val query = o["query"]?.jsonPrimitive?.content ?: ""
        when (o.str("type")) {
            "headword_exact" -> {
                val cards = allCards(dict, query, main, SectionKind.GROUPS, SectionKind.MATCHES).filter { it.matchedAlt == null }
                val actual = memberKeys(dict, dict.search(query, main).let { r ->
                    r.chinese.sections.firstOrNull { it.kind == SectionKind.GROUPS }?.groups?.firstOrNull()?.cards
                        ?: r.chinese.sections.firstOrNull { it.kind == SectionKind.MATCHES }?.cards.orEmpty().filter { c -> c.reading.syllables.size <= 1 || true }
                }, main)
                assertEquals(keys(expected).toSet(), actual.toSet(), "$id members")
                if (dict.mode == DictMode.MANDARIN) {
                    // Card order is the canonical order of the stored group (SRCH-075).
                    val own = cards.map { it.naturalKey }.filter { it in keys(expected) }
                    assertEquals(keys(expected).filter { it in own }, own.distinct().filter { it in keys(expected) }, "$id order")
                }
            }
            "reading_exact" -> {
                val want = keys(expected)
                val a = allCards(dict, query, main, SectionKind.MATCHES, SectionKind.MORE_MATCHES)
                val b = allCards(dict, query, other, SectionKind.MATCHES, SectionKind.MORE_MATCHES)
                val have = (a + b).map { it.naturalKey }.toSet()
                assertTrue(have.containsAll(want), "$id: missing ${want - have}")
                // Golden order inside each tier. The app lists Unicode-only entries after every dictionary
                // entry — alternative-reading matches included (SRCH-079) — so the tiers are compared separately.
                for (tier in listOf(false, true)) {
                    val order = a.filter { it.isUnihanOnly == tier }.map { it.naturalKey }.filter { it in want }
                    assertEquals(want.filter { it in order }, order, "$id order (unihan=$tier)")
                }
                val alts = expected.jsonArray.filter { it.jsonObject["alt"]!!.jsonPrimitive.int != 0 }.map { it.jsonObject.str("key") }
                for (c in a) if (c.naturalKey in alts && c.naturalKey !in want.filter { k -> k !in alts }) assertTrue(c.matchedAlt != null, "$id: ${c.headword} shows its alternative reading")
            }
            "combination" -> {
                val combos = dict.search(query, main).chinese.sections.firstOrNull { it.kind == SectionKind.COMBINATIONS }?.combinations.orEmpty()
                val want = expected.strings()
                if (dict.info().hasBigramModel) assertEquals(want, combos.firstOrNull()?.words?.map { it.headword }, id)
                else assertTrue(combos.any { c -> c.words.map { it.headword } == want }, "$id (unigram-only model): ${combos.map { c -> c.words.map { it.headword } }}")
            }
            "english" -> {
                val have = allCards(dict, query, main, SectionKind.ENGLISH, SectionKind.ENGLISH_CC_CANTO, SectionKind.ENGLISH_CC_CEDICT, SectionKind.ENGLISH_UNIHAN)
                val haveKeys = memberKeys(dict, have, main).toSet()
                assertTrue(haveKeys.containsAll(keys(expected)), "$id: missing ${keys(expected) - haveKeys}")
            }
            "segmentation" -> {
                val p = dict.analyse(query, SegmentationMode.HANZI)
                // The bigram lattice when the dataset carries the model (SCHEMA note 20), else the unigram DAG, else greedy.
                val info = dict.info()
                val strategy = if (!info.hasFrequencyData) "greedy" else if (info.hasBigramModel && "lattice" in expected.jsonObject) "lattice" else "dag"
                val want = expected.jsonObject[strategy]!!.strings()
                assertEquals(want, p.segments.map { query.substring(it.start, it.end) }, id)
            }
            "merge_group" -> {
                val script = if (o["script"]!!.jsonPrimitive.int == 0) Script.SIMPLIFIED else Script.TRADITIONAL
                val cards = dict.search(query, script).chinese.sections.first { it.kind == SectionKind.GROUPS }.groups[0].cards
                for (g in expected.jsonArray) {
                    val members = keys(g.jsonObject["members"])
                    val card = cards.first { it.naturalKey in members }
                    assertEquals(members.toSet(), memberKeys(dict, listOf(card), script).toSet(), "$id members")
                    val forms = if (script == Script.SIMPLIFIED) {
                        g.jsonObject["members"]!!.jsonArray.map { it.jsonObject.str("trad") }.distinct()
                            .takeUnless { it == listOf(card.headword) }.orEmpty()
                    } else g.jsonObject["variants"]!!.strings().filter { it != card.headword }
                    assertEquals(forms, card.alternateForms, "$id variants")
                    assertTrue(dict.entryDetail(card.id, script).merged)
                }
            }
            "redirect" -> for (r in expected.jsonArray) {
                val ro = r.jsonObject
                val d = dict.entryDetailByKey(ro["entry"]!!.jsonObject.str("key"), main)
                val redirect = d.members.first { it.entryId == d.id }.redirect
                val target = (ro["target"] as? JsonObject)?.str("key")
                if (redirect == null) {
                    // Suppressed only when the target is a member of the same merge group (SRCH-082).
                    assertTrue(d.merged && target != null && dict.idForKey(target) in d.card.memberIds, "$id: redirect missing")
                    continue
                }
                assertEquals(ro.str("kind"), redirect.kind.name.lowercase().removeSuffix("_variant").let { if (it == "old") "old_variant" else it }, "$id kind")
                assertEquals(ro["resolved"]!!.jsonPrimitive.boolean, redirect.resolved, "$id resolved")
                if (target != null) assertEquals(dict.idForKey(target), redirect.targetId, "$id target")
            }
            "hsk_entry" -> for (e in expected.jsonArray) {
                val eo = e.jsonObject
                val d = dict.entryDetailByKey(eo.str("key"), main)
                val rows = eo["hsk"]!!.jsonArray + eo["hsk_via"]!!.jsonArray
                val want = rows.groupBy { it.jsonObject["l"]!!.jsonPrimitive.int }.toSortedMap().map { (l, rs) -> l to rs.flatMap { it.jsonObject["pos"]!!.strings() }.distinct() }
                assertEquals(want, d.hskBadges.map { it.level to it.partsOfSpeech }, "$id badges")
                assertEquals(want.map { it.first }, d.card.hskLevels, "$id card levels")
            }
            "hsk_row" -> for (r in expected.jsonArray) {
                val ro = r.jsonObject
                val level = ro["level"]!!.jsonPrimitive.int
                val card = dict.hsk(level..level, main).first { ro["row_id"]!!.jsonPrimitive.int in it.rowIds }
                assertEquals(ro["entry"]!!.jsonObject.str("key"), card.card.naturalKey, "$id entry")
                assertEquals(level, card.level)
            }
            "hsk_chars" -> {
                val level = o["level"]!!.jsonPrimitive.int
                val got = dict.hskChars(level..level, main).take(expected.jsonArray.size)
                assertEquals(expected.jsonArray.map { it.jsonObject.str("ch") to it.jsonObject["entry"]!!.jsonObject.str("key") }, got.map { it.character to it.card.naturalKey }, id)
            }
            "within" -> {
                val cards = dict.search(query, main).chinese.sections.first { it.kind == SectionKind.GROUPS }.groups[0].cards
                for ((i, rows) in expected.jsonArray.withIndex()) {
                    val want = rows.jsonArray.map { r -> Triple(r.jsonArray[0].jsonPrimitive.int, r.jsonArray[1].jsonPrimitive.int, r.jsonArray[3].jsonPrimitive.content) }
                    val got = dict.insideWords(cards[i].id, Script.SIMPLIFIED).map { Triple(it.start, it.length, it.card.reading.canonical) }
                    // Same rows; order may differ only by parent-reading promotion inside one span (SRCH-104).
                    assertEquals(want.toSet(), got.toSet(), "$id rows")
                    assertEquals(want.map { it.first to it.second }, got.map { it.first to it.second }, "$id span order")
                    assertEquals(want.size, cards[i].insideCount, "$id count")
                }
            }
            "popular" -> assertEquals(expected.strings(), dict.popular(main).take(expected.jsonArray.size).map { it.headword }, id)
            "reading_rows" -> {
                val eo = expected.jsonObject
                val cards = dict.search(query, main).chinese.sections.first { it.kind == SectionKind.GROUPS }.groups[0].cards
                assertEquals(eo["entries"]!!.jsonPrimitive.int - eo["reading_rows"]!!.jsonPrimitive.int, cards.count { it.noReading }, id)
            }
            else -> {} // store-level cases (abbrev postings) have no facade equivalent
        }
    }

    @Test
    fun goldenQueriesOnFixtureDataset() = goldens("fixture", ::check)

    @Test
    fun goldenQueriesOnFullDataset() = goldens("full", ::check)
}
