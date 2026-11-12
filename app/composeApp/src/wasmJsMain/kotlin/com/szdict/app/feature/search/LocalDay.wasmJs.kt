@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.szdict.app.feature.search

private fun jsLocalEpochDay(): Double = js(
    "(function () { var d = new Date(); return Math.floor((d.getTime() - d.getTimezoneOffset() * 60000) / 86400000); })()"
)

internal actual fun localEpochDay(): Long = jsLocalEpochDay().toLong()
