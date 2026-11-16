package com.szdict.core

import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.szdict.core.api.DictMode
import com.szdict.core.api.DictionaryFactory
import com.szdict.core.api.Script
import com.szdict.core.api.SectionKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Quality gate of the combinations (SRCH-060…068, PIPE-075): the evaluation set of
 * contract/combinations-eval.json run through the real engine against the full Mandarin dataset
 * (pipeline/out), with the thresholds of contract/lm-quality.json `combinations`. Skipped while the
 * pipeline output is absent.
 *
 * Measures, on the `test` split: phrase recall at 1 / 3 / 5 of the flat ranked list (the kept
 * sequences in rank order, at most five), the stress set (numerals and measure words), English-like
 * inputs with any combination, single-word lookups with combinations and their rows "unattested"
 * (not a substring of any sentence of the sentences pack — a proxy for junk), and on all phrase
 * queries the share whose gold sequence is covered by a grouped row (SRCH-065) and the grouped rows.
 */
class CombinationQualityTest {
    private val out: File? = System.getProperty("szdict.datasetDir")?.let(::File)?.takeIf { File(it, "mandarin.db").isFile }
    private val contract: File? = System.getProperty("szdict.datasetDir")?.let(::File)?.parentFile?.parentFile?.resolve("contract")?.takeIf { it.isDirectory }

    private class Shown(val flat: List<String>, val grouped: List<List<List<String>>>)

    private class Acc {
        var n = 0; var r1 = 0; var r3 = 0; var r5 = 0; var any = 0; var rows = 0; var unatt = 0; var covered = 0; var gRows = 0
        fun pct(x: Int) = 100.0 * x / maxOf(1, n)
        fun avg(x: Int) = x.toDouble() / maxOf(1, n)
    }

    private fun corpus(dir: File): String {
        val f = File(dir, "packs/sentences.db").takeIf { it.isFile } ?: return ""
        val conn = BundledSQLiteDriver().open(f.absolutePath, SQLITE_OPEN_READONLY)
        val sb = StringBuilder()
        try {
            val st = conn.prepare("SELECT zh FROM sentence")
            while (st.step()) sb.append(st.getText(0)).append('\n')
            st.close()
        } finally {
            conn.close()
        }
        return sb.toString()
    }

    private fun covers(row: List<List<String>>, zh: String): Boolean {
        var pos = setOf(0)
        for (slot in row) pos = pos.flatMapTo(HashSet()) { p -> slot.filter { zh.startsWith(it, p) }.map { p + it.length } }
        return zh.length in pos
    }

