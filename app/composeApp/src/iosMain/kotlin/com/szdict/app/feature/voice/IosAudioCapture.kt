@file:OptIn(ExperimentalForeignApi::class)

package com.szdict.app.feature.voice

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineConfigurationChangeNotification
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionMediaServicesWereLostNotification
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.currentRoute
import platform.AVFAudio.setActive
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create

/**
 * [AudioCapture] over the `AVAudioEngine` input node (ARCH-094): the session records in
 * measurement mode (no voice processing), the tap delivers the hardware format (typically 48 kHz
 * float), and each buffer is mixed down to mono and resampled to 16 kHz. Frames go into an
 * unbounded in-memory queue so nothing is dropped under load (VOICE-081); samples only ever live
 * in memory (VOICE-070).
 *
 * Everything that touches the engine runs on one serial queue. A route change (a headset plugged
 * in or pulled out) restarts the tap with the new hardware format. An interruption (a call, another
 * app taking the microphone), the loss of every input, a reset of the media services or the app
 * entering the background ends the flow normally (VOICE-062).
 */
class IosAudioCapture : AudioCapture {
    override fun frames(): Flow<FloatArray> = callbackFlow {
        val queue = dispatch_queue_create("voice-capture", null)
        val callbacks = NSOperationQueue().apply {
            underlyingQueue = queue
            maxConcurrentOperationCount = 1
        }
        val center = NSNotificationCenter.defaultCenter
        val session = AVAudioSession.sharedInstance()
        val observers = ArrayList<NSObjectProtocol>()

        // Confined to `queue`.
        var engine: AVAudioEngine? = null
        var ended = false

        fun stopEngine() {
            engine?.let {
                it.inputNode.removeTapOnBus(0u)
                it.stop()
            }
            engine = null
        }

        /** A fresh engine tapping the current input; false when there is no usable input. */
        fun startEngine(): Boolean {
            stopEngine()
            val fresh = AVAudioEngine()
            val input = fresh.inputNode
            val format = input.outputFormatForBus(0u)
            val rate = format.sampleRate.toInt()
            if (rate <= 0 || format.channelCount == 0u) return false
            val toVoiceRate = VoiceRate(rate)
            input.installTapOnBus(0u, bufferSize = (rate / FRAMES_PER_SECOND).toUInt(), format = format) { buffer, _ ->
                val frame = buffer?.let { toVoiceRate.convert(it) }
                if (frame != null && frame.isNotEmpty()) trySend(frame)
            }
            fresh.prepare()
            if (!fresh.startAndReturnError(null)) {
                input.removeTapOnBus(0u)
                return false
            }
            engine = fresh
            return true
        }

        fun tearDown() {
            if (ended) return
            ended = true
            observers.forEach { center.removeObserver(it) }
            observers.clear()
            stopEngine()
            session.setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
        }

        /** The system took the capture away: the flow completes normally. */
        fun interrupted() {
            tearDown()
            close()
        }

        fun observe(name: String?, block: (NSNotification?) -> Unit) {
            observers += center.addObserverForName(name, `object` = null, queue = callbacks) { note -> if (!ended) block(note) }
        }

        fun open() {
            // During a call the session cannot be activated: the microphone is not ours (VOICE-012).
            if (!session.setCategory(AVAudioSessionCategoryRecord, mode = AVAudioSessionModeMeasurement, options = 0u, error = null) ||
                !session.setActive(true, error = null)
            ) {
                throw AudioCaptureException("audio session refused")
            }
            if (!startEngine()) throw AudioCaptureException()
            observe(AVAudioSessionInterruptionNotification) { note ->
                if (number(note?.userInfo?.get(AVAudioSessionInterruptionTypeKey)) == AVAudioSessionInterruptionTypeBegan) interrupted()
            }
            observe(AVAudioSessionRouteChangeNotification) {
                if (session.currentRoute.inputs.isEmpty()) interrupted()
            }
            // The hardware format changed and the engine stopped itself: tap the new input.
            observe(AVAudioEngineConfigurationChangeNotification) { note ->
                if (note?.`object` === engine && !startEngine()) interrupted()
            }
            observe(AVAudioSessionMediaServicesWereLostNotification) { interrupted() }
            observe(AVAudioSessionMediaServicesWereResetNotification) { interrupted() }
            // There is no background recording.
            observe(UIApplicationDidEnterBackgroundNotification) { interrupted() }
        }

        val failure = suspendCoroutine { continuation ->
            dispatch_async(queue) {
                val result = runCatching { open() }
                if (result.isFailure) tearDown()
                continuation.resume(result.exceptionOrNull())
            }
        }
        if (failure != null) throw failure as? AudioCaptureException ?: AudioCaptureException(cause = failure)

        awaitClose { dispatch_async(queue) { tearDown() } }
    }.buffer(Channel.UNLIMITED)

    private companion object {
        const val FRAMES_PER_SECOND = 10   // the tap delivers about 100 ms at a time

        /** A number out of a notification's `userInfo`, however the bridge boxed it. */
        fun number(value: Any?): ULong? = when (value) {
            is NSNumber -> value.unsignedLongValue
            is ULong -> value
            is UInt -> value.toULong()
            is Number -> value.toLong().toULong()
            else -> null
        }
    }
}

/** Tap buffers (float, any channel count, the hardware rate) → 16 kHz mono frames. */
private class VoiceRate(private val inputRate: Int) {
    private val down = if (inputRate >= VOICE_SAMPLE_RATE) Resampler(inputRate) else null

    // Upsampling (an 8 kHz telephony input): linear interpolation, position carried across buffers.
    private var previous = 0f
    private var position = 0.0

    fun convert(buffer: AVAudioPCMBuffer): FloatArray {
        val channels = buffer.format.channelCount.toInt()
        val data = buffer.floatChannelData ?: return FloatArray(0)
        val length = buffer.frameLength.toInt()
        if (channels == 0 || length == 0) return FloatArray(0)
        val mono = FloatArray(length)
        for (channel in 0 until channels) {
            val samples = data[channel] ?: continue
            for (i in 0 until length) mono[i] += samples[i]
        }
        if (channels > 1) for (i in 0 until length) mono[i] /= channels
        return down?.resample(mono) ?: up(mono)
    }

    private fun up(input: FloatArray): FloatArray {
        val step = inputRate.toDouble() / VOICE_SAMPLE_RATE
        val out = ArrayList<Float>((input.size / step).toInt() + 1)
        // `position` runs over [-1, size - 1): index -1 is the last sample of the previous buffer.
        while (position < input.size - 1) {
            val index = kotlin.math.floor(position).toInt()
            val fraction = (position - index).toFloat()
            val a = if (index < 0) previous else input[index]
            out += a + (input[index + 1] - a) * fraction
            position += step
        }
        position -= input.size
        previous = input[input.size - 1]
        return out.toFloatArray()
    }
}
