package com.szdict.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.szdict.app.feature.entry.EntryDetailScreen
import com.szdict.app.feature.handwriting.HandwritingSheet
import com.szdict.app.feature.hsk.HskScreen
import com.szdict.app.feature.ocr.OcrScreen
import com.szdict.app.feature.reading.ReadingScreen
import com.szdict.app.feature.search.SearchScreen
import com.szdict.app.feature.settings.AttributionScreen
import com.szdict.app.feature.settings.PrivacyScreen
import com.szdict.app.feature.settings.SettingsScreen
import com.szdict.app.feature.voice.VoiceScreen
import com.szdict.app.i18n.AppStrings
import com.szdict.app.i18n.LocalStrings
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.kit.DisplayPrefs
import com.szdict.app.ui.kit.LocalDisplayPrefs
import com.szdict.app.ui.kit.LocalSession
import com.szdict.app.ui.kit.LocalSheetInset
import com.szdict.app.ui.kit.WordSheet
import com.szdict.app.ui.kit.imeVisible
import com.szdict.app.ui.theme.DictionaryTheme
import com.szdict.app.ui.theme.LocalDictionaryPalette

/**
 * App shell: theme, primary navigation (PROD-050), the list-detail layout (GLB-125), the detail
 * route stack host (ENT-010…), the word and handwriting sheets, and back precedence (PROD-056).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun App(session: AppSession) {
    val settings by session.settings.collectAsState()
    val lang = settings.uiLanguage.resolve(session.env.systemLocaleTag())
    val strings = remember(lang) { AppStrings(lang) }
    val prefs = remember(settings.toneColors, settings.showPronunciation, settings.showBoundaries) {
        DisplayPrefs(settings.toneColors, settings.showPronunciation, settings.showBoundaries)
    }
    // A linked route renders in its own mode (PROD-057); the palette follows what is on screen.
    val visibleMode = session.routeMode()
    DictionaryTheme(settings.themeMode, visibleMode) {
        CompositionLocalProvider(LocalStrings provides strings, LocalDisplayPrefs provides prefs, LocalSession provides session) {
            val palette = LocalDictionaryPalette.current
            BoxWithConstraints(Modifier.fillMaxSize().background(palette.bg)) {
                val compact = maxWidth < 600.dp
                val twoPane = maxWidth >= 840.dp && session.destination in setOf(Destination.SEARCH, Destination.HSK, Destination.READING)
                val destinations = remember(session.env.voiceAvailable) {
                    Destination.entries.filter { it != Destination.VOICE || session.env.voiceAvailable }
                }
                var wordSheetHeight by remember { mutableStateOf(0.dp) }
                var handwritingHeight by remember { mutableStateOf(0.dp) }
                if (session.wordSheet == null) wordSheetHeight = 0.dp
                if (!session.handwritingVisible) handwritingHeight = 0.dp
                val imeVisible = imeVisible()
                val topRoute = session.routes.lastOrNull()
                val fullScreenRoute = topRoute != null && (!twoPane || topRoute !is DetailRoute.Entry)
                // Settings-family routes keep the primary navigation visible (SET-005); an entry covers it.
                val coversNavigation = topRoute is DetailRoute.Entry && !twoPane
                val showBottomBar = compact && !imeVisible && !session.handwritingVisible && !coversNavigation

                // A route that covers the destination takes the focus away from it: no caret handle,
                // keyboard or handwriting sheet of a covered field survives or comes back (GLB-059).
                val focusManager = LocalFocusManager.current
                val softKeyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(fullScreenRoute) {
                    if (fullScreenRoute) {
                        focusManager.clearFocus(force = true)
                        softKeyboard?.hide()
                        session.handwritingVisible = false
                    }
                }

                // Back precedence, innermost first (PROD-056, ARCH-052). Later handlers win.
                BackHandler(enabled = session.routes.isNotEmpty()) { session.routes.lastOrNull()?.let { session.popRoute(it.key) } }
                BackHandler(enabled = session.destination == Destination.OCR && session.ocrReviewBack != null && session.routes.isEmpty()) {
                    session.ocrReviewBack?.invoke()
                }
                BackHandler(enabled = session.handwritingVisible) { session.handwritingVisible = false }
                BackHandler(enabled = session.wordSheet != null) { session.closeWordSheet() }

                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        if (!compact) {
                            AppRail(destinations, session.destination, session::selectDestination)
                        }
                        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                            val sheetInset = maxOf(wordSheetHeight, handwritingHeight)
                            // Full height stops short of the status bar (TXT-040).
                            val sheetMaxHeight = maxHeight - WindowInsets.statusBars.asPaddingValues().calculateTopPadding() - 8.dp
                            CompositionLocalProvider(LocalSheetInset provides sheetInset) {
                                val stateHolder = rememberSaveableStateHolder()
                                // Saved state of the detail routes, keyed per visit (GLB-051).
                                val routeStates = rememberSaveableStateHolder()
                                // Popping a route releases its saved state (GLB-052).
                                val liveRoutes = session.routes.map { it.key }
                                val knownRoutes = remember { HashSet<Long>() }
                                SideEffect {
                                    knownRoutes.filter { it !in liveRoutes }.forEach { routeStates.removeState("route-$it") }
                                    knownRoutes.clear()
                                    knownRoutes.addAll(liveRoutes)
                                }
                                // A covered destination is neither focusable nor read by a screen reader.
                                val covered = if (fullScreenRoute) {
                                    Modifier.focusProperties { canFocus = false }.clearAndSetSemantics { }
                                } else Modifier
                                Row(Modifier.fillMaxSize().then(covered)) {
                                    Box(Modifier.weight(1f).fillMaxHeight()) {
                                        stateHolder.SaveableStateProvider("dest-${session.destination}-${session.dataGeneration}") {
                                            when (session.destination) {
                                                Destination.SEARCH -> SearchScreen(session, twoPane)
                                                Destination.OCR -> OcrScreen(session)
                                                Destination.VOICE -> VoiceScreen(session)
                                                Destination.READING -> ReadingScreen(session)
                                                Destination.HSK -> HskScreen(session)
                                            }
                                        }
                                    }
                                    if (twoPane) {
                                        Box(Modifier.width(1.dp).fillMaxHeight().background(palette.border))
                                        // ENT-019: the side pane with nothing selected.
                                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                            Text(
                                                strings.text("Select an entry to see its detail"),
                                                color = palette.muted,
                                                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                            )
                                        }
                                    }
                                }
                                // One host at one place in the composition, laid out over the side pane
                                // or the whole screen: a route keeps its tab and scroll when a rotation
                                // moves it between the two (GLB-050).
                                val sidePane = twoPane && topRoute is DetailRoute.Entry
                                DetailHost(
                                    session, routeStates, twoPane,
                                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(if (sidePane) 0.5f else 1f),
                                )
                            }
                            StatusBarScrim(Modifier.align(Alignment.TopCenter))
                            session.wordSheet?.let { request ->
                                WordSheet(
                                    session = session,
                                    request = request,
                                    maxHeight = sheetMaxHeight,
                                    onVisibleHeight = { wordSheetHeight = it },
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                )
                            }
                            androidx.compose.animation.AnimatedVisibility(
                                visible = session.handwritingVisible,
                                enter = slideInVertically { it },
                                exit = slideOutVertically { it },
                                modifier = Modifier.align(Alignment.BottomCenter),
                            ) {
                                HandwritingSheet(session, onVisibleHeight = { handwritingHeight = it })
                            }
                        }
                    }
                    // Never beside the rail: when the width leaves the compact class the bar is gone in
                    // that frame instead of animating out next to it.
                    if (compact) {
                        AnimatedVisibility(showBottomBar) {
                            AppBottomBar(destinations, session.destination, session::selectDestination)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Hosts the detail stack: the top route, over the whole screen in single-pane and for the
 * Settings-family routes, over the side pane for entries in two-pane (the caller sizes it).
 */
