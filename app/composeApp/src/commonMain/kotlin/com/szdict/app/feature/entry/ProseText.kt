package com.szdict.app.feature.entry

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.szdict.app.ui.kit.LocalDisplayPrefs
import com.szdict.app.ui.theme.DictionaryPalette
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.Segment

// Annotation tag Compose Foundation uses for inline content (`appendInlineContent`).
private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

/** Turns a [Prose] plan into styled text: bold numbers, link ranges, hidden readings, tag slots. */
internal fun proseAnnotatedString(
    prose: Prose,
    palette: DictionaryPalette,
    showReadings: Boolean,
    onRef: ((Segment.Ref) -> Unit)?,
): AnnotatedString = buildAnnotatedString {
    append(prose.text)
    val linkStyle = SpanStyle(color = palette.accent, textDecoration = TextDecoration.Underline)
    prose.spans.forEachIndexed { index, span ->
        when (span.kind) {
            ProseSpanKind.NUMBER -> addStyle(SpanStyle(color = palette.fg, fontWeight = FontWeight.Bold), span.start, span.end)
            ProseSpanKind.LINK -> {
                val ref = span.ref
                if (ref != null && onRef != null) {
                    addLink(
                        LinkAnnotation.Clickable(
                            tag = "definition-reference-$index",
                            styles = TextLinkStyles(style = linkStyle),
                            linkInteractionListener = { onRef(ref) },
                        ),
                        span.start, span.end,
                    )
                } else addStyle(linkStyle, span.start, span.end)
            }
            // GLB-120: not drawn, width kept, so lines wrap identically in both states.
            ProseSpanKind.READING -> if (!showReadings) addStyle(SpanStyle(color = Color.Transparent), span.start, span.end)
            ProseSpanKind.TAG -> addStringAnnotation(INLINE_CONTENT_TAG, "tag-$index", span.start, span.end)
        }
    }
}

/**
 * A definition paragraph (ENT-051…053, ENT-058): tap targets in accent with underline, long press
 * selects text without following the link (GLB-056), tags drawn as small outlined labels.
 */
@Composable
internal fun ProseText(
    prose: Prose,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = LocalDictionaryPalette.current.fg,
    onRef: ((Segment.Ref) -> Unit)? = null,
    selectable: Boolean = true,
) {
    val palette = LocalDictionaryPalette.current
    // Readings inside definitions are always shown: the pronunciation toggle is about passages.
    val showReadings = true
    val currentOnRef = rememberUpdatedState(onRef)
    val hasLinks = onRef != null
    val text = remember(prose, palette, showReadings, hasLinks) {
        proseAnnotatedString(prose, palette, showReadings, if (hasLinks) { ref -> currentOnRef.value?.invoke(ref) } else null)
    }
    val tagStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val inline = remember(prose, tagStyle, density, palette) {
        val map = HashMap<String, InlineTextContent>()
        prose.spans.forEachIndexed { index, span ->
            if (span.kind != ProseSpanKind.TAG) return@forEachIndexed
            val label = span.label.orEmpty()
            val widthPx = measurer.measure(label, tagStyle, maxLines = 1).size.width + with(density) { 12.dp.toPx() }
            val width = with(density) { widthPx.toSp() }
            map["tag-$index"] = InlineTextContent(Placeholder(width, 20.sp, PlaceholderVerticalAlign.TextCenter)) {
                Box(Modifier.fillMaxSize().border(1.dp, palette.border, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                    Text(label, style = tagStyle, color = palette.muted, maxLines = 1, softWrap = false)
                }
            }
        }
        map
    }
    val hasTags = inline.isNotEmpty()
    // Screen readers get the tag labels in place of their slots and no hidden readings (GLB-122).
    val description = remember(prose, showReadings, hasTags) {
        if (showReadings && !hasTags) null else {
            var plain = if (showReadings) prose.text else prose.textWithoutReadings()
            prose.spans.filter { it.kind == ProseSpanKind.TAG }.forEach { plain = plain.replaceFirst(TAG_PLACEHOLDER.toString(), "${it.label},") }
            plain
        }
    }
    val textModifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
    if (selectable) {
        SelectionContainer(modifier) { Text(text, textModifier, style = style, color = color, inlineContent = inline) }
    } else {
        Text(text, modifier.then(textModifier), style = style, color = color, inlineContent = inline)
    }
}
