@file:OptIn(ExperimentalWasmJsInterop::class)

package com.szdict.app.feature.ocr

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.szdict.app.i18n.appStrings
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise

// ---------------------------------------------------------------- camera (ARCH-197, CAM-020…)

private fun jsCameraSupported(): Boolean = js("!!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)")

private fun jsCameraPermission(): Promise<JsString> = js(
    "(navigator.permissions && navigator.permissions.query ? navigator.permissions.query({ name: 'camera' }).then((status) => status.state, () => 'prompt') : Promise.resolve('prompt'))"
)

// The feed is an HTML <video> laid under the Compose canvas at the preview's bounds; the canvas is
// cleared to transparent there, so the Compose controls (shutter, flash, picker, focus reticle)
// stay on top of it. Both the host and Compose's own root are positioned, so document order
// stacks the host (first in <body>) below.
private fun jsCameraCreate(): JsAny = js(
    """(() => {
        const host = document.createElement('div');
        host.style.cssText = 'position:fixed;left:0;top:0;width:0;height:0;background:#000;overflow:hidden;pointer-events:none;display:none';
        const video = document.createElement('video');
        video.playsInline = true;
        video.muted = true;
        video.autoplay = true;
        video.setAttribute('aria-hidden', 'true');
        video.style.cssText = 'width:100%;height:100%;object-fit:contain;display:block';
        host.appendChild(video);
        document.body.insertBefore(host, document.body.firstChild);
        return { host: host, video: video, stream: null, track: null, capture: null, torch: false, fill: [], focus: false, deviceId: null, mirrored: false, closed: false };
    })()"""
)

// Rear camera preferred (CAM-022), or the camera picked from the lens menu this session (CAM-028);
// 'ok', 'denied' or 'unavailable'. A picked camera that is gone falls back to the default.
private fun jsCameraOpen(camera: JsAny, deviceId: String?): Promise<JsString> = js(
    """(async () => {
        try {
            const size = { width: { ideal: 2560 }, height: { ideal: 1920 } };
            let stream = null;
            if (deviceId) {
                try { stream = await navigator.mediaDevices.getUserMedia({ audio: false, video: Object.assign({ deviceId: { exact: deviceId } }, size) }); } catch (e) {
                    if (e && (e.name === 'NotAllowedError' || e.name === 'SecurityError')) throw e;
                }
            }
            if (!stream) stream = await navigator.mediaDevices.getUserMedia({ audio: false, video: Object.assign({ facingMode: { ideal: 'environment' } }, size) });
            if (camera.closed) {
                stream.getTracks().forEach((track) => track.stop());
                return 'closed';
            }
            const track = stream.getVideoTracks()[0];
            camera.stream = stream;
            camera.track = track;
            camera.video.srcObject = stream;
            try { await camera.video.play(); } catch (e) { /* autoplay starts it */ }
            const capabilities = track.getCapabilities ? track.getCapabilities() : {};
            camera.torch = !!capabilities.torch;
            const settings = track.getSettings ? track.getSettings() : {};
            camera.deviceId = settings.deviceId || null;
            // CAM-022: a user-facing camera's preview is mirrored, as camera apps show it; the captured
            // photo (takePhoto() or a frame of the stream) never is.
            camera.mirrored = settings.facingMode === 'user';
            camera.video.style.transform = camera.mirrored ? 'scaleX(-1)' : '';
            // CAM-037: a capture flash exists only where ImageCapture reports fill-light modes.
            camera.fill = [];
            if (typeof ImageCapture !== 'undefined') {
                try {
                    camera.capture = new ImageCapture(track);
                    const photo = await Promise.race([camera.capture.getPhotoCapabilities(), new Promise((resolve) => setTimeout(() => resolve(null), 1500))]);
                    camera.fill = (photo && photo.fillLightMode) || [];
                } catch (e) { camera.capture = null; }
            }
            if (camera.closed) {
                stream.getTracks().forEach((t) => t.stop());
                return 'closed';
            }
            const modes = capabilities.focusMode || [];
            camera.focus = 'pointsOfInterest' in capabilities && (modes.includes('single-shot') || modes.includes('manual'));
            camera.host.style.display = 'block';
            return 'ok';
        } catch (e) {
            return e && (e.name === 'NotAllowedError' || e.name === 'SecurityError') ? 'denied' : 'unavailable';
        }
    })()"""
)

