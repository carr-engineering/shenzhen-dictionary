package com.szdict.app.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.szdict.app.ui.theme.LocalDictionaryPalette

/**
 * Header nav pill (web `.tabs button`): quiet text, accent-soft wash when active.
 */
@Composable
fun NavPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalDictionaryPalette.current
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (selected) palette.accentSoft else Color.Transparent,
            contentColor = if (selected) palette.accent else palette.muted
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

/**
 * Joined segmented pill control (web `.result-tabs` / `.mode-toggle`):
 * one bordered rounded container, active segment = solid accent.
 */
@Composable
fun SegmentedPillRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val palette = LocalDictionaryPalette.current
    Surface(
        shape = CircleShape,
        color = palette.bgSoft,
        border = BorderStroke(1.dp, palette.border)
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Row {
                options.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    Surface(
                        onClick = { onSelect(index) },
                        shape = CircleShape,
                        color = if (selected) palette.accent else Color.Transparent,
                        contentColor = if (selected) palette.onAccent else palette.muted
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * Outlined HSK level pills (web `.hsk-badge`): 1.5dp border in the level color,
 * no fill, "HSK 1".."HSK 7–9".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HskBadge(
    levels: List<Int>,
    compact: Boolean = false,
    posLabels: Map<Int, String> = emptyMap(),
    countsByLevel: Map<Int, Int> = emptyMap(),
    modifier: Modifier = Modifier
) {
    if (levels.isEmpty()) return
    val palette = LocalDictionaryPalette.current
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        levels.forEach { level ->
            val color = palette.hsk(level)
            Text(
                text = hskBadgeLabel(
                    level = level,
                    posLabel = posLabels[level],
                    count = countsByLevel[level]
                ),
                modifier = Modifier
                    .border(1.5.dp, color, CircleShape)
                    .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp,
                color = color,
                maxLines = 1
            )
        }
    }
}

fun hskLevelLabel(level: Int): String =
    if (level == 7) "HSK 7–9" else "HSK $level"

fun hskBadgeLabel(level: Int, posLabel: String? = null, count: Int? = null): String {
    val levelLabel = hskLevelLabel(level)
    return when {
        posLabel != null -> "$levelLabel • $posLabel"
        count != null -> "$levelLabel ($count)"
        else -> levelLabel
    }
}

/**
 * Small neutral count chip shown next to section labels (web `.section-count`).
 */
@Composable
fun CountChip(count: Int) {
    val palette = LocalDictionaryPalette.current
    Surface(
        shape = CircleShape,
        color = palette.bgSoft,
        contentColor = palette.muted
    ) {
        Text(
            text = "$count",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}
