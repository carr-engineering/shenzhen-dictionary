package com.szdict.app.feature.voice

import com.szdict.speech.Speech
import com.szdict.speech.SpeechEngine
import com.szdict.speech.SpeechEngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext

/**
 * [SpeechRecognizer] over the sherpa-onnx engine of the `:speech` module (ARCH-095). The engine and
 * its streams are confined to one background thread: loading, decoding and releasing all run
 * there, in order, so the model is never released under a running session.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class SherpaSpeechRecognizer(
    private val modelDir: String?,
    private val config: SpeechEngineConfig = SpeechEngineConfig(),
) : SpeechRecognizer {
    private val dispatcher = newSingleThreadContext("voice-recognizer")
    private val scope = CoroutineScope(dispatcher)

    // Confined to the recogniser thread.
    private var engine: SpeechEngine? = null
    private var sessions = 0
    private var releasePending = false

    override suspend fun load() {
        withContext(dispatcher) { loadOnThread() }
    }

    override fun release() {
        scope.launch { if (sessions > 0) releasePending = true else releaseOnThread() }
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

    private fun loadOnThread(): SpeechEngine {
        releasePending = false
        engine?.let { return it }
        return try {
            Speech.open(modelDir ?: error("voice model is not bundled"), config).also { engine = it }
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
