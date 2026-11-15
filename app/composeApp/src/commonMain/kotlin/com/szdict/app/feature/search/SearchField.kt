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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Where the text rests from the bar's start edge (the Material text field's content padding). */
private val StartInset = 16.dp

/** Clear space between the end of the text (or the caret) and the first trailing button. */
private val EndGap = 8.dp

/** Length of the fade from opaque text to the faint text beneath the trailing buttons. */
private val EndRamp = 16.dp

/** Opacity of the text where it passes beneath the trailing buttons. */
private const val UnderButtonsAlpha = 0.2f

/** Length and edge opacity of the fade at the start edge once text has scrolled off it. */
private val StartRamp = 12.dp
private const val StartEdgeAlpha = 0.3f

/**
 * The search field (SRCH-090): a single-line [BasicTextField] spanning the whole bar, with [trailing]
 * buttons floating over its end. The insets are padding inside the field's own scroll container, not a
 * margin around it: a scrolled query travels to both edges of the bar (clipped by the bar's shape) and
 * passes beneath the buttons, where it fades to [UnderButtonsAlpha] so they stay legible. The buttons
 * sit above the text, so a tap there goes to the button. The caret is kept between the start inset and
 * just before the buttons, so the end of a long query is readable at full opacity left of them.
 */
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
    trailing: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    var trailingWidth by remember { mutableIntStateOf(0) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val startPx = with(density) { StartInset.toPx() }
    val endPx = trailingWidth + with(density) { EndGap.toPx() }
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
            Modifier
                .fillMaxWidth()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val w = size.width
                    if (w <= 0f) return@drawWithContent
                    val opaqueEnd = (w - endPx).coerceIn(0f, w)
                    val faint = (opaqueEnd + EndRamp.toPx()).coerceIn(opaqueEnd, w)
                    val edge = 1f - (1f - StartEdgeAlpha) * (scroll.value / startPx).coerceIn(0f, 1f)
                    val startRamp = StartRamp.toPx().coerceAtMost(opaqueEnd)
                    val mask = Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = edge),
                        startRamp / w to Color.Black,
                        opaqueEnd / w to Color.Black,
                        faint / w to Color.Black.copy(alpha = UnderButtonsAlpha),
                        1f to Color.Black.copy(alpha = UnderButtonsAlpha),
                        startX = 0f,
                        endX = w,
                    )
                    if (layoutDirection == LayoutDirection.Rtl) {
                        scale(scaleX = -1f, scaleY = 1f) { drawRect(mask, blendMode = BlendMode.DstIn) }
                    } else {
                        drawRect(mask, blendMode = BlendMode.DstIn)
                    }
                },
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
                                    Modifier.padding(start = StartInset, end = endPadding),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    if (value.text.isEmpty()) {
                                        Text(
                                            placeholder,
                                            style = style,
                                            color = placeholderColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = (viewportDp - StartInset - endPadding).coerceAtLeast(0.dp)),
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
