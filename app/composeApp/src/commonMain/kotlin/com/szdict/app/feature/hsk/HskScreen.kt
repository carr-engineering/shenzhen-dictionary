package com.szdict.app.feature.hsk

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.szdict.app.AppSession
import com.szdict.app.Destination
import com.szdict.app.DetailRoute
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.kit.CardAudio
import com.szdict.app.ui.kit.EntryCardView
import com.szdict.app.ui.kit.HskBadge
import com.szdict.app.ui.kit.LocalSheetInset
import com.szdict.app.ui.kit.SegmentedPillRow
import com.szdict.app.ui.kit.hskLevelLabel
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.DictMode
import kotlinx.coroutines.launch

internal enum class HskPhase { LOADING, EMPTY, CONTENT }

internal fun hskPhase(loading: Boolean, model: HskGridModel?): HskPhase = when {
    model != null && model.items.isNotEmpty() -> HskPhase.CONTENT
    loading -> HskPhase.LOADING
    else -> HskPhase.EMPTY
}

/**
 * The HSK destination (HSK-001…): a Words / Characters switch, a floating level selector and a
 * level-tinted masonry grid of shared entry cards, in the dataset's storage order.
 */
@Composable
fun HskScreen(session: AppSession, modifier: Modifier = Modifier) {
    val state = remember(session) { HskStates.of(session) }
    val settings by session.settings.collectAsState()
    val mode = state.linkMode ?: settings.dictionaryMode
    val script = settings.script(mode)
    val section = state.section
    val generation = session.dataGeneration
    // HSK-060: the data loads on first navigation here; a cached model is reused as is (HSK-061).
    LaunchedEffect(section, mode, script, generation) { state.ensure(section, mode, script) }

    val slot = state.slot(section)
    val model = slot.model
    val phase = hskPhase(slot.loading, model)
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val headerHeight = with(LocalDensity.current) { headerHeightPx.toDp() }
    val canScrollUp = phase == HskPhase.CONTENT && slot.grid.canScrollBackward

    // Chip navigation (HSK-012): the tapped chip stays selected while its scroll animation runs.
    val scope = rememberCoroutineScope()
    var requestedLevel by remember(section) { mutableStateOf<Int?>(null) }
    var navigationRequest by remember { mutableIntStateOf(0) }
    val topLevel by remember(model, slot) { derivedStateOf { model?.levelAt(slot.grid.firstVisibleItemIndex) } }

    // Deep-linked level, or a chip tapped before its level finished loading.
    val pending = state.pendingLevel
    LaunchedEffect(pending, model) {
        val target = pending?.let { model?.headerIndexByLevel?.get(it) }
        if (pending != null && target != null) {
            slot.grid.scrollToItem(target)
            state.pendingLevel = null
            requestedLevel = null
        } else if (pending != null && model?.complete == true) {
            state.pendingLevel = null
            requestedLevel = null
        }
    }

    // PROD-053: tapping the selected destination again scrolls the grid to the top.
    val reselect = session.reselect.value
    val reselectAtEntry = remember { reselect }
    LaunchedEffect(reselect) {
        if (reselect != reselectAtEntry && reselect.second == Destination.HSK) slot.grid.animateScrollToItem(0)
    }

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(phase == HskPhase.LOADING, enter = EnterTransition.None, exit = fadeOut(tween(120))) {
            HskSkeleton(headerHeight)
        }
        AnimatedVisibility(phase != HskPhase.LOADING, enter = fadeIn(tween(200)), exit = ExitTransition.None) {
            if (phase == HskPhase.CONTENT && model != null) {
                key(section) { HskGrid(session, slot, model, section, headerHeight) }
            } else {
                // HSK-064
                Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        slot.error?.let { appStrings().text("Unable to load HSK words") } ?: appStrings().text("No HSK data"),
                        style = MaterialTheme.typography.titleMedium,
                        color = LocalDictionaryPalette.current.muted,
                    )
                }
            }
        }
        HskHeader(
            section = section,
            onSection = { state.section = it },
            levels = model?.levels,
            selectedLevel = requestedLevel ?: topLevel,
            linkMode = state.linkMode,
            showPronunciation = settings.showPronunciation,
            onTogglePronunciation = { session.updateSettings { it.copy(showPronunciation = !it.showPronunciation) } },
            canScrollUp = canScrollUp,
            onHeight = { headerHeightPx = it },
            onLevel = { level ->
                val target = model?.headerIndexByLevel?.get(level)
                val request = ++navigationRequest
                requestedLevel = level
                if (target == null) {
                    state.pendingLevel = level
                } else {
                    scope.launch {
                        try {
                            // The grid's top content padding already reserves the header.
                            slot.grid.animateScrollToItem(target, 0)
                        } finally {
                            if (navigationRequest == request) requestedLevel = null
                        }
                    }
                }
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun HskGrid(session: AppSession, slot: HskState.Slot, model: HskGridModel, section: HskSection, headerHeight: Dp) {
    val palette = LocalDictionaryPalette.current
    val mode = model.identity.mode
    // GLB-030: a superseded grid stays visible but inert until its replacement arrives (HSK-085).
    val stale = slot.loading && slot.wanted != model.identity
    val playing by session.playing.collectAsState()
    val selectedId = (session.routes.lastOrNull() as? DetailRoute.Entry)?.takeIf { it.mode == mode }?.entryId
    val bottomInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = hskColumnCount(maxWidth.value)
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(columns),
            state = slot.grid,
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 0.dp,
            // HSK-014
            contentPadding = PaddingValues(top = headerHeight + 4.dp, bottom = bottomInset + 16.dp + LocalSheetInset.current),
        ) {
            items(
                count = model.items.size,
                key = { model.items[it].key },
                contentType = { if (model.items[it] is HskGridItem.LevelHeader) "header" else "card" },
                span = {
                    if (model.items[it] is HskGridItem.LevelHeader) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane
                },
            ) { index ->
                when (val item = model.items[index]) {
                    is HskGridItem.LevelHeader -> Column {
                        Spacer(Modifier.height(14.dp))
                        HskBadge(levels = listOf(item.level), countsByLevel = mapOf(item.level to item.count))
                        Spacer(Modifier.height(8.dp))
                    }
                    is HskGridItem.Entry -> {
                        val card = item.card
                        EntryCardView(
                            card = card,
                            onClick = { if (!stale) session.openEntry(card.id, mode) },
                            selected = card.id == selectedId,
                            containerColor = palette.hskTint(item.level),
                            // HSK-025/066: the plan is resolved when played; hidden readings never hide the button.
                            audio = CardAudio(playing == card.id) { if (!stale) session.play(card.id, card.audio, mode) },
                            // HSK-098: a character card never carries an HSK badge.
                            showHsk = section == HskSection.WORDS,
                        )
                    }
                }
            }
        }
    }
}

