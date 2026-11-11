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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

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
                    // CAM-022: the rear camera by default; whatever camera exists otherwise.
                    val selector = when {
                        controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                        controller.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> null
                    }
                    if (selector == null) {
                        handle.available = false   // CAM-015
                    } else {
                        controller.cameraSelector = selector
                        controller.bindToLifecycle(owner)
                    }
                } catch (e: Exception) {
                    handle.available = false
                }
            }, executor)
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
            handle.shutter = null
            handle.capturing = false
            handle.hasFlash = false
            controller.unbind()
        }
    }

    // CAM-037 / CAM-038: the flash unit is known once the camera is open; the session's state is re-applied on every bind.
    LaunchedEffect(controller) {
        repeat(40) {
            val info = controller.cameraInfo
            if (info != null) {
                handle.hasFlash = info.hasFlashUnit()
                return@LaunchedEffect
            }
            delay(250)
        }
    }
    LaunchedEffect(controller, flash) {
        controller.imageCaptureFlashMode = when (flash) {
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
        }
    }

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
            Modifier.fillMaxSize().pointerInput(controller) {
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
