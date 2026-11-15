package com.szdict.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import com.szdict.app.AppSession
import com.szdict.app.Destination
import com.szdict.core.api.DictMode
import com.szdict.core.api.Script
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Web CJK font (ARCH-005, GLB-008). Compose draws text itself and cannot use the browser's
 * fonts, and this Compose version (1.11) has no automatic web font fallback — that arrives with
 * 1.12, which needs a newer Android toolchain than the project builds with — so Noto Sans CJK is
 * fetched after start-up and registered as the fallback for the glyphs the bundled Latin font lacks.
 *
 * The glyph variant follows the content the user reads: Simplified Chinese shapes (SC) for
 * Mandarin in Simplified script, Taiwan shapes (TC) for Mandarin in Traditional script and Hong
 * Kong shapes (HK) for Cantonese. Fonts registered first win, so the variant is chosen once, when
 * the page starts; a later change of mode or script takes effect on the next load.
 *
 * When the build staged frequency subsets (`tools/stage-web-fonts.sh` → `fonts/index.json`) the
 * font arrives in steps, each registered as soon as it is here:
 *  1. the core — the most frequent characters of the dataset's headwords, with the accented
 *     letters of Pinyin and the punctuation;
 *  2. the further tiers, down to every character the datasets and packs contain;
 *  3. the complete font, only once Reading or OCR is opened, where text from outside the dictionary is shown.
 * Without staged subsets the complete font of the variant is fetched from the font host at once.
 * Start-up never waits for any of it: text laid out before a font arrived is laid out again.
 *
 * Characters of the datasets that Noto Sans CJK has no glyph for (rare ideographs of the CJK
 * extensions, about 2,400) come from the rare tier, files shared by every variant: each is fetched
 * only once the dictionary has handed the interface one of its characters ([ShownCharacters]).
 * Staged files are verified against the SHA-256 of `fonts/index.json`.
 */
@Composable
fun WithCjkFont(session: AppSession, content: @Composable () -> Unit) {
    val resolver = LocalFontFamilyResolver.current
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(resolver) {
        var hashes: Map<String, String> = emptyMap()
        suspend fun register(name: String, urls: List<String>): Boolean {
            for (url in urls) {
                val sha256 = hashes[url]
                val bytes = (if (sha256 != null) fetchVerifiedBytes(url, sha256) else fetchBytes(url)) ?: continue
                if (!isFontFile(bytes)) continue   // a host answering a missing file with the app shell
                if (runCatching { resolver.preload(FontFamily(Font(name, bytes))) }.isSuccess) {
                    generation++
                    return true
                }
            }
            return false
        }
        val index = stagedIndex()
        hashes = index?.sha256.orEmpty()
        // The rare tier waits for its characters, beside everything below.
        index?.rare?.forEachIndexed { k, file ->
            launch {
                ShownCharacters.version.first { ShownCharacters.any(file::contains) }
                register("RareHan-$k", listOf(file.url))
            }
        }
        snapshotFlow { session.settingsLoaded }.first { it }
        // On a first visit the service worker is still installing: the font waits for it (a moment,
        // behind the dataset download) so the files are kept for the next, possibly offline, start.
        awaitServiceWorker()
        val settings = session.settings.value
        val variant = when {
            settings.dictionaryMode == DictMode.CANTONESE -> CjkVariant.HK
            settings.script(DictMode.MANDARIN) == Script.TRADITIONAL -> CjkVariant.TC
            else -> CjkVariant.SC
        }
        val staged = index?.variants?.get(variant.key)
        val complete = listOfNotNull(staged?.full, FONT_HOST + variant.hostPath)
        if (staged == null || !register("NotoSansCJK-0", listOf(staged.tiers.first()))) {
            register("NotoSansCJK", complete)
            return@LaunchedEffect
        }
        // The first frames and the first search come first; the long tail follows — on a first
        // visit only once the dataset is here, so it never shares the connection with it (PERF-060).
        runCatching { session.dictionary() }
        staged.tiers.drop(1).forEachIndexed { index, url ->
            delay(TIER_DELAY_MS * (index + 1))
            register("NotoSansCJK-${index + 1}", listOf(url))
        }
        snapshotFlow { session.destination }.first { it == Destination.READING || it == Destination.OCR }
        register("NotoSansCJK", complete)
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

private enum class CjkVariant(val key: String, @Suppress("unused") val file: String, val hostPath: String) {
    SC("sc", "NotoSansCJKsc-Regular.otf", "SimplifiedChinese/NotoSansCJKsc-Regular.otf"),
    TC("tc", "NotoSansCJKtc-Regular.otf", "TraditionalChinese/NotoSansCJKtc-Regular.otf"),
    HK("hk", "NotoSansCJKhk-Regular.otf", "TraditionalChineseHK/NotoSansCJKhk-Regular.otf"),
}

private class StagedSubsets(val tiers: List<String>, val full: String?)

/** A file of the rare tier and the code points it holds, as sorted inclusive ranges. */
private class RareFile(val url: String, private val starts: IntArray, private val ends: IntArray) {
    fun contains(codePoint: Int): Boolean {
        var low = 0
        var high = starts.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                codePoint < starts[mid] -> high = mid - 1
                codePoint > ends[mid] -> low = mid + 1
                else -> return true
            }
        }
        return false
    }
}

