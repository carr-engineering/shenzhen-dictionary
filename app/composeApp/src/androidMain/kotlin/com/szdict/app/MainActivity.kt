package com.szdict.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.os.LocaleListCompat
import com.szdict.app.platform.AndroidAudioPlayer
import com.szdict.app.platform.AndroidClipboard
import com.szdict.app.platform.AndroidLinkOpener
import com.szdict.app.platform.AndroidSpeaker
import com.szdict.app.platform.AndroidTranslator
import com.szdict.app.platform.DataStoreSettingsStorage
import com.szdict.app.platform.DatasetInstaller
import com.szdict.core.api.DictionaryFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide holder so the session survives configuration changes (GLB-050). */
object AppGraph {
    @Volatile private var instance: AppSession? = null
    var clipboard: AndroidClipboard? = null
        private set

    fun session(activity: ComponentActivity): AppSession = instance ?: synchronized(this) {
        instance ?: build(activity).also { instance = it }
    }

    private fun build(activity: ComponentActivity): AppSession {
        val context = activity.applicationContext
        val clip = AndroidClipboard(context).also { clipboard = it }
        val env = AppEnv(
            openDictionary = { mode ->
                val dir = DatasetInstaller.ensureInstalled(context)
                DictionaryFactory.open(dir.absolutePath, mode)
            },
            settingsStorage = DataStoreSettingsStorage(context),
            audio = AndroidAudioPlayer(context),
            speaker = AndroidSpeaker(context),
            clipboard = clip,
            translator = AndroidTranslator(context),
            links = AndroidLinkOpener(context),
            services = PlatformServices.create(context),
            voiceAvailable = true,
            systemLocaleTag = { LocaleListCompat.getAdjustedDefault()[0]?.toLanguageTag() ?: "en" },
            appVersion = BuildConfig.VERSION_NAME,
        )
        return AppSession(env, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var session: AppSession

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        session = AppGraph.session(this)
        setContent { App(session) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) AppGraph.clipboard?.refresh()   // TXT-053: clipboard only while focused
    }

    override fun onStop() {
        session.flushNow()
        session.stopPlayback()
        super.onStop()
    }
}