    @Test
    fun combinationQuality() {
        val dir = out ?: return println("SKIPPED: no mandarin.db under szdict.datasetDir")
        val c = contract ?: return println("SKIPPED: contract/ not found")
        val eval = Json.parseToJsonElement(File(c, "combinations-eval.json").readText()).jsonObject
        val gate = Json.parseToJsonElement(File(c, "lm-quality.json").readText()).jsonObject["combinations"]?.jsonObject
        val text = corpus(dir)
        val attested = HashMap<String, Boolean>()
        fun att(s: String) = attested.getOrPut(s) { text.isEmpty() || s in text }
        runBlocking {
            val dict = DictionaryFactory.open(dir.absolutePath, DictMode.MANDARIN)
            val cache = HashMap<String, Shown>()
            suspend fun shown(q: String): Shown = cache.getOrPut(q) {
                val r = dict.search(q, Script.SIMPLIFIED)
                val combos = r.chinese.sections.firstOrNull { it.kind == SectionKind.COMBINATIONS }?.combinations.orEmpty()
                Shown(combos.map { cb -> cb.words.joinToString("") { it.headword } }, combos.map { cb -> cb.words.map { listOf(it.headword) } })
            }
            try {
                val phrase = Acc(); val word = Acc(); val neg = Acc(); val stress = Acc(); val allPhrase = Acc()
                for (it in eval["items"]!!.jsonArray) {
                    val o = it.jsonObject
                    val py = o["py"]!!.jsonArray.map { p -> p.jsonPrimitive.content }
                    val zh = o["zh"]!!.jsonPrimitive.content
                    val isWord = o["word"]!!.jsonPrimitive.boolean
                    val test = o["split"]!!.jsonPrimitive.content == "test"
                    if (!isWord && py.size < 2) continue
                    for (q in listOf(py.joinToString(" "), py.joinToString(""))) {
                        val s = shown(q)
                        if (!isWord) {
                            allPhrase.n++
                            allPhrase.gRows += s.grouped.size
                            if (s.grouped.any { row -> covers(row, zh) }) allPhrase.covered++
                        }
                        if (!test) continue
                        val a = if (isWord) word else phrase
                        a.n++
                        a.any += if (s.flat.isNotEmpty()) 1 else 0
                        a.rows += s.flat.size
                        a.unatt += s.flat.count { !att(it) }
                        if (zh in s.flat.take(1)) a.r1++
                        if (zh in s.flat.take(3)) a.r3++
                        if (zh in s.flat.take(5)) a.r5++
                    }
                }
                for (q in eval["neg"]!!.jsonArray) {
                    val s = shown(q.jsonPrimitive.content)
                    neg.n++
                    neg.any += if (s.flat.isNotEmpty()) 1 else 0
                    neg.rows += s.flat.size
                }
                for (it in eval["stress"]!!.jsonArray) {
                    val o = it.jsonObject
                    if (o["word"]!!.jsonPrimitive.boolean) continue
                    val py = o["py"]!!.jsonArray.map { p -> p.jsonPrimitive.content }
                    val zh = o["zh"]!!.jsonPrimitive.content
                    for (q in listOf(py.joinToString(" "), py.joinToString(""))) {
                        val s = shown(q)
                        stress.n++
                        if (zh in s.flat.take(1)) stress.r1++
                        if (zh in s.flat.take(3)) stress.r3++
                        if (zh in s.flat.take(5)) stress.r5++
                    }
                }
                val m = linkedMapOf(
                    "phrase_r1" to phrase.pct(phrase.r1), "phrase_r3" to phrase.pct(phrase.r3), "phrase_r5" to phrase.pct(phrase.r5),
                    "phrase_rows" to phrase.avg(phrase.rows), "phrase_unattested_rows" to phrase.avg(phrase.unatt),
                    "word_with_combinations" to word.pct(word.any), "word_rows" to word.avg(word.rows), "word_unattested_rows" to word.avg(word.unatt),
                    "negatives_with_combinations" to neg.pct(neg.any), "negative_rows" to neg.avg(neg.rows),
                    "stress_r1" to stress.pct(stress.r1), "stress_r3" to stress.pct(stress.r3), "stress_r5" to stress.pct(stress.r5),
                    "grouped_gold_covered" to allPhrase.pct(allPhrase.covered), "grouped_rows" to allPhrase.avg(allPhrase.gRows),
                )
                println("COMBINATION QUALITY (test split: ${phrase.n} phrase queries, ${word.n} single-word queries, ${neg.n} negatives, ${stress.n} stress queries; grouped on ${allPhrase.n} phrase queries)")
                for ((k, v) in m) println("  %-28s %7.2f".format(k, v))
                gate ?: return@runBlocking
                val fails = ArrayList<String>()
                for ((k, lim) in gate["min"]?.jsonObject.orEmpty()) if (m[k]!! < lim.jsonPrimitive.double) fails.add("$k ${"%.2f".format(m[k])} < ${lim.jsonPrimitive.double}")
                for ((k, lim) in gate["max"]?.jsonObject.orEmpty()) if (m[k]!! > lim.jsonPrimitive.double) fails.add("$k ${"%.2f".format(m[k])} > ${lim.jsonPrimitive.double}")
                assertTrue(fails.isEmpty(), "combination quality gate: $fails")
            } finally {
                dict.close()
            }
        }
    }

    @Suppress("unused")
    private fun JsonObject?.orEmpty(): Map<String, kotlinx.serialization.json.JsonElement> = this ?: emptyMap()
}
