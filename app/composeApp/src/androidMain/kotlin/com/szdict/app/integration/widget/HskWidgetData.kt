package com.szdict.app.integration.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.szdict.app.feature.hsk.WidgetProjection
import com.szdict.app.platform.DataStoreSettingsStorage
import com.szdict.core.api.DictMode
import com.szdict.core.api.Settings
import com.szdict.core.api.SettingsCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.SoftReference
import java.time.LocalDate
import java.time.ZoneId

/** Per-instance level mask (SET-061, HSK-072): bit 0 = HSK 1 … bit 6 = HSK 7-9. */
internal val WidgetLevelMaskKey = intPreferencesKey("hsk_level_mask")

/** Bumped to recompose a live widget session on a date, time or settings change (SET-067). */
internal val WidgetRefreshTickKey = longPreferencesKey("refresh_tick")

/**
 * Reads the widget projection asset (`assets/data/<mode>-widget.json`, DATA-130) — never the
 * dictionary database (SET-066). The parsed rows are held softly so the widget host's memory stays
 * bounded; a missing or malformed asset yields null and the widget shows `Word unavailable`.
 */
internal object WidgetProjectionStore {
    private val mutex = Mutex()
    private val cache = HashMap<DictMode, SoftReference<WidgetProjection>>()

    suspend fun load(context: Context, mode: DictMode): WidgetProjection? {
        cache[mode]?.get()?.let { return it }
        return mutex.withLock {
            cache[mode]?.get() ?: withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open("data/" + WidgetProjection.assetName(mode)).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.getOrNull()?.let(WidgetProjection::parse)
            }?.also { cache[mode] = SoftReference(it) }
        }
    }
}

internal object WidgetSettings {
    fun flow(context: Context): Flow<Settings> =
        DataStoreSettingsStorage(context.applicationContext).values.map(SettingsCodec::decode)

    suspend fun current(context: Context): Settings = runCatching { flow(context).first() }.getOrDefault(Settings())

    fun systemLocaleTag(context: Context): String = context.resources.configuration.locales[0]?.toLanguageTag() ?: "en"
}

/** Refreshes every widget instance and keeps a next-midnight refresh scheduled. */
object HskWidgetUpdater {
    const val ACTION_REFRESH = "com.szdict.app.action.WIDGET_REFRESH"

    suspend fun refreshAll(context: Context) {
        val app = context.applicationContext
        runCatching {
            val ids = GlanceAppWidgetManager(app).getGlanceIds(HskWidget::class.java)
            for (id in ids) {
                updateAppWidgetState(app, id) { it[WidgetRefreshTickKey] = System.currentTimeMillis() }
                HskWidget().update(app, id)
            }
            if (ids.isNotEmpty()) scheduleMidnight(app)
        }
    }

    /**
     * SET-067: the date-changed broadcast is not reliably delivered to manifest receivers, so an
     * inexact alarm (no permission needed) also refreshes shortly after local midnight.
     */
    fun scheduleMidnight(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val next = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 5_000
        // A bounded window: a plain inexact alarm may be deferred by an hour or more.
        runCatching { alarms.setWindow(AlarmManager.RTC, next, 10 * 60_000L, refreshIntent(context)) }
    }

    fun cancelMidnight(context: Context) {
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(refreshIntent(context)) }
    }

    private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, HskWidgetReceiver::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
