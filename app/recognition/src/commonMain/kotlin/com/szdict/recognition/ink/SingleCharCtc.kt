package com.szdict.recognition.ink

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Exact CTC score of every one-character transcript.
 *
 * For each symbol `c` it sums the probability of all frame alignments that collapse to exactly `c`:
 * a blank-only prefix, one contiguous run of `c`, then a blank-only suffix. This is the reference
 * decoder (`single_character_scores`) computed in the probability domain with a shared running scale
 * instead of per-element log-sum-exp, which gives the same numbers far more cheaply.
 */
internal object SingleCharCtc {
    /**
     * @param logits `frames x classes` raw network outputs; the last class is the CTC blank.
     * @return natural-log probability per symbol (`classes - 1` values); `-Infinity` when impossible.
     */
    fun scores(logits: FloatArray, frames: Int, classes: Int): FloatArray {
        require(frames > 0 && logits.size >= frames * classes)
        val symbols = classes - 1
        val run = DoubleArray(symbols)
        val suffix = DoubleArray(symbols)
        var blank = 1.0
        var logScale = 0.0
        val p = DoubleArray(classes)
        for (t in 0 until frames) {
            val base = t * classes
            var top = Float.NEGATIVE_INFINITY
            for (k in 0 until classes) if (logits[base + k] > top) top = logits[base + k]
            var sum = 0.0
            for (k in 0 until classes) {
                val e = exp((logits[base + k] - top).toDouble())
                p[k] = e
                sum += e
            }
            val pb = p[symbols] / sum
            var largest = blank * pb
            for (c in 0 until symbols) {
                val r = run[c]
                val s = (r + suffix[c]) * pb
                val nr = (blank + r) * (p[c] / sum)
                suffix[c] = s
                run[c] = nr
                if (nr > largest) largest = nr
                if (s > largest) largest = s
            }
            blank *= pb
            // Rescale so the largest state is 1; keeps long inks away from underflow.
            if (largest > 0.0 && (largest < 1e-100 || largest > 1e100 || t % 16 == 15)) {
                val inv = 1.0 / largest
                for (c in 0 until symbols) { run[c] *= inv; suffix[c] *= inv }
                blank *= inv
                logScale += ln(largest)
            }
        }
        return FloatArray(symbols) { c ->
            val v = run[c] + suffix[c]
            if (v > 0.0) (ln(v) + logScale).toFloat() else Float.NEGATIVE_INFINITY
        }
    }

    /** Indices of the [limit] highest-scoring entries among those accepted by [accept], best first. */
    fun top(scores: FloatArray, limit: Int, accept: (Int) -> Boolean): IntArray {
        val k = max(0, limit)
        val idx = IntArray(k)
        val sc = FloatArray(k)
        var filled = 0
        for (c in scores.indices) {
            val v = scores[c]
            if (v == Float.NEGATIVE_INFINITY || !accept(c)) continue
            if (filled < k || v > sc[filled - 1]) {
                var pos = if (filled < k) filled++ else k - 1
                while (pos > 0 && sc[pos - 1] < v) { sc[pos] = sc[pos - 1]; idx[pos] = idx[pos - 1]; pos-- }
                sc[pos] = v
                idx[pos] = c
            }
        }
        return idx.copyOf(filled)
    }
}
