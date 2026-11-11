package com.szdict.recognition

/**
 * One sampled pen position.
 *
 * @property x horizontal position in the writing area's own units (any consistent unit; pixels or dp),
 *   growing rightwards.
 * @property y vertical position in the same units, growing downwards.
 * @property t time of the sample in milliseconds on any monotonic clock.
 */
data class InkPoint(val x: Float, val y: Float, val t: Long)

/** One pen-down … pen-up trajectory. Point order and stroke order are significant. */
data class InkStroke(val points: List<InkPoint>)

/**
 * Which script the writer intends (HW-031).
 *
 * The bundled network is the single `zh-Hani` Digital Ink model, which covers simplified,
 * traditional and Hong Kong characters at once, so every value runs the same network. The value
 * biases the ranking: characters that exist only in the other script are pushed down
 * ([SIMPLIFIED] demotes traditional-only forms such as 說 in favour of 说; [TRADITIONAL] and
 * [HONG_KONG] demote simplified-only forms), and [HONG_KONG] additionally prefers Hong Kong standard
 * forms (説 over 說). Nothing is removed, and characters shared by both scripts are unaffected.
 * Filtering to dataset characters and frequency re-ranking remain the domain layer's job (HW-032).
 */
enum class InkModel { SIMPLIFIED, TRADITIONAL, HONG_KONG }

/**
 * One candidate character.
 *
 * @property text a single Han character (one code point; may be a surrogate pair).
 * @property score natural-log probability (`<= 0`, higher is better) that the whole ink is exactly
 *   this one character: the exact CTC sum over every alignment that decodes to it. `exp(score)` is a
 *   probability over all single- and multi-character readings, so candidates' probabilities sum to
 *   at most 1. A character belonging only to the script not being written has a fixed 2.5 subtracted
 *   (1.0 for a non-Hong-Kong variant in [InkModel.HONG_KONG]). Scores are comparable between
 *   candidates of one call and roughly between calls.
 */
data class InkCandidate(val text: String, val score: Float)

/**
 * Recognises the strokes of **one** handwritten character, fully offline.
 *
 * Implementations are safe to call from any thread; calls are serialised internally and run off the
 * caller's thread. [close] releases the model.
 */
interface HandwritingRecognizer {
    /**
     * @param strokes the ink of the current character, in writing order.
     * @param writingArea width and height of the pad in the same units as the points. Recognition is
     *   translation- and scale-invariant (ink is normalised by its own bounding box), so this is only
     *   a hint and may be `0f to 0f`.
     * @param model intended script, see [InkModel].
     * @param limit maximum number of candidates to return (ranked best first).
     * @return ranked candidates; empty when [strokes] is empty.
     */
    suspend fun recognize(
        strokes: List<InkStroke>,
        writingArea: Pair<Float, Float>,
        model: InkModel,
        limit: Int = 30,
    ): List<InkCandidate>

    fun close()
}
