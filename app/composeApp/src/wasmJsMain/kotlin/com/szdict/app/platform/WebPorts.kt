@file:OptIn(ExperimentalWasmJsInterop::class)

package com.szdict.app.platform

import kotlinx.browser.localStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.resume
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise

/** Settings in `localStorage` (ARCH-110), one item per key under a common prefix. */
class LocalStorageSettings : SettingsStorage {
    private val state = MutableStateFlow(load())
    override val values: Flow<Map<String, String>> = state

    private fun load(): Map<String, String> = try {
        val out = HashMap<String, String>()
        for (i in 0 until localStorage.length) {
            val key = localStorage.key(i) ?: continue
            if (key.startsWith(PREFIX)) localStorage.getItem(key)?.let { out[key.removePrefix(PREFIX)] = it }
        }
        out
    } catch (e: Throwable) {
        emptyMap()   // storage blocked (private browsing): settings last for the page only
    }

    override suspend fun update(changes: Map<String, String?>) = flushBlocking(changes)

    override fun flushBlocking(changes: Map<String, String?>) {
        if (changes.isEmpty()) return
        val next = HashMap(state.value)
        for ((key, value) in changes) {
            if (value == null) next.remove(key) else next[key] = value
            try {
                if (value == null) localStorage.removeItem(PREFIX + key) else localStorage.setItem(PREFIX + key, value)
            } catch (e: Throwable) {
                // quota or blocked storage: keep the in-memory value
            }
        }
        state.value = next
    }

    private companion object {
        const val PREFIX = "szdict.setting."
    }
}

private fun jsReadClipboard(): Promise<JsString?> = js(
    "(navigator.clipboard && navigator.clipboard.readText) ? navigator.clipboard.readText().catch(function () { return null; }) : Promise.resolve(null)"
)

private fun jsWriteClipboard(text: String): Unit = js(
    "{ if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).catch(function () {}); }"
)

/**
 * Async Clipboard API. A page cannot ask whether the clipboard holds text without reading it, and a
 * read needs a user gesture (TXT-059), so `Paste` is always offered and a refused read is reported.
 */
class WebClipboard(private val scope: CoroutineScope) : Clipboard {
    override val hasText: Flow<Boolean> = MutableStateFlow(true)
    override fun refresh() {}
    override fun readText(): String? = null

    override fun read(onResult: (text: String?, denied: Boolean) -> Unit) {
        val pending = jsReadClipboard()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val text = pending.await<JsString?>()?.toString()
            onResult(text, text == null)
        }
    }

    override fun writeText(text: String) = jsWriteClipboard(text)
}

private fun jsOpen(url: String): Unit = js("{ window.open(url, '_blank', 'noopener,noreferrer'); }")

class WebLinkOpener : LinkOpener {
    override fun open(url: String) = jsOpen(url)
}

private fun jsEncode(text: String): String = js("encodeURIComponent(text)")

/** Google Translate in a new tab (MED-030). */
class WebTranslator : Translator {
    override fun translate(text: String, sourceLanguageTag: String) {
        val capped = text.take(1500)
        jsOpen("https://translate.google.com/?sl=${jsEncode(sourceLanguageTag)}&tl=en&text=${jsEncode(capped)}&op=translate")
    }
}

private fun jsSpeak(text: String, lang: String, done: (Boolean) -> Unit): Boolean = js(
    """{
        var synth = window.speechSynthesis;
        if (!synth || typeof SpeechSynthesisUtterance === 'undefined') return false;
        var u = new SpeechSynthesisUtterance(text);
        u.lang = lang;
        var base = lang.split('-')[0];
        var voices = synth.getVoices() || [];
        var pick = null;
        for (var i = 0; i < voices.length && !pick; i++) if (voices[i].lang.replace('_', '-') === lang) pick = voices[i];
        // Cantonese voices are often absent: any Chinese voice rather than silence.
        for (var j = 0; j < voices.length && !pick; j++) if (voices[j].lang.split(/[-_]/)[0] === base) pick = voices[j];
        if (pick) u.voice = pick;
        u.onend = function () { done(true); };
        u.onerror = function () { done(false); };
        synth.cancel();
        synth.speak(u);
        return true;
    }"""
)

