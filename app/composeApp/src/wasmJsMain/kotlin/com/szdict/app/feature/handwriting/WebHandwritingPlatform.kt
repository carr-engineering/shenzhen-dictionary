package com.szdict.app.feature.handwriting

import com.szdict.app.feature.ocr.ModelDownload
import com.szdict.app.feature.ocr.WebModelStatus
import com.szdict.recognition.HandwritingRecognizer
import com.szdict.recognition.WebRecognizers
import kotlinx.coroutines.CoroutineScope

/**
 * Web side of handwriting (ARCH-093): the same Digital Ink network as on Android, run in a worker;
 * its files are fetched when the sheet first opens and kept offline afterwards.
 */
class WebHandwritingPlatform(scope: CoroutineScope) : HandwritingPlatform {
    private val status = WebModelStatus(WebRecognizers.handwritingModel, scope)

    override fun createRecognizer(): HandwritingRecognizer = WebRecognizers.handwriting()

    override val modelDownload: ModelDownload? get() = status.download

    override fun prepare() = WebRecognizers.handwritingModel.prepare()
}
