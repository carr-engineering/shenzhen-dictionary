package com.szdict.core.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/** Typed domain errors (ARCH-120). Cancellation is never an error (ARCH-121). */
sealed class DictError(val message: String) {
    /** The dataset file is missing or cannot be opened. */
    class DatasetUnavailable(val path: String, detail: String = "") :
        DictError("Dataset unavailable: $path${if (detail.isEmpty()) "" else " ($detail)"}")

    /** DATA-003: the dataset's schema version is outside the supported range. */
    class SchemaMismatch(val found: Int, val supported: Int) :
        DictError("Dataset schema version $found is not supported (app supports $supported)")

    /** A linked or requested record does not exist (ARCH-124). */
    class RecordMissing(val what: String) : DictError("Record missing: $what")

    /** An optional pack is not installed (ARCH-123) — not an error for the user, GLB-035. */
    class AssetMissing(val asset: String) : DictError("Asset missing: $asset")

    class IoFailed(detail: String) : DictError("I/O failed: $detail")

    override fun toString(): String = message
}

/** Thrown by [Dictionary] operations; carries the typed [error]. */
class DictException(val error: DictError, cause: Throwable? = null) : Exception(error.message, cause)

/** `Result`-style wrapper for callers that prefer values over exceptions. */
sealed interface DictResult<out T> {
    data class Ok<T>(val value: T) : DictResult<T>
    data class Err(val error: DictError) : DictResult<Nothing>

    fun getOrNull(): T? = (this as? Ok)?.value
}

/** Runs [block], mapping [DictException] (and unexpected failures) to [DictResult.Err]; cancellation propagates. */
suspend fun <T> dictResult(block: suspend () -> T): DictResult<T> = try {
    DictResult.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: DictException) {
    DictResult.Err(e.error)
} catch (e: Exception) {
    DictResult.Err(DictError.IoFailed(e.message ?: e.toString()))
}

/**
 * The shared dictionary engine for one mode's dataset. All operations are `suspend`, cancellable
 * and safe to call from any dispatcher: the work runs on the engine's own background dispatchers
 * (ARCH-130). Failures are thrown as [DictException] (wrap with [dictResult] for values).
 *
 * Entry ids are meaningful only within this instance's [generation] (GLB-044); persist natural
 * keys, never ids.
 */
private val NoCompanionChanges = kotlinx.coroutines.flow.MutableStateFlow(0)

interface Dictionary {
    val mode: DictMode

    /** Unique per opened dataset; bumps whenever a dataset is (re)opened (GLB-044). */
    val generation: Long

    /**
     * Bumps when data that is fetched on first need — the language model with the abbreviation and
     * combination data on Web (ARCH-143) — became available: answers given before it may lack
     * combinations, so the current search is run again (GLB-044 keeps [generation] unchanged).
     * Constant where everything is installed with the app.
     */
    val companionGeneration: kotlinx.coroutines.flow.StateFlow<Int> get() = NoCompanionChanges

    suspend fun info(): DatasetInfo

    /** The label table (DATA-060): note kinds, tags, block labels, decomposition labels… */
    suspend fun labels(): Map<String, Label>

    /** Label for [key], or a verbatim fallback when the dataset has none. */
    suspend fun label(key: String): Label

    // ---- Search (docs/03) ----

    /** Full response including combinations. */
    suspend fun search(query: String, script: Script, options: SearchOptions = SearchOptions()): SearchResult

    /**
     * Progressive variant (SRCH-066): emits the single-word results first (with
     * `combinationsPending = true` when a lattice decode is running), then the same response with
     * the Combinations section added. Emits exactly once when no combinations are triggered.
     */
    fun searchProgressive(query: String, script: Script, options: SearchOptions = SearchOptions()): Flow<SearchResult>

    /** Hydrates result rows (windowed hydration, SRCH-152). Unknown ids are skipped. */
    suspend fun cards(refs: List<RowRef>, script: Script): List<EntryCard>

    /** Hydrates cards by entry id, in the given order, merge grouping applied. Unknown ids are skipped. */
    suspend fun entryCards(ids: List<Int>, script: Script): List<EntryCard>

    /** The precomputed popular multi-character words (SRCH-111). */
    suspend fun popular(script: Script): List<EntryCard>

