@file:OptIn(ExperimentalWasmJsInterop::class)

package com.szdict.app.platform

import kotlinx.coroutines.await
import kotlinx.coroutines.yield
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise

private fun jsFetchText(url: String, revalidate: Boolean): Promise<JsString?> = js(
    "fetch(url, revalidate ? { cache: 'no-cache' } : {}).then(function (r) { return r.ok ? r.text() : null; }).catch(function () { return null; })"
)

private fun jsFetchBytes(url: String): Promise<Int8Array?> = js(
    "fetch(url).then(function (r) { return r.ok ? r.arrayBuffer().then(function (b) { return new Int8Array(b); }) : null; }).catch(function () { return null; })"
)

// An insecure origin has no crypto.subtle: there only the font check of the caller applies, as in the workers.
private fun jsFetchVerified(url: String, sha256: String): Promise<Int8Array?> = js(
    """fetch(url).then(function (r) { return r.ok ? r.arrayBuffer() : null; }).then(function (b) {
        if (b === null) return null;
        if (!(self.crypto && crypto.subtle)) return new Int8Array(b);
        return crypto.subtle.digest('SHA-256', b).then(function (d) {
            var hex = Array.prototype.map.call(new Uint8Array(d), function (x) { return (x < 16 ? '0' : '') + x.toString(16); }).join('');
            return hex === sha256 ? new Int8Array(b) : null;
        });
    }).catch(function () { return null; })"""
)

/** The text at [url], or null when it cannot be fetched. */
internal suspend fun fetchText(url: String, revalidate: Boolean = false): String? = jsFetchText(url, revalidate).await<JsString?>()?.toString()

/** The bytes at [url], or null when it cannot be fetched. Large files are copied in slices so frames keep rendering. */
internal suspend fun fetchBytes(url: String): ByteArray? {
    val view = jsFetchBytes(url).await<Int8Array?>() ?: return null
    val out = ByteArray(view.length)
    var i = 0
    while (i < out.size) {
        val end = minOf(out.size, i + SLICE)
        while (i < end) {
            out[i] = view[i]
            i++
        }
        if (i < out.size) yield()
    }
    return out
}

/** The bytes at [url] when their SHA-256 is [sha256] (lowercase hex); null when they differ or cannot be fetched. */
internal suspend fun fetchVerifiedBytes(url: String, sha256: String): ByteArray? = jsFetchVerified(url, sha256).await<Int8Array?>()?.let { copy(it) }

private suspend fun copy(view: Int8Array): ByteArray {
    val out = ByteArray(view.length)
    var i = 0
    while (i < out.size) {
        val end = minOf(out.size, i + SLICE)
        while (i < end) {
            out[i] = view[i]
            i++
        }
        if (i < out.size) yield()
    }
    return out
}

private const val SLICE = 1 shl 20

private fun jsServiceWorkerControls(timeoutMs: Int): Promise<JsAny?> = js(
    """new Promise(function (resolve) {
        var sw = navigator.serviceWorker;
        if (!sw || sw.controller) { resolve(null); return; }
        sw.addEventListener('controllerchange', function () { resolve(null); });
        setTimeout(function () { resolve(null); }, timeoutMs);
    })"""
)

/**
 * Returns once the service worker controls the page — at once on every visit after the first, and
 * after [timeoutMs] at the latest — so that what is fetched next is kept by it (ARCH-143).
 */
internal suspend fun awaitServiceWorker(timeoutMs: Int = 3000) {
    jsServiceWorkerControls(timeoutMs).await<JsAny?>()
}

internal fun consoleLog(message: String): Unit = js("console.log(message)")

internal fun consoleWarn(message: String): Unit = js("console.warn(message)")
