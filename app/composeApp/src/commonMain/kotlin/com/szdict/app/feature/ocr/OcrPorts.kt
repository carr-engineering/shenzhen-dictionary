package com.szdict.app.feature.ocr

import androidx.compose.ui.graphics.ImageBitmap
import com.szdict.app.AppEnv
import com.szdict.recognition.OcrImage
import com.szdict.recognition.TextRecognizer

/** Where the image under review came from (CAM-005): decides the header action and whether it can be saved. */
enum class CaptureSource { CAMERA, GALLERY }

/**
 * The five ways an image reaches the OCR destination (CAM-130, CAM-002). They all go through the
 * same decode → review path ([OcrStateHolder.submit]); only the source and the error text differ.
 */
enum class ImageOrigin(val source: CaptureSource, val decodeError: String) {
    CAMERA(CaptureSource.CAMERA, "Could not process the captured photo."),
    PICK(CaptureSource.GALLERY, "Could not open that image."),
    SHARE(CaptureSource.GALLERY, "Could not open the shared image."),
    DROP(CaptureSource.GALLERY, "Could not open the dropped image."),
    PASTE(CaptureSource.GALLERY, "Could not open the pasted image."),
}

/** An image not yet decoded. */
sealed interface ImageRef {
    /** A platform content reference (Android: a `content://` or `file://` URI string). */
    data class Uri(val uri: String) : ImageRef

    /** The camera's encoded output (JPEG with its orientation metadata), kept for saving (CAM-074). */
    class Encoded(val bytes: ByteArray) : ImageRef
}

class ImageInput(val origin: ImageOrigin, val ref: ImageRef)

/**
 * The upright working image (CAM-026): EXIF resolved, longest side at most [MAX_SIDE] px, software
 * pixels. All recognition, overlay and hit-testing coordinates are in its pixel grid.
 */
interface WorkingImage {
    val width: Int
    val height: Int

    /** For display; null only in host tests. */
    val bitmap: ImageBitmap?

    /** Copies the pixels for the recogniser. Call off the main thread. */
    fun toOcrImage(): OcrImage

    /** Frees the pixels (CAM-093). The image must not be drawn afterwards. */
    fun release()

    companion object {
        const val MAX_SIDE = 2560
    }
}

/**
 * A recogniser model that is not installed with the app but fetched on first use — Web only
 * (CAM-134, ARCH-093, GLB-035). Mobile platforms never report one.
 */
sealed interface ModelDownload {
    /** Downloading ([fraction] in `0..1`), or loading the stored model ([fraction] null). */
    data class InProgress(val fraction: Float?, val totalBytes: Long) : ModelDownload

    /** Neither stored nor reachable; [totalBytes] is null when the size is not known either. */
    data class Failed(val totalBytes: Long?) : ModelDownload
}

/**
 * Platform side of the OCR destination that needs no UI (CAM-110): recogniser, image decoding,
 * photo-library writer and the image clipboard. Camera, pickers and the drop target are
 * composables (`OcrPlatformUi.kt`). Registered in `AppEnv.services` under [KEY].
 */
interface OcrPlatform {
    /** A new on-device recogniser; the caller closes it (CAM-092). */
    fun createRecognizer(): TextRecognizer

    /** Decodes [ref] upright and capped (CAM-041), off the caller's thread. Throws when it is not a decodable image. */
    suspend fun decode(ref: ImageRef): WorkingImage

    /** Writes the original capture to the user's photo library (CAM-075). Returns false on failure. */
    suspend fun savePhoto(jpeg: ByteArray): Boolean

    /** Reference to an image on the clipboard, read only on an explicit paste (CAM-132); null when there is none. */
    fun clipboardImage(): String?

    /** State of a recogniser model fetched on first use (Compose-observable); null where it ships with the app, and once ready. */
    val modelDownload: ModelDownload? get() = null

    /** The destination was opened: fetch and load the recogniser model if that is still to do (CAM-134). */
    fun prepareRecognizer() {}

    /** Suspends until the recogniser can run; never part of the recognition timeout (CAM-051). */
    suspend fun awaitRecognizer() {}

    /** `Retry` after a failed model download (CAM-134). */
    fun retryModelDownload() {}

    /**
     * True where pasted images arrive through a platform event ([setPasteListener]; Web: the
     * document `paste` event) rather than by reading the clipboard on Ctrl/Cmd+V (CAM-132).
     */
    val pasteByEvent: Boolean get() = false

    /** Receives each paste while the destination is shown: an image reference, or null when the paste holds no image. */
    fun setPasteListener(listener: ((String?) -> Unit)?) {}

    /** Inline confirmation after a save (CAM-072). */
    val savedMessage: String get() = "Saved to Gallery"

    companion object {
        const val KEY = "ocr.platform"
        fun of(env: AppEnv): OcrPlatform? = env.service(KEY)
    }
}

/** Capture illumination (CAM-036); session-only (CAM-038). */
enum class FlashMode(val label: String) {
    OFF("Flash off"), ON("Flash on"), AUTO("Flash auto");

    fun next(): FlashMode = entries[(ordinal + 1) % entries.size]
}

enum class OcrViewMode { IMAGE, TEXT }

enum class SaveState { IDLE, SAVING, SAVED, FAILED }