private fun jsStopSpeaking(): Unit = js("{ if (window.speechSynthesis) window.speechSynthesis.cancel(); }")

/** `speechSynthesis` with the zh-CN / zh-TW / zh-HK voices the browser offers (MED-020, MED-022). */
class WebSpeaker : Speaker {
    override suspend fun speak(text: String, languageTag: String): Boolean = suspendCancellableCoroutine { cont ->
        val started = jsSpeak(text, languageTag) { ok -> if (cont.isActive) cont.resume(ok) }
        if (!started && cont.isActive) cont.resume(false)
        cont.invokeOnCancellation { jsStopSpeaking() }
    }

    override fun stop() = jsStopSpeaking()
}

private external interface AudioHandle : JsAny

private fun jsPlay(url: String, done: (Boolean) -> Unit): AudioHandle = js(
    """{
        var a = new Audio(url);
        var finished = false;
        function finish(ok) { if (!finished) { finished = true; done(ok); } }
        a.onended = function () { finish(true); };
        a.onerror = function () { finish(false); };
        var p = a.play();
        if (p && p.catch) p.catch(function () { finish(false); });
        return a;
    }"""
)

private fun jsStop(handle: AudioHandle): Unit = js("{ handle.onended = null; handle.onerror = null; handle.pause(); handle.removeAttribute('src'); }")

/**
 * Recorded clips fetched on demand from `data/packs/audio/` (ARCH-143). [has] answers from the
 * audio catalogue once [setCatalog] has it; a clip the browser cannot play (Ogg in some Safari
 * versions) or fetch makes [play] return false, and the caller falls back to TTS (MED-011).
 */
class WebAudioPlayer : AudioPlayer {
    private var clips: Set<String> = emptySet()
    private var current: AudioHandle? = null

    /** `packs/audio/catalog.json`: per mode, the directories, the extension and the recorded keys. */
    fun setCatalog(text: String) {
        val out = HashSet<String>()
        try {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return
            for (section in root.values) {
                if (section !is JsonObject) continue
                val ext = (section["ext"] as? JsonPrimitive)?.content ?: continue
                fun add(dirKey: String, keysKey: String) {
                    val dir = (section[dirKey] as? JsonPrimitive)?.content ?: return
                    (section[keysKey] as? JsonArray)?.forEach { key -> (key as? JsonPrimitive)?.let { out.add("$dir/${it.content}.$ext") } }
                }
                add("syllables", "syllable_keys")
                add("words", "word_keys")
            }
        } catch (e: Throwable) {
            return
        }
        clips = out
    }

    override fun has(clip: String): Boolean = clip in clips

    override suspend fun play(clips: List<String>): Boolean {
        stop()
        if (clips.isEmpty() || clips.any { !has(it) }) return false
        for (clip in clips) if (!playOne(AUDIO_ROOT + clip)) return false
        return true
    }

    private suspend fun playOne(url: String): Boolean = suspendCancellableCoroutine { cont ->
        var handle: AudioHandle? = null
        handle = jsPlay(url) { ok ->
            if (current === handle) current = null
            if (cont.isActive) cont.resume(ok)
        }
        current = handle
        cont.invokeOnCancellation {
            jsStop(handle)
            if (current === handle) current = null
        }
    }

    override fun stop() {
        current?.let { jsStop(it) }
        current = null
    }

    private companion object {
        const val AUDIO_ROOT = "data/packs/audio/"
    }
}

private fun jsLanguage(): String = js("navigator.language || 'en'")

internal fun browserLanguageTag(): String = jsLanguage()

private fun jsPersist(): Unit = js(
    "{ if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(function () {}); }"
)

/** Asks the browser not to evict the dataset (ARCH-145). Harmless when refused or unsupported. */
internal fun requestPersistentStorage() = jsPersist()
