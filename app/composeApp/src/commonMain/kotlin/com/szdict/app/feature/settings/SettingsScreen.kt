package com.szdict.app.feature.settings

import com.szdict.app.ui.kit.WithTooltip
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.szdict.app.AppSession
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.kit.SegmentedPillRow
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.DictMode
import com.szdict.core.api.Script
import com.szdict.core.api.ThemeMode
import com.szdict.core.api.UiLanguageSetting

/** Whether this platform can save captured photos to a gallery (SET-084: absent on Web). */
internal const val SETTINGS_SERVICE_NO_GALLERY = "settings.noGallery"

/**
 * Settings (SET-001…005): a pushed route over Search. Every control applies and persists at once
 * (SET-003); there is no Save button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(session: AppSession, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val settings by session.settings.collectAsState()
    val mode = settings.dictionaryMode
    var showLicences by rememberSaveable { mutableStateOf(false) }
    val datasetVersion by produceState<String?>(null, mode, session.dataGeneration) {
        value = runCatching { session.dictionary(mode).info().datasetVersion }.getOrNull()
    }
    val bottomInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    val gallery = session.env.services[SETTINGS_SERVICE_NO_GALLERY] != true

    RouteScaffold(strings.text("Settings"), onBack, modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 20.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item("dictionary") {
                SettingBlock(strings.text("Dictionary")) {
                    SegmentedPillRow(
                        modifier = Modifier.fillMaxWidth(),
                        equalWidth = true,
                        minHeight = 48.dp,
                        options = listOf("中文 Mandarin", "粵語 Cantonese"),
                        selectedIndex = if (mode == DictMode.MANDARIN) 0 else 1,
                        onSelect = { index ->
                            val target = if (index == 0) DictMode.MANDARIN else DictMode.CANTONESE
                            session.updateSettings { it.copy(dictionaryMode = target) }
                        },
                    )
                }
            }
            item("script") {
                // SET-011: the script is stored per dictionary mode; this control edits the active mode's.
                SettingBlock(strings.text("Default script")) {
                    SegmentedPillRow(
                        modifier = Modifier.fillMaxWidth(),
                        equalWidth = true,
                        minHeight = 48.dp,
                        options = listOf("简体 Simplified", "繁體 Traditional"),
                        selectedIndex = if (settings.script == Script.SIMPLIFIED) 0 else 1,
                        onSelect = { index ->
                            val target = if (index == 0) Script.SIMPLIFIED else Script.TRADITIONAL
                            session.updateSettings { it.withScript(it.dictionaryMode, target) }
                        },
                    )
                }
            }
            item("language") {
                SettingBlock(strings.text("App language")) {
                    // SET-018: each language is named in itself, whatever the current UI language.
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Chips size to their label and wrap onto another row where they do not fit, so no name is clipped.
                        UiLanguageSetting.entries.forEach { option ->
                            ChoiceChip(
                                label = uiLanguageLabel(option, strings.text("System")),
                                selected = settings.uiLanguage == option,
                                onClick = { session.updateSettings { it.copy(uiLanguage = option) } },
                            )
                        }
                    }
                }
            }
            item("theme") {
                SettingBlock(strings.text("Theme")) {
                    val order = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)
                    SegmentedPillRow(
                        modifier = Modifier.fillMaxWidth(),
                        equalWidth = true,
                        minHeight = 48.dp,
                        options = listOf(strings.text("Light"), strings.text("Dark"), strings.text("Auto")),
                        selectedIndex = order.indexOf(settings.themeMode),
                        onSelect = { index -> session.updateSettings { it.copy(themeMode = order[index]) } },
                    )
                }
            }
            item("tones") {
                SettingToggle(strings.text("Tone coloring"), settings.toneColors) { on -> session.updateSettings { it.copy(toneColors = on) } }
            }
            item("pronunciation") {
                // SET-017: the same key as the inline toggle buttons; it is about passages only.
                SettingToggle(
                    strings.text("Show pronunciation above sentences"), settings.showPronunciation,
                    description = strings.text("Readings above the characters of sentences and passages: Search, OCR text, Reading and Voice. Word cards and entries always show theirs."),
                ) { on ->
                    session.updateSettings { it.copy(showPronunciation = on) }
                }
            }
            item("boundaries") {
                SettingToggle(strings.text("Reading boundaries"), settings.showBoundaries) { on ->
                    session.updateSettings { it.copy(showBoundaries = on) }
                }
            }
            if (gallery) {
                item("autosave") {
                    SettingToggle(strings.text("Save photos to gallery"), settings.cameraAutoSave) { on ->
                        session.updateSettings { it.copy(cameraAutoSave = on) }
                    }
                }
            }
            item("privacy") { LinkRow(strings.text("Privacy policy"), onClick = session::openPrivacy) }
            item("divider") { HorizontalDivider(color = palette.border) }
            item("about") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        strings.text("About & Attribution"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.fg,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(strings.text("Version {0}", session.env.appVersion.ifBlank { "—" }),
                        style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                    Text(strings.text("Dataset {0}", datasetVersion?.ifBlank { null } ?: "—"),
                        style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                    FeedbackNote(session)
                    Text(strings.text(AttributionIntro), style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                }
            }
            item("licences") {
                OutlinedButton(onClick = { showLicences = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Policy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(strings.text("Open-source library licenses"))
                }
            }
            item("sources") { LinkRow(strings.text("Data sources"), onClick = session::openAttribution) }
            item("privacy-about") { LinkRow(strings.text("Privacy policy"), onClick = session::openPrivacy) }
        }
    }
    if (showLicences) LibraryLicencesDialog(onDismiss = { showLicences = false }, onOpen = session.env.links::open)
}

/** Option labels of the App language selector (GLB-102): only `System` is translated. */
internal fun uiLanguageLabel(option: UiLanguageSetting, system: String): String = when (option) {
    UiLanguageSetting.SYSTEM -> system
    UiLanguageSetting.EN -> "English"
    UiLanguageSetting.ZH_HANS -> "简体中文"
    UiLanguageSetting.ZH_HANT -> "繁體中文"
}

