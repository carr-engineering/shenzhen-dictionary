package com.szdict.app.integration

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.szdict.app.AppSession
import com.szdict.app.integration.widget.HskWidgetUpdater
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Android platform integration (docs/06, Settings and platform integration): incoming intents —
 * launcher shortcuts (SET-050…053), share targets (SET-054…056), App Links (GLB-111) and the
 * widget's entry link (SET-064) — plus the per-app language (SET-018) and widget refresh on
 * settings changes (SET-015). `MainActivity` forwards its lifecycle callbacks here.
 */
object AndroidIntegrations {
    /** Marks the widget's entry link, so an unknown key shows the entry error state (SET-083). */
    const val EXTRA_FROM_WIDGET = "com.szdict.app.extra.FROM_WIDGET"

    private var installedFor: AppSession? = null
    private var clipboardPending = false

    /** Call from `onCreate`. [fresh] is false when the activity is being recreated (saved state present). */
    fun onCreate(activity: Activity, session: AppSession, fresh: Boolean) {
        install(activity.applicationContext, session)
        AppLanguage.attach(activity, session)
        // A recreated activity gets its original intent again; it was handled already (SET-052, SET-055).
        if (fresh) handle(activity, session, activity.intent)
    }

    /** Call from `onNewIntent` after `setIntent(intent)`. */
    fun onNewIntent(activity: Activity, session: AppSession, intent: Intent?) = handle(activity, session, intent)

    /** Call when the window gains focus: the clipboard shortcut reads only then (SET-051, TXT-053). */
    fun onWindowFocus(session: AppSession) {
        if (!clipboardPending) return
        clipboardPending = false
        IncomingRouter.readClipboardNow()
    }

    private fun handle(activity: Activity, session: AppSession, intent: Intent?) {
        if (intent == null) return
        // Re-launched from Recents: the system re-delivers the original intent; never re-apply it.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val action = IncomingRouter.classify(snapshot(intent), session.env.voiceAvailable)
        if (action == IncomingAction.None) return
        consume(intent)
        clipboardPending = IncomingRouter.dispatch(session, action)
        if (clipboardPending && activity.hasWindowFocus()) onWindowFocus(session)
    }

    /** The routing-relevant fields of [intent]. Nothing here is logged (SET-055, GLB-092). */
    fun snapshot(intent: Intent): IncomingIntentData {
        val stream = runCatching {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        }.getOrNull()
        return IncomingIntentData(
            action = intent.action,
            type = intent.type,
            data = intent.dataString,
            text = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull(),
            processText = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() }.getOrNull(),
            streamUri = stream?.toString(),
            fromWidget = intent.getBooleanExtra(EXTRA_FROM_WIDGET, false),
        )
    }

    /** SET-052 / SET-055: reset the intent so recreation, rotation or a later Back cannot re-trigger it. */
    private fun consume(intent: Intent) {
        intent.action = Intent.ACTION_MAIN
        intent.setDataAndType(null, null)
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.removeExtra(Intent.EXTRA_PROCESS_TEXT)
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.removeExtra(EXTRA_FROM_WIDGET)
    }

    private fun install(context: Context, session: AppSession) {
        if (installedFor === session) return
        installedFor = session
        // SET-015 / SET-062: the widget follows mode, script, tone colours, theme and UI language.
        session.scope.launch {
            session.settings
                .map { listOf(it.dictionaryMode, it.scriptMandarin, it.scriptCantonese, it.toneColors, it.themeMode, it.uiLanguage) }
                .distinctUntilChanged()
                .drop(1)
                .collect { HskWidgetUpdater.refreshAll(context) }
        }
    }
}
