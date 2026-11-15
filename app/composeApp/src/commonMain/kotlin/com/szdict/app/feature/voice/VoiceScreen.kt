package com.szdict.app.feature.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedIconToggleButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.szdict.app.AppSession
import com.szdict.app.Destination
import com.szdict.app.feature.conversion.ScriptConversionButton
import com.szdict.app.i18n.appStrings
import com.szdict.app.integration.IncomingIntents
import com.szdict.app.ui.kit.LocalSheetInset
import com.szdict.app.ui.kit.PassageText
import com.szdict.app.ui.kit.ChipBarVerticalPadding
import com.szdict.app.ui.kit.PronunciationToggle
import com.szdict.app.ui.kit.TranslucentChipBar
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.Route
import com.szdict.core.api.SegmentationMode
import kotlinx.coroutines.launch

/** The Voice destination (VOICE-001, VOICE-002). The signature is the contract with the app shell. */
@Composable
fun VoiceScreen(session: AppSession, modifier: Modifier = Modifier) {
    val holder = remember(session) { VoiceStateHolder.of(session) }
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val settings by session.settings.collectAsState()

    // Opening loads the model and never starts listening (VOICE-003, VOICE-022). The screen is
    // recomposed from scratch on a dictionary-mode change; only a real switch of destination
    // leaves Voice (VOICE-061, VOICE-092).
    DisposableEffect(holder) {
        holder.enter()
        onDispose { if (session.destination != Destination.VOICE) holder.leave() }
    }
    // The launcher shortcut and the /voice link only select the destination (SET-050, GLB-110).
    val pendingRoute by IncomingIntents.pendingRoute.collectAsState()
    LaunchedEffect(pendingRoute) { IncomingIntents.consumeRouteIf { it == Route.Voice } }

    val listState = rememberLazyListState()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    // Measured heights of the floating layers: the top bar (with the status-bar inset) and the
    // bottom stack (status line + start button + insets). The transcript pads by these.
    var barHeightPx by remember { mutableIntStateOf(0) }
    var bottomStackPx by remember { mutableIntStateOf(0) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Below 600 dp the app's bottom bar sits under this screen and owns the navigation inset.
        val bottomInset = if (maxWidth < 600.dp) 0.dp else navInset
        // The controls stay reachable above the word sheet at its peek height (TXT-044).
        val sheetInset = LocalSheetInset.current.coerceAtMost(maxHeight * 0.5f)
        val idle = holder.phase == VoicePhase.IDLE
        val barHeight = with(density) { barHeightPx.toDp() }.coerceAtLeast(topInset)
        // While the pill floats under the bar the first lines start below it.
        val topPad = barHeight + if (idle) 8.dp else PILL_BAND
        // The last words rest above the start button; with it hidden, just above the edge/sheet.
        val bottomPad = maxOf(with(density) { bottomStackPx.toDp() }, maxOf(bottomInset, sheetInset) + 12.dp)
        Box(Modifier.widthIn(max = 900.dp).fillMaxSize().align(Alignment.TopCenter)) {
            val pack = holder.packState
            val permission = holder.permissionStatus
            val panelPad = Modifier.padding(top = barHeight, bottom = bottomPad)
            when {
                !holder.available -> Box(panelPad) { CenteredPanel { PanelText(strings.text(VoiceError.MODEL_UNAVAILABLE.label)) } }
                pack != VoicePackState.Present -> Box(panelPad) { ModelDownloadPanel(pack, onRequest = holder::requestModel) }
                permission == MicPermissionStatus.DENIED || permission == MicPermissionStatus.DENIED_PERMANENTLY ->
                    Box(panelPad) {
                        PermissionPanel(
                            permanent = permission == MicPermissionStatus.DENIED_PERMANENTLY,
                            onTryAgain = holder::startListening,
                            onOpenSettings = holder::openPermissionSettings,
                        )
                    }
                holder.isEmpty -> Box(panelPad) {
                    CenteredPanel {
                        PanelText(strings.text("Tap the microphone and speak Mandarin, Cantonese or English."))
                    }
                }
                else -> Transcript(holder, session, listState, topPad, bottomPad)
            }
            if (!idle) {
                RecordPill(
                    phase = holder.phase,
                    enabled = holder.canStart || holder.phase == VoicePhase.STARTING,
                    elapsedSeconds = holder.elapsedSeconds,
                    onClick = holder::toggleListening,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = barHeight + 8.dp),
                )
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { bottomStackPx = it.height }
                    .padding(bottom = maxOf(bottomInset, sheetInset) + 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val status = holder.status
                if (status != null) {
                    Text(
                        text = strings.text(status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (holder.error != null) MaterialTheme.colorScheme.error else palette.muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (idle) {
                    ListenButton(enabled = holder.canStart, onClick = holder::toggleListening)
                }
            }
        }
        // The translucent control bar spans the pane; the transcript scrolls beneath it.
        TranslucentChipBar(
            scrolledUnder = listState.canScrollBackward,
            modifier = Modifier.align(Alignment.TopCenter),
            onBarHeight = { barHeightPx = it },
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                VoiceControls(
                    session = session,
                    holder = holder,
                    modifier = Modifier
                        .widthIn(max = 900.dp)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = topInset + ChipBarVerticalPadding, bottom = ChipBarVerticalPadding),
                )
            }
        }
    }
}

/** Space the floating record pill takes under the bar: its 48 dp plus 8 dp above and below. */
private val PILL_BAND = 64.dp

/** Pronunciation toggle, `Copy`, `Clear`, and the script-conversion split button (VOICE-002). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceControls(
    session: AppSession,
    holder: VoiceStateHolder,
    modifier: Modifier = Modifier,
) {
    val strings = appStrings()
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        PronunciationToggle(session)
        HeaderAction(Icons.Rounded.ContentCopy, strings.text("Copy"), enabled = !holder.isEmpty, onClick = holder::copy)
        HeaderAction(Icons.Rounded.Clear, strings.text("Clear"), enabled = !holder.isEmpty, onClick = holder::clear)
        ScriptConversionButton(
            selection = holder.selection,
            enabled = !holder.isEmpty,
            onMainAction = holder::toggleConversion,
            onConvert = holder::convert,
        )
    }
}

/** The transcript (VOICE-030, VOICE-040): one line per utterance, virtualised, the live one last. */
@Composable
private fun Transcript(holder: VoiceStateHolder, session: AppSession, listState: LazyListState, topPad: Dp, bottomPad: Dp) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val scope = rememberCoroutineScope()
    val live = holder.live
    val lines = if (live == null) holder.utterances else holder.utterances + live
    val sheetOpen = session.wordSheet != null

    // VOICE-043: follow the live line unless the user scrolled away from the end or the sheet is open.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, forward) ->
            if (!forward) follow = true else if (scrolling) follow = false
        }
    }
    suspend fun scrollToEnd() {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.scrollToItem(last, Int.MAX_VALUE / 4)
    }
    // The bottom clearance changes as the start button hides and returns: keep the end in view.
    LaunchedEffect(bottomPad) { if (follow) scrollToEnd() }
    LaunchedEffect(lines.size, live?.display, holder.listening) {
        if (holder.listening && follow && !sheetOpen) scrollToEnd()
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPad, bottom = bottomPad),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(lines, key = { it.id }) { utterance ->
                // Before its first analysis a line is shown as plain, unmatched text (GLB-030).
                val passage = utterance.passage
                    ?: remember(utterance.display) { AnalysedPassage.empty(utterance.display, session.mode, SegmentationMode.HANZI, -1) }
                PassageText(
                    passage = passage,
                    selectedOffset = holder.selectedOffset(utterance),
                    onTap = { offset -> holder.onTap(utterance.id, offset) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        AnimatedVisibility(
            visible = listState.canScrollForward && holder.listening && (!follow || sheetOpen),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPad),
        ) {
            Button(
                onClick = {
                    follow = true
                    scope.launch { scrollToEnd() }
                },
                colors = ButtonDefaults.buttonColors(containerColor = palette.panel, contentColor = palette.accent),
                border = BorderStroke(1.dp, palette.border),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Rounded.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings.text("Jump to latest"))
            }
        }
    }
}

