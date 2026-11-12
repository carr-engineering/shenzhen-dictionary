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
import org.w3c.dom.MessageEvent
import org.w3c.dom.Worker
import org.w3c.dom.events.Event
import kotlin.js.ExperimentalWasmJsInterop

private fun jsCreateWorker(): Worker = js("new Worker('sqlite-worker.js', { type: 'module' })")

private external interface WorkerEvent : JsAny {
    val evt: String?
    val file: String?
    val loaded: Double
    val total: Double
    val done: Boolean
    val error: String?
    val persistent: Boolean
}

private fun eventOf(message: JsAny?): WorkerEvent? = js("(message && message.data && message.data.evt) ? message.data : null")

/** Progress of one file the store worker is fetching. */
class Download(val file: String, val loaded: Long, val total: Long)

/**
 * The datasets on Web (ARCH-004, ARCH-145): the store worker downloads a mode's dataset (and its
 * language-model companion) the first time that mode is opened and keeps it in OPFS; this class
 * starts the worker, opens dictionaries over it and exposes the download progress to the shell.
 */
class WebDatasets(private val audio: WebAudioPlayer) {
    /** Files being fetched now, by URL. */
    val downloads = mutableStateMapOf<String, Download>()

    /** The last dataset download failed (offline first visit, missing file). Cleared by the next attempt. */
    var failed by mutableStateOf(false)
        private set

    /** False when the dataset could not be kept in OPFS and lives in memory for this page only. */
    var persistent by mutableStateOf(true)
        private set

    private var catalogLoaded = false

    init {
        val worker = jsCreateWorker()
        WebStore.attach(worker)
        // The driver owns `onmessage`; progress events arrive on a second listener.
        worker.addEventListener("message", { event: Event ->
            val data = eventOf((event as MessageEvent).data)
            if (data != null) onEvent(data)
        })
    }

    private fun onEvent(event: WorkerEvent) {
        when (event.evt) {
            "storage" -> persistent = event.persistent
            "download" -> {
                val file = event.file ?: return
                if (event.done) {
                    downloads.remove(file)
                    if (event.error != null && isDataset(file)) failed = true
                } else {
                    if (isDataset(file)) failed = false
                    downloads[file] = Download(file, event.loaded.toLong(), event.total.toLong())
                }
            }
        }
    }

    /** Progress over the dataset files (not the lazily fetched packs), or null when none is downloading. */
    val datasetProgress: Download?
        get() {
            val active = downloads.values.filter { isDataset(it.file) }
            if (active.isEmpty()) return null
            return Download(active.first().file, active.sumOf { it.loaded }, active.sumOf { it.total })
        }

    suspend fun open(mode: DictMode): Dictionary {
        if (!catalogLoaded) {
            // The audio catalogue decides between a recording and TTS; without it everything is spoken.
            fetchText("$DATA_DIR/packs/audio/catalog.json")?.let { text ->
                WebStore.putText("$DATA_DIR/packs/audio/catalog.json", text)
                audio.setCatalog(text)
                catalogLoaded = true
            }
        }
        return DictionaryFactory.open(DATA_DIR, mode)
    }

    private fun isDataset(file: String) = !file.contains("/packs/")

    companion object {
        const val DATA_DIR = "data"
    }
}
