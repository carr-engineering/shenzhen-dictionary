@file:OptIn(ExperimentalWasmJsInterop::class)

package com.szdict.app.feature.ocr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.szdict.recognition.TextRecognizer
import com.szdict.recognition.WebModel
import com.szdict.recognition.WebModelState
import com.szdict.recognition.WebRecognizers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.js.ExperimentalWasmJsInterop

private fun jsOnPaste(handler: (JsAny?, Boolean) -> Unit): Unit = js(
    """{
        document.addEventListener('paste', (event) => {
            const target = event.target;
            // A paste into a text field is that field's business.
            const editable = !!target && (target.isContentEditable || target.tagName === 'INPUT' || target.tagName === 'TEXTAREA');
            const files = event.clipboardData ? Array.from(event.clipboardData.files || []) : [];
            const image = files.find((file) => file.type.startsWith('image/')) || null;
            handler(image, editable);
        });
    }"""
)

private fun jsSaveBlob(blob: JsAny, name: String): Boolean = js(
    """{
        try {
            const url = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            link.download = name;
            link.style.display = 'none';
            document.body.appendChild(link);
            link.click();
            link.remove();
            setTimeout(() => URL.revokeObjectURL(url), 60000);
            return true;
        } catch (e) {
            return false;
        }
    }"""
)

private fun jsTimestamp(): String = js(
    "(() => { const d = new Date(); const p = (n) => String(n).padStart(2, '0'); return d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '_' + p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds()); })()"
)

/** A browser model's state as the shared download UI shows it (CAM-134, ARCH-093). */
internal class WebModelStatus(model: WebModel, scope: CoroutineScope) {
    var download: ModelDownload? by mutableStateOf(null)
        private set
    private var knownSize: Long? = null

    init {
        scope.launch {
            model.state.collect { state ->
                download = when (state) {
                    is WebModelState.Downloading -> {
                        knownSize = state.totalBytes
                        ModelDownload.InProgress(if (state.totalBytes > 0) state.loadedBytes.toFloat() / state.totalBytes else 0f, state.totalBytes)
                    }
                    WebModelState.Loading -> ModelDownload.InProgress(null, knownSize ?: 0)
                    is WebModelState.Failed -> ModelDownload.Failed(knownSize)
                    WebModelState.Idle, WebModelState.Ready -> null
                }
            }
        }
    }
}

/**
 * Web side of the OCR ports (CAM-110, ARCH-197): the recogniser in its worker with its one-time
 * model download, image decoding through the browser's decoders, saving as a file download, and
 * images taken from the document's `paste` event.
 */
class WebOcrPlatform(scope: CoroutineScope) : OcrPlatform {
    private val status = WebModelStatus(WebRecognizers.textModel, scope)
    private var pasteListener: ((String?) -> Unit)? = null

    init {
        jsOnPaste { image, editable ->
            val listener = pasteListener
            // CAM-132: the image comes with the event; nothing is read from the clipboard otherwise.
            if (listener != null && !(editable && image == null)) listener(image?.let(WebImageRefs::register))
        }
    }

    override fun createRecognizer(): TextRecognizer = WebRecognizers.text(alternatives = 8)

    override val modelDownload: ModelDownload? get() = status.download

    override fun prepareRecognizer() = WebRecognizers.textModel.prepare()

    override suspend fun awaitRecognizer() = WebRecognizers.textModel.awaitReady()

    override fun retryModelDownload() = WebRecognizers.textModel.retry()

    override suspend fun decode(ref: ImageRef): WorkingImage {
        val blob = when (ref) {
            is ImageRef.Encoded -> blobOf(ref.bytes, "image/jpeg")
            is ImageRef.Uri -> WebImageRefs.take(ref.uri) ?: error("Cannot open image")
        }
        return decodeWorkingImage(blob)
    }

    /** CAM-075 on Web: the capture is handed to the browser as a download. */
    override suspend fun savePhoto(jpeg: ByteArray): Boolean = jsSaveBlob(blobOf(jpeg, "image/jpeg"), "SZDict_${jsTimestamp()}.jpg")

    override val savedMessage: String get() = "Saved to Downloads"

    // The clipboard is never read: pasted images arrive with the `paste` event (CAM-132).
    override fun clipboardImage(): String? = null

    override val pasteByEvent: Boolean get() = true

    override fun setPasteListener(listener: ((String?) -> Unit)?) {
        pasteListener = listener
    }
}
