package com.szdict.app

import com.szdict.app.platform.consoleLog
import com.szdict.core.api.DictMode
import com.szdict.core.api.Script
import com.szdict.core.api.SearchResult
import kotlin.time.TimeSource

/**
 * `?selftest=1`: runs the shared engine in the browser — search, entry detail, text analysis, HSK,
 * decomposition and examples in both modes — and logs one `[selftest]` line per check, ending with
 * `[selftest] done ok=<n> failed=<n>`. A smoke test of the store worker and the Wasm engine.
 */
internal object SelfTest {
    private var ok = 0
    private var failed = 0

    private suspend fun check(name: String, block: suspend () -> String) {
        val mark = TimeSource.Monotonic.markNow()
        try {
            val detail = block()
            ok++
            consoleLog("[selftest] ok $name (${mark.elapsedNow().inWholeMilliseconds} ms): $detail")
        } catch (e: Throwable) {
            failed++
            consoleLog("[selftest] FAILED $name: ${e::class.simpleName}: ${e.message}")
        }
    }

    private fun top(result: SearchResult): String {
        val side = if (result.chinese.isEmpty) result.english else result.chinese
        val first = side.sections.firstOrNull()
        val head = first?.cards?.firstOrNull()?.headword ?: first?.groups?.firstOrNull()?.text
            ?: first?.combinations?.firstOrNull()?.words?.joinToString("") { it.headword }
        require(head != null) { "no results" }
        return "${side.side} count=${side.count} first=$head"
    }

    /** Combination rows as text: slots by spaces, alternatives by `/`, rows by ` | ` (SRCH-065). */
    private fun combinationRows(result: SearchResult): String =
        result.chinese.sections.firstOrNull { it.kind == com.szdict.core.api.SectionKind.COMBINATIONS }?.combinations.orEmpty()
            .joinToString(" | ") { row -> row.slots.joinToString(" ") { slot -> slot.alternatives.joinToString("/") { it.headword } } }

    private fun firstCard(result: SearchResult) = result.chinese.sections.firstNotNullOf { section ->
        section.cards.firstOrNull() ?: section.groups.firstNotNullOfOrNull { it.cards.firstOrNull() }
    }

    suspend fun run(session: AppSession) {
        for (mode in DictMode.entries) {
            val script = if (mode == DictMode.CANTONESE) Script.TRADITIONAL else Script.SIMPLIFIED
            val tag = mode.datasetName
            var dict: com.szdict.core.api.Dictionary? = null
            check("$tag open") {
                val d = session.dictionary(mode)
                dict = d
                val info = d.info()
                "schema=${info.schemaVersion} version=${info.datasetVersion}"
            }
            val d = dict ?: continue
            val romanised = if (mode == DictMode.CANTONESE) "nei hou" else "nihao"
            check("$tag search $romanised") { top(d.search(romanised, script)) }
            check("$tag search 你好") { top(d.search("你好", script)) }
            check("$tag search hello") { top(d.search("hello", script)) }
            check("$tag combinations") {
                // The language model is fetched on first need (ARCH-143): ask again until it has arrived.
                val q = if (mode == DictMode.CANTONESE) "ngo hai jat g" else "jiu dian zhong"
                var rows = ""
                for (attempt in 0 until 60) {
                    rows = combinationRows(d.search(q, script))
                    if (rows.isNotEmpty()) break
                    kotlinx.coroutines.delay(500)
                }
                require(rows.isNotEmpty()) { "no combinations for $q" }
                "$q -> $rows"
            }
            check("$tag combination timings") {
                val queries = if (mode == DictMode.CANTONESE) listOf("nh", "ngo5 zung1 ji3 nei5", "ngo hai jat g")
                else listOf("nh", "ruan xing tiao zheng", "wo shi yi g", "wo jiu dian qu")
                val out = ArrayList<String>()
                for (q in queries) {
                    for (i in 0 until 2) d.search(q, script)
                    val mark = kotlin.time.TimeSource.Monotonic.markNow()
                    for (i in 0 until 10) d.search(q, script)
                    out.add("$q ${mark.elapsedNow().inWholeMicroseconds / 10 / 1000.0} ms")
                }
                out.joinToString("; ")
            }
            check("$tag search sentence") { top(d.search(if (mode == DictMode.CANTONESE) "我哋今日去邊度食飯" else "我们今天去哪里吃饭", script)) }
            check("$tag entry detail") {
                val card = firstCard(d.search("你好", script))
                val detail = d.entryDetail(card.id, script)
                require(detail.card.definitions.isNotEmpty()) { "no definitions" }
                "${detail.card.headword} ${detail.card.reading.display} — ${detail.card.definitions.first()} (related ${detail.related.size})"
            }
            check("$tag examples") {
                val card = firstCard(d.search("你好", script))
                "count=${d.examples(card.id, script).items.size}"
            }
            check("$tag decompose") {
                val parts = d.decompose(listOf("好"), script)
                require(parts.isNotEmpty()) { "no decomposition" }
                "characters=${parts.size}"
            }
            check("$tag analyse") {
                val passage = d.analyse(if (mode == DictMode.CANTONESE) "佢哋而家喺香港。" else "他们现在在深圳工作。")
                require(passage.segments.isNotEmpty()) { "no segments" }
                "segments=${passage.segments.size}"
            }
            check("$tag hsk") {
                val levels = d.hskLevels()
                val cards = d.hsk(1..1, script)
                require(levels.isNotEmpty() && cards.isNotEmpty()) { "no HSK data" }
                "levels=${levels.size} level1=${cards.size}"
            }
            check("$tag popular") { "count=${d.popular(script).size}" }
        }
        consoleLog("[selftest] done ok=$ok failed=$failed")
    }
}
