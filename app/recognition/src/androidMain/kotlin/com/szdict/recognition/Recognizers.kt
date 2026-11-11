package com.szdict.recognition

import android.content.Context
import android.graphics.Bitmap
import com.szdict.recognition.ink.InkPipeline
import com.szdict.recognition.ink.ScriptClasses
import com.szdict.recognition.ocr.OcrOptions
import com.szdict.recognition.ocr.OcrPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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

/**
 * Lazily loaded native resources shared by serialised calls. [close] never blocks: if a call is
 * running, the resources are released as soon as it finishes.
 */
private class Guarded<T : Any>(private val load: () -> T, private val release: (T) -> Unit) {
    private val mutex = Mutex()
    private var value: T? = null

    @Volatile
    private var closed = false

    suspend fun <R> use(block: (T) -> R): R = mutex.withLock {
        check(!closed) { "Recognizer is closed" }
        try {
            block(value ?: load().also { value = it })
        } finally {
            if (closed) releaseNow()
        }
    }

    fun close() {
        closed = true
        if (mutex.tryLock()) {
            try {
                releaseNow()
            } finally {
                mutex.unlock()
            }
        } else {
            // A call is running: release once it has finished (it may also do so itself).
            CoroutineScope(Dispatchers.Default).launch { mutex.withLock { releaseNow() } }
        }
    }

    private fun releaseNow() {
        value?.let(release)
        value = null
    }
}

internal class AndroidTextRecognizer(private val context: Context, private val alternatives: Int) : TextRecognizer {
    private class Loaded(val nets: LiteRtOcrNets, val pipeline: OcrPipeline)

    private val guarded = Guarded(
        load = {
            val assets = context.assets
            val nets = LiteRtOcrNets(assets, workerThreads())
            Loaded(
                nets,
                OcrPipeline(
                    nets,
                    assets.loadLabels("$MODEL_DIR/ocr/hani-labels.txt"),
                    assets.loadLabels("$MODEL_DIR/ocr/latn-labels.txt"),
                    OcrOptions(alternatives = alternatives),
                ),
            )
        },
        release = { it.nets.close() },
    )

    /** Milliseconds per pipeline stage of the last call (diagnostics for the instrumented tests). */
    @Volatile
    internal var lastTimings: Map<String, Double> = emptyMap()

    override suspend fun recognize(image: OcrImage): OcrResult = withContext(Dispatchers.Default) {
        val context = coroutineContext
        guarded.use { loaded ->
            loaded.pipeline.recognize(image) { context.ensureActive() }.also { lastTimings = loaded.pipeline.timings.toMap() }
        }
    }

    override fun close() = guarded.close()
}

internal class AndroidHandwritingRecognizer(private val context: Context) : HandwritingRecognizer {
    private class Loaded(val net: LiteRtInkNet, val pipeline: InkPipeline)

    private val guarded = Guarded(
        load = {
            val assets = context.assets
            val net = LiteRtInkNet(assets, workerThreads().coerceAtMost(2))
            val labels = assets.loadLabels("$MODEL_DIR/ink/labels.txt")
            val scripts = assets.open("$MODEL_DIR/ink/script-classes.txt").use { it.readBytes() }.toString(Charsets.UTF_8)
            Loaded(net, InkPipeline(net, labels, ScriptClasses(labels, scripts)))
        },
        release = { it.net.close() },
    )

    // All three script models share the zh-Hani network and differ in ranking bias; see InkModel.
    override suspend fun recognize(
        strokes: List<InkStroke>,
        writingArea: Pair<Float, Float>,
        model: InkModel,
        limit: Int,
    ): List<InkCandidate> = withContext(Dispatchers.Default) {
        guarded.use { it.pipeline.recognize(strokes, limit, model) }
    }

    override fun close() = guarded.close()
}
