package com.szdict.app.feature.ocr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.util.concurrent.TimeUnit

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

@Composable
actual fun rememberCameraPermission(): CameraPermissionState {
    val context = LocalContext.current
    val activity = context.findActivity()
    var granted by remember { mutableStateOf(context.hasPermission(Manifest.permission.CAMERA)) }
    // CAM-014: survives configuration change, so the copy never reverts to `Allow camera`.
    var asked by rememberSaveable { mutableStateOf(false) }
    var blocked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        asked = true
        // CAM-012: no rationale after a denial means the denial is permanent.
        blocked = !ok && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }
    // CAM-013: re-checked on every resume, so a grant in system settings starts the viewfinder.
    DisposableEffect(activity) {
        val owner = activity as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = context.hasPermission(Manifest.permission.CAMERA)
                if (granted) blocked = false
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    val status = when {
        granted -> CameraPermission.GRANTED
        blocked -> CameraPermission.BLOCKED
        asked -> CameraPermission.DENIED
        else -> CameraPermission.NOT_ASKED
    }
    return CameraPermissionState(
        status = status,
        request = { runCatching { launcher.launch(Manifest.permission.CAMERA) } },
        openSettings = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    )
}

@OptIn(ExperimentalCamera2Interop::class)
private fun CameraInfo.cameraIdOrNull(): String? = runCatching { Camera2CameraInfo.from(this).cameraId }.getOrNull()

/** Focal length over the active sensor width: proportional to the zoom a lens gives (as CameraX's own FovUtil computes it). */
@OptIn(ExperimentalCamera2Interop::class)
private fun CameraInfo.normalizedFocal(): Float? = runCatching {
    val info = Camera2CameraInfo.from(this)
    val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
    val size = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    val pixels = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
    if (focal == null || size == null || focal <= 0f) return@runCatching null
    val width = if (active != null && pixels != null && pixels.width > 0) size.width * active.width() / pixels.width else size.width
    if (width > 0f) focal / width else null
}.getOrNull()

/** CAM-022 / CAM-028: what the lens menu needs of a camera CameraX can open. */
@OptIn(ExperimentalCamera2Interop::class)
private fun CameraInfo.spec(): CameraSpec? {
    val id = cameraIdOrNull() ?: return null
    val camera2 = runCatching { Camera2CameraInfo.from(this) }.getOrNull()
    // The zoom range from the characteristics: CameraX only reports it for the open camera.
    val range = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) camera2?.getCameraCharacteristic(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
    }.getOrNull()
    val maxDigital = runCatching { camera2?.getCameraCharacteristic(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) }.getOrNull()
    val physical = runCatching { physicalCameraInfos }.getOrDefault(emptySet()).filter { it.cameraIdOrNull() != id }
    return CameraSpec(
        id = id,
        front = lensFacing == CameraSelector.LENS_FACING_FRONT,
        minZoom = range?.lower ?: 1f,
        maxZoom = range?.upper ?: maxDigital ?: 1f,
        focal = normalizedFocal(),
        physicalFocals = if (physical.size >= 2) physical.mapNotNull { it.normalizedFocal() } else emptyList(),
        physicalIds = physical.mapNotNull { it.cameraIdOrNull() }.toSet(),
        intrinsicZoom = runCatching { intrinsicZoomRatio }.getOrNull(),
    )
}

/**
 * CAM-028: the lens menu from every camera CameraX can open (the ids `CameraManager.getCameraIdList()`
 * returns to this app: some vendors list only the main rear and the front camera to third-party apps).
 */
