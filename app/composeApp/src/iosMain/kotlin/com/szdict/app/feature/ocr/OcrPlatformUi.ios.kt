@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.szdict.app.feature.ocr

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.szdict.app.platform.runOnMain
import com.szdict.app.platform.toByteArray
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureExposureModeContinuousAutoExposure
import platform.AVFoundation.AVCaptureFlashModeAuto
import platform.AVFoundation.AVCaptureFlashModeOff
import platform.AVFoundation.AVCaptureFlashModeOn
import platform.AVFoundation.AVCaptureFocusModeAutoFocus
import platform.AVFoundation.AVCaptureFocusModeContinuousAutoFocus
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeJPEG
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.defaultDeviceWithDeviceType
import platform.AVFoundation.exposureMode
import platform.AVFoundation.exposurePointOfInterest
import platform.AVFoundation.exposurePointOfInterestSupported
import platform.AVFoundation.fileDataRepresentation
import platform.AVFoundation.focusMode
import platform.AVFoundation.focusPointOfInterest
import platform.AVFoundation.focusPointOfInterestSupported
import platform.AVFoundation.hasFlash
import platform.AVFoundation.isAdjustingFocus
import platform.AVFoundation.isExposureModeSupported
import platform.AVFoundation.isFocusModeSupported
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.QuartzCore.CATransaction
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIColor
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create

// ---------------------------------------------------------------- camera permission (CAM-011…)

private fun cameraStatus(): CameraPermission = when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
    AVAuthorizationStatusAuthorized -> CameraPermission.GRANTED
    AVAuthorizationStatusNotDetermined -> CameraPermission.NOT_ASKED
    // iOS asks once: `denied` and `restricted` can only be changed in Settings (CAM-012).
    else -> CameraPermission.BLOCKED
}

@Composable
actual fun rememberCameraPermission(): CameraPermissionState {
    var status by remember { mutableStateOf(cameraStatus()) }
    // CAM-013: re-checked whenever the app becomes active, so a grant in Settings starts the viewfinder.
    DisposableEffect(Unit) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
            status = cameraStatus()
        }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }
    return CameraPermissionState(
        status = status,
        request = {
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { _ -> runOnMain { status = cameraStatus() } }
        },
        openSettings = {
            NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null) }
        },
    )
}

// ---------------------------------------------------------------- camera preview (CAM-020…)

/** Hosts the preview layer and keeps it sized and rotated with the view. */
private class CameraPreviewView(session: AVCaptureSession) : UIView(frame = CGRectZero.readValue()) {
    val previewLayer = AVCaptureVideoPreviewLayer(session = session)

    init {
        backgroundColor = UIColor.blackColor
        previewLayer.videoGravity = AVLayerVideoGravityResizeAspect   // CAM-021: fit-centre
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        previewLayer.frame = bounds
        previewLayer.connection?.rotateTo(rotationAngle())
        CATransaction.commit()
    }

    /** Video rotation that shows the sensor image upright in the current interface orientation. */
    fun rotationAngle(): Double = when (window?.windowScene?.interfaceOrientation) {
        UIInterfaceOrientationLandscapeRight -> 0.0
        UIInterfaceOrientationLandscapeLeft -> 180.0
        UIInterfaceOrientationPortraitUpsideDown -> 270.0
        else -> 90.0
    }
}

private fun AVCaptureConnection.rotateTo(angle: Double) {
    if (isVideoRotationAngleSupported(angle) && videoRotationAngle != angle) videoRotationAngle = angle
}

private class PhotoDelegate(private val onDone: (PhotoDelegate, ByteArray?) -> Unit) : NSObject(), AVCapturePhotoCaptureDelegateProtocol {
    override fun captureOutput(output: AVCapturePhotoOutput, didFinishProcessingPhoto: AVCapturePhoto, error: NSError?) {
        // The camera's encoded output is kept as is for saving (CAM-073, CAM-074).
        val bytes = if (error == null) didFinishProcessingPhoto.fileDataRepresentation()?.bytesOrNull() else null
        runOnMain { onDone(this, bytes) }
    }
}

/** One AVFoundation capture session: rear wide camera, photo preset (4:3), JPEG stills (CAM-020, CAM-022). */
private class IosCamera {
    val session = AVCaptureSession()
    val view = CameraPreviewView(session)
    private val output = AVCapturePhotoOutput()
    private val queue = dispatch_queue_create("com.szdict.app.camera", null)
    private val delegates = HashSet<PhotoDelegate>()   // a capture's delegate lives until its photo arrives
    var device: AVCaptureDevice? = null
        private set

