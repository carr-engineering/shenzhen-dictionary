package com.szdict.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.szdict.app.AppSession
import com.szdict.app.i18n.AppStrings
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.SpanCandidates
import com.szdict.core.api.SpanKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal data class WordLookup(val passage: AnalysedPassage, val offset: Int)

internal class WordHoverState {
    var lookup by mutableStateOf<WordLookup?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    private var owner: Any? = null

    fun show(target: Any, word: WordLookup, position: Offset) {
        owner = target
        lookup = word
        pointer = position
    }

    fun clear(target: Any) {
        if (owner !== target) return
        owner = null
        lookup = null
    }

    fun clear() { owner?.let(::clear) }
}

internal val LocalWordHover = staticCompositionLocalOf<WordHoverState?> { null }

/** Observe mouse movement without intercepting clicks, scrolling, or other gestures. */
@Composable
internal fun Modifier.wordHover(key: Any?, hitTest: (Offset) -> WordLookup?): Modifier {
    val state = LocalWordHover.current ?: return this
    val target = remember(key) { Any() }
    val lookupAt by rememberUpdatedState(hitTest)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    DisposableEffect(state, target) {
        onDispose { state.clear(target) }
    }
    return onGloballyPositioned { coordinates = it }.pointerInput(state, target) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val mouse = event.changes.firstOrNull { it.type == PointerType.Mouse } ?: continue
                when (event.type) {
                    PointerEventType.Exit, PointerEventType.Scroll, PointerEventType.Press -> state.clear(target)
                    PointerEventType.Enter, PointerEventType.Move -> {
                        val coords = coordinates?.takeIf { it.isAttached }
                        val inside = Rect(Offset.Zero, Size(size.width.toFloat(), size.height.toFloat()))
                            .contains(mouse.position)
                        val word = if (coords != null && inside && !mouse.pressed) lookupAt(mouse.position) else null
                        if (word == null || coords == null) state.clear(target)
                        else state.show(target, word, coords.localToRoot(mouse.position))
                    }
                }
            }
        }
    }
}

internal fun wordChoicesTitle(strings: AppStrings, choices: SpanCandidates): String = when (choices.kind) {
    SpanKind.WORDS_AT -> strings.text("Words at 「{0}」", choices.character)
    SpanKind.APPEARS_IN -> strings.text("「{0}」 appears in", choices.character)
    SpanKind.NONE -> strings.text("At 「{0}」", choices.character)
}

/** A pointer-transparent preview rendered in the same Compose window as the source glyph. */
@Composable
internal fun WordHoverOverlay(session: AppSession, hover: WordHoverState, modifier: Modifier = Modifier) {
    if (session.wordSheet != null) return
    val lookup = hover.lookup ?: return
    val mode = lookup.passage.mode
    val script = session.settings.value.script(mode)
    var choices by remember(lookup, script) { mutableStateOf<SpanCandidates?>(null) }
    LaunchedEffect(lookup, script) {
        delay(65)
        try {
            choices = session.dictionary(mode).spansAt(lookup.passage, lookup.offset, script)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Moving again or clicking the glyph can retry the lookup.
        }
    }
    val shown = choices ?: return
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val window = constraints
        val margin = with(density) { 8.dp.roundToPx() }
        val gap = with(density) { 16.dp.roundToPx() }
        var panel by remember { mutableStateOf(IntSize.Zero) }
        val visibleRows = ((maxHeight - 100.dp) / 48.dp).toInt().coerceAtLeast(1)
        Column(
            Modifier.absoluteOffset {
                val cursor = hover.pointer
                val x = cursor.x.roundToInt()
                val y = cursor.y.roundToInt()
                val right = x + gap
                val below = y + gap
                val left = if (right + panel.width <= window.maxWidth - margin) right else x - gap - panel.width
                val top = if (below + panel.height <= window.maxHeight - margin) below else y - gap - panel.height
                IntOffset(
                    left.coerceIn(margin, (window.maxWidth - margin - panel.width).coerceAtLeast(margin)),
                    top.coerceIn(margin, (window.maxHeight - margin - panel.height).coerceAtLeast(margin)),
                )
            }
                .width(minOf(310.dp, (maxWidth - 16.dp).coerceAtLeast(0.dp)))
                .heightIn(max = (maxHeight - 16.dp).coerceAtLeast(0.dp))
                .onSizeChanged { panel = it }
                .shadow(6.dp, shape)
                .clip(shape)
                .background(palette.panel)
                .border(1.dp, palette.border, shape)
                .clearAndSetSemantics { }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(wordChoicesTitle(strings, shown), style = MaterialTheme.typography.titleSmall, color = palette.fg)
            val candidates = shown.candidates
            for (candidate in candidates.take(visibleRows)) {
                val card = candidate.card
                val reading = card.mandarinRow?.reading ?: card.reading
                val definitions = card.mandarinRow?.definitions ?: card.definitions
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(card.headword, style = MaterialTheme.typography.titleMedium, color = palette.fg, maxLines = 1)
                        if (!reading.isEmpty) {
                            Text(reading.display, style = MaterialTheme.typography.bodyMedium, color = palette.muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    definitions.firstOrNull()?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = palette.muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (candidates.isEmpty()) {
                Text(strings.text("No dictionary entry"), style = MaterialTheme.typography.bodySmall, color = palette.muted)
            } else if (candidates.size > visibleRows) {
                Text(strings.text("Click to see all {0} results", candidates.size),
                    style = MaterialTheme.typography.labelMedium, color = palette.muted)
            }
        }
    }
}
