package com.szdict.app.platform

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import com.szdict.app.ui.theme.AppFonts

/**
 * The interface font of the iOS app (GLB-008): Source Sans 3 with the ~2,300 characters of the
 * datasets and packs that the iOS system fonts lack (rare ideographs of CJK Extensions B–J …) added
 * from Plangothic, staged by `tools/stage-app-fonts.py` as `assets/fonts/sans-<weight>.otf`.
 *
 * Why in the font itself: Compose on iOS shapes with Skia, whose per-glyph fallback asks only
 * CoreText; CoreText's cascade never offers fonts the app registers (CTFontManagerRegisterFontsForURL)
 * and answers every character it cannot draw with LastResort's "?" box, so fonts handed to Compose
 * (`FontFamily.Resolver.preload`) are never reached either. A glyph is found before fallback only in
 * the family the text is set in. Only characters the system lacks were added, so every other hanzi
 * still comes from PingFang, and Latin text is the unchanged Source Sans outlines.
 *
 * Null when the files were not staged: the app then uses the Source Sans resources, as before.
 */
internal object IosAppFonts {
    private val files = mapOf(
        FontWeight.Normal to "regular",
        FontWeight.Medium to "medium",
        FontWeight.SemiBold to "semibold",
        FontWeight.Bold to "bold",
    )

    val family: FontFamily? by lazy {
        check(files.keys == AppFonts.map { it.first }.toSet()) { "IosAppFonts must cover the weights of AppFonts" }
        val fonts = files.map { (weight, name) ->
            val bytes = readFileBytes(IosBundle.path("fonts/sans-$name.otf")) ?: return@lazy null
            Font("SZDictSans-$name", bytes, weight)
        }
        FontFamily(fonts)
    }
}
