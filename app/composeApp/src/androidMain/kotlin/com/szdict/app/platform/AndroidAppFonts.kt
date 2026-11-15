package com.szdict.app.platform

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.szdict.app.ui.theme.AppFonts

/**
 * The interface font of the Android app (GLB-008): Source Sans 3 with the ~2,400 characters of the
 * datasets and packs that Noto Sans CJK, Android's system CJK font, lacks (rare ideographs of CJK
 * Extensions B–J …) added from Plangothic, staged by `tools/stage-app-fonts.py` and packaged as the
 * assets `fonts/sans-<weight>.otf`.
 *
 * The system fallback chain never reaches a font the app brings, but a glyph is looked for in the
 * text's own family first, so the characters sit there. Only characters Noto Sans CJK lacks were
 * added, so every other hanzi still comes from the system font, and Latin text is the unchanged
 * Source Sans outlines. Compose opens each weight when text first needs it, as it did the
 * resources; the files are stored uncompressed (`noCompress` "otf"), so a weight is mapped rather
 * than inflated: all four take about 3 ms on the emulator, against 12 ms for the deflated resources.
 *
 * Null when the files were not packaged: the app then uses the Source Sans resources, as before.
 */
internal object AndroidAppFonts {
    private val files = mapOf(
        FontWeight.Normal to "regular",
        FontWeight.Medium to "medium",
        FontWeight.SemiBold to "semibold",
        FontWeight.Bold to "bold",
    )

    @Volatile private var cached: Result<FontFamily?>? = null

    fun family(context: Context): FontFamily? {
        cached?.let { return it.getOrNull() }
        check(files.keys == AppFonts.map { it.first }.toSet()) { "AndroidAppFonts must cover the weights of AppFonts" }
        val assets = context.applicationContext.assets
        val present = runCatching { assets.list("fonts")?.toSet() }.getOrNull().orEmpty()
        val family = if (files.values.all { "sans-$it.otf" in present }) {
            FontFamily(files.map { (weight, name) -> Font("fonts/sans-$name.otf", assets, weight) })
        } else null
        cached = Result.success(family)
        return family
    }
}
