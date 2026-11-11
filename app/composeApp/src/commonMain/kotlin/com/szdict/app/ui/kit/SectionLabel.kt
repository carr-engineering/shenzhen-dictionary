package com.szdict.app.ui.kit

import com.szdict.app.i18n.appStrings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.szdict.app.ui.theme.LocalDictionaryPalette

/**
 * Small-caps section heading with an optional count chip
 * (web `.section-label` + `.section-count`, e.g. CONTAINS 「有用」 ELSEWHERE 1).
 */
@Composable
fun SectionLabel(
    text: String,
    count: Int? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalDictionaryPalette.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = appStrings().text(text).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = palette.muted
        )
        count?.let { CountChip(it) }
    }
}
