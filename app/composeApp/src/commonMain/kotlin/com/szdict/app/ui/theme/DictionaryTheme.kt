package com.szdict.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import com.szdict.core.api.DictMode
import com.szdict.core.api.ThemeMode
import androidx.compose.runtime.staticCompositionLocalOf

private fun schemeFrom(palette: DictionaryPalette, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = palette.accent,
        onPrimary = palette.onAccent,
        primaryContainer = palette.accentSoft,
        onPrimaryContainer = palette.accent,
        secondary = palette.indigoGray,
        tertiary = palette.tones[2],
        background = palette.bg,
        onBackground = palette.fg,
        surface = palette.panel,
        onSurface = palette.fg,
        surfaceVariant = palette.bgSoft,
        onSurfaceVariant = palette.muted,
        outline = palette.border,
        outlineVariant = palette.border
    )
} else {
    lightColorScheme(
        primary = palette.accent,
        onPrimary = palette.onAccent,
        primaryContainer = palette.accentSoft,
        onPrimaryContainer = palette.accent,
        secondary = palette.indigoGray,
        tertiary = palette.tones[2],
        background = palette.bg,
        onBackground = palette.fg,
        surface = palette.panel,
        onSurface = palette.fg,
        surfaceVariant = palette.bgSoft,
        onSurfaceVariant = palette.muted,
        outline = palette.border,
        outlineVariant = palette.border
    )
}

private val dictionaryShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp)
)

@Composable
fun DictionaryTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    mode: DictMode = DictMode.MANDARIN,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val base = if (dark) DarkDictionaryPalette else LightDictionaryPalette
    val palette = if (mode == DictMode.CANTONESE) base.forCantonese(dark) else base
    CompositionLocalProvider(LocalDictionaryPalette provides palette, LocalIsDark provides dark) {
        MaterialTheme(
            colorScheme = schemeFrom(palette, dark),
            shapes = dictionaryShapes,
            content = content
        )
    }
}

val LocalIsDark = staticCompositionLocalOf { false }
