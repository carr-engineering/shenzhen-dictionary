package com.szdict.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Non-Material design tokens ported from the web app's styles.css
 * ("ink-and-paper reference book" language: warm paper neutrals, near-black
 * ink, one seal-red accent). Dark variants are derived — the web is light-only.
 */
@Immutable
data class DictionaryPalette(
    val bg: Color,
    val bgSoft: Color,
    val panel: Color,
    val fg: Color,
    val muted: Color,
    val border: Color,
    val accent: Color,
    val accentSoft: Color,
    val hlBg: Color,
    val indigoGray: Color,
    val onAccent: Color,
    /** tones[0]..tones[4] = Mandarin tones 1..5 (5 = neutral). */
    val tones: List<Color>,
    /** hskColors[0]..hskColors[6] = HSK levels 1..7 (7 = band 7–9). */
    val hskColors: List<Color>,
    /** Paper-tint card backgrounds per HSK level. */
    val hskTints: List<Color>,
    /** Alternating word-boundary underline shades (sentence widget / reading). */
    val segA: Color,
    val segB: Color
) {
    /** Tone colour by tone number with a safe fallback (GLB-064). */
    fun tone(tone: Int): Color = tones.getOrElse(tone - 1) { tones.last() }
    val highlight: Color get() = hlBg

    /** Six-tone palette for Jyutping (GLB-064): T1–T4 reused, plus T5 and T6. */
    fun forCantonese(dark: Boolean): DictionaryPalette = copy(
        tones = tones.take(4) + if (dark) listOf(Color(0xFFC49A4E), Color(0xFF97A0B4)) else listOf(Color(0xFF7A5A24), Color(0xFF5A6478))
    )

    fun part(index: Int, dark: Boolean): Color {
        val colors = if (dark) listOf(Color(0xFF6FA8F0), Color(0xFFE0A84A), Color(0xFF5FBF88))
        else listOf(Color(0xFF1E6FD0), Color(0xFFC27C0E), Color(0xFF2E8B57))
        return colors[index.mod(colors.size)]
    }
    fun hsk(level: Int): Color = hskColors.getOrElse(level - 1) { hskColors.last() }
    fun hskTint(level: Int): Color = hskTints.getOrElse(level - 1) { hskTints.last() }
}

val LightDictionaryPalette = DictionaryPalette(
    bg = Color(0xFFFAF7F1),
    bgSoft = Color(0xFFF2EEE5),
    panel = Color(0xFFFFFDF8),
    fg = Color(0xFF221F1A),
    muted = Color(0xFF6F695E),
    border = Color(0xFFDDD6C8),
    accent = Color(0xFFA53A2B),
    accentSoft = Color(0xFFF3E4DF),
    hlBg = Color(0xFFF4E9C8),
    indigoGray = Color(0xFF5A6478),
    onAccent = Color.White,
    tones = listOf(
        Color(0xFFB3382C),
        Color(0xFFB07514),
        Color(0xFF33803F),
        Color(0xFF2B5F9E),
        Color(0xFF8A857C)
    ),
    hskColors = listOf(
        Color(0xFF33803F),
        Color(0xFF1F7F74),
        Color(0xFF2B5F9E),
        Color(0xFF5D4D9C),
        Color(0xFF9C3F74),
        Color(0xFFB3382C),
        Color(0xFF7A5A24)
    ),
    hskTints = listOf(
        Color(0xFFEDF3E8),
        Color(0xFFE8F2EE),
        Color(0xFFE9EFF5),
        Color(0xFFEEEBF4),
        Color(0xFFF5EAF0),
        Color(0xFFF7EBE7),
        Color(0xFFF4EEE1)
    ),
    segA = Color(0x401E6FD0),
    segB = Color(0x121E6FD0)
)

val DarkDictionaryPalette = DictionaryPalette(
    bg = Color(0xFF17140F),
    bgSoft = Color(0xFF242019),
    panel = Color(0xFF1E1A14),
    fg = Color(0xFFEAE4D8),
    muted = Color(0xFFA69E8F),
    border = Color(0xFF3C362B),
    accent = Color(0xFFD46A58),
    accentSoft = Color(0xFF43241D),
    hlBg = Color(0xFF4A3F1E),
    indigoGray = Color(0xFF97A0B4),
    onAccent = Color(0xFF1B0E0B),
    tones = listOf(
        Color(0xFFE0685A),
        Color(0xFFD99C2E),
        Color(0xFF63AC70),
        Color(0xFF6F9FD6),
        Color(0xFF9C968B)
    ),
    hskColors = listOf(
        Color(0xFF63AC70),
        Color(0xFF4FAB9E),
        Color(0xFF6F9FD6),
        Color(0xFF9688CE),
        Color(0xFFC677A2),
        Color(0xFFE0685A),
        Color(0xFFC49A4E)
    ),
    hskTints = listOf(
        Color(0xFF1E2A1B),
        Color(0xFF182A26),
        Color(0xFF1A222D),
        Color(0xFF221E2E),
        Color(0xFF2C1D26),
        Color(0xFF2E1B17),
        Color(0xFF2A2417)
    ),
    segA = Color(0x596F9FD6),
    segB = Color(0x216F9FD6)
)

val LocalDictionaryPalette = staticCompositionLocalOf { LightDictionaryPalette }
