package com.szdict.app.integration.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentHeight
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.szdict.app.MainActivity
import com.szdict.app.R
import com.szdict.app.feature.hsk.WidgetPick
import com.szdict.app.feature.hsk.WidgetProjection
import com.szdict.app.feature.hsk.WidgetReading
import com.szdict.app.feature.hsk.WidgetRow
import com.szdict.app.i18n.AppStrings
import com.szdict.app.integration.AndroidIntegrations
import com.szdict.app.ui.kit.hskLevelLabel
import com.szdict.app.ui.theme.DarkDictionaryPalette
import com.szdict.app.ui.theme.DictionaryPalette
import com.szdict.app.ui.theme.LightDictionaryPalette
import com.szdict.core.api.DictMode
import com.szdict.core.api.Script
import com.szdict.core.api.Settings
import com.szdict.core.api.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

internal val CompactWidgetSize = DpSize(180.dp, 120.dp)
internal val MediumWidgetSize = DpSize(250.dp, 150.dp)
internal val ExpandedWidgetSize = DpSize(320.dp, 210.dp)

/** What the widget projection load produced: still loading is `null`, absent is [projection] == null. */
private class LoadedProjection(val mode: DictMode, val projection: WidgetProjection?)

/**
 * HSK word-of-the-day widget (SET-060…068). Each instance keeps its own level mask; the word is
 * the deterministic daily pick shared with the in-app Word of the Day card (SET-063), read from
 * the widget projection only (SET-066). It follows the app's mode, script, tone-colour, theme and
 * language settings, and always shows the reading (SET-062).
 */
class HskWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Responsive(setOf(CompactWidgetSize, MediumWidgetSize, ExpandedWidgetSize))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext
        val initial = WidgetSettings.current(app)
        val settingsFlow = WidgetSettings.flow(app)
        val appWidgetId = runCatching { GlanceAppWidgetManager(app).getAppWidgetId(id) }.getOrDefault(AppWidgetManager.INVALID_APPWIDGET_ID)
        provideContent {
            val preferences = currentState<Preferences>()
            val levelMask = preferences[WidgetLevelMaskKey] ?: 0
            val tick = preferences[WidgetRefreshTickKey] ?: 0L
            val epochDay = remember(tick) { LocalDate.now().toEpochDay() }
            val settings by settingsFlow.collectAsState(initial)
            val mode = settings.dictionaryMode
            val loaded by produceState<LoadedProjection?>(null, mode) {
                value = LoadedProjection(mode, WidgetProjectionStore.load(app, mode))
            }
            val current = loaded?.takeIf { it.mode == mode }
            val row = remember(current, levelMask, epochDay) {
                current?.projection?.let { WidgetPick.pick(it.rows, levelMask, epochDay) }
            }
            val strings = remember(settings.uiLanguage) { AppStrings(settings.uiLanguage.resolve(WidgetSettings.systemLocaleTag(app))) }
            HskWidgetContent(row, loading = current == null, levelMask, settings, strings, appWidgetId)
        }
    }
}

/** Widget provider: also refreshes on date, time, time-zone and locale changes (SET-067). */
class HskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HskWidget()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RefreshActions) {
            super.onReceive(context, intent)
            return
        }
        // Glance owns goAsync() for its own broadcasts; these refresh broadcasts are ours alone.
        val pending = goAsync() ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                HskWidgetUpdater.refreshAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        HskWidgetUpdater.scheduleMidnight(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        HskWidgetUpdater.cancelMidnight(context)
    }

    private companion object {
        val RefreshActions = setOf(
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED, HskWidgetUpdater.ACTION_REFRESH,
        )
    }
}

@Composable
private fun HskWidgetContent(row: WidgetRow?, loading: Boolean, levelMask: Int, settings: Settings, strings: AppStrings, appWidgetId: Int) {
    val colors = widgetColors(settings.themeMode, settings.dictionaryMode)
    Box(GlanceModifier.fillMaxSize().appWidgetBackground().background(colors.border).cornerRadius(10.dp).padding(1.dp)) {
        when {
            row != null -> WordContent(row, settings, colors, strings)
            levelMask and WidgetPick.ALL_LEVELS == 0 -> MessageContent(strings.text("Choose HSK levels"), colors, appWidgetId)   // SET-061
            loading -> MessageContent(strings.text("Loading today’s word…"), colors, AppWidgetManager.INVALID_APPWIDGET_ID)
            else -> MessageContent(strings.text("Word unavailable"), colors, AppWidgetManager.INVALID_APPWIDGET_ID)              // SET-082
        }
    }
}

