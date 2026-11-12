package com.szdict.app.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedIconToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.szdict.app.AppSession
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.theme.LocalDictionaryPalette

/**
 * The pronunciation toggle of a passage surface (GLB-120): shows or hides the readings drawn above
 * the passages ([PassageText]) everywhere, one persisted setting. Labelled by what a tap does and
 * exposed as a toggle. Cards and entry pages are not affected: they always show their readings.
 */
@Composable
fun PronunciationToggle(session: AppSession, modifier: Modifier = Modifier) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val settings by session.settings.collectAsState()
    val shown = settings.showPronunciation
    val label = strings.text(if (shown) "Hide pronunciation" else "Show pronunciation")
    val state = strings.text(if (shown) "Pronunciation shown" else "Pronunciation hidden")
    OutlinedIconToggleButton(
        checked = shown,
        onCheckedChange = { session.updateSettings { it.copy(showPronunciation = !it.showPronunciation) } },
        modifier = modifier.size(40.dp).semantics {
            contentDescription = label
            stateDescription = state
        },
        colors = IconButtonDefaults.outlinedIconToggleButtonColors(
            containerColor = Color.Transparent,
            contentColor = palette.muted,
            checkedContainerColor = palette.accentSoft,
            checkedContentColor = palette.accent,
        ),
        border = BorderStroke(1.dp, palette.border),
    ) {
        Icon(
            imageVector = if (shown) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
            contentDescription = null,
            modifier = Modifier.size(21.dp),
        )
    }
}
