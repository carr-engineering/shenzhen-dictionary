package com.szdict.recognition.ink

import com.szdict.recognition.InkCandidate
import com.szdict.recognition.InkModel
import com.szdict.recognition.InkStroke
import com.szdict.recognition.ocr.firstCodePoint
import com.szdict.recognition.ocr.isHan

/** The Digital Ink network: `frames x 10` curve features in, `frames x classes` logits out. */
internal interface InkNet {
    val classes: Int

    fun infer(features: FloatArray, frames: Int): FloatArray

    fun close()
}

/**
 * Which labels belong to one script only, used to bias candidates towards the script being written.
 * Parsed from `script-classes.txt` (three lines of characters: simplified-only, traditional-only,
 * traditional forms that Hong Kong writes differently).
 */
internal class ScriptClasses(labels: List<String>, text: String) {
    private val lines = text.split('\n')
    val simplifiedOnly = flags(labels, lines.getOrElse(0) { "" })
    val traditionalOnly = flags(labels, lines.getOrElse(1) { "" })
    val notHongKong = flags(labels, lines.getOrElse(2) { "" })

    private fun flags(labels: List<String>, characters: String): BooleanArray {
        val set = HashSet<String>()
        var i = 0
        while (i < characters.length) {
            val n = if (characters[i].isHighSurrogate() && i + 1 < characters.length) 2 else 1
            set.add(characters.substring(i, i + n))
            i += n
        }
        return BooleanArray(labels.size) { labels[it] in set }
    }

    /** Log-probability penalty for label [index] when writing in [model]'s script. */
    fun penalty(model: InkModel, index: Int): Float = when (model) {
        InkModel.SIMPLIFIED -> if (traditionalOnly[index]) OTHER_SCRIPT else 0f
        InkModel.TRADITIONAL -> if (simplifiedOnly[index]) OTHER_SCRIPT else 0f
        InkModel.HONG_KONG -> if (simplifiedOnly[index]) OTHER_SCRIPT else if (notHongKong[index]) REGIONAL_VARIANT else 0f
    }

    companion object {
        const val OTHER_SCRIPT = 2.5f
        const val REGIONAL_VARIANT = 1f
    }
}

/** Curve encoding, network and single-character decoding for one handwritten character. */
internal class InkPipeline(private val net: InkNet, private val labels: List<String>, private val scripts: ScriptClasses? = null) {
    private val han = BooleanArray(labels.size) { isHan(labels[it].firstCodePoint()) }

    init {
        require(net.classes == labels.size + 1) { "Label table does not match the network output" }
    }

    fun recognize(strokes: List<InkStroke>, limit: Int, model: InkModel = InkModel.SIMPLIFIED): List<InkCandidate> {
        val usable = strokes.filter { it.points.isNotEmpty() }
        if (usable.isEmpty() || limit <= 0) return emptyList()
        require(usable.size <= MAX_STROKES) { "Too many strokes" }
        for (s in usable) for (p in s.points) require(p.x.isFinite() && p.y.isFinite()) { "Ink coordinates must be finite" }
        val features = InkFeatures.encode(usable)
        val frames = features.size / InkFeatures.CHANNELS
        if (frames == 0) return emptyList()
        require(frames <= MAX_FRAMES) { "Too many curve segments; simplify the drawing" }
        for (v in features) check(v.isFinite()) { "Could not encode this drawing" }
        val logits = net.infer(features, frames)
        val scores = SingleCharCtc.scores(logits, frames, net.classes)
        if (scripts != null) for (i in scores.indices) if (han[i]) scores[i] -= scripts.penalty(model, i)
        return SingleCharCtc.top(scores, limit) { han[it] }.map { InkCandidate(labels[it], scores[it]) }
    }

    companion object {
        const val MAX_STROKES = 128
        const val MAX_FRAMES = 1024
    }
}
