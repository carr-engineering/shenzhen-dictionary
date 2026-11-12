package com.szdict.app.feature.conversion

import com.szdict.app.AppEnv
import com.szdict.app.platform.IosBundle
import com.szdict.app.platform.readFileBytes

/** The dictionaries and configurations are read in place from `assets/opencc/` in the app bundle. */
internal actual fun openCcResourceReader(env: AppEnv): (String) -> ByteArray = { name ->
    require(!name.contains("..") && !name.startsWith('/')) { "Invalid OpenCC resource name" }
    readFileBytes(IosBundle.path("opencc/$name")) ?: throw NoSuchElementException(name)
}

internal actual suspend fun prepareOpenCcResources(env: AppEnv) {}
