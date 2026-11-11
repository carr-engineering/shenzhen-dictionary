package com.szdict.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.szdict.app.platform.AudioPlayer
import com.szdict.app.platform.Clipboard
import com.szdict.app.platform.LinkOpener
import com.szdict.app.platform.SettingsStorage
import com.szdict.app.platform.Speaker
import com.szdict.app.platform.Translator
import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.AudioClipKind
import com.szdict.core.api.AudioPlan
import com.szdict.core.api.DictMode
import com.szdict.core.api.Dictionary
import com.szdict.core.api.EntryCard
import com.szdict.core.api.Script
import com.szdict.core.api.Settings
import com.szdict.core.api.SettingsCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Platform capabilities injected at the app layer (ARCH-090). Feature code uses only this. */
class AppEnv(
    /** Opens (or returns the cached) engine for a mode. Throws DictException when unavailable. */
    val openDictionary: suspend (DictMode) -> Dictionary,
    val settingsStorage: SettingsStorage,
    val audio: AudioPlayer,
    val speaker: Speaker,
    val clipboard: Clipboard,
    val translator: Translator,
    val links: LinkOpener,
    /** Platform-specific services looked up by feature modules (recognisers, camera, voice…). */
    val services: Map<String, Any> = emptyMap(),
    val voiceAvailable: Boolean = true,
    val systemLocaleTag: () -> String = { "en" },
    val appVersion: String = "",
) {
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> service(key: String): T? = services[key] as? T
}

enum class Destination { SEARCH, OCR, VOICE, READING, HSK }

/** One route of the detail stack (ENT-010, SET-005, ARCH-199). [key] is unique per visit (GLB-051). */
sealed interface DetailRoute {
    val key: Long

    data class Entry(override val key: Long, val entryId: Int, val mode: DictMode, val generation: Long) : DetailRoute
    data class SettingsRoute(override val key: Long) : DetailRoute
    data class Privacy(override val key: Long) : DetailRoute
    data class Attribution(override val key: Long) : DetailRoute
}

/**
 * The open word-candidate sheet (TXT-040): which passage and character it is showing. [owner]
 * identifies the surface holding the passage so only that surface highlights the selection.
 */
data class WordSheetRequest(
    val owner: Any,
    val passage: AnalysedPassage,
    val offset: Int,
    val mode: DictMode,
    /** Called when stepping moves the selection (camera pans to the glyph, GLB-058). */
    val onSelectionChanged: ((Int) -> Unit)? = null,
    /** Rows given directly instead of a passage lookup (definition-link disambiguation, ENT-054). */
    val fixedTitle: String? = null,
    val fixedCards: List<EntryCard>? = null,
)

/**
 * Cross-feature session state (ARCH-041): settings stream, dictionaries per mode, the detail route
 * stack (ARCH-042), the word sheet, single-owner playback, and navigation signals.
 * All mutation happens on the main thread.
 */
class AppSession(val env: AppEnv, val scope: CoroutineScope) {
    // ---- settings ----
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    var settingsLoaded by mutableStateOf(false)
        private set
    private var userEditedBeforeLoad = false
    private var persisted: Map<String, String> = emptyMap()
    private var persistJob: Job? = null

    val mode: DictMode get() = _settings.value.dictionaryMode
    val script: Script get() = _settings.value.script

    init {
        scope.launch {
            val stored = runCatching { env.settingsStorage.values.first() }.getOrDefault(emptyMap())
            persisted = stored
            val loaded = SettingsCodec.decode(stored)
            // SRCH-142: input typed before settings loaded wins over the restored query.
            _settings.value = if (userEditedBeforeLoad) {
                loaded.withLastQuery(loaded.dictionaryMode, _settings.value.lastSearchQuery)
            } else loaded
            settingsLoaded = true
        }
    }