/** The 72 dp start control (VOICE-002, VOICE-003) in a 104 dp box that leaves room for the halo. Shown only while idle. */
@Composable
private fun ListenButton(enabled: Boolean, onClick: () -> Unit) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val label = strings.text("Start listening")
    val accent = palette.accent
    Box(modifier = Modifier.size(104.dp), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = if (enabled) accent else accent.copy(alpha = 0.35f),
            contentColor = palette.onAccent,
            shadowElevation = if (enabled) 3.dp else 0.dp,
            modifier = Modifier.size(72.dp).semantics {
                contentDescription = label
                role = Role.Button
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(32.dp))
            }
        }
    }
}

/**
 * The compact pill-shaped stop control (VOICE-002, VOICE-003) floating under the control bar while a
 * recording is starting, running or stopping: stop, "Recording" and the elapsed m:ss. At least 48 dp high.
 */
@Composable
private fun RecordPill(phase: VoicePhase, enabled: Boolean, elapsedSeconds: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val listening = phase == VoicePhase.LISTENING
    val active = listening || phase == VoicePhase.STOPPING || phase == VoicePhase.STARTING
    val text = when (phase) {
        VoicePhase.IDLE -> ""
        VoicePhase.STARTING -> strings.text("Starting…")
        VoicePhase.LISTENING -> strings.text("Recording")
        VoicePhase.STOPPING -> strings.text("Stopping…")
    }
    val time = if (listening) formatElapsed(elapsedSeconds) else null
    val description = strings.text(if (active) "Stop listening" else "Start listening") + (time?.let { ", $it" } ?: "")
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(percent = 50),
        color = if (listening) palette.accent else palette.panel,
        contentColor = if (listening) palette.onAccent else palette.accent,
        border = BorderStroke(1.dp, if (listening) palette.accent else palette.border),
        modifier = modifier.heightIn(min = 48.dp).semantics {
            contentDescription = description
            role = Role.Button
        },
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (time != null) Text(time, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** `0:07`, `12:05`, `61:00` — minutes are not wrapped into hours. */
internal fun formatElapsed(seconds: Int): String {
    val total = seconds.coerceAtLeast(0)
    val s = total % 60
    return "${total / 60}:${if (s < 10) "0" else ""}$s"
}

/** Microphone rationale after a denial (VOICE-011). */
@Composable
private fun PermissionPanel(permanent: Boolean, onTryAgain: () -> Unit, onOpenSettings: () -> Unit) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    CenteredPanel {
        Icon(Icons.Rounded.Mic, contentDescription = null, tint = palette.muted, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(14.dp))
        PanelText(strings.text("Microphone access is needed to transcribe speech. Audio never leaves your device."))
        Spacer(Modifier.height(16.dp))
        PanelButton(
            text = strings.text(if (permanent) "Open app settings" else "Try again"),
            onClick = if (permanent) onOpenSettings else onTryAgain,
        )
    }
}

/** The voice model download state (VOICE-095). Never shown by the direct APK, where the model is bundled. */
@Composable
private fun ModelDownloadPanel(state: VoicePackState, onRequest: () -> Unit) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    CenteredPanel {
        when (state) {
            VoicePackState.Present -> Unit
            is VoicePackState.Missing -> {
                val size = state.sizeBytes?.takeIf { it > 0 }
                PanelText(
                    if (size != null) strings.text("Voice transcription needs a one-time download of {0}.", formatSize(size))
                    else strings.text("Voice transcription needs a one-time download."),
                )
            }
            is VoicePackState.Downloading -> {
                val percent = (state.progress.coerceIn(0f, 1f) * 100).toInt()
                if (state.waitingForWifi) {
                    PanelText(strings.text("Waiting for Wi-Fi"))
                    Spacer(Modifier.height(16.dp))
                    PanelButton(strings.text("Download now"), onRequest)
                } else {
                    PanelText(strings.text("Downloading voice model… {0}%", percent))
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(),
                        color = palette.accent,
                        trackColor = palette.border,
                    )
                }
            }
            is VoicePackState.Failed -> {
                if (state.retryable) {
                    PanelText(strings.text("Could not download the voice model."))
                    Spacer(Modifier.height(16.dp))
                    PanelButton(strings.text("Retry"), onRequest)
                } else {
                    PanelText(strings.text(VoiceError.MODEL_UNAVAILABLE.label))
                }
            }
        }
    }
}

@Composable
private fun CenteredPanel(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun PanelText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = LocalDictionaryPalette.current.muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 420.dp),
    )
}

@Composable
private fun PanelButton(text: String, onClick: () -> Unit) {
    val palette = LocalDictionaryPalette.current
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent),
    ) { Text(text) }
}

/** A 40 dp outlined accent icon button, as the sentence actions of Reading (MED-020). */
@Composable
private fun HeaderAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalDictionaryPalette.current
    OutlinedIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.outlinedIconButtonColors(
            contentColor = palette.accent,
            disabledContentColor = palette.accent.copy(alpha = 0.35f),
        ),
        border = BorderStroke(1.dp, palette.border),
    ) { Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(21.dp)) }
}

/** `238 MB`, `1.2 GB` — decimal units, as stores report download sizes. */
internal fun formatSize(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    if (mb < 1000) return "${mb.toLong().coerceAtLeast(1)} MB"
    val tenths = (mb / 100).toLong()
    return "${tenths / 10}.${tenths % 10} GB"
}
