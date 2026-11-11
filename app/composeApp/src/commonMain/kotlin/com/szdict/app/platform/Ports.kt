package com.szdict.app.platform

import kotlinx.coroutines.flow.Flow

/**
 * Narrow platform capabilities (ARCH-090). Shared code never branches on platform; each port has a
 * no-op implementation (ARCH-091) used in tests and where the capability is absent.
 */

/** Plays a sequence of recorded clips; completes when finished or cancelled (ARCH-092). */
interface AudioPlayer {
    /** [clips] are asset-relative paths such as `cmn-syl/ni3.m4a`. Returns false if any clip is missing. */
    suspend fun play(clips: List<String>): Boolean
    fun has(clip: String): Boolean
    fun stop()
}

/** Text-to-speech for sentences and for words without a recording (MED-011, MED-020). */
interface Speaker {
    /** [languageTag] e.g. `zh-CN`, `zh-HK`, `en-US`. Suspends until the utterance ends or is cancelled. */
    suspend fun speak(text: String, languageTag: String): Boolean
    fun stop()
}

interface Clipboard {
    /** Emits whenever availability of usable text may have changed (TXT-055). */
    val hasText: Flow<Boolean>
    fun refresh()
    fun readText(): String?
    fun writeText(text: String)
}

/** Translate hand-off (MED-030…). */
interface Translator {
    fun translate(text: String, sourceLanguageTag: String)
}

/** Key-value settings persistence (ARCH-110): an observable snapshot plus atomic updates. */
interface SettingsStorage {
    val values: Flow<Map<String, String>>
    suspend fun update(changes: Map<String, String?>)
    /** Synchronous best-effort flush for lifecycle stop (ARCH-112). */
    fun flushBlocking(changes: Map<String, String?>)
}

interface LinkOpener {
    fun open(url: String)
}

object NoAudioPlayer : AudioPlayer {
    override suspend fun play(clips: List<String>) = false
    override fun has(clip: String) = false
    override fun stop() {}
}

object NoSpeaker : Speaker {
    override suspend fun speak(text: String, languageTag: String) = false
    override fun stop() {}
}
