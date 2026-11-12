package com.szdict.app.integration

import androidx.compose.runtime.snapshotFlow
import com.szdict.app.AppSession
import com.szdict.core.api.Routes
import com.szdict.core.api.UiLanguageSetting
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState

/**
 * iOS platform integration (docs/06): incoming links — Universal Links and the `szdict:` URL
 * scheme (GLB-111) — and Home Screen quick actions (SET-050…053), all routed through the shared
 * [IncomingRouter], plus the per-app language (SET-018). The Swift host forwards the system
 * callbacks to `MainViewController.kt`, which calls in here. iOS has no share target in v1.
 */
object IosIntegrations {
    /** Quick-action types declared in Info.plist (`UIApplicationShortcutItems`). */
    const val SHORTCUT_READ_CLIPBOARD = "com.szdict.app.read-clipboard"
    const val SHORTCUT_OPEN_CAMERA = "com.szdict.app.open-camera"
    const val SHORTCUT_OPEN_VOICE = "com.szdict.app.open-voice"

    private const val SCHEME = "szdict:"
    private var clipboardPending = false

    /** A Universal Link (`https://szdict.com/…`) or an app-scheme URL (`szdict://word/…`, same paths). */
    fun openUrl(session: AppSession, url: String): Boolean {
        val trimmed = url.trim()
        val link = if (trimmed.startsWith(SCHEME, ignoreCase = true)) {
            "https://${Routes.HOST}/" + trimmed.substring(SCHEME.length).trimStart('/')
        } else trimmed
        return handle(session, IncomingIntentData(action = IncomingRouter.ACTION_VIEW, data = link))
    }

    /** A Home Screen quick action; each delivery is handled once (SET-052). */
    fun performShortcut(session: AppSession, type: String): Boolean = when (type) {
        SHORTCUT_READ_CLIPBOARD -> handle(session, IncomingIntentData(action = IncomingRouter.ACTION_READ_CLIPBOARD))
        SHORTCUT_OPEN_CAMERA -> handle(session, IncomingIntentData(action = IncomingRouter.ACTION_VIEW, data = "https://${Routes.HOST}/ocr"))
        SHORTCUT_OPEN_VOICE -> handle(session, IncomingIntentData(action = IncomingRouter.ACTION_VIEW, data = "https://${Routes.HOST}/voice"))
        else -> false
    }

    /** Call when the app becomes active: the clipboard quick action reads only then (SET-051, TXT-053). */
    fun onActive() {
        if (!clipboardPending) return
        clipboardPending = false
        IncomingRouter.readClipboardNow()
    }

    private fun handle(session: AppSession, intent: IncomingIntentData): Boolean {
        val action = IncomingRouter.classify(intent, session.env.voiceAvailable)
        if (action == IncomingAction.None) return false
        clipboardPending = IncomingRouter.dispatch(session, action)
        if (clipboardPending && UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive) onActive()
        return true
    }
}

/**
 * Per-app language (SET-018, GLB-102). iOS keeps an app's language override in the app's own
 * `AppleLanguages` default: the system's per-app language screen writes it, and so does the in-app
 * setting, so the two are one choice. The override is read into the setting once settings have
 * loaded (the system screen wins: it may have changed while the app was not running).
 */
object IosAppLanguage {
    private const val KEY = "AppleLanguages"
    private var watching: AppSession? = null

    private fun override(): String? {
        val domain = NSBundle.mainBundle.bundleIdentifier ?: return null
        val languages = NSUserDefaults.standardUserDefaults.persistentDomainForName(domain)?.get(KEY) as? List<*>
        return languages?.firstOrNull() as? String
    }

    fun attach(session: AppSession) {
        session.scope.launch {
            snapshotFlow { session.settingsLoaded }.first { it }
            val system = fromTag(override())
            if (system != session.settings.value.uiLanguage) session.updateSettings { it.copy(uiLanguage = system) }
            if (watching === session) return@launch
            watching = session
            session.settings.map { it.uiLanguage }.distinctUntilChanged().drop(1).collect(::apply)
        }
    }

    private fun apply(setting: UiLanguageSetting) {
        if (fromTag(override()) == setting) return
        val defaults = NSUserDefaults.standardUserDefaults
        if (setting == UiLanguageSetting.SYSTEM) defaults.removeObjectForKey(KEY) else defaults.setObject(listOf(setting.stored), KEY)
    }

    fun fromTag(tag: String?): UiLanguageSetting {
        if (tag.isNullOrBlank()) return UiLanguageSetting.SYSTEM
        return when (UiLanguageSetting.SYSTEM.resolve(tag).tag) {
            "zh-Hans" -> UiLanguageSetting.ZH_HANS
            "zh-Hant" -> UiLanguageSetting.ZH_HANT
            else -> UiLanguageSetting.EN
        }
    }
}
