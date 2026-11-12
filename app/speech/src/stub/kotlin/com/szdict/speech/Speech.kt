package com.szdict.speech

// Built without the sherpa-onnx xcframeworks (see tools/fetch-voice-model.sh): no engine.

internal actual val sherpaLinked: Boolean = false

internal actual fun sherpaVersion(): String = ""

internal actual fun openSherpa(modelDir: String, config: SpeechEngineConfig): SpeechEngine =
    throw UnsupportedOperationException("built without sherpa-onnx")