private fun lensPlan(context: Context): LensPlan? = runCatching {
    val infos = ProcessCameraProvider.getInstance(context).get().availableCameraInfos
        .filter { it.lensFacing == CameraSelector.LENS_FACING_BACK || it.lensFacing == CameraSelector.LENS_FACING_FRONT }
    val defaultRear = runCatching { CameraSelector.DEFAULT_BACK_CAMERA.filter(infos).firstOrNull()?.cameraIdOrNull() }.getOrNull()
    val specs = infos.mapNotNull { it.spec() }
    planLenses(specs, defaultRear).also { plan ->
        Log.i("szdict.ocr", "cameras ${specs.joinToString { "${it.id}${if (it.front) "F" else "B"} zoom ${it.minZoom}..${it.maxZoom} focal ${it.focal} lenses ${it.lensCount}" }}; default rear $defaultRear; menu ${plan.lenses.joinToString { "${it.label}=${it.id}" }}")
    }
}.getOrNull()

@OptIn(ExperimentalCamera2Interop::class)
private fun cameraWithId(id: String): CameraSelector =
    CameraSelector.Builder().addCameraFilter { infos -> infos.filter { it.cameraIdOrNull() == id } }.build()

/** Which camera is open, and the zoom to apply once a rebound camera is open (CAM-028). */
private class LensTracker {
    var plan: LensPlan? = null
    var currentId: String? = null
    var info: CameraInfo? = null
    var pendingZoom: Float? = null
    var stateSource: LiveData<CameraState>? = null
}

