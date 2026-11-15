package com.szdict.recognition.ocr2

import kotlin.math.max
import kotlin.math.min

/** One decoded symbol of a line. */
internal class Symbol(
    /** Class index of the reading (never the blank, class 0). */
    val label: Int,
    /** The frame at which the symbol was emitted: the first frame of its run. */
    val frame: Int,
    /** The recogniser's probability of [label] at [frame], blank included in the distribution. */
    val probability: Float,
    /** Candidate classes at [frame], most probable first; `labels[0] == label`. */
    val labels: IntArray,
    /** Probabilities matching [labels], renormalised over the symbol classes (blank excluded). */
    val scores: FloatArray,
)

/**
 * Greedy CTC decoding that keeps, for every emitted symbol, the other symbols the recogniser
 * considered at the frame where it emitted it.
 *
 * The line reading is the plain greedy one (per-frame best class, repeats merged, blanks dropped).
 * A symbol's alternatives are the most probable non-blank classes of the first frame of its run, the
 * frame at which the network commits to a new symbol; this is what was evaluated (top-5 of these
 * contains the right Han character for 89.0 % of labelled characters against 86.7 % for the top-1).
 */
internal object CtcAlternatives {
    /** @param data `frames x classes` probabilities starting at [offset]. */
    fun decode(data: FloatArray, offset: Int, frames: Int, classes: Int, topK: Int): List<Symbol> {
        val out = ArrayList<Symbol>()
        val k = min(topK, classes - 1).coerceAtLeast(1)
        var previous = -1
        for (t in 0 until frames) {
            val base = offset + t * classes
            var best = 0
            var bestP = data[base]
            for (c in 1 until classes) {
                val p = data[base + c]
                if (p > bestP) { bestP = p; best = c }
            }
            if (best != 0 && best != previous) {
                val labels = IntArray(k) { -1 }
                val scores = FloatArray(k) { -1f }
                var filled = 0
                for (c in 1 until classes) {
                    val p = data[base + c]
                    if (filled < k || p > scores[filled - 1]) {
                        var pos = if (filled < k) filled++ else k - 1
                        while (pos > 0 && scores[pos - 1] < p) { scores[pos] = scores[pos - 1]; labels[pos] = labels[pos - 1]; pos-- }
                        scores[pos] = p
                        labels[pos] = c
                    }
                }
                // The blank's share is not a reading: scores are over symbols only.
                val symbolMass = (1f - data[base]).coerceAtLeast(bestP)
                for (i in 0 until filled) scores[i] = (scores[i] / symbolMass).coerceIn(0f, 1f)
                out.add(Symbol(best, t, bestP, labels.copyOf(filled), scores.copyOf(filled)))
            }
            previous = best
        }
        return out
    }
}

/**
 * Horizontal extents of a line's characters in its crop, from the frames at which the recogniser
 * emitted them (RapidOCR's `CalRecBoxes` for single-character boxes).
 *
 * A frame covers `cropWidth / lineFrames` pixels, where `lineFrames` is the part of the padded batch
 * tensor the line itself occupies. A character is centred on its emission frame and as wide as the
 * average spacing of emission frames inside "words": runs of characters of one kind (CJK or not)
 * without a space and without a gap of more than five frames. Neighbouring boxes that overlap are
 * cut at the middle of the overlap.
 */
internal object CharSpans {
    /**
     * @param texts the symbols' texts, in order; whitespace symbols get the gap between their neighbours.
     * @param frames the symbols' emission frames.
     * @param lineFrames frames covered by the line: output frames x (line aspect / batch aspect).
     * @return for each symbol `[left, right]` in crop pixels.
     */
    fun compute(texts: List<String>, frames: IntArray, lineFrames: Double, cropWidth: Int): Array<DoubleArray> {
        val n = texts.size
        val out = Array(n) { DoubleArray(2) }
        if (n == 0 || lineFrames <= 0.0) return out
        val frameWidth = cropWidth / lineFrames
        // Average frame spacing inside words of two or more characters.
        var spacingSum = 0.0
        var spacingCount = 0
        var wordFirst = -1
        var wordLast = -1
        var wordSize = 0
        var wordKind = false
        fun endWord() {
            if (wordSize > 1) {
                spacingSum += (frames[wordLast] - frames[wordFirst]) * frameWidth / (wordSize - 1)
                spacingCount++
            }
            wordSize = 0
        }
        var kindKnown = false
        for (i in 0 until n) {
            val text = texts[i]
            if (text.isBlank()) { endWord(); continue }
            val kind = isCjk(text)
            if (!kindKnown) { wordKind = kind; kindKnown = true }
            val gap = if (i == 0) min(if (isCjk(texts[0])) 3 else 2, frames[0]) else frames[i] - frames[i - 1]
            if (wordKind != kind || gap > 5) {
                endWord()
                wordKind = kind
            }
            if (wordSize == 0) wordFirst = i
            wordLast = i
            wordSize++
        }
        endWord()
        val charWidth = if (spacingCount > 0) spacingSum / spacingCount else cropWidth.toDouble() / n
        var previous = -1
        for (i in 0 until n) {
            if (texts[i].isBlank()) continue
            val centre = (frames[i] + 0.5) * frameWidth
            out[i][0] = max((centre - charWidth / 2).toInt(), 0).toDouble()
            out[i][1] = min((centre + charWidth / 2).toInt(), cropWidth).toDouble()
            if (previous >= 0 && out[previous][1] > out[i][0]) {
                val overlap = out[previous][1] - out[i][0]
                out[previous][1] -= overlap / 2
                out[i][0] += overlap - overlap / 2
            }
            previous = i
        }
        // Whitespace: the gap between the neighbouring glyphs.
        for (i in 0 until n) {
            if (!texts[i].isBlank()) continue
            var before = i - 1
            while (before >= 0 && texts[before].isBlank()) before--
            var after = i + 1
            while (after < n && texts[after].isBlank()) after++
            val centre = (frames[i] + 0.5) * frameWidth
            val left = if (before >= 0) out[before][1] else min(centre, if (after < n) out[after][0] else centre)
            val right = if (after < n) out[after][0] else max(centre, left)
            out[i][0] = min(left, right)
            out[i][1] = max(left, right)
        }
        return out
    }

    /** CJK ideographs, CJK punctuation and full-width forms, as the reference tells them from Latin text and digits. */
    private fun isCjk(text: String): Boolean = text.any { it in '一'..'鿿' || it in '　'..'〿' || it in '＀'..'￯' }
}
