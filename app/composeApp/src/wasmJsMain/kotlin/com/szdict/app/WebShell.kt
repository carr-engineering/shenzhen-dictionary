@file:OptIn(ExperimentalWasmJsInterop::class)

package com.szdict.app

import com.szdict.app.ui.kit.WithTooltip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.szdict.app.feature.hsk.HskStates
import com.szdict.app.feature.search.SearchStateHolder
import com.szdict.app.i18n.AppStrings
import com.szdict.app.platform.WebDatasets
import com.szdict.app.ui.theme.DictionaryTheme
import com.szdict.app.ui.theme.LocalDictionaryPalette
import kotlin.js.ExperimentalWasmJsInterop

private fun jsReloadPage(): Unit = js("{ window.location.reload(); }")

/**
 * "Dictionary updated" (ARCH-145): a newer dataset was downloaded and verified in the background.
 * The one in use stays in use — nothing on screen changes under the user — and the next load of
 * the page opens the new one; this small note offers that reload and can be dismissed.
 */
@Composable
internal fun DatasetUpdatedNotice(session: AppSession, datasets: WebDatasets) {
    if (!datasets.updateInstalled) return
    val settings by session.settings.collectAsState()
    val strings = AppStrings(settings.uiLanguage.resolve(session.env.systemLocaleTag()))
    DictionaryTheme(settings.themeMode, session.routeMode()) {
        val palette = LocalDictionaryPalette.current
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
            Row(
                Modifier.clip(RoundedCornerShape(22.dp)).background(palette.panel).border(1.dp, palette.border, RoundedCornerShape(22.dp))
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(strings.text("Dictionary updated"), color = palette.fg, style = MaterialTheme.typography.bodyMedium)
                Text(
                    strings.text("Reload"), color = palette.accent, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { session.flushNow(); jsReloadPage() }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
                WithTooltip(strings.text("Close")) {
                    Icon(
                        Icons.Filled.Close, contentDescription = strings.text("Close"), tint = palette.muted,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { datasets.dismissUpdate() }
                            .padding(10.dp).size(18.dp),
                    )
                }
            }
        }
    }
}

private fun jsHistoryLength(): Int = js("history.length")

private fun jsDebugEnabled(): Boolean = js("(function () { try { return localStorage.getItem('szdict.debug') === '1'; } catch (e) { return false; } })()")

private fun jsInstallState(read: () -> String): Unit = js("{ window.szdictState = function () { return JSON.parse(read()); }; }")

private fun quote(text: String?): String =
    if (text == null) "null" else "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

/**
 * `window.szdictState()` for browser automation: the app draws on a canvas, so a driver cannot read
 * the screen from the DOM. Installed only when the page's own `localStorage` asks for it
 * (`szdict.debug = 1`); it exposes nothing the page does not already hold.
 */
internal fun installDebugState(session: AppSession, datasets: WebDatasets) {
    if (!jsDebugEnabled()) return
    jsInstallState {
        val search = SearchStateHolder.of(session)
        val hsk = HskStates.of(session)
        val shown = search.displayed
        buildString {
            append("{\"destination\":").append(quote(session.destination.name))
            append(",\"routes\":").append(session.routes.size)
            append(",\"historyLength\":").append(jsHistoryLength())
            append(",\"topRoute\":").append(quote(session.routes.lastOrNull()?.let { it::class.simpleName }))
            append(",\"mode\":").append(quote(session.mode.name))
            append(",\"query\":").append(quote(search.query))
            append(",\"searching\":").append(search.searching)
            append(",\"side\":").append(quote(search.side.name))
            append(",\"chinese\":").append(shown?.result?.chinese?.count ?: -1)
            append(",\"english\":").append(shown?.result?.english?.count ?: -1)
            append(",\"sections\":").append(quote(shown?.result?.chinese?.sections?.joinToString(",") { it.kind.name }))
            append(",\"pending\":").append(shown?.result?.combinationsPending ?: false)
            append(",\"error\":").append(search.error != null)
            append(",\"notice\":").append(quote(search.notice))
            append(",\"hskLevel\":").append(hsk.shownLevel ?: 0)
            append(",\"downloading\":").append(datasets.datasetProgress != null)
            append(",\"downloadFailed\":").append(datasets.failed)
            append(",\"persistent\":").append(datasets.persistent)
            append(",\"updateInstalled\":").append(datasets.updateInstalled)
            append("}")
        }
    }
}
