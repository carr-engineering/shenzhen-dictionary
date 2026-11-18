package com.szdict.app.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

private const val AUDIO_ROOT = "data/packs/audio/"

/** Recorded clips are read in place from APK assets (stored uncompressed), never copied out. */
class AndroidAudioPlayer(private val context: Context) : AudioPlayer {
    private var current: MediaPlayer? = null
    private val dirListings = ConcurrentHashMap<String, Set<String>>()

    override fun isOutputMuted(): Boolean {
        val manager = context.getSystemService(AudioManager::class.java) ?: return false
        return manager.isStreamMute(AudioManager.STREAM_MUSIC) || manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
    }

    override fun has(clip: String): Boolean {
        val dir = clip.substringBeforeLast('/', "")
        val name = clip.substringAfterLast('/')
        val listing = dirListings.getOrPut(dir) {
            runCatching { context.assets.list((AUDIO_ROOT + dir).trimEnd('/'))?.toSet() }.getOrNull() ?: emptySet()
        }
        return name in listing
    }

    override suspend fun play(clips: List<String>): Boolean {
        stop()
        if (clips.isEmpty() || clips.any { !has(it) }) return false
        for (clip in clips) {
            val ok = playOne(clip)
            if (!ok) return false
        }
        return true
    }

    private suspend fun playOne(clip: String): Boolean = suspendCancellableCoroutine { cont ->
        val player = MediaPlayer()
        try {
            context.assets.openFd(AUDIO_ROOT + clip).use { fd ->
                player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            player.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            player.setOnCompletionListener {
                it.release(); if (current === it) current = null
                if (cont.isActive) cont.resume(true)
            }
            player.setOnErrorListener { mp, _, _ ->
                mp.release(); if (current === mp) current = null
                if (cont.isActive) cont.resume(false)
                true
            }
            player.setOnPreparedListener { it.start() }
            current = player
            player.prepareAsync()
        } catch (e: Exception) {
            player.release()
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation {
            runCatching { player.release() }
            if (current === player) current = null
        }
    }

    override fun stop() {
        current?.let { runCatching { it.release() } }
        current = null
    }
}

class AndroidSpeaker(private val context: Context) : Speaker {
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val pending = ConcurrentHashMap<String, (Boolean) -> Unit>()

    private suspend fun engine(): TextToSpeech? {
        val existing = ready
        if (existing != null) return if (existing.await()) tts else null
        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        withContext(Dispatchers.Main) {
            tts = TextToSpeech(context.applicationContext) { status ->
                deferred.complete(status == TextToSpeech.SUCCESS)
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { pending.remove(utteranceId)?.invoke(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { pending.remove(utteranceId)?.invoke(false) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    pending.remove(utteranceId)?.invoke(false)
                }
            })
        }
        return if (deferred.await()) tts else null
    }

    override suspend fun speak(text: String, languageTag: String): Boolean {
        val engine = engine() ?: return false
        val locale = Locale.forLanguageTag(languageTag)
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Cantonese voices are often absent; fall back to any Chinese voice rather than staying silent.
            if (engine.setLanguage(Locale.CHINESE) < TextToSpeech.LANG_AVAILABLE) return false
        }
        val id = "u" + System.nanoTime()
        return suspendCancellableCoroutine { cont ->
            pending[id] = { ok -> if (cont.isActive) cont.resume(ok) }
            val status = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            if (status != TextToSpeech.SUCCESS) {
                pending.remove(id); if (cont.isActive) cont.resume(false)
            }
            cont.invokeOnCancellation { pending.remove(id); engine.stop() }
        }
    }

    override fun stop() { tts?.stop() }

    fun shutdown() { tts?.shutdown(); tts = null; ready = null }
}

class AndroidClipboard(context: Context) : Clipboard {
    private val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val state = MutableStateFlow(false)
    override val hasText: Flow<Boolean> = state

    init { manager.addPrimaryClipChangedListener { refresh() } }

    /** Only call while the window has focus (TXT-053). */
    override fun refresh() {
        state.value = runCatching {
            val description = manager.primaryClipDescription
            manager.hasPrimaryClip() && description != null &&
                (0 until description.mimeTypeCount).any { description.getMimeType(it).startsWith("text/") }
        }.getOrDefault(false)
    }

    override fun readText(): String? = runCatching {
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        clip.getItemAt(0).coerceToText(null)?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    override fun writeText(text: String) {
        runCatching { manager.setPrimaryClip(ClipData.newPlainText("szdict", text)) }
    }
}

class AndroidTranslator(private val context: Context) : Translator {
    override fun translate(text: String, sourceLanguageTag: String) {
        val capped = text.take(1500) // MED-030
        val direct = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .setPackage("com.google.android.apps.translate")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, capped)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(direct)
        } catch (e: Exception) {
            val url = Uri.parse("https://translate.google.com/").buildUpon()
                .appendQueryParameter("sl", sourceLanguageTag).appendQueryParameter("tl", "en")
                .appendQueryParameter("text", capped).appendQueryParameter("op", "translate").build()
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

class AndroidLinkOpener(private val context: Context) : LinkOpener {
    override fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class DataStoreSettingsStorage(private val context: Context) : SettingsStorage {
    override val values: Flow<Map<String, String>> = context.settingsDataStore.data.map { prefs ->
        prefs.asMap().entries.mapNotNull { (k, v) -> (v as? String)?.let { k.name to it } }.toMap()
    }

    // DataStore runs the edit block in the caller's context. Off the main thread, so that
    // [flushBlocking] (called from onStop on the main thread) can never wait on an earlier edit
    // whose block is queued behind it on the main looper — that deadlock was an ANR.
    override suspend fun update(changes: Map<String, String?>) = withContext(Dispatchers.IO) {
        context.settingsDataStore.edit { prefs ->
            for ((key, value) in changes) {
                val prefKey = stringPreferencesKey(key)
                if (value == null) prefs.remove(prefKey) else prefs[prefKey] = value
            }
        }
        Unit
    }

    override fun flushBlocking(changes: Map<String, String?>) {
        if (changes.isEmpty()) return
        runBlocking { update(changes) }
    }
}
