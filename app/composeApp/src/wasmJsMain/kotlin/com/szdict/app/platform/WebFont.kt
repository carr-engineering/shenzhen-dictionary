package com.szdict.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density

/**
 * The Web CJK font (ARCH-005). Compose draws text itself and cannot use the browser's fonts, and
 * this Compose version has no automatic web font fallback yet, so one Noto Sans CJK face is
 * fetched after start-up — from `fonts/` when the build staged one, otherwise from the font host —
 * and registered as a fallback. Start-up never waits for it: text laid out before it arrived is
 * laid out again once it is registered.
 */
@Composable
fun WithCjkFont(content: @Composable () -> Unit) {
    val resolver = LocalFontFamilyResolver.current
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(resolver) {
        for (url in CJK_FONT_URLS) {
            val bytes = fetchBytes(url) ?: continue
            val loaded = runCatching { resolver.preload(FontFamily(Font("NotoSansCJK", bytes))) }.isSuccess
            if (loaded) {
                generation++
                break
            }
        }
    }
    val density = LocalDensity.current
    // Paragraphs are cached by their inputs (Skia compares font sizes with a tolerance of 1/4096 px);
    // an imperceptibly different font scale is a new input for every one of them, so glyphs that
    // were missing are shaped again with the fallback font.
    val refreshed = remember(density, generation) {
        if (generation == 0) density else Density(density.density, density.fontScale * (1f + generation * REFRESH_STEP))
    }
    CompositionLocalProvider(LocalDensity provides refreshed, content = content)
}

private const val REFRESH_STEP = 1f / 4096

private val CJK_FONT_URLS = listOf(
    "fonts/NotoSansCJKsc-Regular.otf",
    "https://cdn.jsdelivr.net/gh/notofonts/noto-cjk@Sans2.004/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Regular.otf",
)
