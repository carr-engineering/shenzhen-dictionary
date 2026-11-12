package com.szdict.app.feature.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.szdict.app.AppSession
import com.szdict.app.Destination
import com.szdict.app.feature.conversion.ScriptConversionButton
import com.szdict.app.i18n.appStrings
import com.szdict.app.integration.IncomingIntents
import com.szdict.app.ui.kit.LocalSheetInset
import com.szdict.app.ui.kit.PassageText
import com.szdict.app.ui.kit.PronunciationToggle
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

    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // Below 600 dp the app's bottom bar sits under this screen and owns the navigation inset.
        val bottomInset = if (maxWidth < 600.dp) 0.dp else navInset
        // The controls stay reachable above the word sheet at its peek height (TXT-044).
        val sheetInset = LocalSheetInset.current.coerceAtMost(maxHeight * 0.5f)
        Column(
            Modifier
                .widthIn(max = 900.dp)   // GLB-067
                .fillMaxSize()
                .padding(top = topInset + 14.dp, bottom = maxOf(bottomInset, sheetInset) + 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VoiceHeader(
                session = session,
                holder = holder,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val pack = holder.packState
                val permission = holder.permissionStatus
                when {
                    !holder.available -> CenteredPanel { PanelText(strings.text(VoiceError.MODEL_UNAVAILABLE.label)) }
                    pack != VoicePackState.Present -> ModelDownloadPanel(pack, onRequest = holder::requestModel)
                    permission == MicPermissionStatus.DENIED || permission == MicPermissionStatus.DENIED_PERMANENTLY ->
                        PermissionPanel(
                            permanent = permission == MicPermissionStatus.DENIED_PERMANENTLY,
                            onTryAgain = holder::startListening,
                            onOpenSettings = holder::openPermissionSettings,
                        )
                    holder.isEmpty -> CenteredPanel {
                        PanelText(strings.text("Tap the microphone and speak Mandarin, Cantonese or English."))
                    }
                    else -> Transcript(holder, session, listState)
                }
            }
            val status = holder.status
            Text(
                text = status?.let { strings.text(it) } ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (holder.error != null) MaterialTheme.colorScheme.error else palette.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).padding(horizontal = 16.dp, vertical = 2.dp),
            )
            ListenButton(
                listening = holder.listening,
                enabled = holder.canStart,
                level = holder.inputLevel,
                onClick = holder::toggleListening,
            )
        }
    }
}

/** Title, pronunciation toggle, `Copy`, `Clear`, and the script-conversion split button (VOICE-002). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceHeader(
    session: AppSession,
    holder: VoiceStateHolder,
    modifier: Modifier = Modifier,
) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = strings.text("Voice"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = palette.fg,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
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
private fun Transcript(holder: VoiceStateHolder, session: AppSession, listState: LazyListState) {
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
    LaunchedEffect(lines.size, live?.display, holder.listening) {
        if (holder.listening && follow && !sheetOpen) scrollToEnd()
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
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

/** The 72 dp start/stop control (VOICE-002, VOICE-003) with a live input-level halo while listening. */
@Composable
private fun ListenButton(listening: Boolean, enabled: Boolean, level: Float, onClick: () -> Unit) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val label = strings.text(if (listening) "Stop listening" else "Start listening")
    val halo by animateFloatAsState(if (listening) level else 0f, tween(110), label = "voice-input-level")
    val accent = palette.accent
    Box(
        modifier = Modifier.size(104.dp).drawBehind {
            if (listening) {
                val base = 36.dp.toPx()
                drawCircle(accent.copy(alpha = 0.14f), radius = base + 5.dp.toPx() + 11.dp.toPx() * halo)
                drawCircle(accent.copy(alpha = 0.22f), radius = base + 2.dp.toPx() + 6.dp.toPx() * halo)
            }
        },
        contentAlignment = Alignment.Center,
    ) {
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
                Icon(
                    imageVector = if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
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
