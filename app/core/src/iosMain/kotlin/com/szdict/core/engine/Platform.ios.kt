@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.szdict.core.engine

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.Foundation.precomposedStringWithCompatibilityMapping
import platform.Foundation.stringWithContentsOfFile

internal actual fun inflateRaw(bytes: ByteArray, dictionary: ByteArray?): ByteArray = PureInflate.inflate(bytes, dictionary)

private fun String.isAscii(): Boolean {
    for (c in this) if (c.code >= 0x80) return false
    return true
}

@Suppress("CAST_NEVER_SUCCEEDS")
private fun String.ns(): NSString = this as NSString

internal actual fun normalizeNfkc(text: String): String =
    if (text.isAscii()) text else text.ns().precomposedStringWithCompatibilityMapping

internal actual fun normalizeNfc(text: String): String =
    if (text.isAscii()) text else text.ns().precomposedStringWithCanonicalMapping

internal actual fun fileExists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

internal actual fun readTextFile(path: String): String? =
    if (!fileExists(path)) null else NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

private val driver = BundledSQLiteDriver()

internal actual suspend fun openSqlite(path: String): SQLiteConnection = driver.open(path, SQLITE_OPEN_READONLY)

internal actual val lazyCompanionFiles: Boolean = false

internal actual val sqliteConnectionsPerFile: Int = 4
