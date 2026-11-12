package com.szdict.recognition

import com.szdict.recognition.ink.InkNet
import com.szdict.recognition.ink.InkPipeline
import com.szdict.recognition.ink.ScriptClasses
import com.szdict.recognition.ocr.DetectorOutput
import com.szdict.recognition.ocr.OcrNets
import com.szdict.recognition.ocr.OcrOptions
import com.szdict.recognition.ocr.OcrPipeline
import com.szdict.recognition.ocr.SequenceModel
import com.szdict.recognition.ocr.SequenceOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** Where a browser model set stands (CAM-134, ARCH-093). */
sealed interface WebModelState {
    /** Not asked for yet, or released. */
    data object Idle : WebModelState

    /** First use (or a browser that evicted the stored copy): the files are being downloaded. */
    data class Downloading(val loadedBytes: Long, val totalBytes: Long) : WebModelState

    /** The verified files are being loaded into the worker. */
    data object Loading : WebModelState

    data object Ready : WebModelState

    /** The files are neither stored nor reachable, or failed verification or loading; [WebModel.retry] tries again. */
    data class Failed(val message: String?) : WebModelState
}

/**
 * One model set of the browser build — the OCR graphs or the handwriting network — with the worker
 * that runs it. The files are fetched on first use, verified against `models/manifest.json`
 * (SHA-256), kept in Cache Storage and read from there afterwards; see `recognition-worker.js`.
 */
class WebModel internal constructor(private val kind: String) {
    internal class Session(val worker: WorkerClient, val labels: List<String>, val classes: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow<WebModelState>(WebModelState.Idle)
    private var session: Session? = null
    private var loading: Job? = null

    // The published manifest is compared with the stored one at most once per page session.
    private var checked = false

    val state: StateFlow<WebModelState> get() = mutableState

    /** Starts fetching and loading unless that is under way or done. A failure stays until [retry]. */
    fun prepare() {
        if (session == null && loading == null && mutableState.value !is WebModelState.Failed) start()
    }

    /** Tries again after a failure. */
    fun retry() {
        if (session == null && loading == null) start()
    }

    private fun start() {
        mutableState.value = WebModelState.Loading
        val worker = WorkerClient(WORKER_URL)
        loading = scope.launch {
            try {
                val reply = worker.load(kind, check = !checked) { loaded, total ->
                    mutableState.value = if (loaded < 0) WebModelState.Loading else WebModelState.Downloading(loaded, total)
                }
                checked = true
                session = Session(worker, listOf(replyLabel(reply, 0), replyLabel(reply, 1)), replyInt(reply, "classes"))
                loading = null
                mutableState.value = WebModelState.Ready
            } catch (e: CancellationException) {
                worker.terminate()
                throw e
            } catch (e: Throwable) {
                worker.terminate()
                loading = null
                mutableState.value = WebModelState.Failed(e.message)
            }
        }
    }

    /**
     * The loaded model. With [waitForRetry] a failure is waited out (the caller's UI offers the
     * retry); without it a failure is thrown.
     */
    internal suspend fun await(waitForRetry: Boolean): Session {
        // Without a retry control, every new use after a failure is another attempt.
        if (waitForRetry) prepare() else retry()
        while (true) {
            val settled = mutableState.first { it is WebModelState.Ready || it is WebModelState.Idle || (!waitForRetry && it is WebModelState.Failed) }
            when (settled) {
                is WebModelState.Failed -> throw IllegalStateException(settled.message ?: "The recognition model is unavailable")
                is WebModelState.Idle -> prepare()   // released meanwhile
                else -> session?.let { return it }
            }
        }
    }

    /** Suspends until the model is ready to recognise, through failures and retries. */
    suspend fun awaitReady() {
        await(waitForRetry = true)
    }

    /** Stops the worker and frees the model; the stored files stay, so the next use needs no network. */
    fun release() {
        loading?.cancel()
        loading = null
        session?.worker?.terminate()
        session = null
        mutableState.value = WebModelState.Idle
    }

    private companion object {
        /** Relative to the document base (the app shell's root). */
        const val WORKER_URL = "recognition-worker.js"
    }
}

/**
 * Entry points for the browser recognisers. Both run fully in the page (a dedicated worker with
 * the TFLite Web runtime); the only network requests are the model files and their manifest.
 */
object WebRecognizers {
    /** The seven OCR graphs with their label tables, and the runtime. */
    val textModel = WebModel("ocr")

