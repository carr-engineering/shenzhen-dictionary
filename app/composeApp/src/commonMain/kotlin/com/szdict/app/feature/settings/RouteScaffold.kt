package com.szdict.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.theme.LocalDictionaryPalette

/** Readable content width of the Settings-family routes (GLB-067). */
internal val ReadableWidth: Dp = 720.dp

/**
 * Frame of a pushed full-screen route (SET-005, SET-090): a top bar with a back/up control and
 * the title, drawn below the status bar, over a single centred pane of readable width.
 */
@Composable
internal fun RouteScaffold(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val palette = LocalDictionaryPalette.current
    val strings = appStrings()
    Column(modifier.fillMaxSize().background(palette.bg)) {
        Box(Modifier.fillMaxWidth().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Row(
                Modifier.widthIn(max = ReadableWidth).fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.text("Back"), tint = palette.fg)
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = if (onBack != null) 4.dp else 14.dp).semantics { heading() },
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = ReadableWidth).fillMaxSize()) { content() }
        }
    }
}
