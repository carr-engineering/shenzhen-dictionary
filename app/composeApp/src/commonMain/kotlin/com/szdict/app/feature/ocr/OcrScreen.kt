package com.szdict.app.feature.ocr

import com.szdict.app.ui.kit.WithTooltip
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.szdict.app.AppSession
import com.szdict.app.Destination
import com.szdict.app.ImagePaneMaxWidth
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.theme.LocalDictionaryPalette
import kotlinx.coroutines.delay

/** The OCR destination (CAM-001). The signature is the contract with the app shell. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OcrScreen(session: AppSession, modifier: Modifier = Modifier) {
    val holder = remember(session) { OcrStateHolder.of(session) }
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val pickers = rememberImagePickers(holder::onPicked)
    var dropHover by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Re-tapping the destination while reviewing returns to the live feed (CAM-009, PROD-053).
    val reselect = session.reselect.value
    val initialReselect = remember { reselect.first }
    LaunchedEffect(reselect) {
        if (reselect.second == Destination.OCR && reselect.first != initialReselect) holder.backToInput()
    }
    // CAM-100: after the word sheet, Back returns from a review (or a decode spinner/error) to the input screen.
    BackHandler(enabled = (holder.inReview || holder.galleryPending) && session.wordSheet == null && session.routes.isEmpty()) {
        holder.backToInput()
    }
    // CAM-134: the first time the destination is opened starts the recogniser's one-time download (Web).
    LaunchedEffect(holder) { holder.prepareRecognizer() }
    DisposableEffect(holder) {
        holder.listenForPaste(true)
        onDispose { holder.listenForPaste(false) }
    }
    // Keyboard focus anywhere inside the destination so Ctrl/Cmd+V pastes an image (CAM-132).
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(
        modifier
            .fillMaxSize()
            .imageDropTarget(onHover = { dropHover = it }, onDrop = holder::onDropped)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (!holder.pasteByEvent && event.type == KeyEventType.KeyDown && event.key == Key.V && (event.isCtrlPressed || event.isMetaPressed)) {
                    holder.paste()
                    true
                } else false
            },
    ) {
        when {
            holder.inReview -> ReviewPhase(session, holder, pickers)
            holder.galleryPending -> DecodePhase(holder, pickers)
            // The camera is bound only while the capture phase is actually visible (CAM-025).
            else -> CapturePhase(holder, pickers, cameraActive = session.routes.isEmpty())
        }

        holder.notice?.let { notice ->
            Surface(
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp, start = 24.dp, end = 24.dp),
                shape = MaterialTheme.shapes.medium,
                color = palette.panel,
                border = BorderStroke(1.dp, palette.border),
                shadowElevation = 3.dp,
            ) {
                Text(
                    strings.text(notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.fg,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        // CAM-131: the only drag feedback is a thin accent outline of the whole content area.
        if (dropHover) Box(Modifier.matchParentSize().border(2.dp, palette.accent))
        holder.announcement?.let { text ->
            val spoken = strings.text(text)
            Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = spoken })
        }
    }
}

/** A picked / shared / dropped / pasted image is decoding, or failed to decode (CAM-040, CAM-042). */
@Composable
private fun DecodePhase(holder: OcrStateHolder, pickers: ImagePickers) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val error = holder.inputError
        if (error == null) {
            CircularProgressIndicator(color = palette.accent)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    strings.text(error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(12.dp))
                Box {
                    var menu by remember { mutableStateOf(false) }
                    TextButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.text("Choose another image"), color = palette.accent)
                    }
                    PickMenu(menu, { menu = false }, pickers)
                }
                TextButton(onClick = { holder.backToInput() }) {
                    Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.text("Camera"), color = palette.accent)
                }
            }
        }
    }
}

/** `Photos` (photo picker) and `Files` (document picker) — CAM-016. */
@Composable
internal fun PickMenu(expanded: Boolean, onDismiss: () -> Unit, pickers: ImagePickers) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = palette.panel, border = BorderStroke(1.dp, palette.border)) {
        DropdownMenuItem(text = { Text(strings.text("Photos")) }, onClick = { onDismiss(); pickers.photos() })
        DropdownMenuItem(text = { Text(strings.text("Files")) }, onClick = { onDismiss(); pickers.files() })
    }
}

