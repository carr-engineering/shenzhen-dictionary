@file:OptIn(ExperimentalWasmJsInterop::class, UnsafeWasmMemoryApi::class)

package com.szdict.app.feature.ocr

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.szdict.recognition.OcrImage
import kotlinx.coroutines.await
import org.jetbrains.skia.Image
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise
import kotlin.math.max
import kotlin.math.min
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

// Bytes cross the JS boundary through the module's linear memory (`wasmExports.memory`, in scope
// of every js() body): Kotlin arrays live on the Wasm GC heap, which JS cannot address, and
// element-wise interop is far too slow for images.

private fun jsByteLength(array: JsAny): Int = js("array.byteLength")

private fun jsCopyToMemory(array: JsAny, address: Int): Unit = js(
    "{ new Uint8Array(wasmExports.memory.buffer, address, array.byteLength).set(new Uint8Array(array.buffer, array.byteOffset, array.byteLength)); }"
)

private fun jsBlobFromMemory(address: Int, size: Int, type: String): JsAny =
    js("new Blob([new Uint8Array(wasmExports.memory.buffer, address, size).slice()], { type: type })")

private fun jsBlobBytes(blob: JsAny): Promise<JsAny> = js("blob.arrayBuffer().then((buffer) => new Uint8Array(buffer))")

/** The bytes of a JS typed array. */
internal fun typedArrayBytes(array: JsAny): ByteArray {
    val size = jsByteLength(array)
    val out = ByteArray(size)
    if (size == 0) return out
    withScopedMemoryAllocator { allocator ->
        val base = allocator.allocate(size)
        jsCopyToMemory(array, base.address.toInt())
        for (i in 0 until size) out[i] = (base + i).loadByte()
    }
    return out
}

internal suspend fun blobBytes(blob: JsAny): ByteArray = typedArrayBytes(jsBlobBytes(blob).await())

internal fun blobOf(bytes: ByteArray, type: String): JsAny = withScopedMemoryAllocator { allocator ->
    val base = allocator.allocate(max(1, bytes.size))
    for (i in bytes.indices) (base + i).storeByte(bytes[i])
    jsBlobFromMemory(base.address.toInt(), bytes.size, type)
}

// ---- image references: the shared code passes images around as strings (CAM-130) ----

private fun jsRegister(blob: JsAny): String = js(
    "{ const store = globalThis.__szdictImages || (globalThis.__szdictImages = { next: 0, items: new Map() }); const key = 'szdict-image:' + (++store.next); store.items.set(key, blob); return key; }"
)

private fun jsTake(key: String): JsAny? = js(
    "{ const store = globalThis.__szdictImages; const blob = store ? store.items.get(key) : null; if (store) store.items.delete(key); return blob || null; }"
)

/** A picked, dropped or pasted file, held until it is decoded; the key stands in for a content URI. */
internal object WebImageRefs {
    fun register(blob: JsAny): String = jsRegister(blob)

    fun take(key: String): JsAny? = jsTake(key)
}

// ---- decoding (CAM-026, CAM-041) ----

// Upright (EXIF applied by the browser's decoder), longest side capped, composited on white; kept
// as a CPU canvas for the recogniser, plus an encoded copy for display (lossless when small).
private fun jsDecode(blob: JsAny, maxSide: Int): Promise<JsAny> = js(
    """(async () => {
        const bitmap = await createImageBitmap(blob, { imageOrientation: 'from-image' });
        try {
            const longest = Math.max(bitmap.width, bitmap.height);
            if (!(longest > 0)) throw new Error('Not an image');
            const scale = longest > maxSide ? maxSide / longest : 1;
            const width = Math.max(1, Math.round(bitmap.width * scale));
            const height = Math.max(1, Math.round(bitmap.height * scale));
            const offscreen = typeof OffscreenCanvas !== 'undefined';
            const canvas = offscreen ? new OffscreenCanvas(width, height) : Object.assign(document.createElement('canvas'), { width: width, height: height });
            const context = canvas.getContext('2d', { willReadFrequently: true });
            context.fillStyle = '#ffffff';
            context.fillRect(0, 0, width, height);
            context.imageSmoothingEnabled = scale !== 1;
            context.imageSmoothingQuality = 'high';
            context.drawImage(bitmap, 0, 0, width, height);
            const type = width * height <= 1048576 ? 'image/png' : 'image/jpeg';
            const shown = offscreen ? await canvas.convertToBlob({ type: type, quality: 0.92 }) : await new Promise((resolve) => canvas.toBlob(resolve, type, 0.92));
            return { canvas: canvas, context: context, width: width, height: height, display: new Uint8Array(await shown.arrayBuffer()) };
        } finally {
            bitmap.close();
        }
    })()"""
)

private fun jsWidth(image: JsAny): Int = js("image.width")

private fun jsHeight(image: JsAny): Int = js("image.height")

private fun jsDisplay(image: JsAny): JsAny = js("image.display")

private fun jsDropDisplay(image: JsAny): Unit = js("{ image.display = null; }")

private fun jsRows(image: JsAny, y: Int, rows: Int): JsAny = js("image.context.getImageData(0, y, image.width, rows).data")

private fun jsRelease(image: JsAny): Unit = js("{ image.canvas.width = 0; image.canvas.height = 0; image.canvas = null; image.context = null; }")

/** Decodes [blob] into the upright working image; throws when it is not a decodable image. */
internal suspend fun decodeWorkingImage(blob: JsAny): WorkingImage {
    val decoded = jsDecode(blob, WorkingImage.MAX_SIDE).await<JsAny>()
    val shown = try {
        Image.makeFromEncoded(typedArrayBytes(jsDisplay(decoded))).toComposeImageBitmap()
    } catch (e: Throwable) {
        jsRelease(decoded)
        throw e
    }
    jsDropDisplay(decoded)
    return WebWorkingImage(decoded, jsWidth(decoded), jsHeight(decoded), shown)
}

private class WebWorkingImage(private var source: JsAny?, override val width: Int, override val height: Int, override val bitmap: ImageBitmap?) : WorkingImage {
    /** Reads the canvas in bands so the transfer buffer stays small whatever the image size. */
    override fun toOcrImage(): OcrImage {
        val image = source ?: error("Image released")
        val argb = IntArray(width * height)
        val band = max(1, min(height, BAND_BYTES / (width * 4)))
        withScopedMemoryAllocator { allocator ->
            val base = allocator.allocate(band * width * 4)
            var y = 0
            var out = 0
            while (y < height) {
                val rows = min(band, height - y)
                jsCopyToMemory(jsRows(image, y, rows), base.address.toInt())
                for (i in 0 until rows * width) {
                    // Canvas bytes are R, G, B, A: read little-endian that is 0xAABBGGRR.
                    val v = (base + i * 4).loadInt()
                    argb[out++] = (v and 0xFF00FF00.toInt()) or ((v and 0xFF) shl 16) or ((v ushr 16) and 0xFF)
                }
                y += rows
            }
        }
        return OcrImage(width, height, argb)
    }

    override fun release() {
        source?.let(::jsRelease)
        source = null
    }

    private companion object {
        const val BAND_BYTES = 1 shl 21
    }
}
