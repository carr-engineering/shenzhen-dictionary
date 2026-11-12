package com.szdict.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.szdict.app.feature.handwriting.HandwritingPlatform
import com.szdict.app.feature.ocr.IosOcrPlatform
import com.szdict.app.feature.ocr.OcrPlatform
import com.szdict.app.feature.voice.IosVoice
import com.szdict.app.feature.voice.VoiceServices
import com.szdict.app.integration.IosAppLanguage
import com.szdict.app.integration.IosIntegrations
import com.szdict.app.platform.IosAudioPlayer
import com.szdict.app.platform.IosBundle
import com.szdict.app.platform.IosClipboard
import com.szdict.app.platform.IosHost
import com.szdict.app.platform.IosLinkOpener
import com.szdict.app.platform.IosSpeaker
import com.szdict.app.platform.IosTranslator
import com.szdict.app.platform.UserDefaultsSettings
import com.szdict.app.ui.theme.DarkDictionaryPalette
import com.szdict.app.ui.theme.LightDictionaryPalette
import com.szdict.core.api.DictionaryFactory
import com.szdict.core.api.ThemeMode
import com.szdict.recognition.Recognizers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.Foundation.NSLocale
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.preferredLanguages
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIColor
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/** Process-wide holder of the session: one per process, whatever happens to the scene (GLB-050). */
internal object IosAppGraph {
    val clipboard = IosClipboard()
    private val links = IosLinkOpener()
    val translator = IosTranslator(links)

    val session: AppSession by lazy {
        val voice = IosVoice.services(IosBundle.voiceModelDir())
        val env = AppEnv(
            // The datasets are opened read-only in place, inside the app bundle (ARCH-141).
            openDictionary = { mode -> DictionaryFactory.open(IosBundle.path("data"), mode) },
            settingsStorage = UserDefaultsSettings(),
            audio = IosAudioPlayer(),
            speaker = IosSpeaker(),
            clipboard = clipboard,
            translator = translator,
            links = links,
            services = buildMap {
                put(OcrPlatform.KEY, IosOcrPlatform())
                put(HandwritingPlatform.KEY, HandwritingPlatform { Recognizers.handwriting(IosBundle.path("models")) })
                if (voice != null) put(VoiceServices.KEY, voice)
            },
            voiceAvailable = voice != null,
            systemLocaleTag = { NSLocale.preferredLanguages.firstOrNull() as? String ?: "en" },
            appVersion = IosBuild.VERSION_NAME,
        )
        AppSession(env, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)).also(::install)
    }

    private fun install(session: AppSession) {
        IosAppLanguage.attach(session)
        val center = NSNotificationCenter.defaultCenter
        // Entering the background is iOS's "stop": settle the settings and silence playback (ARCH-112).
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) {
            session.flushNow()
            session.stopPlayback()
        }
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
            clipboard.refresh()   // TXT-053: clipboard availability only while active
            IosIntegrations.onActive()   // SET-051: the clipboard quick action reads now
        }
    }
}

/** The app's root view controller; hosted by the Swift entry point (iosApp/SZDict/SZDictApp.swift). */
fun MainViewController(): UIViewController {
    val session = IosAppGraph.session
    return ComposeUIViewController(
        configure = {
            // The shell pads for the keyboard itself through the IME insets, as on Android.
            onFocusBehavior = OnFocusBehavior.DoNothing
        },
    ) {
        SystemChromeFollowsTheme(session)
        App(session)
    }
}

/** A Universal Link or `szdict:` URL delivered to the app (GLB-111). Returns false when it is not one of the app's routes. */
fun handleIncomingUrl(url: String): Boolean = IosIntegrations.openUrl(IosAppGraph.session, url)

/** A Home Screen quick action, by its `UIApplicationShortcutItemType` (SET-050). */
fun handleShortcut(type: String): Boolean = IosIntegrations.performShortcut(IosAppGraph.session, type)

/** Installed by the Swift host: presents Apple's translation sheet (MED-032); see [IosHost.translate]. */
fun setTranslationPresenter(presenter: (String, String) -> Unit) {
    IosHost.translate = presenter
}

/** The web hand-off of MED-030, for the Swift host when the sheet does not support the language pair. */
fun openWebTranslation(text: String, sourceLanguageTag: String) = IosAppGraph.translator.openWeb(text, sourceLanguageTag)

/**
 * GLB-070: status-bar contrast, system sheets and the window background follow the theme the app
 * actually shows (the Theme setting may differ from the system's).
 */
@Composable
private fun SystemChromeFollowsTheme(session: AppSession) {
    val settings by session.settings.collectAsState()
    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(settings.themeMode, systemDark) {
        val style = when (settings.themeMode) {
            ThemeMode.LIGHT -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
            ThemeMode.DARK -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
            ThemeMode.SYSTEM -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
        }
        val dark = settings.themeMode == ThemeMode.DARK || (settings.themeMode == ThemeMode.SYSTEM && systemDark)
        val bg = if (dark) DarkDictionaryPalette.bg else LightDictionaryPalette.bg
        val background = UIColor(red = bg.red.toDouble(), green = bg.green.toDouble(), blue = bg.blue.toDouble(), alpha = 1.0)
        for (scene in UIApplication.sharedApplication.connectedScenes) {
            val windows = (scene as? UIWindowScene)?.windows ?: continue
            for (window in windows) {
                (window as? UIWindow)?.let {
                    it.overrideUserInterfaceStyle = style
                    it.backgroundColor = background
                }
            }
        }
    }
}
