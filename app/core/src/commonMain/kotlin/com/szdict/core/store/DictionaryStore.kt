package com.szdict.core.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.szdict.core.api.DictError
import com.szdict.core.api.DictException
import com.szdict.core.api.DictMode
import com.szdict.core.api.DictionaryFactory
import com.szdict.core.api.Label
import com.szdict.core.engine.fileExists
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** One cached-statement connection. Used by one coroutine at a time. */
internal class Conn(private val raw: SQLiteConnection) {
    private val cache = HashMap<String, SQLiteStatement>()

    fun stmt(sql: String): SQLiteStatement {
        val st = cache.getOrPut(sql) { raw.prepare(sql) }
        st.reset()
        st.clearBindings()
        return st
    }

    fun close() {
        cache.values.forEach { runCatching { it.close() } }
        cache.clear()
        runCatching { raw.close() }
    }
}

/** A small pool of read-only connections to one database file. */
internal class Db(val path: String, size: Int) {
    private val pool = Channel<Conn>(size)
    private val all = ArrayList<Conn>()

    init {
        val driver = BundledSQLiteDriver()
        repeat(size) {
            val c = Conn(driver.open(path, SQLITE_OPEN_READONLY))
            all.add(c)
            pool.trySend(c)
        }
    }

    suspend fun <T> use(block: suspend (Conn) -> T): T {
        val c = pool.receive()
        try {
            return block(c)
        } finally {
            pool.trySend(c)
        }
    }

    fun close() {
        pool.close()
        all.forEach { it.close() }
    }
}

private suspend inline fun Conn.query(sql: String, bind: (SQLiteStatement) -> Unit, row: (SQLiteStatement) -> Unit) {
    val st = stmt(sql)
    bind(st)
    var n = 0
    while (st.step()) {
        row(st)
        if (++n and 1023 == 0) currentCoroutineContext().ensureActive()
    }
    st.reset()
}

/**
 * The dataset access layer (ARCH-020): exactly the batched query shapes the engine needs, over the
 * SQLite files of contract/SCHEMA.md. Every multi-key operation is one statement (ARCH-021); row
 * loops check for cancellation (ARCH-022); only two caches exist — a bounded LRU of hydrated
 * entries and small fixed tables (inventory, labels, codebooks) — and no whole-index structure is
 * ever built in memory (ARCH-023, ARCH-136).
 */
