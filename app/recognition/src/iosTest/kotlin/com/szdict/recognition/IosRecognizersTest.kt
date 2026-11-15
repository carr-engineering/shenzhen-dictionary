@file:OptIn(ExperimentalForeignApi::class)

package com.szdict.recognition

import com.szdict.recognition.ink.SingleCharCtc
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageOrientation
import platform.posix.getenv
import platform.zlib.gzclose
import platform.zlib.gzopen
import platform.zlib.gzread
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Both recognisers end to end on the iOS simulator: the bundled models through ONNX Runtime (OCR)
 * and the TensorFlow Lite C runtime (handwriting), on the reference tensors, test images and ink drawings (the same cases
 * as the Android unit and instrumented tests). The directories come from the environment, see
 * build.gradle.kts; the simulator reads them straight from the host file system.
 */
class IosRecognizersTest {
    private fun dir(name: String): String =
        getenv(name)?.toKString()?.takeIf { it.isNotEmpty() } ?: error("$name is not set (run through ./gradlew :recognition:iosSimulatorArm64Test)")

    private val modelDir get() = dir("RECOGNITION_MODEL_DIR")
    private val goldenDir get() = dir("RECOGNITION_GOLDEN_DIR")
    private val testdataDir get() = dir("RECOGNITION_TESTDATA_DIR")

    private fun gunzip(path: String): ByteArray {
        val file = gzopen(path, "rb") ?: error("Cannot open $path")
        try {
            var out = ByteArray(0)
            val chunk = ByteArray(1 shl 16)
            while (true) {
                val n = chunk.usePinned { gzread(file, it.addressOf(0), chunk.size.toUInt()) }
                check(n >= 0) { "Cannot inflate $path" }
                if (n == 0) break
                out += chunk.copyOf(n)
            }
            return out
        } finally {
            gzclose(file)
        }
    }

    private fun text(path: String): String = readFile(path).decodeToString()

    private fun image(name: String): OcrImage = (UIImage.imageWithContentsOfFile("$testdataDir/$name") ?: error("Cannot decode $name")).toOcrImage()

    private fun fixed(value: Float, digits: Int): String {
        var scale = 1L
        repeat(digits) { scale *= 10 }
        val scaled = floor(abs(value.toDouble()) * scale + .5).toLong()
        val sign = if (value < 0 && scaled != 0L) "-" else ""
        return if (digits == 0) "$sign$scaled" else "$sign${scaled / scale}.${(scaled % scale).toString().padStart(digits, '0')}"
    }

