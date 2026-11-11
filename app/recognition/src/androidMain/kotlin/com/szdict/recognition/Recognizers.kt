package com.szdict.recognition

import android.content.Context
import android.graphics.Bitmap
import com.szdict.recognition.ink.InkPipeline
import com.szdict.recognition.ocr.OcrOptions
import com.szdict.recognition.ocr.OcrPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Entry points for the bundled recognisers. Both run fully offline on models packaged under
 * `assets/models/`; nothing is downloaded and no Google Play services are involved.
 */
object Recognizers {
    /**
     * OCR with per-character alternatives. Models (about 1.9 MB) are loaded on the first
     * [TextRecognizer.recognize] call and released by [TextRecognizer.close].
     *
     * @param alternatives how many candidates to keep per character, including the best one.
     */
    fun text(context: Context, alternatives: Int = 8): TextRecognizer = AndroidTextRecognizer(context.applicationContext, alternatives)

    /**
     * Single-character handwriting recognition. The model (7.8 MB) is loaded on the first
     * [HandwritingRecognizer.recognize] call and released by [HandwritingRecognizer.close].
     */
    fun handwriting(context: Context): HandwritingRecognizer = AndroidHandwritingRecognizer(context.applicationContext)
}

/** Copies the bitmap's pixels into the platform-neutral [OcrImage]. The bitmap must be upright. */
fun Bitmap.toOcrImage(): OcrImage {
    val source = if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    // Composite any transparency on white, the page colour the pipeline assumes.
    if (source.hasAlpha()) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24
            if (a != 255) {
                val r = (((p shr 16) and 0xFF) * a + 255 * (255 - a)) / 255
                val g = (((p shr 8) and 0xFF) * a + 255 * (255 - a)) / 255
                val b = ((p and 0xFF) * a + 255 * (255 - a)) / 255
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
    return OcrImage(source.width, source.height, pixels)
}

private fun workerThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

internal class AndroidTextRecognizer(private val context: Context, private val alternatives: Int) : TextRecognizer {
    private val mutex = Mutex()
    private var nets: LiteRtOcrNets? = null
    private var pipeline: OcrPipeline? = null
    private var closed = false

    override suspend fun recognize(image: OcrImage): OcrResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            check(!closed) { "TextRecognizer is closed" }
            val active = pipeline ?: run {
                val assets = context.assets
                val loaded = LiteRtOcrNets(assets, workerThreads())
                nets = loaded
                OcrPipeline(
                    loaded,
                    assets.loadLabels("$MODEL_DIR/ocr/hani-labels.txt"),
                    assets.loadLabels("$MODEL_DIR/ocr/latn-labels.txt"),
                    OcrOptions(alternatives = alternatives),
                ).also { pipeline = it }
            }
            val context = coroutineContext
            active.recognize(image) { context.ensureActive() }
        }
    }

    override fun close() {
        // Waits for a running recognition to reach its end (or its next cancellation point).
        kotlinx.coroutines.runBlocking {
            mutex.withLock {
                closed = true
                pipeline = null
                nets?.close()
                nets = null
            }
        }
    }
}

internal class AndroidHandwritingRecognizer(private val context: Context) : HandwritingRecognizer {
    private val mutex = Mutex()
    private var net: LiteRtInkNet? = null
    private var pipeline: InkPipeline? = null
    private var closed = false

    override suspend fun recognize(
        strokes: List<InkStroke>,
        writingArea: Pair<Float, Float>,
        model: InkModel,
        limit: Int,
    ): List<InkCandidate> = withContext(Dispatchers.Default) {
        mutex.withLock {
            check(!closed) { "HandwritingRecognizer is closed" }
            val active = pipeline ?: run {
                val assets = context.assets
                val loaded = LiteRtInkNet(assets, workerThreads().coerceAtMost(2))
                net = loaded
                InkPipeline(loaded, assets.loadLabels("$MODEL_DIR/ink/labels.txt")).also { pipeline = it }
            }
            // All three scripts share the zh-Hani network; see InkModel.
            active.recognize(strokes, limit)
        }
    }

    override fun close() {
        kotlinx.coroutines.runBlocking {
            mutex.withLock {
                closed = true
                pipeline = null
                net?.close()
                net = null
            }
        }
    }
}
