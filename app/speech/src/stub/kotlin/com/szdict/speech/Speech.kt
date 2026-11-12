package com.szdict.speech

/** Built without the sherpa-onnx xcframeworks (see tools/fetch-voice-model.sh): no engine. */
object Speech {
    /** True when the sherpa-onnx library is linked into this build. */
    const val AVAILABLE: Boolean = false

    const val VERSION: String = ""

    fun open(modelDir: String, config: SpeechEngineConfig = SpeechEngineConfig()): SpeechEngine =
        throw UnsupportedOperationException("built without sherpa-onnx")
}
