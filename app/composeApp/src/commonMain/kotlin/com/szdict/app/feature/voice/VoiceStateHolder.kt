package com.szdict.app.feature.voice

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.szdict.app.AppSession
import com.szdict.app.WordSheetRequest
import com.szdict.app.feature.conversion.ConversionPair
import com.szdict.app.feature.conversion.ConversionRules
import com.szdict.app.feature.conversion.ConversionSelection
import com.szdict.app.feature.conversion.DetectedKind
import com.szdict.app.feature.conversion.DetectedScript
import com.szdict.app.feature.conversion.ScriptConverter
import com.szdict.app.feature.conversion.ScriptConverters
import com.szdict.app.feature.conversion.ScriptVariant
import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.LatestOnly
import com.szdict.core.api.PassageSegment
import com.szdict.core.api.Script
import com.szdict.core.api.SegmentationMode
import kotlin.coroutines.CoroutineContext
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One line of the transcript (VOICE-030). [source] is the recognised text, [display] what is shown
 * (the source or its script conversion, VOICE-034) and [passage] the analysis of [display] — or,
 * while a newer analysis is pending, the previous analysis applied to its prefix, with the newly
 * appended characters unmatched (VOICE-033, GLB-030). [settled]: [passage] analyses all of [display].
 */
@Immutable
data class VoiceUtterance(
    val id: Long,
    val source: String,
    val display: String,
    val passage: AnalysedPassage?,
    val settled: Boolean,
)

enum class VoicePhase {
    IDLE,

    /** Start was tapped; the permission prompt may be showing. */
    STARTING,
    LISTENING,

    /** Capture has ended; the recogniser is committing what it has. */
    STOPPING,
}

enum class VoiceModelState { UNLOADED, LOADING, READY, FAILED }

/** Recoverable failures shown in the status line (GLB-034); [label] is the English text. */
enum class VoiceError(val label: String) {
    MIC_UNAVAILABLE("The microphone is unavailable right now."),
    MODEL_UNAVAILABLE("Voice transcription is unavailable."),
}

/**
 * State of the Voice destination (VOICE-001…VOICE-096). It outlives the screen's composable so the
 * transcript survives destination switches and configuration changes (VOICE-060); nothing is
 * persisted. The screen calls [enter] / [leave]; everything else is driven by the ports.
 *
 * Operation families (VOICE-080): recogniser events are applied in order on the UI thread; the
 * live utterance is converted and analysed latest-only; every committed utterance is analysed by
 * its own job, never superseded by live updates.
 *
 * Call every method from the UI thread of the session's scope. State is Compose snapshot state.
 */
