package com.szdict.app.feature.entry

import androidx.compose.runtime.Composable
import com.szdict.app.platform.IosBundle
import engineering.carr.hanzianimator.data.BundledStrokeOrderProvider
import engineering.carr.hanzianimator.data.StrokeOrderProvider
import engineering.carr.hanzianimator.data.iosBundledDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// Process lifetime: the stroke data is read in place from the app bundle and its caches are bounded.
private val shared: StrokeOrderProvider by lazy {
    BundledStrokeOrderProvider(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        source = iosBundledDataSource(basePath = IosBundle.ASSETS + "/strokes"),
    )
}

@Composable
internal actual fun rememberStrokeProvider(): StrokeOrderProvider = shared
