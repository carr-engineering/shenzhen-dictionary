package com.szdict.app.feature.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** A microphone driven by the test: [send] frames, [interrupt] it, or make it fail to open. */
class FakeAudioCapture : AudioCapture {
    var fail = false
    var opens = 0
    var open = false
        private set
    private var current: Channel<FloatArray>? = null

    override fun frames(): Flow<FloatArray> = flow {
        opens++
        if (fail) throw AudioCaptureException()
        val channel = Channel<FloatArray>(Channel.UNLIMITED)
        current = channel
        open = true
        try {
            for (frame in channel) emit(frame)
        } finally {
            open = false
        }
    }

    fun send(frame: FloatArray = FloatArray(800) { 0.1f }) {
        current?.trySend(frame)
    }

    /** The system took the microphone away: the flow completes normally. */
    fun interrupt() {
        current?.close()
    }
}

/**
 * A recogniser following the test's script: [say] reports the live utterance, [endpoint] commits
 * it. Like the real one it commits a non-empty live utterance when the audio ends.
 */
class ScriptedSpeechRecognizer : SpeechRecognizer {
    var failLoad = false
    var loadGate: CompletableDeferred<Unit>? = null
    var loads = 0
    var releases = 0
    var loaded = false
        private set
    var sessions = 0
    var activeSessions = 0
        private set
    var framesHeard = 0
        private set

    private var script: Channel<SpeechEvent>? = null
    private var liveText = ""

    override suspend fun load() {
        if (loaded) return
        loads++
        loadGate?.await()
        if (failLoad) throw SpeechModelException()
        loaded = true
    }

    override fun release() {
        releases++
        loaded = false
    }

    override fun transcribe(audio: Flow<FloatArray>): Flow<SpeechEvent> = channelFlow {
        load()
        sessions++
        activeSessions++
        val events = Channel<SpeechEvent>(Channel.UNLIMITED)
        script = events
        liveText = ""
        val forward = launch { for (event in events) send(event) }
        try {
            audio.collect { framesHeard++ }
            events.close()
            forward.join()
            if (liveText.isNotBlank()) send(SpeechEvent.Committed(liveText))
        } finally {
            activeSessions--
            script = null
        }
    }

    /** The live utterance is now [text]. */
    fun say(text: String) {
        liveText = text
        script?.trySend(SpeechEvent.Live(text))
    }

    /** Trailing silence: commits the live utterance, or just resets when nothing was said. */
    fun endpoint() {
        val text = liveText
        liveText = ""
        if (text.isNotBlank()) script?.trySend(SpeechEvent.Committed(text))
        script?.trySend(SpeechEvent.Endpoint)
    }
}

class FakeVoiceModelPack(initial: VoicePackState = VoicePackState.Present) : VoiceModelPack {
    val current = MutableStateFlow(initial)
    var requests = 0
    override val state: StateFlow<VoicePackState> = current
    override fun refresh() {}
    override fun request() {
        requests++
    }
}

class FakeMicPermission(var current: MicPermissionStatus = MicPermissionStatus.GRANTED) : MicPermission {
    /** What the system prompt answers. */
    var answer: MicPermissionStatus = MicPermissionStatus.GRANTED
    var requests = 0
    var settingsOpened = 0

    override fun status(): MicPermissionStatus = current

    override suspend fun request(): MicPermissionStatus {
        requests++
        current = answer
        return current
    }

    override fun openSettings() {
        settingsOpened++
    }
}

class FakeForegroundMonitor : ForegroundMonitor {
    val state = MutableStateFlow(true)
    override val foreground: StateFlow<Boolean> = state
}
