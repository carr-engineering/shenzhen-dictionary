package com.szdict.recognition

/** A point in image pixels: x grows rightwards, y grows downwards, origin at the image's top-left corner. */
data class Point(val x: Float, val y: Float)

/**
 * Four corner points in image pixels, clockwise starting from the glyph-relative top-left corner
 * (top-left, top-right, bottom-right, bottom-left *as the text is read*). For upright horizontal text
 * this is the usual visual order; for rotated or upside-down text the quad rotates with the text, so
 * the winding stays clockwise and convex containment tests work unchanged (CAM-052, CAM-065).
 */
data class Quad(val topLeft: Point, val topRight: Point, val bottomRight: Point, val bottomLeft: Point) {
    val points: List<Point> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)
    val center: Point
        get() = Point(
            (topLeft.x + topRight.x + bottomRight.x + bottomLeft.x) / 4f,
            (topLeft.y + topRight.y + bottomRight.y + bottomLeft.y) / 4f,
        )

    /** Convex containment; works for either winding. */
    fun contains(x: Float, y: Float): Boolean {
        val p = points
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            val s = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
            if (s != 0) {
                if (sign == 0) sign = s else if (sign != s) return false
            }
        }
        return true
    }
}

/**
 * An upright image to recognise, as packed ARGB pixels (`0xAARRGGBB`, row-major, `argb[y * width + x]`),
 * the layout of `android.graphics.Bitmap.getPixels`. Alpha is ignored (transparent pixels should be
 * composited on white by the caller). All result coordinates are in this image's pixel grid.
 */
class OcrImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "Image must not be empty" }
        require(argb.size >= width * height) { "Pixel buffer is smaller than width * height" }
    }
}

/**
 * The result of recognising one image.
 *
 * @property width width of the input image in pixels; all quads are in this coordinate system.
 * @property height height of the input image in pixels.
 * @property lines text lines in reading order (columns left to right, rows top to bottom; vertical
 *   columns right to left).
 */
data class OcrResult(val width: Int, val height: Int, val lines: List<OcrLine>) {
    val text: String get() = lines.joinToString("\n") { it.text }
}

/**
 * One text line.
 *
 * @property text exactly the concatenation of [chars]' texts, so UTF-16 offsets into it map to
 *   characters (see [charStart]).
 * @property box the line's rotated rectangle in image pixels.
 * @property chars one entry per recognised symbol, in reading order.
 * @property vertical true for a top-to-bottom column of upright glyphs.
 */
data class OcrLine(
    val text: String,
    val box: Quad,
    val chars: List<OcrChar>,
    val vertical: Boolean = false,
) {
    /** UTF-16 start offset of `chars[index]` in [text]. */
    fun charStart(index: Int): Int {
        var offset = 0
        for (i in 0 until index) offset += chars[i].text.length
        return offset
    }
}

/**
 * One recognised symbol (usually one Hanzi; may be a surrogate pair, a Latin letter, a digit,
 * punctuation or a space).
 *
 * @property text the best reading; always equal to `alternatives.first().text`.
 * @property box the glyph's quad in image pixels. It is derived from the recogniser's emission
 *   position and the ink profile of the line, so it is a good estimate (suitable for hit-testing and
 *   highlighting), not a segmentation ground truth.
 * @property confidence the probability of the best reading, in `0..1`; always
 *   `alternatives.first().score`. Low values (under about 0.5) mark characters the recogniser was
 *   unsure of, typically because a look-alike scored almost as high.
 * @property alternatives the readings the recogniser considered likely at this position, best first,
 *   including the top-1. Typically visually similar characters (e.g. 己 / 已 / 巳). In a Chinese line
 *   look-alikes from other scripts (kana, Latin letters, digits, symbols) are ranked down, see
 *   [OcrAlt.score].
 */
data class OcrChar(
    val text: String,
    val box: Quad,
    val confidence: Float,
    val alternatives: List<OcrAlt>,
)

/**
 * One candidate reading for a character position.
 *
 * @property score in `0..1`: the recogniser's probability for this symbol at the frames where the
 *   character was emitted, renormalised over real symbols (the CTC blank is excluded) and averaged
 *   over the emission frames weighted by how much non-blank mass each frame carries. Scores of all
 *   symbols at one position sum to 1; the returned top-K list is a prefix of that distribution.
 *   In a line that is Chinese, candidates of other scripts under a Han top reading are down-weighted
 *   (kana by 10, Latin letters, digits and symbols by 4) and the freed probability is spread over
 *   the remaining candidates; a kana top reading is replaced by a Han look-alike close behind it.
 *   The network's logits are 8-bit quantised, so near-ties are common and scores are a ranking aid
 *   rather than calibrated probabilities.
 */
data class OcrAlt(val text: String, val score: Float)

/**
 * On-device OCR that returns, for every character, the other characters that could match there.
 *
 * Implementations are safe to call from any thread; calls are serialised internally and run off the
 * caller's thread. [close] releases the models; the recogniser must not be used afterwards.
 */
interface TextRecognizer {
    /** Recognises [image]. Coordinates in the result are pixels of [image]. Cancellable between lines. */
    suspend fun recognize(image: OcrImage): OcrResult

    fun close()
}
