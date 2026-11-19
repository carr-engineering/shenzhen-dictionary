package com.szdict.app.ui.kit

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.szdict.app.ui.theme.DictionaryPalette
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.AnalysedPassage

/**
 * Hanzi size of the passage surfaces (Search sentence card, Reading, OCR text, Voice): one size
 * larger than the headline, so that a character cell (advance plus [PASSAGE_LETTER_SPACING_EM])
 * holds the reading of nearly every syllable at full ruby size. Measured on the dataset's syllables
 * (Source Sans 3 Medium, the app's Latin font): at 28 sp with 12 sp readings the cell, less the
 * 2 dp between readings, has room for 2.87 em of reading, and every Pinyin syllable up to five
 * letters (2.68 em at most) fits; only zhuang/chuang/shuang (3.10–3.13 em) and the longest
 * Jyutping (gwaang2, 3.82 em) can need more room, so the layout shares space between neighbouring readings ([placeRuby]).
 */
val PassageFontSize: TextUnit = 28.sp

/** The text style of a passage surface: the headline at [PassageFontSize]. */
@Composable
fun passageTextStyle(): TextStyle = MaterialTheme.typography.headlineSmall.copy(fontSize = PassageFontSize)

/**
 * Whether the text engine adds letter spacing after each glyph (true) or splits it around the
 * glyph (false). Readings are centred on the glyph, not on its cell with the spacing.
 */
internal expect val letterSpacingTrails: Boolean

/** Letter spacing of analysed text in a ruby passage, the same with readings shown or hidden. */
internal const val PASSAGE_LETTER_SPACING_EM = 0.3f

/** Reading size relative to the hanzi: 12 sp over 28 sp hanzi. */
internal const val RUBY_SIZE_RATIO = 12f / 28f

/** Line height without readings, in hanzi ems (CJK fonts are about 1.45 em from ascent to descent). */
internal const val PLAIN_LINE_HEIGHT_EM = 1.45f

/** Height of the reading band added above each line when readings are shown, in reading ems. */
internal const val RUBY_BAND_EM = 0.8f

/**
 * The size decisions of a passage (TXT-034/035, GLB-120), in sp. Only [lineHeight] depends on
 * whether readings are shown: hanzi size and letter spacing — so every line break and every
 * character's x — are the same in both states, and hiding the readings only closes the lines up.
 */
internal data class PassageMetrics(
    val fontSize: Float,
    val letterSpacingEm: Float,
    val rubyFontSize: Float,
    val lineHeight: Float,
)

internal fun passageMetrics(fontSizeSp: Float, showReadings: Boolean): PassageMetrics {
    val ruby = fontSizeSp * RUBY_SIZE_RATIO
    val plain = fontSizeSp * PLAIN_LINE_HEIGHT_EM
    return PassageMetrics(
        fontSize = fontSizeSp,
        letterSpacingEm = PASSAGE_LETTER_SPACING_EM,
        rubyFontSize = ruby,
        lineHeight = if (showReadings) plain + ruby * RUBY_BAND_EM else plain,
    )
}

/**
 * The shared interactive passage renderer (TXT-030…TXT-035): one text layout, tone colours,
 * alternating word-boundary underlines, interlinear (ruby) readings in a band above each line,
 * selected character highlight, and taps resolved to a visible code-point start.
 *
 * The band exists only while readings are shown (GLB-120); hanzi size and spacing never change
 * with it, so toggling moves lines vertically and nothing else.
 *
 * Used verbatim by the Search passage card, Reading, OCR text lines and Voice utterances, for
 * original and script-converted text alike (TXT-032).
 */