private class StagedIndex(val variants: Map<String, StagedSubsets>, val rare: List<RareFile>, val sha256: Map<String, String>)

/**
 * `fonts/index.json`, or null when nothing is staged:
 * `{ "sc": { "tiers": ["sc-core.otf", "sc-mid.otf", "sc-tail.otf"], "full": "…" }, …,
 *    "rare": [{ "file": "rare-1.ttf", "ranges": "20000-20003,2A6D6,…" }, …], "sha256": { "sc-core.otf": "…", … } }`.
 * URLs are relative to the page (`fonts/…`), and so are the keys of [StagedIndex.sha256].
 */
private suspend fun stagedIndex(): StagedIndex? {
    val text = fetchText("fonts/index.json") ?: return null
    return try {
        val root = Json.parseToJsonElement(text) as JsonObject
        val variants = CjkVariant.entries.mapNotNull { variant ->
            val entry = root[variant.key] as? JsonObject ?: return@mapNotNull null
            val tiers = (entry["tiers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.let { name -> "fonts/$name" } }.orEmpty()
            if (tiers.isEmpty()) null else variant.key to StagedSubsets(tiers, (entry["full"] as? JsonPrimitive)?.content?.let { "fonts/$it" })
        }.toMap()
        val rare = (root["rare"] as? JsonArray)?.mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            val file = (entry["file"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val ranges = (entry["ranges"] as? JsonPrimitive)?.content?.split(',')?.filter { it.isNotEmpty() }?.map { range ->
                val bounds = range.split('-').map { it.toInt(16) }
                bounds.first() to bounds.last()
            }?.sortedBy { it.first }.orEmpty()
            RareFile("fonts/$file", IntArray(ranges.size) { ranges[it].first }, IntArray(ranges.size) { ranges[it].second })
        }.orEmpty()
        val sha256 = (root["sha256"] as? JsonObject)?.mapNotNull { (name, value) -> (value as? JsonPrimitive)?.content?.let { "fonts/$name" to it } }?.toMap().orEmpty()
        StagedIndex(variants, rare, sha256)
    } catch (e: Throwable) {
        null
    }
}

/** OpenType with CFF (`OTTO`) or TrueType outlines, or a collection. */
private fun isFontFile(bytes: ByteArray): Boolean {
    if (bytes.size < 12) return false
    val tag = (0 until 4).joinToString("") { (bytes[it].toInt() and 0xFF).toChar().toString() }
    return tag == "OTTO" || tag == "ttcf" || tag == "true" || (bytes[0].toInt() == 0 && bytes[1].toInt() == 1 && bytes[2].toInt() == 0 && bytes[3].toInt() == 0)
}

private const val REFRESH_STEP = 1f / 4096
private const val TIER_DELAY_MS = 1500L
private const val FONT_HOST = "https://cdn.jsdelivr.net/gh/notofonts/noto-cjk@Sans2.004/Sans/OTF/"
