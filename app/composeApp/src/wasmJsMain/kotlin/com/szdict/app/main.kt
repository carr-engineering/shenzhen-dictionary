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
import com.szdict.app.platform.requestPersistentStorage
import com.szdict.app.ui.theme.DictionaryTheme
import com.szdict.app.ui.theme.LocalDictionaryPalette
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    val audio = WebAudioPlayer()
    val datasets = WebDatasets(audio, scope)
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
        systemLocaleTag = ::browserLanguageTag,
        appVersion = WebBuild.VERSION_NAME,
    )
    val session = AppSession(env, scope)
    // Read before the history layer rewrites the URL.
    window.location.search.let { query -> if (query.contains("selftest=recognition")) scope.launch { com.szdict.app.feature.ocr.RecognitionSelfTest.run(query) } }
    requestPersistentStorage()
    WebHistory(session).start()
    // Leaving or hiding the page is the Web's "stop": settle the settings and silence playback (ARCH-112).
    window.addEventListener("pagehide", { session.flushNow(); session.stopPlayback() })
    document.addEventListener("visibilitychange", { if (jsPageHidden()) session.flushNow() })

    ComposeViewport(document.body!!) {
        LaunchedEffect(Unit) { document.getElementById("loading")?.remove() }
        WithCjkFont(session) {
            Box(Modifier.fillMaxSize()) {
                App(session)
                DatasetDownloadState(session, datasets)
            }
        }
                DatasetUpdatedNotice(session, datasets)
    }
    if (window.location.search.contains("selftest=1")) scope.launch { SelfTest.run(session) }
}
    installDebugState(session, datasets)

/** First-visit download of a mode's dataset (ARCH-004): a progress card over the shell, or the failure with a reload. */
@Composable
private fun DatasetDownloadState(session: AppSession, datasets: WebDatasets) {
    val progress = datasets.datasetProgress
    val failed = datasets.failed
    if (progress == null && !failed) return
    val settings by session.settings.collectAsState()
    val strings = AppStrings(settings.uiLanguage.resolve(session.env.systemLocaleTag()))
    DictionaryTheme(settings.themeMode, session.routeMode()) {
        val palette = LocalDictionaryPalette.current
        // The shell underneath cannot answer anything yet: the scrim takes its pointer input.
        Box(
            Modifier.fillMaxSize().background(palette.bg.copy(alpha = 0.86f)).pointerInput(Unit) { }.padding(24.dp),
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
                } else {
                    Text(strings.text("The dictionary could not be downloaded"), color = palette.fg, style = MaterialTheme.typography.titleMedium)
                    Text(strings.text("Check the connection and try again."), color = palette.muted, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { jsReload() }) { Text(strings.text("Try again"), color = palette.accent) }
                }
            }
        }
    }
}

private fun megabytes(bytes: Long): String {
    val tenths = (bytes * 10 + 524_288) / 1_048_576
    return "${tenths / 10}.${tenths % 10}"
}
