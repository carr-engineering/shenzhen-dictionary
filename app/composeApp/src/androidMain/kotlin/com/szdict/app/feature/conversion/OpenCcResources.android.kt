package com.szdict.app.feature.conversion

import android.content.Context
import com.szdict.app.AppEnv
import java.io.FileNotFoundException

private const val DIRECTORY = "opencc"

internal actual fun openCcResourceReader(env: AppEnv): (String) -> ByteArray {
    val context = env.service<Any>("context") as? Context
    return if (context != null) {
        val assets = context.applicationContext.assets
        { name -> assets.open("$DIRECTORY/$name").use { it.readBytes() } }
    } else {
        ::readOpenCcClasspathResource
    }
}

internal actual suspend fun prepareOpenCcResources(env: AppEnv) {}

/** Host JVM (unit tests): the staged dictionaries are on the test classpath. */
internal fun readOpenCcClasspathResource(name: String): ByteArray {
    val stream = OpenCcScriptConverter::class.java.classLoader?.getResourceAsStream("$DIRECTORY/$name")
        ?: throw FileNotFoundException("$DIRECTORY/$name")
    return stream.use { it.readBytes() }
}
