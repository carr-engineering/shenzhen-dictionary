@file:OptIn(ExperimentalWasmJsInterop::class)

package com.szdict.app.feature.ocr

import com.szdict.app.platform.consoleLog
import com.szdict.recognition.InkModel
import com.szdict.recognition.InkPoint
import com.szdict.recognition.InkStroke
import com.szdict.recognition.OcrResult
import com.szdict.recognition.WebRecognizers
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.TimeSource

private fun jsFetchBlob(url: String): Promise<JsAny?> = js("fetch(url).then((response) => (response.ok ? response.blob() : null), () => null)")

private fun jsBlobText(blob: JsAny): Promise<JsString> = js("blob.text()")

private fun jsParameter(search: String, name: String): String? = js("new URLSearchParams(search).get(name)")

/**
 * `?selftest=recognition&base=testdata/&ocr=a.png,b.png&ink=ink-fixtures.txt`: runs the browser
 * recognisers on the recognition module's reference images and ink drawings (served under `base`)
 * and logs `[selftest] …` lines in the format of the Android instrumented tests, so the results can
 * be compared with them. Ends with `[selftest] recognition done`.
 */
internal object RecognitionSelfTest {
    private fun score(value: Float): String {
        val hundredths = (abs(value) * 100).roundToInt()
        return (if (value < 0 && hundredths != 0) "-" else "") + "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }

    private fun describe(result: OcrResult): String = buildString {
        for (line in result.lines) {
            append("  ").append(line.text).append(if (line.vertical) "  [vertical]\n" else "\n")
            for (c in line.chars) {
                append("    ").append(c.text).append("  ")
                append(c.alternatives.joinToString(" ") { "${it.text}:${score(it.score)}" }).append('\n')
            }
        }
    }

    suspend fun run(search: String) {
        val base = jsParameter(search, "base") ?: "testdata/"
        try {
            jsParameter(search, "ocr")?.split(',')?.filter { it.isNotEmpty() }?.let { ocr(base, it) }
            jsParameter(search, "ink")?.let { ink(base + it) }
        } catch (e: Throwable) {
            consoleLog("[selftest] FAILED ${e::class.simpleName}: ${e.message}")
        }
        consoleLog("[selftest] recognition done")
    }

    private suspend fun ocr(base: String, files: List<String>) {
        val recognizer = WebRecognizers.text(alternatives = 8)
        val cold = TimeSource.Monotonic.markNow()
        WebRecognizers.textModel.awaitReady()
        consoleLog("[selftest] ocr model ready: ${cold.elapsedNow().inWholeMilliseconds} ms")
        for (file in files) {
            val blob = jsFetchBlob(base + file).await<JsAny?>()
            if (blob == null) {
                consoleLog("[selftest] ocr $file MISSING")
                continue
            }
            val decodeMark = TimeSource.Monotonic.markNow()
            val image = decodeWorkingImage(blob)
            val decodeMs = decodeMark.elapsedNow().inWholeMilliseconds
            val pixelsMark = TimeSource.Monotonic.markNow()
            val pixels = image.toOcrImage()
            val pixelsMs = pixelsMark.elapsedNow().inWholeMilliseconds
            val mark = TimeSource.Monotonic.markNow()
            val result = recognizer.recognize(pixels)
            val ms = mark.elapsedNow().inWholeMilliseconds
            image.release()
            val stages = WebRecognizers.lastTextTimings.entries.joinToString(" ") { "${it.key}=${it.value.roundToInt()}" }
            consoleLog("[selftest] ocr $file ${pixels.width}x${pixels.height}: $ms ms, ${result.lines.size} lines (decode $decodeMs ms, pixels $pixelsMs ms; $stages)")
            consoleLog("[selftest] ocr-text $file ${result.text.replace("\n", "\\n")}")
            consoleLog("[selftest] ocr-detail $file\n${describe(result)}")
        }
    }

    private suspend fun ink(url: String) {
        val blob = jsFetchBlob(url).await<JsAny?>()
        if (blob == null) {
            consoleLog("[selftest] ink fixtures MISSING")
            return
        }
        val recognizer = WebRecognizers.handwriting()
        val cold = TimeSource.Monotonic.markNow()
        var expected = ""
        var strokes = ArrayList<InkStroke>()
        var first = true
        for (line in jsBlobText(blob).await<JsString>().toString().split('\n')) {
            val parts = line.trim().split(' ')
            when (parts[0]) {
                "case" -> {
                    expected = parts[1]
                    strokes = ArrayList()
                }
                "stroke" -> {
                    val values = parts.drop(1).map { it.toFloat() }
                    strokes.add(InkStroke(List(values.size / 2) { InkPoint(values[2 * it], values[2 * it + 1], 20L * it) }))
                }
                "top" -> {
                    if (first) {
                        recognizer.recognize(strokes, 480f to 480f, InkModel.SIMPLIFIED)
                        consoleLog("[selftest] ink first call including model load: ${cold.elapsedNow().inWholeMilliseconds} ms")
                        first = false
                    }
                    val mark = TimeSource.Monotonic.markNow()
                    val candidates = recognizer.recognize(strokes, 480f to 480f, InkModel.SIMPLIFIED, limit = 30)
                    val micros = mark.elapsedNow().inWholeMicroseconds
                    consoleLog(
                        "[selftest] ink $expected ${micros / 1000}.${(micros % 1000 / 100)} ms reference=${parts.getOrNull(1)} -> " +
                            candidates.joinToString(" ") { "${it.text}:${score(it.score)}" },
                    )
                }
            }
        }
    }
}
