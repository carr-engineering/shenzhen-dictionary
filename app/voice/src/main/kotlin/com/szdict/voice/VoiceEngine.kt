package com.szdict.voice

import android.content.res.AssetManager

/** The bundled voice model: streaming Paraformer trilingual (zh / yue / en), int8 (VOICE-020). */
object VoiceModel {
    const val ASSET_DIR = "voice-model"
    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val TOKENS = "tokens.txt"
    const val SAMPLE_RATE = 16_000

    /** True when every model file is packaged in the APK (ARCH-142, direct flavour). */
    fun isBundled(assets: AssetManager): Boolean = try {
        val present = assets.list(ASSET_DIR)?.toSet().orEmpty()
        ENCODER in present && DECODER in present && TOKENS in present
    } catch (e: Exception) {
        false
    }
}

/** Endpointing and decoding constants (VOICE-024, VOICE-025). */
class VoiceEngineConfig(
    /** Trailing silence after speech that commits the utterance. */
    val commitSilenceSeconds: Float = 1.2f,
    /** An utterance is committed when it reaches this length. */
    val maxUtteranceSeconds: Float = 20f,
    /** Silence with nothing decoded that resets the stream without committing. */
    val resetSilenceSeconds: Float = 2.4f,
    val threads: Int = if (Runtime.getRuntime().availableProcessors() >= 8) 4 else 2,
)

/** A loaded recogniser. Not thread-safe: use it, and its streams, from one thread. */
interface VoiceEngine {
    fun createStream(): VoiceStream
    fun release()
}

/** One streaming decode (greedy search, so [text] is append-only within an utterance). */
interface VoiceStream {
    /** Feeds 16 kHz mono samples in −1…1 and decodes everything that is ready. */
    fun accept(samples: FloatArray)

    /** The current utterance so far. */
    fun text(): String

    fun isEndpoint(): Boolean

    /** Starts the next utterance. */
    fun reset()

    /** No more audio: decodes what is left, so [text] is final. */
    fun finish()

    fun release()
}
