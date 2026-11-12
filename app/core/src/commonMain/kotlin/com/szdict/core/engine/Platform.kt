package com.szdict.core.engine

import androidx.sqlite.SQLiteConnection

/**
 * Raw DEFLATE (no zlib header) decompression — `zjson` blobs of the dataset contract — with the
 * dataset's preset dictionary when it has one (`body_dict`, schema version 2).
 */
internal expect fun inflateRaw(bytes: ByteArray, dictionary: ByteArray? = null): ByteArray

internal expect fun normalizeNfkc(text: String): String

internal expect fun normalizeNfc(text: String): String

internal expect fun fileExists(path: String): Boolean

/** Reads a small UTF-8 text file, or null when it does not exist or cannot be read. */
internal expect fun readTextFile(path: String): String?

/** Opens a database file read-only with the platform's SQLite driver. */
internal expect suspend fun openSqlite(path: String): SQLiteConnection

/**
 * True where the language-model companion is not installed with the dataset but fetched when a
 * query first needs it (Web, ARCH-143): the store then opens without it and answers "not yet
 * available" until the fetch completes (ARCH-026).
 */
internal expect val lazyCompanionFiles: Boolean

/** Upper bound of pooled connections per database file. */
internal expect val sqliteConnectionsPerFile: Int
