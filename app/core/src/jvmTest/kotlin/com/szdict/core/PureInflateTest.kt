package com.szdict.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.szdict.core.engine.PureInflate
import com.szdict.core.engine.inflateRaw
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** The plain-Kotlin inflater used on Web must produce exactly what `java.util.zip` produces. */
class PureInflateTest {
    private fun deflate(data: ByteArray, level: Int, dictionary: ByteArray?, strategy: Int = Deflater.DEFAULT_STRATEGY): ByteArray {
        val d = Deflater(level, true)
        d.setStrategy(strategy)
        if (dictionary != null) d.setDictionary(dictionary)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    @Test
    fun syntheticStreams() {
        val rnd = Random(7)
        val words = listOf("\"defs\":[", "{\"g\":\"", "to be", "measure word", "的", "variant of ", "\"],", "(coll.)", "你好", "abbr. for ")
        val dictionary = words.joinToString("").encodeToByteArray()
        val inputs = ArrayList<ByteArray>()
        inputs += ByteArray(0)
        inputs += "a".encodeToByteArray()
        inputs += ByteArray(70_000) { 'x'.code.toByte() }
        inputs += rnd.nextBytes(5000)
        repeat(40) { inputs += buildString { repeat(rnd.nextInt(1, 400)) { append(words[rnd.nextInt(words.size)]) } }.encodeToByteArray() }
        for (data in inputs) {
            for (level in intArrayOf(0, 1, 6, 9)) {
                for (dict in listOf(null, dictionary)) {
                    val packed = deflate(data, level, dict)
                    assertContentEquals(data, PureInflate.inflate(packed, dict))
                }
            }
            assertContentEquals(data, PureInflate.inflate(deflate(data, 6, null, Deflater.HUFFMAN_ONLY)))
            // A fixed-Huffman stream (zlib chooses it for short inputs under Z_FIXED-like conditions).
            assertContentEquals(data, PureInflate.inflate(deflate(data, 9, dictionary, Deflater.FILTERED), dictionary))
        }
    }

    @Test
    fun realEntryBodies() {
        val dir = System.getProperty("szdict.datasetDir") ?: return println("SKIPPED: no szdict.datasetDir")
        var compared = 0
        for (name in listOf("mandarin.db", "cantonese.db", "fixture/mandarin.db")) {
            val file = File(dir, name).takeIf { it.isFile } ?: continue
            val conn = BundledSQLiteDriver().open(file.absolutePath, SQLITE_OPEN_READONLY)
            try {
                var dict: ByteArray? = null
                val hasDict = conn.prepare("SELECT 1 FROM sqlite_master WHERE name = 'body_dict'").use { it.step() }
                if (hasDict) conn.prepare("SELECT dict FROM body_dict ORDER BY id LIMIT 1").use { if (it.step()) dict = it.getBlob(0) }
                // Every 23rd entry: thousands of bodies of all sizes without reading the whole table.
                conn.prepare("SELECT body FROM entry WHERE id % 23 = 0 AND body IS NOT NULL").use { st ->
                    while (st.step()) {
                        val body = st.getBlob(0)
                        if (body.isEmpty()) continue
                        assertContentEquals(inflateRaw(body, dict), PureInflate.inflate(body, dict))
                        compared++
                    }
                }
            } finally {
                conn.close()
            }
        }
        if (compared == 0) println("SKIPPED: no dataset under szdict.datasetDir") else assertTrue(compared > 50)
    }
}