@Composable
fun PassageText(
    passage: AnalysedPassage,
    selectedOffset: Int?,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = passageTextStyle(),
    ruby: Boolean = true,
) {
    val palette = LocalDictionaryPalette.current
    val prefs = LocalDisplayPrefs.current
    val text = passage.text
    var layoutResult by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val onCurrentTap by rememberUpdatedState(onTap)
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val annotated = remember(passage, selectedOffset, prefs.toneColors, palette, ruby) {
        passageAnnotatedString(passage, selectedOffset, prefs.toneColors, palette, ruby)
    }
    val showRuby = ruby && prefs.showPronunciation
    val fontSizeSp = if (style.fontSize.isSp) style.fontSize.value else PassageFontSize.value
    val metrics = passageMetrics(fontSizeSp, showRuby)
    val textStyle = if (ruby) {
        style.copy(
            lineHeight = metrics.lineHeight.sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.None),
        )
    } else style
    val rubyStyle = remember(metrics.rubyFontSize, style.fontFamily) {
        TextStyle(fontSize = metrics.rubyFontSize.sp, fontWeight = FontWeight.Medium, fontFamily = style.fontFamily)
    }
    val showBoundaries = prefs.showBoundaries
    val toneColors = prefs.toneColors
    val density = LocalDensity.current
    // Readings are measured and placed once per layout, not on every draw.
    val rubyPlan = remember(passage, layoutResult, rubyStyle, showRuby, density) {
        val layout = layoutResult
        if (!showRuby || layout == null || layout.layoutInput.text.length != text.length) null
        else with(density) {
            val trailing = if (letterSpacingTrails) metrics.letterSpacingEm * metrics.fontSize.sp.toPx() else 0f
            rubyPlan(passage, text, layout, measurer, rubyStyle, 2.dp.toPx(), trailing)
        }
    }

    Text(
        text = annotated,
        style = textStyle,
        modifier = modifier
            .drawWithContent {
                drawContent()
                val layout = layoutResult ?: return@drawWithContent
                if (layout.layoutInput.text.length != text.length) return@drawWithContent
                val inset = 1.25.dp.toPx()
                val strokeWidth = 1.6.dp.toPx()
                var shade = 0
                if (showBoundaries) for (segment in passage.segments) {
                    if (!segment.matched || segment.length <= 0 || segment.end > text.length) continue
                    var i = segment.start
                    while (i < segment.end) {
                        val len = codePointLength(text, i)
                        val box = layout.getBoundingBox(i)
                        val first = i == segment.start
                        val last = i + len >= segment.end
                        val left = box.left + if (first) inset else 0f
                        val right = box.right - if (last) inset else 0f
                        if (right > left) {
                            val y = (box.bottom - 1.dp.toPx()).coerceAtMost(size.height - strokeWidth)
                            drawLine(
                                color = (if (shade == 0) palette.segA else palette.segB).let {
                                    it.copy(alpha = if (shade == 0) 0.82f else 0.48f)
                                },
                                start = Offset(left, y), end = Offset(right, y), strokeWidth = strokeWidth, cap = StrokeCap.Round,
                            )
                        }
                        i += len
                    }
                    shade = 1 - shade
                }
                rubyPlan?.let { plan ->
                    for ((index, item) in plan.items.withIndex()) {
                        val placement = plan.placements[index]
                        val scale = placement.scale
                        val color = when {
                            item.tone == null -> palette.muted
                            toneColors && item.tone > 0 -> palette.tone(item.tone)
                            else -> palette.muted
                        }
                        // All readings share one baseline, scaled ones included: a syllable whose tone
                        // mark comes from a fallback font (taller line) does not sink below its neighbours.
                        val y = item.top + 1.dp.toPx() + plan.baseline - item.measured.firstBaseline * scale
                        withTransform({ translate(placement.left, y); scale(scale, scale, pivot = Offset.Zero) }) {
                            drawText(item.measured, color = color)
                        }
                    }
                }
            }
            .wordHover(passage) { position ->
                layoutResult?.let { passageOffsetAt(it, passage, position) }?.let { WordLookup(passage, it) }
            }
            .pointerInput(passage) {
                detectTapGestures { position ->
                    val layout = layoutResult ?: return@detectTapGestures
                    passageOffsetAt(layout, passage, position, allowNearest = true)?.let(onCurrentTap)
                }
            },
        onTextLayout = { layoutResult = it },
    )
}

/** Convert Compose's nearest caret into the character cell under the pointer. */
internal fun passageOffsetAt(
    layout: TextLayoutResult,
    passage: AnalysedPassage,
    position: Offset,
    allowNearest: Boolean = false,
): Int? {
    val text = passage.text
    val raw = layout.getOffsetForPosition(position)
    val candidates = listOf(raw, raw - 1).filter { it in text.indices }
    val hit = candidates.firstOrNull { layout.getBoundingBox(it).contains(position) }
        ?: (if (allowNearest) candidates.firstOrNull() else null)
        ?: return null
    return passage.normaliseOffset(hit).takeUnless { text[it].isWhitespace() }
}

/** A reading to draw: one syllable over its character ([tone] set) or a whole word's reading ([tone] null). */
private class RubyItem(val measured: TextLayoutResult, val top: Float, val tone: Int?, val slot: RubySlot)

/** [baseline]: of the readings, below the top of their line's band. */
private class RubyPlan(val items: List<RubyItem>, val placements: List<RubyPlacement>, val baseline: Float)

/**
 * Measures the readings at full size — never cropped — and places them: one syllable centred over
 * each character of a tone-colourable segment, the whole reading centred over the other segments'
 * glyphs on the line where they start.
 */
