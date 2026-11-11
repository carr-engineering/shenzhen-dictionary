package com.szdict.app.feature.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.szdict.app.AppSession
import com.szdict.app.HandwritingSink
import com.szdict.core.api.DailyPick
import com.szdict.core.api.DictException
import com.szdict.core.api.DictMode
import com.szdict.core.api.EntryCard
import com.szdict.core.api.InsideWord
import com.szdict.core.api.LatestOnly
import com.szdict.core.api.RowRef
import com.szdict.core.api.Script
import com.szdict.core.api.SearchResult
import com.szdict.core.api.SearchSection
import com.szdict.core.api.SearchSide
import com.szdict.core.api.Settings
import com.szdict.core.api.SettingsCodec
import com.szdict.core.api.SideResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Identity of one search request: a result is applied only while [seq] is the newest (GLB-040). */
internal data class SearchRequest(
    val seq: Long,
    val query: String,
    val mode: DictMode,
    val script: Script,
    val dataGeneration: Int,
)

/** A failed search (GLB-034): shown as `Unable to search`, with [detail] after a colon when present. */
data class SearchError(val detail: String?)

/** Expander state key (SRCH-105): remembered per (word, row count) while the result list lives. */
data class InsideKey(val word: String, val count: Int)

/**
 * One section of a displayed page with its windowed hydration (SRCH-073, SRCH-152). [cards] is
 * aligned with the section's row list; a `null` slot is a row the engine could not hydrate.
 */
class SectionState internal constructor(val side: SearchSide, val section: SearchSection, carried: List<EntryCard?>?) {
    /** Number of rows (cards or placeholders) the section renders. */
    val total: Int = maxOf(section.refs.size, section.cards.size)
    val cards = mutableStateListOf<EntryCard?>().apply { addAll(carried ?: section.cards) }

    /** Stable, unique item keys: section prefix + row identity (SRCH-169). */
    val keys: List<String> = run {
        val seen = HashMap<String, Int>()
        List(total) { index ->
            val ref: RowRef? = section.refs.getOrNull(index)
            val base = if (ref != null) {
                "${side.ordinal}:${section.key}:${ref.entryId}:${ref.altOrdinal}:${ref.altKey.orEmpty()}:${if (ref.mandarinRow) 1 else 0}"
            } else {
                "${side.ordinal}:${section.key}:${section.cards[index].id}"
            }
            val n = seen.getOrElse(base) { 0 }
            seen[base] = n + 1
            if (n == 0) base else "$base#$n"
        }
    }
    internal var loading = false
}

class SideState internal constructor(val result: SideResult, val sections: List<SectionState>)

/** The response on screen plus the context it was produced in. */
class DisplayedSearch internal constructor(
    val result: SearchResult,
    val mode: DictMode,
    val dataGeneration: Int,
    val chinese: SideState,
    val english: SideState,
) {
    fun side(side: SearchSide): SideState = if (side == SearchSide.CHINESE) chinese else english
}

/**
 * Search screen state (ARCH-040): the input, the latest-only search (GLB-040…042), the displayed
 * response with progressive combinations (SRCH-066), side selection (SRCH-024…026), last-query
 * persistence (SRCH-140…142) and the empty-state data. All calls happen on the UI thread.
 */
