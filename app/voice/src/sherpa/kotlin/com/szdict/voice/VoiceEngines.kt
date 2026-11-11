package com.szdict.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream

/** sherpa-onnx streaming Paraformer, loaded straight from the APK's assets (ARCH-095). */
object VoiceEngines {
    const val AVAILABLE = true

    /** Loads the model (slow: call off the UI thread). Throws when the model is missing or corrupt. */
    fun open(assets: AssetManager, config: VoiceEngineConfig = VoiceEngineConfig()): VoiceEngine {
        check(VoiceModel.isBundled(assets)) { "voice model is not bundled" }
        val recognizerConfig = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = VoiceModel.SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                paraformer = OnlineParaformerModelConfig(
                    encoder = "${VoiceModel.ASSET_DIR}/${VoiceModel.ENCODER}",
                    decoder = "${VoiceModel.ASSET_DIR}/${VoiceModel.DECODER}",
                ),
                tokens = "${VoiceModel.ASSET_DIR}/${VoiceModel.TOKENS}",
                numThreads = config.threads,
                debug = false,
                provider = "cpu",
                modelType = "paraformer",
            ),
            endpointConfig = EndpointConfig(
                // Silence with nothing decoded: reset without committing.
                rule1 = EndpointRule(false, config.resetSilenceSeconds, 0f),
                // Trailing silence after speech: commit.
                rule2 = EndpointRule(true, config.commitSilenceSeconds, 0f),
                // Maximum utterance length: commit.
                rule3 = EndpointRule(false, 0f, config.maxUtteranceSeconds),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",   // append-only results (VOICE-024)
        )
        return SherpaEngine(OnlineRecognizer(assets, recognizerConfig))
    }
}

private class SherpaEngine(private val recognizer: OnlineRecognizer) : VoiceEngine {
    override fun createStream(): VoiceStream = SherpaStream(recognizer, recognizer.createStream(""))
    override fun release() = recognizer.release()
}

private class SherpaStream(private val recognizer: OnlineRecognizer, private val stream: OnlineStream) : VoiceStream {
    override fun accept(samples: FloatArray) {
        if (samples.isEmpty()) return
        stream.acceptWaveform(samples, VoiceModel.SAMPLE_RATE)
        decodeReady()
    }

    override fun text(): String = recognizer.getResult(stream).text

    override fun isEndpoint(): Boolean = recognizer.isEndpoint(stream)

    override fun reset() = recognizer.reset(stream)

    override fun finish() {
        // Half a second of silence flushes the last chunk through the streaming encoder.
        stream.acceptWaveform(FloatArray(VoiceModel.SAMPLE_RATE / 2), VoiceModel.SAMPLE_RATE)
        stream.inputFinished()
        decodeReady()
    }

    override fun release() = stream.release()

    private fun decodeReady() {
        while (recognizer.isReady(stream)) recognizer.decode(stream)
    }
}