    /**
     * Word of the Day (SRCH-115, SET-063) for the device-local [epochDay] and the 7-bit [levelMask]
     * (bit 0 = HSK 1 … bit 6 = HSK 7-9). Null when no candidate exists.
     */
    suspend fun wordOfTheDay(epochDay: Long, script: Script, levelMask: Int = 0x7F): EntryCard?

    // ---- Entry detail (docs/04) ----

    suspend fun entryDetail(id: Int, script: Script): EntryDetail

    /** Lookup by natural key (DATA-014); throws [DictError.RecordMissing] when unknown. */
    suspend fun entryDetailByKey(naturalKey: String, script: Script): EntryDetail

    /** Entry id for a natural key, or null. */
    suspend fun idForKey(naturalKey: String): Int?

    /** One page of a WORDS-tab section (ENT-064). */
    suspend fun related(id: Int, kind: RelatedKind, script: Script, offset: Int = 0, limit: Int = 25): RelatedPage

    /** Rows behind the inside-words button (SRCH-105): sub-span order with parent-reading promotion. */
    suspend fun insideWords(id: Int, script: Script): List<InsideWord>

    /** Example sentences (MED-040…048); an empty page when the sentences pack is absent. */
    suspend fun examples(id: Int, script: Script, offset: Int = 0, limit: Int = 20): ExamplePage

    /**
     * Decomposition trees (ENT-120…129) for [characters] (use `EntryDetail.characters`). Pass the
     * opened entry's id as [headwordEntryId] so top-level rows pick the reading matching the
     * headword (ENT-122). Throws [DictError.AssetMissing] when the decomposition pack is absent.
     */
    suspend fun decompose(characters: List<String>, script: Script, headwordEntryId: Int? = null): List<CharacterDecomposition>

    /** Candidates for a tapped definition link (ENT-054, ENT-046 tiering). */
    suspend fun resolveReference(traditional: String, simplified: String, readingCanonical: String?, script: Script): ReferenceCandidates

    /** Audio plan for an entry (MED-006); cards already carry theirs. */
    suspend fun audioPlan(id: Int): AudioPlan

    // ---- Text analysis (docs/05) ----

    suspend fun analyse(text: String, segmentation: SegmentationMode = SegmentationMode.HANZI): AnalysedPassage

    /** Words covering the character at UTF-16 [offset] of [passage] (TXT-020…025). */
    suspend fun spansAt(passage: AnalysedPassage, offset: Int, script: Script): SpanCandidates

    // ---- HSK (docs/06) ----

    suspend fun hskLevels(): List<HskLevelInfo>

    /** Word cards of the levels in [levels] (1..7), in storage order (HSK-030/032), rows merged per HSK-027. */
    suspend fun hsk(levels: IntRange, script: Script): List<HskCard>

    /** Recognition characters of the levels in [levels], in storage order (HSK-098). */
    suspend fun hskChars(levels: IntRange, script: Script): List<HskCharCard>

    // ---- Handwriting / OCR helpers (HW-032) ----

    /**
     * Candidates in recogniser order (best first): drops those containing any character absent from
     * the dataset (including non-Hanzi) and re-ranks the rest by dataset character frequency, the
     * recogniser's top surviving candidate staying within the first three (HW-032).
     */
    suspend fun filterHandwritingCandidates(raw: List<String>): List<String>

    /**
     * Same as [filterHandwritingCandidates] for candidates carrying recogniser scores (higher is better).
     */
    suspend fun rankHandwritingCandidates(raw: List<ScoredText>): List<ScoredText>

    /** Ranks per-position OCR alternatives: filtered to dataset characters, ordered by score and frequency. */
    suspend fun characterAlternatives(alternatives: List<ScoredText>): List<ScoredText>

    /** Releases the dataset handles. Further calls fail with [DictError.DatasetUnavailable]. */
    fun close()
}

/** Opens datasets. No "load the dictionary" phase: a file open plus a metadata read (ARCH-131). */
object DictionaryFactory {
    /** Schema version this build reads (DATA-003); the previous version 1 is still read. */
    const val SUPPORTED_SCHEMA_VERSION: Int = 2

    /**
     * Opens `<datasetDir>/<mode>.db` read-only, plus `<mode>-lm.db` and `packs/…` when present.
     * Throws [DictException] with [DictError.DatasetUnavailable] or [DictError.SchemaMismatch].
     */
    suspend fun open(datasetDir: String, mode: DictMode): Dictionary =
        com.szdict.core.domain.openDictionary(datasetDir, mode)
}
