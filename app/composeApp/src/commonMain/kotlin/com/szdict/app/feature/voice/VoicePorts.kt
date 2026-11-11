package com.szdict.app.feature.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Sample rate of every frame crossing the voice ports (ARCH-094). */
const val VOICE_SAMPLE_RATE = 16_000

/** The microphone could not be opened: in use by a call or another app, or no input device (VOICE-012). */
class AudioCaptureException(message: String = "microphone unavailable", cause: Throwable? = null) : Exception(message, cause)

/** An installed voice model could not be loaded (VOICE-023). */
class SpeechModelException(message: String = "voice model unavailable", cause: Throwable? = null) : Exception(message, cause)

/**
 * Microphone capture (ARCH-094): **16 kHz mono PCM** frames as floats in −1…1.
 *
 * [frames] is cold. Collecting it opens the microphone on the capture's own thread; cancelling the
 * collection closes it. It never drops frames under load (VOICE-081) and never writes samples to
 * storage (VOICE-070). It
 * - throws [AudioCaptureException] when the microphone cannot be opened, and
 * - completes normally when the system interrupts the capture (a call, another app taking the
 *   microphone or audio focus — VOICE-062).
 */
interface AudioCapture {
    fun frames(): Flow<FloatArray>
}

/** What a recognition session reports (ARCH-095). Texts are the recogniser's original output. */
sealed interface SpeechEvent {
    /** The current utterance so far. Append-only under greedy decoding (VOICE-024). */
    data class Live(val text: String) : SpeechEvent

    /** An utterance closed by an endpoint, or by the end of the audio (VOICE-025). Never blank. */
    data class Committed(val text: String) : SpeechEvent

    /** An endpoint was detected and the stream reset; follows [Committed] when there was text. */
    data object Endpoint : SpeechEvent
}

/**
 * Streaming speech recognition (ARCH-095, VOICE-020). Decoding runs on the recogniser's own thread.
 */
interface SpeechRecognizer {
    /** Loads the model off the caller's thread. Idempotent. Throws [SpeechModelException]. */
    suspend fun load()

    /** Releases the model; a later [load] loads it again. Sessions must have ended. */
    fun release()

    /**
     * One recognition session over [audio] (16 kHz mono). Loads the model if needed, emits
     * [SpeechEvent]s while audio arrives and, when [audio] completes, commits a non-empty live
     * utterance and completes. Throws [SpeechModelException] when the model cannot be loaded.
     */
    fun transcribe(audio: Flow<FloatArray>): Flow<SpeechEvent>
}

/** Where the voice model is (VOICE-095, ARCH-142). */
sealed interface VoicePackState {
    /** On the device and loadable. The only state of the direct APK, where the model is bundled. */
    data object Present : VoicePackState

    /** Not on the device yet; [sizeBytes] is the download size reported by the platform, or null. */
    data class Missing(val sizeBytes: Long?) : VoicePackState

    /** [progress] in 0…1. [waitingForWifi]: the platform holds the download for Wi-Fi. */
    data class Downloading(val progress: Float, val waitingForWifi: Boolean = false) : VoicePackState

    /**
     * The download failed or was cancelled. [retryable] is false when nothing can fetch the model
     * (a direct APK built without it) — Voice is then unavailable.
     */
    data class Failed(val retryable: Boolean = true) : VoicePackState
}

/**
 * Delivery of the voice model (ARCH-141, ARCH-142): bundled in the direct APK, an on-demand pack
 * on Google Play, a Background Assets pack on iOS. The platform performs any download; the app
 * makes no network request itself (VOICE-021).
 */
interface VoiceModelPack {
    val state: StateFlow<VoicePackState>

    /** Re-reads the platform's state (the pack may have been removed, VOICE-095). */
    fun refresh()

    /**
     * Asks the platform for the pack: on first open, on `Retry`, and on `Download now` (which lets
     * the platform show its own mobile-data confirmation). No-op when present or downloading.
     */
    fun request()
}

enum class MicPermissionStatus {
    /** Not granted, and not refused in this session: asking is appropriate. */
    NOT_REQUESTED,
    GRANTED,

    /** Refused; the system will still show its prompt (`Try again`). */
    DENIED,

    /** Refused for good; only the app's system settings can grant it (`Open app settings`). */
    DENIED_PERMANENTLY,
}

/** The microphone permission (VOICE-010, VOICE-011, ARCH-101). */
interface MicPermission {
    fun status(): MicPermissionStatus

    /** Shows the system prompt when the platform allows it and returns the outcome. */
    suspend fun request(): MicPermissionStatus

    fun openSettings()
}

/** Whether the app is in the foreground (VOICE-062). A configuration change is not a background. */
interface ForegroundMonitor {
    val foreground: StateFlow<Boolean>
}

/**
 * Everything Voice needs from the platform, registered under [KEY] in `AppEnv.services`. Absent on
 * platforms without Voice (and in previews): the destination then reports itself unavailable.
 */
class VoiceServices(
    val capture: AudioCapture,
    val recognizer: SpeechRecognizer,
    val modelPack: VoiceModelPack,
    val permission: MicPermission,
    val foreground: ForegroundMonitor,
) {
    companion object {
        const val KEY = "voice"
    }
}

/**
 * Streaming resampler to [VOICE_SAMPLE_RATE] for devices that cannot capture at 16 kHz (ARCH-094):
 * each output sample is the mean of the input samples it spans (a box low-pass, adequate for
 * speech), with the fractional position carried across frames so no sample is lost or repeated.
 */
class Resampler(private val inputRate: Int, private val outputRate: Int = VOICE_SAMPLE_RATE) {
    init {
        require(inputRate >= outputRate) { "only downsampling is supported" }
    }

    private val step = inputRate.toDouble() / outputRate
    private var carry = FloatArray(0)
    private var position = 0.0   // start of the next output window, in samples of `carry + input`

    fun resample(input: FloatArray, length: Int = input.size): FloatArray {
        if (inputRate == outputRate) return input.copyOf(length)
        val all = FloatArray(carry.size + length)
        carry.copyInto(all)
        input.copyInto(all, carry.size, 0, length)
        val out = FloatArray(((all.size - position) / step).toInt().coerceAtLeast(0))
        var n = 0
        while (n < out.size) {
            val from = position.toInt()
            val to = (position + step).toInt().coerceAtLeast(from + 1)
            if (to > all.size) break
            var sum = 0f
            for (i in from until to) sum += all[i]
            out[n++] = sum / (to - from)
            position += step
        }
        val consumed = position.toInt().coerceAtMost(all.size)
        carry = all.copyOfRange(consumed, all.size)
        position -= consumed
        return if (n == out.size) out else out.copyOf(n)
    }
}
