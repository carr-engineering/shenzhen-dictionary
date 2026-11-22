package com.szdict.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Primary navigation by window width (PROD-050): bottom bar, icon rail, or the rail with labels beside the icons. */
enum class NavigationStyle { BOTTOM_BAR, RAIL, EXPANDED_RAIL }

fun navigationStyle(windowWidth: Dp): NavigationStyle = when {
    windowWidth < 600.dp -> NavigationStyle.BOTTOM_BAR
    windowWidth < 1200.dp -> NavigationStyle.RAIL
    else -> NavigationStyle.EXPANDED_RAIL
}

/** The window is wide enough for a list and its detail side by side (GLB-125). */
fun fitsTwoPanes(windowWidth: Dp): Boolean = windowWidth >= TwoPaneMinWidth

/** Voice always uses a detail pane when it fits. OCR splits on demand in landscape. */
internal fun usesTwoPanes(
    width: Dp,
    height: Dp,
    destination: Destination,
    wordChoicesOpen: Boolean,
    entryOpen: Boolean,
): Boolean = fitsTwoPanes(width) && when (destination) {
    Destination.OCR -> width > height && (wordChoicesOpen || entryOpen)
    Destination.SEARCH, Destination.READING, Destination.VOICE, Destination.HSK -> true
    Destination.SETTINGS -> false
}

/** The shelf reserved when a bottom word sheet first opens, limited by the available area. */
internal fun wordSheetPeekHeight(maxHeight: Dp): Dp = maxHeight.coerceIn(0.dp, 256.dp)

/** Narrowest window with the two-pane layout: a modern phone in landscape (long edge 760 dp and up) has it, a small one does not. */
val TwoPaneMinWidth: Dp = 760.dp

/** Widest either pane grows in the balanced list-detail layout. */
private val TwoPaneMaxWidth = 600.dp

/** Readable width of a single-pane page (GLB-067). */
val ReadablePaneWidth: Dp = 900.dp

/** Widest the OCR review grows: an image wants more room than text, but not a whole wide monitor. */
val ImagePaneMaxWidth: Dp = 1100.dp

/** Where the two panes sit in the content area: [start] of empty space, the list, a hairline, the detail. */
data class PaneLayout(val start: Dp, val list: Dp, val detail: Dp) {
    val detailStart: Dp get() = start + list + PaneDivider
}

val PaneDivider: Dp = 1.dp

/** Split the content evenly and centre the pair once both panes reach their width limit. */
fun paneLayout(contentWidth: Dp): PaneLayout {
    val available = (contentWidth - PaneDivider).coerceAtLeast(0.dp)
    val pane = minOf(available / 2, TwoPaneMaxWidth)
    return PaneLayout(start = available / 2 - pane, list = pane, detail = pane)
}

/** How the detail host moves from one stack to the next (ENT-016). */
enum class RouteMotion { FORWARD, BACK, FADE }

/**
 * Navigation inside the stack slides: forward when the route that was on top is still under the
 * new one, back when the new top was already there. In two-pane, a selection in the list — the
 * first one, another one, or closing the last — only cross-fades the side pane: nothing flies
 * across the list. Everything else keeps the single-pane slide.
 */
fun routeMotion(before: List<DetailRoute>, after: List<DetailRoute>, twoPane: Boolean): RouteMotion {
    val old = before.lastOrNull()
    val new = after.lastOrNull()
    val sidePane = twoPane && (old == null || old is DetailRoute.Entry) && (new == null || new is DetailRoute.Entry)
    return when {
        old != null && new != null && old.key != new.key && after.any { it.key == old.key } -> RouteMotion.FORWARD
        new != null && old?.key != new.key && before.any { it.key == new.key } -> RouteMotion.BACK
        sidePane -> RouteMotion.FADE
        new == null -> RouteMotion.BACK
        else -> RouteMotion.FORWARD
    }
}
