package com.szdict.core.web

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import org.w3c.dom.Worker

/**
 * Web wiring of the store (ARCH-026): the app layer starts the SQLite worker and hands it over
 * here before the first dictionary is opened. The worker owns the dataset files (OPFS) and answers
 * the `androidx.sqlite` web-driver protocol; file names are the dataset-relative paths the store
 * uses on every platform (`<dir>/mandarin.db`, `<dir>/packs/sentences.db`, …).
 */
object WebStore {
    private var driver: WebWorkerSQLiteDriver? = null
    private val texts = HashMap<String, String>()

    fun attach(worker: Worker) {
        driver = WebWorkerSQLiteDriver(worker)
    }

    /** Small text files the engine reads synchronously (the audio catalogue), fetched by the app layer. */
    fun putText(path: String, text: String) {
        texts[path] = text
    }

    internal fun text(path: String): String? = texts[path]

    internal suspend fun open(path: String): SQLiteConnection =
        (driver ?: error("WebStore.attach was not called")).open(path)
}
