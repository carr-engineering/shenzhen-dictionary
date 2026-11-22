package com.szdict.app.ui.kit

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser

@Composable
internal fun PinyinIcon(shown: Boolean, modifier: Modifier = Modifier) {
    val slashProgress by animateFloatAsState(
        targetValue = if (shown) 0f else 1f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "pinyin-slash",
    )
    val tint = LocalContentColor.current
    val slash = remember { Path() }
    val knockout = remember { Path() }

    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val progress = slashProgress
            if (progress == 0f) {
                drawPath(PinyinGlyph, tint)
            } else {
                val upperTip = lerp(SlashUpperStart, SlashUpperEnd, progress)
                val lowerTip = lerp(SlashLowerStart, SlashLowerEnd, progress)
                slash.setQuad(SlashUpperStart, upperTip, lowerTip, SlashLowerStart)

                // Clear the glyph beside the slash as it grows. The cut extends under the
                // slash so the two shapes meet without leaving a seam.
                knockout.setQuad(
                    SlashUpperStart + GapOffset,
                    upperTip + GapOffset,
                    lerp(upperTip, lowerTip, 0.5f),
                    lerp(SlashUpperStart, SlashLowerStart, 0.5f),
                )
                clipPath(knockout, clipOp = ClipOp.Difference) {
                    drawPath(PinyinGlyph, tint)
                }
                drawPath(slash, tint)
            }
        }
    }
}

private fun Path.setQuad(start: Offset, first: Offset, second: Offset, end: Offset) {
    rewind()
    moveTo(start.x, start.y)
    lineTo(first.x, first.y)
    lineTo(second.x, second.y)
    lineTo(end.x, end.y)
    close()
}

// Match the diagonal of the original hidden icon in the 24 × 24 viewport.
private val SlashUpperStart = Offset(2.8f, 2.8f)
private val SlashUpperEnd = Offset(21.2f, 21.2f)
private val SlashLowerStart = Offset(1.4f, 4.2f)
private val SlashLowerEnd = Offset(19.75f, 22.6f)
private val GapOffset = Offset(1.425f, -1.425f)

private val PinyinGlyph: Path by lazy {
    PathParser().parsePathString(
        "M14,12h2V9H14v3ZM4.5,20L4,18H5.5q0.2,0 0.35-0.15T6,17.5V14.38q-0.42,0.17-0.82,0.34T4.4,15L4,13.05q0.48-0.1 0.99-0.28T6,12.4V9H4.5V7H6V4H8V7H9.5V9H8v2.4q0.38-0.22 0.72-0.45Q9.08,10.73 9.4,10.5v2q-0.3,0.25-0.65,0.49Q8.4,13.23 8,13.43V18.5q0,0.57-0.46,1.04T6.5,20h-2Zm5.55,0l-1.1-1.65q1.05-0.7 1.81-1.88T11.83,14H10V12h2V9H10.5V7h9V9H18v3h2v2H18v6H16V14H13.85q-0.35,1.78-1.36,3.41T10.05,20Zm7.2-12.25L15.53,7q0.4-0.68 0.89-1.48T17.2,4l1.85,0.67Q18.68,5.4 18.18,6.22T17.25,7.75Zm-4.72-0.2Q12.1,6.93 11.55,6.18T10.5,4.85L12.3,4q0.45,0.57 0.96,1.3q0.51,0.72 0.94,1.32L12.53,7.55Z",
    ).toPath()
}
