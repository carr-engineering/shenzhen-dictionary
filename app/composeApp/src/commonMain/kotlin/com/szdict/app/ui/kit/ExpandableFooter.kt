package com.szdict.app.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.theme.LocalDictionaryPalette

/**
 * The expander strip at the foot of a card (ENT-073): a hairline, then [label] (`+ 3 more words
 * within`) and a chevron as one tap target that only toggles; [content] opens beneath it, inside
 * the card, on the expanded-rows tone. Shared by the inside-words footer and the PARTS tab.
 */
@Composable
fun ExpandableFooter(
    label: String,
    spoken: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val stateText = if (expanded) strings.text("Expanded") else strings.text("Collapsed")
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = palette.border)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    stateDescription = stateText
                }
                .heightIn(min = 40.dp)
                .padding(start = 13.dp, end = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = palette.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(22.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(200), expandFrom = Alignment.Top) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(160), shrinkTowards = Alignment.Top) + fadeOut(tween(110)),
        ) {
            // Flush rows on the recessed tone (SRCH-131).
            Column(Modifier.fillMaxWidth().background(expandedRowsColor()), content = content)
        }
    }
}

/** Background of the rows an [ExpandableFooter] opens; rows drawn on it pass it as their badge background. */
@Composable
fun expandedRowsColor() = LocalDictionaryPalette.current.highlightPaper
