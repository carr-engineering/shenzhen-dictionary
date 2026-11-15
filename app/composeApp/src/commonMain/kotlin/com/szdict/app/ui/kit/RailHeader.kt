package com.szdict.app.ui.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.szdict.app.AppName
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.theme.LocalDictionaryPalette

/**
 * Header of the expanded navigation rail: the app logo, large and centred, with the app's full
 * name (AppName.full — "Shenzhen Dictionary", 深圳词典 or 深圳詞典) centred below it. Read as one
 * heading.
 */
@Composable
fun RailHeader(modifier: Modifier = Modifier) {
    val palette = LocalDictionaryPalette.current
    val name = AppName.full(appStrings().lang)
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 24.dp)
            .clearAndSetSemantics { heading(); contentDescription = name },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(AppLogo, contentDescription = null, modifier = Modifier.size(60.dp))
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.fg,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