private fun jsCameraStop(camera: JsAny): Unit = js(
    "{ if (camera.stream) camera.stream.getTracks().forEach((track) => track.stop()); camera.stream = null; camera.track = null; camera.capture = null; camera.video.srcObject = null; camera.host.style.display = 'none'; }"
)

private fun jsCameraClose(camera: JsAny): Unit = js(
    "{ camera.closed = true; if (camera.stream) camera.stream.getTracks().forEach((track) => track.stop()); camera.stream = null; camera.track = null; camera.video.srcObject = null; camera.host.remove(); }"
)

private fun jsCameraPlace(camera: JsAny, left: Double, top: Double, width: Double, height: Double): Unit = js(
    "{ const s = camera.host.style; s.left = left + 'px'; s.top = top + 'px'; s.width = width + 'px'; s.height = height + 'px'; }"
)

// The illumination the open camera offers besides 'off' (CAM-037): 'auto' and 'flash' (fill light at capture), 'torch'.
private fun jsCameraLights(camera: JsAny): String = js(
    "[camera.fill.includes('auto') ? 'auto' : '', camera.fill.includes('flash') ? 'flash' : '', camera.torch ? 'torch' : ''].filter((x) => x).join(',')"
)

// CAM-039: the continuous light of `Flashlight`.
private fun jsCameraSetTorch(camera: JsAny, on: Boolean): Unit = js(
    "{ if (camera.track && camera.torch) camera.track.applyConstraints({ advanced: [{ torch: on }] }).catch(() => {}); }"
)

private fun jsCameraDeviceId(camera: JsAny): String? = js("camera.deviceId")

// CAM-028: the video inputs as `id<TAB>label` lines; labels are empty until camera access is granted.
private fun jsVideoInputs(): Promise<JsString> = js(
    """(navigator.mediaDevices && navigator.mediaDevices.enumerateDevices
        ? navigator.mediaDevices.enumerateDevices().then((devices) => devices.filter((d) => d.kind === 'videoinput' && d.deviceId).map((d) => d.deviceId + '\t' + (d.label || '').replace(/[\t\n]/g, ' ')).join('\n'), () => '')
        : Promise.resolve(''))"""
)

private fun jsOnDeviceChange(handler: () -> Unit): JsAny = js(
    "(() => { const md = navigator.mediaDevices; if (!md || !md.addEventListener) return () => {}; md.addEventListener('devicechange', handler); return () => md.removeEventListener('devicechange', handler); })()"
)

private fun jsCameraFocus(camera: JsAny): Boolean = js("camera.focus")

// A full-resolution still: ImageCapture.takePhoto() where the browser has it, otherwise the current
// video frame. Always answered as JPEG bytes (the shared code keeps them for saving, CAM-074).
// `flash` is 'off', 'auto', 'on' or 'torch' (CAM-036): the fill light of takePhoto() where the camera
// reports it; `Flashlight` is the torch, already lit, which is lit again should the photo turn it off.
private fun jsCameraCapture(camera: JsAny, flash: String): Promise<JsAny> = js(
    """(async () => {
        const track = camera.track;
        const video = camera.video;
        if (!track) throw new Error('The camera is not open');
        const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
        let blob = null;
        if (typeof ImageCapture !== 'undefined') {
            try {
                const capture = camera.capture || (camera.capture = new ImageCapture(track));
                const settings = {};
                if (flash === 'auto' && camera.fill.includes('auto')) settings.fillLightMode = 'auto';
                else if (flash === 'on' && camera.fill.includes('flash')) settings.fillLightMode = 'flash';
                else if (flash === 'off' && camera.fill.includes('off')) settings.fillLightMode = 'off';
                blob = await Promise.race([capture.takePhoto(settings), sleep(4000).then(() => { throw new Error('timeout'); })]);
            } catch (e) {
                blob = null;
                camera.capture = null;
            }
            if (flash === 'torch' && camera.torch) track.applyConstraints({ advanced: [{ torch: true }] }).catch(() => {});
        }
        const jpeg = async (source, width, height) => {
            const canvas = document.createElement('canvas');
            canvas.width = width;
            canvas.height = height;
            canvas.getContext('2d').drawImage(source, 0, 0, width, height);
            return new Promise((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('encode'))), 'image/jpeg', 0.95));
        };
        if (!blob) {
            if (!(video.videoWidth > 0 && video.videoHeight > 0)) throw new Error('No video frame');
            blob = await jpeg(video, video.videoWidth, video.videoHeight);
        } else if (blob.type !== 'image/jpeg') {
            const bitmap = await createImageBitmap(blob);
            try { blob = await jpeg(bitmap, bitmap.width, bitmap.height); } finally { bitmap.close(); }
        }
        return new Uint8Array(await blob.arrayBuffer());
    })()"""
)