@Composable
private fun MessageContent(text: String, colors: WidgetColors, configureWidgetId: Int) {
    val context = LocalContext.current
    val action = if (configureWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) GlanceModifier else GlanceModifier.clickable(
        actionStartActivity(
            Intent(context, HskWidgetConfigureActivity::class.java)
                .setData(Uri.parse("szdict-widget://configure/$configureWidgetId"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, configureWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    )
    Column(
        modifier = GlanceModifier.fillMaxSize().background(colors.panel).cornerRadius(9.dp).padding(14.dp).then(action),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        Text(text, style = TextStyle(color = colors.fg, fontSize = 17.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun WordContent(row: WidgetRow, settings: Settings, colors: WidgetColors, strings: AppStrings) {
    val context = LocalContext.current
    val size = LocalSize.current
    val mode = settings.dictionaryMode
    val compact = size.width < MediumWidgetSize.width || size.height < MediumWidgetSize.height
    val expanded = size.height >= ExpandedWidgetSize.height
    val traditional = settings.script == Script.TRADITIONAL
    val primary = (if (traditional) row.trad else row.simp).ifEmpty { row.simp.ifEmpty { row.trad } }
    val alternate = if (traditional) row.simp else row.trad
    val syllables = remember(mode, row.reading) { WidgetReading.syllables(mode, row.reading) }
    // SET-064: the entry route of the widget's mode, carrying the natural key — never the entry id.
    val openEntry = Intent(Intent.ACTION_VIEW, Uri.parse(WidgetPick.url(mode, row)))
        .setClass(context, MainActivity::class.java)
        .putExtra(AndroidIntegrations.EXTRA_FROM_WIDGET, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    Column(
        modifier = GlanceModifier.fillMaxSize().background(colors.bg).cornerRadius(9.dp)
            .padding(if (compact) 11.dp else 14.dp).clickable(actionStartActivity(openEntry)),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.Top) {
            Column(GlanceModifier.defaultWeight()) {
                val headTones = syllables.map { it.tone }.takeIf { settings.toneColors && it.size == primary.codePointCount(0, primary.length) }
                TonedParts(codePoints(primary), headTones, colors, colors.fg, if (compact) 24 else 29, FontWeight.Medium, gap = 0)
                if (alternate.isNotEmpty() && alternate != primary) {
                    Text(alternate, style = TextStyle(color = colors.muted, fontSize = if (compact) 13.sp else 15.sp), maxLines = 1)
                }
                Spacer(GlanceModifier.height(2.dp))
                if (syllables.isEmpty()) {
                    if (mode == DictMode.CANTONESE) {
                        Text(strings.text("No Cantonese reading"), style = TextStyle(color = colors.muted, fontSize = if (compact) 13.sp else 15.sp), maxLines = 1)
                    }
                } else {
                    // SET-062: the widget always shows the reading; show_pronunciation does not apply.
                    TonedParts(syllables.map { it.text }, syllables.map { it.tone }.takeIf { settings.toneColors }, colors, colors.accent,
                        if (compact) 14 else 16, FontWeight.Normal, gap = 4)
                }
            }
            Spacer(GlanceModifier.width(8.dp))
            SpeakerButton(mode, row, primary, colors, strings)
        }
        Spacer(GlanceModifier.height(if (compact) 5.dp else 7.dp))
        Text(
            row.gloss,
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            style = TextStyle(color = colors.fg, fontSize = if (compact) 13.sp else 15.sp),
            maxLines = if (expanded) 6 else if (compact) 2 else 3,
        )
        Spacer(GlanceModifier.height(if (compact) 5.dp else 8.dp))
        Row(GlanceModifier.fillMaxWidth().wrapContentHeight()) { LevelBadges(row.levels, colors) }
    }
}

@Composable
private fun SpeakerButton(mode: DictMode, row: WidgetRow, text: String, colors: WidgetColors, strings: AppStrings) {
    val action = actionRunCallback<PlayWidgetAudioAction>(
        actionParametersOf(WidgetAudioModeKey to mode.name, WidgetAudioReadingKey to row.reading, WidgetAudioTextKey to text)
    )
    Box(
        modifier = GlanceModifier.size(44.dp).background(colors.border).cornerRadius(22.dp).padding(1.dp).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Box(GlanceModifier.fillMaxSize().background(colors.bg).cornerRadius(21.dp), contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_speaker),
                contentDescription = strings.text("Play pronunciation"),
                modifier = GlanceModifier.size(22.dp),
                colorFilter = ColorFilter.tint(colors.accent),
            )
        }
    }
}

/** One text per character or syllable so each can carry its tone colour (GLB-066). */
@Composable
private fun TonedParts(parts: List<String>, tones: List<Int>?, colors: WidgetColors, fallback: ColorProvider, fontSize: Int, weight: FontWeight, gap: Int) {
    if (tones == null || tones.size != parts.size || parts.size > 8) {
        Text(parts.joinToString(if (gap > 0) " " else ""), style = TextStyle(color = fallback, fontSize = fontSize.sp, fontWeight = weight), maxLines = 1)
        return
    }
    Row {
        parts.forEachIndexed { index, part ->
            if (gap > 0 && index > 0) Spacer(GlanceModifier.width(gap.dp))
            Text(part, style = TextStyle(color = colors.tones.getOrElse(tones[index] - 1) { colors.muted }, fontSize = fontSize.sp, fontWeight = weight), maxLines = 1)
        }
    }
}

@Composable
private fun LevelBadges(levels: List<Int>, colors: WidgetColors) {
    Row(GlanceModifier.wrapContentHeight()) {
        levels.distinct().sorted().forEachIndexed { index, level ->
            if (index > 0) Spacer(GlanceModifier.width(4.dp))
            val color = colors.hsk.getOrElse(level - 1) { colors.accent }
            Box(GlanceModifier.wrapContentHeight().background(color).cornerRadius(8.dp).padding(1.dp)) {
                Text(
                    hskLevelLabel(level),
                    modifier = GlanceModifier.wrapContentHeight().background(colors.bg).cornerRadius(7.dp).padding(horizontal = 7.dp, vertical = 2.dp),
                    style = TextStyle(color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
        }
    }
}

private fun codePoints(text: String): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i < text.length) {
        val n = Character.charCount(text.codePointAt(i))
        out += text.substring(i, i + n)
        i += n
    }
    return out
}

private class WidgetColors(
    val bg: ColorProvider,
    val panel: ColorProvider,
    val fg: ColorProvider,
    val muted: ColorProvider,
    val border: ColorProvider,
    val accent: ColorProvider,
    val tones: List<ColorProvider>,
    val hsk: List<ColorProvider>,
)

/** SET-021 / SET-062: the app's fixed palette under the app's theme setting, six tones in Cantonese mode. */
private fun widgetColors(themeMode: ThemeMode, mode: DictMode): WidgetColors {
    val light = if (mode == DictMode.CANTONESE) LightDictionaryPalette.forCantonese(false) else LightDictionaryPalette
    val dark = if (mode == DictMode.CANTONESE) DarkDictionaryPalette.forCantonese(true) else DarkDictionaryPalette
    fun color(pick: (DictionaryPalette) -> Color): ColorProvider = when (themeMode) {
        ThemeMode.LIGHT -> ColorProvider(pick(light))
        ThemeMode.DARK -> ColorProvider(pick(dark))
        ThemeMode.SYSTEM -> androidx.glance.color.ColorProvider(pick(light), pick(dark))
    }
    return WidgetColors(
        bg = color { it.bg },
        panel = color { it.panel },
        fg = color { it.fg },
        muted = color { it.muted },
        border = color { it.border },
        accent = color { it.accent },
        tones = light.tones.indices.map { i -> color { it.tones[i] } },
        hsk = light.hskColors.indices.map { i -> color { it.hskColors[i] } },
    )
}
