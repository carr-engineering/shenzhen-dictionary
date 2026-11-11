package com.szdict.app.feature.conversion

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.kit.LocalSession
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.DictMode

/** The split button bound to a single-passage [ConversionState] (Reading). */
@Composable
fun ScriptConversionButton(state: ConversionState, modifier: Modifier = Modifier) {
    ScriptConversionButton(
        selection = state.selection,
        enabled = state.enabled,
        onMainAction = state::toggle,
        onConvert = state::convert,
        modifier = modifier,
    )
}

/**
 * The script-conversion split button (TXT-081): the main segment converts or shows the original,
 * the chevron segment (`Conversion options`) opens the conversion dialog (TXT-083). Surfaces with
 * several texts (OCR, Voice) drive it with their own [ConversionSelection]:
 * `onMainAction = { selection = selection.toggled(script, mode) }`,
 * `onConvert = { s, t -> selection = selection.confirmedWith(s, t) }`, then reconvert.
 */
@Composable
fun ScriptConversionButton(
    selection: ConversionSelection,
    enabled: Boolean,
    onMainAction: () -> Unit,
    onConvert: (source: ScriptVariant, target: ScriptVariant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val settings by LocalSession.current.settings.collectAsState()
    val mode = settings.dictionaryMode
    val action = selection.mainAction(settings.script, mode)
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    val content = if (enabled) palette.accent else palette.accent.copy(alpha = 0.38f)
    val border = BorderStroke(1.dp, if (enabled) palette.border else palette.border.copy(alpha = 0.6f))

    Row(modifier.height(40.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        SplitSegment(
            onClick = onMainAction,
            enabled = enabled,
            shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 4.dp, bottomEnd = 4.dp),
            border = border,
            contentColor = content,
            contentPadding = PaddingValues(start = 16.dp, end = 12.dp),
        ) {
            // The visible text is the accessibility label: it names what a tap does (GLB-080).
            Text(strings.text(action.label), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        SplitSegment(
            onClick = { dialogOpen = true },
            enabled = enabled,
            shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp),
            border = border,
            contentColor = content,
            contentPadding = PaddingValues(start = 8.dp, end = 10.dp),
        ) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = strings.text("Conversion options"), modifier = Modifier.size(22.dp))
        }
    }

    if (dialogOpen && enabled) {
        ConversionDialog(
            initial = selection.dialogDefaults(settings.script, mode),
            mode = mode,
            onDismiss = { dialogOpen = false },
            onConvert = { source, target ->
                dialogOpen = false
                onConvert(source, target)
            },
        )
    }
}

@Composable
private fun SplitSegment(
    onClick: () -> Unit,
    enabled: Boolean,
    shape: Shape,
    border: BorderStroke,
    contentColor: Color,
    contentPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = Color.Transparent,
        contentColor = contentColor,
        border = border,
        modifier = Modifier.height(40.dp).defaultMinSize(minWidth = 40.dp),
    ) {
        Box(Modifier.padding(contentPadding), contentAlignment = Alignment.Center) { content() }
    }
}

/** The conversion dialog (TXT-083): `Text is:` and `Convert to:` single-choice groups. */
@Composable
fun ConversionDialog(
    initial: ConversionPair,
    mode: DictMode,
    onDismiss: () -> Unit,
    onConvert: (source: ScriptVariant, target: ScriptVariant) -> Unit,
) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    var source by rememberSaveable { mutableStateOf(initial.source) }
    var target by rememberSaveable { mutableStateOf(ConversionRules.coerceTarget(initial.source, initial.target, mode)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.panel,
        titleContentColor = palette.fg,
        textContentColor = palette.fg,
        title = { Text(strings.text("Convert script")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                GroupLabel(strings.text("Text is:"))
                Column(Modifier.selectableGroup()) {
                    for (option in ScriptVariant.sources) {
                        OptionRow(
                            label = strings.text(option.label),
                            selected = option == source,
                            enabled = true,
                            onSelect = {
                                source = option
                                // A source change that disables the selected target moves it to the default.
                                target = ConversionRules.coerceTarget(option, target, mode)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                GroupLabel(strings.text("Convert to:"))
                Column(Modifier.selectableGroup()) {
                    for (option in ScriptVariant.targets) {
                        OptionRow(
                            label = strings.text(option.label),
                            supporting = if (option == ScriptVariant.TRADITIONAL_TW_PHRASES) {
                                strings.text("Converts vocabulary too, e.g. 软件 → 軟體")
                            } else null,
                            selected = option == target,
                            enabled = ConversionRules.isTargetEnabled(source, option),
                            onSelect = { target = option },
                        )
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.text("Cancel"), color = palette.accent) } },
        confirmButton = {
            val valid = ConversionRules.isTargetEnabled(source, target)
            TextButton(onClick = { onConvert(source, target) }, enabled = valid) {
                Text(strings.text("Convert"), color = if (valid) palette.accent else palette.muted, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalDictionaryPalette.current.muted,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit, supporting: String? = null) {
    val palette = LocalDictionaryPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = palette.accent, unselectedColor = palette.muted),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.padding(vertical = 4.dp)) {
            val color = if (enabled) palette.fg else palette.muted.copy(alpha = 0.5f)
            Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = if (enabled) palette.muted else color)
            }
        }
    }
}
