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

/** Narrowest window with the two-pane layout: a modern phone in landscape (long edge 760 dp and up) has it, a small one does not. */
val TwoPaneMinWidth: Dp = 760.dp

/** Share of the pane area the list takes, until its minimum or maximum. */
private const val ListPaneShare = 0.43f

/** The list pane never gets narrower than this: cards still read at that width. */
val ListPaneMinWidth: Dp = 300.dp

/** Widest a list pane grows: beyond it cards only get longer lines. */
val ListPaneMaxWidth: Dp = 600.dp

/** Readable width of a detail pane and of every single-pane page (GLB-067). */
val ReadablePaneWidth: Dp = 900.dp

/** Widest the OCR review grows: an image wants more room than text, but not a whole wide monitor. */
val ImagePaneMaxWidth: Dp = 1100.dp

/** Where the two panes sit in the content area: [start] of empty space, the list, a hairline, the detail. */
data class PaneLayout(val start: Dp, val list: Dp, val detail: Dp) {
    val detailStart: Dp get() = start + list + PaneDivider
}

val PaneDivider: Dp = 1.dp

/**
 * The list takes about 43 % of the width (at least [ListPaneMinWidth], at most [ListPaneMaxWidth]),
 * then the detail takes the rest up to the readable width; on wider windows the pair stays that size, centred (GLB-067).
 */
fun paneLayout(contentWidth: Dp): PaneLayout {
    val available = (contentWidth - PaneDivider).coerceAtLeast(0.dp)
    val list = minOf(maxOf(available * ListPaneShare, ListPaneMinWidth), ListPaneMaxWidth, available)
    val detail = minOf(available - list, ReadablePaneWidth)
    return PaneLayout(start = (available - list - detail) / 2, list = list, detail = detail)
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
