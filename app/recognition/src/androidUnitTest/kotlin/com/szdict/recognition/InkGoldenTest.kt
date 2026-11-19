package com.szdict.recognition

import com.szdict.recognition.ink.InkFeatures
import com.szdict.recognition.ink.InkNet
import com.szdict.recognition.ink.InkPipeline
import com.szdict.recognition.ink.SingleCharCtc
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The ink curve encoder and single-character decoder against the reference Python implementation. */
class InkGoldenTest {
    @Test
    fun featuresMatchReferenceEncoder() {
        val golden = TestData.inkGolden()
        assertEquals(25, golden.cases.size)
        for (case in golden.cases) {
            val actual = InkFeatures.encode(case.strokes)
            assertEquals(case.features.size, actual.size, "curve count for ${case.expected}")
            for (i in actual.indices) {
                val e = case.features[i]
                assertTrue(abs(actual[i] - e) <= 2e-3f + 1e-3f * abs(e), "${case.expected} feature $i: ${actual[i]} vs $e")
            }
        }
    }

    @Test
    fun encodingIsTranslationAndScaleInvariant() {
        val case = TestData.inkGolden().cases[4]
        val moved = case.strokes.map { s -> InkStroke(s.points.map { InkPoint(it.x * 2.35f + 910f, it.y * 2.35f - 400f, it.t) }) }
        val a = InkFeatures.encode(case.strokes)
        val b = InkFeatures.encode(moved)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 2e-3f)
    }

    @Test
    fun degenerateInkEncodesToFiniteFeatures() {
        val dot = InkStroke(listOf(InkPoint(0f, 0f, 0)))
        for (strokes in listOf(listOf(dot), listOf(InkStroke(listOf(InkPoint(0f, 0f, 0), InkPoint(0f, 0f, 5)))), listOf(dot, dot))) {
            val f = InkFeatures.encode(strokes)
            assertTrue(f.isNotEmpty() && f.all { it.isFinite() })
        }
        assertEquals(0, InkFeatures.encode(emptyList()).size)
    }

    @Test
    fun singleCharacterScoresMatchReferenceOnRealLogits() {
        val rows = File(TestData.golden, "ink-logits.txt").readLines().filter { it.isNotBlank() }
        val (frames, classes) = rows[0].split(' ').map { it.toInt() }
        val bytes = TestData.gunzip("ink-logits.bin.gz")
        val logits = FloatArray(frames * classes)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(logits)
        val scores = SingleCharCtc.scores(logits, frames, classes)
        assertEquals(classes - 1, scores.size)
        val expected = rows.drop(1).map { it.split(' ') }
        for (row in expected) assertEquals(row[1].toFloat(), scores[row[0].toInt()], 1e-3f, "score of label ${row[0]}")
        val top = SingleCharCtc.top(scores, 40) { true }
        assertEquals(expected.map { it[0].toInt() }, top.toList())
    }

    @Test
    fun handwritingKeepsOnlyTheBestScoringFormOfEachDigit() = runBlocking {
        val labels = listOf("7", "７", "8", "８", "七")
        val logits = floatArrayOf(4f, 5f, 3f, 1f, 2f, 0f)
        val net = object : InkNet {
            override val classes = logits.size
            override suspend fun infer(features: FloatArray, frames: Int) = FloatArray(frames * classes) { logits[it % classes] }
            override fun close() = Unit
        }
        val pipeline = InkPipeline(net, labels)
        val strokes = listOf(InkStroke(listOf(InkPoint(0f, 0f, 0))))

        assertEquals(listOf("７", "8"), pipeline.recognize(strokes, limit = 2).map { it.text })
        assertEquals(listOf("７", "8", "七"), pipeline.recognize(strokes, limit = labels.size).map { it.text })
    }

    @Test
    fun scriptClassesBiasOnlyOneScriptCharacters() {
        val labels = HostNets.labels("ink/labels.txt")
        val scripts = com.szdict.recognition.ink.ScriptClasses(labels, File(HostNets.modelDir, "ink/script-classes.txt").readText())
        fun penalty(model: InkModel, c: String) = scripts.penalty(model, labels.indexOf(c))
        // 说 (simplified) / 說 (traditional) / 説 (Hong Kong form); 后 and 里 are shared by both scripts.
        assertEquals(0f, penalty(InkModel.SIMPLIFIED, "说"))
        assertEquals(2.5f, penalty(InkModel.SIMPLIFIED, "說"))
        assertEquals(2.5f, penalty(InkModel.TRADITIONAL, "说"))
        assertEquals(0f, penalty(InkModel.TRADITIONAL, "說"))
        assertEquals(1f, penalty(InkModel.HONG_KONG, "說"))
        assertEquals(2.5f, penalty(InkModel.HONG_KONG, "说"))
        for (model in InkModel.entries) for (c in listOf("后", "里", "人", "十")) assertEquals(0f, penalty(model, c), "$c in $model")
        assertEquals(0f, penalty(InkModel.HONG_KONG, "説"))
    }

    @Test
    fun singleCharacterScoresMatchExhaustivePathEnumeration() {
        // Two symbols plus blank, four frames: enumerate all 81 alignments.
        val logits = floatArrayOf(.3f, -.7f, 1.2f, 1.1f, .2f, -.4f, -.3f, 1.5f, .6f, .7f, -.1f, .4f)
        val p = Array(4) { t ->
            val e = DoubleArray(3) { exp(logits[t * 3 + it].toDouble()) }
            DoubleArray(3) { e[it] / e.sum() }
        }
        val expected = DoubleArray(2)
        for (code in 0 until 81) {
            val path = IntArray(4) { var c = code; repeat(it) { c /= 3 }; c % 3 }
            val transcript = path.filterIndexed { i, v -> v != 2 && (i == 0 || path[i - 1] != v) }
            if (transcript.size == 1) expected[transcript[0]] += (0 until 4).fold(1.0) { acc, t -> acc * p[t][path[t]] }
        }
        val scores = SingleCharCtc.scores(logits, 4, 3)
        for (c in 0 until 2) assertEquals(ln(expected[c]), scores[c].toDouble(), 1e-5)
    }

    @Test
    fun singleCharacterScoresStayFiniteOnLongAndExtremeInputs() {
        val extreme = SingleCharCtc.scores(floatArrayOf(1000f, -1000f, 0f, -1000f, 1000f, 0f), 2, 3)
        assertTrue(extreme.all { !it.isNaN() })
        // 600 frames of a confident blank with one symbol frame: far beyond double underflow without rescaling.
        val frames = 600
        val logits = FloatArray(frames * 3) { if (it % 3 == 2) 12f else -12f }
        logits[300 * 3] = 30f
        val scores = SingleCharCtc.scores(logits, frames, 3)
        assertTrue(scores[0].isFinite() && scores[0] > -1f, "symbol 0 score ${scores[0]}")
        assertTrue(scores[1] < scores[0])
    }
}
