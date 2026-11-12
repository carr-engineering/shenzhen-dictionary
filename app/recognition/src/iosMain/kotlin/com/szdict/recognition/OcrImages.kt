@file:OptIn(ExperimentalForeignApi::class)

package com.szdict.recognition

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.UIKit.UIImage
import platform.UIKit.UIImageOrientation

/**
 * Copies the image's pixels into the platform-neutral [OcrImage], upright: the image's
 * `imageOrientation` (as set by the camera) is applied. Transparency is composited on white, the
 * page colour the pipeline assumes; colours are converted to sRGB.
 */
fun UIImage.toOcrImage(): OcrImage {
    val image = CGImage ?: throw IllegalArgumentException("Image has no bitmap")
    return image.toOcrImage(imageOrientation)
}

/** Copies a bitmap into an [OcrImage], turning it upright from [orientation]. */
fun CGImageRef.toOcrImage(orientation: UIImageOrientation = UIImageOrientation.UIImageOrientationUp): OcrImage {
    val w = CGImageGetWidth(this).toInt()
    val h = CGImageGetHeight(this).toInt()
    require(w > 0 && h > 0) { "Image must not be empty" }
    // Stored pixels, top row first, as opaque RGBX.
    val rgbx = ByteArray(w * h * 4)
    rgbx.usePinned { pinned ->
        val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
        val context = CGBitmapContextCreate(
            pinned.addressOf(0), w.toULong(), h.toULong(), 8u, (w * 4).toULong(), space,
            CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
        )
        CGColorSpaceRelease(space)
        checkNotNull(context) { "Cannot create a bitmap context" }
        val bounds = CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble())
        CGContextSetRGBFillColor(context, 1.0, 1.0, 1.0, 1.0)
        CGContextFillRect(context, bounds)
        CGContextDrawImage(context, bounds, this)
        CGContextRelease(context)
    }
    fun stored(row: Int, column: Int): Int {
        val p = (row * w + column) * 4
        return (0xFF shl 24) or ((rgbx[p].toInt() and 0xFF) shl 16) or ((rgbx[p + 1].toInt() and 0xFF) shl 8) or (rgbx[p + 2].toInt() and 0xFF)
    }
    // The orientation says how the stored bitmap must be turned to stand upright (EXIF semantics).
    return when (orientation) {
        UIImageOrientation.UIImageOrientationDown -> OcrImage(w, h, IntArray(w * h) { stored(h - 1 - it / w, w - 1 - it % w) })
        UIImageOrientation.UIImageOrientationUpMirrored -> OcrImage(w, h, IntArray(w * h) { stored(it / w, w - 1 - it % w) })
        UIImageOrientation.UIImageOrientationDownMirrored -> OcrImage(w, h, IntArray(w * h) { stored(h - 1 - it / w, it % w) })
        // Quarter turns: the upright image is h wide and w high.
        UIImageOrientation.UIImageOrientationRight -> OcrImage(h, w, IntArray(w * h) { stored(h - 1 - it % h, it / h) })
        UIImageOrientation.UIImageOrientationLeft -> OcrImage(h, w, IntArray(w * h) { stored(it % h, w - 1 - it / h) })
        UIImageOrientation.UIImageOrientationLeftMirrored -> OcrImage(h, w, IntArray(w * h) { stored(it % h, it / h) })
        UIImageOrientation.UIImageOrientationRightMirrored -> OcrImage(h, w, IntArray(w * h) { stored(h - 1 - it % h, w - 1 - it / h) })
        else -> OcrImage(w, h, IntArray(w * h) { stored(it / w, it % w) })
    }
}