@OptIn(ExperimentalCamera2Interop::class)
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
    val context = LocalContext.current
    val owner = context.findActivity() as? LifecycleOwner
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val captured by rememberUpdatedState(onCaptured)
    val captureError by rememberUpdatedState(onCaptureError)
    val focus by rememberUpdatedState(onFocus)
    val longPress by rememberUpdatedState(onLongPress)
    val flashMode by rememberUpdatedState(flash)
    val disposed = remember { booleanArrayOf(false) }
    val focusSequence = remember { intArrayOf(0) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    val controller = remember(context) {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            // CAM-020: 4:3 keeps the full sensor area; preview and capture share it, so what is framed is what is recognised.
            val selector = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            previewResolutionSelector = selector
            imageCaptureResolutionSelector = selector
            isTapToFocusEnabled = false   // handled below, with the reticle
            isPinchToZoomEnabled = false  // likewise: the Compose layer above the preview takes the touches
        }
    }
    val lenses = remember(controller) { LensTracker() }

    // CAM-036 / CAM-039: capture flash through ImageCapture; `Flashlight` is the torch with the flash off. A torch
    // request only takes while the camera is open, so this runs again whenever it (re)opens.
    fun applyIllumination() {
        val mode = flashMode
        controller.imageCaptureFlashMode = when (captureFlash(mode)) {
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        if (controller.cameraInfo != null) runCatching { controller.enableTorch(mode == FlashMode.TORCH) }
    }

    val stateObserver = remember(controller) {
        Observer<CameraState> { state -> if (!disposed[0] && state.type == CameraState.Type.OPEN) applyIllumination() }
    }

    // A camera was bound (first open, or a switch to another camera of the lens menu).
    fun onCamera(info: CameraInfo) {
        if (info === lenses.info) return
        lenses.info = info
        val id = info.cameraIdOrNull()
        lenses.currentId = id
        handle.flashModes = if (info.hasFlashUnit()) setOf(FlashMode.AUTO, FlashMode.ON, FlashMode.TORCH) else emptySet()   // CAM-037
        handle.ready = true
        lenses.stateSource?.removeObserver(stateObserver)
        lenses.stateSource = info.cameraState.also { source -> owner?.let { source.observe(it, stateObserver) } }
        lenses.pendingZoom?.let { ratio ->
            lenses.pendingZoom = null
            runCatching { controller.setZoomRatio(ratio) }
        }
    }

    val zoomObserver = remember(controller) {
        Observer<ZoomState> { _ ->
            if (disposed[0]) return@Observer
            controller.cameraInfo?.let { onCamera(it) }
        }
    }

    // CAM-025: bound while composed and the activity is started; released on dispose or backgrounding.
    DisposableEffect(controller, owner) {
        disposed[0] = false
        if (owner == null) {
            handle.available = false
        } else {
            val ready = controller.initializationFuture
            ready.addListener({
                if (disposed[0]) return@addListener
                try {
                    ready.get()
                    // CAM-022: the rear camera by default (a logical multi-camera where it reaches more lenses,
                    // CAM-028); whatever camera exists otherwise. The front camera is an entry of the lens menu.
                    val plan = lensPlan(context)
                    lenses.plan = plan
                    val rear = plan?.rearId?.let(::cameraWithId)
                    val selector = when {
                        rear != null && controller.hasCamera(rear) -> rear
                        controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                        controller.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> null
                    }
                    if (selector == null) {
                        handle.available = false   // CAM-015
                    } else {
                        handle.lenses = plan?.lenses.orEmpty()
                        handle.lens = plan?.initial?.id
                        controller.cameraSelector = selector
                        controller.bindToLifecycle(owner)
                        controller.zoomState.observe(owner, zoomObserver)
                    }
                } catch (e: Exception) {
                    handle.available = false
                }
            }, executor)
        }
        handle.lensSwitch = { lens ->
            // `<camera id>@<zoom ratio>` is a step of the main camera's zoom; a bare id is another camera
            // (a separate rear lens, or the front camera), opened by rebinding.
            val at = lens.id.lastIndexOf('@')
            val cameraId = if (at > 0) lens.id.substring(0, at) else lens.id
            // A choice also resets any pinch zoom to the lens's own base (1 on a separate camera).
            val ratio = (if (at > 0) lens.id.substring(at + 1).toFloatOrNull() else null) ?: 1f
            if (cameraId == lenses.currentId) {
                runCatching { controller.setZoomRatio(ratio) }
            } else {
                lenses.pendingZoom = ratio
                runCatching { controller.cameraSelector = cameraWithId(cameraId) }
            }
        }
        handle.shutter = {
            handle.capturing = true
            try {
                controller.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        // The camera's encoded output is kept as is for saving (CAM-073, CAM-074).
                        val bytes = try {
                            image.use { proxy ->
                                if (proxy.format != ImageFormat.JPEG) null
                                else proxy.planes[0].buffer.let { buffer -> ByteArray(buffer.remaining()).also { buffer.get(it) } }
                            }
                        } catch (e: Exception) {
                            null
                        }
                        if (disposed[0]) return   // CAM-127: arrived after release
                        handle.capturing = false
                        if (bytes != null) captured(bytes) else captureError()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (disposed[0]) return
                        handle.capturing = false
                        captureError()
                    }
                })
            } catch (e: Exception) {
                handle.capturing = false
                captureError()
            }
        }
        onDispose {
            disposed[0] = true
            // CAM-039: the torch goes out with the camera.
            if (controller.cameraInfo != null) runCatching { controller.enableTorch(false) }
            controller.zoomState.removeObserver(zoomObserver)
            lenses.stateSource?.removeObserver(stateObserver)
            handle.reset()
            controller.unbind()
        }
    }

    // CAM-037 / CAM-038: the session's flash state is applied on every change and on every (re)open.
    LaunchedEffect(controller, flash) { applyIllumination() }

    Box(modifier) {
        AndroidView(
            factory = { viewContext ->
                PreviewView(viewContext).apply {
                    // A TextureView clips to the frame's rounded corners and sits under the Compose controls.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FIT_CENTER   // CAM-021
                    this.controller = controller
                    previewView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize()
                .pointerInput(controller) {
                    // CAM-028: pinch zooms continuously (across a logical camera's lenses); the chip keeps the selected camera.
                    detectTransformGestures { _, _, zoomChange, _ ->
                        val zoom = controller.zoomState.value ?: return@detectTransformGestures
                        if (zoomChange != 1f) {
                            runCatching { controller.setZoomRatio((zoom.zoomRatio * zoomChange).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)) }
                        }
                    }
                }
                .pointerInput(controller) {
                detectTapGestures(
                    onLongPress = { longPress() },
                    onTap = { position ->
                        // CAM-030…CAM-034: AF + AE at the tapped point through the preview's own transform.
                        val view = previewView ?: return@detectTapGestures
                        val info = controller.cameraInfo ?: return@detectTapGestures
                        val control = controller.cameraControl ?: return@detectTapGestures
                        val point = view.meteringPointFactory.createPoint(position.x, position.y)
                        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                            .setAutoCancelDuration(5, TimeUnit.SECONDS)
                            .build()
                        if (!info.isFocusMeteringSupported(action)) return@detectTapGestures
                        val id = ++focusSequence[0]
                        focus(position, FocusOutcome.PENDING)
                        val result = control.startFocusAndMetering(action)   // replaces an in-flight request
                        result.addListener({
                            if (disposed[0] || id != focusSequence[0]) return@addListener
                            val focused = try { result.get().isFocusSuccessful } catch (e: Exception) { false }
                            focus(position, if (focused) FocusOutcome.FOCUSED else FocusOutcome.FAILED)
                        }, executor)
                    },
                )
            },
        )
    }
}

