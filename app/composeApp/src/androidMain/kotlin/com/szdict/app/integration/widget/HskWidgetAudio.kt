package com.szdict.app.integration.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.szdict.app.feature.hsk.WidgetReading
import com.szdict.app.platform.AndroidAudioPlayer
import com.szdict.app.platform.AndroidSpeaker
import com.szdict.core.api.DictMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal val WidgetAudioModeKey = ActionParameters.Key<String>("mode")
internal val WidgetAudioReadingKey = ActionParameters.Key<String>("reading")
internal val WidgetAudioTextKey = ActionParameters.Key<String>("text")

/**
 * The widget's speaker button (SET-065): plays the installed recording addressed by the reading
 * the projection row carries — the dictionary database is never opened. Words without a recording
 * fall back to the device's text-to-speech (MED-011).
 */
class PlayWidgetAudioAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val mode = DictMode.entries.firstOrNull { it.name == parameters[WidgetAudioModeKey] } ?: return
        WidgetAudio.play(context.applicationContext, mode, parameters[WidgetAudioReadingKey].orEmpty(), parameters[WidgetAudioTextKey].orEmpty())
    }
}

internal object WidgetAudio {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val extensions = listOf("opus", "m4a", "ogg", "mp3")
    private var player: AndroidAudioPlayer? = null
    private var speaker: AndroidSpeaker? = null
    private var job: Job? = null

    /** Clip paths (relative to `packs/audio/`) of the first candidate whose clips are all installed. */
    fun resolve(mode: DictMode, reading: String, has: (String) -> Boolean): List<String>? {
        for (candidate in WidgetReading.clipCandidates(mode, reading)) {
            val paths = candidate.map { stem -> extensions.map { "$stem.$it" }.firstOrNull(has) }
            if (paths.isNotEmpty() && paths.none { it == null }) return paths.filterNotNull()
        }
        return null
    }

    fun play(context: Context, mode: DictMode, reading: String, text: String) {
        val audio = player ?: AndroidAudioPlayer(context).also { player = it }
        val tts = speaker ?: AndroidSpeaker(context).also { speaker = it }
        job?.cancel()
        audio.stop()
        tts.stop()
        job = scope.launch {
            val clips = resolve(mode, reading, audio::has)
            val played = clips != null && audio.play(clips)
            if (!played && text.isNotBlank()) tts.speak(text, if (mode == DictMode.CANTONESE) "zh-HK" else "zh-CN")
        }
    }
}
