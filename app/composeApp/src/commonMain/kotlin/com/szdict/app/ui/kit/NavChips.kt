package com.szdict.app.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.app.ui.theme.LocalIsDark

/**
 * A section-navigation chip (HSK level selector, WORDS-tab sections): a label in [color], an
 * optional muted count, and a [selectedFill] wash with a 2 dp border while selected.
 */
@Composable
fun NavChip(
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    color: Color = LocalDictionaryPalette.current.accent,
    selectedFill: Color = LocalDictionaryPalette.current.accentSoft,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDictionaryPalette.current
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            color = if (selected) selectedFill else palette.panel,
            border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) color else palette.border),
            modifier = modifier.semantics {
                role = Role.Tab
                this.selected = selected
            },
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
                if (count != null) Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = palette.muted, maxLines = 1)
            }
        }
    }
}

/**
 * Keeps the selected chip of a horizontally scrolling chip row in view as the content scrolls:
 * [selected] is the chip's position among the chips, [leading] the row items before the first chip.
 * The first chip brings the row back to its start.
 */
@Composable
fun FollowSelectedChip(row: LazyListState, selected: Int, leading: Int = 0, chipCount: Int) {
    LaunchedEffect(selected, chipCount) {
        if (selected > 0) {
            val item = selected + leading
            val info = row.layoutInfo
            if (info.visibleItemsInfo.none { it.index == item && it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }) {
                row.animateScrollToItem(item)
            }
        } else if (selected == 0) {
            row.animateScrollToItem(0)
        }
    }
}

/** Opacity of the page background behind a [TranslucentChipBar]. */
const val CHIP_BAR_ALPHA = 0.88f

/** Padding of a [TranslucentChipBar]'s chip row: vertical around the chips, horizontal at the row's ends. */
val ChipBarVerticalPadding = 10.dp
val ChipBarHorizontalPadding = 14.dp

/**
 * The floating chip bar over scrolling content (HSK-011…016, GLB-074; the HSK selector and the
 * entry page's WORDS chip row and the Parts chip row of DICT): the page background at [CHIP_BAR_ALPHA] so content
 * shows through as it scrolls beneath, no blur, and a bottom-only shadow while [scrolledUnder] (light theme only: in dark theme it reads as a glow).
 * [onBarHeight] reports the bar without its shadow (where content should stop when jumped to).
 */
@Composable
fun TranslucentChipBar(
    scrolledUnder: Boolean,
    modifier: Modifier = Modifier,
    onBarHeight: (Int) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val palette = LocalDictionaryPalette.current
    val shadowAlpha by animateFloatAsState(if (scrolledUnder) 1f else 0f)
    Column(modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth().onSizeChanged { onBarHeight(it.height) },
            color = palette.bg.copy(alpha = CHIP_BAR_ALPHA),
            content = content,
        )
        if (!LocalIsDark.current) {
            Box(
                Modifier.fillMaxWidth().height(8.dp).background(
                    Brush.verticalGradient(listOf(palette.border.copy(alpha = 0.52f * shadowAlpha), palette.border.copy(alpha = 0f)))
                )
            )
        }
    }
}
