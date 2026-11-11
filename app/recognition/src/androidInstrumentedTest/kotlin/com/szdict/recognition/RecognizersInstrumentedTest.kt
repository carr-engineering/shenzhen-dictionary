package com.szdict.recognition

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Both recognisers end to end on a device: bundled models through LiteRT, on the reference
 * implementation's test images and ink drawings. Alternatives and timings are logged under the
 * `RecognitionTest` tag.
 */
@RunWith(AndroidJUnit4::class)
class RecognizersInstrumentedTest {
    companion object {
        private const val TAG = "RecognitionTest"
        private lateinit var text: TextRecognizer
        private lateinit var ink: HandwritingRecognizer

        // Library test APKs carry the library's own assets and the test assets in one package.
        private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

        @BeforeClass
        @JvmStatic
        fun open() {
            val context = InstrumentationRegistry.getInstrumentation().context
            text = Recognizers.text(context)
            ink = Recognizers.handwriting(context)
        }

        @AfterClass
        @JvmStatic
        fun close() {
            text.close()
            ink.close()
        }
    }

    private fun image(name: String): OcrImage =
        assets.open("testdata/$name").use { BitmapFactory.decodeStream(it) }.toOcrImage()

    private fun describe(result: OcrResult): String = buildString {
        for (line in result.lines) {
            append("  ").append(line.text).append(if (line.vertical) "  [vertical]\n" else "\n")
            for (c in line.chars) {
                append("    ").append(c.text).append("  ")
                append(c.alternatives.joinToString(" ") { "${it.text}:${"%.2f".format(it.score)}" }).append('\n')
            }
        }
    }

