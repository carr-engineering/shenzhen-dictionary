@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.szdict.app.feature.ocr

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
import platform.AVFoundation.AVCaptureDeviceDiscoverySession
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInDualCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInDualWideCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInTelephotoCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInTripleCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInUltraWideCamera
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
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
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
import platform.AVFoundation.constituentDevices
import platform.AVFoundation.deviceType
import platform.AVFoundation.hasTorch
import platform.AVFoundation.isTorchModeSupported
import platform.AVFoundation.maxAvailableVideoZoomFactor
import platform.AVFoundation.minAvailableVideoZoomFactor
import platform.AVFoundation.position
import platform.AVFoundation.torchMode
import platform.AVFoundation.videoZoomFactor
import platform.AVFoundation.virtualDevice
import platform.AVFoundation.virtualDeviceSwitchOverVideoZoomFactors
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
import platform.Foundation.NSNumber
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
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIColor
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import kotlin.math.PI
import kotlin.math.tan

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

    /** The front camera is open: its sensor turns the other way, so the landscape angles swap. */
    var front = false

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
        UIInterfaceOrientationLandscapeRight -> if (front) 180.0 else 0.0
        UIInterfaceOrientationLandscapeLeft -> if (front) 0.0 else 180.0
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

private val VirtualDeviceTypes = listOfNotNull(
    AVCaptureDeviceTypeBuiltInTripleCamera,
    AVCaptureDeviceTypeBuiltInDualWideCamera,
    AVCaptureDeviceTypeBuiltInDualCamera,
)

private fun AVCaptureDevice.isType(type: String?) = type != null && deviceType == type

/** Zoom of [device] relative to [wide] from their horizontal fields of view (the discrete-lens fallback of CAM-028). */
private fun fovFactor(wide: AVCaptureDevice, device: AVCaptureDevice): Float {
    val w = wide.activeFormat.videoFieldOfView.toDouble()
    val d = device.activeFormat.videoFieldOfView.toDouble()
    if (w <= 0.0 || d <= 0.0) return 1f
    return (tan(w * PI / 360.0) / tan(d * PI / 360.0)).toFloat()
}

/** Where each lens of the menu lives: a zoom factor of the open (virtual) device, or a separate device. */
private class IosLens(val device: AVCaptureDevice, val deviceZoom: Double, val factor: Float)

/**
 * One AVFoundation capture session: rear camera, photo preset (4:3), JPEG stills (CAM-020, CAM-022).
 * CAM-028: the back virtual device (triple / dual-wide / dual) is preferred, so the lenses are its zoom
 * factors (display factor = zoom factor / the wide camera's switch-over factor, as in the Camera app);
 * without one, the separate back cameras are offered, each labelled by its field of view.
 */
private class IosCamera {
    val session = AVCaptureSession()
    val view = CameraPreviewView(session)
    private val output = AVCapturePhotoOutput()
    private val queue = dispatch_queue_create("com.szdict.app.camera", null)
    private val delegates = HashSet<PhotoDelegate>()   // a capture's delegate lives until its photo arrives
    private var input: AVCaptureDeviceInput? = null
    private val lensMap = HashMap<String, IosLens>()
    var device: AVCaptureDevice? = null
        private set

    /** The lens menu; filled by [start]. */
    var lenses: List<CameraLens> = emptyList()
        private set

    /** Display factor of the open device's own zoom factor 1. */
    private var deviceFactor = 1f

    /** Device zoom factor of `1×` on a virtual device. */
    private var base = 1.0

