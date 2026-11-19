package com.szdict.app.ui.kit

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Keep scrolling content faintly visible under the controls that float at either edge. */
internal fun Modifier.scrollUnderControls(start: Dp, end: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val width = size.width
        if (width <= 0f) return@drawWithContent
        val opaqueStart = start.toPx().coerceIn(0f, width)
        val opaqueEnd = (width - end.toPx()).coerceIn(opaqueStart, width)
        val ramp = 16.dp.toPx()
        val faint = Color.Black.copy(alpha = 0.2f)
        val startColor = if (start > 0.dp) faint else Color.Black
        val endColor = if (end > 0.dp) faint else Color.Black
        val mask = Brush.horizontalGradient(
            0f to startColor,
            (opaqueStart - ramp).coerceAtLeast(0f) / width to startColor,
            opaqueStart / width to Color.Black,
            opaqueEnd / width to Color.Black,
            (opaqueEnd + ramp).coerceAtMost(width) / width to endColor,
            1f to endColor,
            startX = 0f,
            endX = width,
        )
        if (layoutDirection == LayoutDirection.Rtl) {
            scale(scaleX = -1f, scaleY = 1f) { drawRect(mask, blendMode = BlendMode.DstIn) }
        } else {
            drawRect(mask, blendMode = BlendMode.DstIn)
        }
    }
