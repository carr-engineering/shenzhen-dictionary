package com.szdict.recognition.ocr

import kotlin.math.exp

/** One decoded symbol with the frames that produced it. */
internal class Emission(
    /** Label index of the best reading (the frame-wise best symbol, except on exact score ties). */
    val label: Int,
    /** First and last frame (inclusive) of the run that emitted the symbol. */
    val firstFrame: Int,
    val lastFrame: Int,
    /** Frame of the run where the symbol was most probable. */
    val peakFrame: Int,
    /** Candidate label indices, best first; `labels[0] == label`. */
    val labels: IntArray,
    /** Scores matching [labels], see `OcrAlt.score`. */
    val scores: FloatArray,
)

/**
 * Greedy CTC decoding of the line recogniser's per-frame logits, keeping for each emitted symbol the
 * other symbols the network considered at the same frames.
 *
 * The recogniser emits one distribution per 4-pixel column of the normalised line. Output classes
 * `0 until labelCount` are symbols; every class at or above [labelCount] is a blank/no-symbol class.
 * The first of those (`labelCount`) is the network's repeat separator: it fires between two identical
 * consecutive symbols ("ll", "00") in place of a blank.
 * A character is a maximal run of frames with the same best symbol, split where the repeat separator
 * is strong inside the run; a blank frame where the space symbol nearly wins counts as a space. Its alternatives are computed
 * from those run frames only: each frame's symbol distribution (softmax over symbol classes, blanks
 * excluded) is averaged with a weight equal to the frame's non-blank probability mass.
 */
internal object CtcDecoder {
    fun decode(
        logits: ByteArray,
        frames: Int,
        classes: Int,
        labelCount: Int,
        scale: Float,
        topK: Int,
        spaceLabel: Int = -1,
    ): List<Emission> {
        require(logits.size >= frames * classes)
        val out = ArrayList<Emission>()
        val best = IntArray(frames)
        val frameMax = IntArray(frames)
        for (t in 0 until frames) {
            val base = t * classes
            var b = 0
            var bv = logits[base].toInt() and 0xFF
            for (k in 1 until classes) {
                val v = logits[base + k].toInt() and 0xFF
                if (v > bv) { bv = v; b = k }
            }
            best[t] = b
            frameMax[t] = bv
            // Word spaces are emitted weakly and often lose narrowly to the blank. A space that has
            // at least half the blank's probability on a blank frame is taken as a space.
            if (b >= labelCount && spaceLabel >= 0 && (bv - (logits[base + spaceLabel].toInt() and 0xFF)) * scale < SPACE_MARGIN) best[t] = spaceLabel
        }
        // exp() of a quantised logit relative to the frame maximum only takes 256 values.
        val table = FloatArray(256) { exp(-it * scale) }
        val acc = FloatArray(labelCount)
        var t = 0
        while (t < frames) {
            val label = best[t]
            if (label >= labelCount) { t++; continue }
            var end = t
            var next = -1
            while (end + 1 < frames && best[end + 1] == label) {
                // A strong repeat separator on an interior frame starts a second copy of the symbol.
                val f = end + 1
                if (f + 1 < frames && best[f + 1] == label && separates(logits, f * classes, label, labelCount, classes, scale)) {
                    next = f + 1
                    break
                }
                end++
            }
            if (next < 0) next = end + 1
            acc.fill(0f)
            var weightSum = 0f
            var peak = t
            var peakP = -1f
            for (f in t..end) {
                val base = f * classes
                val top = frameMax[f]
                var symbolMass = 0f
                var blankMass = 0f
                for (k in 0 until labelCount) symbolMass += table[top - (logits[base + k].toInt() and 0xFF)]
                for (k in labelCount until classes) blankMass += table[top - (logits[base + k].toInt() and 0xFF)]
                val weight = symbolMass / (symbolMass + blankMass)
                val p = table[top - (logits[base + label].toInt() and 0xFF)] / (symbolMass + blankMass)
                if (p > peakP) { peakP = p; peak = f }
                val norm = weight / symbolMass
                for (k in 0 until labelCount) acc[k] += table[top - (logits[base + k].toInt() and 0xFF)] * norm
                weightSum += weight
            }
            val k = minOf(topK, labelCount).coerceAtLeast(1)
            val idx = IntArray(k) { -1 }
            val sc = FloatArray(k) { -1f }
            // Seed with the decoded symbol so it stays first on ties.
            idx[0] = label
            sc[0] = acc[label]
            var filled = 1
            for (c in 0 until labelCount) {
                if (c == label) continue
                val v = acc[c]
                if (filled < k || v > sc[filled - 1]) {
                    var pos = if (filled < k) filled++ else k - 1
                    while (pos > 1 && sc[pos - 1] < v) { sc[pos] = sc[pos - 1]; idx[pos] = idx[pos - 1]; pos-- }
                    sc[pos] = v
                    idx[pos] = c
                }
            }
            for (i in 0 until filled) sc[i] = sc[i] / weightSum
            // Exact ties are common with 8-bit logits. Break them with the frames just outside the
            // run, where the symbol is already (or still) faintly visible to the network.
            var tied = 1
            while (tied < filled && sc[tied] >= sc[0] * (1f - 1e-5f)) tied++
            if (tied > 1) {
                fun support(c: Int): Int {
                    var v = 0
                    if (t > 0) v += logits[(t - 1) * classes + c].toInt() and 0xFF
                    if (end + 1 < frames) v += logits[(end + 1) * classes + c].toInt() and 0xFF
                    return v
                }
                val order = (0 until tied).sortedByDescending { support(idx[it]) }
                val tiedIdx = IntArray(tied) { idx[order[it]] }
                for (i in 0 until tied) idx[i] = tiedIdx[i]
            }
            out.add(Emission(idx[0], t, end, peak, idx.copyOf(filled), sc.copyOf(filled)))
            t = next
        }
        return out
    }

    /** Whether the repeat-separator class carries at least a third of the symbol's probability at this frame. */
    private fun separates(logits: ByteArray, base: Int, label: Int, labelCount: Int, classes: Int, scale: Float): Boolean {
        if (classes < labelCount + 2) return false
        val symbol = logits[base + label].toInt() and 0xFF
        val separator = logits[base + labelCount].toInt() and 0xFF
        return (symbol - separator) * scale < SEPARATOR_MARGIN
    }

    private const val SEPARATOR_MARGIN = 1.1f // ln 3
    private const val SPACE_MARGIN = 0.7f // ln 2
}
