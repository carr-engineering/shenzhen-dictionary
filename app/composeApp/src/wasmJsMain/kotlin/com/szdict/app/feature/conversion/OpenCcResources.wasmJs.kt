package com.szdict.app.feature.conversion

import com.szdict.app.AppEnv
import com.szdict.app.platform.fetchBytes
import com.szdict.app.platform.fetchText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The OpenCC configurations and dictionaries (about 1.3 MB) are fetched together from `opencc/`
 * the first time text needs detection or conversion (ARCH-143) and kept for the page; a failed
 * fetch is retried on the next use.
 */
private object OpenCcWebResources {
    private const val ROOT = "opencc/"
    private val files = HashMap<String, ByteArray>()
    private val mutex = Mutex()
    private var complete = false

    fun read(name: String): ByteArray = files[name] ?: throw NoSuchElementException(name)

    suspend fun prepare() {
        if (complete) return
        mutex.withLock {
            if (complete) return
            val index = fetchText(ROOT + "index.json") ?: error("OpenCC resources are unavailable")
            val names = Json.decodeFromString(ListSerializer(String.serializer()), index)
            coroutineScope {
                names.filter { it !in files }.map { name ->
                    async { files[name] = fetchBytes(ROOT + name) ?: error("OpenCC resource $name is unavailable") }
                }.awaitAll()
            }
            complete = true
        }
    }
}

internal actual fun openCcResourceReader(env: AppEnv): (String) -> ByteArray = OpenCcWebResources::read

internal actual suspend fun prepareOpenCcResources(env: AppEnv) = OpenCcWebResources.prepare()
