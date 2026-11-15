package com.szdict.app.feature.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.kit.LocalDisplayPrefs
import com.szdict.app.ui.kit.PassageText
import com.szdict.app.ui.kit.passageTextStyle
import com.szdict.app.ui.kit.TonedHanziText
import com.szdict.app.ui.kit.TonedReadingText
import com.szdict.app.ui.kit.WithTooltip
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.Combination
import com.szdict.core.api.EntryCard

/** Segmented passage card of sentence mode (SRCH-100, SRCH-101). */
@Composable
internal fun PassageCard(
    passage: AnalysedPassage,
    selectedOffset: Int?,
    speaking: Boolean,
    onTap: (Int) -> Unit,
    onSpeak: () -> Unit,
    onTranslate: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    /** At the trailing end of the action row: the pronunciation toggle of the passage. */
    trailing: @Composable () -> Unit = {},
) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = palette.panel,
        border = BorderStroke(1.dp, palette.border),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                strings.text("Tap a Hanzi to see words at that position"),
                style = MaterialTheme.typography.labelMedium,
                color = palette.muted,
            )
            Spacer(Modifier.height(6.dp))
            PassageText(passage, selectedOffset, onTap, Modifier.fillMaxWidth(), style = passageTextStyle())
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SentenceActions(passage.text.isNotBlank(), speaking, onSpeak, onTranslate, onCopy)
                Spacer(Modifier.weight(1f))
                trailing()
            }
        }
    }
}

/** Sentence action row (MED-020, MED-021): play/stop, translate, copy — 40 dp outlined accent buttons. */
@Composable
internal fun SentenceActions(
    enabled: Boolean,
    speaking: Boolean,
    onSpeak: () -> Unit,
    onTranslate: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = appStrings()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionButton(strings.text(if (speaking) "Stop sentence playback" else "Play sentence"), enabled, onSpeak) {
            AnimatedContent(
                targetState = speaking,
                transitionSpec = {
                    (fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.86f)) togetherWith
                        (fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.86f))
                },
                label = "sentence-playback-icon",
            ) { playing ->
                Icon(
                    if (playing) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = strings.text(if (playing) "Stop sentence playback" else "Play sentence"),
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        ActionButton(strings.text("Translate sentence"), enabled, onTranslate) { ActionIcon(Icons.Rounded.Translate, strings.text("Translate sentence")) }
        ActionButton(strings.text("Copy sentence"), enabled, onCopy) { ActionIcon(Icons.Rounded.ContentCopy, strings.text("Copy sentence")) }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, label: String) = Icon(icon, contentDescription = label, modifier = Modifier.size(21.dp))

@Composable
private fun ActionButton(label: String, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val palette = LocalDictionaryPalette.current
    WithTooltip(label) {
        OutlinedIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.outlinedIconButtonColors(
                contentColor = palette.accent,
                disabledContentColor = palette.accent.copy(alpha = 0.35f),
            ),
            border = BorderStroke(1.dp, palette.border),
            content = content,
        )
    }
}

/**
 * One multi-word combination (SRCH-065): the words in order, each its own tap target; there is no
 * target for the combination as a whole.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CombinationCard(
    combination: Combination,
    selectedId: Int?,
    onOpen: (EntryCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDictionaryPalette.current
    val prefs = LocalDisplayPrefs.current
    val spoken = combination.words.joinToString(", ") { word ->
        if (!word.reading.isEmpty) "${word.headword} ${word.reading.display}" else word.headword
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { contentDescription = spoken },
        shape = MaterialTheme.shapes.medium,
        color = palette.panel,
        border = BorderStroke(1.dp, if (combination.words.any { it.id == selectedId }) palette.accent else palette.border),
    ) {
        FlowRow(
            Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            combination.words.forEach { word ->
                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (word.id == selectedId) palette.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(role = Role.Button) { onOpen(word) }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    TonedHanziText(
                        word.headword, word.reading, prefs.toneColors && word.toneColourable,
                        MaterialTheme.typography.titleLarge, color = palette.fg,
                    )
                    TonedReadingText(
                        word.reading, prefs.toneColors, MaterialTheme.typography.bodyMedium,
                        color = palette.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Stand-in for a row whose window is still being hydrated (SRCH-073); sized like a result card. */
@Composable
internal fun CardPlaceholder(modifier: Modifier = Modifier) {
    val palette = LocalDictionaryPalette.current
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).height(104.dp),
        shape = MaterialTheme.shapes.medium,
        color = palette.panel,
        border = BorderStroke(1.dp, palette.border),
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(width = 72.dp, height = 22.dp).background(palette.bgSoft, RoundedCornerShape(4.dp)))
            Box(Modifier.size(width = 96.dp, height = 12.dp).background(palette.bgSoft, RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth().height(12.dp).background(palette.bgSoft, RoundedCornerShape(4.dp)))
        }
    }
}

/** Settings card of the empty state (SRCH-116). */
@Composable
internal fun SettingsCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = palette.panel,
        border = BorderStroke(1.dp, palette.border),
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Settings, contentDescription = null, tint = palette.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                strings.text("App Settings"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = palette.fg, modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.muted)
        }
    }
}

@Composable
internal fun EmptyHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        appStrings().text(text),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = LocalDictionaryPalette.current.fg,
        modifier = modifier.padding(top = 10.dp, bottom = 6.dp),
    )
}