/** Floating selector surface (HSK-011…016): translucent, with a bottom-only shadow (GLB-074). */
@Composable
private fun HskHeader(
    section: HskSection,
    onSection: (HskSection) -> Unit,
    levels: List<HskLevelCount>?,
    selectedLevel: Int?,
    linkMode: DictMode?,
    showPronunciation: Boolean,
    onTogglePronunciation: () -> Unit,
    canScrollUp: Boolean,
    onHeight: (Int) -> Unit,
    onLevel: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDictionaryPalette.current
    val strings = appStrings()
    val shadowAlpha by animateFloatAsState(if (canScrollUp) 1f else 0f)
    val chipRow = rememberLazyListState()
    val leading = if (linkMode != null) 2 else 1
    // Keep the selected chip in view as the grid scrolls through the levels.
    LaunchedEffect(selectedLevel, levels?.size) {
        val index = levels?.indexOfFirst { it.level == selectedLevel } ?: -1
        if (index > 0 && chipRow.layoutInfo.visibleItemsInfo.none { it.index == index + leading && it.offset >= 0 &&
                it.offset + it.size <= chipRow.layoutInfo.viewportEndOffset }) {
            chipRow.animateScrollToItem(index + leading)
        } else if (index == 0) {
            chipRow.animateScrollToItem(0)
        }
    }
    Column(modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth().onSizeChanged { onHeight(it.height) },
            color = palette.bg.copy(alpha = 0.88f),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(
                    state = chipRow,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 14.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item(key = "section") {
                        SegmentedPillRow(
                            options = listOf(strings.text("Words"), strings.text("Characters")),
                            selectedIndex = section.ordinal,
                            onSelect = { onSection(HskSection.entries[it]) },
                        )
                    }
                    if (linkMode != null) {
                        // GLB-110: a link-mode screen indicates that it differs from the persisted mode.
                        item(key = "mode") {
                            Text(
                                if (linkMode == DictMode.CANTONESE) "粵語" else "中文",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.accent,
                                modifier = Modifier.background(palette.accentSoft, CircleShape).padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                    if (levels == null) {
                        // HSK-063: three grey chip placeholders while the first load runs.
                        items(3, key = { "placeholder-$it" }) {
                            Surface(Modifier.width(92.dp).height(36.dp), shape = RoundedCornerShape(12.dp), color = palette.bgSoft,
                                border = BorderStroke(1.dp, palette.border)) {}
                        }
                    } else {
                        items(levels, key = { "level-${it.level}" }) { level ->
                            LevelChip(level, selected = level.level == selectedLevel, onClick = { onLevel(level.level) })
                        }
                    }
                }
                PronunciationToggle(showPronunciation, onTogglePronunciation)
                Spacer(Modifier.width(12.dp))
            }
        }
        Box(
            Modifier.fillMaxWidth().height(8.dp).background(
                Brush.verticalGradient(listOf(palette.border.copy(alpha = 0.52f * shadowAlpha), palette.border.copy(alpha = 0f)))
            )
        )
    }
}

@Composable
private fun LevelChip(level: HskLevelCount, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalDictionaryPalette.current
    val color = palette.hsk(level.level)
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            color = if (selected) palette.hskTint(level.level) else palette.panel,
            border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) color else palette.border),
            modifier = Modifier.semantics { role = Role.Tab },
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(hskLevelLabel(level.level), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = color, maxLines = 1)
                Text(level.count.toString(), style = MaterialTheme.typography.labelSmall, color = palette.muted, maxLines = 1)
            }
        }
    }
}