@Composable
internal fun SettingBlock(label: String, content: @Composable () -> Unit) {
    val palette = LocalDictionaryPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = palette.fg)
        content()
    }
}

/** Inner horizontal padding of the clickable rows: the ripple's rounded shape stands clear of the text. */
private val RowInset = 12.dp

/** Makes the row [extra] wider on both sides, so padding of the same size inside it leaves the content where it was. */
private fun Modifier.bleedHorizontally(extra: Dp): Modifier = layout { measurable, constraints ->
    val e = extra.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = 2 * e))
    layout(placeable.width - 2 * e, placeable.height) { placeable.place(-e, 0) }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, description: String? = null, onCheckedChange: (Boolean) -> Unit) {
    val palette = LocalDictionaryPalette.current
    Row(
        Modifier.bleedHorizontally(RowInset).fillMaxWidth().clip(MaterialTheme.shapes.medium).heightIn(min = 48.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = RowInset, vertical = if (description != null) 6.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = palette.fg)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = palette.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.onAccent, checkedTrackColor = palette.accent,
                uncheckedThumbColor = palette.muted, uncheckedTrackColor = palette.bgSoft, uncheckedBorderColor = palette.border,
            ),
        )
    }
}

@Composable
internal fun LinkRow(label: String, onClick: () -> Unit) {
    val palette = LocalDictionaryPalette.current
    Row(
        Modifier.bleedHorizontally(RowInset).fillMaxWidth().clip(MaterialTheme.shapes.medium).heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = RowInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = palette.fg, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.muted)
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalDictionaryPalette.current
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            selected = selected,
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp).widthIn(min = 72.dp),
            shape = CircleShape,
            color = if (selected) palette.accent else palette.bgSoft,
            contentColor = if (selected) palette.onAccent else palette.muted,
            border = BorderStroke(1.dp, if (selected) palette.accent else palette.border),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}
