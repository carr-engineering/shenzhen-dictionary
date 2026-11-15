package com.szdict.app.feature.ocr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/** Camera permission as the rationale panel needs it (CAM-011, CAM-012). */
enum class CameraPermission {
    GRANTED,

    /** Never asked: `Allow camera`. */
    NOT_ASKED,

    /** Soft denial: `Try again`. */
    DENIED,

    /** Permanent denial: `Open app settings`. */
    BLOCKED,
}

class CameraPermissionState(val status: CameraPermission, val request: () -> Unit, val openSettings: () -> Unit)

/** Current permission, re-checked on every resume (CAM-013); the "already asked" flag survives rotation (CAM-014). */
@Composable
expect fun rememberCameraPermission(): CameraPermissionState

/** Outcome of a tap-to-focus request, for the reticle (CAM-032). */
enum class FocusOutcome { PENDING, FOCUSED, FAILED }

/** What the shared viewfinder chrome knows about, and asks of, the bound camera. */
@Stable
class CameraHandle {
    /** False when no camera could be bound (CAM-015). */
    var available: Boolean by mutableStateOf(true)

    /** The camera is open: the shutter works. */
    var ready: Boolean by mutableStateOf(false)

    /** The illumination modes the active camera supports besides `Off` (CAM-037); empty without flash or torch. */
    var flashModes: Set<FlashMode> by mutableStateOf(emptySet())

    /** The active camera has a flash unit or a torch: the flash control is shown (CAM-037). */
    val hasFlash: Boolean get() = flashMenu(flashModes).isNotEmpty()

    /** A capture is in flight (CAM-023). */
    var capturing: Boolean by mutableStateOf(false)

    /** The lens menu (CAM-028): shown with two or more entries. */
    var lenses: List<CameraLens> by mutableStateOf(emptyList())

    /** Id of the lens last picked (or opened with): what the chip shows, whatever the zoom (CAM-028). */
    var lens: String? by mutableStateOf(null)

    /** Set by the platform preview while it is bound. */
    var shutter: (() -> Unit)? = null

    /** Set by the platform preview: switches to a lens of [lenses]. */
    var lensSwitch: ((CameraLens) -> Unit)? = null

    fun takePicture() {
        if (ready && !capturing) shutter?.invoke()
    }

    fun selectLens(lens: CameraLens) {
        this.lens = lens.id
        lensSwitch?.invoke(lens)
    }

    /** Back to the closed state when the preview is released. */
    fun reset() {
        shutter = null
        lensSwitch = null
        capturing = false
        flashModes = emptySet()
        ready = false
        lenses = emptyList()
        lens = null
    }
}

/**
 * The live preview only (CAM-020…CAM-034): 4:3, fit-centre, rear camera, bound while composed and
 * the app is in the foreground. A tap meters focus and exposure at that point and reports the
 * reticle position and outcome through [onFocus]; a long press calls [onLongPress]. The shutter is
 * driven through [handle]; a capture delivers the encoded JPEG to [onCaptured], and one finishing
 * after the preview left the composition is discarded (CAM-127).
 */
@Composable
expect fun CameraPreview(
    handle: CameraHandle,
    flash: FlashMode,
    onFocus: (position: Offset, outcome: FocusOutcome) -> Unit,
    onLongPress: () -> Unit,
    onCaptured: (ByteArray) -> Unit,
    onCaptureError: () -> Unit,
    modifier: Modifier = Modifier,
)

/** The photo picker and the document picker (CAM-016); neither needs a permission. */
class ImagePickers(val photos: () -> Unit, val files: () -> Unit)

@Composable
expect fun rememberImagePickers(onPicked: (String) -> Unit): ImagePickers

/**
 * Makes the element a drop target for images from other apps (CAM-131). [onHover] reports whether
 * an acceptable item is over it; [onDrop] receives the first dropped image, or null when the
 * dropped items contain none.
 */
@Composable
expect fun Modifier.imageDropTarget(onHover: (Boolean) -> Unit, onDrop: (String?) -> Unit): Modifier

/**
 * Wheel and trackpad input over the element, for a zoomable image: a pinch on a trackpad (browsers
 * deliver it as wheel events with Ctrl held) and Ctrl/Cmd + wheel call [onZoom] with the pointer
 * position inside the element and a scale factor; a plain two-finger scroll or wheel calls [onPan]
 * with the movement of the content in pixels. On Web the browser's own page zoom and scroll are
 * suppressed while the pointer is over the element. Nothing is delivered while [enabled] is false.
 */
@Composable
expect fun Modifier.wheelGestures(enabled: Boolean, onZoom: (position: Offset, factor: Float) -> Unit, onPan: (Offset) -> Unit): Modifier

/** Scale factor of one wheel event: a pinch sends many small deltas, a mouse notch one large (clamped) one. */
fun wheelZoomFactor(deltaY: Float): Float = kotlin.math.exp(-deltaY.coerceIn(-30f, 30f) * 0.01f)

/** Wraps the save action with whatever the platform must ask first (Android ≤ 9: storage permission, CAM-075). */
@Composable
expect fun rememberSaveAction(save: () -> Unit): () -> Unit
