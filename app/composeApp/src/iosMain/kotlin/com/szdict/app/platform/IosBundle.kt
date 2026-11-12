@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.szdict.app.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.posix.memcpy

/**
 * The files staged by `:composeApp:stageIosAssets` and copied by Xcode into the app bundle as the
 * folder `assets/` (ARCH-141). Everything is read in place: the bundle is read-only on a device.
 */
object IosBundle {
    /** Bundle-relative name of the staged folder. */
    const val ASSETS = "assets"

    val root: String by lazy { (NSBundle.mainBundle.resourcePath ?: NSBundle.mainBundle.bundlePath) + "/" + ASSETS }

    /** Absolute path of [relative] inside the staged folder (`data`, `opencc/s2t.json`, `models`…). */
    fun path(relative: String): String = "$root/$relative"

    fun exists(relative: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path(relative))

    /** The voice model directory when all its files are bundled, else null (VOICE-095). */
    fun voiceModelDir(): String? =
        "voice-model".takeIf { dir -> listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt").all { exists("$dir/$it") } }?.let(::path)
}

internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

internal fun ByteArray.toNSData(): NSData = memScoped {
    if (isEmpty()) NSData() else NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong())
}

/** The bytes of the file at [path], or null when it cannot be read. */
internal fun readFileBytes(path: String): ByteArray? = NSFileManager.defaultManager.contentsAtPath(path)?.toByteArray()
