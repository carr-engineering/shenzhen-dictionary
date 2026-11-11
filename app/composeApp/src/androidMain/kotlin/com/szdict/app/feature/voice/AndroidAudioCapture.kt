package com.szdict.app.feature.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * [AudioCapture] over `AudioRecord` with the voice-recognition source (ARCH-094): 16 kHz mono,
 * resampled when the device cannot deliver 16 kHz, read on its own urgent-audio thread into an
 * unbounded in-memory queue so nothing is dropped under load (VOICE-081). Samples only ever live
 * in memory (VOICE-070).
 *
 * The capture holds transient audio focus; losing it, or being silenced by the system because a
 * call or another app took the microphone, ends the flow normally (VOICE-062).
 */
class AndroidAudioCapture(context: Context) : AudioCapture {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun frames(): Flow<FloatArray> = callbackFlow {
        val running = AtomicBoolean(true)
        val sessionId = AtomicInteger(AudioManager.ERROR)

        fun interrupted() {
            running.set(false)
            close()
        }

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener({ change -> if (change < 0) interrupted() }, mainHandler)
            .build()
        // During a call the system refuses focus: the microphone is not ours to open (VOICE-012).
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw AudioCaptureException("audio focus refused")
        }

        val silenced = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    val id = sessionId.get()
                    if (configs.any { it.clientAudioSessionId == id && it.isClientSilenced }) interrupted()
                }
            }.also { audioManager.registerAudioRecordingCallback(it, mainHandler) }
        } else null

        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var record: AudioRecord? = null
            var delivered = false
            try {
                val opened = open() ?: throw AudioCaptureException()
                record = opened.first
                val rate = opened.second
                sessionId.set(record.audioSessionId)
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw AudioCaptureException("recording did not start")
                val resampler = if (rate != VOICE_SAMPLE_RATE) Resampler(rate) else null
                val pcm = ShortArray(rate / FRAMES_PER_SECOND)
                val floats = FloatArray(pcm.size)
                while (running.get()) {
                    val n = record.read(pcm, 0, pcm.size)
                    if (n < 0) {
                        // The input went away mid-capture: an interruption once audio has flowed.
                        if (!delivered) throw AudioCaptureException("read failed: $n")
                        break
                    }
                    if (n == 0) continue
                    for (i in 0 until n) floats[i] = pcm[i] / 32768f
                    val frame = resampler?.resample(floats, n) ?: floats.copyOf(n)
                    if (frame.isNotEmpty()) {
                        trySend(frame)
                        delivered = true
                    }
                }
                close()
            } catch (e: Throwable) {
                close(e as? AudioCaptureException ?: AudioCaptureException(cause = e))
            } finally {
                record?.runCatching { stop() }
                record?.release()
            }
        }, "voice-capture")
        thread.start()

        awaitClose {
            running.set(false)
            silenced?.let { audioManager.unregisterAudioRecordingCallback(it) }
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }.buffer(Channel.UNLIMITED)

    /** The first input configuration the device accepts, 16 kHz preferred, with its sample rate. */
    @SuppressLint("MissingPermission")
    private fun open(): Pair<AudioRecord, Int>? {
        for (rate in RATES) {
            val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) continue
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum * 2, rate),   // at least half a second of 16-bit mono
                )
            } catch (e: IllegalArgumentException) {
                continue
            } catch (e: SecurityException) {
                return null
            }
            if (record.state == AudioRecord.STATE_INITIALIZED) return record to rate
            record.release()
        }
        return null
    }

    private companion object {
        val RATES = intArrayOf(VOICE_SAMPLE_RATE, 48_000, 44_100, 32_000)
        const val FRAMES_PER_SECOND = 20   // 50 ms frames
    }
}
