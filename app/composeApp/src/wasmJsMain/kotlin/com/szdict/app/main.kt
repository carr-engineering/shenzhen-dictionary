@file:OptIn(ExperimentalWasmJsInterop::class)

package com.szdict.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import com.szdict.app.feature.hsk.HskStates
import com.szdict.app.feature.search.SearchStateHolder
import com.szdict.app.i18n.AppStrings
import com.szdict.app.platform.LocalStorageSettings
import com.szdict.app.platform.WebAudioPlayer
import com.szdict.app.platform.WebClipboard
import com.szdict.app.platform.WebDatasets
import com.szdict.app.platform.WebHistory
import com.szdict.app.platform.WebLinkOpener
import com.szdict.app.platform.WebSpeaker
import com.szdict.app.platform.WebTranslator
import com.szdict.app.platform.WithCjkFont
import com.szdict.app.platform.browserLanguageTag
import com.szdict.app.platform.keepTitleInStep
import com.szdict.app.platform.latinFontReady
import com.szdict.app.platform.requestPersistentStorage
import com.szdict.app.ui.theme.DictionaryTheme
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.DictMode
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import kotlin.js.ExperimentalWasmJsInterop

private fun jsReload(): Unit = js("{ window.location.reload(); }")

private fun jsPageHidden(): Boolean = js("document.hidden")

/**
 * Web entry point (PROD-020): four destinations — no Voice (PROD-022) — over the store worker.
 * The first visit downloads the active mode's dataset behind a visible progress state (ARCH-004).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Read before the history rewrites the address to the canonical route (GLB-110).
    val selfTest = window.location.search.contains("selftest=1")
    val modeSegment = window.location.pathname.trimStart('/').substringBefore('/')
    val initialMode = DictMode.entries.firstOrNull { it.routeSegment == modeSegment }
    val audio = WebAudioPlayer()
    val datasets = WebDatasets(audio, scope, initialMode)
    val env = AppEnv(
        openDictionary = datasets::open,
        settingsStorage = LocalStorageSettings(),
        audio = audio,
        speaker = WebSpeaker(),
        clipboard = WebClipboard(scope),
        translator = WebTranslator(),
        links = WebLinkOpener(),
        services = webServices(scope),
        voiceAvailable = false,
        settingsIsDestination = true,
        systemLocaleTag = ::browserLanguageTag,
        appVersion = WebBuild.VERSION_NAME,
        appPlatform = AppPlatform.WEB,
    )
    val session = AppSession(env, scope)
    // Read before the history layer rewrites the URL.
    window.location.search.let { query -> if (query.contains("selftest=recognition")) scope.launch { com.szdict.app.feature.ocr.RecognitionSelfTest.run(query) } }
    requestPersistentStorage()
    if (initialMode != null) scope.launch { runCatching { session.dictionary(initialMode) } }
    // Search starts with the page, not with its first frame: a tab that is loading in the
    // background gets few frames or none, and the linked or restored query, its search and the
    // empty state's content must not wait for one.
    SearchStateHolder.of(session).start()
    WebHistory(session).start()
    keepTitleInStep(session, scope)
    // Leaving or hiding the page is the Web's "stop": settle the settings and silence playback (ARCH-112).
    window.addEventListener("pagehide", { session.flushNow(); session.stopPlayback() })
    document.addEventListener("visibilitychange", { if (jsPageHidden()) session.flushNow() })

    val appHost = document.getElementById("app") as? HTMLElement ?: document.body!!
    ComposeViewport(appHost) {
        // The start page stays up until the Latin font is here: no frame shows text in another face.
        if (!latinFontReady()) return@ComposeViewport
        WithCjkFont(session, initialMode) { fontReady ->
            if (fontReady) {
                LaunchedEffect(Unit) { document.getElementById("loading")?.remove() }
                Box(Modifier.fillMaxSize()) {
                    App(session)
                    DatasetDownloadState(session, datasets, initialMode)
                    DatasetUpdatedNotice(session, datasets)
                }
            }
        }
    }
    installDebugState(session, datasets)
    if (selfTest) scope.launch { SelfTest.run(session) }
}

/** First-visit download of a mode's dataset (ARCH-004): a progress card over the shell, or the failure with a reload. */
@Composable
private fun DatasetDownloadState(session: AppSession, datasets: WebDatasets, initialMode: DictMode?) {
    val linkedMode = when (session.destination) {
        Destination.SEARCH -> SearchStateHolder.of(session).linkMode
        Destination.HSK -> HskStates.of(session).linkMode
        else -> null
    }
    val activeMode = if (datasets.initialOpenPending && initialMode != null) initialMode else
        (session.routes.lastOrNull() as? DetailRoute.Entry)?.mode ?: linkedMode ?: session.mode
    val progress = datasets.progress(activeMode)
    val failed = datasets.failed(activeMode)
    if (progress == null && !failed && !datasets.initialOpenPending) return
    val settings by session.settings.collectAsState()
    val strings = AppStrings(settings.uiLanguage.resolve(session.env.systemLocaleTag()))
    DictionaryTheme(settings.themeMode, session.routeMode()) {
        val palette = LocalDictionaryPalette.current
        // The shell underneath cannot answer anything yet: the scrim takes its pointer input.
        Box(
            Modifier.fillMaxSize().background(palette.bg).pointerInput(Unit) { }.padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 380.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.panel)
                    .border(1.dp, palette.border, RoundedCornerShape(14.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (progress != null) {
                    Text(strings.text("Downloading the dictionary…"), color = palette.fg, style = MaterialTheme.typography.titleMedium)
                    val fraction = if (progress.total > 0) (progress.loaded.toFloat() / progress.total).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = palette.accent,
                        trackColor = palette.border,
                    )
                    Text(
                        strings.text("{0} of {1} MB · kept on this device for offline use", megabytes(progress.loaded), megabytes(progress.total)),
                        color = palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (failed) {
                    Text(strings.text("The dictionary could not be downloaded"), color = palette.fg, style = MaterialTheme.typography.titleMedium)
                    Text(strings.text("Check the connection and try again."), color = palette.muted, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { jsReload() }) { Text(strings.text("Try again"), color = palette.accent) }
                } else {
                    Text(strings.text("Loading…"), color = palette.fg, style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = palette.accent, trackColor = palette.border)
                }
            }
        }
    }
}

private fun megabytes(bytes: Long): String {
    val tenths = (bytes * 10 + 524_288) / 1_048_576
    return "${tenths / 10}.${tenths % 10}"
}
