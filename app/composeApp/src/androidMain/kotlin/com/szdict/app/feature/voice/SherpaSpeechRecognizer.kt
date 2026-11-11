package com.szdict.app.feature.voice

import android.content.res.AssetManager
import com.szdict.voice.VoiceEngine
import com.szdict.voice.VoiceEngineConfig
import com.szdict.voice.VoiceEngines
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * [SpeechRecognizer] over the sherpa-onnx engine of the `:voice` module (ARCH-095). The engine and
 * its streams are confined to one background thread: loading, decoding and releasing all run
 * there, in order, so the model is never released under a running session.
 */
class SherpaSpeechRecognizer(
    private val assets: AssetManager,
    private val config: VoiceEngineConfig = VoiceEngineConfig(),
) : SpeechRecognizer {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "voice-recognizer").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()

    // Confined to the recogniser thread.
    private var engine: VoiceEngine? = null
    private var sessions = 0
    private var releasePending = false

    override suspend fun load() {
        withContext(dispatcher) { loadOnThread() }
    }

    override fun release() {
        executor.execute { if (sessions > 0) releasePending = true else releaseOnThread() }
    }

    override fun transcribe(audio: Flow<FloatArray>): Flow<SpeechEvent> = flow {
        val engine = loadOnThread()
        sessions++
        val stream = engine.createStream()
        try {
            var last = ""
            audio.collect { frame ->
                stream.accept(frame)
                val text = stream.text()
                if (stream.isEndpoint()) {
                    if (text.isNotBlank()) emit(SpeechEvent.Committed(text))
                    emit(SpeechEvent.Endpoint)
                    stream.reset()
                    last = ""
                } else if (text != last) {
                    last = text
                    emit(SpeechEvent.Live(text))
                }
            }
            // Stopping commits a non-empty live utterance (VOICE-025).
            stream.finish()
            val text = stream.text()
            if (text.isNotBlank()) emit(SpeechEvent.Committed(text))
        } finally {
            stream.release()
            sessions--
            if (releasePending && sessions == 0) releaseOnThread()
        }
    }.flowOn(dispatcher)

    private fun loadOnThread(): VoiceEngine {
        releasePending = false
        engine?.let { return it }
        return try {
            VoiceEngines.open(assets, config).also { engine = it }
        } catch (e: Throwable) {
            throw SpeechModelException(cause = e)
        }
    }

    private fun releaseOnThread() {
        releasePending = false
        engine?.release()
        engine = null
    }
}
