package com.szdict.app.feature.ocr

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

// The browser camera, file input, drop target and recogniser are not wired yet: the OCR destination
// shows its "camera unavailable" state (PROD-023) and the image inputs do nothing.

@Composable
actual fun rememberCameraPermission(): CameraPermissionState =
    remember { CameraPermissionState(CameraPermission.GRANTED, request = {}, openSettings = {}) }

@Composable
actual fun CameraPreview(
    handle: CameraHandle,
    flash: FlashMode,
    onFocus: (position: Offset, outcome: FocusOutcome) -> Unit,
    onLongPress: () -> Unit,
    onCaptured: (ByteArray) -> Unit,
    onCaptureError: () -> Unit,
    modifier: Modifier,
) {
    LaunchedEffect(handle) {
        handle.available = false
        handle.ready = false
        handle.hasFlash = false
    }
    Box(modifier)
}

@Composable
actual fun rememberImagePickers(onPicked: (String) -> Unit): ImagePickers = remember { ImagePickers(photos = {}, files = {}) }

@Composable
actual fun Modifier.imageDropTarget(onHover: (Boolean) -> Unit, onDrop: (String?) -> Unit): Modifier = this

@Composable
actual fun rememberSaveAction(save: () -> Unit): () -> Unit = save
