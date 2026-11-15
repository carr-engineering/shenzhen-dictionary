package com.szdict.app.ui.kit

import com.szdict.app.i18n.appStrings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.szdict.app.ui.theme.LocalDictionaryPalette

/**
 * Circular pronunciation button with a speaker glyph. The active speaker has
 * a restrained breathing pulse for the duration of actual audio playback.
 */
@Composable
fun PlayButton(
    enabled: Boolean,
    playing: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    WithTooltip(strings.text(if (playing) "Pronunciation playing" else "Play pronunciation"), modifier) {
        OutlinedIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.outlinedIconButtonColors(
                contentColor = palette.accent,
                disabledContentColor = palette.accent.copy(alpha = 0.35f)
            ),
            border = BorderStroke(1.dp, palette.border)
        ) {
            if (playing) {
                PlayingSpeakerIcon()
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = strings.text("Play pronunciation"),
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

@Composable
private fun PlayingSpeakerIcon() {
    val strings = appStrings()
    val transition = rememberInfiniteTransition(label = "pronunciation-playing")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 620, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "speaker-pulse"
    )
    Icon(
        imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
        contentDescription = strings.text("Pronunciation playing"),
        modifier = Modifier
            .size(21.dp)
            .graphicsLayer {
                scaleX = pulse
                scaleY = pulse
            }
    )
}