    /** The Digital Ink network with its label table, and the runtime. */
    val handwritingModel = WebModel("ink")

    /** Milliseconds per pipeline stage of the last OCR call (diagnostics). */
    var lastTextTimings: Map<String, Double> = emptyMap()
        private set

    /**
     * OCR with per-character alternatives; same pipeline, graphs and decoding as on Android.
     * [TextRecognizer.recognize] waits for [textModel]; [TextRecognizer.close] releases it.
     */
    fun text(alternatives: Int = 8): TextRecognizer = WebTextRecognizer(textModel, alternatives)

    /** Single-character handwriting recognition on [handwritingModel]; a model failure is thrown by `recognize`. */
    fun handwriting(): HandwritingRecognizer = WebHandwritingRecognizer(handwritingModel)

    internal fun timings(value: Map<String, Double>) {
        lastTextTimings = value
    }
}

private fun labels(text: String): List<String> = text.split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }

private class WorkerOcrNets(private val worker: WorkerClient) : OcrNets {
    override suspend fun detect(gray: ByteArray, width: Int, height: Int): DetectorOutput {
        val reply = worker.detect(gray, width, height)
        return DetectorOutput(replyBytes(reply, "data"), replyInt(reply, "rows"), replyInt(reply, "cols"), replyFloat(reply, "scale"), replyInt(reply, "zeroPoint"))
    }

    override suspend fun sequence(model: SequenceModel, columns: ByteArray, width: Int): SequenceOutput {
        val reply = worker.sequence(model.assetPrefix, columns, width)
        return SequenceOutput(replyBytes(reply, "data"), replyInt(reply, "frames"), replyInt(reply, "classes"), replyFloat(reply, "scale"), replyInt(reply, "zeroPoint"))
    }

    override fun close() = Unit   // the worker belongs to the WebModel
}

private class WorkerInkNet(private val worker: WorkerClient, override val classes: Int) : InkNet {
    override suspend fun infer(features: FloatArray, frames: Int): FloatArray = replyFloats(worker.ink(features, frames), "logits")

    override fun close() = Unit
}

private class WebTextRecognizer(private val model: WebModel, private val alternatives: Int) : TextRecognizer {
    private val mutex = Mutex()
    private var closed = false
    private var loaded: Pair<WebModel.Session, OcrPipeline>? = null

    override suspend fun recognize(image: OcrImage): OcrResult {
        check(!closed) { "Recognizer is closed" }
        val session = model.await(waitForRetry = true)
        return mutex.withLock {
            check(!closed) { "Recognizer is closed" }
            val pipeline = loaded?.takeIf { it.first === session }?.second
                ?: OcrPipeline(WorkerOcrNets(session.worker), labels(session.labels[0]), labels(session.labels[1]), OcrOptions(alternatives = alternatives))
                    .also { loaded = session to it }
            val context = coroutineContext
            try {
                pipeline.recognize(image) { context.ensureActive() }
            } finally {
                WebRecognizers.timings(pipeline.timings.toMap())
            }
        }
    }

    override fun close() {
        closed = true
        loaded = null
        model.release()
    }
}

private class WebHandwritingRecognizer(private val model: WebModel) : HandwritingRecognizer {
    private val mutex = Mutex()
    private var closed = false
    private var loaded: Pair<WebModel.Session, InkPipeline>? = null

    // All three script models share the zh-Hani network and differ in ranking bias; see InkModel.
    override suspend fun recognize(strokes: List<InkStroke>, writingArea: Pair<Float, Float>, model: InkModel, limit: Int): List<InkCandidate> {
        check(!closed) { "Recognizer is closed" }
        val session = this.model.await(waitForRetry = false)
        return mutex.withLock {
            check(!closed) { "Recognizer is closed" }
            val pipeline = loaded?.takeIf { it.first === session }?.second
                ?: labels(session.labels[0]).let { table -> InkPipeline(WorkerInkNet(session.worker, session.classes), table, ScriptClasses(table, session.labels[1])) }
                    .also { loaded = session to it }
            pipeline.recognize(strokes, limit, model)
        }
    }

    override fun close() {
        closed = true
        loaded = null
        model.release()
    }
}
