package com.szdict.core.engine

/** Raw DEFLATE (no zlib header) decompression — `zjson` blobs of the dataset contract. */
internal expect fun inflateRaw(bytes: ByteArray): ByteArray

internal expect fun normalizeNfkc(text: String): String

internal expect fun normalizeNfc(text: String): String

internal expect fun fileExists(path: String): Boolean

/** Reads a small UTF-8 text file, or null when it does not exist or cannot be read. */
internal expect fun readTextFile(path: String): String?