    /** Opens the camera off the main thread; [onOpened] gets the device, or null when there is none (CAM-015). */
    fun start(onOpened: (AVCaptureDevice?) -> Unit) {
        dispatch_async(queue) {
            val camera = AVCaptureDevice.defaultDeviceWithDeviceType(AVCaptureDeviceTypeBuiltInWideAngleCamera, AVMediaTypeVideo, AVCaptureDevicePositionBack)
                ?: AVCaptureDevice.defaultDeviceWithDeviceType(AVCaptureDeviceTypeBuiltInWideAngleCamera, AVMediaTypeVideo, AVCaptureDevicePositionFront)
                ?: AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
            val input = camera?.let { runCatching { AVCaptureDeviceInput.deviceInputWithDevice(it, null) }.getOrNull() }
            var opened = false
            if (input != null) {
                session.beginConfiguration()
                if (session.canSetSessionPreset(AVCaptureSessionPresetPhoto)) session.sessionPreset = AVCaptureSessionPresetPhoto
                if (session.canAddInput(input) && session.canAddOutput(output)) {
                    session.addInput(input)
                    session.addOutput(output)
                    opened = true
                }
                session.commitConfiguration()
                if (opened) session.startRunning()
            }
            runOnMain {
                device = camera.takeIf { opened }
                onOpened(device)
            }
        }
    }

    fun stop() {
        dispatch_async(queue) { if (session.isRunning()) session.stopRunning() }
    }

    fun capture(flash: FlashMode, onDone: (ByteArray?) -> Unit) {
        val settings = AVCapturePhotoSettings.photoSettingsWithFormat(mapOf<Any?, Any?>(AVVideoCodecKey to AVVideoCodecTypeJPEG))
        val wanted = when (flash) {
            FlashMode.OFF -> AVCaptureFlashModeOff
            FlashMode.ON -> AVCaptureFlashModeOn
            FlashMode.AUTO -> AVCaptureFlashModeAuto
        }
        if (output.supportedFlashModes.any { (it as? Number)?.toLong() == wanted }) settings.flashMode = wanted
        // What is framed is what is captured: the still carries the preview's orientation.
        output.connectionWithMediaType(AVMediaTypeVideo)?.rotateTo(view.rotationAngle())
        val delegate = PhotoDelegate { self, bytes ->
            delegates.remove(self)
            onDone(bytes)
        }
        delegates.add(delegate)
        output.capturePhotoWithSettings(settings, delegate)
    }

    /** AF + AE at a point of the preview (in view points). False when the camera can do neither. */
    fun focusAt(x: Double, y: Double): Boolean {
        val camera = device ?: return false
        val point = view.previewLayer.captureDevicePointOfInterestForPoint(CGPointMake(x, y))
        val canFocus = camera.focusPointOfInterestSupported && camera.isFocusModeSupported(AVCaptureFocusModeAutoFocus)
        val canExpose = camera.exposurePointOfInterestSupported && camera.isExposureModeSupported(AVCaptureExposureModeContinuousAutoExposure)
        if (!canFocus && !canExpose) return false
        if (!camera.lockForConfiguration(null)) return false
        if (canFocus) {
            camera.focusPointOfInterest = point
            camera.focusMode = AVCaptureFocusModeAutoFocus
        }
        if (canExpose) {
            camera.exposurePointOfInterest = point
            camera.exposureMode = AVCaptureExposureModeContinuousAutoExposure
        }
        camera.unlockForConfiguration()
        return true
    }