// Focus and exposure at a point of the preview box (CSS pixels within the host), where the camera
// supports points of interest; resolves to whether the request was accepted.
private fun jsCameraFocusAt(camera: JsAny, x: Double, y: Double): Promise<JsBoolean> = js(
    """(async () => {
        const track = camera.track;
        const video = camera.video;
        if (!track || !camera.focus || !(video.videoWidth > 0)) return false;
        const box = camera.host.getBoundingClientRect();
        const scale = Math.min(box.width / video.videoWidth, box.height / video.videoHeight);
        const shown = (x - (box.width - video.videoWidth * scale) / 2) / (video.videoWidth * scale);
        const nx = camera.mirrored ? 1 - shown : shown;
        const ny = (y - (box.height - video.videoHeight * scale) / 2) / (video.videoHeight * scale);
        if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return false;
        const modes = (track.getCapabilities().focusMode || []);
        try {
            await track.applyConstraints({ advanced: [{ pointsOfInterest: [{ x: nx, y: ny }], focusMode: modes.includes('single-shot') ? 'single-shot' : 'manual' }] });
            return true;
        } catch (e) {
            return false;
        }
    })()"""
)

private fun jsOnVisibility(handler: (Boolean) -> Unit): JsAny = js(
    "(() => { const listener = () => handler(document.hidden); document.addEventListener('visibilitychange', listener); return () => document.removeEventListener('visibilitychange', listener); })()"
)

private fun jsInvoke(callback: JsAny): Unit = js("callback()")

/** The camera picked from the lens menu, for the rest of the session (CAM-028); null is the default rear camera. */
private object WebCameraChoice {
    var deviceId: String? = null
}

/** `auto`, `flash` (fill light at capture) and `torch`, as the open camera reports them (CAM-037). */
private fun webFlashModes(lights: String): Set<FlashMode> = lights.split(',').mapNotNull {
    when (it) {
        "auto" -> FlashMode.AUTO
        "flash" -> FlashMode.ON
        "torch" -> FlashMode.TORCH
        else -> null
    }
}.toSet()

/** Camera access as the browser reports it (CAM-010…CAM-014); the browser's own prompt is the request. */
private object WebCameraAccess {
    var status: CameraPermission by mutableStateOf(CameraPermission.GRANTED)
    private var asked = false

    suspend fun refresh() {
        // Without the API there is nothing to grant: the preview reports "unavailable" (CAM-015).
        if (!jsCameraSupported()) {
            status = CameraPermission.GRANTED
            return
        }
        status = when (jsCameraPermission().await<JsString>().toString()) {
            "granted" -> CameraPermission.GRANTED
            "denied" -> CameraPermission.DENIED
            else -> if (asked && status == CameraPermission.GRANTED) CameraPermission.GRANTED else CameraPermission.NOT_ASKED
        }
    }

    /** `Allow camera` / `Try again`: opening the camera makes the browser ask. */
    fun request() {
        asked = true
        status = CameraPermission.GRANTED
    }

    fun denied() {
        status = CameraPermission.DENIED
    }
}

