package com.szdict.app.feature.ocr

import androidx.compose.ui.graphics.ImageBitmap
import com.szdict.app.AppEnv
import com.szdict.app.AppSession
import com.szdict.app.feature.conversion.CountingDictionary
import com.szdict.app.feature.conversion.FakeScriptConverter
import com.szdict.app.platform.Clipboard
import com.szdict.app.platform.LinkOpener
import com.szdict.app.platform.NoAudioPlayer
import com.szdict.app.platform.NoSpeaker
import com.szdict.app.platform.SettingsStorage
import com.szdict.app.platform.Translator
import com.szdict.recognition.OcrAlt
import com.szdict.recognition.OcrChar
import com.szdict.recognition.OcrImage
import com.szdict.recognition.OcrLine
import com.szdict.recognition.OcrResult
import com.szdict.recognition.Point
import com.szdict.recognition.Quad
import com.szdict.recognition.TextRecognizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

fun box(left: Float, top: Float, right: Float, bottom: Float) =
    Quad(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom))

/** A horizontal line of 10 px glyphs starting at ([x], [y]); `alternatives[i]` lists the other readings of symbol i. */
fun line(text: String, x: Float = 0f, y: Float = 0f, alternatives: Map<Int, List<String>> = emptyMap()): OcrLine {
    val chars = text.mapIndexed { i, c ->
        val others = alternatives[i].orEmpty()
        val alts = listOf(OcrAlt(c.toString(), 0.5f)) + others.mapIndexed { k, t -> OcrAlt(t, 0.4f / (k + 1)) }
        OcrChar(c.toString(), box(x + i * 10f, y, x + i * 10f + 10f, y + 10f), 0.5f, alts)
    }
    return OcrLine(text, box(x, y, x + text.length * 10f, y + 10f), chars)
}

class FakeImage(override val width: Int, override val height: Int = 4) : WorkingImage {
    var released = false
    override val bitmap: ImageBitmap? = null
    override fun toOcrImage() = OcrImage(width, height, IntArray(width * height))
    override fun release() { released = true }
}

/** Results are keyed by image width; [delayMs] simulates a slow model. */
class FakeRecognizer : TextRecognizer {
    val results = HashMap<Int, OcrResult>()
    val started = ArrayList<Int>()
    val finished = ArrayList<Int>()
    var delayMs = 0L
    var fail = false
    var closed = false

    override suspend fun recognize(image: OcrImage): OcrResult {
        started += image.width
        if (delayMs > 0) delay(delayMs)
        if (fail) throw IllegalStateException("model broke")
        finished += image.width
        return results[image.width] ?: OcrResult(image.width, image.height, emptyList())
    }

    override fun close() { closed = true }
}

class FakePlatform(val recognizer: FakeRecognizer = FakeRecognizer()) : OcrPlatform {
    /** URI → image width; an unknown URI fails to decode. */
    val images = HashMap<String, Int>()
    val decoded = ArrayList<ImageRef>()
    val produced = ArrayList<FakeImage>()
    val saved = ArrayList<ByteArray>()
    var decodeDelayMs = 0L
    var saveSucceeds = true
    var clipboard: String? = null
    var cameraWidth = 100

    override fun createRecognizer(): TextRecognizer = recognizer

    override suspend fun decode(ref: ImageRef): WorkingImage {
        decoded += ref
        if (decodeDelayMs > 0) delay(decodeDelayMs)
        val width = when (ref) {
            is ImageRef.Encoded -> cameraWidth
            is ImageRef.Uri -> images[ref.uri] ?: throw IllegalArgumentException("not an image")
        }
        return FakeImage(width).also { produced += it }
    }

    override suspend fun savePhoto(jpeg: ByteArray): Boolean {
        saved += jpeg
        return saveSucceeds
    }

    override fun clipboardImage(): String? = clipboard
}

class OcrFixture(scope: TestScope, timeoutMs: Long = 20_000) {
    val platform = FakePlatform()
    val recognizer get() = platform.recognizer
    val dictionary = CountingDictionary()
    val converter = FakeScriptConverter()
    val session = AppSession(
        AppEnv(
            openDictionary = { dictionary },
            settingsStorage = object : SettingsStorage {
                override val values: Flow<Map<String, String>> = flowOf(emptyMap())
                override suspend fun update(changes: Map<String, String?>) {}
                override fun flushBlocking(changes: Map<String, String?>) {}
            },
            audio = NoAudioPlayer,
            speaker = NoSpeaker,
            clipboard = object : Clipboard {
                override val hasText: Flow<Boolean> = MutableStateFlow(false)
                override fun refresh() {}
                override fun readText(): String? = null
                override fun writeText(text: String) {}
            },
            translator = object : Translator { override fun translate(text: String, sourceLanguageTag: String) {} },
            links = object : LinkOpener { override fun open(url: String) {} },
        ),
        scope.backgroundScope,
    )
    val holder = OcrStateHolder(session, platform, converter, StandardTestDispatcher(scope.testScheduler), timeoutMs)

    fun result(width: Int, vararg lines: OcrLine) {
        recognizer.results[width] = OcrResult(width, 4, lines.toList())
    }
}
