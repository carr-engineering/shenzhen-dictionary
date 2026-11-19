package com.szdict.app.feature.handwriting

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.szdict.app.AppEnv
import com.szdict.app.AppSession
import com.szdict.app.feature.ocr.ModelDownload
import com.szdict.core.api.DictMode
import com.szdict.core.api.LatestOnly
import com.szdict.core.api.ScoredText
import com.szdict.core.api.Script
import com.szdict.recognition.HandwritingRecognizer
import com.szdict.recognition.InkModel
import com.szdict.recognition.InkPoint
import com.szdict.recognition.InkStroke
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Platform side of handwriting (ARCH-093): the bundled recogniser. Registered under [KEY]. */
fun interface HandwritingPlatform {
    /** A new recogniser; the model loads on its first use, off the UI thread (HW-035). */
    fun createRecognizer(): HandwritingRecognizer

    /** Web: the model is fetched when the sheet first opens (ARCH-093); null where it ships with the app, and once ready. */
    val modelDownload: ModelDownload? get() = null

    /** The sheet was opened: fetch and load the model if that is still to do. */
    fun prepare() {}

    companion object {
        const val KEY = "handwriting.platform"
        fun of(env: AppEnv): HandwritingPlatform? = env.service(KEY)
    }
}

/** HW-031: the model follows dictionary mode and script. */
fun inkModel(mode: DictMode, script: Script): InkModel = when {
    script == Script.SIMPLIFIED -> InkModel.SIMPLIFIED
    mode == DictMode.CANTONESE -> InkModel.HONG_KONG
    else -> InkModel.TRADITIONAL
}

/**
 * Ink and candidates of the handwriting sheet (HW-010…HW-036). Ink lives in memory only and is
 * never logged, persisted or transmitted (HW-040). It survives the sheet being hidden by the word
 * sheet and configuration changes (HW-020, HW-025), and is discarded when the sheet closes or the
 * mode or script changes (HW-024, HW-031).
 *
 * Nothing is ever committed automatically (HW-016): text reaches the field only through [commit].
 */
class HandwritingState(
    private val session: AppSession,
    private val platform: HandwritingPlatform? = HandwritingPlatform.of(session.env),
    observeSession: Boolean = true,
) {
    /** Finished strokes, in writing order. */
    var strokes: List<InkStroke> by mutableStateOf(emptyList())
        private set

    /** Points of the stroke being drawn. */
    val live = mutableStateListOf<InkPoint>()

    /** Up to [SHOWN] candidates, best first (HW-013). */
    var candidates: List<String> by mutableStateOf(emptyList())
        private set

    /** A recognition is running (also covers the first model load). */
    var busy: Boolean by mutableStateOf(false)
        private set

    /** The recogniser failed (HW-036); typing is unaffected. */
    var failed: Boolean by mutableStateOf(false)
        private set

    val isEmpty: Boolean get() = strokes.isEmpty() && live.isEmpty()

    /** Web: the model's first-use download or load in progress, or its failure (ARCH-093). */
    val modelDownload: ModelDownload? get() = platform?.modelDownload

    fun prepare() {
        platform?.prepare()
    }

    private var area: Pair<Float, Float> = 0f to 0f
    private var recognizer: HandwritingRecognizer? = null

    private class Request(val strokes: List<InkStroke>, val area: Pair<Float, Float>, val model: InkModel)

    // Latest-only per ink state (HW-034): a result for an older stroke set is never shown.
    private val recognition = LatestOnly<Request, List<String>>(session.scope, ::recognise) { request, result ->
        busy = false
        if (request.strokes !== strokes) return@LatestOnly
        val value = result.getOrNull()
        failed = value == null
        candidates = value ?: emptyList()
    }

    private val jobs: List<Job> = if (!observeSession) emptyList() else listOf(
        // A mode or script change switches the model and clears the ink (HW-031).
        session.scope.launch {
            session.settings.map { it.dictionaryMode to it.script }.distinctUntilChanged().drop(1).collect { clear() }
        },
        // Closing the sheet discards the ink; being hidden by the word sheet keeps it (HW-020, HW-024).
        session.scope.launch {
            snapshotFlow { session.handwritingVisible || session.wordSheet != null }.distinctUntilChanged().collect { kept ->
                if (!kept) clear()
            }
        },
    )

    fun setArea(width: Float, height: Float) {
        area = width to height
    }

    fun beginStroke(x: Float, y: Float, t: Long) {
        live.clear()
        live += InkPoint(x, y, t)
    }

    fun extendStroke(x: Float, y: Float, t: Long) {
        if (live.isEmpty()) return
        val last = live.last()
        if (last.x == x && last.y == y) return
        live += InkPoint(x, y, t)
    }

    /** Pointer up: the stroke joins the ink and the whole ink is recognised again (HW-012). */
    fun endStroke() {
        if (live.isEmpty()) return
        strokes = strokes + InkStroke(live.toList())
        live.clear()
        recogniseInk()
    }

    /** Abandons the stroke in progress (the gesture was cancelled). */
    fun cancelStroke() = live.clear()

    /** `Undo stroke` (HW-014). */
    fun undo() {
        if (strokes.isEmpty()) return
        strokes = strokes.dropLast(1)
        recogniseInk()
    }

    /** `Clear`: removes all ink and candidates (HW-014). */
    fun clear() {
        live.clear()
        strokes = emptyList()
        recogniseInk()
    }

    /** Tapping a candidate inserts it at the cursor and clears the pad (HW-015). */
    fun commit(candidate: String) {
        if (candidate.isEmpty()) return
        session.handwritingSink?.insert(candidate)
        clear()
    }

    /** `Delete`: a backspace in the field; the ink is untouched (HW-014). */
    fun delete() {
        session.handwritingSink?.deleteBackward()
    }

    private fun recogniseInk() {
        if (strokes.isEmpty()) {
            recognition.invalidate()
            candidates = emptyList()
            busy = false
            failed = false
            return
        }
        busy = true
        recognition.submit(Request(strokes, area, inkModel(session.mode, session.script)))
    }

    private suspend fun recognise(request: Request): List<String> {
        val engine = recognizer ?: (platform?.createRecognizer() ?: error("Handwriting is unavailable")).also { recognizer = it }
        // HW-032: ask for more than is shown; retain digits and use dataset frequency to rank Hanzi.
        val raw = engine.recognize(request.strokes, request.area, request.model, REQUESTED)
        val ranked = session.dictionary().rankHandwritingCandidates(raw.map { ScoredText(it.text, it.score.toDouble()) })
        return ranked.map { it.text }.distinct().take(SHOWN)
    }

    fun close() {
        jobs.forEach { it.cancel() }
        recognition.close()
        recognizer?.close()
        recognizer = null
    }

    companion object {
        const val SHOWN = 10
        const val REQUESTED = 30
        private var current: HandwritingState? = null
        private var currentSession: AppSession? = null

        /** The state of [session], created on first use and kept for the life of the process. */
        fun of(session: AppSession): HandwritingState {
            current?.let { if (currentSession === session) return it else it.close() }
            currentSession = session
            return HandwritingState(session).also { current = it }
        }
    }
}
