package com.szdict.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.szdict.app.AppName
import com.szdict.app.AppSession
import com.szdict.app.ui.theme.AppFonts
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.preloadFont

/**
 * Whether the Latin font (Source Sans 3, ui/theme/AppTypography.kt) is loaded at every weight the
 * theme names. A Compose resource arrives asynchronously on Web, and until then the theme's font
 * family draws nothing; the start page fetches the files beside the code (`<link rel="preload">` in
 * index.html), so the first frame waits for it instead of showing text in another face, or none.
 * After [LATIN_FONT_TIMEOUT_MS] the app starts regardless — the files are part of the offline shell,
 * so that only happens on a broken connection.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun latinFontReady(): Boolean {
    val fonts = AppFonts.map { (weight, resource) -> preloadFont(resource, weight) }
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LATIN_FONT_TIMEOUT_MS)
        timedOut = true
    }
    return timedOut || fonts.all { it.value != null }
}

private const val LATIN_FONT_TIMEOUT_MS = 5000L

/**
 * Keeps the tab title in the interface language: the full name (AppName.full) — "Shenzhen
 * Dictionary", or 深圳词典 / 深圳詞典 — from the stored setting on, through every change of it.
 * index.html sets the same title before the app starts.
 */
internal fun keepTitleInStep(session: AppSession, scope: CoroutineScope) {
    scope.launch {
        snapshotFlow { session.settingsLoaded }.first { it }
        session.settings.collect { settings ->
            document.title = AppName.full(settings.uiLanguage.resolve(browserLanguageTag()))
        }
    }
}
