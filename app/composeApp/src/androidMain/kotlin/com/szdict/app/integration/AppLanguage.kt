package com.szdict.app.integration

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.snapshotFlow
import com.szdict.app.AppSession
import com.szdict.core.api.UiLanguageSetting
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Per-app language (SET-018, GLB-102). On Android 13+ the `ui_language` setting and the system's
 * per-app language screen are one choice: an in-app change is written to [LocaleManager], and a
 * change made in system settings is read back into the setting when the activity is created.
 * Older versions apply the setting in-app only.
 */
object AppLanguage {
    fun apply(context: Context, setting: UiLanguageSetting) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        val wanted = if (setting == UiLanguageSetting.SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(setting.stored)
        if (manager.applicationLocales != wanted) runCatching { manager.applicationLocales = wanted }
    }

    private var watching: AppSession? = null

    /**
     * Reads the system's per-app language into the setting once settings have loaded (the system
     * screen wins: it may have changed while the app was not running), then starts writing in-app
     * changes back to the system.
     */
    fun attach(activity: Activity, session: AppSession) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val context = activity.applicationContext
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        session.scope.launch {
            snapshotFlow { session.settingsLoaded }.first { it }
            val system = fromLocales(runCatching { manager.applicationLocales.toLanguageTags() }.getOrDefault(""))
            if (system != session.settings.value.uiLanguage) session.updateSettings { it.copy(uiLanguage = system) }
            if (watching === session) return@launch
            watching = session
            session.settings.map { it.uiLanguage }.distinctUntilChanged().drop(1).collect { apply(context, it) }
        }
    }

    /** Maps the system's per-app locale list (language tags, comma separated; empty = follow system). */
    fun fromLocales(tags: String): UiLanguageSetting {
        val first = tags.split(',').firstOrNull { it.isNotBlank() }?.trim() ?: return UiLanguageSetting.SYSTEM
        return when (UiLanguageSetting.SYSTEM.resolve(first).tag) {
            "zh-Hans" -> UiLanguageSetting.ZH_HANS
            "zh-Hant" -> UiLanguageSetting.ZH_HANT
            else -> UiLanguageSetting.EN
        }
    }
}
