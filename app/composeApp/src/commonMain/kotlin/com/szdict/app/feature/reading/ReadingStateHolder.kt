package com.szdict.app.feature.reading

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.szdict.app.AppSession
import com.szdict.app.HandwritingSink
import com.szdict.app.WordSheetRequest
import com.szdict.app.feature.conversion.ConversionState
import com.szdict.app.feature.conversion.DetectedKind
import com.szdict.app.feature.conversion.ScriptConverter
import com.szdict.app.feature.conversion.ScriptConverters
import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.DictMode
import com.szdict.core.api.Script
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * State of the Reading destination (TXT-050…TXT-059, TXT-080, TXT-084). It outlives the screen's
 * composable so the draft, the analysis and the conversion state survive destination switches and
 * configuration changes (GLB-050); nothing is persisted across launches (SET-014).
 */
class ReadingStateHolder(
    private val session: AppSession,
    converter: ScriptConverter = ScriptConverters.get(session.env),
    analysisContext: CoroutineContext = Dispatchers.Default,
) : HandwritingSink {
    /** The original draft with its caret; conversion never modifies it (TXT-084). */
    var draft: TextFieldValue by mutableStateOf(TextFieldValue(""))
        private set

    /** Inline message key (English source string), e.g. after a shortcut paste with no clipboard text. */
    var message: String? by mutableStateOf(null)
        private set

    /** Detection, conversion and latest-only analysis of the draft. */
    val conversion = ConversionState(
        scope = session.scope,
        converter = converter,
        dictionary = { session.dictionary() },
        userScript = { session.script },
        mode = { session.mode },
        onPassageChanging = { session.closeWordSheetOf(this) },
        analysisContext = analysisContext,
    )

    val passage: AnalysedPassage? get() = conversion.passage
    val selectedOffset: Int? get() = session.selectionFor(this, conversion.passage)

    private val jobs: List<Job> = listOf(
        // Shared text and the clipboard shortcut, consumed once (TXT-057, TXT-080).
        session.scope.launch {
            ReadingInbox.pending.filterNotNull().collect {
                when (val delivery = ReadingInbox.take()) {
                    is ReadingInbox.Delivery.SharedText -> replaceDraft(delivery.text)
                    ReadingInbox.Delivery.ReadClipboard -> pasteFromShortcut()
                    null -> Unit
                }
            }
        },
        // A dictionary-mode change re-analyses the same draft against the new dataset (GLB-044).
        session.scope.launch {
            session.settings.map { it.dictionaryMode }.distinctUntilChanged().drop(1).collect { conversion.refresh() }
        },
    )

    /** Typing, IME edits and caret moves. Every text edit supersedes the in-flight analysis (TXT-051). */
    fun onDraftChange(value: TextFieldValue) {
        val edited = value.text != draft.text
        draft = value
        if (edited) {
            message = null
            conversion.setText(value.text)
        }
    }

    /** `Clear`: a new (empty) passage; the result is removed at once. */
    fun clear() {
        draft = TextFieldValue("")
        message = null
        conversion.setText("", newPassage = true)
    }

    /** `Paste` (TXT-052). Returns false when the clipboard has no usable text. */
    fun paste(): Boolean {
        val text = session.env.clipboard.readText()
        if (text.isNullOrBlank()) {
            session.env.clipboard.refresh()
            return false
        }
        replaceDraft(text)
        return true
    }

    /** A new passage: replaces the draft (no merge), starts unconverted, analyses automatically. */
    fun replaceDraft(text: String) {
        draft = TextFieldValue(text, TextRange(text.length))
        message = null
        conversion.setText(text, newPassage = true)
    }

    private suspend fun pasteFromShortcut() {
        if (paste()) return
        // The window may not have focus yet right after launch (TXT-053): wait briefly for the clip.
        withTimeoutOrNull(SHORTCUT_CLIPBOARD_WAIT_MS) { session.env.clipboard.hasText.first { it } }
        if (!paste()) message = "Clipboard empty or unavailable"
    }

    fun onPassageTap(offset: Int) {
        val passage = conversion.passage ?: return
        session.openWordSheet(WordSheetRequest(owner = this, passage = passage, offset = offset, mode = passage.mode))
    }

    // ---- sentence actions act on the displayed text (MED-020) ----

    /** Playback key: the play button shows stop while this exact text is speaking (MED-021). */
    fun playbackKey(): Any = PlaybackKey(this, conversion.displayedText.trim())

    fun togglePlayback() {
        val text = conversion.displayedText.trim()
        if (text.isNotEmpty()) session.speak(playbackKey(), text, session.mode)
    }

    fun translate() {
        val capped = TranslateText.cap(conversion.displayedText)
        if (capped.text.isEmpty()) return
        message = if (capped.truncated) "Only the first part was sent to translate." else null
        session.env.translator.translate(capped.text, translateSourceTag())
    }

    fun copy() {
        val text = conversion.displayedText.trim()
        if (text.isNotEmpty()) session.env.clipboard.writeText(text)
    }

    /** MED-030 with GLB-009: the displayed text's script stands in for script mode. */
    internal fun translateSourceTag(): String {
        if (session.mode == DictMode.CANTONESE) return "yue"
        val applied = conversion.selection.applied
        val simplified = when {
            conversion.converted != null && applied != null -> applied.target.isSimplified
            else -> when (conversion.selection.detected?.kind) {
                DetectedKind.SIMPLIFIED -> true
                DetectedKind.TRADITIONAL -> false
                else -> session.script == Script.SIMPLIFIED
            }
        }
        return if (simplified) "zh-CN" else "zh-TW"
    }

    // ---- handwriting sheet target (HW-014, HW-015) ----

    override fun insert(text: String) {
        if (text.isEmpty()) return
        val value = draft
        val start = value.selection.min.coerceIn(0, value.text.length)
        val end = value.selection.max.coerceIn(start, value.text.length)
        val next = value.text.replaceRange(start, end, text)
        onDraftChange(TextFieldValue(next, TextRange(start + text.length)))
    }

    override fun deleteBackward() {
        val value = draft
        val start = value.selection.min.coerceIn(0, value.text.length)
        val end = value.selection.max.coerceIn(start, value.text.length)
        if (start != end) {
            onDraftChange(TextFieldValue(value.text.removeRange(start, end), TextRange(start)))
            return
        }
        if (start == 0) return
        // One code point back.
        val from = if (start >= 2 && value.text[start - 1].isLowSurrogate() && value.text[start - 2].isHighSurrogate()) start - 2 else start - 1
        onDraftChange(TextFieldValue(value.text.removeRange(from, start), TextRange(from)))
    }

    fun close() {
        jobs.forEach { it.cancel() }
        conversion.close()
        session.closeWordSheetOf(this)
        if (session.handwritingSink === this) session.handwritingSink = null
    }

    private data class PlaybackKey(val owner: Any, val text: String)

    companion object {
        private const val SHORTCUT_CLIPBOARD_WAIT_MS = 1500L
        private var current: ReadingStateHolder? = null

        /** The holder of [session], created on first use and kept for the life of the process. */
        fun of(session: AppSession): ReadingStateHolder {
            current?.let { if (it.session === session) return it else it.close() }
            return ReadingStateHolder(session).also { current = it }
        }
    }
}

/** Text handed to the external translator: trimmed and capped at 1,500 code points (MED-030, Q-118). */
object TranslateText {
    const val MAX_CODE_POINTS = 1500
    private const val BOUNDARIES = "。！？!?\n\r"

    class Capped(val text: String, val truncated: Boolean)

    fun cap(text: String, maxCodePoints: Int = MAX_CODE_POINTS): Capped {
        val trimmed = text.trim()
        // Find the UTF-16 index of the cap.
        var index = 0
        var count = 0
        while (index < trimmed.length && count < maxCodePoints) {
            index += if (trimmed[index].isHighSurrogate() && index + 1 < trimmed.length && trimmed[index + 1].isLowSurrogate()) 2 else 1
            count++
        }
        if (index >= trimmed.length) return Capped(trimmed, false)
        // Cut after the last sentence boundary before the cap, or at the cap when there is none.
        var cut = index
        var i = index - 1
        while (i > 0) {
            if (trimmed[i] in BOUNDARIES) { cut = i + 1; break }
            i--
        }
        return Capped(trimmed.substring(0, cut).trim(), true)
    }
}
