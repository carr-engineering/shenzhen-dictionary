@file:OptIn(ExperimentalWasmJsInterop::class)

package com.szdict.app.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.szdict.core.api.DictMode
import com.szdict.core.api.Dictionary
import com.szdict.core.api.DictionaryFactory
import com.szdict.core.web.WebStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.MessageEvent
import org.w3c.dom.Worker
import org.w3c.dom.events.Event
import kotlin.js.ExperimentalWasmJsInterop

// index.html starts the worker beside the download of the app's code; a page without it starts one here.
private fun jsCreateWorker(): Worker = js("(window.szdictStore && window.szdictStore.worker) || new Worker('sqlite-worker.js', { type: 'module' })")

/** The events the worker posted before the app was there to hear them, oldest first; taking them stops the keeping. */
private fun jsEarlyEvents(): JsArray<JsAny> = js(
    "(function () { var s = window.szdictStore; if (!s) return []; var e = s.events.splice(0); s.events.push = function () { return 0; }; return e; })()"
)

private external interface WorkerEvent : JsAny {
    val evt: String?
    val file: String?
    val loaded: Double
    val total: Double
    val done: Boolean
    val error: String?
    val persistent: Boolean
    val background: Boolean
}

private fun jsOnDatasetBroadcast(onUpdated: () -> Unit): Unit = js(
    """{
        try {
            var channel = new BroadcastChannel('szdict-datasets');
            channel.onmessage = function (event) { if (event.data && event.data.type === 'updated') onUpdated(); };
        } catch (e) {}
    }"""
)

private fun eventOf(message: JsAny?): WorkerEvent? = js("(message && message.data && message.data.evt) ? message.data : null")

/** Progress of one file the store worker is fetching. */
class Download(val file: String, val loaded: Long, val total: Long)

/**
 * The datasets on Web (ARCH-004, ARCH-145): the store worker downloads a mode's dataset the first
 * time that mode is opened, verifies it and keeps it in OPFS; the language model follows when a
 * query first needs it and never holds up a search (ARCH-143). This class starts the worker, opens
 * dictionaries over it and exposes the first-visit progress and an installed update to the shell.
 */
class WebDatasets(
    private val audio: WebAudioPlayer,
    private val scope: CoroutineScope,
    private val initialMode: DictMode?,
) {
    /** Files being fetched now, by URL. */
    val downloads = mutableStateMapOf<String, Download>()

    /** Keep the startup screen visible until the first dictionary has opened. */
    var initialOpenPending by mutableStateOf(true)
        private set

    /** The last dataset download failed (offline first visit, missing file). Cleared by the next attempt. */
    private val failures = mutableStateMapOf<String, Boolean>()

    val failed: Boolean get() = failures.isNotEmpty()

    fun failed(mode: DictMode): Boolean = datasetFile(mode) in failures

    /** False when the dataset could not be kept in OPFS and lives in memory for this page only. */
    var persistent by mutableStateOf(true)
        private set

    /**
     * A newer dataset was downloaded and verified in the background — by this tab's worker or
     * another tab's (ARCH-145). The open one stays in use; the next load of the page opens the new one.
     */
    var updateInstalled by mutableStateOf(false)
        private set

    fun dismissUpdate() { updateInstalled = false }

    private var catalogRequested = false

    init {
        jsOnDatasetBroadcast { updateInstalled = true }
        val worker = jsCreateWorker()
        WebStore.attach(worker)
        // The driver owns `onmessage`; progress events arrive on a second listener.
        worker.addEventListener("message", { event: Event ->
            val data = eventOf((event as MessageEvent).data)
            if (data != null) onEvent(data)
        })
        val early = jsEarlyEvents()
        for (i in 0 until early.length) early[i]?.let { message -> eventOf(message)?.let(::onEvent) }
    }

    private fun onEvent(event: WorkerEvent) {
        when (event.evt) {
            "storage" -> persistent = event.persistent
            "updated" -> updateInstalled = true
            "download" -> {
                val file = event.file ?: return
                if (event.background) return   // an update beside the dataset in use: nothing to wait for
                if (event.done) {
                    downloads.remove(file)
                    if (event.error != null && isDataset(file)) failures[file] = true
                } else {
                    failures.remove(file)
                    downloads[file] = Download(file, event.loaded.toLong(), event.total.toLong())
                }
            }
        }
    }

    /** Progress over the dataset files (not the lazily fetched packs), or null when none is downloading. */
    val datasetProgress: Download? get() = downloads.values.firstOrNull { isDataset(it.file) }

    fun progress(mode: DictMode): Download? = downloads[datasetFile(mode)]

    suspend fun open(mode: DictMode): Dictionary {
        if (!catalogRequested) {
            // The audio catalogue decides between a recording and TTS. It is fetched beside the
            // dataset, never ahead of it: until it is here everything is spoken (MED-011).
            catalogRequested = true
            scope.launch {
                val text = fetchText("$DATA_DIR/packs/audio/catalog.json")
                if (text != null) {
                    WebStore.putText("$DATA_DIR/packs/audio/catalog.json", text)
                    audio.setCatalog(text)
                } else catalogRequested = false   // asked again when the next dictionary opens
            }
        }
        // Its answers name the characters about to be shown: the rare-character font follows them (WebFont.kt).
        return try {
            CharacterNotingDictionary(DictionaryFactory.open(DATA_DIR, mode)).also {
                failures.remove(datasetFile(mode))
                if (initialMode == null || initialMode == mode) initialOpenPending = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            downloads.remove(datasetFile(mode))
            failures[datasetFile(mode)] = true
            throw e
        }
    }

    /** The files of the first-visit progress: a mode's main dataset, not its packs or language model. */
    private fun datasetFile(mode: DictMode) = "$DATA_DIR/${mode.datasetName}.db"

    private fun isDataset(file: String) = !file.contains("/packs/") && !file.endsWith("-lm.db")

    companion object {
        const val DATA_DIR = "data"
    }
}
