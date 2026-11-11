package com.szdict.app.integration

import com.szdict.app.AppSession
import com.szdict.app.Destination
import com.szdict.app.feature.hsk.HskStates
import com.szdict.core.api.DictMode
import com.szdict.core.api.Route
import com.szdict.core.api.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-level inbox for content and navigation that arrive from outside the app: launcher
 * shortcuts (SET-050), share targets (SET-054…056), App Links (GLB-111) and the widget (SET-064).
 * The platform layer delivers here; each feature observes its own flow and **consumes** the value
 * once (SET-052, SET-055), so recreation or a later Back never re-applies it. Shared text and
 * image URIs are never logged (GLB-092).
 */
object IncomingIntents {
    private val _pendingText = MutableStateFlow<String?>(null)
    private val _pendingImageUri = MutableStateFlow<String?>(null)
    private val _pendingRoute = MutableStateFlow<Route?>(null)

    /** Text for Reading (shared text, `Look up in…`, clipboard shortcut): replaces the draft (TXT-080). */
    val pendingText: StateFlow<String?> = _pendingText.asStateFlow()

    /** A shared image (content URI string) for the OCR destination's gallery-review mode (SET-054). */
    val pendingImageUri: StateFlow<String?> = _pendingImageUri.asStateFlow()

    /**
     * A route for a feature to finish applying after the destination was selected: [Route.Search]
     * and a mode-scoped [Route.Home] for Search; [Route.Ocr] (reset to the live camera input,
     * CAM-006) for OCR; [Route.Voice] for Voice. Word, HSK, Settings and Privacy routes are applied
     * by [IncomingRouter] itself and never appear here.
     */
    val pendingRoute: StateFlow<Route?> = _pendingRoute.asStateFlow()

    fun deliverText(text: String) { if (text.isNotBlank()) _pendingText.value = text }
    fun deliverImage(uri: String) { _pendingImageUri.value = uri }
    fun deliverRoute(route: Route) { _pendingRoute.value = route }

    /** Returns the pending text and clears it; null when there is none. */
    fun consumeText(): String? = _pendingText.value.also { _pendingText.value = null }
    fun consumeImageUri(): String? = _pendingImageUri.value.also { _pendingImageUri.value = null }
    fun consumeRoute(): Route? = _pendingRoute.value.also { _pendingRoute.value = null }

    /** Like [consumeRoute] but only when the pending route satisfies [accept] (so features do not steal each other's). */
    fun consumeRouteIf(accept: (Route) -> Boolean): Route? {
        val route = _pendingRoute.value ?: return null
        if (!accept(route)) return null
        _pendingRoute.value = null
        return route
    }
}

/** What an incoming platform intent asks for, independent of Android types (testable on the host). */
sealed interface IncomingAction {
    data object None : IncomingAction

    /** Shared or selected text → Reading (SET-056). Blank text still opens Reading, draft unchanged. */
    data class ReadText(val text: String) : IncomingAction

    /** `Read clipboard` shortcut: the clipboard is read only once the window has focus (SET-051). */
    data object ReadClipboard : IncomingAction

    /** Shared image → OCR destination (SET-054). */
    data class ScanImage(val uri: String) : IncomingAction

    /** A szdict.com link, a shortcut route or the widget's entry link (GLB-110…112). */
    data class Open(val route: Route, val fromWidget: Boolean = false) : IncomingAction
}

/** The fields of a platform intent that routing depends on. */
data class IncomingIntentData(
    val action: String?,
    val type: String? = null,
    val data: String? = null,
    val text: String? = null,
    val processText: String? = null,
    val streamUri: String? = null,
    val fromWidget: Boolean = false,
)

object IncomingRouter {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"
    const val ACTION_READ_CLIPBOARD = "com.szdict.app.action.READ_CLIPBOARD"

    /** Maps an intent to its action. Anything unrecognised is [IncomingAction.None]. */
    fun classify(intent: IncomingIntentData, voiceAvailable: Boolean = true): IncomingAction = when (intent.action) {
        ACTION_READ_CLIPBOARD -> IncomingAction.ReadClipboard
        ACTION_PROCESS_TEXT -> IncomingAction.ReadText(intent.processText.orEmpty())
        ACTION_SEND -> when {
            intent.type?.startsWith("image/") == true ->
                intent.streamUri?.takeIf { it.isNotBlank() }?.let { IncomingAction.ScanImage(it) } ?: IncomingAction.None
            intent.type?.startsWith("text/") == true -> IncomingAction.ReadText(intent.text.orEmpty())
            else -> IncomingAction.None
        }
        ACTION_VIEW -> {
            val url = intent.data
            if (url == null || !isAppLink(url)) IncomingAction.None
            else IncomingAction.Open(Routes.parse(url, voiceAvailable), intent.fromWidget)
        }
        else -> IncomingAction.None
    }

