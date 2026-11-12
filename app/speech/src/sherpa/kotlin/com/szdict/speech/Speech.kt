@file:OptIn(ExperimentalForeignApi::class)

package com.szdict.speech

import com.szdict.speech.sherpa.SherpaOnnxCreateOnlineRecognizer
import com.szdict.speech.sherpa.SherpaOnnxCreateOnlineStream
import com.szdict.speech.sherpa.SherpaOnnxDecodeOnlineStream
import com.szdict.speech.sherpa.SherpaOnnxDestroyOnlineRecognizer
import com.szdict.speech.sherpa.SherpaOnnxDestroyOnlineRecognizerResult
import com.szdict.speech.sherpa.SherpaOnnxDestroyOnlineStream
import com.szdict.speech.sherpa.SherpaOnnxGetOnlineStreamResult
import com.szdict.speech.sherpa.SherpaOnnxGetVersionStr
import com.szdict.speech.sherpa.SherpaOnnxIsOnlineStreamReady
import com.szdict.speech.sherpa.SherpaOnnxOnlineRecognizer
import com.szdict.speech.sherpa.SherpaOnnxOnlineRecognizerConfig
import com.szdict.speech.sherpa.SherpaOnnxOnlineStream
import com.szdict.speech.sherpa.SherpaOnnxOnlineStreamAcceptWaveform
import com.szdict.speech.sherpa.SherpaOnnxOnlineStreamInputFinished
import com.szdict.speech.sherpa.SherpaOnnxOnlineStreamIsEndpoint
import com.szdict.speech.sherpa.SherpaOnnxOnlineStreamReset
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.memset

/** sherpa-onnx streaming Paraformer through the C API (ARCH-095). */
object Speech {
    /** True when the sherpa-onnx library is linked into this build. */
    const val AVAILABLE: Boolean = true

    /** The linked sherpa-onnx version. */
    val VERSION: String get() = SherpaOnnxGetVersionStr()?.toKString().orEmpty()

    /**
     * Loads the model in [modelDir], an absolute path (slow: call off the UI thread). Throws when
     * the model is missing or corrupt.
     */
    fun open(modelDir: String, config: SpeechEngineConfig = SpeechEngineConfig()): SpeechEngine {
        check(SpeechModel.isPresent(modelDir)) { "voice model is not in $modelDir" }
        val recognizer = memScoped {
            val c = alloc<SherpaOnnxOnlineRecognizerConfig>()
            memset(c.ptr, 0, sizeOf<SherpaOnnxOnlineRecognizerConfig>().toULong())
            c.feat_config.sample_rate = SpeechModel.SAMPLE_RATE
            c.feat_config.feature_dim = 80
            c.model_config.paraformer.encoder = "$modelDir/${SpeechModel.ENCODER}".cstr.ptr
            c.model_config.paraformer.decoder = "$modelDir/${SpeechModel.DECODER}".cstr.ptr
            c.model_config.tokens = "$modelDir/${SpeechModel.TOKENS}".cstr.ptr
            c.model_config.num_threads = config.threads
            c.model_config.debug = 0
            c.model_config.provider = "cpu".cstr.ptr
            c.model_config.model_type = "paraformer".cstr.ptr
            c.enable_endpoint = 1
            // Silence with nothing decoded: reset without committing.
            c.rule1_min_trailing_silence = config.resetSilenceSeconds
            // Trailing silence after speech: commit.
            c.rule2_min_trailing_silence = config.commitSilenceSeconds
            // Maximum utterance length: commit.
            c.rule3_min_utterance_length = config.maxUtteranceSeconds
            c.decoding_method = "greedy_search".cstr.ptr   // append-only results (VOICE-024)
            SherpaOnnxCreateOnlineRecognizer(c.ptr)
        } ?: error("the voice model could not be loaded from $modelDir")
        return SherpaEngine(recognizer)
    }
}

private inline fun <reified T : kotlinx.cinterop.CVariable> sizeOf(): Long = kotlinx.cinterop.sizeOf<T>()

private class SherpaEngine(private var recognizer: CPointer<SherpaOnnxOnlineRecognizer>?) : SpeechEngine {
    override fun createStream(): SpeechStream {
        val r = checkNotNull(recognizer) { "engine released" }
        return SherpaStream(r, SherpaOnnxCreateOnlineStream(r) ?: error("no stream"))
    }

    override fun release() {
        recognizer?.let { SherpaOnnxDestroyOnlineRecognizer(it) }
        recognizer = null
    }
}

private class SherpaStream(
    private val recognizer: CPointer<SherpaOnnxOnlineRecognizer>,
    private var stream: CPointer<SherpaOnnxOnlineStream>?,
) : SpeechStream {
    private val s get() = checkNotNull(stream) { "stream released" }

    override fun accept(samples: FloatArray) {
        if (samples.isEmpty()) return
        samples.usePinned { SherpaOnnxOnlineStreamAcceptWaveform(s, SpeechModel.SAMPLE_RATE, it.addressOf(0), samples.size) }
        decodeReady()
    }

    override fun text(): String {
        val result = SherpaOnnxGetOnlineStreamResult(recognizer, s) ?: return ""
        return try {
            result.pointed.text?.toKString().orEmpty()
        } finally {
            SherpaOnnxDestroyOnlineRecognizerResult(result)
        }
    }

    override fun isEndpoint(): Boolean = SherpaOnnxOnlineStreamIsEndpoint(recognizer, s) != 0

    override fun reset() = SherpaOnnxOnlineStreamReset(recognizer, s)

    override fun finish() {
        // Half a second of silence flushes the last chunk through the streaming encoder.
        val tail = FloatArray(SpeechModel.SAMPLE_RATE / 2)
        tail.usePinned { SherpaOnnxOnlineStreamAcceptWaveform(s, SpeechModel.SAMPLE_RATE, it.addressOf(0), tail.size) }
        SherpaOnnxOnlineStreamInputFinished(s)
        decodeReady()
    }

    override fun release() {
        stream?.let { SherpaOnnxDestroyOnlineStream(it) }
        stream = null
    }

    private fun decodeReady() {
        while (SherpaOnnxIsOnlineStreamReady(recognizer, s) != 0) SherpaOnnxDecodeOnlineStream(recognizer, s)
    }
}
