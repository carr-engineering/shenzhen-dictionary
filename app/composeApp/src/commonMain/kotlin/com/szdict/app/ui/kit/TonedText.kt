package com.szdict.app.ui.kit

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.szdict.app.ui.theme.DictionaryPalette
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.Reading

/** Headword text, tone-coloured per character only when the counts align (GLB-066). */
@Composable
fun TonedHanziText(
    text: String,
    reading: Reading,
    toneColors: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val palette = LocalDictionaryPalette.current
    val annotated = remember(text, reading, toneColors, palette) { tonedHanzi(text, reading, toneColors, palette) }
    Text(annotated, modifier, style = style, fontWeight = fontWeight, color = color, maxLines = maxLines, overflow = overflow)
}

/**
 * Reading line. When [visible] is false the text keeps its measured space but is not drawn and is
 * hidden from accessibility (GLB-120, GLB-122).
 */
@Composable
fun TonedReadingText(
    reading: Reading,
    toneColors: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val palette = LocalDictionaryPalette.current
    val annotated = remember(reading, toneColors, palette) { tonedReading(reading, toneColors, palette) }
    Text(
        annotated,
        modifier.then(if (visible) Modifier else Modifier.alpha(0f).hiddenFromAccessibility()),
        style = style, color = color, fontWeight = fontWeight, maxLines = maxLines, overflow = overflow,
    )
}

fun tonedHanzi(text: String, reading: Reading, toneColors: Boolean, palette: DictionaryPalette): AnnotatedString =
    buildAnnotatedString {
        val chars = codePointStrings(text)
        val tones = reading.tones
        if (!toneColors || tones.isEmpty() || chars.size != tones.size) {
            append(text)
            return@buildAnnotatedString
        }
        chars.forEachIndexed { index, char ->
            val tone = tones[index]
            if (tone <= 0) append(char) else withStyle(SpanStyle(color = palette.tone(tone))) { append(char) }
        }
    }

fun tonedReading(reading: Reading, toneColors: Boolean, palette: DictionaryPalette): AnnotatedString =
    buildAnnotatedString {
        val parts = reading.display.split(' ').filter { it.isNotBlank() }
        val tones = reading.tones
        // A syllable with attached punctuation ("zuò,") also consumes the punctuation's tone-0 slot.
        val attached = parts.count { it.isNotEmpty() && isReadingPunctuation(it.last()) && it.length > 1 }
        if (!toneColors || parts.size + attached != tones.size) {
            append(reading.display)
            return@buildAnnotatedString
        }
        var n = 0
        parts.forEachIndexed { index, syllable ->
            if (index > 0) append(" ")
            val tone = tones[n++]
            if (tone <= 0) append(syllable) else withStyle(SpanStyle(color = palette.tone(tone))) { append(syllable) }
            if (syllable.length > 1 && isReadingPunctuation(syllable.last())) n++
        }
    }

private fun isReadingPunctuation(c: Char): Boolean = c == ',' || c == '，' || c == '·' || c == ';' || c == '、'

fun codePointStrings(text: String): List<String> {
    val out = ArrayList<String>(text.length)
    var index = 0
    while (index < text.length) {
        val end = if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) index + 2 else index + 1
        out += text.substring(index, end)
        index = end
    }
    return out
}

fun codePointLength(text: String, index: Int): Int =
    if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1