private class FocusMark(val id: Int, val position: Offset, val outcome: FocusOutcome)

/** The camera-first input screen (CAM-006, CAM-133): the viewfinder frame with its controls. */
@Composable
private fun CapturePhase(holder: OcrStateHolder, pickers: ImagePickers, cameraActive: Boolean) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val permission = rememberCameraPermission()
    val handle = remember { CameraHandle() }
    val granted = permission.status == CameraPermission.GRANTED
    val live = granted && handle.available
    var pickMenu by remember { mutableStateOf(false) }
    var pasteMenu by remember { mutableStateOf(false) }
    var pasteEnabled by remember { mutableStateOf(false) }
    var focusMark by remember { mutableStateOf<FocusMark?>(null) }
    var focusId by remember { mutableIntStateOf(0) }
    val openPasteMenu = {
        // Where pastes arrive as events there is no clipboard to offer (CAM-132).
        if (!holder.pasteByEvent) {
            pasteEnabled = holder.clipboardHasImage()
            pasteMenu = true
        }
    }
    val capturing = handle.capturing || holder.decoding == ImageOrigin.CAMERA

    BoxWithConstraints(
        Modifier.fillMaxSize().padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
        contentAlignment = Alignment.Center,
    ) {
        // CAM-020: a 4:3 frame (portrait 3:4), inset 20 dp, as large as the area allows.
        val portrait = maxHeight >= maxWidth
        val ratio = if (portrait) 3f / 4f else 4f / 3f
        val availableWidth = maxWidth - 40.dp
        val availableHeight = maxHeight - 56.dp
        // On a wide, tall window the frame stops at the width the review uses (GLB-067).
        val frameWidth = minOf(availableWidth, availableHeight * ratio, ImagePaneMaxWidth)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(frameWidth, frameWidth / ratio)
                    .clip(MaterialTheme.shapes.large)
                    .background(if (live) Color.Black else palette.panel)
                    .then(if (live) Modifier else Modifier.border(1.dp, palette.border, MaterialTheme.shapes.large))
                    .pointerInput(live) { if (!live) detectTapGestures(onLongPress = { openPasteMenu() }) },
            ) {
                when {
                    !granted -> PermissionPanel(permission, Modifier.fillMaxSize())
                    !handle.available -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            strings.text("The camera is unavailable on this device."),
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                    cameraActive -> {
                        CameraPreview(
                            handle = handle,
                            flash = effectiveFlash(holder.flash, handle.flashModes),
                            onFocus = { position, outcome ->
                                focusMark = if (outcome == FocusOutcome.PENDING) FocusMark(++focusId, position, outcome)
                                else focusMark?.let { FocusMark(it.id, it.position, outcome) }
                            },
                            onLongPress = openPasteMenu,
                            onCaptured = holder::onCameraCaptured,
                            onCaptureError = holder::onCameraError,
                            modifier = Modifier.fillMaxSize(),
                        )
                        focusMark?.let { mark -> FocusReticle(mark) { if (focusMark?.id == mark.id) focusMark = null } }
                    }
                }

                // CAM-035…CAM-037: flash menu, hidden when the active camera has neither flash nor torch.
                if (live) {
                    FlashControl(
                        mode = effectiveFlash(holder.flash, handle.flashModes),
                        supported = handle.flashModes,
                        onSelect = { holder.flash = it },
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    )
                    // CAM-028: lens chip above the shutter, with two or more lenses.
                    LensControl(handle, Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp))
                }

                // CAM-023: 72 dp accent shutter, bottom-centre; a 30 dp spinner while a capture is in flight.
                // The placement sits on a Box of this BoxScope: TooltipBox applies its modifier to an inner
                // anchor inside a Box of its own, so an `align` passed to WithTooltip would be ignored.
                if (live) Box(Modifier.align(Alignment.BottomCenter).padding(20.dp)) {
                    WithTooltip(strings.text("Take photo")) {
                        FilledIconButton(
                            onClick = handle::takePicture,
                            enabled = handle.ready && !capturing,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = palette.accent,
                                contentColor = palette.onAccent,
                                disabledContainerColor = palette.accent.copy(alpha = 0.7f),
                                disabledContentColor = palette.onAccent,
                            ),
                            modifier = Modifier.size(72.dp),
                        ) {
                            if (capturing) CircularProgressIndicator(color = palette.onAccent, modifier = Modifier.size(30.dp))
                            else Icon(Icons.Rounded.PhotoCamera, contentDescription = strings.text("Take photo"), modifier = Modifier.size(34.dp))
                        }
                    }
                }

                // CAM-007: the image picker, bottom-leading beside the shutter, in every capture-phase state.
                Box(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 32.dp)) {
                    val label = strings.text("Choose from gallery")
                    WithTooltip(label) {
                        OverlayButton(
                            icon = Icons.Rounded.PhotoLibrary,
                            onClick = { pickMenu = true },
                            live = live,
                            size = 48,
                            modifier = Modifier.semantics { contentDescription = label },
                        )
                    }
                    PickMenu(pickMenu, { pickMenu = false }, pickers)
                }

                // CAM-132: long-press offers Paste; enabled only when the clipboard holds an image.
                Box(Modifier.align(Alignment.Center)) {
                    DropdownMenu(expanded = pasteMenu, onDismissRequest = { pasteMenu = false }, containerColor = palette.panel, border = BorderStroke(1.dp, palette.border)) {
                        DropdownMenuItem(
                            text = { Text(strings.text("Paste")) },
                            enabled = pasteEnabled,
                            onClick = { pasteMenu = false; holder.paste() },
                        )
                    }
                }
            }
            // CAM-024: error text below the frame.
            Box(Modifier.height(48.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                holder.inputError?.let { error ->
                    Text(
                        strings.text(error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayButton(icon: ImageVector, onClick: () -> Unit, live: Boolean, modifier: Modifier = Modifier, size: Int = 44) {
    val palette = LocalDictionaryPalette.current
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (live) Color.Black.copy(alpha = 0.42f) else palette.bgSoft,
        contentColor = if (live) Color.White else palette.accent,
        border = if (live) BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)) else BorderStroke(1.dp, palette.border),
        modifier = modifier.size(size.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size((size / 2).dp)) }
    }
}

/** CAM-032: appears at once with a small scale-in, shows the outcome, fades out after about 1.2 s. */
@Composable
private fun FocusReticle(mark: FocusMark, onDone: () -> Unit) {
    val palette = LocalDictionaryPalette.current
    val scale = remember(mark.id) { Animatable(1.35f) }
    val alpha = remember(mark.id) { Animatable(1f) }
    LaunchedEffect(mark.id) { scale.animateTo(1f, tween(180)) }
    LaunchedEffect(mark.id, mark.outcome) {
        // An unresolved request ends with the platform's 5 s timeout (CAM-033).
        delay(if (mark.outcome == FocusOutcome.PENDING) 5_200 else 900)
        alpha.animateTo(0f, tween(300))
        onDone()
    }
    val color = when (mark.outcome) {
        FocusOutcome.PENDING -> Color.White
        FocusOutcome.FOCUSED -> palette.onAccent
        FocusOutcome.FAILED -> Color.White.copy(alpha = 0.45f)
    }
    val ring = if (mark.outcome == FocusOutcome.FOCUSED) palette.accent else color
    Canvas(Modifier.fillMaxSize()) {
        val radius = 30.dp.toPx() * scale.value
        drawCircle(ring.copy(alpha = ring.alpha * alpha.value), radius, mark.position, style = Stroke(1.75.dp.toPx()))
        if (mark.outcome == FocusOutcome.FOCUSED) drawCircle(color.copy(alpha = alpha.value), 2.5.dp.toPx(), mark.position)
    }
}

/** CAM-011: camera icon, rationale and one button whose label follows the denial state. */
@Composable
private fun PermissionPanel(permission: CameraPermissionState, modifier: Modifier = Modifier) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = palette.accent, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            strings.text("Camera access is needed to recognize Chinese text in photos."),
            style = MaterialTheme.typography.bodyLarge,
            color = palette.fg,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        when (permission.status) {
            CameraPermission.BLOCKED -> OutlinedButton(onClick = permission.openSettings) { Text(strings.text("Open app settings"), color = palette.accent) }
            CameraPermission.DENIED -> OutlinedButton(onClick = permission.request) { Text(strings.text("Try again"), color = palette.accent) }
            else -> OutlinedButton(onClick = permission.request) { Text(strings.text("Allow camera"), color = palette.accent) }
        }
    }
}
