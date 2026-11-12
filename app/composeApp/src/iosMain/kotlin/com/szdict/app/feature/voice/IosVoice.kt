package com.szdict.app.feature.voice

/** The iOS voice services (registered under [VoiceServices.KEY]); null while Voice is unavailable. */
object IosVoice {
    /** [modelDir]: absolute path of the directory holding the voice model files, or null when not bundled. */
    fun services(modelDir: String?): VoiceServices? = null
}
