package com.szdict.app.feature.conversion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.DictMode
import com.szdict.core.api.Dictionary
import com.szdict.core.api.LatestOnly
import com.szdict.core.api.Script
import com.szdict.core.api.SegmentationMode
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Status shown next to a converted passage (TXT-084); [label] is the English text. */
enum class ConversionNotice(val label: String) {
    NOTHING_TO_CONVERT("Nothing to convert"),
    FAILED("Unable to convert text"),
}

/**
 * Conversion view state of ONE passage (GLB-009, TXT-084) plus its analysis pipeline: give it the
 * original text, read back [passage] — the analysis of the text as displayed, converted or not.
 *
 * - the original is kept untouched in [original]; conversion always starts from it;
 * - detection, conversion and analysis run off the UI thread as one latest-only job (GLB-040); the
 *   previous [passage] stays until the new one is ready (GLB-030) and [busy] drives the in-place
 *   progress (GLB-033); a blank text removes the passage at once;
 * - editing ([setText] with `newPassage = false`) keeps the conversion; a new passage (Clear,
 *   Paste, shared text) starts unconverted with fresh detection;
 * - [onPassageChanging] is called whenever the displayed passage is about to change (edit,
 *   convert, show original, option change) — close the word sheet there (TXT-045).
 *
 * Call every method from the UI thread of [scope]. State is Compose snapshot state.
 */
class ConversionState(
    scope: CoroutineScope,
    private val converter: ScriptConverter,
    private val dictionary: suspend () -> Dictionary,
    private val userScript: () -> Script,
    private val mode: () -> DictMode,
    private val onPassageChanging: () -> Unit = {},
    private val segmentation: SegmentationMode = SegmentationMode.HANZI,
    private val analysisContext: CoroutineContext = Dispatchers.Default,
) {
    /** The original text; never modified by conversion. */
    var original: String by mutableStateOf("")
        private set

    var selection: ConversionSelection by mutableStateOf(ConversionSelection.None)
        private set

    /** Analysis of the displayed text, or null when there is no text (or its first analysis is pending). */
    var passage: AnalysedPassage? by mutableStateOf(null)
        private set

    /** The conversion behind [passage] (with its segment map), or null while the original is shown. */
    var converted: Converted? by mutableStateOf(null)
        private set

    /** A detection / conversion / analysis job is running. */
    var busy: Boolean by mutableStateOf(false)
        private set

    var notice: ConversionNotice? by mutableStateOf(null)
        private set

    /** The last analysis failed (TXT-074); cleared by the next successful one. */
    var analysisFailed: Boolean by mutableStateOf(false)
        private set

    /** The split button is disabled while there is no text (TXT-081). */
    val enabled: Boolean get() = original.isNotBlank()

    /** The text the sentence actions act on (MED-020): the converted text while one is shown. */
    val displayedText: String get() = passage?.text ?: original

    val mainAction: ConversionAction get() = selection.mainAction(userScript(), mode())

    /** Preselected dialog options (TXT-083). */
    val dialogDefaults: ConversionPair get() = selection.dialogDefaults(userScript(), mode())

    private data class Request(val text: String, val applied: ConversionPair?)
    private class Outcome(
        val detected: DetectedScript?,
        val converted: Converted?,
        val notice: ConversionNotice?,
        val passage: AnalysedPassage?,
    )

    private val runner = LatestOnly<Request, Outcome>(scope, ::run, ::apply)

    /**
     * Sets the original text. [newPassage] = true (Clear, Paste, shared text, a new recognition)
     * resets the conversion; false (typing) keeps it and converts the edited text the same way.
     */
    fun setText(text: String, newPassage: Boolean = false) {
        if (!newPassage && text == original) return
        original = text
        if (newPassage) {
            selection = ConversionSelection.None
            notice = null
        }
        onPassageChanging()
        if (text.isBlank()) {
            runner.invalidate()
            passage = null
            converted = null
            busy = false
            analysisFailed = false
            notice = null
            selection = selection.withDetection(null)
            return
        }
        submit()
    }

    /** Main action: convert (confirmed pair, else detection — TXT-082) or show the original. */
    fun toggle() {
        if (!enabled) return
        change(selection.toggled(userScript(), mode()))
    }

    /** Dialog `Convert`: applies [source] → [target] to the original text. */
    fun convert(source: ScriptVariant, target: ScriptVariant) {
        if (!enabled || !ConversionRules.isTargetEnabled(source, target)) return
        change(selection.confirmedWith(source, target))
    }

    fun showOriginal() {
        if (selection.isConverted) change(selection.showingOriginal())
    }

    /** Re-runs the pipeline on the same text (dictionary mode or data changed). */
    fun refresh() {
        if (original.isNotBlank()) {
            onPassageChanging()
            submit()
        }
    }

    fun close() = runner.close()

    private fun change(next: ConversionSelection) {
        selection = next
        notice = null
        onPassageChanging()
        submit()
    }

    private fun submit() {
        busy = true
        runner.submit(Request(original, selection.applied))
    }

    private suspend fun run(request: Request): Outcome {
        val detected = try {
            converter.detect(request.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        var converted: Converted? = null
        var notice: ConversionNotice? = null
        val applied = request.applied
        if (applied != null) {
            try {
                converted = converter.convert(request.text, applied.source, applied.target)
                if (converted.isIdentityOf(request.text)) notice = ConversionNotice.NOTHING_TO_CONVERT
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                notice = ConversionNotice.FAILED
            }
        }
        val display = converted?.text ?: request.text
        val passage = try {
            withContext(analysisContext) { dictionary().analyseChunked(display, segmentation) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        return Outcome(detected, converted, notice, passage)
    }

    private fun apply(request: Request, result: Result<Outcome>) {
        busy = false
        val outcome = result.getOrNull()
        if (outcome == null) {
            analysisFailed = true
            return
        }
        var next = selection.withDetection(outcome.detected)
        // A failed conversion keeps the original on screen (GLB-034).
        if (outcome.notice == ConversionNotice.FAILED) next = next.showingOriginal()
        selection = next
        notice = outcome.notice
        converted = outcome.converted
        analysisFailed = outcome.passage == null
        if (outcome.passage != null) {
            passage = outcome.passage
        } else if (passage?.text != (outcome.converted?.text ?: request.text)) {
            // The previous analysis belongs to another text; never show it as this one's.
            passage = null
        }
    }
}
