package com.szdict.app.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.szdict.app.ui.theme.LocalDictionaryPalette

/**
 * The visible, opaque colour of the card surface the content is drawn on. Anything that has to
 * paint over card text (the `+N` hidden-definitions badge) uses it as its background so the patch
 * is exactly the card's colour, tinted or selected or not. [Color.Unspecified] outside a card.
 */
val LocalCardContainerColor = compositionLocalOf { Color.Unspecified }

/**
 * Resolves [color] to the opaque colour a card Surface shows (a translucent colour composited over
 * the colour below: the enclosing card, else the page background) and provides it as
 * [LocalCardContainerColor] to [content]. Returns the resolved colour to [content] too, so the
 * card Surface can be given exactly it.
 */
@Composable
fun CardContainer(color: Color, content: @Composable (Color) -> Unit) {
    val palette = LocalDictionaryPalette.current
    val below = LocalCardContainerColor.current.let { if (it == Color.Unspecified) palette.bg else it }
    val visible = if (color.alpha < 1f) color.compositeOver(below) else color
    CompositionLocalProvider(LocalCardContainerColor provides visible) { content(visible) }
}