    /** Opens the camera off the main thread; [onOpened] gets the device, or null when there is none (CAM-015). */
    fun start(onOpened: (AVCaptureDevice?) -> Unit) {
        dispatch_async(queue) {
            val types = VirtualDeviceTypes + listOfNotNull(
                AVCaptureDeviceTypeBuiltInWideAngleCamera,
                AVCaptureDeviceTypeBuiltInUltraWideCamera,
                AVCaptureDeviceTypeBuiltInTelephotoCamera,
            )
            val found = runCatching {
                AVCaptureDeviceDiscoverySession.discoverySessionWithDeviceTypes(types, AVMediaTypeVideo, AVCaptureDevicePositionBack)
                    .devices.filterIsInstance<AVCaptureDevice>()
            }.getOrDefault(emptyList())
            val virtual = VirtualDeviceTypes.firstNotNullOfOrNull { type -> found.firstOrNull { it.isType(type) } }
            val wide = found.firstOrNull { it.isType(AVCaptureDeviceTypeBuiltInWideAngleCamera) }
            val camera = virtual ?: wide
                ?: AVCaptureDevice.defaultDeviceWithDeviceType(AVCaptureDeviceTypeBuiltInWideAngleCamera, AVMediaTypeVideo, AVCaptureDevicePositionFront)
                ?: AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
            lensMap.clear()
            if (virtual != null) {
                val hasUltraWide = virtual.constituentDevices.any { (it as? AVCaptureDevice)?.isType(AVCaptureDeviceTypeBuiltInUltraWideCamera) == true }
                val switchOvers = virtual.virtualDeviceSwitchOverVideoZoomFactors.mapNotNull { (it as? NSNumber)?.doubleValue?.toFloat() }
                val (wideZoom, steps) = virtualDeviceSteps(hasUltraWide, switchOvers, virtual.maxAvailableVideoZoomFactor.toFloat())
                base = wideZoom.toDouble()
                lenses = steps.map { step -> CameraLens("zoom@$step", step).also { lensMap[it.id] = IosLens(virtual, step * base, step) } }
            } else if (wide != null) {
                base = 1.0
                val others = found.filter { it !== wide && !it.isType(AVCaptureDeviceTypeBuiltInWideAngleCamera) }
                    .map { other ->
                        val raw = fovFactor(wide, other)
                        other.uniqueID to (if (raw < 0.9f) raw else snapLensFactor(raw))
                    }
                lenses = lensOptions(listOf(1f), others, mainId = "wide")
                for (lens in lenses) {
                    val other = found.firstOrNull { it.uniqueID == lens.id }
                    lensMap[lens.id] = if (other != null) IosLens(other, 1.0, lens.factor ?: 1f) else IosLens(wide, (lens.factor ?: 1f).toDouble(), 1f)
                }
            } else {
                lenses = emptyList()
            }
            // CAM-022: the front camera is an entry of the same menu, after the rear lenses.
            val frontCamera = AVCaptureDevice.defaultDeviceWithDeviceType(AVCaptureDeviceTypeBuiltInWideAngleCamera, AVMediaTypeVideo, AVCaptureDevicePositionFront)
            if (frontCamera != null && camera !== frontCamera) {
                val entry = frontLens("front")
                lenses = withFacingNames(lenses + entry)   // one rear lens + front: `Back` / `Front`
                lensMap[entry.id] = IosLens(frontCamera, 1.0, 1f)
            }
            val newInput = camera?.let { runCatching { AVCaptureDeviceInput.deviceInputWithDevice(it, null) }.getOrNull() }
            var opened = false
            if (newInput != null) {
                session.beginConfiguration()
                if (session.canSetSessionPreset(AVCaptureSessionPresetPhoto)) session.sessionPreset = AVCaptureSessionPresetPhoto
                if (session.canAddInput(newInput) && session.canAddOutput(output)) {
                    session.addInput(newInput)
                    session.addOutput(output)
                    input = newInput
                    opened = true
                }
                session.commitConfiguration()
                if (opened) {
                    // Opens at `1×`: on a virtual device with an ultra-wide, zoom factor 1 is the ultra-wide.
                    if (camera.virtualDevice && camera.lockForConfiguration(null)) {
                        camera.videoZoomFactor = base.coerceIn(camera.minAvailableVideoZoomFactor, camera.maxAvailableVideoZoomFactor)
                        camera.unlockForConfiguration()
                    }
                    session.startRunning()
                }
            }
            runOnMain {
                device = camera.takeIf { opened }
                deviceFactor = 1f
                view.front = camera?.position == AVCaptureDevicePositionFront
                onOpened(device)
            }
        }
    }