/** The global pronunciation-visibility toggle (GLB-120, HSK-015): one persisted setting, shared app-wide. */
@Composable
internal fun PronunciationToggle(shown: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalDictionaryPalette.current
    val strings = appStrings()
    // GLB-080: labelled by what a tap does, as on every other screen.
    val label = strings.text(if (shown) "Hide pronunciation" else "Show pronunciation")
    val stateText = if (shown) strings.text("On") else strings.text("Off")
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = onToggle,
            shape = CircleShape,
            color = if (shown) palette.accentSoft else palette.panel,
            contentColor = if (shown) palette.accent else palette.muted,
            border = BorderStroke(1.dp, if (shown) palette.accent.copy(alpha = 0.35f) else palette.border),
            modifier = modifier.size(36.dp).semantics {
                role = Role.Switch
                contentDescription = label
                stateDescription = stateText
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(if (shown) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Shape-matched loading placeholder (HSK-063): cards of varied heights, not scrollable. */
@Composable
private fun HskSkeleton(headerHeight: Dp) {
    val bottomInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    val statusTop = WindowInsets.safeDrawing.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
    val top = if (headerHeight > 0.dp) headerHeight else statusTop + 56.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(hskColumnCount(maxWidth.value)),
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 0.dp,
            contentPadding = PaddingValues(top = top + 4.dp, bottom = bottomInset + 16.dp),
        ) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    SkeletonBar(0.18f, 20.dp)
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(HskSkeletonHeights.size, key = { "skeleton-$it" }) { SkeletonCard(HskSkeletonHeights[it].dp) }
        }
    }
}

@Composable
private fun SkeletonCard(height: Dp) {
    val palette = LocalDictionaryPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(height),
        shape = MaterialTheme.shapes.medium,
        color = palette.panel,
        border = BorderStroke(1.dp, palette.border),
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SkeletonBar(0.5f, 25.dp)
            SkeletonBar(0.35f, 15.dp)
            Spacer(Modifier.height(2.dp))
            SkeletonBar(0.9f, 13.dp)
            SkeletonBar(0.72f, 13.dp)
            Spacer(Modifier.weight(1f))
            SkeletonBar(0.28f, 18.dp)
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: Dp) {
    Box(Modifier.fillMaxWidth(widthFraction).height(height).background(LocalDictionaryPalette.current.bgSoft, CircleShape))
}
