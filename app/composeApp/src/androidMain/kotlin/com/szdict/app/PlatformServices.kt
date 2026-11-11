package com.szdict.app

import android.content.Context

/**
 * Android services handed to feature code through [AppEnv.services] (recognisers, camera, voice…).
 * Feature agents register their implementations here under a well-known key.
 */
object PlatformServices {
    fun create(context: Context): Map<String, Any> = buildMap {
        put("context", context)
    }
}
