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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
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
                val navigation = navigationStyle(maxWidth)
                val compact = navigation == NavigationStyle.BOTTOM_BAR
                val twoPane = fitsTwoPanes(maxWidth) && session.destination in setOf(Destination.SEARCH, Destination.HSK, Destination.READING)
                val destinations = remember(session.env.voiceAvailable, session.env.settingsIsDestination) {
                    navigationDestinations(session.env.voiceAvailable, session.env.settingsIsDestination)
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
                // The word sheet gets the bar's room too; the sheet reports its own height, so content stays clear of it.
                val showBottomBar = compact && !imeVisible && !session.handwritingVisible && !coversNavigation && session.wordSheet == null

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
                        when (navigation) {
                            NavigationStyle.BOTTOM_BAR -> Unit
                            NavigationStyle.RAIL -> AppRail(destinations, session.destination, session::selectDestination)
                            NavigationStyle.EXPANDED_RAIL -> AppExpandedRail(destinations, session.destination, session::selectDestination)
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
                                // Wide windows: each pane keeps a readable width and the pair is centred (GLB-067).
                                val panes = paneLayout(maxWidth)
                                Row(Modifier.fillMaxSize().then(covered)) {
                                    Spacer(Modifier.width(if (twoPane) panes.start else 0.dp))
                                    Box((if (twoPane) Modifier.width(panes.list) else Modifier.weight(1f)).fillMaxHeight()) {
                                        // A short fade through between destinations; each keeps its saved state.
                                        AnimatedContent(
                                            targetState = session.destination,
                                            transitionSpec = { destinationFade() },
                                            modifier = Modifier.fillMaxSize(),
                                            label = "destination",
                                        ) { destination ->
                                            stateHolder.SaveableStateProvider("dest-$destination-${session.dataGeneration}") {
                                                when (destination) {
                                                    Destination.SEARCH -> SearchScreen(session, twoPane)
                                                    Destination.OCR -> OcrScreen(session)
                                                    Destination.VOICE -> VoiceScreen(session)
                                                    Destination.READING -> ReadingScreen(session)
                                                    Destination.HSK -> HskScreen(session)
                                                    Destination.SETTINGS -> SettingsScreen(session, onBack = null)
                                                }
                                            }
                                        }
                                    }
                                    if (twoPane) {
                                        Box(Modifier.width(PaneDivider).fillMaxHeight().background(palette.border))
                                        // ENT-019: the side pane with nothing selected.
                                        Box(Modifier.width(panes.detail).fillMaxHeight(), contentAlignment = Alignment.Center) {
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
                                // moves it between the two (GLB-050). It stays over the side pane while
                                // the last entry fades out of it.
                                val lastTop = remember { arrayOfNulls<DetailRoute>(1) }
                                if (topRoute != null) lastTop[0] = topRoute
                                val hostRoute = topRoute ?: lastTop[0]
                                val sidePane = twoPane && (hostRoute == null || hostRoute is DetailRoute.Entry)
                                DetailHost(
                                    session, routeStates, twoPane,
                                    if (sidePane) Modifier.align(Alignment.TopStart).offset(x = panes.detailStart).width(panes.detail).fillMaxHeight()
                                    else Modifier.fillMaxSize(),
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
    val routes = session.routes.toList()
    val shown: DetailRoute? = routes.lastOrNull()
    val previous = remember { arrayOf(routes) }
    val motion = remember { arrayOf(RouteMotion.FORWARD) }
    if (routes.map { it.key } != previous[0].map { it.key }) {
        motion[0] = routeMotion(previous[0], routes, twoPane)
        previous[0] = routes
    }
    AnimatedContent(
        targetState = shown,
        contentKey = { it?.key ?: 0L },
        transitionSpec = {
            when (motion[0]) {
                // ENT-016: forward slides in from the trailing edge; back mirrors.
                RouteMotion.FORWARD ->
                    (slideInHorizontally(tween(220)) { it } + fadeIn(tween(220), initialAlpha = 0.72f)) togetherWith
                        (slideOutHorizontally(tween(220)) { -it / 8 } + fadeOut(tween(220), targetAlpha = 0.88f))
                RouteMotion.BACK ->
                    (slideInHorizontally(tween(220)) { -it / 8 } + fadeIn(tween(220), initialAlpha = 0.88f)) togetherWith
                        (slideOutHorizontally(tween(220)) { it } + fadeOut(tween(220), targetAlpha = 0.72f))
                // A selection in the list, or closing it: the side pane changes quietly in place.
                RouteMotion.FADE -> fadeIn(tween(200)) togetherWith fadeOut(tween(200))
            }
        },
        modifier = modifier,
    ) { route ->
        when (route) {
            // Same size as a route, so the host never animates its size; touches pass through it.
            null -> Box(Modifier.fillMaxSize())
            else -> stateHolder.SaveableStateProvider("route-${route.key}") {
                // The route is opaque to touches too: nothing reaches the screen it covers. Over the
                // whole of a wide window it keeps the readable width, centred (GLB-067).
                Box(Modifier.fillMaxSize().background(palette.bg).pointerInput(Unit) { }, contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = ReadablePaneWidth).fillMaxSize()) {
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
}

/** GLB-071: a gradient scrim keeps content legible beneath the transparent status bar. */
@Composable
private fun StatusBarScrim(modifier: Modifier = Modifier) {
    val palette = LocalDictionaryPalette.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(modifier.fillMaxWidth().height(top + 12.dp).background(Brush.verticalGradient(listOf(palette.bg, palette.bg.copy(alpha = 0f)))))
}

/** The destinations of the bar and rails, in order: Voice only where there is a recogniser, Settings only where it is one. */
fun navigationDestinations(voiceAvailable: Boolean, settingsIsDestination: Boolean): List<Destination> =
    Destination.entries.filter {
        (it != Destination.VOICE || voiceAvailable) && (it != Destination.SETTINGS || settingsIsDestination)
    }

/** Fade through between destinations: the old one out, then the new one in, no movement (~150 ms). */
private fun destinationFade(): androidx.compose.animation.ContentTransform =
    fadeIn(tween(90, delayMillis = 60)) togetherWith fadeOut(tween(60))

/** Outlined when unselected, filled when selected. */
private fun icon(destination: Destination, selected: Boolean): ImageVector = when (destination) {
    Destination.SEARCH -> if (selected) Icons.Filled.Search else Icons.Outlined.Search
    Destination.OCR -> if (selected) Icons.Filled.CameraAlt else Icons.Outlined.CameraAlt
    Destination.VOICE -> if (selected) Icons.Filled.Mic else Icons.Outlined.MicNone
    Destination.READING -> if (selected) Icons.AutoMirrored.Filled.MenuBook else Icons.AutoMirrored.Outlined.MenuBook
    Destination.HSK -> if (selected) Icons.Filled.School else Icons.Outlined.School
    Destination.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
}

private fun label(destination: Destination): String = when (destination) {
    Destination.SEARCH -> "Search"
    Destination.OCR -> "OCR"
    Destination.VOICE -> "Voice"
    Destination.READING -> "Reading"
    Destination.HSK -> "HSK"
    Destination.SETTINGS -> "Settings"
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
                    icon = { Icon(icon(destination, destination == selected), null) },
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
    NavigationRail(containerColor = palette.panel, modifier = Modifier.drawBehind { trailingHairline(palette.border) }) {
        destinations.forEach { destination ->
            NavigationRailItem(
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = { Icon(icon(destination, destination == selected), null) },
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

/**
 * The rail of a wide window: the app's name, then every destination as its icon with the label
 * beside it. Same panel, accent selection and hairline as the icon rail.
 */
@Composable
private fun AppExpandedRail(destinations: List<Destination>, selected: Destination, onSelect: (Destination) -> Unit) {
    val palette = LocalDictionaryPalette.current
    val strings = appStrings()
    Column(
        Modifier
            .fillMaxHeight()
            .width(ExpandedRailWidth)
            .background(palette.panel)
            .drawBehind { trailingHairline(palette.border) }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
            .padding(horizontal = 12.dp)
            .selectableGroup(),
    ) {
        Text(
            "SZ Dict",
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = palette.fg,
            maxLines = 1,
            modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 18.dp).semantics { heading() },
        )
        destinations.forEach { destination ->
            NavigationDrawerItem(
                label = { Text(strings.text(label(destination)), maxLines = 1) },
                icon = { Icon(icon(destination, destination == selected), null) },
                selected = destination == selected,
                onClick = { onSelect(destination) },
                modifier = Modifier.padding(vertical = 2.dp).height(48.dp),
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = palette.accentSoft,
                    unselectedContainerColor = Color.Transparent,
                    selectedIconColor = palette.accent, selectedTextColor = palette.accent,
                    unselectedIconColor = palette.muted, unselectedTextColor = palette.muted,
                ),
            )
        }
    }
}

private val ExpandedRailWidth = 220.dp

/** A 1 px line down the trailing edge: the rail is told apart from the page by more than its tone. */
private fun DrawScope.trailingHairline(color: Color) {
    val width = 1.dp.toPx()
    drawRect(color, topLeft = Offset(size.width - width, 0f), size = Size(width, size.height))
}
