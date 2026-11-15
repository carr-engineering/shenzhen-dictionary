package com.szdict.recognition

import com.szdict.recognition.ink.InkPipeline
import com.szdict.recognition.ink.ScriptClasses
import com.szdict.recognition.ocr2.Ocr2Options
import com.szdict.recognition.ocr2.Ocr2Pipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSProcessInfo
import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext

/**
 * Entry points for the bundled recognisers on iOS. Both run fully offline: OCR on ONNX Runtime,
 * handwriting on the TensorFlow Lite C runtime. `modelDir` is the absolute path of the directory
 * holding `ocr2/` and `ink/` (the same files as the Android `assets/models/`).
 */
object Recognizers {
    /**
     * OCR with per-character alternatives. Models (16 MB, `ocr2/`) are loaded on the first
     * [TextRecognizer.recognize] call and released by [TextRecognizer.close].
     *
     * @param alternatives how many candidates to keep per character, including the best one.
     */
    fun text(modelDir: String, alternatives: Int = 8): TextRecognizer = IosTextRecognizer(modelDir, alternatives)

    /**
     * Single-character handwriting recognition. The model (7.8 MB) is loaded on the first
     * [HandwritingRecognizer.recognize] call and released by [HandwritingRecognizer.close].
     */
    fun handwriting(modelDir: String): HandwritingRecognizer = IosHandwritingRecognizer(modelDir)
}

private fun workerThreads(): Int = NSProcessInfo.processInfo.activeProcessorCount.toInt().coerceIn(1, 4)

/**
 * Lazily loaded native resources shared by serialised calls. [close] never blocks: if a call is
 * running, the resources are released as soon as it finishes.
 */
private class Guarded<T : Any>(private val load: () -> T, private val release: (T) -> Unit) {
    private val mutex = Mutex()
    private var value: T? = null

    @Volatile
    private var closed = false

    suspend fun <R> use(block: suspend (T) -> R): R = mutex.withLock {
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

internal class IosTextRecognizer(private val modelDir: String, private val alternatives: Int) : TextRecognizer {
    private class Loaded(val nets: OrtOcr2Nets, val pipeline: Ocr2Pipeline)

    private val guarded = Guarded(
        load = {
            val nets = OrtOcr2Nets(modelDir, workerThreads())
            try {
                Loaded(nets, Ocr2Pipeline(nets, readLabels("$modelDir/ocr2/rec-labels.txt"), Ocr2Options(alternatives = alternatives)))
            } catch (e: Throwable) {
                nets.close()
                throw e
            }
        },
        release = { it.nets.close() },
    )

    /** Milliseconds per pipeline stage of the last call (diagnostics for the tests). */
    @Volatile
    internal var lastTimings: Map<String, Double> = emptyMap()

    override suspend fun recognize(image: OcrImage): OcrResult = withContext(Dispatchers.Default) {
        val context = coroutineContext
        guarded.use { loaded ->
            try {
                loaded.pipeline.recognize(image) { context.ensureActive() }
            } finally {
                lastTimings = loaded.pipeline.timings.toMap()
            }
        }
    }

    override fun close() = guarded.close()
}

internal class IosHandwritingRecognizer(private val modelDir: String) : HandwritingRecognizer {
    private class Loaded(val net: TfLiteInkNet, val pipeline: InkPipeline)

    private val guarded = Guarded(
        load = {
            val net = TfLiteInkNet(modelDir, workerThreads().coerceAtMost(2))
            try {
                val labels = readLabels("$modelDir/ink/labels.txt")
                val scripts = readFile("$modelDir/ink/script-classes.txt").decodeToString()
                Loaded(net, InkPipeline(net, labels, ScriptClasses(labels, scripts)))
            } catch (e: Throwable) {
                net.close()
                throw e
            }
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