internal class DictionaryStore private constructor(
    val mode: DictMode,
    private val dir: String,
    private val main: Db,
    private val lm: Db?,
    val meta: Map<String, String>,
    private val io: CoroutineDispatcher,
) {
    private val cacheLock = Mutex()
    private val entryCache = LinkedHashMap<Int, EntryRow>()
    private val mergeCache = HashMap<Int, MergeGroup>()
    private var sentencesDb: Db? = null
    private var decompDb: Db? = null
    private val packLock = Mutex()

    val hasLm: Boolean get() = lm != null
    val maxWordLen: Int = meta["max_word_len"]?.toIntOrNull() ?: 12
    val maxHeadwordLen: Int = meta["max_headword_len"]?.toIntOrNull() ?: 64
    val hasFrequency: Boolean = metaBool("has_frequency_data", true)
    val hasBigram: Boolean = metaBool("has_bigram_model", false)
    val totalFrequency: Double = meta["total_frequency"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
    val abbrevPenalty: Double = kotlin.math.abs(meta["abbrev_penalty"]?.toDoubleOrNull() ?: 0.6931471805599453)
    val partialPenalty: Double = kotlin.math.abs(meta["partial_penalty"]?.toDoubleOrNull() ?: 1.0)
    val toneCount: Int = meta["tone_count"]?.toIntOrNull() ?: if (mode == DictMode.MANDARIN) 5 else 6
    val sentencesPath: String get() = "$dir/packs/" + if (mode == DictMode.MANDARIN) "sentences.db" else "sentences-yue.db"
    val decompPath: String get() = "$dir/packs/decomposition.db"
    val audioCatalogPath: String get() = "$dir/packs/audio/catalog.json"

    fun metaBool(key: String, default: Boolean): Boolean = when (meta[key]?.lowercase()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> default
    }

    fun close() {
        main.close()
        lm?.close()
        sentencesDb?.close()
        decompDb?.close()
    }

    private suspend fun <T> read(block: suspend (Conn) -> T): T = withContext(io) { main.use(block) }

    // ---- entries ----

    private fun entryOf(st: SQLiteStatement): EntryRow {
        val syl = st.getText(6)
        val toneBlob = st.getBlob(7)
        return EntryRow(
            id = st.getLong(0).toInt(), trad = st.getText(1), simp = st.getText(2), readingDisplay = st.getText(3),
            readingCanonical = st.getText(4), readingSource = st.getLong(5).toInt(),
            syllables = if (syl.isEmpty()) emptyList() else syl.split(' '),
            tones = IntArray(toneBlob.size) { toneBlob[it].toInt() and 0xFF },
            flags = st.getLong(8).toInt(), freqRank = st.getLong(9).toInt(), charCount = st.getLong(10).toInt(),
            naturalKey = st.getText(11),
            mergeSimp = if (st.isNull(12)) -1 else st.getLong(12).toInt(),
            mergeTrad = if (st.isNull(13)) -1 else st.getLong(13).toInt(),
            hskJson = st.getText(14), hskViaJson = st.getText(15),
            audioKey = if (st.isNull(16)) null else st.getText(16), gloss = st.getText(17), bodyBlob = st.getBlob(18),
        )
    }

    /** Batched, cached hydration (SRCH-152). Missing ids are absent from the result. */
    suspend fun entries(ids: Collection<Int>): Map<Int, EntryRow> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Int, EntryRow>(ids.size * 2)
        val missing = ArrayList<Int>()
        cacheLock.withLock {
            for (id in ids) {
                if (id in out) continue
                val hit = entryCache.remove(id)
                if (hit != null) {
                    entryCache[id] = hit
                    out[id] = hit
                } else missing.add(id)
            }
        }
        if (missing.isNotEmpty()) {
            val loaded = ArrayList<EntryRow>(missing.size)
            read { c ->
                c.query("SELECT $ENTRY_COLS FROM entry e WHERE e.id IN (SELECT value FROM json_each(?))", { it.bindText(1, Codec.jsonIntArray(missing)) }) {
                    loaded.add(entryOf(it))
                }
            }
            cacheLock.withLock {
                for (e in loaded) {
                    out[e.id] = e
                    entryCache[e.id] = e
                }
                val it = entryCache.keys.iterator()
                while (entryCache.size > ENTRY_CACHE_SIZE && it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
        }
        return out
    }

    suspend fun entry(id: Int): EntryRow? = entries(listOf(id))[id]

    suspend fun idForNaturalKey(key: String): Int? = read { c ->
        var id: Int? = null
        c.query("SELECT id FROM entry WHERE natural_key = ?", { it.bindText(1, key) }) { id = it.getLong(0).toInt() }
        id
    }

    private fun projOf(st: SQLiteStatement, o: Int): Proj = Proj(
        st.getLong(o).toInt(), st.getLong(o + 1).toInt(), st.getLong(o + 2).toInt(), st.getLong(o + 3).toInt(),
        if (st.isNull(o + 4)) -1 else st.getLong(o + 4).toInt(), if (st.isNull(o + 5)) -1 else st.getLong(o + 5).toInt(),
    )

    /** Ranking projections without definitions (DATA-078). */
    suspend fun projections(ids: IntArray): Map<Int, Proj> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Int, Proj>(ids.size * 2)
        read { c ->
            c.query("SELECT $PROJ_COLS FROM entry e WHERE e.id IN (SELECT value FROM json_each(?))", { it.bindText(1, Codec.jsonIntArray(ids)) }) {
                val p = projOf(it, 0)
                out[p.id] = p
            }
        }
        return out
    }

    /** Projection plus the columns pattern search and verification need. */
    class Form(val proj: Proj, val trad: String, val simp: String, val syllables: String, val tones: ByteArray)

    suspend fun forms(ids: IntArray): Map<Int, Form> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Int, Form>(ids.size * 2)
        read { c ->
            c.query(
                "SELECT $PROJ_COLS, e.trad, e.simp, e.syllables, e.tones FROM entry e WHERE e.id IN (SELECT value FROM json_each(?))",
                { it.bindText(1, Codec.jsonIntArray(ids)) },
            ) {
                val p = projOf(it, 0)
                out[p.id] = Form(p, it.getText(6), it.getText(7), it.getText(8), it.getBlob(9))
            }
        }
        return out
    }

    // ---- headword index ----

    /** Exact headword lookup for many keys at once (DATA-071), with ranking projections. */
    suspend fun headwordExact(keys: Collection<String>): Map<String, List<HeadHit>> {
        if (keys.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<HeadHit>>()
        read { c ->
            c.query(
                "SELECT h.key, h.kind, $PROJ_COLS FROM headword h JOIN entry e ON e.id = h.entry_id WHERE h.key IN (SELECT value FROM json_each(?))",
                { it.bindText(1, Codec.jsonArray(keys)) },
            ) {
                val key = it.getText(0)
                out.getOrPut(key) { ArrayList(2) }.add(HeadHit(key, it.getLong(1).toInt(), projOf(it, 2)))
            }
        }
        return out
    }

    /** Headwords strictly longer than [prefix] that begin with it (all rows; rank before limiting, SRCH-071). */
    suspend fun headwordPrefix(prefix: String): List<HeadHit> {
        val out = ArrayList<HeadHit>()
        read { c ->
            c.query(
                "SELECT h.key, h.kind, $PROJ_COLS FROM headword h JOIN entry e ON e.id = h.entry_id WHERE h.key > ? AND h.key < ?",
                { it.bindText(1, prefix); it.bindText(2, upperBound(prefix)) },
            ) { out.add(HeadHit(it.getText(0), it.getLong(1).toInt(), projOf(it, 2))) }
        }
        return out
    }

    // ---- reading index ----

    /** One reading-index lookup: exact key ([hi] null) or the half-open range `[lo, hi)`. */
    class ReadSpec(val lo: String, val hi: String?)

    suspend fun readings(specs: List<ReadSpec>): List<List<ReadHit>> {
        if (specs.isEmpty()) return emptyList()
        return read { c ->
            specs.map { spec ->
                val out = ArrayList<ReadHit>()
                val sql = "SELECT r.key, r.alt, r.alt_note, r.tones, $PROJ_COLS FROM reading r JOIN entry e ON e.id = r.entry_id WHERE " +
                    if (spec.hi == null) "r.key = ?" else "r.key >= ? AND r.key < ?"
                c.query(sql, { it.bindText(1, spec.lo); if (spec.hi != null) it.bindText(2, spec.hi) }) {
                    out.add(
                        ReadHit(
                            it.getText(0), it.getLong(1).toInt(), if (it.isNull(2)) -1 else it.getLong(2).toInt(),
                            if (it.isNull(3)) null else it.getBlob(3), projOf(it, 4),
                        ),
                    )
                }
                out
            }
        }
    }

    // ---- posting lists ----

    suspend fun substrPostings(key: String): IntArray = read { c ->
        var blob: ByteArray? = null
        c.query("SELECT postings FROM substr_idx WHERE key = ?", { it.bindText(1, key) }) { blob = it.getBlob(0) }
        Codec.deltaIds(blob)
    }

    /** Character postings in (frequency rank, id) order (DATA-075). */
    suspend fun charPostings(chars: Collection<String>, limit: Int = Int.MAX_VALUE): Map<String, IntArray> {
        if (chars.isEmpty()) return emptyMap()
        val out = HashMap<String, IntArray>()
        read { c ->
            c.query("SELECT ch, postings FROM char_idx WHERE ch IN (SELECT value FROM json_each(?))", { it.bindText(1, Codec.jsonArray(chars)) }) {
                out[it.getText(0)] = Codec.plainIds(it.getBlob(1), limit)
            }
        }
        return out
    }

    /** English postings: parallel arrays of entry id and `sense << 2 | flags`, sorted by (entry, sense). */
    class EnPostings(val token: String, val ids: IntArray, val senses: IntArray)

    private fun enPostings(token: String, blob: ByteArray): EnPostings {
        val ids = IntArray(blob.size / 2 + 1)
        val senses = IntArray(blob.size / 2 + 1)
        val r = VarintReader(blob)
        var n = 0
        var prev = 0
        while (r.hasMore) {
            prev += r.next()
            ids[n] = prev
            senses[n] = r.next()
            n++
        }
        return EnPostings(token, ids.copyOf(n), senses.copyOf(n))
    }

    /** Exact token, or every token starting with [token] when [prefix] (SRCH-045). */
    suspend fun englishPostings(token: String, prefix: Boolean): List<EnPostings> {
        val out = ArrayList<EnPostings>()
        read { c ->
            if (prefix) {
                c.query("SELECT token, postings FROM en_token WHERE token >= ? AND token < ?", { it.bindText(1, token); it.bindText(2, upperBound(token)) }) {
                    out.add(enPostings(it.getText(0), it.getBlob(1)))
                }
            } else {
                c.query("SELECT token, postings FROM en_token WHERE token = ?", { it.bindText(1, token) }) {
                    out.add(enPostings(it.getText(0), it.getBlob(1)))
                }
            }
        }
        return out
    }

    /** `within` rows per entry as flat (start, length, entry id) triples (DATA-101). */
    suspend fun within(entryIds: Collection<Int>): Map<Int, IntArray> {
        if (entryIds.isEmpty()) return emptyMap()
        val out = HashMap<Int, IntArray>()
        read { c ->
            c.query("SELECT entry_id, rows FROM within WHERE entry_id IN (SELECT value FROM json_each(?))", { it.bindText(1, Codec.jsonIntArray(entryIds)) }) {
                out[it.getLong(0).toInt()] = Codec.plainIds(it.getBlob(1))
            }
        }
        return out
    }

    suspend fun mergeGroups(ids: Collection<Int>): Map<Int, MergeGroup> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Int, MergeGroup>()
        val missing = ArrayList<Int>()
        cacheLock.withLock { for (id in ids) mergeCache[id]?.let { out[id] = it } ?: missing.add(id) }
        if (missing.isNotEmpty()) {
            read { c ->
                c.query("SELECT id, script, members, variants FROM merge_group WHERE id IN (SELECT value FROM json_each(?))", { it.bindText(1, Codec.jsonIntArray(missing)) }) {
                    val variants = try {
                        Codec.json.decodeFromString(ListSerializer(String.serializer()), it.getText(3))
                    } catch (e: Exception) {
                        emptyList()
                    }
                    val g = MergeGroup(it.getLong(0).toInt(), it.getLong(1).toInt(), Codec.plainIds(it.getBlob(2)), variants)
                    out[g.id] = g
                }
            }
            cacheLock.withLock {
                if (mergeCache.size > 4000) mergeCache.clear()
                for (id in missing) out[id]?.let { mergeCache[id] = it }
            }
        }
        return out
    }

    /** Natural-log probability per surface form (DATA-110/113); absent keys are not in the map. */
    suspend fun wordLogProbs(keys: Collection<String>): Map<String, Double> {
        if (keys.isEmpty()) return emptyMap()
        val out = HashMap<String, Double>()
        read { c ->
            c.query("SELECT key, log_prob FROM word_freq WHERE key IN (SELECT value FROM json_each(?))", { it.bindText(1, Codec.jsonArray(keys)) }) {
                out[it.getText(0)] = it.getDouble(1)
            }
        }
        return out
    }

    // ---- small tables (read once) ----

    private var inventoryCache: Inventory? = null
    suspend fun inventory(): Inventory = inventoryCache ?: read { c ->
        val syl = ArrayList<Pair<Int, String>>()
        c.query("SELECT value, idx FROM syllable", {}) { syl.add(it.getLong(1).toInt() to it.getText(0)) }
        val n = (syl.maxOfOrNull { it.first } ?: -1) + 1
        val list = MutableList(n) { "" }
        for ((i, s) in syl) list[i] = s
        val aliases = HashMap<String, String>()
        c.query("SELECT src, dst FROM syllable_alias", {}) { aliases[it.getText(0)] = it.getText(1) }
        val abbr = ArrayList<Pair<String, String>>()
        c.query("SELECT abbrev, syllable FROM syllable_abbrev", {}) { abbr.add(it.getText(0) to it.getText(1)) }
        Inventory(list, aliases, abbr)
    }.also { inventoryCache = it }

    private var labelCache: Map<String, Label>? = null
    suspend fun labels(): Map<String, Label> = labelCache ?: read { c ->
        val out = HashMap<String, Label>()
        c.query("SELECT key, en, zh_hans, zh_hant FROM label", {}) {
            out[it.getText(0)] = Label(it.getText(0), it.getText(1), if (it.isNull(2)) "" else it.getText(2), if (it.isNull(3)) "" else it.getText(3))
        }
        out
    }.also { labelCache = it }

    private var posCache: Map<String, Label>? = null
    suspend fun posLabels(): Map<String, Label> = posCache ?: read { c ->
        val out = HashMap<String, Label>()
        c.query("SELECT token, en, zh_hans, zh_hant FROM hsk_pos_label", {}) {
            out[it.getText(0)] = Label(it.getText(0), it.getText(1), if (it.isNull(2)) "" else it.getText(2), if (it.isNull(3)) "" else it.getText(3))
        }
        out
    }.also { posCache = it }

    suspend fun popular(): IntArray = read { c ->
        val out = ArrayList<Int>()
        c.query("SELECT entry_id FROM popular ORDER BY rank", {}) { out.add(it.getLong(0).toInt()) }
        out.toIntArray()
    }

    // ---- HSK ----

    suspend fun hskRows(levels: IntRange): List<HskRow> = read { c ->
        val out = ArrayList<HskRow>()
        c.query(
            "SELECT row_id, ord, level, simp, trad, homograph, entry_id FROM hsk_row WHERE level >= ? AND level <= ? ORDER BY level, ord",
            { it.bindLong(1, levels.first.toLong()); it.bindLong(2, levels.last.toLong()) },
        ) {
            out.add(HskRow(it.getLong(0).toInt(), it.getLong(1).toInt(), it.getLong(2).toInt(), it.getText(3), it.getText(4), if (it.isNull(5)) 0 else it.getLong(5).toInt(), it.getLong(6).toInt()))
        }
        out
    }

    suspend fun hskCharRows(levels: IntRange): List<HskCharRow> = read { c ->
        val out = ArrayList<HskCharRow>()
        c.query(
            "SELECT ch, level, writing, entry_id FROM hsk_char WHERE level >= ? AND level <= ? ORDER BY level, ord",
            { it.bindLong(1, levels.first.toLong()); it.bindLong(2, levels.last.toLong()) },
        ) { out.add(HskCharRow(it.getText(0), it.getLong(1).toInt(), !it.isNull(2) && it.getLong(2) != 0L, if (it.isNull(3)) -1 else it.getLong(3).toInt())) }
        out
    }

    /** Per level: (syllabus rows, distinct linked entries, recognition characters). */
    suspend fun hskCounts(): Map<Int, IntArray> = read { c ->
        val out = HashMap<Int, IntArray>()
        c.query("SELECT level, COUNT(*), COUNT(DISTINCT entry_id) FROM hsk_row GROUP BY level", {}) {
            out[it.getLong(0).toInt()] = intArrayOf(it.getLong(1).toInt(), it.getLong(2).toInt(), 0)
        }
        c.query("SELECT level, COUNT(*) FROM hsk_char GROUP BY level", {}) {
            out.getOrPut(it.getLong(0).toInt()) { IntArray(3) }[2] = it.getLong(1).toInt()
        }
        out
    }

    /** Candidates of the daily pick (SET-063): distinct primary entry ids of the masked levels, ascending. */
    suspend fun hskEntryIds(levelMask: Int): IntArray = read { c ->
        val levels = (1..7).filter { levelMask and (1 shl (it - 1)) != 0 }
        val out = ArrayList<Int>()
        if (levels.isNotEmpty()) {
            c.query("SELECT DISTINCT entry_id FROM hsk_row WHERE level IN (${levels.joinToString(",")}) ORDER BY entry_id", {}) { out.add(it.getLong(0).toInt()) }
        }
        out.toIntArray()
    }

    // ---- language-model companion ----

    /** First-letter postings: exact keys plus every key strictly extending one of [prefixes] (DATA-151). */
    suspend fun abbrevPostings(exact: Collection<String>, prefixes: Collection<String>): Map<String, List<AbbrPosting>> {
        val db = lm ?: return emptyMap()
        if (exact.isEmpty() && prefixes.isEmpty()) return emptyMap()
        val out = HashMap<String, List<AbbrPosting>>()
        withContext(io) {
            db.use { c ->
                fun decode(blob: ByteArray): List<AbbrPosting> {
                    val r = VarintReader(blob)
                    val n = r.next()
                    val list = ArrayList<AbbrPosting>(n)
                    repeat(n) {
                        if (!r.hasMore) return list
                        val id = r.next()
                        val word = r.next()
                        val uni = r.byte()
                        val alt = r.byte()
                        val k = r.byte()
                        list.add(AbbrPosting(id, word, uni, alt, IntArray(k) { r.next() }))
                    }
                    return list
                }
                if (exact.isNotEmpty()) {
                    c.query("SELECT key, postings FROM abbr_idx WHERE key IN (SELECT value FROM json_each(?))", { it.bindText(1, Codec.jsonArray(exact)) }) {
                        out[it.getText(0)] = decode(it.getBlob(1))
                    }
                }
                for (p in prefixes) {
                    c.query("SELECT key, postings FROM abbr_idx WHERE key > ? AND key < ?", { it.bindText(1, p); it.bindText(2, upperBound(p)) }) {
                        val key = it.getText(0)
                        if (key !in out) out[key] = decode(it.getBlob(1))
                    }
                }
            }
        }
        return out
    }

    private var codebookCache: Array<DoubleArray>? = null

    /** Codebooks by kind: 0 unigram, 1 bigram, 2 backoff (DATA-154). */
    suspend fun codebooks(): Array<DoubleArray> {
        codebookCache?.let { return it }
        val db = lm ?: return Array(3) { DoubleArray(256) }
        val books = Array(3) { DoubleArray(256) { -30.0 } }
        withContext(io) {
            db.use { c ->
                c.query("SELECT kind, code, value FROM lm_codebook", {}) {
                    val k = it.getLong(0).toInt()
                    val code = it.getLong(1).toInt()
                    if (k in 0..2 && code in 0..255) books[k][code] = it.getDouble(2)
                }
            }
        }
        codebookCache = books
        return books
    }

    /** Bigram blocks of the given left words, one batched read (DATA-153). */
    suspend fun bigramBlocks(lefts: Collection<Int>): Map<Int, BigramBlock> {
        val db = lm ?: return emptyMap()
        if (lefts.isEmpty()) return emptyMap()
        val books = codebooks()
        val out = HashMap<Int, BigramBlock>()
        withContext(io) {
            db.use { c ->
                c.query("SELECT left, block FROM bigram WHERE left IN (SELECT value FROM json_each(?))", { it.bindText(1, Codec.jsonIntArray(lefts)) }) {
                    val blob = it.getBlob(1)
                    if (blob.isEmpty()) return@query
                    val r = VarintReader(blob)
                    val backoff = books[2][r.byte()]
                    val n = r.next()
                    val succ = IntArray(n)
                    var prev = 0
                    for (i in 0 until n) {
                        prev = if (i == 0) r.next() else prev + r.next()
                        succ[i] = prev
                    }
                    val vals = DoubleArray(n) { books[1][r.byte()] }
                    out[it.getLong(0).toInt()] = BigramBlock(backoff, succ, vals)
                }
            }
        }
        return out
    }

    /** LM word id and unigram log-probability per entry (lm_entry). */
    suspend fun lmEntries(ids: Collection<Int>): Map<Int, Pair<Int, Double>> {
        val db = lm ?: return emptyMap()
        if (ids.isEmpty()) return emptyMap()
        val books = codebooks()
        val out = HashMap<Int, Pair<Int, Double>>()
        withContext(io) {
            db.use { c ->
                c.query("SELECT entry_id, lm_word, lm_unigram FROM lm_entry WHERE entry_id IN (SELECT value FROM json_each(?))", { it.bindText(1, Codec.jsonIntArray(ids)) }) {
                    out[it.getLong(0).toInt()] = it.getLong(1).toInt() to books[0][it.getLong(2).toInt() and 0xFF]
                }
            }
        }
        return out
    }

    // ---- packs ----

    private suspend fun pack(path: String, current: Db?, set: (Db) -> Unit): Db? {
        current?.let { return it }
        return packLock.withLock {
            if (!fileExists(path)) null else withContext(io) { runCatching { Db(path, 1) }.getOrNull() }?.also(set)
        }
    }

    suspend fun hasSentences(): Boolean = pack(sentencesPath, sentencesDb) { sentencesDb = it } != null
    suspend fun hasDecomposition(): Boolean = pack(decompPath, decompDb) { decompDb = it } != null

    suspend fun sentenceIds(word: String): IntArray {
        val db = pack(sentencesPath, sentencesDb) { sentencesDb = it } ?: return IntArray(0)
        return withContext(io) {
            db.use { c ->
                var blob: ByteArray? = null
                c.query("SELECT ids FROM word_sentences WHERE word = ?", { it.bindText(1, word) }) { blob = it.getBlob(0) }
                Codec.plainIds(blob)
            }
        }
    }

    suspend fun sentences(ids: IntArray): Map<Int, SentenceRow> {
        val db = pack(sentencesPath, sentencesDb) { sentencesDb = it } ?: return emptyMap()
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Int, SentenceRow>()
        withContext(io) {
            db.use { c ->
                c.query("SELECT id, zh, zh_alt, en FROM sentence WHERE id IN (SELECT value FROM json_each(?))", { it.bindText(1, Codec.jsonIntArray(ids)) }) {
                    out[it.getLong(0).toInt()] = SentenceRow(it.getLong(0).toInt(), it.getText(1), if (it.isNull(2)) it.getText(1) else it.getText(2), if (it.isNull(3)) "" else it.getText(3))
                }
            }
        }
        return out
    }

    /** Decomposition rows, one level per call (DATA-191). Null when the pack is absent. */
    suspend fun decompositions(chars: Collection<String>): Map<String, DecompRow>? {
        val db = pack(decompPath, decompDb) { decompDb = it } ?: return null
        if (chars.isEmpty()) return emptyMap()
        val out = HashMap<String, DecompRow>()
        withContext(io) {
            db.use { c ->
                c.query("SELECT ch, ids, parts, radical, strokes, name, svg, matches FROM decomp WHERE ch IN (SELECT value FROM json_each(?))", { it.bindText(1, Codec.jsonArray(chars)) }) {
                    val parts = try {
                        if (it.isNull(2)) emptyList() else Codec.json.decodeFromString(ListSerializer(String.serializer()), it.getText(2))
                    } catch (e: Exception) {
                        emptyList()
                    }
                    val matches: List<List<Int>?>? = if (it.isNull(7)) null else try {
                        (Codec.json.parseToJsonElement(it.getText(7)) as? JsonArray)?.map { m: JsonElement ->
                            when (m) {
                                is JsonNull -> null
                                is JsonArray -> m.mapNotNull { v -> (v as? JsonPrimitive)?.intOrNull }
                                is JsonPrimitive -> m.intOrNull?.let { v -> listOf(v) }
                                else -> null
                            }
                        }
                    } catch (e: Exception) {
                        null
                    }
                    val ch = it.getText(0)
                    out[ch] = DecompRow(
                        ch, if (it.isNull(1)) "" else it.getText(1), parts, if (it.isNull(3)) null else it.getText(3),
                        if (it.isNull(4)) null else it.getLong(4).toInt(), if (it.isNull(5)) null else it.getText(5),
                        if (it.isNull(6)) null else it.getText(6), matches,
                    )
                }
            }
        }
        return out
    }

    companion object {
        private const val ENTRY_CACHE_SIZE = 2000
        private const val PROJ_COLS = "e.id, e.flags, e.freq_rank, e.char_count, e.merge_simp, e.merge_trad"
        private const val ENTRY_COLS = "e.id, e.trad, e.simp, e.reading_display, e.reading_canonical, e.reading_source, e.syllables, e.tones, " +
            "e.flags, e.freq_rank, e.char_count, e.natural_key, e.merge_simp, e.merge_trad, e.hsk, e.hsk_via, e.audio_key, e.gloss, e.body"

        /** Smallest string greater than every string starting with [prefix] (binary collation). */
        fun upperBound(prefix: String): String {
            if (prefix.isEmpty()) return "￿"
            val last = prefix.last()
            return if (last.isSurrogate() || last == '￿') prefix + "￿" else prefix.dropLast(1) + (last + 1)
        }

        /** Opens the dataset read-only and validates the schema version before any query (DATA-003). */
        suspend fun open(dir: String, mode: DictMode, io: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(4)): DictionaryStore = withContext(io) {
            val path = "$dir/${mode.datasetName}.db"
            if (!fileExists(path)) throw DictException(DictError.DatasetUnavailable(path, "file not found"))
            val main = try {
                Db(path, 3)
            } catch (e: Exception) {
                throw DictException(DictError.DatasetUnavailable(path, e.message ?: ""), e)
            }
            try {
                val meta = HashMap<String, String>()
                var userVersion = -1
                main.use { c ->
                    c.query("PRAGMA user_version", {}) { userVersion = it.getLong(0).toInt() }
                    c.query("SELECT key, value FROM meta", {}) { if (!it.isNull(1)) meta[it.getText(0)] = it.getText(1) }
                }
                val version = meta["schema_version"]?.toIntOrNull() ?: userVersion
                if (version != DictionaryFactory.SUPPORTED_SCHEMA_VERSION) {
                    throw DictException(DictError.SchemaMismatch(version, DictionaryFactory.SUPPORTED_SCHEMA_VERSION))
                }
                val lmPath = "$dir/${mode.datasetName}-lm.db"
                var lm = if (fileExists(lmPath)) runCatching { Db(lmPath, 2) }.getOrNull() else null
                if (lm != null) {
                    // DATA-006: the companion must be built against this dataset version.
                    var lmVersion: String? = null
                    runCatching { lm!!.use { c -> c.query("SELECT value FROM meta WHERE key = 'dataset_version'", {}) { lmVersion = it.getText(0) } } }
                    val own = meta["dataset_version"]
                    if (lmVersion != null && own != null && lmVersion != own) {
                        lm.close()
                        lm = null
                    }
                }
                DictionaryStore(mode, dir, main, lm, meta, io)
            } catch (e: DictException) {
                main.close()
                throw e
            } catch (e: Exception) {
                main.close()
                throw DictException(DictError.DatasetUnavailable(path, e.message ?: e.toString()), e)
            }
        }
    }
}