@Composable
private fun DetailHost(session: AppSession, stateHolder: SaveableStateHolder, twoPane: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalDictionaryPalette.current
    val shown: DetailRoute? = session.routes.lastOrNull()
    val depth = session.routes.size
    var lastDepth by remember { mutableStateOf(depth) }
    val forward = depth >= lastDepth
    lastDepth = depth
    AnimatedContent(
        targetState = shown,
        contentKey = { it?.key ?: 0L },
        transitionSpec = {
            // ENT-016: forward slides in from the trailing edge; back mirrors.
            if (forward) {
                (slideInHorizontally(tween(220)) { it } + fadeIn(tween(220), initialAlpha = 0.72f)) togetherWith
                    (slideOutHorizontally(tween(220)) { -it / 8 } + fadeOut(tween(220), targetAlpha = 0.88f))
            } else {
                (slideInHorizontally(tween(220)) { -it / 8 } + fadeIn(tween(220), initialAlpha = 0.88f)) togetherWith
                    (slideOutHorizontally(tween(220)) { it } + fadeOut(tween(220), targetAlpha = 0.72f))
            }
        },
        modifier = modifier,
    ) { route ->
        when (route) {
            null -> Unit
            else -> stateHolder.SaveableStateProvider("route-${route.key}") {
                // The route is opaque to touches too: nothing reaches the screen it covers.
                Box(Modifier.fillMaxSize().background(palette.bg).pointerInput(Unit) { }) {
                    val onBack = { session.popRoute(route.key); Unit }
                    when (route) {
                        is DetailRoute.Entry -> EntryDetailScreen(session, route, twoPane, onBack, onClose = session::clearRoutes)
                        is DetailRoute.SettingsRoute -> SettingsScreen(session, onBack)
                        is DetailRoute.Privacy -> PrivacyScreen(session, onBack)
                        is DetailRoute.Attribution -> AttributionScreen(session, onBack)
                    }
                }
            }
        }
    }
}