class SearchStateHolder(
    private val session: AppSession,
    private val scope: CoroutineScope,
    private val epochDay: () -> Long = ::localEpochDay,
) {
    var input by mutableStateOf(TextFieldValue(""))
        private set
    val query: String get() = input.text

    /** The response on screen; null shows the empty state (blank query, SRCH-110/160). */
    var displayed by mutableStateOf<DisplayedSearch?>(null)
        private set

    /** A search for the current query is running (including its combination decode). */
    var searching by mutableStateOf(false)
        private set

    /** True when [displayed] answers the current query, so counts may be shown (SRCH-090). */
    var countsCurrent by mutableStateOf(false)
        private set

    /** Inline error text (GLB-034), cleared by the next successful search (SRCH-172). */
    var error by mutableStateOf<SearchError?>(null)
        private set

    var side by mutableStateOf(session.settings.value.searchSide)
        private set

    var wordOfDay by mutableStateOf<EntryCard?>(null)
        private set
    var popular by mutableStateOf<List<EntryCard>>(emptyList())
        private set
    var emptyError by mutableStateOf<SearchError?>(null)
        private set

    val insideExpanded = mutableStateMapOf<InsideKey, Boolean>()

    /** Set by the screen: brings every list back to the top without key-following (SRCH-093). */
    var onScrollToTop: (() -> Unit)? = null

    /** Searches that ran to completion and were applied — diagnostics and tests (GLB-041). */
    var completedSearches = 0
        private set

    private var seq = 0L
    private var appliedSeq = -1L
    private var awaitingSide = false
    private var manualSide = false
    private var userEdited = false
    private var restored = false
    private var seenMode: DictMode? = null
    private var seenScript: Script? = null
    private var loadedDay = Long.MIN_VALUE
    private var emptyJob: Job? = null
    private var settingsJob: Job? = null
    private var started = false

    private val latest = LatestOnly<SearchRequest, Unit>(scope, ::runSearch) { request, result ->
        if (request.seq != seq) return@LatestOnly
        searching = false
        result.fold(
            onSuccess = { error = null; completedSearches++ },
            // A failure after the single-word results were applied is a decoder failure: the
            // Combinations group is omitted silently (SRCH-066).
            onFailure = { if (appliedSeq != request.seq) error = errorText(it) },
        )
    }

    /** Mode of a search opened from a link in another mode; cleared by the next user edit. */
    var linkMode: com.szdict.core.api.DictMode? = null
        private set

    fun start() {
        if (started) return
        started = true
        settingsJob = scope.launch { session.settings.collect(::onSettings) }
        // A load that leaves the defaults unchanged emits nothing: watch the flag as well.
        scope.launch {
            while (!session.settingsLoaded) delay(16)
            onSettings(session.settings.value)
        }
        // Incoming `/zh/search?q=` links and shortcuts (GLB-110): applied once, as a paste.
        scope.launch {
            com.szdict.app.integration.IncomingIntents.pendingRoute.collect {
                val route = com.szdict.app.integration.IncomingIntents.consumeRouteIf { r -> r is com.szdict.core.api.Route.Search }
                if (route is com.szdict.core.api.Route.Search && route.query.isNotBlank()) {
                    if (route.mode != session.mode) {
                        // A link's mode applies to that search only; the persisted mode is untouched (GLB-110).
                        linkMode = route.mode
                        setField(TextFieldValue(route.query, TextRange(route.query.length)), user = false)
                        submit(newQuery = true)
                    } else paste(route.query)
                }
            }
        }
    }

    fun close() {
        latest.close()
        settingsJob?.cancel()
        emptyJob?.cancel()
        session.closeWordSheetOf(this)
        if (session.handwritingSink === handwritingSink) session.handwritingSink = null
    }

    // ---- settings: restore, script and mode changes ----

    private fun onSettings(settings: Settings) {
        if (!session.settingsLoaded) return
        if (!restored) {
            restored = true
            seenMode = settings.dictionaryMode
            seenScript = settings.script
            side = settings.searchSide
            // SRCH-142: input typed before the settings loaded wins over the restored query.
            if (!userEdited) setField(TextFieldValue(settings.lastSearchQuery, TextRange(settings.lastSearchQuery.length)), user = false)
            else submit(newQuery = false)
            loadEmptyState()
            return
        }
        if (settings.dictionaryMode != seenMode) {
            seenMode = settings.dictionaryMode
            seenScript = settings.script
            val q = settings.lastSearchQuery
            input = SettingsCodec.normaliseLineBreaks(q).let { TextFieldValue(it, TextRange(it.length)) }
            manualSide = false
            insideExpanded.clear()
            onScrollToTop?.invoke()
            submit(newQuery = true)
            loadEmptyState()
        } else if (settings.script != seenScript) {
            seenScript = settings.script
            submit(newQuery = false)
            loadEmptyState()
        }
    }

    // ---- input ----

    fun onFieldChange(value: TextFieldValue) = setField(value, user = true)

    /** Paste-into-search (SRCH-124): replaces the query. */
    fun paste(text: String) = setField(TextFieldValue(text, TextRange(text.length)), user = true)

    fun clear() = setField(TextFieldValue(""), user = true)

    private fun setField(value: TextFieldValue, user: Boolean) {
        val text = SettingsCodec.normaliseLineBreaks(value.text)   // SRCH-011, SRCH-141
        val next = when {
            text == value.text -> value
            text.length == value.text.length -> value.copy(text = text)
            else -> TextFieldValue(text, TextRange(minOf(value.selection.end, text.length)))
        }
        val changed = text != input.text
        input = next
        if (!changed) return
        if (user) {
            linkMode = null
            userEdited = true
            session.clearRoutes()   // SRCH-133
            session.updateSettings(debounceMs = 750) { it.withLastQuery(it.dictionaryMode, text) }   // SRCH-140
        }
        manualSide = false
        insideExpanded.clear()
        onScrollToTop?.invoke()
        submit(newQuery = true)
    }

    /** Inserts handwriting candidates at the caret (HW-004). */
    val handwritingSink: HandwritingSink = object : HandwritingSink {
        override fun insert(text: String) {
            val v = input
            val start = v.selection.min.coerceIn(0, v.text.length)
            val end = v.selection.max.coerceIn(start, v.text.length)
            setField(TextFieldValue(v.text.replaceRange(start, end, text), TextRange(start + text.length)), user = true)
        }

        override fun deleteBackward() {
            val v = input
            var start = v.selection.min.coerceIn(0, v.text.length)
            val end = v.selection.max.coerceIn(start, v.text.length)
            if (start == end) {
                if (start == 0) return
                start -= if (start >= 2 && v.text[start - 1].isLowSurrogate() && v.text[start - 2].isHighSurrogate()) 2 else 1
            }
            setField(TextFieldValue(v.text.removeRange(start, end), TextRange(start)), user = true)
        }
    }

    // ---- search ----

    private fun submit(newQuery: Boolean) {
        val q = input.text
        if (q.isBlank()) {
            // GLB-042: the empty state is applied in the same frame; everything in flight is superseded.
            seq++
            latest.invalidate()
            searching = false
            awaitingSide = false
            error = null
            show(null)
            countsCurrent = true
            return
        }
        if (newQuery) awaitingSide = true
        searching = true
        countsCurrent = false
        val mode = linkMode ?: session.mode
        latest.submit(SearchRequest(++seq, q, mode, session.settings.value.script(mode), session.dataGeneration))
    }

    private suspend fun runSearch(request: SearchRequest) {
        val dictionary = session.dictionary(request.mode)
        dictionary.searchProgressive(request.query, request.script).collect { result ->
            if (request.seq == seq) apply(request, result)
        }
    }

    private fun apply(request: SearchRequest, result: SearchResult) {
        appliedSeq = request.seq
        error = null
        countsCurrent = true
        if (result.isBlank) {   // SRCH-160: punctuation only behaves like a blank query
            awaitingSide = false
            show(null)
            return
        }
        val previous = displayed?.takeIf {
            it.result.query == result.query && it.result.script == result.script &&
                it.mode == request.mode && it.dataGeneration == request.dataGeneration
        }
        show(
            DisplayedSearch(
                result, request.mode, request.dataGeneration,
                sideState(result.chinese, previous?.chinese),
                sideState(result.english, previous?.english),
            ),
        )
        if (awaitingSide) {
            awaitingSide = false
            onScrollToTop?.invoke()
            if (!manualSide) {
                side = result.forcedSide ?: session.settings.value.searchSide   // SRCH-024
            } else if (result.forcedSide == null) {
                persistSide(side)
            }
        }
    }

    /** Hydrated windows survive a refresh of the same response (combinations arriving, SRCH-066). */
    private fun sideState(result: SideResult, previous: SideState?): SideState = SideState(
        result,
        result.sections.map { section ->
            val old = previous?.sections?.firstOrNull { it.section.key == section.key && it.section.refs == section.refs }
            SectionState(result.side, section, old?.cards?.toList())
        },
    )

    private fun show(next: DisplayedSearch?) {
        val oldPassage = displayed?.result?.chinese?.passage
        if (oldPassage !== next?.result?.chinese?.passage) session.closeWordSheetOf(this)   // TXT-045
        displayed = next
    }

    private fun errorText(e: Throwable): SearchError =
        SearchError(((e as? DictException)?.error?.message ?: e.message)?.takeIf { it.isNotBlank() })

    // ---- side ----

    /** A tap on the indicator or a settled swipe (SRCH-025): authoritative until the query changes. */
    fun onManualSide(target: SearchSide) {
        if (target == side) return
        side = target
        manualSide = true
        val current = displayed
        // SRCH-026: only ambiguous queries teach the remembered side.
        if (current != null && countsCurrent && !awaitingSide && current.result.forcedSide == null) persistSide(target)
    }

    private fun persistSide(target: SearchSide) = session.updateSettings { it.copy(searchSide = target) }

    // ---- hydration and inside words ----

    /** Hydrates the next window of [state] (SRCH-152); no-op while one is loading or when complete. */
    fun loadMore(state: SectionState) {
        if (state.loading || state.cards.size >= state.total) return
        val current = displayed ?: return
        val from = state.cards.size
        val refs = state.section.refs.subList(from, minOf(from + WINDOW, state.total))
        state.loading = true
        scope.launch {
            val cards = try {
                session.dictionary(current.mode).cards(refs, current.result.script)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyList()
            } finally {
                state.loading = false
            }
            if (state.cards.size == from) state.cards.addAll(align(refs, cards))
        }
    }

    /** Unknown ids are skipped by the engine: keep the slots aligned with the row list. */
    private fun align(refs: List<RowRef>, cards: List<EntryCard>): List<EntryCard?> {
        if (cards.size == refs.size) return cards
        var next = 0
        return refs.map { ref ->
            val card = cards.getOrNull(next)
            if (card != null && (card.id == ref.entryId || ref.entryId in card.memberIds)) { next++; card } else null
        }
    }

    fun toggleInside(key: InsideKey) {
        insideExpanded[key] = insideExpanded[key] != true
    }

    suspend fun insideWords(entryId: Int): List<InsideWord> {
        val current = displayed
        val mode = current?.mode ?: session.mode
        return session.dictionary(mode).insideWords(entryId, current?.result?.script ?: session.script)
    }

    // ---- empty state ----

    private fun loadEmptyState() {
        emptyJob?.cancel()
        val mode = session.mode
        val script = session.script
        emptyJob = scope.launch {
            try {
                val dictionary = session.dictionary(mode)
                val day = epochDay()
                wordOfDay = dictionary.wordOfTheDay(day, script, DailyPick.ALL_LEVELS)
                loadedDay = day
                popular = dictionary.popular(script).distinctBy { it.id }.take(POPULAR_COUNT)
                emptyError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyError = errorText(e)
            }
        }
    }

    /** Called periodically while the screen is visible: the word changes with the local date (SRCH-115). */
    fun refreshDay() {
        if (restored && loadedDay != Long.MIN_VALUE && epochDay() != loadedDay) loadEmptyState()
    }

    companion object {
        private var current: SearchStateHolder? = null

        /**
         * The holder of [session], created on first use and kept for the life of the process, so
         * results, counts and expanders survive a visit to another destination (GLB-050).
         */
        fun of(session: AppSession): SearchStateHolder {
            current?.let { if (it.session === session) return it else it.close() }
            return SearchStateHolder(session, session.scope).also { current = it }
        }

        const val WINDOW = 40
        const val POPULAR_COUNT = 50
    }
}
