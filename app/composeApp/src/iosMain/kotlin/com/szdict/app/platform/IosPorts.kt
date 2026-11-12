@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.szdict.app.platform

import kotlin.concurrent.AtomicReference
import kotlin.coroutines.resume
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard
import platform.UIKit.UIPasteboardChangedNotification
import platform.darwin.NSObject

/**
 * Hooks the Swift host installs (iosApp/SZDict/SZDictApp.swift) for the capabilities that only
 * exist as SwiftUI API.
 */
object IosHost {
    /**
     * Presents Apple's translation sheet for the text (MED-032); the host itself falls back to the
     * web page of MED-030 (`openWebTranslation`) when the language pair is unsupported. Arguments:
     * text, source language tag (`zh-CN`, `zh-TW`, `yue`).
     */
    var translate: ((String, String) -> Unit)? = null
}

/**
 * Recorded clips, read in place from `assets/data/packs/audio/` (MED-084). The pack's Ogg Opus
 * files are decoded by AudioToolbox itself (iOS 26), so they play through [AVAudioPlayer] as they are.
 */
class IosAudioPlayer(private val root: String = IosBundle.path("data/packs/audio")) : AudioPlayer {
    private var current: AVAudioPlayer? = null
    private var currentDelegate: NSObject? = null
    private val listings = AtomicReference<Map<String, Set<String>>>(emptyMap())

    override fun has(clip: String): Boolean {
        val dir = clip.substringBeforeLast('/', "")
        val name = clip.substringAfterLast('/')
        val known = listings.value[dir]
        val listing = known ?: run {
            val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath("$root/$dir".trimEnd('/'), null)
                ?.mapNotNull { it as? String }?.toHashSet() ?: emptySet()
            while (true) {
                val old = listings.value
                if (listings.compareAndSet(old, old + (dir to names))) break
            }
            names
        }
        return name in listing
    }

    override suspend fun play(clips: List<String>): Boolean = withContext(Dispatchers.Main.immediate) {
        stop()
        if (clips.isEmpty() || clips.any { !has(it) }) return@withContext false
        for (clip in clips) if (!playOne(clip)) return@withContext false
        true
    }

    private suspend fun playOne(clip: String): Boolean = suspendCancellableCoroutine { cont ->
        val player = try {
            AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath("$root/$clip"), error = null)
        } catch (e: Throwable) {
            null
        }
        if (player == null) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        fun finish(ok: Boolean) {
            if (current === player) {
                current = null
                currentDelegate = null
            }
            if (cont.isActive) cont.resume(ok)
        }
        val delegate = object : NSObject(), AVAudioPlayerDelegateProtocol {
            override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) = finish(successfully)
            override fun audioPlayerDecodeErrorDidOccur(player: AVAudioPlayer, error: NSError?) = finish(false)
        }
        player.delegate = delegate
        current = player
        currentDelegate = delegate   // the player holds its delegate weakly
        preparePlaybackSession()
        if (!player.prepareToPlay() || !player.play()) finish(false)
        cont.invokeOnCancellation {
            runOnMain {
                player.delegate = null
                player.stop()
                if (current === player) {
                    current = null
                    currentDelegate = null
                }
            }
        }
    }

    override fun stop() {
        current?.let {
            it.delegate = null
            it.stop()
        }
        current = null
        currentDelegate = null
    }
}

/**
 * Clips and speech are media, so they sound with the ring switch off; other apps' audio keeps
 * playing. A session the Voice destination configured for recording is left as it is.
 */
internal fun preparePlaybackSession() {
    val session = AVAudioSession.sharedInstance()
    val category = session.category
    if (category == AVAudioSessionCategoryPlayback || category == AVAudioSessionCategoryPlayAndRecord) return
    runCatching { session.setCategory(AVAudioSessionCategoryPlayback, withOptions = AVAudioSessionCategoryOptionMixWithOthers, error = null) }
}

internal fun runOnMain(block: () -> Unit) {
    if (platform.Foundation.NSThread.isMainThread) block() else platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue(), block)
}

private class SpeechDelegate(private val onEnd: (AVSpeechUtterance, Boolean) -> Unit) : NSObject(), AVSpeechSynthesizerDelegateProtocol {
    @kotlinx.cinterop.ObjCSignatureOverride
    override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) =
        onEnd(didFinishSpeechUtterance, true)

    @kotlinx.cinterop.ObjCSignatureOverride
    override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) =
        onEnd(didCancelSpeechUtterance, false)
}

/** AVSpeechSynthesizer with the system's zh-CN / zh-TW / zh-HK voices (MED-020, MED-022). */
class IosSpeaker : Speaker {
    private val pending = HashMap<AVSpeechUtterance, (Boolean) -> Unit>()   // main thread only
    private val delegate = SpeechDelegate { utterance, ok -> pending.remove(utterance)?.invoke(ok) }
    private val synthesizer: AVSpeechSynthesizer by lazy { AVSpeechSynthesizer().also { it.delegate = delegate } }

