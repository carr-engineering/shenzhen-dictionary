@file:OptIn(ExperimentalWasmJsInterop::class)

package com.szdict.app.platform

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import com.szdict.app.AppSession
import com.szdict.app.Destination
import com.szdict.app.DetailRoute
import com.szdict.app.feature.hsk.HskStates
import com.szdict.app.feature.search.SearchStateHolder
import com.szdict.app.integration.IncomingAction
import com.szdict.app.integration.IncomingRouter
import com.szdict.core.api.Route
import com.szdict.core.api.Routes
import com.szdict.core.api.Script
import kotlinx.browser.window
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.js.ExperimentalWasmJsInterop

private fun jsPush(index: Int, url: String): Unit = js("{ history.pushState({ idx: index }, '', url); }")
private fun jsReplace(index: Int, url: String): Unit = js("{ history.replaceState({ idx: index }, '', url); }")
private fun jsStateIndex(): Int = js("(history.state && typeof history.state.idx === 'number') ? history.state.idx : -1")
private fun jsLocation(): String = js("location.pathname + location.search")
private fun jsBack(): Unit = js("{ history.back(); }")

/**
 * Routes on Web (GLB-110…112): the address bar and the browser history mirror the destination, the
 * query and the detail stack. The URL the page was opened with is applied through the shared
 * route table; afterwards switching destination and pushing a route add history entries, anything
 * else (query edits, a mode change) replaces the current one, and browser back / forward restore
 * the screen of the entry they land on — popping the detail stack in place when that is all that
 * differs, so the screens underneath keep their state.
 */
class WebHistory(private val session: AppSession) {
    /** What a history entry showed: enough to tell a pop from a different screen. */
    private data class Shape(val destination: Destination, val routeKeys: List<Long>)
    private data class State(val shape: Shape, val url: String?)

    private val shapes = HashMap<Int, Shape>()
    private var index = 0
    /** The next state change comes from the browser (back / forward), not from the app. */
    private var fromBrowser = false
    private val naturalKeys = mutableStateMapOf<String, String>()
    private val resolving = HashSet<String>()

    fun start() {
        index = jsStateIndex().coerceAtLeast(0)
        val opened = Routes.parse(jsLocation(), voiceAvailable = false)
        session.scope.launch {
            snapshotFlow { session.settingsLoaded }.first { it }
            if (opened != Route.Home(null)) IncomingRouter.dispatch(session, IncomingAction.Open(opened))
            fromBrowser = true   // the first state belongs to the entry the page was opened on
            snapshotFlow { state() }.collect(::onState)
        }
        window.addEventListener("popstate", { onPop() })
    }

    private fun state(): State = State(Shape(session.destination, session.routes.map { it.key }), url())

    /** The canonical URL of what is on screen, or null while an entry's natural key is being looked up. */
    private fun url(): String? = when (val top = session.routes.lastOrNull()) {
        is DetailRoute.Entry -> {
            val id = "${top.mode.routeSegment}:${top.entryId}"
            val key = naturalKeys[id]
            if (key == null) resolve(id, top)
            key?.let { Routes.build(Route.Word(top.mode, it)) }
        }
        is DetailRoute.Privacy -> Routes.build(Route.Privacy)
        is DetailRoute.SettingsRoute, is DetailRoute.Attribution -> Routes.build(Route.Settings)
        null -> when (session.destination) {
            Destination.SEARCH -> {
                val holder = SearchStateHolder.of(session)
                val mode = holder.linkMode ?: session.mode
                val query = holder.query
                // GLB-112: passages never go into the URL.
                val passage = query.length > MAX_QUERY || '\n' in query || holder.displayed?.result?.chinese?.isSentence == true
                if (query.isBlank() || passage) Routes.build(Route.Home(mode)) else Routes.build(Route.Search(mode, query.trim()))
            }
            Destination.HSK -> Routes.build(Route.Hsk(HskStates.of(session).linkMode ?: session.mode))
            Destination.READING -> Routes.build(Route.Reading)
            Destination.OCR -> Routes.build(Route.Ocr)
            Destination.VOICE -> Routes.build(Route.Home(null))
        }
    }

    private fun resolve(id: String, route: DetailRoute.Entry) {
        if (!resolving.add(id)) return
        session.scope.launch {
            val key = runCatching {
                session.dictionary(route.mode).entryCards(listOf(route.entryId), Script.SIMPLIFIED).firstOrNull()?.naturalKey
            }.getOrNull()
            resolving.remove(id)
            if (key != null) naturalKeys[id] = key
        }
    }

    private fun onState(state: State) {
        val url = state.url ?: return
        val shown = shapes[index]
        when {
            fromBrowser || shown == null || shown == state.shape -> jsReplace(index, url)
            // The app went back to exactly the previous entry (back arrow, close): so does the browser.
            shapes[index - 1] == state.shape -> {
                index--
                jsBack()
                return
            }
            else -> jsPush(++index, url)
        }
        fromBrowser = false
        shapes[index] = state.shape
    }

    private fun onPop() {
        val target = jsStateIndex().coerceAtLeast(0)
        index = target
        val shape = shapes[target]
        val current = Shape(session.destination, session.routes.map { it.key })
        if (shape == current) return
        fromBrowser = true
        if (shape != null && shape.destination == current.destination && current.routeKeys.take(shape.routeKeys.size) == shape.routeKeys) {
            // Back within the detail stack: pop to that depth, keeping what is underneath (PROD-056).
            while (session.routes.size > shape.routeKeys.size) session.popRoute(session.routes.last().key)
        } else {
            IncomingRouter.dispatch(session, IncomingAction.Open(Routes.parse(jsLocation(), voiceAvailable = false)))
        }
        // Nothing changed after all (the URL names what is already shown): the next change is the app's.
        if (Shape(session.destination, session.routes.map { it.key }) == current) fromBrowser = false
    }

    private companion object {
        const val MAX_QUERY = 60
    }
}