    private fun describe(result: OcrResult): String = buildString {
        for (line in result.lines) {
            append("   ").append(line.text).append(if (line.vertical) "  [vertical]\n" else "\n")
            for (c in line.chars) {
                append("      ").append(c.text).append("  ")
                append(c.alternatives.joinToString(" ") { "${it.text}:${fixed(it.score, 2)}" }).append('\n')
            }
        }
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Double> {
        val mark = TimeSource.Monotonic.markNow()
        val value = block()
        return value to mark.elapsedNow().inWholeMicroseconds / 1000.0
    }

    // ---- the graphs against tensors exported from the host reference interpreter -------------

    @Test
    fun inkNetworkReproducesHostLogitsAndScores() = runBlocking {
        val golden = InkGolden.parse(text("$testdataDir/ink-fixtures.txt"))
        val labels = readLabels("$modelDir/ink/labels.txt")
        val net = TfLiteInkNet(modelDir, 2)
        try {
            assertEquals(labels.size + 1, net.classes)
            // Raw logits of one drawing (十).
            val head = text("$goldenDir/ink-logits.txt").split('\n').first().split(' ').map { it.toInt() }
            val reference = gunzip("$goldenDir/ink-logits.bin.gz")
            val case = golden.cases.first { it.expected == "十" }
            val logits = net.infer(case.features, case.features.size / 10)
            assertEquals(head[0] * head[1], logits.size)
            var worst = 0f
            for (i in logits.indices) {
                val bits = (reference[4 * i].toInt() and 0xFF) or ((reference[4 * i + 1].toInt() and 0xFF) shl 8) or
                    ((reference[4 * i + 2].toInt() and 0xFF) shl 16) or ((reference[4 * i + 3].toInt() and 0xFF) shl 24)
                worst = maxOf(worst, abs(logits[i] - Float.fromBits(bits)))
            }
            println("INK logits: largest difference from the host interpreter $worst")
            assertTrue(worst < 1e-3f, "logits differ from the host interpreter by $worst")
            // Reference scores of the first ten candidates of every drawing.
            var worstScore = 0f
            for (c in golden.cases) {
                val frames = c.features.size / 10
                val scores = SingleCharCtc.scores(net.infer(c.features, frames), frames, net.classes)
                c.referenceTop.forEachIndexed { i, label ->
                    val d = abs(scores[labels.indexOf(label)] - c.referenceScores[i])
                    worstScore = maxOf(worstScore, d)
                    assertTrue(d < 2e-3f, "${c.expected}: score of $label differs by $d")
                }
            }
            println("INK scores: largest difference from the reference $worstScore")
        } finally {
            net.close()
        }
    }

    // ---- the public recognisers, as in the Android instrumented tests ------------------------

    @Test
    fun ocrReadsReferenceImagesWithConsistentStructure() = runBlocking {
        val recognizer = Recognizers.text(modelDir)
        try {
            val (first, decodeMs) = timed { image("hello-world-zh.png") }
            println("first image decoded in ${fixed(decodeMs.toFloat(), 0)} ms")
            val (_, cold) = timed { recognizer.recognize(first) } // loads the models
            println("OCR first call including model load: ${fixed(cold.toFloat(), 0)} ms")
            val failures = ArrayList<String>()
            var total = 0.0
            for (case in Ocr2Cases.all) {
                val img = image(case.file)
                val (result, ms) = timed { recognizer.recognize(img) }
                total += ms
                println("== ${case.file} (${img.width}x${img.height}) ${fixed(ms.toFloat(), 0)} ms pixels ${img.argb.contentHashCode()}")
                print(describe(result))
                println("OCR stages ${case.file}: " + (recognizer as IosTextRecognizer).lastTimings.entries.joinToString(" ") { "${it.key}=${fixed(it.value.toFloat(), 0)}" })
                if (!case.matches(result.text)) failures.add("${case.file}: expected <${case.expected}> got <${result.text}>")
                assertEquals(img.width, result.width)
                assertEquals(img.height, result.height)
                for (line in result.lines) {
                    assertEquals(line.text, line.chars.joinToString("") { it.text }, "line text is the concatenation of its symbols")
                    assertTrue(line.chars.isNotEmpty())
                    for (c in line.chars) {
                        assertEquals(c.text, c.alternatives.first().text)
                        assertEquals(c.confidence, c.alternatives.first().score)
                        assertEquals(8, c.alternatives.size, "eight candidates per character")
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
            println("OCR ${Ocr2Cases.all.size} images in ${fixed(total.toFloat(), 0)} ms")
            assertEquals(emptyList(), failures)
        } finally {
            recognizer.close()
        }
    }

    @Test
    fun ocrOffersLookAlikesAndFollowsVerticalAndRotatedText() = runBlocking {
        val recognizer = Recognizers.text(modelDir)
        try {
            val result = recognizer.recognize(image("similar.png"))
            assertEquals(2, result.lines.size)
            fun alternatives(line: Int, index: Int) = result.lines[line].chars[index].alternatives.map { it.text }
            assertEquals(listOf("已", "己", "巳"), alternatives(0, 1).take(3))
            assertTrue(alternatives(1, 0).take(4).containsAll(listOf("未", "末")), "未/末: ${alternatives(1, 0)}")
            assertTrue(alternatives(1, 3).containsAll(listOf("土", "士")), "土/士: ${alternatives(1, 3)}")
            for (line in result.lines) {
                val centres = line.chars.map { it.box.center.x }
                assertEquals(centres.sorted(), centres)
            }

            val vertical = recognizer.recognize(image("vertical-tight.png"))
            assertEquals(4, vertical.lines.size)
            assertTrue(vertical.lines.all { it.vertical })
            for (line in vertical.lines) {
                val ys = line.chars.map { it.box.center.y }
                assertEquals(ys.sorted(), ys, "vertical glyphs run top to bottom")
            }
            assertTrue(vertical.lines[0].box.center.x > vertical.lines[1].box.center.x)

            val upsideDown = recognizer.recognize(image("pipeline-upside-down.png")).lines.single()
            val xs = upsideDown.chars.map { it.box.center.x }
            assertEquals(xs.sortedDescending(), xs)
            assertTrue(upsideDown.box.topLeft.x > upsideDown.box.bottomRight.x && upsideDown.box.topLeft.y > upsideDown.box.bottomRight.y)
        } finally {
            recognizer.close()
        }
    }

    @Test
    fun handwritingRanksReferenceDrawings() = runBlocking {
        val golden = InkGolden.parse(text("$testdataDir/ink-fixtures.txt"))
        assertEquals(25, golden.cases.size)
        val ink = Recognizers.handwriting(modelDir)
        try {
            val (_, cold) = timed { ink.recognize(golden.cases[0].strokes, 480f to 480f, InkModel.SIMPLIFIED) } // loads the model
            println("INK first call including model load: ${fixed(cold.toFloat(), 0)} ms")
            var top1 = 0
            val times = ArrayList<Double>()
            for (case in golden.cases) {
                val (candidates, ms) = timed { ink.recognize(case.strokes, 480f to 480f, InkModel.SIMPLIFIED, limit = 30) }
                times.add(ms)
                println("INK ${case.expected} ${fixed(ms.toFloat(), 1)} ms -> " + candidates.joinToString(" ") { "${it.text}:${fixed(it.score, 2)}" })
                assertEquals(30, candidates.size, "30 ranked candidates for ${case.expected}")
                assertEquals(candidates.map { it.text }.toSet().size, candidates.size)
                assertTrue(candidates.zipWithNext().all { (a, b) -> a.score >= b.score })
                assertTrue(candidates.all { it.score <= 0f && it.score.isFinite() })
                // Same first candidate as the reference engine on every drawing.
                assertEquals(case.referenceTop.first(), candidates.first().text, "first candidate for ${case.expected}")
                val texts = candidates.map { it.text }
                if (texts.first() == case.expected) top1++
                assertTrue(case.expected in texts.take(3), "${case.expected} within the first three: ${texts.take(5)}")
            }
            times.sort()
            println("INK intended top-1 $top1/25, median ${fixed(times[times.size / 2].toFloat(), 1)} ms, max ${fixed(times.last().toFloat(), 1)} ms")
            assertTrue(top1 >= 23)
            for (model in InkModel.entries) assertEquals("十", ink.recognize(golden.cases[3].strokes, 0f to 0f, model, 5).first().text)
            assertEquals(emptyList(), ink.recognize(emptyList(), 0f to 0f, InkModel.SIMPLIFIED))
            // One drawing does not influence the next.
            val first = ink.recognize(golden.cases[4].strokes, 0f to 0f, InkModel.SIMPLIFIED)
            ink.recognize(golden.cases[1].strokes, 0f to 0f, InkModel.SIMPLIFIED)
            assertEquals(first, ink.recognize(golden.cases[4].strokes, 0f to 0f, InkModel.SIMPLIFIED))
        } finally {
            ink.close()
        }
    }

    @Test
    fun cancellationAndCloseDoNotBlock() = runBlocking {
        val recognizer = Recognizers.text(modelDir)
        // A dense page tiled 2 x 2: long enough to be interrupted on any machine.
        val tile = image("pipeline-page.png")
        val page = OcrImage(tile.width * 2, tile.height * 2, IntArray(tile.width * tile.height * 4) {
            val x = it % (tile.width * 2)
            val y = it / (tile.width * 2)
            tile.argb[(y % tile.height) * tile.width + x % tile.width]
        })
        recognizer.recognize(image("hello-world-zh.png"))
        // Cancelled between lines; the recogniser stays usable.
        val cancelled = async(Dispatchers.Default) { recognizer.recognize(page) }
        delay(30)
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled, "the call was cancelled")
        assertEquals("你好世界", recognizer.recognize(image("hello-world-zh.png")).text)
        // Closed while a call runs: close returns at once, the call ends normally or by the closed check.
        val running = async(Dispatchers.Default) { runCatching { recognizer.recognize(page) } }
        delay(30)
        val (_, closeMs) = timed { recognizer.close() }
        assertTrue(closeMs < 20.0, "close took $closeMs ms")
        val outcome = running.await()
        assertTrue(outcome.isSuccess || outcome.exceptionOrNull() !is CancellationException, "outcome $outcome")
        assertFailsWith<IllegalStateException> { recognizer.recognize(page) }
        recognizer.close()
    }

    // ---- image conversion --------------------------------------------------------------------

    @Test
    fun imageConversionAppliesOrientationLikeUIKit() {
        // 3 x 2 stored pixels with distinct colours.
        val colours = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0x00FFFF, 0x202020)
        val rgbx = ByteArray(3 * 2 * 4)
        for (i in colours.indices) {
            rgbx[4 * i] = (colours[i] shr 16).toByte()
            rgbx[4 * i + 1] = (colours[i] shr 8).toByte()
            rgbx[4 * i + 2] = colours[i].toByte()
            rgbx[4 * i + 3] = 0xFF.toByte()
        }
        val stored = rgbx.usePinned { pinned ->
            val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
            val context = CGBitmapContextCreate(pinned.addressOf(0), 3u, 2u, 8u, 12u, space, CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value)
            CGColorSpaceRelease(space)
            CGBitmapContextCreateImage(context).also { CGContextRelease(context) }
        }!!
        try {
            val plain = stored.toOcrImage()
            assertContentEquals(colours.map { it or (0xFF shl 24) }, plain.argb.toList())
            for (orientation in UIImageOrientation.entries) {
                val oriented = UIImage.imageWithCGImage(stored, 1.0, orientation)
                val actual = oriented.toOcrImage()
                // UIKit's own upright rendering of the same image.
                val size = CGSizeMake(actual.width.toDouble(), actual.height.toDouble())
                UIGraphicsBeginImageContextWithOptions(size, true, 1.0)
                oriented.drawInRect(CGRectMake(0.0, 0.0, actual.width.toDouble(), actual.height.toDouble()))
                val rendered = UIGraphicsGetImageFromCurrentImageContext()!!.toOcrImage()
                UIGraphicsEndImageContext()
                assertEquals(actual.width, rendered.width, "$orientation")
                assertEquals(actual.height, rendered.height, "$orientation")
                for (i in actual.argb.indices) {
                    val a = actual.argb[i]
                    val b = rendered.argb[i]
                    val close = (0..2).all { abs(((a shr (8 * it)) and 0xFF) - ((b shr (8 * it)) and 0xFF)) <= 3 }
                    assertTrue(close, "$orientation pixel $i: ${a.toString(16)} vs ${b.toString(16)}")
                }
            }
        } finally {
            CGImageRelease(stored)
        }
    }
}
