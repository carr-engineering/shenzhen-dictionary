package com.szdict.app.feature.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.szdict.app.ui.kit.scrollUnderControls
import kotlin.math.roundToInt

/** Clear space between the query and the floating controls. */
private val ControlGap = 8.dp

/** The query scrolls behind the leading and trailing controls while the caret stays between them. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    textColor: Color,
    placeholderColor: Color,
    cursorColor: Color,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
    /** Wraps the text field (the input-method gate); the buttons stay outside it. */
    fieldWrapper: @Composable (@Composable () -> Unit) -> Unit = { it() },
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    var leadingWidth by remember { mutableIntStateOf(0) }
    var trailingWidth by remember { mutableIntStateOf(0) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val gapPx = with(density) { ControlGap.toPx() }
    val startPx = leadingWidth + gapPx
    val endPx = trailingWidth + gapPx
    val startPadding: Dp = with(density) { startPx.toDp() }
    val endPadding: Dp = with(density) { endPx.toDp() }

    // The caret window for any bring-into-view request the field makes itself (focus, IME): the same
    // window as the padding, so a request never parks the caret under a button or against the edge.
    val bringIntoView = remember(startPx, endPx) { CaretWindow(startPx, endPx) }

    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        val viewport = constraints.maxWidth
        val viewportDp = maxWidth

        // Follow the moving end of the selection, as the field's own scroller would: the end that
        // changed last (a handle drag on the start follows the start).
        val previous = remember { arrayOf(value.selection) }
        val follow = if (value.selection.start != previous[0].start) value.selection.start else value.selection.end
        previous[0] = value.selection
        LaunchedEffect(follow, layout, viewport, startPx, endPx) {
            val l = layout ?: return@LaunchedEffect
            val caret = startPx + l.getHorizontalPosition(follow.coerceIn(0, l.layoutInput.text.length), true)
            val lo = scroll.value + startPx
            val hi = scroll.value + viewport - endPx
            val target = when {
                // Past the start: reveal a third of the window before the caret (all of it near the start).
                caret < lo -> caret - startPx - (viewport - startPx - endPx) / 3f
                caret > hi -> caret - (viewport - endPx)
                else -> return@LaunchedEffect
            }
            scroll.scrollTo(target.roundToInt().coerceIn(0, scroll.maxValue))
        }

        Box(
            Modifier.fillMaxWidth().scrollUnderControls(startPadding, if (trailingWidth > 0) endPadding else 0.dp),
        ) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
                Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
                    fieldWrapper {
                        val style = LocalTextStyle.current.merge(color = textColor)
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier
                                .widthIn(min = viewportDp)
                                .heightIn(min = 56.dp)
                                .focusRequester(focusRequester)
                                .onFocusChanged { if (it.isFocused) onFocused() },
                            textStyle = style,
                            keyboardOptions = keyboardOptions,
                            keyboardActions = keyboardActions,
                            singleLine = true,
                            onTextLayout = { layout = it },
                            interactionSource = interactionSource,
                            cursorBrush = SolidColor(cursorColor),
                            decorationBox = { inner ->
                                // The insets are inside the field (and inside the scroll container):
                                // taps on them still place the caret, and scrolled text reaches the edges.
                                Box(
                                    Modifier.padding(start = startPadding, end = endPadding),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    if (value.text.isEmpty()) {
                                        Text(
                                            placeholder,
                                            style = style,
                                            color = placeholderColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = (viewportDp - startPadding - endPadding).coerceAtLeast(0.dp)),
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.CenterStart).onSizeChanged { leadingWidth = it.width },
            verticalAlignment = Alignment.CenterVertically,
        ) { leading() }
        Row(
            Modifier.align(Alignment.CenterEnd).onSizeChanged { trailingWidth = it.width },
            verticalAlignment = Alignment.CenterVertically,
        ) { trailing() }
    }
}

/** Keeps a bring-into-view target between [start] px from the viewport's start and [end] px from its end. */
@OptIn(ExperimentalFoundationApi::class)
private class CaretWindow(private val start: Float, private val end: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val lo = start
        val hi = containerSize - end
        if (hi - lo < size) {
            // Larger than the window (the whole field): leave it if any of it shows.
            val visible = offset < containerSize && offset + size > 0f
            return if (visible) 0f else offset - lo
        }
        return when {
            offset < lo -> offset - lo
            offset + size > hi -> offset + size - hi
            else -> 0f
        }
    }
}
