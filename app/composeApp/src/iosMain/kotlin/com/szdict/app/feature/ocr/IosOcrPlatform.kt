@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.szdict.app.feature.ocr

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.szdict.app.platform.IosBundle
import com.szdict.app.platform.toByteArray
import com.szdict.app.platform.toNSData
import com.szdict.recognition.OcrImage
import com.szdict.recognition.Recognizers
import com.szdict.recognition.TextRecognizer
import com.szdict.recognition.toOcrImage
import kotlin.coroutines.resume
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringRef
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceShouldCacheImmediately
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIPasteboard

/**
 * Encoded images that reached the app through a picker, a drop or the pasteboard, held in memory
 * until the OCR destination decodes them (nothing is written to storage, GLB-092). A reference is
 * the string the shared code passes around as [ImageRef.Uri].
 */
internal object IosImages {
    private const val PREFIX = "szdict-image:"

    /** The pasteboard's image, read only when the reference is decoded (CAM-132: on an explicit paste). */
    const val PASTEBOARD = "szdict-pasteboard:image"

    private val lock = platform.Foundation.NSLock()
    private val held = LinkedHashMap<String, NSData>()
    private var next = 0L

    fun put(data: NSData): String {
        lock.lock()
        try {
            val ref = PREFIX + (next++)
            held[ref] = data
            // Only the newest few are ever decoded; older ones were replaced or abandoned.
            while (held.size > 3) held.remove(held.keys.first())
            return ref
        } finally {
            lock.unlock()
        }
    }

    fun data(ref: String): NSData? = when {
        ref == PASTEBOARD -> UIPasteboard.generalPasteboard.image?.let { UIImagePNGRepresentation(it) }
        ref.startsWith(PREFIX) -> {
            lock.lock()
            try {
                held[ref]
            } finally {
                lock.unlock()
            }
        }
        ref.startsWith("file:") -> NSURL.URLWithString(ref)?.let { NSData.dataWithContentsOfURL(it) }
        else -> null
    }
}

/** iOS side of the OCR ports (CAM-110): bundled recogniser, ImageIO decoding, photo library, image pasteboard. */
class IosOcrPlatform : OcrPlatform {
    override fun createRecognizer(): TextRecognizer = Recognizers.text(IosBundle.path("models"), alternatives = 8)

    /** CAM-026, CAM-041: upright (EXIF resolved by ImageIO), longest side at most 2560 px, opaque pixels on white. */
    override suspend fun decode(ref: ImageRef): WorkingImage {
        val data = when (ref) {
            is ImageRef.Encoded -> ref.bytes.toNSData()
            // The pasteboard is read on the main thread.
            is ImageRef.Uri -> withContext(Dispatchers.Main) { IosImages.data(ref.uri) } ?: error("Cannot open image")
        }
        return withContext(Dispatchers.Default) { decodeUpright(data) }
    }

    /** CAM-075: the original capture goes to the photo library through add-only access (asked on first save). */
    override suspend fun savePhoto(jpeg: ByteArray): Boolean = suspendCancellableCoroutine { cont ->
        PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
            if (status != PHAuthorizationStatusAuthorized && status != PHAuthorizationStatusLimited) {
                if (cont.isActive) cont.resume(false)
            } else {
                val data = jpeg.toNSData()
                PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                    { PHAssetCreationRequest.creationRequestForAsset().addResourceWithType(PHAssetResourceTypePhoto, data, null) },
                    { saved, _ -> if (cont.isActive) cont.resume(saved) },
                )
            }
        }
    }

    /** `hasImages` reads nothing, so asking shows no paste notice; the image is read when the reference is decoded. */
    override fun clipboardImage(): String? =
        if (runCatching { UIPasteboard.generalPasteboard.hasImages }.getOrDefault(false)) IosImages.PASTEBOARD else null
}

@Suppress("UNCHECKED_CAST")
private fun decodeUpright(data: NSData): WorkingImage {
    fun key(ref: CFStringRef?): Any? = CFBridgingRelease(CFRetain(ref))
    val options: Map<Any?, Any?> = mapOf(
        key(kCGImageSourceCreateThumbnailFromImageAlways) to true,
        key(kCGImageSourceCreateThumbnailWithTransform) to true,
        key(kCGImageSourceShouldCacheImmediately) to true,
        key(kCGImageSourceThumbnailMaxPixelSize) to WorkingImage.MAX_SIDE,
    )
    val cfData = CFBridgingRetain(data) as CFDataRef?
    val cfOptions = CFBridgingRetain(options) as CFDictionaryRef?
    val image = try {
        val source = CGImageSourceCreateWithData(cfData, null) ?: error("Not an image")
        try {
            // Subsampled while decoding, never upscaled; the EXIF orientation is applied to the pixels.
            CGImageSourceCreateThumbnailAtIndex(source, 0u, cfOptions) ?: error("Cannot decode image")
        } finally {
            CFRelease(source)
        }
    } finally {
        CFRelease(cfData)
        CFRelease(cfOptions)
    }
    try {
        val width = CGImageGetWidth(image).toInt()
        val height = CGImageGetHeight(image).toInt()
        require(width > 0 && height > 0) { "Not an image" }
        // The displayed copy: opaque BGRA on white, which Skia takes as it is.
        val pixels = ByteArray(width * height * 4)
        pixels.usePinned { pinned ->
            val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
            val context = CGBitmapContextCreate(
                pinned.addressOf(0), width.convert(), height.convert(), 8u, (width * 4).convert(), space,
                CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value or kCGBitmapByteOrder32Little,
            )
            CGColorSpaceRelease(space)
            if (context == null) error("Cannot allocate image")
            val rect = CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble())
            CGContextSetRGBFillColor(context, 1.0, 1.0, 1.0, 1.0)
            CGContextFillRect(context, rect)
            CGContextDrawImage(context, rect, image)
            CGContextRelease(context)
        }
        val bitmap = Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), pixels, width * 4).toComposeImageBitmap()
        return DecodedWorkingImage(image, width, height, bitmap)
    } catch (e: Throwable) {
        CGImageRelease(image)
        throw e
    }
}

/** Keeps the decoded, upright bitmap for the recogniser; the displayed [bitmap] is its own copy. */
private class DecodedWorkingImage(image: CGImageRef, override val width: Int, override val height: Int, override val bitmap: ImageBitmap?) : WorkingImage {
    private val lock = platform.Foundation.NSLock()
    private var image: CGImageRef? = image

    // The recognition module's own conversion: sRGB, composited on white.
    override fun toOcrImage(): OcrImage {
        lock.lock()
        try {
            return (image ?: error("Image released")).toOcrImage()
        } finally {
            lock.unlock()
        }
    }

    override fun release() {
        lock.lock()
        try {
            image?.let { CGImageRelease(it) }
            image = null
        } finally {
            lock.unlock()
        }
    }
}

internal fun NSData.bytesOrNull(): ByteArray? = runCatching { toByteArray() }.getOrNull()
