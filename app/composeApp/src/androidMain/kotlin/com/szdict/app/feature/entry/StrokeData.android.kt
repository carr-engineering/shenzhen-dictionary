package com.szdict.app.feature.entry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import engineering.carr.hanzianimator.data.BundledStrokeOrderProvider
import engineering.carr.hanzianimator.data.StrokeOrderProvider
import engineering.carr.hanzianimator.data.androidBundledDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private val lock = Any()
private var shared: StrokeOrderProvider? = null

@Composable
internal actual fun rememberStrokeProvider(): StrokeOrderProvider {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        synchronized(lock) {
            shared ?: BundledStrokeOrderProvider(
                // Process lifetime: the dataset is bundled in the APK and its caches are bounded.
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                source = androidBundledDataSource(context.assets),
            ).also { shared = it }
        }
    }
}