    @Test
    fun ocrReadsReferenceImagesWithConsistentStructure() = runBlocking {
        val cold = SystemClock.elapsedRealtime()
        text.recognize(image("hello-world-zh.png")) // loads the models
        Log.i(TAG, "OCR first call including model load: ${SystemClock.elapsedRealtime() - cold} ms")
        val failures = ArrayList<String>()
        for (case in OcrCases.all) {
            val img = image(case.file)
            val start = SystemClock.elapsedRealtime()
            val result = text.recognize(img)
            val ms = SystemClock.elapsedRealtime() - start
            Log.i(TAG, "OCR ${case.file} ${img.width}x${img.height}: $ms ms, ${result.lines.size} lines\n${describe(result)}")
            if (!case.matches(result.text)) failures.add("${case.file}: expected <${case.expected}> got <${result.text}>")
            assertEquals(img.width, result.width)
            assertEquals(img.height, result.height)
            for (line in result.lines) {
                assertEquals(line.text, line.chars.joinToString("") { it.text }, "line text is the concatenation of its symbols")
                assertTrue(line.chars.isNotEmpty())
                for (c in line.chars) {
                    assertEquals(c.text, c.alternatives.first().text)
                    assertEquals(c.confidence, c.alternatives.first().score)
                    assertTrue(c.alternatives.size in 5..8, "five to eight candidates per character")
                    assertTrue(c.confidence > 0f && c.confidence <= 1f)
                    assertTrue(c.alternatives.zipWithNext().all { (a, b) -> a.score >= b.score }, "alternatives are best first")
                    for (p in c.box.points) {
                        assertTrue(p.x >= -3f && p.x <= img.width + 3f && p.y >= -3f && p.y <= img.height + 3f, "glyph box inside the image: $p")
                    }
                    val centre = c.box.center
                    assertTrue(c.box.contains(centre.x, centre.y))
                }
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun ocrOffersLookAlikeCharactersAsAlternatives() = runBlocking {
        val result = text.recognize(image("similar.png"))
        Log.i(TAG, "OCR similar.png alternatives\n${describe(result)}")
        assertEquals(2, result.lines.size)
        fun alternatives(line: Int, index: Int) = result.lines[line].chars[index].alternatives.map { it.text }
        // 我已经吃过了: 已 / 己 / 巳 are near-identical; all must be offered at that position.
        assertTrue(alternatives(0, 1).take(4).containsAll(listOf("已", "己", "巳")), "已/己/巳: ${alternatives(0, 1)}")
        // 未来的土地很大: 未 / 末 and 土 / 士.
        assertTrue(alternatives(1, 0).containsAll(listOf("未", "末")), "未/末: ${alternatives(1, 0)}")
        assertTrue(alternatives(1, 3).take(3).containsAll(listOf("土", "士")), "土/士: ${alternatives(1, 3)}")
        // Glyph boxes run left to right without overlapping their neighbours' centres.
        for (line in result.lines) {
            val centres = line.chars.map { it.box.center.x }
            assertEquals(centres.sorted(), centres)
        }
    }

    @Test
    fun ocrBoxesFollowVerticalAndRotatedText() = runBlocking {
        val vertical = text.recognize(image("pipeline-vertical.png"))
        assertTrue(vertical.lines.all { it.vertical })
        for (line in vertical.lines) {
            val ys = line.chars.map { it.box.center.y }
            assertEquals(ys.sorted(), ys, "vertical glyphs run top to bottom")
        }
        // Columns read right to left.
        assertTrue(vertical.lines[0].box.center.x > vertical.lines[1].box.center.x)

        val upsideDown = text.recognize(image("pipeline-upside-down.png")).lines.single()
        // Reading order is preserved, so on the page the glyphs run right to left and the
        // glyph-relative top-left corner is at the bottom right.
        val xs = upsideDown.chars.map { it.box.center.x }
        assertEquals(xs.sortedDescending(), xs)
        assertTrue(upsideDown.box.topLeft.x > upsideDown.box.bottomRight.x && upsideDown.box.topLeft.y > upsideDown.box.bottomRight.y)
    }

    @Test
    fun handwritingRanksReferenceDrawings() = runBlocking {
        val golden = InkGolden.parse(assets.open("testdata/ink-fixtures.txt").use { it.readBytes() }.toString(Charsets.UTF_8))
        assertEquals(25, golden.cases.size)
        val cold = SystemClock.elapsedRealtime()
        ink.recognize(golden.cases[0].strokes, 480f to 480f, InkModel.SIMPLIFIED) // loads the model
        Log.i(TAG, "INK first call including model load: ${SystemClock.elapsedRealtime() - cold} ms")
        var top1 = 0
        var top3 = 0
        val times = ArrayList<Long>()
        for (case in golden.cases) {
            val start = SystemClock.elapsedRealtimeNanos()
            val candidates = ink.recognize(case.strokes, 480f to 480f, InkModel.SIMPLIFIED, limit = 30)
            times.add((SystemClock.elapsedRealtimeNanos() - start) / 1000)
            Log.i(TAG, "INK ${case.expected} ${times.last()} us -> " + candidates.joinToString(" ") { "${it.text}:${"%.2f".format(it.score)}" })
            assertEquals(30, candidates.size, "30 ranked candidates for ${case.expected}")
            assertEquals(candidates.map { it.text }.toSet().size, candidates.size)
            assertTrue(candidates.zipWithNext().all { (a, b) -> a.score >= b.score })
            assertTrue(candidates.all { it.score <= 0f && it.score.isFinite() })
            // Same first candidate as the reference engine (Google's runtime) on every drawing.
            assertEquals(case.referenceTop.first(), candidates.first().text, "first candidate for ${case.expected}")
            val texts = candidates.map { it.text }
            if (texts.first() == case.expected) top1++
            assertTrue(case.expected in texts.take(3), "${case.expected} within the first three: ${texts.take(5)}")
            top3++
        }
        times.sort()
        Log.i(TAG, "INK intended top-1 $top1/25, top-3 $top3/25, median ${times[times.size / 2]} us, max ${times.last()} us")
        assertTrue(top1 >= 23)
        // The script models share the network and differ only in ranking bias: a character common
        // to both scripts (十) leads in all of them.
        for (model in InkModel.entries) assertEquals("十", ink.recognize(golden.cases[3].strokes, 0f to 0f, model, 5).first().text)
        assertEquals(emptyList(), ink.recognize(emptyList(), 0f to 0f, InkModel.SIMPLIFIED))
    }

    @Test
    fun handwritingIsStatelessAcrossDrawings() = runBlocking {
        val golden = InkGolden.parse(assets.open("testdata/ink-fixtures.txt").use { it.readBytes() }.toString(Charsets.UTF_8))
        val first = ink.recognize(golden.cases[4].strokes, 0f to 0f, InkModel.SIMPLIFIED)
        ink.recognize(golden.cases[1].strokes, 0f to 0f, InkModel.SIMPLIFIED)
        assertEquals(first, ink.recognize(golden.cases[4].strokes, 0f to 0f, InkModel.SIMPLIFIED))
        ink.recognize(golden.cases[7].strokes, 0f to 0f, InkModel.SIMPLIFIED)
        assertEquals(first, ink.recognize(golden.cases[4].strokes, 0f to 0f, InkModel.SIMPLIFIED))
    }
}
