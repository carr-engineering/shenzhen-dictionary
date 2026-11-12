package com.szdict.app.feature.ocr

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.szdict.app.AppSession
import com.szdict.app.WordSheetRequest
import com.szdict.app.feature.conversion.ConversionNotice
import com.szdict.app.feature.conversion.ConversionPair
import com.szdict.app.feature.conversion.ConversionSelection
import com.szdict.app.feature.conversion.DetectedKind
import com.szdict.app.feature.conversion.DetectedScript
import com.szdict.app.feature.conversion.ScriptConverter
import com.szdict.app.feature.conversion.ScriptConverters
import com.szdict.app.feature.conversion.ScriptVariant
import com.szdict.app.feature.conversion.analyseChunked
import com.szdict.app.feature.reading.TranslateText
import com.szdict.app.integration.IncomingIntents
import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.DictMode
import com.szdict.core.api.Dictionary
import com.szdict.core.api.LatestOnly
import com.szdict.core.api.Route
import com.szdict.core.api.ScoredText
import com.szdict.core.api.Script
import com.szdict.recognition.OcrLine
import com.szdict.recognition.OcrResult
import com.szdict.recognition.TextRecognizer
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The image under review (CAM-004) with the camera's encoded original when it can be saved (CAM-074). */
class OcrCapture(val id: Long, val image: WorkingImage, val origin: ImageOrigin, val original: ByteArray?) {
    val source: CaptureSource get() = origin.source
}

/** One chip of the alternatives bar. */
data class AltChoice(
    val text: String,
    /** Shown at this position now. */
    val current: Boolean,
    /** What the recogniser read here; picking it undoes a correction. */
    val recognised: Boolean,
)

/** Asks the views to bring a character into view after stepping (CAM-067, CAM-070). */
data class OcrFocusRequest(val id: Int, val line: Int, val ref: CharRef?)

/**
 * State of the OCR destination (CAM-001…CAM-135). It outlives the screen's composable, so the
 * capture, its recognition result, corrections, the Image/Text choice, zoom/pan, the text list
 * position, the conversion and the flash state survive destination switches and configuration
 * changes (CAM-008, CAM-093); nothing is persisted across launches. The camera itself is owned by
 * the composable and released with it.
 *
 * Every image input — shutter, picker, share, drop, paste — enters through [submit].
 */