/** GLB-071: a gradient scrim keeps content legible beneath the transparent status bar. */
@Composable
private fun StatusBarScrim(modifier: Modifier = Modifier) {
    val palette = LocalDictionaryPalette.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(modifier.fillMaxWidth().height(top + 12.dp).background(Brush.verticalGradient(listOf(palette.bg, palette.bg.copy(alpha = 0f)))))
}

private fun icon(destination: Destination): ImageVector = when (destination) {
    Destination.SEARCH -> Icons.Filled.Search
    Destination.OCR -> Icons.Filled.CameraAlt
    Destination.VOICE -> Icons.Filled.Mic
    Destination.READING -> Icons.AutoMirrored.Filled.MenuBook
    Destination.HSK -> Icons.Filled.School
}

private fun label(destination: Destination): String = when (destination) {
    Destination.SEARCH -> "Search"
    Destination.OCR -> "OCR"
    Destination.VOICE -> "Voice"
    Destination.READING -> "Reading"
    Destination.HSK -> "HSK"
}

@Composable
private fun AppBottomBar(destinations: List<Destination>, selected: Destination, onSelect: (Destination) -> Unit) {
    val palette = LocalDictionaryPalette.current
    val strings = appStrings()
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
        NavigationBar(containerColor = palette.panel, tonalElevation = 0.dp) {
            destinations.forEach { destination ->
                NavigationBarItem(
                    selected = destination == selected,
                    onClick = { onSelect(destination) },
                    icon = { Icon(icon(destination), null) },
                    label = { Text(strings.text(label(destination))) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = palette.accent, selectedTextColor = palette.accent,
                        unselectedIconColor = palette.muted, unselectedTextColor = palette.muted,
                        indicatorColor = palette.accentSoft,
                    ),
                )
            }
        }
    }
}

@Composable
private fun AppRail(destinations: List<Destination>, selected: Destination, onSelect: (Destination) -> Unit) {
    val palette = LocalDictionaryPalette.current
    val strings = appStrings()
    NavigationRail(containerColor = palette.panel) {
        destinations.forEach { destination ->
            NavigationRailItem(
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = { Icon(icon(destination), null) },
                label = { Text(strings.text(label(destination))) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = palette.accent, selectedTextColor = palette.accent,
                    unselectedIconColor = palette.muted, unselectedTextColor = palette.muted,
                    indicatorColor = palette.accentSoft,
                ),
            )
        }
    }
}
