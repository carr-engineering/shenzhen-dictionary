package com.szdict.core

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.szdict.core.api.Romanisation
import com.szdict.core.engine.Normaliser
import com.szdict.core.engine.cps
import com.szdict.core.engine.cpString
import com.szdict.core.engine.isHanzi
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Deflater
import kotlin.math.ln

/**
 * Builds a tiny dataset in the physical format of contract/SCHEMA.md (schema_version 1) with the
 * bundled SQLite driver, so engine tests never depend on the pipeline output. Indexes are derived
 * from the entries exactly as the contract describes them.
 */
class Fixture(
    private val name: String = "mandarin",
    private val hasFrequency: Boolean = true,
    private val hasBigram: Boolean = true,
    private val schemaVersion: Int = 1,
) {
    class E(
        val id: Int, val trad: String, val simp: String, val pinyin: String, val senses: List<String>, val freq: Int,
        val flags: Int, val hsk: String, val hskVia: String, val redirectTo: Int?, val redirectText: String?,
        val alt: List<Pair<String, String>>, val notes: String, val block: String, val mergeSimp: Int?, val tags: Map<Int, List<String>>,
    ) {
        val syllables: List<String>
        val tones: List<Int>
        init { Romanisation.parseCanonical(pinyin).let { syllables = it.first; tones = it.second } }
        val canonical: String get() = pinyin.lowercase()
        val isProper: Boolean get() = pinyin.isNotEmpty() && pinyin[0].isUpperCase() && pinyin.length > 1 && pinyin[1].isLowerCase()
    }

    val entries = ArrayList<E>()
    private val bigrams = ArrayList<Triple<String, String, Double>>()
    private val hskRows = ArrayList<List<Any?>>()
    private val mergeGroups = ArrayList<Triple<Int, List<Int>, List<String>>>()
    private val freqTable = HashMap<String, Int>()
    /** Optional `english_word` table (SCHEMA note 19): word → corpus count; absent when empty. */
    val englishWords = LinkedHashMap<String, Int>()
    var englishTotal = 1_000_000

    fun entry(
        trad: String, simp: String, pinyin: String, senses: String, freq: Int = 1_000_000, count: Int = 0, flags: Int = 2,
        hsk: String = "[]", hskVia: String = "[]", redirectTo: Int? = null, redirectText: String? = null,
        alt: List<Pair<String, String>> = emptyList(), notes: String = "", block: String = "mandarin", mergeSimp: Int? = null,
        tags: Map<Int, List<String>> = emptyMap(),
    ): Int {
        val id = entries.size + 1
        entries.add(E(id, trad, simp, pinyin, if (senses.isEmpty()) emptyList() else senses.split("; "), freq, flags, hsk, hskVia, redirectTo, redirectText, alt, notes, block, mergeSimp, tags))
        if (count > 0) { freqTable[simp] = count; freqTable[trad] = count }
        return id
    }

    fun bigram(left: String, right: String, logProb: Double) { bigrams.add(Triple(left, right, logProb)) }
    fun hskRow(level: Int, entryId: Int, pos: String = "[]", homograph: Int = 0) { hskRows.add(listOf(level, entryId, pos, homograph)) }
    fun mergeGroup(id: Int, members: List<Int>, variants: List<String>) { mergeGroups.add(Triple(id, members, variants)) }

    private fun varint(out: ByteArrayOutputStream, v: Int) {
        var x = v
        while (x >= 0x80) { out.write((x and 0x7F) or 0x80); x = x ushr 7 }
        out.write(x)
    }
    private fun plain(ids: List<Int>) = ByteArrayOutputStream().also { o -> ids.forEach { varint(o, it) } }.toByteArray()
    private fun delta(ids: List<Int>) = ByteArrayOutputStream().also { o -> var p = 0; ids.sorted().forEachIndexed { i, v -> varint(o, if (i == 0) v else v - p); p = v } }.toByteArray()
    private fun deflate(s: String): ByteArray {
        val d = Deflater(9, true)
        d.setInput(s.toByteArray()); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun body(e: E): String {
        val senses = e.senses.mapIndexed { i, t ->
            val tok = Normaliser.englishTokens(t).joinToString(",") { q(it) }
            val tags = e.tags[i]?.joinToString(",") { q(it) } ?: ""
            val evr = if ("(variant of" in t) ",\"evr\":true" else ""
            "{\"t\":${q(t)},\"src\":\"cedict\",\"tags\":[$tags],\"tok\":[$tok],\"seg\":[{\"k\":\"t\",\"t\":${q(t)}}]$evr}"
        }
        val sb = StringBuilder("{\"senses\":[").append(senses.joinToString(",")).append("]")
        if (e.senses.isNotEmpty()) sb.append(",\"blocks\":[{\"kind\":${q(e.block)},\"label\":${q("block_" + e.block)},\"first\":0,\"count\":${e.senses.size}}]")
        if (e.notes.isNotEmpty()) sb.append(",\"notes\":[").append(e.notes).append("]")
        if (e.redirectText != null) {
            sb.append(",\"redirect\":{\"kind\":\"variant\",\"text\":${q(e.redirectText)},\"seg\":[{\"k\":\"t\",\"t\":${q(e.redirectText)}}]")
            if (e.redirectTo != null) sb.append(",\"target\":${e.redirectTo},\"resolved\":true")
            sb.append("}")
        }
        if (e.alt.isNotEmpty()) sb.append(",\"alt\":[").append(e.alt.joinToString(",") { (kind, c) -> "{\"kind\":${q(kind)},\"d\":${q(Romanisation.pinyinToMarked(c))},\"c\":${q(c)}}" }).append("]")
        return sb.append("}").toString()
    }

    private fun SQLiteConnection.run(sql: String, vararg args: Any?) {
        val st = prepare(sql)
        try {
            args.forEachIndexed { i, a ->
                when (a) {
                    null -> st.bindNull(i + 1)
                    is Int -> st.bindLong(i + 1, a.toLong())
                    is Long -> st.bindLong(i + 1, a)
                    is Double -> st.bindDouble(i + 1, a)
                    is ByteArray -> st.bindBlob(i + 1, a)
                    else -> st.bindText(i + 1, a.toString())
                }
            }
            st.step()
        } finally {
            st.close()
        }
    }

    /** Writes `<name>.db` and `<name>-lm.db` into a fresh temp directory and returns it. */
    fun build(withLm: Boolean = true): File {
        val dir = File.createTempFile("szdict-fixture", "").apply { delete(); mkdirs(); deleteOnExit() }
        val db = BundledSQLiteDriver().open(File(dir, "$name.db").absolutePath)
        for (ddl in DDL) db.execSQL(ddl)
        db.execSQL("BEGIN")
        val total = freqTable.values.sum().coerceAtLeast(1)
        val meta = mapOf(
            "schema_version" to "$schemaVersion", "dataset_id" to name, "dataset_version" to "fixture+1", "language" to "cmn", "romanization" to "pinyin",
            "tone_count" to "5", "entry_count" to "${entries.size}", "max_headword_len" to "8", "max_word_len" to "4", "total_frequency" to "$total",
            "has_frequency_data" to if (hasFrequency) "1" else "0", "has_hsk" to "1", "has_bigram_model" to if (hasBigram) "1" else "0",
            "abbrev_penalty" to "-0.693147", "partial_penalty" to "-0.356675", "built_at" to "2026-01-01T00:00:00Z",
            "sources" to "[{\"name\":\"Fixture\",\"version\":\"1\",\"date\":\"2026-01-01\",\"licence\":\"CC0\",\"url\":\"https://example.org\"}]",
        )
        for ((k, v) in meta) db.run("INSERT INTO meta VALUES(?,?)", k, v)
        db.execSQL("PRAGMA user_version = $schemaVersion")

        val headKeys = LinkedHashSet<Triple<String, Int, Int>>()
        val substr = HashMap<String, MutableList<Int>>()
        val chars = HashMap<String, MutableList<E>>()
        val tokens = HashMap<String, MutableList<Pair<Int, Int>>>()
        val syllables = sortedSetOf("a", "e", "o", "men", "ma", "hen", "he", "shi", "de", "zhe", "ge", "xiang", "qu", "zai")
        for (e in entries) {
            val sha = MessageDigest.getInstance("SHA-256").digest("${e.trad} ${e.simp} ${e.pinyin}".toByteArray())
            val key = sha.take(8).joinToString("") { "%02x".format(it) }
            val gloss = (listOfNotNull(e.redirectText) + (e.redirectTo?.let { t -> entries[t - 1].senses } ?: e.senses)).joinToString("; ")
            db.run(
                "INSERT INTO entry VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", e.id, e.trad, e.simp, Romanisation.pinyinToMarked(e.pinyin), e.canonical,
                if (e.pinyin.isEmpty()) 5 else if (e.block == "unihan") 4 else 0, e.syllables.joinToString(" "), ByteArray(e.tones.size) { e.tones[it].toByte() },
                e.flags or (if (e.isProper) 1 else 0), e.freq, e.simp.cps().size, key, e.mergeSimp, null, e.hsk, e.hskVia,
                if (e.tones.isNotEmpty() && e.tones.all { it != 0 }) e.canonical.replace(' ', '_') else null, gloss, deflate(body(e)),
            )
            for (form in setOf(e.simp, e.trad)) {
                headKeys.add(Triple(form, e.id, 0))
                val folded = Normaliser.matchingKey(form)
                if (folded != form && folded.isNotEmpty()) headKeys.add(Triple(folded, e.id, 2))
                val cps = form.cps()
                for (start in 1 until cps.size) for (len in 1..minOf(4, cps.size - start)) {
                    substr.getOrPut(Normaliser.matchingKey(cps.cpString(start, start + len))) { ArrayList() }.let { if (e.id !in it) it.add(e.id) }
                }
                for (cp in cps) if (isHanzi(cp)) chars.getOrPut(String(Character.toChars(cp))) { ArrayList() }.let { if (e !in it) it.add(e) }
            }
            if (e.syllables.isNotEmpty()) {
                syllables.addAll(e.syllables.filter { it.length > 1 || it in "aeo" })
                db.run("INSERT OR IGNORE INTO reading VALUES(?,?,0,0,?)", e.syllables.joinToString(" "), e.id, ByteArray(e.tones.size) { e.tones[it].toByte() })
                e.alt.forEachIndexed { i, (kind, c) ->
                    val (syl, tones) = Romanisation.parseCanonical(c)
                    syllables.addAll(syl)
                    db.run("INSERT OR IGNORE INTO reading VALUES(?,?,?,?,?)", syl.joinToString(" "), e.id, if (kind == "also_pr") 1 else 2, i, ByteArray(tones.size) { tones[it].toByte() })
                }
            }
            e.senses.forEachIndexed { si, t ->
                for (tok in Normaliser.englishTokens(t).toSet()) tokens.getOrPut(tok) { ArrayList() }.add(e.id to ((si shl 2) or (if (e.block == "unihan") 2 else 0)))
            }
        }
        for ((k, id, kind) in headKeys) db.run("INSERT OR IGNORE INTO headword VALUES(?,?,?)", k, id, kind)
        for ((k, ids) in substr) db.run("INSERT INTO substr_idx VALUES(?,?)", k, delta(ids))
        for ((ch, es) in chars) db.run("INSERT INTO char_idx VALUES(?,?)", ch, plain(es.sortedWith(compareBy({ it.freq }, { it.id })).map { it.id }))
        for ((tok, ps) in tokens) {
            val out = ByteArrayOutputStream()
            var prev = 0
            for ((id, sf) in ps.sortedWith(compareBy({ it.first }, { it.second }))) { varint(out, id - prev); varint(out, sf); prev = id }
            db.run("INSERT INTO en_token VALUES(?,?)", tok, out.toByteArray())
        }
        // within: every dictionary word inside a multi-character entry (DATA-101 order).
        for (e in entries) {
            val cps = e.simp.cps()
            if (cps.size < 2 || cps.size > 8) continue
            val rows = ArrayList<Int>()
            for (len in cps.size - 1 downTo 1) for (start in 0..cps.size - len) {
                val sub = cps.cpString(start, start + len)
                entries.filter { it.simp == sub || it.trad == sub }.sortedWith(compareBy({ it.freq }, { it.id })).forEach { rows.add(start); rows.add(len); rows.add(it.id) }
            }
            if (rows.isNotEmpty()) db.run("INSERT INTO within VALUES(?,?)", e.id, plain(rows))
        }
        for ((id, members, variants) in mergeGroups) db.run("INSERT INTO merge_group VALUES(?,?,?,?)", id, 0, plain(members), variants.joinToString(",", "[", "]") { q(it) })
        for ((k, c) in freqTable) db.run("INSERT INTO word_freq VALUES(?,?,?)", k, c, ln(c.toDouble()) - ln(total.toDouble()))
        var ord = 0
        for (row in hskRows.sortedBy { it[0] as Int }) {
            val e = entries[(row[1] as Int) - 1]
            db.run("INSERT INTO hsk_row VALUES(?,?,?,?,?,?,?,?,?,?,?)", ord + 1, ord, row[0], e.simp, e.trad, row[3], row[2], "[]", e.id, "[]", e.canonical)
            ord++
        }
        entries.filter { it.simp.length == 1 && it.hsk != "[]" }.distinctBy { it.simp }.forEachIndexed { i, e ->
            db.run("INSERT INTO hsk_char VALUES(?,?,?,?,?,?,?)", e.simp, i, 1, 1, "1-2", e.id, e.canonical)
        }
        db.run("INSERT INTO hsk_pos_label VALUES('动','Verb','动词','動詞')")
        db.run("INSERT INTO hsk_pos_label VALUES('名','Noun','名词','名詞')")
        for ((k, en, hans) in listOf(
            Triple("block_mandarin", "Mandarin senses (CC-CEDICT)", "普通话释义"), Triple("block_unihan", "Unicode (Unihan)", "Unicode（Unihan）"),
            Triple("taiwan_pr", "Taiwan pronunciation", "台湾读音"), Triple("also_pr", "Also pronounced", "又读"), Triple("classifier", "Classifier", "量词"),
            Triple("idiom", "Idiom", "成语"), Triple("variant_of", "Variant of", "异体"),
        )) db.run("INSERT INTO label VALUES(?,?,?,?)", k, en, hans, hans)
        val sylList = syllables.toList()
        sylList.forEachIndexed { i, s -> db.run("INSERT INTO syllable VALUES(?,?)", s, i) }
        db.run("INSERT INTO syllable_alias VALUES('lv','lü')")
        for (s in sylList) {
            if (s[0] !in "iuvü") db.run("INSERT OR IGNORE INTO syllable_abbrev VALUES(?,?)", s.substring(0, 1), s)
            if (s.length > 2 && s.substring(0, 2) in setOf("zh", "ch", "sh")) db.run("INSERT OR IGNORE INTO syllable_abbrev VALUES(?,?)", s.substring(0, 2), s)
        }
        entries.filter { it.simp.cps().size > 1 && it.freq < 1_000_000 }.sortedBy { it.freq }.take(50).forEachIndexed { i, e -> db.run("INSERT INTO popular VALUES(?,?)", i + 1, e.id) }
        if (englishWords.isNotEmpty()) {
            db.execSQL("CREATE TABLE english_word(word TEXT PRIMARY KEY, freq INTEGER) WITHOUT ROWID")
            for ((w, c) in englishWords) db.run("INSERT INTO english_word VALUES(?,?)", w, c)
            db.run("INSERT INTO meta VALUES('english_word_total',?)", "$englishTotal")
        }
        db.execSQL("COMMIT")
        db.close()

        if (withLm) {
            val lm = BundledSQLiteDriver().open(File(dir, "$name-lm.db").absolutePath)
            for (ddl in LM_DDL) lm.execSQL(ddl)
            lm.execSQL("BEGIN")
            lm.run("INSERT INTO meta VALUES('dataset_version','fixture+1')")
            // Codebooks: code c ↔ -c / 8 (natural log), fine enough for exact test arithmetic on multiples of 0.125.
            for (kind in 0..2) for (code in 0..255) lm.run("INSERT INTO lm_codebook VALUES(?,?,?)", kind, code, -code / 8.0)
            fun code(lp: Double) = (-lp * 8).toInt().coerceIn(0, 255)
            val words = entries.filter { it.block != "unihan" }.map { it.simp }.distinct().sortedBy { w -> entries.first { it.simp == w }.freq }
            val wordId = words.withIndex().associate { it.value to it.index }
            fun unigram(e: E): Double = -(2.0 + minOf(e.freq, 4000) / 250.0)
            val abbr = HashMap<String, MutableList<ByteArray>>()
            for (e in entries.sortedBy { it.freq }) {
                if (e.block == "unihan") continue
                lm.run("INSERT INTO lm_entry VALUES(?,?,?)", e.id, wordId[e.simp], code(unigram(e)))
                fun posting(syl: List<String>, alt: Int) {
                    if (syl.isEmpty() || syl.size > 6 || syl.any { it !in sylList }) return
                    val out = ByteArrayOutputStream()
                    varint(out, e.id); varint(out, wordId[e.simp]!!); out.write(code(unigram(e))); out.write(alt); out.write(syl.size)
                    syl.forEach { varint(out, sylList.indexOf(it)) }
                    abbr.getOrPut(syl.joinToString("") { it.take(1) }) { ArrayList() }.add(out.toByteArray())
                }
                posting(e.syllables, 0)
                for ((kind, c) in e.alt) posting(Romanisation.parseCanonical(c).first, if (kind == "also_pr") 1 else 2)
            }
            for ((k, ps) in abbr) {
                val out = ByteArrayOutputStream()
                varint(out, ps.size)
                ps.forEach { out.write(it) }
                lm.run("INSERT INTO abbr_idx VALUES(?,?)", k, out.toByteArray())
            }
            for ((left, pairs) in bigrams.groupBy { it.first }) {
                val out = ByteArrayOutputStream()
                out.write(code(-0.5))
                val succ = pairs.map { wordId[it.second]!! to it.third }.sortedBy { it.first }
                varint(out, succ.size)
                var prev = 0
                succ.forEachIndexed { i, (w, _) -> varint(out, if (i == 0) w else w - prev); prev = w }
                succ.forEach { out.write(code(it.second)) }
                lm.run("INSERT INTO bigram VALUES(?,?)", wordId[left]!!, out.toByteArray())
            }
            lm.execSQL("COMMIT")
            lm.close()
        }
        return dir
    }

    companion object {
        /** The standard fixture used by most tests. */
        fun standard(hasFrequency: Boolean = true, hasBigram: Boolean = true): Fixture = Fixture(hasFrequency = hasFrequency, hasBigram = hasBigram).apply {
            val nihao = entry("你好", "你好", "ni3 hao3", "hello; hi", 273, 400000, hsk = "[{\"l\":1,\"pos\":[],\"x\":0}]")
            val ni = entry("你", "你", "ni3", "you (informal)", 3, 900000, hsk = "[{\"l\":1,\"pos\":[\"代\"],\"x\":0}]")
            entry("好", "好", "hao3", "good; well", 15, 300000)
            entry("好", "好", "hao4", "to be fond of; to have a tendency to", 15, 300000)
            val wo = entry("我", "我", "wo3", "I; me; my", 2, 1000000)
            entry("我們", "我们", "wo3 men5", "we; us; ourselves", 20, 400000)
            entry("研究", "研究", "yan2 jiu1", "research; a study; to research", 400, 30000)
            val yjs = entry("研究生", "研究生", "yan2 jiu1 sheng1", "graduate student; postgraduate student; research student", 3200, 200)
            entry("生命", "生命", "sheng1 ming4", "life (as the characteristic of living beings); living being", 500, 40000)
            entry("科學", "科学", "ke1 xue2", "science; scientific knowledge", 700, 20000)
            entry("命", "命", "ming4", "life; fate; order or command", 450, 9000)
            entry("生", "生", "sheng1", "to be born; to give birth; life; student of graduate school", 280, 12000)
            val xihuan = entry("喜歡", "喜欢", "xi3 huan5", "to like; to be fond of", 90, 80000, hsk = "[{\"l\":1,\"pos\":[\"动\"],\"x\":0},{\"l\":4,\"pos\":[\"名\"],\"x\":1}]")
            entry("中國", "中国", "Zhong1 guo2", "China", 150, 60000)
            entry("T恤", "T恤", "T xu4", "T-shirt", 5000)
            entry("體恤", "体恤", "ti3 xu4", "to empathize with; to show solicitude for", 6000)
            entry("垃圾", "垃圾", "la1 ji1", "trash; refuse; garbage", 800, 3000, alt = listOf("taiwan_pr" to "le4 se4"),
                notes = "{\"kind\":\"taiwan_pr\",\"label\":\"taiwan_pr\",\"reading\":\"lè sè\"},{\"kind\":\"classifier\",\"label\":\"classifier\",\"refs\":[{\"tr\":\"個\",\"si\":\"个\",\"d\":\"gè\",\"c\":\"ge4\",\"rom\":\"pinyin\"}]}")
            entry("樂色", "乐色", "le4 se4", "(Cantonese) garbage", 9000)
            entry("王", "王", "wang2", "king or monarch; best or strongest of its type", 600, 7000)
            entry("王", "王", "Wang2", "surname Wang", 600, 7000)
            val fa1 = entry("發", "发", "fa1", "to send out; to issue; to develop", 210, 50000, mergeSimp = 1)
            val fa2 = entry("髮", "发", "fa1", "hair (variant of 發); to issue", 210, 50000, mergeSimp = 1)
            mergeGroup(1, listOf(fa1, fa2), listOf("發", "髮"))
            val li = entry("里", "里", "li3", "inside; lining; interior", 100, 70000)
            entry("裏", "里", "li3", "", 100, 70000, redirectTo = li, redirectText = "variant of 里")
            entry("㐀", "㐀", "qiu1", "hillock; mound", flags = 2 or 4, block = "unihan")
            entry("想", "想", "xiang3", "to think; to wish; to miss", 29, 500000)
            entry("想去", "想去", "xiang3 qu4", "to want to go", 1500, 800)
            entry("去", "去", "qu4", "to go; to leave", 40, 450000)
            entry("很", "很", "hen3", "very; quite", 30, 480000)
            entry("世界", "世界", "shi4 jie4", "world", 160, 55000, tags = mapOf(0 to listOf("idiom")))
            entry("上海", "上海", "Shang4 hai3", "Shanghai", 330, 9000)
            entry("海上", "海上", "hai3 shang4", "maritime; at sea", 2100, 600)
            entry("海", "海", "hai3", "ocean; sea", 420, 8000)
            entry("好學生", "好学生", "hao3 xue2 sheng5", "good student", 4000, 50)
            entry("學生", "学生", "xue2 sheng5", "student; schoolchild", 300, 45000)
            bigram("我", "喜欢", -1.0)
            bigram("喜欢", "你", -0.5)
            bigram("我", "想去", -0.25)
            hskRow(1, nihao); hskRow(1, ni, "[\"代\"]"); hskRow(1, xihuan, "[\"动\"]"); hskRow(1, xihuan, "[\"名\"]", 2); hskRow(1, wo); hskRow(4, yjs, "[\"名\"]")
        }

        private val DDL = listOf(
            "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID",
            "CREATE TABLE entry(id INTEGER PRIMARY KEY, trad TEXT NOT NULL, simp TEXT NOT NULL, reading_display TEXT NOT NULL, reading_canonical TEXT NOT NULL, " +
                "reading_source INTEGER NOT NULL, syllables TEXT NOT NULL, tones BLOB NOT NULL, flags INTEGER NOT NULL, freq_rank INTEGER NOT NULL, char_count INTEGER NOT NULL, " +
                "natural_key TEXT NOT NULL, merge_simp INTEGER, merge_trad INTEGER, hsk TEXT NOT NULL, hsk_via TEXT NOT NULL, audio_key TEXT, gloss TEXT NOT NULL, body BLOB NOT NULL)",
            "CREATE INDEX entry_freq ON entry(freq_rank, id)", "CREATE UNIQUE INDEX entry_natural ON entry(natural_key)",
            "CREATE TABLE headword(key TEXT, entry_id INTEGER, kind INTEGER, PRIMARY KEY(key, entry_id, kind)) WITHOUT ROWID",
            "CREATE TABLE reading(key TEXT, entry_id INTEGER, alt INTEGER, alt_note INTEGER, tones BLOB, PRIMARY KEY(key, entry_id, alt, alt_note)) WITHOUT ROWID",
            "CREATE TABLE substr_idx(key TEXT PRIMARY KEY, postings BLOB) WITHOUT ROWID", "CREATE TABLE char_idx(ch TEXT PRIMARY KEY, postings BLOB) WITHOUT ROWID",
            "CREATE TABLE en_token(token TEXT PRIMARY KEY, postings BLOB) WITHOUT ROWID",
            "CREATE TABLE merge_group(id INTEGER PRIMARY KEY, script INTEGER, members BLOB, variants TEXT)",
            "CREATE TABLE within(entry_id INTEGER PRIMARY KEY, rows BLOB)", "CREATE TABLE word_freq(key TEXT PRIMARY KEY, freq INTEGER, log_prob REAL) WITHOUT ROWID",
            "CREATE TABLE hsk_row(row_id INTEGER PRIMARY KEY, ord INTEGER, level INTEGER, simp TEXT, trad TEXT, homograph INTEGER, pos TEXT, extra_levels TEXT, " +
                "entry_id INTEGER NOT NULL, also_entry_ids TEXT, sort_key TEXT)",
            "CREATE INDEX hsk_row_level ON hsk_row(level, ord)",
            "CREATE TABLE hsk_char(ch TEXT PRIMARY KEY, ord INTEGER, level INTEGER, writing INTEGER, writing_band TEXT, entry_id INTEGER, sort_key TEXT) WITHOUT ROWID",
            "CREATE INDEX hsk_char_level ON hsk_char(level, ord)",
            "CREATE TABLE hsk_pos_label(token TEXT PRIMARY KEY, en TEXT, zh_hans TEXT, zh_hant TEXT) WITHOUT ROWID",
            "CREATE TABLE label(key TEXT PRIMARY KEY, en TEXT, zh_hans TEXT, zh_hant TEXT) WITHOUT ROWID",
            "CREATE TABLE syllable(value TEXT PRIMARY KEY, idx INTEGER) WITHOUT ROWID", "CREATE TABLE syllable_alias(src TEXT PRIMARY KEY, dst TEXT) WITHOUT ROWID",
            "CREATE TABLE syllable_abbrev(abbrev TEXT, syllable TEXT, PRIMARY KEY(abbrev, syllable)) WITHOUT ROWID",
            "CREATE TABLE popular(rank INTEGER PRIMARY KEY, entry_id INTEGER)",
        )
        private val LM_DDL = listOf(
            "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID",
            "CREATE TABLE lm_entry(entry_id INTEGER PRIMARY KEY, lm_word INTEGER, lm_unigram INTEGER)",
            "CREATE TABLE lm_codebook(kind INTEGER, code INTEGER, value REAL, PRIMARY KEY(kind, code)) WITHOUT ROWID",
            "CREATE TABLE abbr_idx(key TEXT PRIMARY KEY, postings BLOB) WITHOUT ROWID", "CREATE TABLE bigram(left INTEGER PRIMARY KEY, block BLOB)",
        )
    }
}