class OcrStateHolder(
    private val session: AppSession,
    private val platform: OcrPlatform? = OcrPlatform.of(session.env),
    private val converter: ScriptConverter = ScriptConverters.get(session.env),
    private val workContext: CoroutineContext = Dispatchers.Default,
    private val recognitionTimeoutMs: Long = 20_000,
) {
    // ---- capture phase ----

    /** The origin whose image is being decoded (shutter spinner for the camera, centred spinner otherwise). */
    var decoding: ImageOrigin? by mutableStateOf(null)
        private set

    /** A picked / shared / dropped / pasted image is decoding or failed to decode (CAM-040, CAM-042). */
    var galleryPending: Boolean by mutableStateOf(false)
        private set

    /** Capture or decode error (English key), shown below the frame or in the decode error state. */
    var inputError: String? by mutableStateOf(null)
        private set

    var flash: FlashMode by mutableStateOf(FlashMode.OFF)

    // ---- review phase ----

    var capture: OcrCapture? by mutableStateOf(null)
        private set
    var recognising: Boolean by mutableStateOf(false)
        private set

    /** English key plus optional detail (CAM-056). */
    var recognitionError: Pair<String, String?>? by mutableStateOf(null)
        private set

    /** CAM-054: nothing recognised contains Hanzi. */
    var noChinese: Boolean by mutableStateOf(false)
        private set

    /** The lines on screen, corrections applied; consistent with [review]. */
    var lines: List<CorrectedLine> by mutableStateOf(emptyList())
        private set

    /** The recognised text (corrections applied) and its analysis; null until the first analysis completes. */
    var review: ReviewText? by mutableStateOf(null)
        private set

    /** The script-converted text shown in the Text view while a conversion is applied (CAM-135). */
    var convertedReview: ReviewText? by mutableStateOf(null)
        private set

    var conversion: ConversionSelection by mutableStateOf(ConversionSelection.None)
        private set
    var conversionNotice: ConversionNotice? by mutableStateOf(null)
        private set

    /** An analysis / conversion pass is running (in-place progress, GLB-033). */
    var busy: Boolean by mutableStateOf(false)
        private set

    var viewMode: OcrViewMode by mutableStateOf(OcrViewMode.IMAGE)
        private set
    var saveState: SaveState by mutableStateOf(SaveState.IDLE)
        private set

    /** Brief inline message (English key): save confirmation, `That file is not an image.`… */
    var notice: String? by mutableStateOf(null)
        private set

    /** Polite announcement for assistive technologies (`Image dropped`, `Image pasted`; GLB-084). */
    var announcement: String? by mutableStateOf(null)
        private set

    // Image view transform, retained across destination switches (CAM-008), reset per image (CAM-068).
    var zoom: Float by mutableFloatStateOf(ImageViewport.MIN_ZOOM)
    var translation: Offset by mutableStateOf(Offset.Zero)

    /** Text list position, retained like the zoom; a new capture starts at the top. */
    var listState: LazyListState by mutableStateOf(LazyListState())
        private set

    var focusRequest: OcrFocusRequest? by mutableStateOf(null)
        private set

    /** True the first time [request] is taken: a returning screen never replays an old pan or scroll. */
    fun takeFocus(request: OcrFocusRequest): Boolean {
        if (request.id <= handledFocus) return false
        handledFocus = request.id
        return true
    }

    val inReview: Boolean get() = capture != null
    val ocrLines: List<OcrLine> get() = lines.map { it.original }

    /** The review the Text view shows: converted while a conversion is applied. */
    val textReview: ReviewText? get() = convertedReview ?: review
    val showingConverted: Boolean get() = convertedReview != null
    val conversionEnabled: Boolean get() = lines.any { it.text.isNotBlank() }

    // ---- internals ----

    private var inputGeneration = 0L
    private var textGeneration = 0L
    private var pendingLines: List<CorrectedLine> = emptyList()
    private var linePassages: List<AnalysedPassage?> = emptyList()
    private var convertedLines: List<ConvertedLine?> = emptyList()
    private var rebuildJob: Job? = null
    private var noticeJob: Job? = null
    private var focusId = 0
    private var handledFocus = 0
    private var recognizer: TextRecognizer? = null
    private val rankedAlternatives = mutableStateMapOf<CharRef, List<String>>()

    private class ConvertedLine(val source: String, val pair: ConversionPair, val text: String, val passage: AnalysedPassage?)
    private class RecognitionTimeout : Exception("timeout")

    private val recognition = LatestOnly<OcrCapture, OcrResult>(session.scope, ::recognise, ::onRecognised)

    private val jobs: List<Job> = listOf(
        // A shared image is consumed once (CAM-043, SET-055).
        session.scope.launch {
            IncomingIntents.pendingImageUri.filterNotNull().collect {
                IncomingIntents.consumeImageUri()?.let { uri -> submit(ImageInput(ImageOrigin.SHARE, ImageRef.Uri(uri))) }
            }
        },
        // The `Open camera` shortcut and the /ocr link select the live feed, discarding any capture (CAM-002).
        session.scope.launch {
            IncomingIntents.pendingRoute.filterNotNull().collect {
                if (IncomingIntents.consumeRouteIf { it == Route.Ocr } != null) backToInput()
            }
        },
        // A dictionary-mode change re-runs the analysis against the new dataset (CAM-057).
        session.scope.launch {
            session.settings.map { it.dictionaryMode }.distinctUntilChanged().drop(1).collect {
                rankedAlternatives.clear()
                if (capture != null && pendingLines.isNotEmpty()) rebuild()
            }
        },
    )

    // ---- image inputs: one path (CAM-130) ----

    /** Starts a new capture from any input, replacing the previous one (CAM-003). */
    fun submit(input: ImageInput) {
        val generation = ++inputGeneration
        discard()
        decoding = input.origin
        galleryPending = input.origin.source == CaptureSource.GALLERY
        syncBack()
        session.scope.launch {
            val image = try {
                platform?.decode(input.ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            if (generation != inputGeneration) {
                image?.release()
                return@launch
            }
            decoding = null
            if (image == null) {
                inputError = input.origin.decodeError
                return@launch
            }
            galleryPending = false
            val original = (input.ref as? ImageRef.Encoded)?.bytes?.takeIf { input.origin == ImageOrigin.CAMERA }
            val next = OcrCapture(generation, image, input.origin, original)
            capture = next
            recognising = true
            syncBack()
            recognition.submit(next)
            // CAM-081, CAM-084: never waits for, or delays, the recognition.
            if (original != null && session.settings.value.cameraAutoSave) savePhoto()
        }
    }

    /** The shutter's encoded output. */
    fun onCameraCaptured(jpeg: ByteArray) = submit(ImageInput(ImageOrigin.CAMERA, ImageRef.Encoded(jpeg)))

    fun onCameraError() {
        if (capture == null && !galleryPending) inputError = "Could not take a photo. Try again."
    }

    fun onPicked(uri: String) = submit(ImageInput(ImageOrigin.PICK, ImageRef.Uri(uri)))

    /** A drop anywhere on the destination (CAM-131); [uri] is null when the dropped items hold no image. */
    fun onDropped(uri: String?) {
        if (uri == null) {
            showNotice("That file is not an image.")
            return
        }
        submit(ImageInput(ImageOrigin.DROP, ImageRef.Uri(uri)))
        announcement = "Image dropped"
    }

    /** An explicit paste (CAM-132): the clipboard is read only here. Returns false when it holds no image. */
    fun paste(): Boolean {
        val uri = platform?.clipboardImage()
        if (uri == null) {
            showNotice("No image on the clipboard.")
            return false
        }
        submit(ImageInput(ImageOrigin.PASTE, ImageRef.Uri(uri)))
        announcement = "Image pasted"
        return true
    }

    fun clipboardHasImage(): Boolean = platform?.clipboardImage() != null

    /** Web: images come with the browser's `paste` event, so Ctrl/Cmd+V is left to the browser (CAM-132). */
    val pasteByEvent: Boolean get() = platform?.pasteByEvent == true

    /** While the destination is shown, a platform paste event feeds the same path as [paste]. */
    fun listenForPaste(listening: Boolean) {
        platform?.setPasteListener(if (listening) ::onPasteEvent else null)
    }

    private fun onPasteEvent(uri: String?) {
        if (session.routes.isNotEmpty()) return   // the paste was meant for the page covering the destination
        if (uri == null) {
            showNotice("No image on the clipboard.")
            return
        }
        submit(ImageInput(ImageOrigin.PASTE, ImageRef.Uri(uri)))
        announcement = "Image pasted"
    }

    // ---- recogniser model fetched on first use (Web, CAM-134) ----

    val modelDownload: ModelDownload? get() = platform?.modelDownload

    fun prepareRecognizer() {
        platform?.prepareRecognizer()
    }

    fun retryModelDownload() {
        platform?.retryModelDownload()
    }

    /**
     * `Retake`, `Camera`, system Back and a re-tap of the destination (CAM-009, CAM-100): back to
     * the live feed, discarding the capture. Returns false when already on the input screen.
     */
    fun backToInput(): Boolean {
        val had = capture != null || galleryPending
        inputGeneration++
        discard()
        syncBack()
        return had
    }

    private fun discard() {
        session.closeWordSheetOf(this)
        recognition.invalidate()
        textGeneration++
        rebuildJob?.cancel()
        capture?.image?.release()
        capture = null
        decoding = null
        galleryPending = false
        inputError = null
        recognising = false
        recognitionError = null
        noChinese = false
        pendingLines = emptyList()
        linePassages = emptyList()
        convertedLines = emptyList()
        lines = emptyList()
        review = null
        convertedReview = null
        conversion = ConversionSelection.None
        conversionNotice = null
        busy = false
        saveState = SaveState.IDLE
        notice = null
        zoom = ImageViewport.MIN_ZOOM
        translation = Offset.Zero
        listState = LazyListState()
        focusRequest = null
        rankedAlternatives.clear()
    }

    private fun syncBack() {
        session.ocrReviewBack = if (capture != null || galleryPending) ::backToInput else null
    }

    // ---- recognition (CAM-050…CAM-056): latest-only, 20 s timeout ----

    private suspend fun recognise(target: OcrCapture): OcrResult {
        val engine = recognizer ?: (platform?.createRecognizer() ?: error("Text recognition is unavailable")).also { recognizer = it }
        // A model download is not recognition time (CAM-051): the image waits for it, however long it takes.
        platform?.awaitRecognizer()
        return withTimeoutOrNull(recognitionTimeoutMs) {
            val image = withContext(workContext) { target.image.toOcrImage() }
            engine.recognize(image)
        } ?: throw RecognitionTimeout()
    }

    private fun onRecognised(target: OcrCapture, result: Result<OcrResult>) {
        if (capture !== target) return
        recognising = false
        val value = result.getOrNull()
        if (value == null) {
            val error = result.exceptionOrNull()
            recognitionError = if (error is RecognitionTimeout) {
                "Text recognition timed out. Try a smaller or clearer image." to null
            } else {
                "Unable to recognize text" to error?.message
            }
            return
        }
        // CAM-054: lines with no characters are dropped.
        val recognised = value.lines.filter { it.chars.isNotEmpty() && it.text.isNotEmpty() }.map { CorrectedLine(it) }
        noChinese = recognised.none { it.hasHanzi }
        pendingLines = recognised
        lines = recognised
        if (recognised.isNotEmpty()) rebuild()
    }

    // ---- analysis, corrections and conversion ----

    private class Rebuilt(
        val dictionary: Dictionary,
        val passages: List<AnalysedPassage?>,
        val detected: DetectedScript?,
        val converted: List<ConvertedLine?>?,
        val notice: ConversionNotice?,
    )

    /**
     * Brings the analyses in line with [pendingLines] and the conversion selection. Latest-only;
     * a line whose text, dataset and conversion are unchanged keeps its analysis, so correcting one
     * character re-analyses that line alone.
     */
    private fun rebuild() {
        val generation = ++textGeneration
        val target = pendingLines
        val applied = conversion.applied
        val oldPassages = linePassages
        val oldConverted = convertedLines
        busy = true
        rebuildJob?.cancel()
        rebuildJob = session.scope.launch {
            val rebuilt = try {
                val dictionary = session.dictionary()
                withContext(workContext) {
                    val passages = target.mapIndexed { i, line ->
                        oldPassages.getOrNull(i)?.takeIf { it.text == line.text && it.generation == dictionary.generation && it.mode == dictionary.mode }
                            ?: analyse(dictionary, line.text)
                    }
                    val detected = try {
                        converter.detect(target.joinToString("\n") { it.text })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        null
                    }
                    var notice: ConversionNotice? = null
                    var converted: List<ConvertedLine?>? = null
                    if (applied != null) {
                        try {
                            // Each line is converted separately so line boundaries are kept (CAM-135).
                            converted = target.mapIndexed { i, line ->
                                val old = oldConverted.getOrNull(i)
                                if (old != null && old.source == line.text && old.pair == applied &&
                                    old.passage?.let { it.generation == dictionary.generation && it.mode == dictionary.mode } == true
                                ) old
                                else {
                                    val text = converter.convert(line.text, applied.source, applied.target).text
                                    ConvertedLine(line.text, applied, text, analyse(dictionary, text))
                                }
                            }
                            if (converted.indices.all { converted[it]?.text == target[it].text }) notice = ConversionNotice.NOTHING_TO_CONVERT
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            converted = null
                            notice = ConversionNotice.FAILED
                        }
                    }
                    Rebuilt(dictionary, passages, detected, converted, notice)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            if (generation != textGeneration) return@launch
            busy = false
            if (rebuilt == null) return@launch   // CAM-055: the lines stay as plain text
            apply(target, rebuilt)
        }
    }

    private suspend fun analyse(dictionary: Dictionary, text: String): AnalysedPassage? = try {
        dictionary.analyseChunked(text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    private fun apply(target: List<CorrectedLine>, rebuilt: Rebuilt) {
        val previous = review
        val previousSelection = selected
        val dictionary = rebuilt.dictionary
        val next = ReviewText(target.map { it.text }, rebuilt.passages, dictionary.mode, dictionary.generation)
        linePassages = rebuilt.passages
        lines = target
        review = next

        var selection = conversion.withDetection(rebuilt.detected)
        if (rebuilt.notice == ConversionNotice.FAILED) selection = selection.showingOriginal()   // GLB-034
        conversion = selection
        conversionNotice = rebuilt.notice
        val converted = rebuilt.converted
        convertedLines = converted ?: emptyList()
        convertedReview = if (converted != null && selection.isConverted) {
            ReviewText(converted.map { it?.text ?: "" }, converted.map { it?.passage }, dictionary.mode, dictionary.generation)
        } else null

        // Keep the word sheet on the same character of the re-analysed line.
        val sheet = session.wordSheet
        if (sheet != null && sheet.owner === this) {
            if (previous != null && sheet.passage === previous.passage && previousSelection != null) {
                val line = target[previousSelection.line]
                val offset = next.lineStart(previousSelection.line) + (line.selectableOffset(previousSelection.char) ?: line.charStart(previousSelection.char))
                session.openWordSheet(sheet.copy(passage = next.passage, offset = offset))
            } else {
                session.closeWordSheetOf(this)
            }
        }
    }

    // ---- selection and the word sheet (CAM-065…CAM-070) ----

    /** The selected symbol of the original lines, or null (also null while a converted line is selected). */
    val selected: CharRef?
        get() {
            val current = review ?: return null
            val offset = session.selectionFor(this, current.passage) ?: return null
            val line = current.lineAt(offset)
            val char = lines.getOrNull(line)?.charIndexAt(offset - current.lineStart(line)) ?: return null
            return CharRef(line, char)
        }

    /** Selected offset inside text-view line [line], in the text that view shows. */
    fun selectedOffsetIn(line: Int): Int? {
        val current = textReview ?: return null
        val offset = session.selectionFor(this, current.passage) ?: return null
        return if (current.lineAt(offset) == line) offset - current.lineStart(line) else null
    }

    /** A tap on a glyph of the image (always the original lines, CAM-135). */
    fun tapChar(ref: CharRef) {
        val current = review ?: return
        val line = lines.getOrNull(ref.line) ?: return
        val offset = line.selectableOffset(ref.char) ?: return
        open(current, current.lineStart(ref.line) + offset)
    }

    /** A tap on a character of a text-view line; [offset] is relative to that line's displayed text. */
    fun tapText(line: Int, offset: Int) {
        val current = textReview ?: return
        open(current, current.lineStart(line) + offset)
    }

    private fun open(target: ReviewText, offset: Int) {
        // CAM-057: taps are ignored until the analysis matches the current dataset.
        if (busy && target.passage.mode != session.mode) return
        session.openWordSheet(
            WordSheetRequest(
                owner = this,
                passage = target.passage,
                offset = offset,
                mode = target.passage.mode,
                onSelectionChanged = { moved -> onStepped(target, moved) },
            ),
        )
    }

    private fun onStepped(target: ReviewText, offset: Int) {
        val line = target.lineAt(offset)
        val ref = if (target === review) lines.getOrNull(line)?.charIndexAt(offset - target.lineStart(line))?.let { CharRef(line, it) } else null
        focusRequest = OcrFocusRequest(++focusId, line, ref)
    }

    fun changeViewMode(mode: OcrViewMode) {
        if (mode == viewMode) return
        // CAM-135: switching views while converted clears the selection (the passages differ).
        if (showingConverted) session.closeWordSheetOf(this)
        viewMode = mode
    }

    // ---- per-character alternatives ----

    /**
     * The readings that could stand at [ref], best first: the recogniser's alternatives filtered to
     * dataset characters and ranked by score and frequency ([Dictionary.characterAlternatives]),
     * always including what the recogniser read and what is shown now. Empty until [loadAlternatives]
     * has ranked them, and when there is nothing to choose from.
     */
    fun alternatives(ref: CharRef): List<AltChoice> {
        val ranked = rankedAlternatives[ref] ?: return emptyList()
        val line = lines.getOrNull(ref.line) ?: return emptyList()
        val recognised = line.original.chars.getOrNull(ref.char)?.text ?: return emptyList()
        val current = line.chars[ref.char]
        val texts = LinkedHashSet<String>()
        if (recognised !in ranked) texts += recognised
        texts += ranked
        texts += current
        if (texts.size < 2) return emptyList()
        return texts.map { AltChoice(it, current = it == current, recognised = it == recognised) }
    }

    fun loadAlternatives(ref: CharRef) {
        if (rankedAlternatives.containsKey(ref)) return
        val char = lines.getOrNull(ref.line)?.original?.chars?.getOrNull(ref.char) ?: return
        val owner = capture ?: return
        session.scope.launch {
            val raw = char.alternatives.map { ScoredText(it.text, it.score.toDouble()) }
            val ranked = try {
                session.dictionary().characterAlternatives(raw).map { it.text }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                raw.map { it.text }
            }
            if (capture === owner) rankedAlternatives[ref] = ranked.take(MAX_ALTERNATIVES)
        }
    }

    /**
     * Replaces the character at [ref] with [text] (one of its alternatives; the recognised reading
     * undoes the correction). The line is re-analysed and the selection stays on that position.
     */
    fun pickAlternative(ref: CharRef, text: String) {
        val line = pendingLines.getOrNull(ref.line) ?: return
        val next = line.withPick(ref.char, text)
        if (next === line) return
        pendingLines = pendingLines.toMutableList().also { it[ref.line] = next }
        noChinese = pendingLines.none { it.hasHanzi }
        rebuild()
    }

    // ---- script conversion of the recognised text (CAM-135) ----

    fun toggleConversion() {
        if (!conversionEnabled) return
        changeConversion(conversion.toggled(session.script, session.mode))
    }

    fun convert(source: ScriptVariant, target: ScriptVariant) {
        if (!conversionEnabled) return
        changeConversion(conversion.confirmedWith(source, target))
    }

    private fun changeConversion(next: ConversionSelection) {
        conversion = next
        conversionNotice = null
        session.closeWordSheetOf(this)
        if (!next.isConverted) convertedReview = null
        rebuild()
    }

    // ---- sentence actions on the displayed text (MED-020) ----

    /** Displayed text of line [line], or of the whole capture when null. */
    fun displayedText(line: Int?): String {
        val current = textReview
        return when {
            current == null -> if (line == null) lines.joinToString("\n") { it.text } else lines.getOrNull(line)?.text ?: ""
            line == null -> current.text
            else -> current.texts.getOrNull(line) ?: ""
        }
    }

    fun playbackKey(line: Int?): Any = PlaybackKey(this, line, displayedText(line))

    fun togglePlayback(line: Int?) {
        val text = displayedText(line).trim()
        if (text.isNotEmpty()) session.speak(playbackKey(line), text, session.mode)
    }

    fun translate(line: Int?) {
        val capped = TranslateText.cap(displayedText(line))
        if (capped.text.isEmpty()) return
        if (capped.truncated) showNotice("Only the first part was sent to translate.")
        session.env.translator.translate(capped.text, translateSourceTag())
    }

    fun copy(line: Int?) {
        val text = displayedText(line).trim()
        if (text.isNotEmpty()) session.env.clipboard.writeText(text)
    }

    private fun translateSourceTag(): String {
        if (session.mode == DictMode.CANTONESE) return "yue"
        val applied = conversion.applied
        val simplified = when {
            convertedReview != null && applied != null -> applied.target.isSimplified
            else -> when (conversion.detected?.kind) {
                DetectedKind.SIMPLIFIED -> true
                DetectedKind.TRADITIONAL -> false
                else -> session.script == Script.SIMPLIFIED
            }
        }
        return if (simplified) "zh-CN" else "zh-TW"
    }

    // ---- save photo (CAM-071…CAM-084) ----

    val canSave: Boolean get() = capture?.original != null

    /** Saves the original capture once; a second call for the same capture writes nothing (CAM-072, CAM-128). */
    fun savePhoto() {
        val target = capture ?: return
        val bytes = target.original ?: return
        if (saveState == SaveState.SAVING || saveState == SaveState.SAVED) return
        val writer = platform ?: return
        saveState = SaveState.SAVING
        session.scope.launch {
            val saved = try {
                writer.savePhoto(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                false
            }
            if (capture !== target) return@launch
            saveState = if (saved) SaveState.SAVED else SaveState.FAILED
            showNotice(if (saved) writer.savedMessage else "Could not save the photo.")
        }
    }

    private fun showNotice(text: String) {
        notice = text
        noticeJob?.cancel()
        noticeJob = session.scope.launch {
            delay(NOTICE_MS)
            if (notice == text) notice = null
        }
    }

    fun close() {
        jobs.forEach { it.cancel() }
        inputGeneration++
        discard()
        syncBack()
        recognition.close()
        recognizer?.close()   // CAM-092
        recognizer = null
    }

    private data class PlaybackKey(val owner: Any, val line: Int?, val text: String)

    companion object {
        private const val NOTICE_MS = 3_000L
        const val MAX_ALTERNATIVES = 10
        private var current: OcrStateHolder? = null

        /** The holder of [session], created on first use and kept for the life of the process. */
        fun of(session: AppSession): OcrStateHolder {
            current?.let { if (it.session === session) return it else it.close() }
            return OcrStateHolder(session).also { current = it }
        }
    }
}
