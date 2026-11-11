package com.szdict.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.szdict.app.AppSession
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.theme.LocalDictionaryPalette

/**
 * The privacy policy route (`/privacy`, SET-090, ARCH-199): bundled text in the active UI
 * language, a single readable-width page, pushed over Settings. It makes no network request.
 */
@Composable
fun PrivacyScreen(session: AppSession, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val policy = remember(strings.lang) { privacyPolicy(strings.lang) }
    val bottomInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    RouteScaffold(policy.title, onBack, modifier) {
        SelectionContainer {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp + bottomInset),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item("head") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(policy.lastUpdated, style = MaterialTheme.typography.labelLarge, color = palette.muted)
                        policy.summary.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = palette.fg) }
                    }
                }
                items(policy.sections, key = { it.heading }) { section ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            section.heading,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.fg,
                            modifier = Modifier.padding(top = 2.dp).semantics { heading() },
                        )
                        section.paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.fg) }
                    }
                }
            }
        }
    }
}
