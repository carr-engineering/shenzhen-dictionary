package com.szdict.recognition.ocr2

import com.szdict.recognition.OcrImage
import com.szdict.recognition.OcrLine
import com.szdict.recognition.OcrResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The pipeline's geometry and bookkeeping around scripted networks. */
class Ocr2PipelineTest {
    /** Scripted graphs complete immediately; actual suspension is controlled explicitly below. */
    private fun runImmediate(block: suspend () -> Unit) {
        var completion: Result<Unit>? = null
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { completion = result }
        })
        checkNotNull(completion) { "Scripted test did not complete" }.getOrThrow()
    }

    private class Gate {
        private var pending: Continuation<Unit>? = null
        suspend fun await() = suspendCoroutine<Unit> { pending = it }
        fun release() {
            val continuation = checkNotNull(pending)
            pending = null
            continuation.resume(Unit)
        }
    }

    /**
     * Networks that "detect" the given kernels (x0, y0, x1, y1 in detector pixels, inclusive), call
     * every line upright or turned as [turned] says, and read "你好" then a space and "A" at fixed frames.
     */
    private class Scripted(val kernels: List<IntArray>, val turned: Boolean = false, val probability: Float = 0.9f) : Ocr2Nets {
        var detectorSize = 0 to 0
        val recogniserCalls = ArrayList<Pair<Int, Int>>()
        var closed = false

        private val sink = object : FloatSink {
            override fun put(offset: Int, source: FloatArray, count: Int) = Unit
        }

        override suspend fun detect(height: Int, width: Int, fill: (FloatSink) -> Unit): FloatArray {
            fill(sink)
            detectorSize = width to height
            return FloatArray(width * height) { i ->
                val x = i % width
                val y = i / width
                if (kernels.any { x in it[0]..it[2] && y in it[1]..it[3] }) 0.95f else 0.01f
            }
        }

        override suspend fun classify(batch: Int, fill: (FloatSink) -> Unit): FloatArray {
            fill(sink)
            return FloatArray(batch * 2) { if ((it % 2 == 1) == turned) 0.99f else 0.01f }
        }

        override suspend fun recognize(batch: Int, width: Int, fill: (FloatSink) -> Unit): RecognitionOutput {
            fill(sink)
            recogniserCalls.add(batch to width)
            val frames = width / 8
            val data = FloatArray(batch * frames * CLASSES)
            for (n in 0 until batch) for (t in 0 until frames) {
                val symbol = when (t) { 3 -> 1; 8 -> 2; 11 -> 4; 14 -> 3; else -> 0 }
                val base = (n * frames + t) * CLASSES
                // The rest of the mass: 60 % on another symbol, 40 % on the blank (all of it on a blank frame).
                data[base + symbol] = probability
                data[base + if (symbol == 1) 2 else 1] += (1f - probability) * 0.6f
                data[base] += (1f - probability) * 0.4f
            }
            return RecognitionOutput(data, frames, CLASSES)
        }

        override fun close() { closed = true }

        companion object { const val CLASSES = 5 }
    }

    // Classes: 0 blank, 1 你, 2 好, 3 A, 4 the space the pipeline appends.
    private val labels = listOf("你", "好", "A")

    private fun white(width: Int, height: Int) = OcrImage(width, height, IntArray(width * height) { -1 })

    private fun OcrLine.xs() = chars.map { it.box.center.x }
    private fun OcrLine.ys() = chars.map { it.box.center.y }

    @Test
    fun aLineIsReadWithBoxesInImageCoordinates() = runImmediate {
        val nets = Scripted(listOf(intArrayOf(100, 300, 499, 339)))
        val pipeline = Ocr2Pipeline(nets, labels)
        val result = pipeline.recognize(white(736, 736))
        assertEquals(736 to 736, nets.detectorSize)
        val line = result.lines.single()
        assertEquals("你好 A", line.text)
        assertEquals(false, line.vertical)
        // The 400 x 40 kernel grown by area x 1.5 / perimeter = 26.6 pixels.
        assertEquals(73f, line.box.topLeft.x, 1.5f)
        assertEquals(273f, line.box.topLeft.y, 1.5f)
        assertEquals(526f, line.box.bottomRight.x, 1.5f)
        assertEquals(366f, line.box.bottomRight.y, 1.5f)
        assertEquals(4, line.chars.size)
        assertEquals(line.xs().sorted(), line.xs())
        for (c in line.chars) {
            assertTrue(c.box.topLeft.x >= line.box.topLeft.x - 1 && c.box.topRight.x <= line.box.topRight.x + 1, "inside the line: ${c.box}")
            assertEquals(line.box.topLeft.y, c.box.topLeft.y, 1f)
            assertEquals(line.box.bottomLeft.y, c.box.bottomLeft.y, 1f)
            assertEquals(c.text, c.alternatives.first().text)
            assertEquals(c.confidence, c.alternatives.first().score)
        }
        // The line fills 453 / 93 x 48 = 234 of the tensor's 320 columns, 29.2 of its 40 frames:
        // frame 3 is centred 3.5 x 453 / 29.2 = 54 pixels into the line.
        assertEquals(73f + 54f, line.chars[0].box.center.x, 3f)
        assertEquals(listOf("你", "好"), line.chars[0].alternatives.take(2).map { it.text })
        // 0.9 of the frame's mass, 0.04 of which is the blank's.
        assertEquals(0.9f / 0.96f, line.chars[0].confidence, 1e-5f)
        assertEquals(0.06f / 0.96f, line.chars[0].alternatives[1].score, 1e-5f)
        // The space lies between its neighbours.
        assertEquals(" ", line.chars[2].text)
        assertTrue(line.chars[2].box.topLeft.x >= line.chars[1].box.topRight.x - 0.01f && line.chars[2].box.topRight.x <= line.chars[3].box.topLeft.x + 0.01f)
        assertEquals(listOf(1 to 320), nets.recogniserCalls)
        assertTrue(pipeline.timings.keys.containsAll(listOf("detect", "classify", "recognise")))
    }

    @Test
    fun aTurnedLineKeepsItsGlyphRelativeCorners() = runImmediate {
        val result = Ocr2Pipeline(Scripted(listOf(intArrayOf(100, 300, 499, 339)), turned = true), labels).recognize(white(736, 736))
        val line = result.lines.single()
        assertEquals("你好 A", line.text)
        // Read right to left in the image, the quad turned with the text.
        assertEquals(line.xs().sortedDescending(), line.xs())
        assertTrue(line.box.topLeft.x > line.box.bottomRight.x && line.box.topLeft.y > line.box.bottomRight.y)
        val first = line.chars.first().box
        assertTrue(first.topLeft.x > first.topRight.x && first.topLeft.y > first.bottomLeft.y)
        assertTrue(first.center.x > 400f)
    }

    @Test
    fun aTallBoxIsAColumnReadTopToBottom() = runImmediate {
        val result = Ocr2Pipeline(Scripted(listOf(intArrayOf(300, 100, 339, 499))), labels).recognize(white(736, 736))
        val line = result.lines.single()
        assertEquals(true, line.vertical)
        assertEquals(line.ys().sorted(), line.ys())
        // Upright glyphs: corners in image order.
        val first = line.chars.first().box
        assertTrue(first.topLeft.x < first.topRight.x && first.topLeft.y < first.bottomLeft.y)
        assertEquals(273f, first.topLeft.x, 1.5f)
        assertEquals(366f, first.topRight.x, 1.5f)
        assertTrue(line.box.topLeft.y < line.box.bottomLeft.y)
    }

    @Test
    fun smallImagesAreEnlargedForTheDetectorAndBoxesScaledBack() = runImmediate {
        // 368 x 176 is doubled: the kernel is given in detector pixels.
        val nets = Scripted(listOf(intArrayOf(100, 200, 499, 239)))
        val result = Ocr2Pipeline(nets, labels).recognize(white(368, 176))
        assertEquals(736 to 352, nets.detectorSize)
        val line = result.lines.single()
        assertEquals(73f / 2, line.box.topLeft.x, 1f)
        assertEquals(173f / 2, line.box.topLeft.y, 1f)
        assertEquals(526f / 2, line.box.bottomRight.x, 1f)
        assertTrue(line.chars.all { it.box.center.x < 368f && it.box.center.y < 176f })
    }

    @Test
    fun flatImagesArePaddedAndBoxesMovedBack() = runImmediate {
        // 800 x 60 (wider than 8:1) gets (max(800 / 8, 30) x 2 - 60) / 2 = 70 black rows above and
        // below; the 800 x 200 result is doubled for the detector (400 rows rounded to 384).
        val nets = Scripted(listOf(intArrayOf(200, 180, 1000, 200)))
        val result = Ocr2Pipeline(nets, labels).recognize(white(800, 60))
        assertEquals(1600 to 384, nets.detectorSize)
        val line = result.lines.single()
        assertTrue(line.box.topLeft.y >= 0f && line.box.bottomLeft.y <= 60f, "inside the image: ${line.box}")
        assertEquals(30f, line.box.center.y, 4f)
    }

    @Test
    fun unsureAndEmptyLinesAreDropped() = runImmediate {
        assertTrue(Ocr2Pipeline(Scripted(listOf(intArrayOf(100, 300, 499, 339)), probability = 0.45f), labels).recognize(white(736, 736)).lines.isEmpty())
        assertTrue(Ocr2Pipeline(Scripted(emptyList()), labels).recognize(white(736, 736)).lines.isEmpty())
    }

    @Test
    fun linesAreBatchedByAspectAndColumnsFollowRows() = runImmediate {
        val kernels = ArrayList<IntArray>()
        // Seven rows of growing length and one column at the right.
        for (i in 0 until 7) kernels.add(intArrayOf(40, 40 + i * 90, 240 + i * 50, 69 + i * 90))
        kernels.add(intArrayOf(680, 100, 709, 500))
        val nets = Scripted(kernels)
        val result = Ocr2Pipeline(nets, labels).recognize(white(736, 736))
        assertEquals(8, result.lines.size)
        assertEquals(listOf(false, false, false, false, false, false, false, true), result.lines.map { it.vertical })
        assertEquals(result.lines.take(7).map { it.box.topLeft.y }.sorted(), result.lines.take(7).map { it.box.topLeft.y })
        // One line per call, each padded to its batch: six lines, then the two widest.
        assertEquals(List(8) { 1 }, nets.recogniserCalls.map { it.first })
        val widths = nets.recogniserCalls.map { it.second }
        assertEquals(1, widths.take(6).toSet().size)
        assertEquals(1, widths.drop(6).toSet().size)
        assertTrue(widths[6] > widths[0])
        // Whole batches per call read the same.
        val batched = Scripted(kernels)
        val same = Ocr2Pipeline(batched, labels, Ocr2Options(linesPerCall = 6)).recognize(white(736, 736))
        assertEquals(listOf(6 to widths[0], 2 to widths[6]), batched.recogniserCalls)
        assertEquals(result, same)
    }

    @Test
    fun theCheckpointCancelsBetweenStages() = runImmediate {
        val nets = Scripted(listOf(intArrayOf(100, 300, 499, 339)))
        var calls = 0
        assertFailsWith<IllegalStateException> {
            Ocr2Pipeline(nets, labels).recognize(white(736, 736)) { if (++calls == 3) error("cancelled") }
        }
        assertTrue(nets.recogniserCalls.isEmpty())
    }

    @Test
    fun modelCallsCanSuspendWithoutLosingTheLineOrAlternatives() = runImmediate {
        val kernels = listOf(intArrayOf(100, 300, 499, 339))
        val scripted = Scripted(kernels)
        val detector = Gate()
        val classifier = Gate()
        val recogniser = Gate()
        val calls = ArrayList<String>()
        val nets = object : Ocr2Nets by scripted {
            override suspend fun detect(height: Int, width: Int, fill: (FloatSink) -> Unit): FloatArray {
                calls.add("detect")
                detector.await()
                return scripted.detect(height, width, fill)
            }

            override suspend fun classify(batch: Int, fill: (FloatSink) -> Unit): FloatArray {
                calls.add("classify")
                classifier.await()
                return scripted.classify(batch, fill)
            }

            override suspend fun recognize(batch: Int, width: Int, fill: (FloatSink) -> Unit): RecognitionOutput {
                calls.add("recognize")
                recogniser.await()
                return scripted.recognize(batch, width, fill)
            }
        }
        var completion: Result<OcrResult>? = null
        val recognize: suspend () -> OcrResult = { Ocr2Pipeline(nets, labels).recognize(white(736, 736)) }
        recognize.startCoroutine(object : Continuation<OcrResult> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<OcrResult>) { completion = result }
        })
        assertEquals(listOf("detect"), calls)
        assertEquals(null, completion)
        detector.release()
        assertEquals(listOf("detect", "classify"), calls)
        assertEquals(null, completion)
        classifier.release()
        assertEquals(listOf("detect", "classify", "recognize"), calls)
        assertTrue(scripted.recogniserCalls.isEmpty())
        assertEquals(null, completion)
        recogniser.release()
        val expected = Ocr2Pipeline(Scripted(kernels), labels).recognize(white(736, 736))
        assertEquals(expected, checkNotNull(completion).getOrThrow())
    }
}
