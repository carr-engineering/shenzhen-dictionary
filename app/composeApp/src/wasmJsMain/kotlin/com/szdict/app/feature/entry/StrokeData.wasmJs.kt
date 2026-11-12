package com.szdict.app.feature.entry

import androidx.compose.runtime.Composable
import engineering.carr.hanzianimator.data.BundledStrokeOrderProvider
import engineering.carr.hanzianimator.data.StrokeOrderProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// Page lifetime: stroke blocks are fetched from `strokes/` on demand and its caches are bounded.
private val shared: StrokeOrderProvider by lazy {
    BundledStrokeOrderProvider(CoroutineScope(SupervisorJob() + Dispatchers.Default))
}

@Composable
internal actual fun rememberStrokeProvider(): StrokeOrderProvider = shared
