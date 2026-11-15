package com.szdict.app.feature.entry

import com.szdict.app.ui.kit.WithTooltip
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.szdict.app.AppSession
import com.szdict.app.DetailRoute
import com.szdict.app.WordSheetRequest
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.kit.LocalDisplayPrefs
import com.szdict.app.ui.kit.LocalSheetInset
import com.szdict.app.ui.kit.UnderlineTabs
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.DictException
import com.szdict.core.api.EntryCard
import com.szdict.core.api.EntryDetail
import com.szdict.core.api.Script
import com.szdict.core.api.Segment
import com.szdict.core.api.SegmentationMode
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The word page (docs/04): one route of the detail stack. Loads the entry for the route's mode,
 * shows a skeleton / content / error (ENT-017), and keeps all per-visit state route-scoped: the
 * shell hosts this inside a `SaveableStateProvider` keyed by the route key (GLB-051).
 */
@Composable
fun EntryDetailScreen(
    session: AppSession,
    route: DetailRoute.Entry,
    twoPane: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Kept as it was while the route animates out of the stack.
    var showBack by remember(route.key) { mutableStateOf(session.showsBack(route, twoPane)) }
    if (session.routes.any { it.key == route.key }) showBack = session.showsBack(route, twoPane)
    val frame = EntryFrameState(twoPane, showBack, onBack, onClose)
    val strings = appStrings()
    val settings by session.settings.collectAsState()
    val script = settings.script(route.mode)
    val cache = remember(route.key) { EntryRouteCache.of(route.key) }
    var load by remember(route.key) {
        mutableStateOf<EntryLoad>(cache.derived?.detail?.let { EntryLoad.Ready(it) } ?: EntryLoad.Loading)
    }
    var refreshing by remember(route.key) { mutableStateOf(false) }

    // Latest-only: a new key cancels the running read, so a stale result never lands (ENT-092).
    LaunchedEffect(route.key, route.entryId, route.mode, script) {
        val shown = (load as? EntryLoad.Ready)?.detail
        if (shown != null && shown.script == script && shown.mode == route.mode) return@LaunchedEffect
        if (route.generation != session.dataGeneration.toLong()) {   // GLB-044: ids are dataset-scoped
            load = EntryLoad.Error(strings.text("Unable to load entry"))
            return@LaunchedEffect
        }
        // Previous content stays while re-fetching; the bar shows only for slow refreshes (GLB-033).
        val bar = if (shown != null) launch { delay(150); refreshing = true } else null
        load = try {
            val detail = session.dictionary(route.mode).entryDetail(route.entryId, script)
            cache.derivedFor(detail)
            EntryLoad.Ready(detail)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DictException) {
            EntryLoad.Error(strings.text("Unable to load entry") + ": " + e.error.message)
        } catch (e: Exception) {
            EntryLoad.Error(strings.text("Unable to load entry") + (e.message?.let { ": $it" } ?: ""))
        }
        bar?.cancel()
        if (refreshing) {
            delay(300)
            refreshing = false
        }
    }

    // The last ready content keeps rendering during the cross-fade to another state.
    var lastReady by remember(route.key) { mutableStateOf((load as? EntryLoad.Ready)?.detail) }
    (load as? EntryLoad.Ready)?.let { lastReady = it.detail }

    val stateKind = when (load) {
        EntryLoad.Loading -> 0
        is EntryLoad.Ready -> 1
        is EntryLoad.Error -> 2
    }
    AnimatedContent(
        targetState = stateKind,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },   // ENT-016: never a slide
        modifier = modifier.fillMaxSize(),
        label = "entry-content-state",
    ) { kind ->
        when (kind) {
            1 -> lastReady?.let { detail ->
                EntryReady(session, route, detail, cache.derivedFor(detail), cache, script, refreshing, frame)
            }
            2 -> EntryFrame(title = null, frame = frame) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        (load as? EntryLoad.Error)?.message ?: strings.text("Unable to load entry"),
                        style = MaterialTheme.typography.titleMedium,
                        color = LocalDictionaryPalette.current.muted,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            else -> EntryFrame(title = null, frame = frame) { LoadingSkeleton() }
        }
    }
}