private fun rubyPlan(
    passage: AnalysedPassage,
    text: String,
    layout: TextLayoutResult,
    measurer: TextMeasurer,
    rubyStyle: TextStyle,
    gapPx: Float,
    trailingSpacingPx: Float,
): RubyPlan {
    val measuredByReading = HashMap<String, TextLayoutResult>()
    fun measure(reading: String) = measuredByReading.getOrPut(reading) { measurer.measure(reading, rubyStyle, softWrap = false, maxLines = 1) }
    val items = ArrayList<RubyItem>()
    for (segment in passage.segments) {
        if (!segment.matched || segment.reading.isEmpty || segment.end > text.length) continue
        if (segment.toneColourable) {
            val syllables = segment.reading.display.split(' ').filter { it.isNotEmpty() }
            val tones = segment.reading.tones
            var i = segment.start
            var n = 0
            while (i < segment.end && n < syllables.size) {
                val box = layout.getBoundingBox(i)
                val measured = measure(syllables[n])
                val slot = RubySlot(layout.getLineForOffset(i), box.center.x - trailingSpacingPx / 2f, measured.size.width.toFloat())
                items += RubyItem(measured, box.top, tones.getOrElse(n) { 0 }, slot)
                i += codePointLength(text, i)
                n++
            }
        } else {
            val firstBox = layout.getBoundingBox(segment.start)
            val line = layout.getLineForOffset(segment.start)
            var right = firstBox.right
            var i = segment.start
            while (i < segment.end && layout.getLineForOffset(i) == line) {
                right = layout.getBoundingBox(i).right
                i += codePointLength(text, i)
            }
            val measured = measure(segment.reading.display)
            val slot = RubySlot(line, (firstBox.left + right - trailingSpacingPx) / 2f, measured.size.width.toFloat())
            items += RubyItem(measured, firstBox.top, null, slot)
        }
    }
    val baseline = measure("a").firstBaseline
    return RubyPlan(items, placeRuby(items.map { it.slot }, 0f, layout.size.width.toFloat(), gapPx), baseline)
}

/** A reading of [width] pixels at full size, centred over its glyph or word. */
internal class RubySlot(val line: Int, val center: Float, val width: Float)

/** Where a reading is drawn (scale 1 is full size). */
internal data class RubyPlacement(val left: Float, val scale: Float)

/** Shrink readings at line edges and resolve collisions without moving their centres. */
internal fun placeRuby(slots: List<RubySlot>, lineLeft: Float, lineRight: Float, gap: Float): List<RubyPlacement> {
    val radii = FloatArray(slots.size) { i ->
        val slot = slots[i]
        minOf(slot.width / 2f, slot.center - lineLeft, lineRight - slot.center).coerceAtLeast(0f)
    }
    for (line in slots.indices.groupBy { slots[it].line }.values) {
        val ordered = line.sortedBy { slots[it].center }
        for (i in 1 until ordered.size) {
            val left = ordered[i - 1]
            val right = ordered[i]
            val room = (slots[right].center - slots[left].center - gap).coerceAtLeast(0f)
            if (radii[left] + radii[right] <= room) continue
            // A shorter reading keeps its size; two wide readings share the available space.
            radii[left] = minOf(radii[left], maxOf(room / 2f, room - radii[right]))
            radii[right] = minOf(radii[right], room - radii[left])
        }
    }
    return slots.mapIndexed { i, slot ->
        RubyPlacement(slot.center - radii[i], if (slot.width > 0f) 2f * radii[i] / slot.width else 1f)
    }
}

internal fun passageAnnotatedString(
    passage: AnalysedPassage,
    selectedOffset: Int?,
    toneColors: Boolean,
    palette: DictionaryPalette,
    ruby: Boolean,
): AnnotatedString = buildAnnotatedString {
    val text = passage.text
    append(text)
    addStyle(SpanStyle(color = palette.muted), 0, text.length)
    for (segment in passage.segments) {
        val start = segment.start.coerceIn(0, text.length)
        val end = segment.end.coerceIn(start, text.length)
        if (start == end || !segment.matched) continue
        // Constant spacing in ruby passages keeps room for readings and never reflows on toggle (GLB-120).
        addStyle(SpanStyle(color = palette.fg, letterSpacing = if (ruby) PASSAGE_LETTER_SPACING_EM.em else androidx.compose.ui.unit.TextUnit.Unspecified), start, end)
        if (toneColors && segment.toneColourable) {
            var i = start
            var n = 0
            while (i < end) {
                val len = codePointLength(text, i)
                val tone = segment.reading.tones.getOrElse(n) { 0 }
                if (tone > 0) addStyle(SpanStyle(color = palette.tone(tone)), i, i + len)
                i += len
                n++
            }
        }
    }
    selectedOffset?.takeIf { it in text.indices }?.let { index ->
        addStyle(SpanStyle(background = palette.hlBg, fontWeight = FontWeight.SemiBold), index, index + codePointLength(text, index))
    }
}
