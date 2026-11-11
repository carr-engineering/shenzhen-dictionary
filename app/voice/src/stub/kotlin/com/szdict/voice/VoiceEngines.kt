package com.szdict.voice

import android.content.res.AssetManager

/** Built without the sherpa-onnx library (see tools/fetch-voice-model.sh): no engine. */
object VoiceEngines {
    const val AVAILABLE = false

    fun open(assets: AssetManager, config: VoiceEngineConfig = VoiceEngineConfig()): VoiceEngine =
        throw UnsupportedOperationException("built without sherpa-onnx")
}
