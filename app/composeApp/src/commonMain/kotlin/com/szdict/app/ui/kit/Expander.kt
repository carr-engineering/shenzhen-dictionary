package com.szdict.app.ui.kit

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.szdict.app.ui.theme.LocalDictionaryPalette
import androidx.compose.ui.unit.dp

/**
 * Accent-soft pill expander toggle (web `.expander-toggle`,
 * e.g. "+2 more within 「有用」" / "− Origin of 有").
 */
@Composable
internal fun ExpanderToggle(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalDictionaryPalette.current
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = onToggle,
            modifier = modifier,
            shape = CircleShape,
            color = palette.accentSoft,
            contentColor = palette.accent
        ) {
            Text(
                text = "${if (expanded) "−" else "+"} $label",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
        }
    }
}
