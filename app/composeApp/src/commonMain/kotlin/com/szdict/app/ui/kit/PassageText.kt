package com.szdict.app.ui.kit

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.szdict.app.ui.theme.DictionaryPalette
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.PassageSegment

/**
 * The shared interactive passage renderer (TXT-030…TXT-035): one text layout, tone colours,
 * alternating word-boundary underlines, interlinear (ruby) readings in a reserved band, selected
 * character highlight, and taps resolved to a visible code-point start.
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
    style: TextStyle = MaterialTheme.typography.headlineSmall,
    ruby: Boolean = true,
) {
    val palette = LocalDictionaryPalette.current
    val prefs = LocalDisplayPrefs.current
    val text = passage.text
    var layoutResult by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val annotated = remember(passage, selectedOffset, prefs.toneColors, palette, ruby) {
        passageAnnotatedString(passage, selectedOffset, prefs.toneColors, palette, ruby)
    }
    // Every line reserves the ruby band above its glyphs, whether or not readings are drawn (TXT-034/035).
    val textStyle = if (ruby) {
        style.copy(
            lineHeight = style.fontSize * 1.85f,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.None),
        )
    } else style
    val rubyStyle = remember(style) { TextStyle(fontSize = style.fontSize * 0.5f, fontWeight = FontWeight.Medium) }
    val showBoundaries = prefs.showBoundaries
    val showRuby = ruby && prefs.showPronunciation
    val toneColors = prefs.toneColors

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
                for (segment in passage.segments) {
                    if (!segment.matched || segment.length <= 0 || segment.end > text.length) continue
                    if (showBoundaries) {
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
                    }
                    shade = 1 - shade
                    if (showRuby && !segment.reading.isEmpty) drawRuby(segment, text, layout, measurer, rubyStyle, toneColors, palette)
                }
            }
            .pointerInput(text) {
                detectTapGestures { position ->
                    val layout = layoutResult ?: return@detectTapGestures
                    val raw = layout.getOffsetForPosition(position)
                    // getOffsetForPosition returns a caret position; pick the character under the point.
                    val candidates = listOf(raw, raw - 1).filter { it in text.indices }
                    val hit = candidates.firstOrNull { layout.getBoundingBox(it).contains(position) } ?: candidates.firstOrNull()
                        ?: return@detectTapGestures
                    val offset = passage.normaliseOffset(hit)
                    val c = text[offset]
                    if (c == ' ' || c == '\t' || c == '\r' || c == '\n') return@detectTapGestures
                    onTap(offset)
                }
            },
        onTextLayout = { layoutResult = it },
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRuby(
    segment: PassageSegment,
    text: String,
    layout: TextLayoutResult,
    measurer: androidx.compose.ui.text.TextMeasurer,
    rubyStyle: TextStyle,
    toneColors: Boolean,
    palette: DictionaryPalette,
) {
    val syllables = segment.reading.display.split(' ').filter { it.isNotEmpty() }
    val tones = segment.reading.tones
    if (segment.toneColourable && syllables.isNotEmpty()) {
        // One syllable centred over each character.
        var i = segment.start
        var n = 0
        while (i < segment.end && n < syllables.size) {
            val box = layout.getBoundingBox(i)
            val tone = tones.getOrElse(n) { 0 }
            val color = if (toneColors && tone > 0) palette.tone(tone) else palette.muted
            val measured = measurer.measure(syllables[n], rubyStyle.copy(color = color), softWrap = false, maxLines = 1)
            // Slightly narrower than the glyph cell, so neighbouring readings never touch.
            val available = box.width - 1.5.dp.toPx()
            val scale = if (measured.size.width > available) (available / measured.size.width).coerceAtLeast(0.6f) else 1f
            val w = measured.size.width * scale
            val x = box.center.x - w / 2f
            val y = box.top + 1.dp.toPx()
            withTransform({ translate(x, y); scale(scale, scale, pivot = Offset.Zero) }) { drawText(measured) }
            i += codePointLength(text, i)
            n++
        }
    } else {
        // Whole reading centred over the segment's glyphs on the line where it starts.
        val firstBox = layout.getBoundingBox(segment.start)
        val line = layout.getLineForOffset(segment.start)
        var right = firstBox.right
        var i = segment.start
        while (i < segment.end && layout.getLineForOffset(i) == line) {
            right = layout.getBoundingBox(i).right
            i += codePointLength(text, i)
        }
        val width = (right - firstBox.left).coerceAtLeast(1f)
        val measured = measurer.measure(
            segment.reading.display, rubyStyle.copy(color = palette.muted), softWrap = false, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = width.toInt().coerceAtLeast(1)),
        )
        val x = firstBox.left + (width - measured.size.width) / 2f
        val rubyTop = firstBox.top + 1.dp.toPx()
        withTransform({ translate(x, rubyTop) }) { drawText(measured) }
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
        addStyle(SpanStyle(color = palette.fg, letterSpacing = if (ruby) 0.3.em else androidx.compose.ui.unit.TextUnit.Unspecified), start, end)
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