/** What the app bar of a route offers: back only where there is something to go back to, close in two-pane. */
private class EntryFrameState(val twoPane: Boolean, val showBack: Boolean, val onBack: () -> Unit, val onClose: () -> Unit)

/** App bar with working back (and close when two-pane) around every content state (ENT-017). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryFrame(
    title: String?,
    frame: EntryFrameState,
    titleVisible: Boolean = false,
    content: @Composable () -> Unit,
) {
    val twoPane = frame.twoPane
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = palette.bg,
        // Bottom excluded on purpose: pages pad it themselves so content scrolls beneath the
        // transparent navigation bar (ENT-091).
        // Side pane: its start edge is the divider, never the window edge, so the cutout / rail inset on
        // the start side is not applied again here (the rail consumed it); the end side still is.
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + if (twoPane) WindowInsetsSides.End else WindowInsetsSides.Horizontal,
        ),
        topBar = {
            TopAppBar(
                expandedHeight = if (twoPane) 48.dp else TopAppBarDefaults.TopAppBarExpandedHeight,
                title = {
                    AnimatedVisibility(visible = titleVisible && title != null, enter = fadeIn(), exit = fadeOut()) {
                        Text(title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    if (frame.showBack) {
                        WithTooltip(strings.text("Back")) {
                            IconButton(onClick = frame.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.text("Back")) }
                        }
                    }
                },
                actions = {
                    if (twoPane) WithTooltip(strings.text("Close detail")) {
                        IconButton(onClick = frame.onClose) { Icon(Icons.Rounded.Close, contentDescription = strings.text("Close detail")) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = palette.bg,
                    titleContentColor = palette.fg,
                    navigationIconContentColor = palette.muted,
                    actionIconContentColor = palette.muted,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

/** ENT-018: approximates the real header. */
@Composable
private fun LoadingSkeleton() {
    val palette = LocalDictionaryPalette.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("entry-detail-loading-skeleton")) {
        Spacer(Modifier.height(12.dp))
        SkeletonBar(126.dp, 42.dp)
        Spacer(Modifier.height(10.dp))
        SkeletonBar(152.dp, 18.dp)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) { Box(Modifier.weight(1f).height(30.dp).clip(MaterialTheme.shapes.small).background(palette.bgSoft)) }
        }
        Spacer(Modifier.height(24.dp))
        listOf(0.92f, 0.78f, 0.64f).forEachIndexed { index, fraction ->
            Box(Modifier.fillMaxWidth(fraction).height(14.dp).clip(MaterialTheme.shapes.extraSmall).background(palette.bgSoft))
            if (index < 2) Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun SkeletonBar(width: Dp, height: Dp, modifier: Modifier = Modifier) {
    Box(modifier.width(width).height(height).clip(MaterialTheme.shapes.small).background(LocalDictionaryPalette.current.bgSoft))
}

/** Callbacks and context shared by the tabs of one route. */
@Stable
internal class EntryActions(
    val session: AppSession,
    val route: DetailRoute.Entry,
    val script: Script,
    val onRef: (Segment.Ref) -> Unit,
    val openEntry: (Int) -> Unit,
    /** Opens the word-candidate sheet with fixed rows (ENT-054, ENT-122). */
    val openCards: (title: String, form: String, cards: List<EntryCard>) -> Unit,
)

@Composable
private fun EntryReady(
    session: AppSession,
    route: DetailRoute.Entry,
    detail: EntryDetail,
    derived: EntryDerived,
    cache: EntryRouteCache,
    script: Script,
    refreshing: Boolean,
    frame: EntryFrameState,
) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val scope = rememberCoroutineScope()

    // ---- optional assets: stroke graphics of the displayed headword (ENT-030, ENT-108) ----
    val provider = rememberStrokeProvider()
    var strokes by remember(route.key) { mutableStateOf(cache.strokes.takeIf { cache.strokesFor == detail.characters }) }
    LaunchedEffect(detail.characters) {
        if (cache.strokesFor == detail.characters && cache.strokes != null) {
            strokes = cache.strokes
            return@LaunchedEffect
        }
        val loaded = loadStrokeOrders(provider, detail.characters)
        cache.strokes = loaded
        cache.strokesFor = detail.characters
        strokes = loaded
    }
    val strokeCharacters = remember(strokes, detail.characters) {
        detail.characters.filter { (strokes?.get(it)?.strokeCount ?: 0) > 0 }
    }
    val tabs = remember(strokeCharacters.isNotEmpty()) { entryTabs(strokeCharacters.isNotEmpty()) }
    // The DICT tab's Parts section: none for a headword without Hanzi (Latin-only entries).
    val parts = if (!hasPartsSection(detail.characters)) null else PartsStates.of(derived) {
        PartsState(
            characters = detail.characters,
            loadParts = { session.dictionary(route.mode).characterParts(it, script, detail.id) },
            loadAppears = { c, offset, limit -> session.dictionary(route.mode).appearsIn(c, script, offset, limit) },
        )
    }

    // ---- definition references (ENT-054, ENT-095: only the latest lookup survives) ----
    var lookupStatus by remember(route.key) { mutableStateOf<String?>(null) }
    val lookupJob = remember(route.key) { arrayOfNulls<Job>(1) }
    val openCards: (String, String, List<EntryCard>) -> Unit = { title, form, cards ->
        scope.launch {
            val generation = runCatching { session.dictionary(route.mode).generation }.getOrDefault(0L)
            session.openWordSheet(
                WordSheetRequest(
                    owner = "entry-${route.key}",
                    passage = AnalysedPassage.empty(form, route.mode, SegmentationMode.HANZI, generation),
                    offset = 0,
                    mode = route.mode,
                    fixedTitle = title,
                    fixedCards = cards,
                    nested = true,
                ),
            )
        }
    }
    val actions = remember(route.key, script, strings) {
        EntryActions(
            session = session,
            route = route,
            script = script,
            onRef = { ref ->
                lookupJob[0]?.cancel()
                lookupStatus = null
                // ENT-054: a reference always offers its entry plus the words inside it; only a
                // reference with a single candidate (one character, one reading) opens directly.
                lookupJob[0] = scope.launch {
                    try {
                        val found = session.dictionary(route.mode)
                            .resolveReference(ref.traditional, ref.simplified, ref.readingCanonical, script)
                        val resolved = found.matches.firstOrNull { it.id == ref.targetId }
                        val matches = if (resolved != null) listOf(resolved) + found.matches.filter { it.id != resolved.id } else found.matches
                        val cards = (matches + found.inside.map { it.card }).distinctBy { it.id }
                        when {
                            cards.isEmpty() -> ref.targetId?.let { session.openEntry(it, route.mode, nested = true) }
                                ?: run { lookupStatus = strings.text("No dictionary entry") }
                            cards.size == 1 -> session.openEntry(cards[0].id, route.mode, nested = true)
                            // ENT-106: never silently pick one of several readings.
                            else -> openCards(strings.text("Words at 「{0}」", found.form), found.form, cards)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lookupStatus = strings.text("Unable to load word choices") + (e.message?.let { ": $it" } ?: "")
                    }
                }
            },
            openEntry = { id -> session.openEntry(id, route.mode, nested = true) },
            openCards = openCards,
        )
    }

    // ---- route-scoped UI state (ENT-023, ENT-033) ----
    val headerState = rememberSaveable(saver = CollapsingHeaderState.Saver) { CollapsingHeaderState() }
    var savedTab by rememberSaveable { mutableStateOf(EntryTab.DICT.name) }
    val dictList = rememberLazyListState()
    val strokesList = rememberLazyListState()
    val wordsList = rememberLazyListState()
    val titleVisible by remember { derivedStateOf { headerState.collapseFraction > 0.95f } }

    EntryFrame(title = detail.card.headword, frame = frame, titleVisible = titleVisible) {
        Box(Modifier.fillMaxSize().clipToBounds().nestedScroll(headerState.connection)) {
            Column(Modifier.collapsingHeader(headerState)) {
                EntryHeader(
                    session = session,
                    route = route,
                    detail = detail,
                    assetsLoading = strokes == null,
                    status = lookupStatus,
                    modifier = Modifier.fillMaxWidth().onSizeChanged { headerState.updateHeaderHeight(it.height) },
                )
                // ENT-035: a pager must never outlive the tab list its item provider was built for.
                key(tabs) {
                    val pagerState = rememberPagerState(initialPage = pageForTab(tabs, restoredTab(tabs, savedTab))) { tabs.size }
                    LaunchedEffect(pagerState, tabs) {
                        // ENT-034: the route tab is authoritative over the pager's own saved page.
                        val restored = pageForTab(tabs, restoredTab(tabs, savedTab))
                        if (pagerState.currentPage != restored) pagerState.scrollToPage(restored)
                        // Only later page changes are the user's: the first emission is the restored page,
                        // which must not overwrite a saved STROKES tab while stroke data is still loading.
                        snapshotFlow { pagerState.settledPage }.drop(1).collect { page -> tabs.getOrNull(page)?.let { savedTab = it.name } }
                    }
                    UnderlineTabs(
                        labels = tabs.map { it.label },
                        selectedIndex = pagerState.currentPage.coerceIn(0, tabs.lastIndex),
                        onSelect = { index ->
                            tabs.getOrNull(index)?.let { tab ->
                                savedTab = tab.name
                                scope.launch { pagerState.animateScrollToPage(index) }
                            }
                        },
                    )
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        key = { tabKey(tabs, it) },
                        verticalAlignment = Alignment.Top,
                    ) { page ->
                        when (tabs.getOrNull(page)) {
                            EntryTab.DICT -> TabPage(dictList) { dictTabItems(detail, derived, parts, actions, dictList) }
                            EntryTab.STROKES -> TabPage(strokesList) {
                                item(key = "strokes") {
                                    StrokesTab(strokeCharacters.mapNotNull { strokes?.get(it) }, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                                }
                            }
                            EntryTab.WORDS -> TabPage(wordsList) { wordsTabItems(derived, actions, strings, wordsList) }
                            null -> Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }
            if (refreshing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = palette.accent, trackColor = palette.border)
            }
        }
    }
}

/** One tab page: its own lazy list, padded past the navigation bar and any open sheet (ENT-091). */
@Composable
private fun TabPage(state: LazyListState, content: LazyListScope.() -> Unit) {
    val bottomInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(bottom = 24.dp + bottomInset + LocalSheetInset.current),
        content = content,
    )
}

/**
 * Collapse state shared by every tab (ENT-022): upward scroll shrinks the header first, downward
 * scroll at list top restores it. Vertical only — horizontal deltas belong to the pager (ENT-090).
 */
@Stable
internal class CollapsingHeaderState(initialCollapse: Float = 0f) {
    var headerHeightPx by mutableFloatStateOf(0f)
        private set
    var collapsePx by mutableFloatStateOf(initialCollapse)
        private set
    private var restoredPx = initialCollapse

    val collapseFraction: Float
        get() = if (headerHeightPx <= 0f) 0f else (collapsePx / headerHeightPx).coerceIn(0f, 1f)

    fun updateHeaderHeight(height: Int) {
        headerHeightPx = height.toFloat()
        // The restored collapse is applied once the header has been measured (ENT-023).
        collapsePx = (if (restoredPx > 0f) restoredPx else collapsePx).coerceIn(0f, headerHeightPx)
        restoredPx = 0f
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy >= 0f) return Offset.Zero
            val room = headerHeightPx - collapsePx
            if (room <= 0f) return Offset.Zero
            val used = (-dy).coerceAtMost(room)
            collapsePx += used
            return Offset(0f, -used)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy <= 0f || collapsePx <= 0f) return Offset.Zero
            val used = dy.coerceAtMost(collapsePx)
            collapsePx -= used
            return Offset(0f, used)
        }
    }

    companion object {
        val Saver = Saver<CollapsingHeaderState, Float>(save = { it.collapsePx }, restore = { CollapsingHeaderState(it) })
    }
}

/**
 * Placement-only collapse (ENT-022): the child is measured taller by the expanded header height so
 * the pager keeps a constant measured height, then translated by the collapse amount.
 */
private fun Modifier.collapsingHeader(state: CollapsingHeaderState): Modifier = layout { measurable, constraints ->
    val extra = state.headerHeightPx.roundToInt()
    val placeable = measurable.measure(constraints.copy(minHeight = constraints.maxHeight + extra, maxHeight = constraints.maxHeight + extra))
    layout(placeable.width, constraints.maxHeight) { placeable.place(0, -state.collapsePx.roundToInt()) }
}
