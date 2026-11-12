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

private const val SLICE = 1 shl 20

internal fun consoleLog(message: String): Unit = js("console.log(message)")

internal fun consoleWarn(message: String): Unit = js("console.warn(message)")