    fun stop() {
        dispatch_async(queue) { if (session.isRunning()) session.stopRunning() }
    }

    /** CAM-037: what the active camera can do besides `Off`; the ultra-wide, for one, may have no flash. */
    fun flashModes(): Set<FlashMode> {
        val camera = device ?: return emptySet()
        val supported = output.supportedFlashModes.mapNotNull { (it as? NSNumber)?.longValue }
        return buildSet {
            if (AVCaptureFlashModeAuto in supported) add(FlashMode.AUTO)
            if (AVCaptureFlashModeOn in supported) add(FlashMode.ON)
            if (camera.hasTorch && camera.isTorchModeSupported(AVCaptureTorchModeOn)) add(FlashMode.TORCH)
        }
    }

    /** CAM-039: the continuous light of `Flashlight`; off in every other mode and whenever the camera is released. */
    fun setTorch(on: Boolean) {
        val camera = device ?: return
        if (!camera.hasTorch) return
        val mode = if (on) AVCaptureTorchModeOn else AVCaptureTorchModeOff
        if (camera.torchMode == mode || !camera.isTorchModeSupported(mode) || !camera.lockForConfiguration(null)) return
        camera.torchMode = mode
        camera.unlockForConfiguration()
    }

    /** Pinch: continuous zoom across the lenses of a virtual device (or within one lens), up to 10× or twice the longest lens. */
    fun zoomBy(scale: Float) {
        val camera = device ?: return
        val longest = lenses.maxOfOrNull { it.factor ?: 1f } ?: 1f
        val limit = minOf(camera.maxAvailableVideoZoomFactor, base * maxOf(10.0, longest * 2.0) / deviceFactor)
        val target = (camera.videoZoomFactor * scale).coerceIn(camera.minAvailableVideoZoomFactor, maxOf(limit, camera.minAvailableVideoZoomFactor))
        if (!camera.lockForConfiguration(null)) return
        camera.videoZoomFactor = target
        camera.unlockForConfiguration()
    }

    /** Switches to a lens of [lenses]; [onSwitched] runs on the main thread once the camera is in place. */
    fun select(id: String, onSwitched: () -> Unit) {
        val lens = lensMap[id] ?: return
        val current = device ?: return
        if (lens.device === current) {
            if (current.lockForConfiguration(null)) {
                current.videoZoomFactor = lens.deviceZoom.coerceIn(current.minAvailableVideoZoomFactor, current.maxAvailableVideoZoomFactor)
                current.unlockForConfiguration()
            }
            onSwitched()
            return
        }
        // A separate camera: swap the session's input.
        dispatch_async(queue) {
            val newInput = runCatching { AVCaptureDeviceInput.deviceInputWithDevice(lens.device, null) }.getOrNull() ?: return@dispatch_async
            session.beginConfiguration()
            input?.let { session.removeInput(it) }
            val switched = session.canAddInput(newInput)
            if (switched) {
                session.addInput(newInput)
                input = newInput
            } else {
                input?.let { session.addInput(it) }
            }
            session.commitConfiguration()
            // The lens's own zoom on the new camera (a virtual device's `1×` is not its zoom factor 1).
            if (switched && lens.device.lockForConfiguration(null)) {
                lens.device.videoZoomFactor = lens.deviceZoom.coerceIn(lens.device.minAvailableVideoZoomFactor, lens.device.maxAvailableVideoZoomFactor)
                lens.device.unlockForConfiguration()
            }
            runOnMain {
                if (switched) {
                    device = lens.device
                    deviceFactor = lens.factor
                    view.front = lens.device.position == AVCaptureDevicePositionFront
                    view.setNeedsLayout()
                }
                onSwitched()
            }
        }
    }

