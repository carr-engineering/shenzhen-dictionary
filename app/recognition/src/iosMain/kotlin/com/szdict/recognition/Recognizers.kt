package com.szdict.recognition

/**
 * Entry points for the bundled recognisers on iOS. [modelDir] is the absolute path of the directory
 * holding `ocr/` and `ink/` (the same files as the Android `assets/models/`).
 */
object Recognizers {
    fun text(modelDir: String, alternatives: Int = 8): TextRecognizer = TODO("iOS text recognition")

    fun handwriting(modelDir: String): HandwritingRecognizer = TODO("iOS handwriting recognition")
}