@Composable
actual fun rememberImagePickers(onPicked: (String) -> Unit): ImagePickers {
    val picked by rememberUpdatedState(onPicked)
    // CAM-016: the Photo Picker and the document picker grant access to the one chosen image; no permission.
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { picked(it.toString()) } }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { picked(it.toString()) } }
    return remember(photos, files) {
        ImagePickers(
            photos = { runCatching { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
            files = { runCatching { files.launch(arrayOf("image/*")) } },
        )
    }
}

@Composable
actual fun Modifier.imageDropTarget(onHover: (Boolean) -> Unit, onDrop: (String?) -> Unit): Modifier {
    val context = LocalContext.current
    val hover by rememberUpdatedState(onHover)
    val drop by rememberUpdatedState(onDrop)
    val target = remember(context) {
        object : DragAndDropTarget {
            private fun acceptable(event: DragAndDropEvent) = event.mimeTypes().any { it.startsWith("image/") }

            override fun onEntered(event: DragAndDropEvent) = hover(acceptable(event))
            override fun onExited(event: DragAndDropEvent) = hover(false)
            override fun onEnded(event: DragAndDropEvent) = hover(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover(false)
                val dragEvent = event.toAndroidDragEvent()
                // Read access to content dropped from another app (split screen, freeform, ChromeOS).
                runCatching { context.findActivity()?.requestDragAndDropPermissions(dragEvent) }
                val clip = dragEvent.clipData
                var image: String? = null
                if (clip != null) {
                    for (i in 0 until clip.itemCount) {
                        // Only local content: an image that arrives as a web URL is never fetched (GLB-090).
                        val uri = clip.getItemAt(i).uri ?: continue
                        if (uri.scheme != "content" && uri.scheme != "file") continue
                        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                        if (type?.startsWith("image/") == true || (type == null && clip.description.hasMimeType("image/*"))) {
                            image = uri.toString()
                            break
                        }
                    }
                }
                drop(image)
                return true
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)
}

/**
 * CAM-075: on API 26–28 the legacy save path needs `WRITE_EXTERNAL_STORAGE`; it is requested at the
 * moment of the first save. API 29+ saves through MediaStore without any permission.
 */
@Composable
actual fun rememberSaveAction(save: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val action by rememberUpdatedState(save)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { action() }
    return {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !context.hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            runCatching { launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }.onFailure { action() }
        } else action()
    }
}

/** A mouse or trackpad attached to the device: Ctrl/Cmd + wheel zooms, the wheel alone pans. */
@Composable
actual fun Modifier.wheelGestures(enabled: Boolean, onZoom: (position: Offset, factor: Float) -> Unit, onPan: (Offset) -> Unit): Modifier {
    val zoom by rememberUpdatedState(onZoom)
    val pan by rememberUpdatedState(onPan)
    if (!enabled) return this
    return pointerInput(Unit) {
        val notch = 48.dp.toPx()
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                val delta = change.scrollDelta
                if (event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed) {
                    zoom(change.position, wheelZoomFactor(delta.y * 30f))
                } else {
                    pan(Offset(-delta.x * notch, -delta.y * notch))
                }
                change.consume()
            }
        }
    }
}
