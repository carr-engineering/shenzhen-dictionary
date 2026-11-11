package com.szdict.app.ui.kit

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics

fun Modifier.hiddenFromAccessibility(): Modifier = clearAndSetSemantics { }