class VoiceStateHolder(
    private val session: AppSession,
    private val services: VoiceServices? = session.env.service(VoiceServices.KEY),
    converter: ScriptConverter? = null,
    private val analysisContext: CoroutineContext = Dispatchers.Default,
) {
    private val converter: ScriptConverter by lazy { converter ?: ScriptConverters.get(session.env) }
    private val scope = session.scope

    /** False on a platform or build without the voice services: the destination is unavailable. */
    val available: Boolean get() = services != null

    // ---- transcript ----

    private val _utterances = mutableStateListOf<VoiceUtterance>()

    /** Committed utterances, oldest first. Their ids and (for a given conversion) offsets never change. */
    val utterances: List<VoiceUtterance> get() = _utterances

    /** The utterance being spoken, or null. */
    var live: VoiceUtterance? by mutableStateOf(null)
        private set

    val isEmpty: Boolean get() = _utterances.isEmpty() && live == null

    // ---- listening ----

    var phase: VoicePhase by mutableStateOf(VoicePhase.IDLE)
        private set
    var modelState: VoiceModelState by mutableStateOf(VoiceModelState.UNLOADED)
        private set
    var packState: VoicePackState by mutableStateOf(services?.modelPack?.state?.value ?: VoicePackState.Failed(retryable = false))
        private set
    var permissionStatus: MicPermissionStatus by mutableStateOf(MicPermissionStatus.NOT_REQUESTED)
        private set
    var error: VoiceError? by mutableStateOf(null)
        private set

    /** Input level 0…1 while listening (VOICE-003). */
    var inputLevel: Float by mutableFloatStateOf(0f)
        private set

    /** Whole seconds since the current recording started; 0 outside one (reset on each start). */
    var elapsedSeconds: Int by mutableIntStateOf(0)
        private set

    val listening: Boolean get() = phase == VoicePhase.LISTENING

    /** The start button is disabled until the model is on the device (VOICE-002). */
    val canStart: Boolean get() = services != null && packState == VoicePackState.Present && phase != VoicePhase.STOPPING

    /** English status line text (VOICE-002), or null; plain listening is shown by the record pill. */
    val status: String?
        get() = when {
            error != null -> error?.label
            phase == VoicePhase.LISTENING && modelState != VoiceModelState.READY -> "Preparing…"
            else -> null
        }

    // ---- script conversion (VOICE-096) ----

    /** One selection for the whole transcript; the recogniser's output is Simplified (VOICE-034). */
    var selection: ConversionSelection by mutableStateOf(defaultSelection())
        private set
    private var userChose = false

    // ---- selection: anchored by (utterance, offset) so it survives every transcript change (VOICE-031) ----

    private data class Anchor(val id: Long, val offset: Int)

    private var anchor: Anchor? by mutableStateOf(null)

    // ---- internals ----

    private class Rendered(val source: String, val display: String, val passage: AnalysedPassage?)
    private data class LiveRequest(val id: Long, val source: String, val pair: ConversionPair?)

    private val liveRunner = LatestOnly<LiveRequest, Rendered>(scope, { render(it.source, it.pair) }, ::applyLive)
    private val commitJobs = HashMap<Long, Job>()
    private var reanalysisJob: Job? = null
    private var nextId = 1L

    private var entered = false
    private var foreground = true
    private var listenJob: Job? = null
    private var modelJob: Job? = null
    private var stopSignal: CompletableDeferred<Unit>? = null

    /** The recogniser's current utterance text as reported, and the part of it dropped by `Clear`. */
    private var rawLive = ""
    private var discardPrefix = ""

    /** The mode and script the transcript was last converted and analysed for. */
    private var analysedFor = session.mode to session.script

    private val jobs: List<Job> = listOfNotNull(
        // A mode or script change re-analyses everything; listening continues (VOICE-092).
        scope.launch {
            session.settings.map { it.dictionaryMode to it.script }.distinctUntilChanged().collect {
                if (it == analysedFor) return@collect
                analysedFor = it
                if (!userChose) selection = defaultSelection()
                reanalyseAll()
            }
        },
        // Recording and playback are exclusive (VOICE-063, MED-002): playback started elsewhere ends listening.
        scope.launch { session.playing.collect { if (it != null) stopListening() } },
        services?.let { s ->
            scope.launch {
                s.modelPack.state.collect { state ->
                    packState = state
                    if (state == VoicePackState.Present) {
                        if (entered && foreground) ensureModel()
                    } else {
                        // The platform removed the pack (VOICE-095): back to the download panel.
                        stopOrRelease()
                    }
                }
            }
        },
        services?.let { s ->
            scope.launch {
                s.foreground.foreground.collect { fg ->
                    foreground = fg
                    if (!fg) {
                        stopOrRelease()   // VOICE-062: no background recording
                    } else if (entered) {
                        refreshPermission()   // VOICE-011: re-checked on every resume
                        s.modelPack.refresh()
                        if (s.modelPack.state.value == VoicePackState.Present) ensureModel()
                    }
                }
            }
        },
    )

    // ------------------------------------------------------------------ destination lifecycle

    /** The destination was opened (VOICE-022, VOICE-095). Never starts listening (VOICE-003). */
    fun enter() {
        entered = true
        val s = services ?: return
        refreshPermission()
        s.modelPack.refresh()
        when (s.modelPack.state.value) {
            VoicePackState.Present -> if (foreground) ensureModel()
            is VoicePackState.Missing -> s.modelPack.request()
            else -> Unit
        }
    }

    /** The destination was left (VOICE-061): commit, stop the microphone, release the model. */
    fun leave() {
        entered = false
        stopOrRelease()
    }

    fun refreshPermission() {
        permissionStatus = services?.permission?.status() ?: return
    }

    fun openPermissionSettings() {
        services?.permission?.openSettings()
    }

    /** `Retry` / `Download now` of the download panel (VOICE-095). */
    fun requestModel() {
        services?.modelPack?.request()
    }

    private fun ensureModel() {
        val s = services ?: return
        if (modelState == VoiceModelState.LOADING || modelState == VoiceModelState.READY) return
        modelState = VoiceModelState.LOADING
        modelJob = scope.launch {
            modelState = try {
                s.recognizer.load()
                VoiceModelState.READY
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                VoiceModelState.FAILED
            }
        }
    }

    private fun releaseModel() {
        val s = services ?: return
        modelJob?.cancel()
        modelJob = null
        if (modelState != VoiceModelState.UNLOADED) {
            s.recognizer.release()
            modelState = VoiceModelState.UNLOADED
        }
    }

    /** Ends any session (committing the live utterance) and releases the model once it has ended. */
    private fun stopOrRelease() {
        when (phase) {
            VoicePhase.IDLE -> releaseModel()
            else -> stopListening()   // the session's end releases the model
        }
    }

    // ------------------------------------------------------------------ listening

    fun toggleListening() {
        if (phase == VoicePhase.IDLE) startListening() else stopListening()
    }

    /** Start (VOICE-003): asks for the microphone at this moment of use (VOICE-010), then listens. */
    fun startListening() {
        val s = services ?: return
        if (phase != VoicePhase.IDLE || packState != VoicePackState.Present) return
        error = null
        phase = VoicePhase.STARTING
        listenJob = scope.launch {
            try {
                var status = s.permission.status()
                if (status != MicPermissionStatus.GRANTED) status = s.permission.request()
                permissionStatus = status
                if (status != MicPermissionStatus.GRANTED || !entered || !foreground) return@launch
                session.stopPlayback()   // VOICE-063
                ensureModel()
                listen(s)
            } finally {
                phase = VoicePhase.IDLE
                inputLevel = 0f
                stopSignal = null
                rawLive = ""
                discardPrefix = ""
                if (!entered || !foreground) releaseModel()
            }
        }
    }

    /** Stop: ends capture at once; the recogniser then commits a non-empty live utterance (VOICE-025). */
    fun stopListening() {
        when (phase) {
            VoicePhase.STARTING -> listenJob?.cancel()
            VoicePhase.LISTENING -> {
                phase = VoicePhase.STOPPING
                inputLevel = 0f
                stopSignal?.complete(Unit)
            }
            else -> Unit
        }
    }

    private suspend fun listen(s: VoiceServices) = coroutineScope {
        val stop = CompletableDeferred<Unit>()
        stopSignal = stop
        phase = VoicePhase.LISTENING
        elapsedSeconds = 0
        val startedAt = TimeSource.Monotonic.markNow()
        val ticker = launch {
            while (true) {
                delay(TICK_MS)
                elapsedSeconds = startedAt.elapsedNow().inWholeSeconds.toInt()
            }
        }
        // Audio is buffered in memory until the model is ready (VOICE-022) and never dropped (VOICE-081).
        val audio = Channel<FloatArray>(Channel.UNLIMITED)
        val pump = launch {
            try {
                s.capture.frames().collect { frame ->
                    inputLevel = maxOf(levelOf(frame), inputLevel * LEVEL_DECAY)
                    audio.send(frame)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = VoiceError.MIC_UNAVAILABLE   // VOICE-012
            } finally {
                audio.close()
                // Ended by a failure or an interruption (VOICE-062): the button returns to Start.
                if (phase == VoicePhase.LISTENING) phase = VoicePhase.STOPPING
                inputLevel = 0f
            }
        }
        val stopper = launch {
            stop.await()
            pump.cancel()
        }
        try {
            modelJob?.join()
            if (modelState != VoiceModelState.READY) throw SpeechModelException()
            s.recognizer.transcribe(audio.receiveAsFlow()).collect(::onEvent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            modelState = VoiceModelState.FAILED   // the next start loads again (ARCH-122)
            error = VoiceError.MODEL_UNAVAILABLE   // VOICE-023
        } finally {
            ticker.cancel()
            stopper.cancel()
            pump.cancel()
        }
    }

    // ------------------------------------------------------------------ recogniser events

    private fun onEvent(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.Live -> {
                rawLive = event.text
                onLive(visible(event.text))
            }
            is SpeechEvent.Committed -> {
                val text = visible(event.text)
                rawLive = ""
                discardPrefix = ""
                onCommitted(text)
            }
            SpeechEvent.Endpoint -> {
                rawLive = ""
                discardPrefix = ""
                dropLive()
            }
        }
    }

    /** The recogniser's text without what `Clear` removed from the current utterance (VOICE-050). */
    private fun visible(raw: String): String {
        if (discardPrefix.isEmpty()) return raw.trim()
        if (!raw.startsWith(discardPrefix)) {
            discardPrefix = ""
            return raw.trim()
        }
        return raw.substring(discardPrefix.length).trim()
    }

    /** A recogniser update of the live utterance: shown at once, re-analysed latest-only (VOICE-033). */
    private fun onLive(text: String) {
        val current = live
        if (text.isBlank()) {
            if (current != null) dropLive()
            return
        }
        if (current != null && current.source == text) return
        val id = current?.id ?: nextId++
        // Keep the previous analysis on its prefix; the appended characters stay unmatched until
        // the next analysis arrives (GLB-030).
        val base = current?.takeIf { text.startsWith(it.source) }
        live = if (base == null) {
            VoiceUtterance(id, text, text, null, settled = false)
        } else {
            val display = base.display + text.substring(base.source.length)
            VoiceUtterance(id, text, display, base.passage?.copy(text = display), settled = false)
        }
        liveRunner.submit(LiveRequest(id, text, selection.applied))
        syncSheet()
    }

    private fun applyLive(request: LiveRequest, result: Result<Rendered>) {
        val rendered = result.getOrNull() ?: return
        val current = live ?: return
        if (current.id != request.id || current.source != rendered.source) return
        val before = current.display
        live = settledUtterance(current, rendered)
        // A length-changing conversion may move the selected character (VOICE-096): then the
        // selection is cleared and the sheet closes.
        val selected = anchor
        if (selected != null && selected.id == current.id && rendered.display.length != rendered.source.length) {
            val after = rendered.display
            val o = selected.offset
            if (o >= after.length || o >= before.length || codePointAt(before, o) != codePointAt(after, o)) closeSheet()
        }
        syncSheet()
    }

    /** An endpoint (or stop) closed the utterance: it is frozen and analysed once (VOICE-032). */
    private fun onCommitted(text: String) {
        if (text.isBlank()) {
            dropLive()
            return
        }
        onLive(text)
        val utterance = live ?: return
        live = null
        liveRunner.invalidate()
        _utterances += utterance
        // The live analysis is reused when it already covers the whole utterance.
        if (!utterance.settled) analyseCommitted(utterance.id, utterance.source)
        syncSheet()
    }

    private fun dropLive() {
        val current = live ?: return
        live = null
        liveRunner.invalidate()
        if (anchor?.id == current.id) closeSheet()
        syncSheet()
    }

    private fun analyseCommitted(id: Long, source: String) {
        val pair = selection.applied
        commitJobs[id] = scope.launch {
            val rendered = render(source, pair)
            commitJobs.remove(id)
            replaceCommitted(id, rendered)
        }
    }

    private fun replaceCommitted(id: Long, rendered: Rendered) {
        val index = _utterances.indexOfFirst { it.id == id }
        if (index < 0) return
        _utterances[index] = settledUtterance(_utterances[index], rendered)
        syncSheet()
    }

    private fun settledUtterance(previous: VoiceUtterance, rendered: Rendered): VoiceUtterance {
        // A failed analysis keeps the previous one when it is of the same text (GLB-034).
        val passage = rendered.passage ?: previous.passage?.takeIf { it.text == rendered.display }
        return VoiceUtterance(previous.id, rendered.source, rendered.display, passage, settled = rendered.passage != null)
    }

    /** Conversion then analysis of the displayed text, off the UI thread (VOICE-034, VOICE-096). */
    private suspend fun render(source: String, pair: ConversionPair?): Rendered {
        val display = if (pair == null) source else try {
            converter.convert(source, pair.source, pair.target).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            source   // a failed conversion keeps the original on screen (GLB-034)
        }
        val passage = try {
            withContext(analysisContext) { session.dictionary().analyse(display, SegmentationMode.HANZI) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        return Rendered(source, display, passage)
    }

    /** Mode, script, conversion or data changed: every utterance is converted and analysed again (VOICE-032). */
    private fun reanalyseAll() {
        closeSheet()
        commitJobs.values.forEach { it.cancel() }
        commitJobs.clear()
        reanalysisJob?.cancel()
        liveRunner.invalidate()
        val pair = selection.applied
        live?.let { liveRunner.submit(LiveRequest(it.id, it.source, pair)) }
        val pending = _utterances.map { it.id to it.source }
        if (pending.isEmpty()) return
        reanalysisJob = scope.launch {
            for ((id, source) in pending) replaceCommitted(id, render(source, pair))
        }
    }

    // ------------------------------------------------------------------ actions

    /** `Clear` (VOICE-050): empties the transcript; listening continues with a fresh live utterance. */
    fun clear() {
        closeSheet()
        commitJobs.values.forEach { it.cancel() }
        commitJobs.clear()
        reanalysisJob?.cancel()
        liveRunner.invalidate()
        _utterances.clear()
        live = null
        discardPrefix = rawLive
        // The conversion choice holds until Clear; then the default applies again (VOICE-096).
        userChose = false
        selection = defaultSelection()
    }

    /** The transcript as displayed, one line per utterance, including the live one (VOICE-050). */
    fun transcriptText(): String = lines().joinToString("\n") { it.display }

    fun copy() {
        val text = transcriptText()
        if (text.isNotBlank()) session.env.clipboard.writeText(text)
    }

    /** The split button's main action: convert, or show the original. */
    fun toggleConversion() {
        if (!isEmpty) changeConversion(selection.toggled(session.script, session.mode))
    }

    /** `Convert` in the conversion dialog. */
    fun convert(source: ScriptVariant, target: ScriptVariant) {
        if (!isEmpty && ConversionRules.isTargetEnabled(source, target)) changeConversion(selection.confirmedWith(source, target))
    }

    private fun changeConversion(next: ConversionSelection) {
        userChose = true
        if (next == selection) return
        selection = next
        reanalyseAll()
    }

    /**
     * On by default when the user's script is Traditional: `s2hk` in Cantonese mode, `s2t` in
     * Mandarin mode (VOICE-096). The recognised text is always treated as Simplified.
     */
    private fun defaultSelection(): ConversionSelection {
        val recognised = ConversionSelection(detected = DetectedScript(DetectedKind.SIMPLIFIED, ScriptVariant.SIMPLIFIED, true))
        if (session.script != Script.TRADITIONAL) return recognised
        val target = ConversionRules.defaultTarget(ScriptVariant.SIMPLIFIED, session.mode)
        return recognised.copy(applied = ConversionPair(ScriptVariant.SIMPLIFIED, target))
    }

    // ------------------------------------------------------------------ word sheet (VOICE-040…042)

    /** A character of [utteranceId] was tapped. */
    fun onTap(utteranceId: Long, offset: Int) {
        val target = Anchor(utteranceId, offset)
        if (anchor == target && session.wordSheet?.owner === this) {
            anchor = null
            session.closeWordSheet()
            return
        }
        val (passage, base) = transcriptPassage(target) ?: return
        anchor = target
        session.openWordSheet(
            WordSheetRequest(
                owner = this,
                passage = passage,
                offset = base + offset,
                mode = passage.mode,
                onSelectionChanged = ::onSheetMoved,
            ),
        )
    }

    /** The selected offset inside [utterance], or null. */
    fun selectedOffset(utterance: VoiceUtterance): Int? {
        val selected = anchor ?: return null
        if (selected.id != utterance.id || session.wordSheet?.owner !== this) return null
        return selected.offset.takeIf { it < utterance.display.length }
    }

    /** Stepping moved the selection; it may have crossed into another utterance (VOICE-042). */
    private fun onSheetMoved(offset: Int) {
        var base = 0
        for (line in lines()) {
            if (offset < base + line.display.length) {
                anchor = Anchor(line.id, offset - base)
                return
            }
            base += line.display.length + 1
        }
    }

    /**
     * The sheet looks at the whole transcript as one passage — utterances joined by line breaks —
     * so stepping crosses utterances in order. Committed text never moves, and the live utterance
     * is last, so the sheet is simply handed the new passage as the transcript grows and stays
     * open, refreshing in place (VOICE-041, VOICE-094).
     */
    private fun syncSheet() {
        val sheet = session.wordSheet
        if (sheet == null || sheet.owner !== this) {
            anchor = null
            return
        }
        val selected = anchor ?: return
        val (passage, base) = transcriptPassage(selected) ?: run {
            closeSheet()
            return
        }
        val offset = base + selected.offset
        if (offset != sheet.offset || passage != sheet.passage) {
            session.openWordSheet(sheet.copy(passage = passage, offset = offset))
        }
    }

    private fun closeSheet() {
        anchor = null
        session.closeWordSheetOf(this)
    }

    private fun lines(): List<VoiceUtterance> = live?.let { _utterances + it } ?: _utterances

    /** The transcript passage and the base offset of [target]'s utterance in it, or null when it cannot be selected. */
    private fun transcriptPassage(target: Anchor): Pair<AnalysedPassage, Int>? {
        val lines = lines()
        val line = lines.firstOrNull { it.id == target.id } ?: return null
        val template = line.passage ?: return null
        if (target.offset >= line.display.length) return null
        val text = StringBuilder()
        val segments = ArrayList<PassageSegment>()
        val covered = ArrayList<Int>()
        var targetBase = 0
        for ((index, each) in lines.withIndex()) {
            if (index > 0) text.append('\n')
            val base = text.length
            if (each.id == target.id) targetBase = base
            text.append(each.display)
            // An analysis from another mode or data generation (mid re-analysis) contributes text only.
            val passage = each.passage?.takeIf {
                it.text == each.display && it.mode == template.mode && it.generation == template.generation
            } ?: continue
            for (s in passage.segments) segments += if (base == 0) s else s.copy(start = s.start + base)
            for (o in passage.coveredOffsets) covered += o + base
        }
        return AnalysedPassage(text.toString(), template.mode, template.segmentation, template.generation, segments, covered) to targetBase
    }

    fun close() {
        leave()
        listenJob?.cancel()
        jobs.forEach { it.cancel() }
        commitJobs.values.forEach { it.cancel() }
        reanalysisJob?.cancel()
        liveRunner.close()
        closeSheet()
    }

    companion object {
        private const val LEVEL_DECAY = 0.82f
        private const val TICK_MS = 250L
        private var current: VoiceStateHolder? = null

        /** The holder of [session], created on first use and kept for the life of the process. */
        fun of(session: AppSession): VoiceStateHolder {
            current?.let { if (it.session === session) return it else it.close() }
            return VoiceStateHolder(session).also { current = it }
        }

        /** Loudness of a frame mapped to 0…1 over a 50 dB range. */
        internal fun levelOf(frame: FloatArray): Float {
            if (frame.isEmpty()) return 0f
            var sum = 0.0
            for (s in frame) sum += s * s
            val rms = sqrt(sum / frame.size)
            if (rms <= 1e-6) return 0f
            return ((20 * log10(rms) + 55) / 50).toFloat().coerceIn(0f, 1f)
        }

        private fun codePointAt(text: String, index: Int): Int {
            val c = text[index]
            if (c.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                return ((c.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00) + 0x10000
            }
            return c.code
        }
    }
}
