package com.szdict.recognition.ink

import com.szdict.recognition.InkCandidate
import com.szdict.recognition.InkStroke
import com.szdict.recognition.ocr.firstCodePoint
import com.szdict.recognition.ocr.isHan

/** The Digital Ink network: `frames x 10` curve features in, `frames x classes` logits out. */
internal interface InkNet {
    val classes: Int

    fun infer(features: FloatArray, frames: Int): FloatArray

    fun close()
}

/** Curve encoding, network and single-character decoding for one handwritten character. */
internal class InkPipeline(private val net: InkNet, private val labels: List<String>) {
    private val han = BooleanArray(labels.size) { isHan(labels[it].firstCodePoint()) }

    init {
        require(net.classes == labels.size + 1) { "Label table does not match the network output" }
    }

    fun recognize(strokes: List<InkStroke>, limit: Int): List<InkCandidate> {
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
        return SingleCharCtc.top(scores, limit) { han[it] }.map { InkCandidate(labels[it], scores[it]) }
    }

    companion object {
        const val MAX_STROKES = 128
        const val MAX_FRAMES = 1024
    }
}
