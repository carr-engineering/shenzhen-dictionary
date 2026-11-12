@file:OptIn(ExperimentalWasmJsInterop::class, UnsafeWasmMemoryApi::class)

package com.szdict.recognition

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

// Tensors cross the JS boundary through the module's linear memory: Kotlin arrays live on the Wasm
// GC heap, which JS cannot see, and element-wise interop calls are far too slow for megabyte
// buffers. `wasmExports` is the Kotlin/Wasm module's own export object, in scope of every js() body.

private fun jsNewWorker(url: String): JsAny = js("new Worker(url)")

private fun jsListen(worker: JsAny, onMessage: (JsAny) -> Unit, onError: (String) -> Unit): Unit = js(
    "{ worker.onmessage = (e) => onMessage(e.data); worker.onerror = (e) => { onError(String((e && e.message) || 'The recognition worker failed')); }; }"
)

private fun jsTerminate(worker: JsAny): Unit = js("worker.terminate()")

private fun jsPostLoad(worker: JsAny, id: Int, kind: String, check: Boolean): Unit =
    js("worker.postMessage({ id: id, op: 'load', kind: kind, check: check })")

private fun jsPostDetect(worker: JsAny, id: Int, address: Int, size: Int, width: Int, height: Int): Unit = js(
    "{ const gray = new Uint8Array(wasmExports.memory.buffer, address, size).slice(); worker.postMessage({ id: id, op: 'detect', gray: gray, width: width, height: height }, [gray.buffer]); }"
)

private fun jsPostSequence(worker: JsAny, id: Int, model: String, address: Int, size: Int, width: Int): Unit = js(
    "{ const columns = new Uint8Array(wasmExports.memory.buffer, address, size).slice(); worker.postMessage({ id: id, op: 'sequence', model: model, columns: columns, width: width }, [columns.buffer]); }"
)

private fun jsPostInk(worker: JsAny, id: Int, address: Int, count: Int, frames: Int): Unit = js(
    "{ const features = new Float32Array(wasmExports.memory.buffer, address, count).slice(); worker.postMessage({ id: id, op: 'ink', features: features, frames: frames }, [features.buffer]); }"
)

private fun jsInt(o: JsAny, name: String): Int = js("(o[name] | 0)")

private fun jsDouble(o: JsAny, name: String): Double = js("Number(o[name])")

private fun jsHas(o: JsAny, name: String): Boolean = js("(o[name] != null)")

private fun jsString(o: JsAny, name: String): String = js("String(o[name])")

private fun jsLabel(o: JsAny, index: Int): String = js("String(o.labels[index])")

private fun jsByteLength(o: JsAny, name: String): Int = js("o[name].byteLength")

private fun jsCopyOut(o: JsAny, name: String, address: Int): Unit = js(
    "{ const a = o[name]; new Uint8Array(wasmExports.memory.buffer, address, a.byteLength).set(new Uint8Array(a.buffer, a.byteOffset, a.byteLength)); }"
)

/** The bytes of typed array `reply[name]`. */
internal fun replyBytes(reply: JsAny, name: String): ByteArray {
    val size = jsByteLength(reply, name)
    val out = ByteArray(size)
    if (size == 0) return out
    withScopedMemoryAllocator { allocator ->
        val base = allocator.allocate(size)
        jsCopyOut(reply, name, base.address.toInt())
        for (i in 0 until size) out[i] = (base + i).loadByte()
    }
    return out
}

/** The floats of Float32Array `reply[name]`. */
internal fun replyFloats(reply: JsAny, name: String): FloatArray {
    val count = jsByteLength(reply, name) / 4
    val out = FloatArray(count)
    if (count == 0) return out
    withScopedMemoryAllocator { allocator ->
        val base = allocator.allocate(count * 4)
        jsCopyOut(reply, name, base.address.toInt())
        for (i in 0 until count) out[i] = Float.fromBits((base + i * 4).loadInt())
    }
    return out
}

internal fun replyInt(reply: JsAny, name: String): Int = jsInt(reply, name)

internal fun replyFloat(reply: JsAny, name: String): Float = jsDouble(reply, name).toFloat()

internal fun replyLabel(reply: JsAny, index: Int): String = jsLabel(reply, index)

internal class WorkerFailure(message: String) : Exception(message)

/**
 * One recognition worker (`recognition-worker.js`): request/reply over `postMessage`, replies
 * matched by id. Used from the page's single thread only.
 */
internal class WorkerClient(url: String) {
    private val worker = jsNewWorker(url)
    private var nextId = 0
    private val pending = HashMap<Int, CancellableContinuation<JsAny>>()
    private val progress = HashMap<Int, (loaded: Long, total: Long) -> Unit>()
    private var dead: String? = null

    init {
        jsListen(
            worker,
            onMessage = { reply ->
                val id = jsInt(reply, "id")
                if (jsHas(reply, "progress")) {
                    progress[id]?.invoke(jsDouble(reply, "loaded").toLong(), jsDouble(reply, "total").toLong())
                } else {
                    progress.remove(id)
                    val waiting = pending.remove(id)
                    if (waiting != null) {
                        if (jsHas(reply, "error")) waiting.resumeWithException(WorkerFailure(jsString(reply, "error"))) else waiting.resume(reply)
                    }
                }
            },
            onError = { message -> fail(message) },
        )
    }

    private fun fail(message: String) {
        dead = message
        val waiting = pending.values.toList()
        pending.clear()
        progress.clear()
        waiting.forEach { it.resumeWithException(WorkerFailure(message)) }
    }

    private suspend fun call(onProgress: ((Long, Long) -> Unit)? = null, post: (id: Int) -> Unit): JsAny {
        dead?.let { throw WorkerFailure(it) }
        val id = ++nextId
        return suspendCancellableCoroutine { continuation ->
            pending[id] = continuation
            if (onProgress != null) progress[id] = onProgress
            continuation.invokeOnCancellation {
                pending.remove(id)
                progress.remove(id)
            }
            post(id)
        }
    }

    /** Fetches (or reads from storage), verifies and loads a model set; `loaded < 0` reports the loading stage. */
    suspend fun load(kind: String, check: Boolean, onProgress: (loaded: Long, total: Long) -> Unit): JsAny =
        call(onProgress) { id -> jsPostLoad(worker, id, kind, check) }

    suspend fun detect(gray: ByteArray, width: Int, height: Int): JsAny = call { id ->
        val size = width * height
        withScopedMemoryAllocator { allocator ->
            val base = allocator.allocate(size)
            for (i in 0 until size) (base + i).storeByte(gray[i])
            jsPostDetect(worker, id, base.address.toInt(), size, width, height)
        }
    }

    suspend fun sequence(model: String, columns: ByteArray, width: Int): JsAny = call { id ->
        val size = width * 24
        withScopedMemoryAllocator { allocator ->
            val base = allocator.allocate(size)
            for (i in 0 until size) (base + i).storeByte(columns[i])
            jsPostSequence(worker, id, model, base.address.toInt(), size, width)
        }
    }

    suspend fun ink(features: FloatArray, frames: Int): JsAny = call { id ->
        val count = frames * 10
        withScopedMemoryAllocator { allocator ->
            val base = allocator.allocate(count * 4)
            for (i in 0 until count) (base + i * 4).storeInt(features[i].toRawBits())
            jsPostInk(worker, id, base.address.toInt(), count, frames)
        }
    }

    fun terminate() {
        jsTerminate(worker)
        fail("Recognizer is closed")
    }
}