@Composable
actual fun rememberCameraPermission(): CameraPermissionState {
    LaunchedEffect(Unit) { WebCameraAccess.refresh() }
    val status = WebCameraAccess.status
    return remember(status) { CameraPermissionState(status, request = WebCameraAccess::request, openSettings = WebCameraAccess::request) }
}

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
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val captured by rememberUpdatedState(onCaptured)
    val captureError by rememberUpdatedState(onCaptureError)
    val focus by rememberUpdatedState(onFocus)
    val longPress by rememberUpdatedState(onLongPress)
    val flashMode by rememberUpdatedState(flash)
    var camera by remember { mutableStateOf<JsAny?>(null) }
    val strings = appStrings()
    val unnamed by rememberUpdatedState { index: Int -> strings.text("Camera {0}", index) }

    // CAM-025: open while composed and the page is visible; tracks are stopped when OCR is left or the page is hidden.
    DisposableEffect(Unit) {
        if (!jsCameraSupported()) {
            handle.available = false
            return@DisposableEffect onDispose { }
        }
        val created = jsCameraCreate()
        camera = created
        var disposed = false

        // CAM-028: the video inputs by name (`Camera 1`, `Camera 2`… while the browser withholds labels).
        fun refreshCameras() {
            scope.launch {
                val lines = jsVideoInputs().await<JsString>().toString().split('\n').filter { it.isNotBlank() }
                if (disposed) return@launch
                handle.lenses = lines.mapIndexed { index, line ->
                    val id = line.substringBefore('\t')
                    val label = line.substringAfter('\t', "").trim()
                    CameraLens(id, factor = null, name = label.ifEmpty { unnamed(index + 1) })
                }
            }
        }

        fun open() {
            scope.launch {
                val outcome = jsCameraOpen(created, WebCameraChoice.deviceId).await<JsString>().toString()
                if (disposed) return@launch
                when (outcome) {
                    "ok" -> {
                        handle.flashModes = webFlashModes(jsCameraLights(created))   // CAM-037
                        handle.lens = jsCameraDeviceId(created)
                        handle.ready = true
                        if (flashMode == FlashMode.TORCH) jsCameraSetTorch(created, true)
                        refreshCameras()   // labels are only listed once access is granted
                    }
                    "denied" -> WebCameraAccess.denied()
                    "closed" -> Unit
                    else -> handle.available = false   // CAM-015: no camera
                }
            }
        }
        open()
        val unlistenDevices = jsOnDeviceChange { if (!disposed) refreshCameras() }
        handle.lensSwitch = { lens ->
            // Remembered for the session; the stream restarts on that camera.
            WebCameraChoice.deviceId = lens.id
            if (lens.id != jsCameraDeviceId(created)) {
                handle.ready = false
                jsCameraStop(created)
                open()
            }
        }
        val unlisten = jsOnVisibility { hidden ->
            if (disposed) return@jsOnVisibility
            if (hidden) {
                handle.ready = false
                handle.capturing = false
                jsCameraStop(created)
            } else {
                open()
            }
        }
        handle.shutter = {
            handle.capturing = true
            scope.launch {
                val bytes = try {
                    typedArrayBytes(jsCameraCapture(created, flashMode.name.lowercase()).await())
                } catch (e: Throwable) {
                    null
                }
                if (disposed) return@launch   // CAM-127: arrived after release
                handle.capturing = false
                if (bytes != null && bytes.isNotEmpty()) captured(bytes) else captureError()
            }
        }
        onDispose {
            disposed = true
            jsInvoke(unlisten)
            jsInvoke(unlistenDevices)
            handle.reset()
            jsCameraClose(created)   // stopping the track also puts out the torch (CAM-039)
            camera = null
        }
    }

    // CAM-038: the chosen mode is applied as it changes; the torch only exists in `Flashlight`.
    LaunchedEffect(camera, flash) {
        val open = camera ?: return@LaunchedEffect
        if (handle.ready) jsCameraSetTorch(open, flash == FlashMode.TORCH)
    }

    Box(
        modifier
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                camera?.let { jsCameraPlace(it, (bounds.left / density).toDouble(), (bounds.top / density).toDouble(), (bounds.width / density).toDouble(), (bounds.height / density).toDouble()) }
            }
            // A hole in the canvas: the <video> underneath shows through.
            .drawBehind { drawRect(Color.Transparent, blendMode = BlendMode.Clear) }
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { longPress() },
                    onTap = { position ->
                        // CAM-030…CAM-034: only where the camera takes a focus point; otherwise a tap does nothing.
                        val target = camera
                        if (target != null && jsCameraFocus(target)) {
                            focus(position, FocusOutcome.PENDING)
                            scope.launch {
                                val accepted = jsCameraFocusAt(target, (position.x / density).toDouble(), (position.y / density).toDouble()).await<JsBoolean>().toBoolean()
                                focus(position, if (accepted) FocusOutcome.FOCUSED else FocusOutcome.FAILED)
                            }
                        }
                    },
                )
            },
    )
}

// ---------------------------------------------------------------- pick, drop (CAM-007, CAM-131)

private fun jsPickImage(onPicked: (JsAny) -> Unit): Unit = js(
    """{
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = 'image/*';
        input.style.display = 'none';
        input.addEventListener('change', () => {
            const file = input.files && input.files[0];
            input.remove();
            if (file) onPicked(file);
        });
        input.addEventListener('cancel', () => input.remove());
        document.body.appendChild(input);
        input.click();
    }"""
)

