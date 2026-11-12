@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.szdict.core.engine

import androidx.sqlite.SQLiteConnection
import com.szdict.core.web.WebStore

internal actual fun inflateRaw(bytes: ByteArray, dictionary: ByteArray?): ByteArray = PureInflate.inflate(bytes, dictionary)

private fun jsNormalize(text: String, form: String): String = js("text.normalize(form)")

private fun String.isAscii(): Boolean {
    for (c in this) if (c.code >= 0x80) return false
    return true
}

internal actual fun normalizeNfkc(text: String): String = if (text.isAscii()) text else jsNormalize(text, "NFKC")

internal actual fun normalizeNfc(text: String): String = if (text.isAscii()) text else jsNormalize(text, "NFC")

/** Files live behind the store worker, which fetches a missing one when it is opened (ARCH-143). */
internal actual fun fileExists(path: String): Boolean = true

internal actual fun readTextFile(path: String): String? = WebStore.text(path)

internal actual suspend fun openSqlite(path: String): SQLiteConnection = WebStore.open(path)

/** One worker serves every connection, so more than one per file gains nothing. */
internal actual val sqliteConnectionsPerFile: Int = 1
