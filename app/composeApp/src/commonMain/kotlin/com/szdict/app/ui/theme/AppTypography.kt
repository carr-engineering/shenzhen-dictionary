package com.szdict.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.szdict.app.resources.Res
import com.szdict.app.resources.source_sans_3_bold
import com.szdict.app.resources.source_sans_3_medium
import com.szdict.app.resources.source_sans_3_regular
import com.szdict.app.resources.source_sans_3_semibold
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.FontResource

/** The weights the interface uses — Material's 400 and 500, and 600 / 700 for emphasis — and their files. */
internal val AppFonts: List<Pair<FontWeight, FontResource>> = listOf(
    FontWeight.Normal to Res.font.source_sans_3_regular,
    FontWeight.Medium to Res.font.source_sans_3_medium,
    FontWeight.SemiBold to Res.font.source_sans_3_semibold,
    FontWeight.Bold to Res.font.source_sans_3_bold,
)

/**
 * Source Sans 3 (SIL OFL 1.1, Adobe) for Latin text: English, Pinyin and Jyutping, including every
 * tone-marked letter the app prints (ā…ǜ, ḿ ń ň ǹ and the combining marks). It has no Han glyphs,
 * so Chinese keeps coming from the platform's fallback exactly as before: the system CJK font on
 * Android, PingFang on iOS, the staged Noto Sans CJK tiers on Web (WebFont.kt).
 *
 * `composeResources/font/` holds the unmodified static OpenType (CFF) instances of release 3.052R,
 * one per weight of [AppFonts] (1.35 MB in all). Not the variable font: drawn through its `wght`
 * axis on Web, its weights came out mixed within a word.
 */
@Composable
fun appFontFamily(): FontFamily {
    val fonts = AppFonts.map { (weight, resource) -> Font(resource, weight) }
    return remember(fonts) { FontFamily(fonts) }
}

/** Material's type scale with every style set in [family]. */
internal fun appTypography(family: FontFamily): Typography {
    val base = Typography()
    fun TextStyle.inFamily() = copy(fontFamily = family)
    return Typography(
        displayLarge = base.displayLarge.inFamily(),
        displayMedium = base.displayMedium.inFamily(),
        displaySmall = base.displaySmall.inFamily(),
        headlineLarge = base.headlineLarge.inFamily(),
        headlineMedium = base.headlineMedium.inFamily(),
        headlineSmall = base.headlineSmall.inFamily(),
        titleLarge = base.titleLarge.inFamily(),
        titleMedium = base.titleMedium.inFamily(),
        titleSmall = base.titleSmall.inFamily(),
        bodyLarge = base.bodyLarge.inFamily(),
        bodyMedium = base.bodyMedium.inFamily(),
        bodySmall = base.bodySmall.inFamily(),
        labelLarge = base.labelLarge.inFamily(),
        labelMedium = base.labelMedium.inFamily(),
        labelSmall = base.labelSmall.inFamily(),
    )
}