    fun capture(flash: FlashMode, onDone: (ByteArray?) -> Unit) {
        val settings = AVCapturePhotoSettings.photoSettingsWithFormat(mapOf<Any?, Any?>(AVVideoCodecKey to AVVideoCodecTypeJPEG))
        val wanted = when (captureFlash(flash)) {
            FlashMode.ON -> AVCaptureFlashModeOn
            FlashMode.AUTO -> AVCaptureFlashModeAuto
            else -> AVCaptureFlashModeOff
        }
        if (output.supportedFlashModes.any { (it as? Number)?.toLong() == wanted }) settings.flashMode = wanted
        // What is framed is what is captured: the still carries the preview's orientation.
        output.connectionWithMediaType(AVMediaTypeVideo)?.let { connection ->
            connection.rotateTo(view.rotationAngle())
            // The preview of the front camera is mirrored, as the system's is; the photo read for OCR is not.
            if (connection.isVideoMirroringSupported()) {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.videoMirrored = false
            }
        }
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
    hdr: Boolean,
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
    var opened by remember { mutableStateOf(false) }

    // CAM-025: open while composed; the system suspends the session in the background and resumes it.
    DisposableEffect(camera) {
        disposed[0] = false
        camera.start { device ->
            if (disposed[0]) return@start
            if (device == null) {
                handle.available = false   // CAM-015
            } else {
                handle.flashModes = camera.flashModes()   // CAM-037
                handle.lenses = camera.lenses   // CAM-028
                handle.lens = camera.lenses.firstOrNull { !it.front && it.factor == 1f }?.id
                handle.ready = true
                opened = true
            }
        }
        handle.lensSwitch = { lens ->
            camera.select(lens.id) {
                if (!disposed[0]) {
                    handle.flashModes = camera.flashModes()
                    camera.setTorch(flashMode == FlashMode.TORCH)
                }
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
        // The torch goes out while the app is in the background; it is relit on return (CAM-038).
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
            if (!disposed[0]) camera.setTorch(flashMode == FlashMode.TORCH)
        }
        val resignObserver = NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) {
            camera.setTorch(false)
        }
        onDispose {
            disposed[0] = true
            NSNotificationCenter.defaultCenter.removeObserver(observer)
            NSNotificationCenter.defaultCenter.removeObserver(resignObserver)
            camera.setTorch(false)   // CAM-039
            handle.reset()
            camera.stop()
        }
    }

    // CAM-038: the session's mode is applied on every change and once the camera is open.
    LaunchedEffect(camera, flash, opened) {
        if (opened) camera.setTorch(flash == FlashMode.TORCH)
    }

    Box(modifier) {
        // Not interactive: taps belong to the Compose layer above, which draws the reticle and controls.
        UIKitView(factory = { camera.view }, modifier = Modifier.fillMaxSize(), properties = UIKitInteropProperties(interactionMode = null))
        Box(
            Modifier.fillMaxSize()
                .pointerInput(camera) {
                    // CAM-028: pinch zooms continuously (across a virtual device's lenses); the chip keeps the selected camera.
                    detectTransformGestures { _, _, zoomChange, _ ->
                        if (zoomChange != 1f) camera.zoomBy(zoomChange)
                    }
                }
                .pointerInput(camera) {
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

private fun UIViewController.topPresented(): UIViewController {
    var top = this
    while (true) top = top.presentedViewController ?: return top
}

@Composable
actual fun rememberImagePicker(onPicked: (String) -> Unit): () -> Unit {
    val picked by rememberUpdatedState(onPicked)
    val controller = LocalUIViewController.current
    // PHPicker runs out of process and holds its delegate weakly.
    val photoDelegate = remember { PhotoPickerDelegate { data -> picked(IosImages.put(data)) } }
    return remember(controller, photoDelegate) {
        {
            val configuration = PHPickerConfiguration()
            configuration.filter = PHPickerFilter.imagesFilter
            configuration.selectionLimit = 1
            val picker = PHPickerViewController(configuration = configuration)
            picker.delegate = photoDelegate
            controller.topPresented().presentViewController(picker, true, null)
        }
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