    /** Only `https://szdict.com/...` (GLB-111); any other scheme or host is ignored. */
    fun isAppLink(url: String): Boolean {
        val rest = url.trim().let {
            when {
                it.startsWith("https://", ignoreCase = true) -> it.substring(8)
                else -> return false
            }
        }
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        return host.equals(Routes.HOST, ignoreCase = true)
    }

    /**
     * Applies [action] to the session. Returns true when the clipboard must be read once the
     * window has focus (the caller then calls [deliverClipboard]).
     */
    fun dispatch(session: AppSession, action: IncomingAction): Boolean {
        when (action) {
            IncomingAction.None -> return false
            is IncomingAction.ReadText -> {
                show(session, Destination.READING)
                IncomingIntents.deliverText(action.text)   // blank text is ignored (SET-056)
            }
            IncomingAction.ReadClipboard -> {
                show(session, Destination.READING)
                return true
            }
            is IncomingAction.ScanImage -> {
                show(session, Destination.OCR)
                IncomingIntents.deliverImage(action.uri)
            }
            is IncomingAction.Open -> open(session, action.route, action.fromWidget)
        }
        return false
    }

    /** Clipboard text for the `Read clipboard` shortcut, read by the platform while focused. */
    fun deliverClipboard(text: String?) {
        if (!text.isNullOrBlank()) IncomingIntents.deliverText(text)
    }

    private fun open(session: AppSession, route: Route, fromWidget: Boolean) {
        when (route) {
            is Route.Home -> {
                show(session, Destination.SEARCH)
                if (route.mode != null && route.mode != session.mode) IncomingIntents.deliverRoute(route)
            }
            is Route.Search -> {
                show(session, Destination.SEARCH)
                IncomingIntents.deliverRoute(route)
            }
            is Route.Word -> openWord(session, route.mode, route.naturalKey, fromWidget)
            is Route.Hsk -> {
                show(session, Destination.HSK)
                HskStates.of(session).openRoute(route.mode, route.level)
            }
            Route.Reading -> show(session, Destination.READING)
            Route.Ocr -> {
                show(session, Destination.OCR)
                IncomingIntents.deliverRoute(route)
            }
            Route.Voice -> {
                if (session.env.voiceAvailable) {
                    show(session, Destination.VOICE)
                    IncomingIntents.deliverRoute(route)
                } else show(session, Destination.SEARCH)
            }
            Route.Settings -> {
                show(session, Destination.SEARCH)
                session.openSettings()
            }
            Route.Privacy -> {
                // SET-090: the policy is a route pushed over Settings.
                show(session, Destination.SEARCH)
                session.openSettings()
                session.openPrivacy()
            }
        }
    }

    /**
     * SET-064: switch to Search, clear the stack, push that entry in the link's mode. The mode is
     * route-scoped — the persisted dictionary mode is never changed (GLB-110). The dataset is
     * opened first if needed. An unknown key from a link falls back to Search (GLB-112); from the
     * widget it opens the entry error state (SET-083).
     */
    private fun openWord(session: AppSession, mode: DictMode, naturalKey: String, fromWidget: Boolean) {
        show(session, Destination.SEARCH)
        session.scope.launch {
            val id = runCatching { session.dictionary(mode).idForKey(naturalKey) }.getOrNull()
            if (session.destination != Destination.SEARCH) return@launch
            when {
                id != null -> session.openEntry(id, mode)
                fromWidget -> session.openEntry(UNKNOWN_ENTRY_ID, mode)
            }
        }
    }

    /** Selects [destination] with an empty detail stack, without triggering scroll-to-top on a reselect. */
    private fun show(session: AppSession, destination: Destination) {
        if (session.destination == destination) {
            session.clearRoutes()
            session.closeWordSheet(userDismissed = false)
            session.handwritingVisible = false
        } else {
            session.selectDestination(destination)
        }
    }

    /** An id no dataset uses: opening it shows the entry error state (SET-083). */
    const val UNKNOWN_ENTRY_ID: Int = -1
}
