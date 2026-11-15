package com.szdict.app.feature.handwriting

import com.szdict.app.ui.kit.WithTooltip
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.szdict.app.AppSession
import com.szdict.app.feature.ocr.ModelDownload
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.recognition.InkPoint

/**
 * The handwriting sheet (HW-010…HW-016): candidate row with the close button, the writing pad, and
 * `Undo stroke` / `Clear` / `Delete`. Non-modal, no drag handle. The signature is the contract with
 * the app shell, which shows it while `session.handwritingVisible` and pads content by the height
 * reported through [onVisibleHeight] (HW-021).
 */
@Composable
fun HandwritingSheet(session: AppSession, onVisibleHeight: (Dp) -> Unit, modifier: Modifier = Modifier) {
    val state = remember(session) { HandwritingState.of(session) }
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val density = LocalDensity.current
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // HW-021: the soft keyboard is never shown together with the sheet, even one left over from before.
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(keyboard) { keyboard?.hide() }
    LaunchedEffect(state) { state.prepare() }

    Surface(
        modifier = modifier
            .widthIn(max = 720.dp)   // same cap as the word sheet (GLB-067)
            .fillMaxWidth()
            .onSizeChanged { onVisibleHeight(with(density) { it.height.toDp() }) },
        color = palette.panel,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        border = BorderStroke(1.dp, palette.border),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(bottom = navigationInset)) {
            // Switch to keyboard, candidate row, close.
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                WithTooltip(strings.text("Switch to keyboard")) {
                    IconButton(onClick = session::switchHandwritingToKeyboard) {
                        Icon(Icons.Rounded.Keyboard, contentDescription = strings.text("Switch to keyboard"), tint = palette.muted)
                    }
                }
                Spacer(Modifier.width(4.dp))
                val candidates = state.candidates
                val download = state.modelDownload
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    when {
                        // ARCH-093 (Web): the model is fetched when the sheet first opens.
                        download is ModelDownload.InProgress && candidates.isEmpty() -> Text(
                            download.fraction?.let { strings.text("Downloading handwriting… {0}%", (it.coerceIn(0f, 1f) * 100).toInt()) }
                                ?: strings.text("Loading handwriting…"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.muted,
                        )
                        state.failed || (download is ModelDownload.Failed && candidates.isEmpty()) -> Text(
                            strings.text("Handwriting is unavailable."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.muted,
                        )
                        candidates.isEmpty() && !state.isEmpty && !state.busy -> Text(
                            strings.text("No matching character"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.muted,
                        )
                        // Each candidate list gets a row of its own, starting at its first candidate:
                        // a row updated in place kept showing the previous chips on Compose for Web.
                        else -> key(candidates) { LazyRow(state = rememberLazyListState(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            itemsIndexed(candidates) { index, candidate ->
                                val first = index == 0
                                val label = candidate + ", " + strings.text("Candidate {0} of {1}", index + 1, candidates.size)
                                Surface(
                                    onClick = { state.commit(candidate) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (first) palette.accent else palette.bgSoft,
                                    contentColor = if (first) palette.onAccent else palette.fg,
                                    border = if (first) null else BorderStroke(1.dp, palette.border),
                                    modifier = Modifier.height(42.dp).widthIn(min = 42.dp).semantics { contentDescription = label },
                                ) {
                                    Box(Modifier.padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            candidate,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = if (first) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        } }
                    }
                }
                WithTooltip(strings.text("Close handwriting")) {
                    IconButton(onClick = { session.handwritingVisible = false }) {
                        Icon(Icons.Rounded.Close, contentDescription = strings.text("Close handwriting"), tint = palette.muted)
                    }
                }
            }
            if (state.busy || state.modelDownload is ModelDownload.InProgress) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = palette.accent, trackColor = Color.Transparent)
            else Spacer(Modifier.height(2.dp))

            WritingPad(state, Modifier.padding(horizontal = 12.dp).fillMaxWidth())

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row {
                    PadAction(Icons.AutoMirrored.Rounded.Undo, strings.text("Undo"), strings.text("Undo stroke"), enabled = state.strokes.isNotEmpty(), onClick = state::undo)
                    PadAction(Icons.Rounded.DeleteSweep, strings.text("Clear"), strings.text("Clear writing"), enabled = !state.isEmpty, onClick = state::clear)
                }
                PadAction(Icons.AutoMirrored.Rounded.Backspace, strings.text("Delete"), strings.text("Delete"), enabled = session.handwritingSink != null, onClick = state::delete)
            }
        }
    }
}

@Composable
private fun PadAction(icon: ImageVector, text: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalDictionaryPalette.current
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = palette.accent, disabledContentColor = palette.muted.copy(alpha = 0.45f)),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** The framed pad (HW-011, HW-012): finger, stylus and mouse ink in `fg`, with a faint centre guide. */
@Composable
private fun WritingPad(state: HandwritingState, modifier: Modifier = Modifier) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val label = strings.text("Handwriting area")
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(modifier) {
        // Roomy on a phone in portrait, lower when the window is short (landscape) or very wide.
        val height = (maxWidth * 0.62f).coerceIn(150.dp, 250.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(shape)
                .background(palette.bg)
                .border(1.dp, palette.border, shape)
                .semantics { contentDescription = label }
                .onSizeChanged { state.setArea(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(state) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        state.beginStroke(down.position.x, down.position.y, down.uptimeMillis)
                        var finished = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) break
                            // Intermediate samples keep fast strokes smooth.
                            for (h in change.historical) state.extendStroke(h.position.x, h.position.y, h.uptimeMillis)
                            state.extendStroke(change.position.x, change.position.y, change.uptimeMillis)
                            change.consume()
                            if (!change.pressed) {
                                finished = true
                                break
                            }
                        }
                        if (finished) state.endStroke() else state.cancelStroke()
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // Faint centre guide.
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
                val guide = palette.border
                drawLine(guide, Offset(size.width / 2f, 10.dp.toPx()), Offset(size.width / 2f, size.height - 10.dp.toPx()), 1.dp.toPx(), pathEffect = dash)
                drawLine(guide, Offset(10.dp.toPx(), size.height / 2f), Offset(size.width - 10.dp.toPx(), size.height / 2f), 1.dp.toPx(), pathEffect = dash)
                val width = 4.5.dp.toPx()
                for (stroke in state.strokes) drawInk(stroke.points, palette.fg, width)
                if (state.live.isNotEmpty()) drawInk(state.live, palette.fg, width)
            }
        }
    }
}

/** One stroke as a smooth curve through the midpoints of consecutive samples. */
private fun DrawScope.drawInk(points: List<InkPoint>, color: Color, width: Float) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color, width / 2f, Offset(points[0].x, points[0].y))
        return
    }
    val path = Path()
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) {
        val previous = points[i - 1]
        val current = points[i]
        path.quadraticTo(previous.x, previous.y, (previous.x + current.x) / 2f, (previous.y + current.y) / 2f)
    }
    path.lineTo(points.last().x, points.last().y)
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
