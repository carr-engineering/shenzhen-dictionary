package com.szdict.speech

import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo

/** The speech engine of this build: sherpa-onnx streaming Paraformer through the C API (ARCH-095). */
object Speech {
    /** True when the sherpa-onnx library is linked into this build. */
    val AVAILABLE: Boolean get() = sherpaLinked

    /** The linked sherpa-onnx version; empty without the library. */
    val VERSION: String get() = sherpaVersion()

    /**
     * Loads the model in [modelDir], an absolute path (slow: call off the UI thread). Throws when
     * the library is not linked, or the model is missing or corrupt.
     */
    fun open(modelDir: String, config: SpeechEngineConfig = SpeechEngineConfig()): SpeechEngine {
        check(SpeechModel.isPresent(modelDir)) { "voice model is not in $modelDir" }
        return openSherpa(modelDir, config)
    }
}

internal expect val sherpaLinked: Boolean
internal expect fun sherpaVersion(): String
internal expect fun openSherpa(modelDir: String, config: SpeechEngineConfig): SpeechEngine

/** The voice model: streaming Paraformer trilingual (zh / yue / en), int8 (VOICE-020). */
object SpeechModel {
    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val TOKENS = "tokens.txt"
    const val SAMPLE_RATE = 16_000

    /** True when every model file is in [dir] (an absolute path). */
    fun isPresent(dir: String): Boolean {
        val files = NSFileManager.defaultManager
        return listOf(ENCODER, DECODER, TOKENS).all { files.fileExistsAtPath("$dir/$it") }
    }
}

/** Endpointing and decoding constants (VOICE-024, VOICE-025); the same values as on Android. */
class SpeechEngineConfig(
    /** Trailing silence after speech that commits the utterance. */
    val commitSilenceSeconds: Float = 1.2f,
    /** An utterance is committed when it reaches this length. */
    val maxUtteranceSeconds: Float = 20f,
    /** Silence with nothing decoded that resets the stream without committing. */
    val resetSilenceSeconds: Float = 2.4f,
    val threads: Int = if (NSProcessInfo.processInfo.activeProcessorCount.toInt() >= 8) 4 else 2,
)

/** A loaded recogniser. Not thread-safe: use it, and its streams, from one thread. */
interface SpeechEngine {
    fun createStream(): SpeechStream
    fun release()
}

/** One streaming decode (greedy search, so [text] is append-only within an utterance). */
interface SpeechStream {
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
