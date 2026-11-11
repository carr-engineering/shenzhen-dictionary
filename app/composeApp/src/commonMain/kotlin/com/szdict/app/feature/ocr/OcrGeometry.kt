package com.szdict.app.feature.ocr

import androidx.compose.ui.geometry.Offset
import com.szdict.recognition.OcrChar
import com.szdict.recognition.OcrLine
import com.szdict.recognition.Quad
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** A recognised symbol: index of its line and of the symbol within the line (stable under corrections). */
data class CharRef(val line: Int, val char: Int)

private fun isBlank(codePoint: Char) = codePoint == ' ' || codePoint == '\t' || codePoint == '\r' || codePoint == '\n' || codePoint == '　'

/** UTF-16 offset inside [text] of its first non-whitespace code point, or null (CAM-065). */
fun firstSelectableOffset(text: String): Int? {
    for (i in text.indices) if (!isBlank(text[i])) return i
    return null
}

/** A symbol without usable geometry is ignored for hit-testing (CAM-122). */
fun Quad.hasArea(): Boolean {
    val p = points
    var twiceArea = 0f
    for (i in 0 until 4) {
        val a = p[i]
        val b = p[(i + 1) % 4]
        twiceArea += a.x * b.y - b.x * a.y
    }
    return abs(twiceArea) > 1e-3f
}

/**
 * The selectable symbol at ([x], [y]) in image pixels (CAM-065): a point inside a symbol's quad
 * wins at once (convex containment with the quad's own winding, so rotated text works); otherwise
 * the nearest symbol centre within [slop] image pixels; whitespace-only symbols are never returned.
 */
fun hitTestChar(lines: List<OcrLine>, x: Float, y: Float, slop: Float): CharRef? {
    var nearest: CharRef? = null
    var nearestDistance = slop
    for ((lineIndex, line) in lines.withIndex()) {
        for ((charIndex, char) in line.chars.withIndex()) {
            if (firstSelectableOffset(char.text) == null || !char.box.hasArea()) continue
            if (char.box.contains(x, y)) return CharRef(lineIndex, charIndex)
            val c = char.box.center
            val distance = sqrt((c.x - x) * (c.x - x) + (c.y - y) * (c.y - y))
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearest = CharRef(lineIndex, charIndex)
            }
        }
    }
    return nearest
}

/**
 * A character whose runner-up reading is close to the best one: worth a second look, so the
 * overlay marks it. Scores are a ranking aid (8-bit logits), hence the generous margins.
 */
fun OcrChar.isUncertain(): Boolean {
    if (firstSelectableOffset(text) == null) return false
    val best = alternatives.getOrNull(0) ?: return false
    val second = alternatives.getOrNull(1) ?: return false
    return second.score >= 0.2f && second.score >= 0.5f * best.score
}

/**
 * The image view's coordinate chain (CAM-062…CAM-065):
 * image px --(fit scale s0, letterbox b)--> fit box px --(zoom z about the top-left, translation t)--> view px,
 * so an image point p lands at `(b + p·s0)·z + t`.
 */
class ImageViewport(val imageWidth: Int, val imageHeight: Int, val viewWidth: Float, val viewHeight: Float) {
    val valid: Boolean get() = imageWidth > 0 && imageHeight > 0 && viewWidth > 0f && viewHeight > 0f
    val fitScale: Float get() = if (!valid) 0f else min(viewWidth / imageWidth, viewHeight / imageHeight)
    val letterbox: Offset get() = Offset((viewWidth - imageWidth * fitScale) / 2f, (viewHeight - imageHeight * fitScale) / 2f)

    fun toView(image: Offset, zoom: Float, translation: Offset): Offset = (letterbox + image * fitScale) * zoom + translation

    fun toImage(view: Offset, zoom: Float, translation: Offset): Offset = ((view - translation) / zoom - letterbox) / fitScale

    /** Keeps the zoomed content covering the viewport: no rubber-band gaps (CAM-063). */
    fun clamp(translation: Offset, zoom: Float): Offset = Offset(
        translation.x.coerceIn(viewWidth * (1f - zoom), 0f),
        translation.y.coerceIn(viewHeight * (1f - zoom), 0f),
    )

    /** Pinch: the centroid stays under the fingers while the zoom changes, then the pan is applied. */
    fun transform(zoom: Float, translation: Offset, centroid: Offset, pan: Offset, factor: Float): Pair<Float, Offset> {
        val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val scaled = centroid - (centroid - translation) * (next / zoom)
        return next to clamp(scaled + pan, next)
    }

    /** Double-tap: 1x ↔ 2.5x toward the tapped point (CAM-064). */
    fun doubleTap(zoom: Float, tap: Offset): Pair<Float, Offset> =
        if (zoom > MIN_ZOOM) MIN_ZOOM to Offset.Zero
        else DOUBLE_TAP_ZOOM to clamp(tap * (1f - DOUBLE_TAP_ZOOM), DOUBLE_TAP_ZOOM)

    /** Translation that brings [image] to the viewport centre at [zoom], clamped (CAM-067). */
    fun centreOn(image: Offset, zoom: Float): Offset =
        clamp(Offset(viewWidth / 2f, viewHeight / 2f) - (letterbox + image * fitScale) * zoom, zoom)

    /** Hit-test tolerance in image pixels for a constant on-screen [slopPx] (CAM-065). */
    fun slop(slopPx: Float, zoom: Float): Float = slopPx / (zoom * fitScale)

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 8f
        const val DOUBLE_TAP_ZOOM = 2.5f
    }
}
