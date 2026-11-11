package com.szdict.app.integration.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.szdict.app.feature.hsk.WidgetPick
import com.szdict.app.feature.hsk.WidgetProjection
import com.szdict.app.feature.hsk.WidgetRow
import com.szdict.app.i18n.AppStrings
import com.szdict.app.i18n.LocalStrings
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.kit.HskBadge
import com.szdict.app.ui.theme.DictionaryTheme
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.Script
import com.szdict.core.api.Settings
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Chooses the HSK levels of one widget instance when it is placed or reconfigured (SET-061). */
class HskWidgetConfigureActivity : ComponentActivity() {
    private val scope = MainScope()
    private val appWidgetId: Int by lazy {
        intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val glanceId = runCatching { GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId) }.getOrNull()
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || glanceId == null) {
            finish()
            return
        }
        setContent {
            var levelMask by remember { mutableIntStateOf(0) }
            var loaded by remember { mutableStateOf(false) }
            var saving by remember { mutableStateOf(false) }
            var projection by remember { mutableStateOf<WidgetProjection?>(null) }
            var settings by remember { mutableStateOf(Settings()) }
            LaunchedEffect(glanceId) {
                levelMask = runCatching { getAppWidgetState(this@HskWidgetConfigureActivity, PreferencesGlanceStateDefinition, glanceId)[WidgetLevelMaskKey] }
                    .getOrNull() ?: 0
                settings = WidgetSettings.current(this@HskWidgetConfigureActivity)
                projection = WidgetProjectionStore.load(applicationContext, settings.dictionaryMode)
                loaded = true
            }
            val strings = remember(settings.uiLanguage) { AppStrings(settings.uiLanguage.resolve(WidgetSettings.systemLocaleTag(this))) }
            DictionaryTheme(settings.themeMode, settings.dictionaryMode) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    Surface(Modifier.fillMaxSize(), color = LocalDictionaryPalette.current.bg) {
                        ConfigureScreen(
                            loaded = loaded,
                            saving = saving,
                            levelMask = levelMask,
                            settings = settings,
                            preview = remember(projection, levelMask) {
                                projection?.let { WidgetPick.pick(it.rows, levelMask, LocalDate.now().toEpochDay()) }
                            },
                            onToggle = { levelMask = WidgetPick.toggle(levelMask, it) },
                            onCancel = ::finish,
                            onConfirm = {
                                if (levelMask != 0 && !saving) {
                                    saving = true
                                    save(glanceId, levelMask)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun save(glanceId: GlanceId, levelMask: Int) {
        scope.launch {
            runCatching {
                updateAppWidgetState(this@HskWidgetConfigureActivity, glanceId) {
                    it[WidgetLevelMaskKey] = levelMask and WidgetPick.ALL_LEVELS
                    it[WidgetRefreshTickKey] = System.currentTimeMillis()
                }
                HskWidget().update(this@HskWidgetConfigureActivity, glanceId)
                HskWidgetUpdater.scheduleMidnight(applicationContext)
            }
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigureScreen(
    loaded: Boolean,
    saving: Boolean,
    levelMask: Int,
    settings: Settings,
    preview: WidgetRow?,
    onToggle: (Int) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 20.dp + insets.calculateTopPadding(), bottom = 20.dp + insets.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Text(strings.text("HSK word of the day"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = palette.fg)
                Spacer(Modifier.height(5.dp))
                Text(strings.text("Choose one or more levels."), style = MaterialTheme.typography.bodyMedium, color = palette.muted)
            }
            item {
                if (!loaded) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = palette.accent) }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..7).forEach { level ->
                            val selected = WidgetPick.contains(levelMask, level)
                            val color = palette.hsk(level)
                            Surface(
                                onClick = { onToggle(level) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) palette.hskTint(level) else palette.panel,
                                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) color else palette.border),
                                modifier = Modifier.semantics { role = Role.Checkbox; this.selected = selected },
                            ) {
                                Text(
                                    if (level == 7) "HSK 7–9" else "HSK $level",
                                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text(strings.text("Preview"), style = MaterialTheme.typography.titleMedium, color = palette.fg)
                Spacer(Modifier.height(8.dp))
                if (preview == null) {
                    Text(
                        if (levelMask == 0) strings.text("Select at least one level.") else strings.text("Preview unavailable."),
                        style = MaterialTheme.typography.bodyMedium, color = palette.muted,
                    )
                } else {
                    PreviewCard(preview, settings)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onCancel, enabled = !saving, modifier = Modifier.weight(1f)) { Text(strings.text("Cancel")) }
                    Button(
                        onClick = onConfirm,
                        enabled = loaded && levelMask != 0 && !saving,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent),
                    ) { Text(if (saving) strings.text("Adding…") else strings.text("Add widget")) }
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(row: WidgetRow, settings: Settings) {
    val palette = LocalDictionaryPalette.current
    val traditional = settings.script == Script.TRADITIONAL
    val level = row.levels.minOrNull() ?: 7
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = palette.hskTint(level), border = BorderStroke(1.dp, palette.border)) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text((if (traditional) row.trad else row.simp).ifEmpty { row.simp }, style = MaterialTheme.typography.titleLarge, color = palette.fg)
            if (row.reading.isNotBlank()) Text(row.reading, style = MaterialTheme.typography.bodyMedium, color = palette.accent)
            Text(row.gloss, style = MaterialTheme.typography.bodyMedium, color = palette.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            HskBadge(levels = row.levels.distinct().sorted())
        }
    }
}
