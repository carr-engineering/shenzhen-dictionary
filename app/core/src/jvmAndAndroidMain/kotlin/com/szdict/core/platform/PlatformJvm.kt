package com.szdict.core.engine

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.Normalizer
import java.util.zip.Inflater

internal actual fun inflateRaw(bytes: ByteArray, dictionary: ByteArray?): ByteArray {
    val inflater = Inflater(true)
    try {
        // A raw stream takes its preset dictionary up front (zlib inflateSetDictionary).
        if (dictionary != null && dictionary.isNotEmpty()) inflater.setDictionary(dictionary)
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream(maxOf(64, bytes.size * 4))
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            } else out.write(buf, 0, n)
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}

internal actual fun normalizeNfkc(text: String): String =
    if (Normalizer.isNormalized(text, Normalizer.Form.NFKC)) text else Normalizer.normalize(text, Normalizer.Form.NFKC)

internal actual fun normalizeNfc(text: String): String =
    if (Normalizer.isNormalized(text, Normalizer.Form.NFC)) text else Normalizer.normalize(text, Normalizer.Form.NFC)

internal actual fun fileExists(path: String): Boolean = File(path).isFile

internal actual fun readTextFile(path: String): String? = try {
    File(path).takeIf { it.isFile }?.readText(Charsets.UTF_8)
} catch (e: Exception) {
    null
}

private val driver = BundledSQLiteDriver()

internal actual suspend fun openSqlite(path: String): SQLiteConnection = driver.open(path, SQLITE_OPEN_READONLY)

internal actual val lazyCompanionFiles: Boolean = false

internal actual val sqliteConnectionsPerFile: Int = 4