/** The browser's file chooser serves both `Photos` and `Files` (CAM-016). */
@Composable
actual fun rememberImagePickers(onPicked: (String) -> Unit): ImagePickers {
    val picked by rememberUpdatedState(onPicked)
    return remember {
        val pick = { jsPickImage { file -> picked(WebImageRefs.register(file)) } }
        ImagePickers(photos = pick, files = pick)
    }
}

// HTML drag and drop over the whole page while the destination is shown. Only files count: an
// image dragged from a web page that arrives as a URL is never fetched (CAM-131).
private fun jsDropTarget(onHover: (Boolean) -> Unit, onDrop: (JsAny?) -> Unit): JsAny = js(
    """(() => {
        let depth = 0;
        const acceptable = (event) => {
            const data = event.dataTransfer;
            if (!data) return false;
            const items = Array.from(data.items || []);
            if (items.length) return items.some((item) => item.kind === 'file' && (item.type === '' || item.type.startsWith('image/')));
            return Array.from(data.types || []).includes('Files');
        };
        const enter = (event) => { event.preventDefault(); depth++; if (acceptable(event)) onHover(true); };
        const over = (event) => { event.preventDefault(); if (event.dataTransfer) event.dataTransfer.dropEffect = acceptable(event) ? 'copy' : 'none'; };
        const leave = () => { depth = Math.max(0, depth - 1); if (depth === 0) onHover(false); };
        const drop = (event) => {
            event.preventDefault();
            depth = 0;
            onHover(false);
            const files = event.dataTransfer ? Array.from(event.dataTransfer.files || []) : [];
            onDrop(files.find((file) => file.type.startsWith('image/')) || null);
        };
        document.addEventListener('dragenter', enter);
        document.addEventListener('dragover', over);
        document.addEventListener('dragleave', leave);
        document.addEventListener('drop', drop);
        return () => {
            document.removeEventListener('dragenter', enter);
            document.removeEventListener('dragover', over);
            document.removeEventListener('dragleave', leave);
            document.removeEventListener('drop', drop);
        };
    })()"""
)

@Composable
actual fun Modifier.imageDropTarget(onHover: (Boolean) -> Unit, onDrop: (String?) -> Unit): Modifier {
    val hover by rememberUpdatedState(onHover)
    val drop by rememberUpdatedState(onDrop)
    DisposableEffect(Unit) {
        val remove = jsDropTarget({ hover(it) }, { file -> drop(file?.let(WebImageRefs::register)) })
        onDispose {
            jsInvoke(remove)
            hover(false)
        }
    }
    return this
}

/** A download needs no permission. */
@Composable
actual fun rememberSaveAction(save: () -> Unit): () -> Unit = save

// ---------------------------------------------------------------- wheel and trackpad over the review image

// Listens before the page does (capture, not passive): a wheel event the element takes is kept from
// the browser, whose default for a pinch or Ctrl/Cmd + wheel is to zoom the whole page. Deltas are
// normalised to CSS pixels.
private fun jsWheelTarget(onWheel: (Double, Double, Double, Double, Boolean) -> Boolean): JsAny = js(
    """(() => {
        const handler = (event) => {
            const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? window.innerHeight : 1;
            if (onWheel(event.clientX, event.clientY, event.deltaX * unit, event.deltaY * unit, event.ctrlKey || event.metaKey)) event.preventDefault();
        };
        window.addEventListener('wheel', handler, { capture: true, passive: false });
        return () => window.removeEventListener('wheel', handler, { capture: true });
    })()"""
)

@Composable
actual fun Modifier.wheelGestures(enabled: Boolean, onZoom: (position: Offset, factor: Float) -> Unit, onPan: (Offset) -> Unit): Modifier {
    val zoom by rememberUpdatedState(onZoom)
    val pan by rememberUpdatedState(onPan)
    val active by rememberUpdatedState(enabled)
    val density = LocalDensity.current.density
    val scale by rememberUpdatedState(density)
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    DisposableEffect(Unit) {
        val remove = jsWheelTarget { clientX, clientY, deltaX, deltaY, zooming ->
            // The canvas fills the page: CSS pixels times the density are its own pixels.
            val point = Offset((clientX * scale).toFloat(), (clientY * scale).toFloat())
            if (!active || !bounds.contains(point)) return@jsWheelTarget false
            if (zooming) zoom(point - bounds.topLeft, wheelZoomFactor(deltaY.toFloat()))
            else pan(Offset(-(deltaX * scale).toFloat(), -(deltaY * scale).toFloat()))
            true
        }
        onDispose { jsInvoke(remove) }
    }
    return onGloballyPositioned { bounds = it.boundsInWindow() }
}
