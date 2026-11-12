package com.szdict.app.feature.ocr

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun Modifier.imageDropTarget(onHover: (Boolean) -> Unit, onDrop: (String?) -> Unit): Modifier = this
