package com.szdict.app.feature.reading

import com.szdict.app.AppSession
import com.szdict.app.Destination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Entry point for platform code delivering text to the Reading destination: shared text
 * (`ACTION_SEND` / `ACTION_PROCESS_TEXT`, TXT-080) and the `Read clipboard` shortcut (TXT-057).
 *
 * A delivery is held until the Reading state holder takes it and is consumed exactly once, so
 * activity recreation or a later activation never re-applies it. The caller clears its intent
 * action itself (SET-055). The text is never logged (GLB-090).
 */
object ReadingInbox {
    sealed interface Delivery {
        /** Replaces the draft with [text] and analyses it; the field is not focused. */
        class SharedText(val text: String) : Delivery

        /** Pastes the clipboard as the `Paste` button would, reporting an empty clipboard. */
        data object ReadClipboard : Delivery
    }

    private val _pending = MutableStateFlow<Delivery?>(null)
    val pending: StateFlow<Delivery?> = _pending.asStateFlow()

    /**
     * Shared text. Pass [session] to also select the Reading destination. Text that is blank after
     * trimming only opens Reading and leaves the draft unchanged.
     */
    fun deliver(text: String, session: AppSession? = null) {
        session?.open()
        if (text.isNotBlank()) _pending.value = Delivery.SharedText(text)
    }

    /** The `Read clipboard` launcher shortcut. Pass [session] to also select the Reading destination. */
    fun readClipboard(session: AppSession? = null) {
        session?.open()
        _pending.value = Delivery.ReadClipboard
    }

    /** Takes the pending delivery, if any; later calls return null until the next delivery. */
    fun take(): Delivery? {
        while (true) {
            val current = _pending.value ?: return null
            if (_pending.compareAndSet(current, null)) return current
        }
    }

    private fun AppSession.open() {
        clearRoutes()
        if (destination != Destination.READING) selectDestination(Destination.READING)
    }
}