    override suspend fun speak(text: String, languageTag: String): Boolean = withContext(Dispatchers.Main.immediate) {
        // Cantonese voices may be absent; any Chinese voice rather than silence.
        val voice = AVSpeechSynthesisVoice.voiceWithLanguage(languageTag)
            ?: (if (languageTag.startsWith("zh") || languageTag.startsWith("yue")) AVSpeechSynthesisVoice.voiceWithLanguage("zh-CN") else null)
            ?: return@withContext false
        val utterance = AVSpeechUtterance.speechUtteranceWithString(text)
        utterance.voice = voice
        preparePlaybackSession()
        suspendCancellableCoroutine { cont ->
            pending[utterance] = { ok -> if (cont.isActive) cont.resume(ok) }
            if (synthesizer.isSpeaking()) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
            synthesizer.speakUtterance(utterance)
            cont.invokeOnCancellation {
                runOnMain {
                    pending.remove(utterance)
                    synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
                }
            }
        }
    }

    override fun stop() {
        runOnMain { if (synthesizer.isSpeaking()) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate) }
    }
}

/**
 * UIPasteboard. Availability is asked with `hasStrings`, which reads nothing and so never shows
 * the system paste notice; the text itself is read only when the user pastes (TXT-053, TXT-055).
 */
class IosClipboard : Clipboard {
    private val state = MutableStateFlow(false)
    override val hasText: Flow<Boolean> = state
    private val observer = NSNotificationCenter.defaultCenter.addObserverForName(UIPasteboardChangedNotification, null, NSOperationQueue.mainQueue) { refresh() }

    override fun refresh() {
        state.value = runCatching { UIPasteboard.generalPasteboard.hasStrings }.getOrDefault(false)
    }

    override fun readText(): String? = runCatching { UIPasteboard.generalPasteboard.string?.takeIf { it.isNotBlank() } }.getOrNull()

    override fun writeText(text: String) {
        runCatching { UIPasteboard.generalPasteboard.string = text }
        refresh()
    }
}

class IosLinkOpener : LinkOpener {
    override fun open(url: String) {
        val target = NSURL.URLWithString(url) ?: return
        runOnMain { UIApplication.sharedApplication.openURL(target, emptyMap<Any?, Any>(), null) }
    }
}

/**
 * MED-032: Apple's translation sheet, presented by the Swift host; the Google Translate page of
 * MED-030 in the system browser when the sheet cannot translate the language pair.
 */
class IosTranslator(private val links: LinkOpener) : Translator {
    override fun translate(text: String, sourceLanguageTag: String) {
        val capped = text.take(1500) // MED-030
        runOnMain {
            val present = IosHost.translate
            if (present != null) present(capped, sourceLanguageTag) else openWeb(capped, sourceLanguageTag)
        }
    }

    /** The web hand-off; also called by the Swift host when the language pair turns out unsupported. */
    fun openWeb(text: String, sourceLanguageTag: String) {
        val components = NSURLComponents(string = "https://translate.google.com/")
        components.queryItems = listOf(
            NSURLQueryItem(name = "sl", value = sourceLanguageTag),
            NSURLQueryItem(name = "tl", value = "en"),
            NSURLQueryItem(name = "text", value = text),
            NSURLQueryItem(name = "op", value = "translate"),
        )
        // `+` is left alone by the query encoding but means a space to the page.
        components.percentEncodedQuery = components.percentEncodedQuery?.replace("+", "%2B")
        components.URL?.absoluteString?.let(links::open)
    }
}

/**
 * Settings in UserDefaults (ARCH-110): the whole map under one key, so every update replaces it
 * atomically. UserDefaults persists asynchronously on its own and at app suspension.
 */
class UserDefaultsSettings : SettingsStorage {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val lock = NSLock()
    private val state = MutableStateFlow(load())
    override val values: Flow<Map<String, String>> = state

    private fun load(): Map<String, String> {
        val stored = defaults.dictionaryForKey(KEY) ?: return emptyMap()
        val out = HashMap<String, String>()
        for ((key, value) in stored) if (key is String && value is String) out[key] = value
        return out
    }

    override suspend fun update(changes: Map<String, String?>) = flushBlocking(changes)

    override fun flushBlocking(changes: Map<String, String?>) {
        if (changes.isEmpty()) return
        lock.lock()
        try {
            val next = HashMap(state.value)
            for ((key, value) in changes) if (value == null) next.remove(key) else next[key] = value
            defaults.setObject(next, KEY)
            state.value = next
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        const val KEY = "szdict.settings"
    }
}
