package com.szdict.app.ui.kit

import com.szdict.app.i18n.appStrings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.szdict.app.ui.theme.LocalDictionaryPalette

/**
 * Underline-style tab strip (web `.entry-tabs`): letter-spaced uppercase
 * labels over a 2dp baseline, active tab in accent with an accent underline.
 */
@Composable
internal fun UnderlineTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalDictionaryPalette.current
    Box(modifier = modifier.fillMaxWidth()) {
        // Full-width baseline; the active tab's underline overlaps it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(palette.border)
                .align(Alignment.BottomStart)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Column(
                    modifier = Modifier.weight(1f).clickable { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = appStrings().text(label).uppercase(),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        color = if (selected) palette.accent else palette.muted
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (selected) palette.accent else Color.Transparent)
                    )
                }
            }
        }
    }
}
