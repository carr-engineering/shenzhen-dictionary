package com.szdict.app.ui.kit

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.szdict.app.core.numberedToTones
import com.szdict.app.engine.EntryDetailData
import com.szdict.app.engine.ScriptMode
import com.szdict.app.model.DictionaryEntry
import com.szdict.app.ui.theme.DictionaryPalette
import com.szdict.app.ui.theme.LocalDictionaryPalette

@Composable
internal fun TonedHanziText(
    text: String,
    pinyinNumbered: String,
    toneColors: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    Text(
        text = tonedHanziAnnotatedString(text, pinyinNumbered, toneColors, LocalDictionaryPalette.current),
        modifier = modifier,
        style = style,
        fontWeight = fontWeight,
        color = color,
        maxLines = maxLines,
        overflow = overflow
    )
}

@Composable
internal fun TonedPinyinText(
    text: String,
    pinyinNumbered: String,
    toneColors: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    Text(
        text = tonedPinyinAnnotatedString(text, pinyinNumbered, toneColors, LocalDictionaryPalette.current),
        modifier = modifier,
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = overflow
    )
}

internal fun tonedHanziAnnotatedString(
    text: String,
    pinyinNumbered: String,
    toneColors: Boolean,
    palette: DictionaryPalette
) = buildAnnotatedString {
    val chars = codePointStrings(text)
    val tones = numberedToTones(pinyinNumbered)
    if (!toneColors || chars.size != tones.size) {
        append(text)
        return@buildAnnotatedString
    }
    chars.forEachIndexed { index, char ->
        withStyle(SpanStyle(color = palette.tone(tones[index]))) {
            append(char)
        }
    }
}

internal fun tonedPinyinAnnotatedString(
    text: String,
    pinyinNumbered: String,
    toneColors: Boolean,
    palette: DictionaryPalette
) = buildAnnotatedString {
    val syllables = text.split(Regex("\\s+")).filter { it.isNotBlank() }
    val tones = numberedToTones(pinyinNumbered)
    if (!toneColors || syllables.size != tones.size) {
        append(text)
        return@buildAnnotatedString
    }
    syllables.forEachIndexed { index, syllable ->
        if (index > 0) append(" ")
        withStyle(SpanStyle(color = palette.tone(tones[index]))) {
            append(syllable)
        }
    }
}

internal fun codePointStrings(text: String): List<String> {
    val out = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        out += String(intArrayOf(codePoint), 0, 1)
        index += Character.charCount(codePoint)
    }
    return out
}

internal fun headword(entry: DictionaryEntry, script: ScriptMode): String {
    return if (script == ScriptMode.Traditional) entry.traditional else entry.simplified
}

internal fun otherScript(entry: DictionaryEntry, script: ScriptMode): String? {
    return when (script) {
        ScriptMode.Simplified -> entry.traditional.takeIf { it != entry.simplified }
        ScriptMode.Traditional -> entry.simplified.takeIf { it != entry.traditional }
    }
}

internal fun variantForms(data: EntryDetailData, script: ScriptMode): List<String>? {
    val members = data.groupMembers ?: return null
    return members
        .mapNotNull(data.entries::get)
        .map { otherScriptForm(it, script) }
        .distinct()
        .takeIf { it.isNotEmpty() }
}

internal fun otherScriptForm(entry: DictionaryEntry, script: ScriptMode): String {
    return if (script == ScriptMode.Traditional) entry.simplified else entry.traditional
}

internal fun isHanzi(char: Char): Boolean {
    return char in '一'..'鿿' ||
        char in '㐀'..'䶿' ||
        char in '豈'..'﫿' ||
        char == '〇'
}
