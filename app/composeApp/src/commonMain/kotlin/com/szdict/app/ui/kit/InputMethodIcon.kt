package com.szdict.app.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

/** A single silhouette rotates clockwise between the two input-method icons. */
@Composable
internal fun InputMethodIcon(
    keyboard: Boolean,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val stop = if (keyboard) 1 else 0
    val turn = remember { Animatable(stop.toFloat()) }
    LaunchedEffect(stop) {
        val current = turn.value
        val lastStop = current.toInt()
        if (current == lastStop.toFloat() && lastStop % 2 == stop) return@LaunchedEffect

        // Advance to the next matching stop even when a tap interrupts the previous turn.
        val nextStop = lastStop + 1
        val target = if (nextStop % 2 == stop) nextStop else nextStop + 1
        turn.animateTo(
            target.toFloat(),
            tween(durationMillis = ((target - current) * TurnDurationMillis).roundToInt(), easing = LinearEasing),
        )
        turn.snapTo(stop.toFloat())
    }

    Spacer(
        modifier = modifier.size(24.dp).semantics {
            contentDescription = label
            role = Role.Image
        }.drawWithCache {
            val body = Path()
            val keys = Path()
            val eraserCutout = Path()
            onDrawBehind {
                val phase = turn.value % 2f
                val towardKeyboard = phase <= 1f
                val travel = if (towardKeyboard) phase else phase - 1f
                val rotation = easeBetween(travel, 0.12f, 0.90f)
                val keyboardShape = if (towardKeyboard) rotation else 1f - rotation
                val angle = if (towardKeyboard) lerp(-45f, 0f, rotation) else lerp(-180f, -45f, rotation)
                val keyReveal = if (towardKeyboard) progressBetween(travel, 0.63f, 1f)
                    else 1f - progressBetween(travel, 0f, 0.25f)
                val inkAlpha = if (towardKeyboard) 1f - easeBetween(travel, 0.14f, 0.50f)
                    else easeBetween(travel, 0.47f, 0.88f)
                val eraserHalfGap = 0.8f * if (towardKeyboard) {
                    1f - easeBetween(travel, 0.12f, 0.63f)
                } else {
                    easeBetween(travel, 0.35f, 0.88f)
                }
                val centerY = lerp(12.4f, 12f, keyboardShape)

                scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                    translate(12f, centerY) {
                        body.setSilhouette(keyboardShape)
                        keys.setKeyboardCutouts(keyReveal)
                        eraserCutout.rewind()
                        if (eraserHalfGap > 0f) {
                            eraserCutout.addRect(Rect(5.6f - eraserHalfGap, -8f, 5.6f + eraserHalfGap, 8f))
                        }
                        clipPath(keys, clipOp = ClipOp.Difference) {
                            rotate(angle, pivot = Offset.Zero) {
                                clipPath(eraserCutout, clipOp = ClipOp.Difference) {
                                    drawPath(body, tint)
                                }
                            }
                        }
                    }
                    if (inkAlpha > 0f) {
                        // The scribble paths are drawn at the pencil's resting angle.
                        rotate(angle + 45f, pivot = Offset(12f, centerY)) {
                            drawPath(FirstScribble, tint, alpha = inkAlpha, style = ScribbleStroke)
                            drawPath(SecondScribble, tint, alpha = inkAlpha, style = ScribbleStroke)
                        }
                    }
                }
            }
        },
    )
}

private fun progressBetween(value: Float, start: Float, end: Float): Float =
    ((value - start) / (end - start)).coerceIn(0f, 1f)

private fun easeBetween(value: Float, start: Float, end: Float): Float =
    FastOutSlowInEasing.transform(progressBetween(value, start, end))

// In its own coordinate system, the pencil is a narrow case with a pointed left edge.
// Widen the case and flatten that same edge to make the keyboard.
private fun Path.setSilhouette(keyboardShape: Float) {
    val halfWidth = lerp(10.8f, 10f, keyboardShape)
    val halfHeight = lerp(3f, 7f, keyboardShape)
    val shoulder = -halfWidth + lerp(3.1f, 0f, keyboardShape)
    val corner = lerp(0.65f, 2f, keyboardShape)
    val tip = lerp(0.42f, 0f, keyboardShape)
    rewind()
    moveTo(-halfWidth + tip, -tip)
    lineTo(shoulder, -halfHeight + corner)
    quadraticTo(shoulder, -halfHeight, shoulder + corner, -halfHeight)
    lineTo(halfWidth - corner, -halfHeight)
    quadraticTo(halfWidth, -halfHeight, halfWidth, -halfHeight + corner)
    lineTo(halfWidth, halfHeight - corner)
    quadraticTo(halfWidth, halfHeight, halfWidth - corner, halfHeight)
    lineTo(shoulder + corner, halfHeight)
    quadraticTo(shoulder, halfHeight, shoulder, halfHeight - corner)
    lineTo(-halfWidth + tip, tip)
    quadraticTo(-halfWidth, 0f, -halfWidth + tip, -tip)
    close()
}

private fun Path.setKeyboardCutouts(reveal: Float) {
    rewind()
    for (row in 0..1) {
        val y = -3f + row * 3f
        for (column in 0..4) {
            val x = -6f + column * 3f
            val offset = column * 0.085f + row * 0.10f
            val radius = easeBetween(reveal, offset, offset + 0.42f)
            if (radius > 0f) addRect(Rect(x - radius, y - radius, x + radius, y + radius))
        }
    }
    val bar = easeBetween(reveal, 0.48f, 1f)
    if (bar > 0f) {
        addRoundRect(RoundRect(Rect(-4f * bar, 4f - bar, 4f * bar, 4f + bar), CornerRadius(bar)))
    }
}

private const val TurnDurationMillis = 340
private val ScribbleStroke = Stroke(2f, cap = StrokeCap.Round)

private val FirstScribble = Path().apply {
    moveTo(4.35f, 5.04f)
    cubicTo(4.95f, 4.36f, 5.95f, 4f, 7f, 4f)
    cubicTo(8.75f, 4f, 10f, 4.94f, 10f, 6f)
    cubicTo(10f, 8.32f, 4f, 8.79f, 4f, 11f)
    cubicTo(4f, 11.59f, 4.52f, 12.09f, 5.22f, 12.55f)
}

private val SecondScribble = Path().apply {
    moveTo(16.75f, 15.5f)
    cubicTo(17.62f, 16.03f, 18f, 16.67f, 18f, 17.5f)
    cubicTo(18f, 18.93f, 15.96f, 20f, 14f, 20f)
}
