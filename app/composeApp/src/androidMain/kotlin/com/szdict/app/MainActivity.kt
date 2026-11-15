package com.szdict.app

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.os.LocaleListCompat
import com.szdict.app.integration.AndroidIntegrations
import com.szdict.app.platform.AndroidAudioPlayer
import com.szdict.app.platform.AndroidClipboard
import com.szdict.app.platform.AndroidLinkOpener
import com.szdict.app.platform.AndroidSpeaker
import com.szdict.app.platform.AndroidTranslator
import com.szdict.app.platform.DataStoreSettingsStorage
import com.szdict.app.platform.AndroidAppFonts
import com.szdict.app.platform.DatasetInstaller
import com.szdict.app.ui.theme.DarkDictionaryPalette
import com.szdict.app.ui.theme.LightDictionaryPalette
import com.szdict.app.ui.theme.LocalPlatformFontFamily
import com.szdict.core.api.DictionaryFactory
import com.szdict.core.api.ThemeMode
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
        // Shortcuts, share targets, App Links and the widget link (SET-050…064), each consumed once.
        AndroidIntegrations.onCreate(this, session, fresh = savedInstanceState == null)
        setContent {
            SystemBarsFollowTheme()
            // GLB-008: the interface font with the rare characters the system CJK font lacks.
            CompositionLocalProvider(LocalPlatformFontFamily provides AndroidAppFonts.family(this)) { App(session) }
        }
    }

    /**
     * GLB-070: the system bars stay transparent and their icon contrast follows the theme the app
     * actually shows (the Theme setting may differ from the system's). The window background
     * follows too, because the system tints the three-button navigation scrim with it.
     */
    @Composable
    private fun SystemBarsFollowTheme() {
        val settings by session.settings.collectAsState()
        val dark = when (settings.themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }
        LaunchedEffect(dark) {
            val transparent = android.graphics.Color.TRANSPARENT
            enableEdgeToEdge(
                statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                navigationBarStyle = SystemBarStyle.auto(transparent, transparent) { dark },
            )
            val background = if (dark) DarkDictionaryPalette.bg else LightDictionaryPalette.bg
            window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AndroidIntegrations.onNewIntent(this, session, intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            AppGraph.clipboard?.refresh()   // TXT-053: clipboard only while focused
            AndroidIntegrations.onWindowFocus(session)   // SET-051: the clipboard shortcut reads now
        }
    }

    override fun onStop() {
        session.flushNow()
        session.stopPlayback()
        super.onStop()
    }
}
