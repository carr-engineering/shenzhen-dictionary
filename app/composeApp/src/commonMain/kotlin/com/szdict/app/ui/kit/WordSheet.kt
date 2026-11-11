package com.szdict.app.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.szdict.app.AppSession
import com.szdict.app.WordSheetRequest
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.SpanCandidates
import com.szdict.core.api.SpanKind
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch

/**
 * Non-modal word-candidate sheet (TXT-040…TXT-047, GLB-057). Opens at a 256 dp peek, drags to full
 * height, dismisses by drag-down, close or back. It never closes and reopens when the selection
 * moves; previous rows stay (dimmed) while a new lookup runs (TXT-042).
 *
 * [onVisibleHeight] reports the currently visible height so the screen behind pads by exactly it (TXT-044).
 */
@Composable
fun WordSheet(
    session: AppSession,
    request: WordSheetRequest,
    maxHeight: Dp,
    onVisibleHeight: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDictionaryPalette.current
    val strings = appStrings()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val peekPx = with(density) { 256.dp.toPx() }
    val maxPx = with(density) { maxHeight.toPx() }.coerceAtLeast(peekPx)
    val height = remember { Animatable(0f) }
    LaunchedEffect(Unit) { height.animateTo(peekPx) }
    LaunchedEffect(height.value) { onVisibleHeight(with(density) { height.value.toDp() }) }

    var shown by remember { mutableStateOf<SpanCandidates?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showBar by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val script = session.settings.value.script(request.mode)
    LaunchedEffect(request.passage, request.offset, request.mode, script, request.fixedCards) {
        if (request.fixedCards != null) { loading = false; return@LaunchedEffect }
        loading = true
        error = null
        val barJob = launch { delay(150); showBar = true }  // GLB-033
        try {
            val result = session.dictionary(request.mode).spansAt(request.passage, request.offset, script)
            shown = result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = strings.text("Unable to load word choices") + (e.message?.let { ": $it" } ?: "")
        } finally {
            barJob.cancel()
            loading = false
            if (showBar) { delay(300); showBar = false }
        }
    }

    fun dragBy(delta: Float) {
        scope.launch { height.snapTo((height.value - delta).coerceIn(0f, maxPx)) }
    }
    fun settle(velocity: Float) {
        val v = height.value
        val target = when {
            velocity > 900f -> if (v > peekPx * 1.2f) peekPx else 0f
            velocity < -900f -> maxPx
            v < peekPx * 0.55f -> 0f
            v < (peekPx + maxPx) / 2f -> peekPx
            else -> maxPx
        }
        scope.launch {
            height.animateTo(target)
            if (target == 0f) session.closeWordSheet()
        }
    }
    val dragState = rememberDraggableState { delta -> dragBy(delta) }
    // The row list drags the sheet too: scrolling up grows it to full height before the rows move,
    // and pulling down with the rows at their top shrinks it (TXT-040, TXT-045).
    val listConnection = remember(maxPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y >= 0f || height.value >= maxPx) return Offset.Zero
                val used = available.y.coerceAtLeast(height.value - maxPx)
                dragBy(used)
                return Offset(0f, used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                dragBy(available.y)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (height.value >= maxPx || height.value == peekPx) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }
    Surface(
        modifier = modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .height(with(density) { height.value.toDp() }),
        color = palette.panel,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        border = BorderStroke(1.dp, palette.border),
        shadowElevation = 6.dp,
    ) {
        Column {
            Column(
                Modifier.fillMaxWidth().draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity -> settle(velocity) },
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.padding(top = 8.dp).size(32.dp, 4.dp).background(palette.border, RoundedCornerShape(2.dp)))
                val current = shown
                val character = current?.character ?: ""
                val title = request.fixedTitle ?: when (current?.kind) {
                    SpanKind.WORDS_AT -> strings.text("Words at 「{0}」", character)
                    SpanKind.APPEARS_IN -> strings.text("「{0}」 appears in", character)
                    SpanKind.NONE -> strings.text("At 「{0}」", character)
                    null -> ""
                }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = palette.fg, modifier = Modifier.weight(1f))
                    if (request.fixedCards == null) {
                        val prev = current?.previousCovered ?: request.passage.previousCovered(request.offset)
                        val next = current?.nextCovered ?: request.passage.nextCovered(request.offset)
                        IconButton(onClick = { prev?.let(session::moveWordSheet) }, enabled = prev != null) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, strings.text("Previous character"))
                        }
                        IconButton(onClick = { next?.let(session::moveWordSheet) }, enabled = next != null) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, strings.text("Next character"))
                        }
                    }
                    IconButton(onClick = { session.closeWordSheet() }) { Icon(Icons.Filled.Close, strings.text("Close word options")) }
                }
                if (showBar) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = palette.accent, trackColor = palette.border)
                else Spacer(Modifier.height(2.dp))
            }
            val cards = request.fixedCards ?: shown?.candidates?.map { it.card }
            val playing by session.playing.collectAsStateCompat()
            LazyColumn(
                Modifier.fillMaxWidth().nestedScroll(listConnection).alpha(if (loading && request.fixedCards == null) 0.55f else 1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                error?.let { message -> item { Text(message, color = palette.muted, modifier = Modifier.padding(12.dp)) } }
                if (cards != null && cards.isEmpty() && !loading && error == null) {
                    item { Text(strings.text("No dictionary entry"), color = palette.muted, modifier = Modifier.padding(12.dp)) }
                }
                items(cards.orEmpty(), key = { it.id }) { card ->
                    EntryCardView(
                        card = card,
                        onClick = { if (!loading) session.openEntry(card.id, request.mode) },
                        compact = true,
                        audio = CardAudio(playing == card.id) { session.play(card.id, card.audio, request.mode) },
                    )
                }
                // TXT-046: the bottom safe inset is a trailing item, not sheet padding.
                item { Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp)) }
            }
        }
    }
}