    /** Back to continuous autofocus on the whole frame, as before any tap. */
    fun resetFocus() {
        val camera = device ?: return
        if (!camera.isFocusModeSupported(AVCaptureFocusModeContinuousAutoFocus) || !camera.lockForConfiguration(null)) return
        if (camera.focusPointOfInterestSupported) camera.focusPointOfInterest = CGPointMake(0.5, 0.5)
        camera.focusMode = AVCaptureFocusModeContinuousAutoFocus
        camera.unlockForConfiguration()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
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
    val captured by rememberUpdatedState(onCaptured)
    val captureError by rememberUpdatedState(onCaptureError)
    val focus by rememberUpdatedState(onFocus)
    val longPress by rememberUpdatedState(onLongPress)
    val flashMode by rememberUpdatedState(flash)
    val camera = remember { IosCamera() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val disposed = remember { booleanArrayOf(false) }
    var focusJob by remember { mutableStateOf<Job?>(null) }

    // CAM-025: open while composed; the system suspends the session in the background and resumes it.
    DisposableEffect(camera) {
        disposed[0] = false
        camera.start { device ->
            if (disposed[0]) return@start
            if (device == null) {
                handle.available = false   // CAM-015
            } else {
                handle.hasFlash = device.hasFlash   // CAM-037
                handle.ready = true
            }
        }
        handle.shutter = {
            handle.capturing = true
            camera.capture(flashMode) { bytes ->
                if (!disposed[0]) {   // CAM-127: a photo arriving after release is discarded
                    handle.capturing = false
                    if (bytes != null) captured(bytes) else captureError()
                }
            }
        }
        onDispose {
            disposed[0] = true
            handle.shutter = null
            handle.capturing = false
            handle.hasFlash = false
            handle.ready = false
            camera.stop()
        }
    }

    Box(modifier) {
        // Not interactive: taps belong to the Compose layer above, which draws the reticle and controls.
        UIKitView(factory = { camera.view }, modifier = Modifier.fillMaxSize(), properties = UIKitInteropProperties(interactionMode = null))
        Box(
            Modifier.fillMaxSize().pointerInput(camera) {
                detectTapGestures(
                    onLongPress = { longPress() },
                    onTap = { position ->
                        // CAM-030…CAM-034: AF + AE at the tapped point through the preview layer's own transform.
                        if (!camera.focusAt((position.x / density).toDouble(), (position.y / density).toDouble())) return@detectTapGestures
                        focusJob?.cancel()   // a new tap replaces an in-flight request
                        focus(position, FocusOutcome.PENDING)
                        focusJob = scope.launch {
                            delay(150)
                            var waited = 150
                            while (camera.device?.isAdjustingFocus() == true && waited < 3000) {
                                delay(100)
                                waited += 100
                            }
                            focus(position, if (waited < 3000) FocusOutcome.FOCUSED else FocusOutcome.FAILED)
                            delay(5000)
                            camera.resetFocus()
                        }
                    },
                )
            },
        )
    }
}

// ---------------------------------------------------------------- pickers (CAM-016)

private class PhotoPickerDelegate(private val onPicked: (NSData) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, null)
        val provider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider ?: return
        // The encoded file as stored (HEIC, JPEG, PNG…), with its orientation metadata.
        provider.loadDataRepresentationForTypeIdentifier(UTTypeImage.identifier) { data, _ ->
            if (data != null) runOnMain { onPicked(data) }
        }
    }
}

private class FilePickerDelegate(private val onPicked: (NSData) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        // Picked as a copy inside the app's own container, so no security scope is needed.
        NSData.dataWithContentsOfURL(url)?.let(onPicked)
    }
}

private fun UIViewController.topPresented(): UIViewController {
    var top = this
    while (true) top = top.presentedViewController ?: return top
}

@Composable
actual fun rememberImagePickers(onPicked: (String) -> Unit): ImagePickers {
    val picked by rememberUpdatedState(onPicked)
    val controller = LocalUIViewController.current
    // The pickers hold their delegates weakly.
    val photoDelegate = remember { PhotoPickerDelegate { data -> picked(IosImages.put(data)) } }
    val fileDelegate = remember { FilePickerDelegate { data -> picked(IosImages.put(data)) } }
    return remember(controller) {
        ImagePickers(
            photos = {
                // PHPicker runs out of process and hands over the one chosen image: no library permission.
                val configuration = PHPickerConfiguration()
                configuration.filter = PHPickerFilter.imagesFilter
                configuration.selectionLimit = 1
                val picker = PHPickerViewController(configuration = configuration)
                picker.delegate = photoDelegate
                controller.topPresented().presentViewController(picker, true, null)
            },
            files = {
                val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeImage), asCopy = true)
                picker.delegate = fileDelegate
                picker.allowsMultipleSelection = false
                controller.topPresented().presentViewController(picker, true, null)
            },
        )
    }
}

/** CAM-075: add-only photo-library access is requested by the first save itself; nothing to ask beforehand. */
@Composable
actual fun rememberSaveAction(save: () -> Unit): () -> Unit = save

/** A mouse or trackpad attached to an iPad: Ctrl/Cmd + wheel zooms, the wheel alone pans. */
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