    /** Applies [transform]; persists the difference (debounced for the query, ARCH-112). */
    fun updateSettings(debounceMs: Long = 0, transform: (Settings) -> Settings) {
        val old = _settings.value
        val new = transform(old)
        if (new == old) return
        if (!settingsLoaded) userEditedBeforeLoad = true
        _settings.value = new
        if (new.dictionaryMode != old.dictionaryMode) onModeChanged()
        persistJob?.cancel()
        persistJob = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            flushSettings()
        }
    }

    private suspend fun flushSettings() {
        val changes = pendingChanges()
        if (changes.isEmpty()) return
        runCatching { env.settingsStorage.update(changes) }
        persisted = persisted + changes
    }

    private fun pendingChanges(): Map<String, String> =
        SettingsCodec.encode(_settings.value).filter { (k, v) -> persisted[k] != v }

    /** Lifecycle stop: flush immediately (SRCH-140). */
    fun flushNow() {
        persistJob?.cancel()
        val changes = pendingChanges()
        if (changes.isEmpty()) return
        runCatching { env.settingsStorage.flushBlocking(changes) }
        persisted = persisted + changes
    }

    // ---- dictionaries ----
    private val dictMutex = Mutex()
    private val dictionaries = HashMap<DictMode, Dictionary>()

    /** The engine for [mode] (default: the user's mode). Opening is a file open + metadata read (ARCH-131). */
    suspend fun dictionary(mode: DictMode = this.mode): Dictionary = dictMutex.withLock {
        dictionaries[mode] ?: env.openDictionary(mode).also { dictionaries[mode] = it }
    }

    /** Bumped on a real mode change (GLB-044); requests carry it as part of their identity. */
    var dataGeneration by mutableIntStateOf(0)
        private set

    private fun onModeChanged() {
        dataGeneration++
        routes.clear()
        closeWordSheet(userDismissed = false)
        handwritingVisible = false   // HW-024
        stopPlayback()
    }

    // ---- primary navigation ----
    var destination by mutableStateOf(Destination.SEARCH)
        private set

    /** Incremented when the selected destination is tapped again (PROD-053); screens scroll to top. */
    val reselect = mutableStateOf(0 to Destination.SEARCH)

    fun selectDestination(target: Destination) {
        if (target == destination) {
            reselect.value = (reselect.value.first + 1) to target
            return
        }
        destination = target
        routes.clear()          // PROD-055
        closeWordSheet()
        handwritingVisible = false
    }

    // ---- detail route stack (state, not a navigator: ARCH-042) ----
    val routes = mutableStateListOf<DetailRoute>()
    private var nextRouteKey = 1L

    fun openEntry(entryId: Int, mode: DictMode = routeMode()) {
        val top = routes.lastOrNull()
        if (top is DetailRoute.Entry && top.entryId == entryId && top.mode == mode) return  // ENT-013
        closeWordSheet(userDismissed = false)
        handwritingVisible = false
        routes += DetailRoute.Entry(nextRouteKey++, entryId, mode, dataGeneration.toLong())
    }

    /** A route pushed from another route inherits that route's mode (ENT-010). */
    fun routeMode(): DictMode = (routes.lastOrNull() as? DetailRoute.Entry)?.mode ?: mode

    fun openSettings() { routes += DetailRoute.SettingsRoute(nextRouteKey++) }
    fun openPrivacy() { routes += DetailRoute.Privacy(nextRouteKey++) }
    fun openAttribution() { routes += DetailRoute.Attribution(nextRouteKey++) }

    /** Guarded pop (ENT-014): only if [key] is still the top route. */
    fun popRoute(key: Long): Boolean {
        if (routes.lastOrNull()?.key != key) return false
        routes.removeAt(routes.lastIndex)
        return true
    }

    fun clearRoutes() { routes.clear() }

    // ---- word-candidate sheet ----
    var wordSheet by mutableStateOf<WordSheetRequest?>(null)
        private set
    private var handwritingHiddenBySheet = false

    fun openWordSheet(request: WordSheetRequest) {
        if (handwritingVisible) {           // TXT-047
            handwritingVisible = false
            handwritingHiddenBySheet = true
        }
        wordSheet = request
    }

    fun moveWordSheet(offset: Int) {
        val current = wordSheet ?: return
        wordSheet = current.copy(offset = offset)
        current.onSelectionChanged?.invoke(offset)
    }

    /** Selected offset for [owner]'s passage, or null when the sheet shows another surface. */
    fun selectionFor(owner: Any, passage: AnalysedPassage?): Int? {
        val sheet = wordSheet ?: return null
        return if (sheet.owner == owner && sheet.passage === passage) sheet.offset else null
    }

    fun closeWordSheet(userDismissed: Boolean = true) {
        if (wordSheet == null) return
        wordSheet = null
        if (handwritingHiddenBySheet && userDismissed) handwritingVisible = true
        handwritingHiddenBySheet = false
    }

    /** Close the sheet if it belongs to [owner] (the passage changed, TXT-045). */
    fun closeWordSheetOf(owner: Any) {
        if (wordSheet?.owner == owner) closeWordSheet(userDismissed = false)
    }

    // ---- handwriting sheet (HW-010…): owned by whichever field opened it ----
    var handwritingVisible by mutableStateOf(false)
    /** Receives a committed candidate / a delete request from the handwriting sheet. */
    var handwritingSink: HandwritingSink? = null

    // ---- OCR review back handling (PROD-056 item 3) ----
    var ocrReviewBack: (() -> Boolean)? = null

    // ---- playback: one owner, never overlapping (MED-002) ----
    private val _playing = MutableStateFlow<Any?>(null)
    /** Key of what is playing now (an entry id, a sentence id…), or null. */
    val playing: StateFlow<Any?> = _playing.asStateFlow()
    private var playJob: Job? = null

    fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        env.audio.stop()
        env.speaker.stop()
        _playing.value = null
    }

    /** Plays an entry's recording, falling back to TTS when there is none (MED-011). Tapping again stops. */
    fun play(key: Any, plan: AudioPlan, mode: DictMode = routeMode()) {
        if (_playing.value == key) { stopPlayback(); return }
        stopPlayback()
        _playing.value = key
        playJob = scope.launch {
            try {
                val clips = clipPaths(plan, mode)
                val played = clips != null && env.audio.play(clips)
                if (!played && plan.ttsText.isNotBlank()) env.speaker.speak(plan.ttsText, ttsTag(mode))
            } finally {
                if (_playing.value == key) _playing.value = null
            }
        }
    }

    /** Speaks a sentence with TTS (MED-020). Tapping again stops. */
    fun speak(key: Any, text: String, mode: DictMode = routeMode()) {
        if (_playing.value == key) { stopPlayback(); return }
        stopPlayback()
        if (text.isBlank()) return
        _playing.value = key
        playJob = scope.launch {
            try { env.speaker.speak(text, ttsTag(mode)) } finally { if (_playing.value == key) _playing.value = null }
        }
    }

    /** MED-022: Cantonese → zh-HK; Mandarin follows the script (zh-TW for Traditional). */
    private fun ttsTag(mode: DictMode) = when {
        mode == DictMode.CANTONESE -> "zh-HK"
        _settings.value.script(mode) == Script.TRADITIONAL -> "zh-TW"
        else -> "zh-CN"
    }

    private fun clipPaths(plan: AudioPlan, mode: DictMode): List<String>? {
        if (plan.clips.isEmpty()) return null
        val out = ArrayList<String>()
        for (clip in plan.clips) {
            val dir = when {
                mode == DictMode.CANTONESE -> "yue-syl"
                clip.kind == AudioClipKind.WORD -> "cmn-word"
                else -> "cmn-syl"
            }
            val path = AUDIO_EXTENSIONS.map { "$dir/${clip.key}.$it" }.firstOrNull { env.audio.has(it) } ?: return null
            out += path
        }
        return out
    }

    companion object {
        private val AUDIO_EXTENSIONS = listOf("m4a", "opus", "ogg", "mp3")
    }
}

interface HandwritingSink {
    fun insert(text: String)
    fun deleteBackward()
}
