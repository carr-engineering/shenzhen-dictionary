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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.szdict.app.ui.kit.hiddenFromAccessibility
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.Combination
import com.szdict.core.api.CombinationSlot
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
 * One combination row (SRCH-065): slots in order, each followed by a dot but the last; a slot's alternatives (the
 * homophones the row offers at those syllables) side by side, separated by a thin slash, the first —
 * the word of the best sequence — at full strength. Every alternative is its own tap target opening
 * its entry; there is no target for the row as a whole. A slot whose alternatives share one reading
 * shows it once beneath them, else each alternative carries its own. The row is described as a whole
 * for assistive technology ("就 jiù or 九 jiǔ, 点 diǎn"), each alternative also on its own.
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
    val strings = appStrings()
    val or = strings.text("or")
    fun said(word: EntryCard) = if (!word.reading.isEmpty) "${word.headword} ${word.reading.display}" else word.headword
    val spoken = combination.slots.joinToString(", ") { slot -> slot.alternatives.joinToString(" $or ") { said(it) } }
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { contentDescription = spoken },
        shape = MaterialTheme.shapes.medium,
        color = palette.panel,
        border = BorderStroke(1.dp, if (combination.slots.any { s -> s.alternatives.any { it.id == selectedId } }) palette.accent else palette.border),
    ) {
        FlowRow(
            Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.Top,
        ) {
            combination.slots.forEachIndexed { index, slot ->
                // The dot follows its slot, so a wrapped line never starts with one.
                Row(verticalAlignment = Alignment.Top) {
                    CombinationSlotView(slot, selectedId, onOpen, ::said)
                    if (index < combination.slots.lastIndex) SlotSeparator("·")
                }
            }
        }
    }
}

/** The alternatives of one slot; a shared reading is drawn once, centred beneath them. */
@Composable
private fun CombinationSlotView(
    slot: CombinationSlot,
    selectedId: Int?,
    onOpen: (EntryCard) -> Unit,
    said: (EntryCard) -> String,
) {
    val palette = LocalDictionaryPalette.current
    val prefs = LocalDisplayPrefs.current
    val readings = slot.alternatives.map { it.reading.display }
    val shared = slot.alternatives.size > 1 && readings.distinct().size == 1 && !slot.alternatives[0].reading.isEmpty
    Box {
        Row(verticalAlignment = Alignment.Top) {
            slot.alternatives.forEachIndexed { i, word ->
                if (i > 0) SlotSeparator("/")
                // Alternatives after the first are the row's other readings of the syllables: same size
                // and tone colours, slightly receded so the best sequence reads first.
                val strength = if (i == 0) 1f else 0.68f
                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (word.id == selectedId) palette.accentSoft else Color.Transparent)
                        .clickable(role = Role.Button) { onOpen(word) }
                        // One announcement per alternative: headword and reading (the texts inside are not read again).
                        .clearAndSetSemantics { contentDescription = said(word) }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TonedHanziText(
                        word.headword, word.reading, prefs.toneColors && word.toneColourable,
                        MaterialTheme.typography.titleLarge, color = palette.fg, modifier = Modifier.alpha(strength),
                    )
                    TonedReadingText(
                        word.reading, prefs.toneColors, MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.alpha(strength),
                        visible = !shared, color = palette.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (shared) {
            TonedReadingText(
                slot.alternatives[0].reading, prefs.toneColors, MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).hiddenFromAccessibility(),
                color = palette.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Thin separator between alternatives (`/`) or slots (`·`), aligned with the hanzi line. */
@Composable
private fun SlotSeparator(glyph: String) {
    val palette = LocalDictionaryPalette.current
    Text(
        glyph,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Light,
        color = palette.muted.copy(alpha = if (glyph == "/") 0.55f else 0.8f),
        modifier = Modifier.padding(top = 4.dp).padding(horizontal = if (glyph == "/") 0.dp else 2.dp).hiddenFromAccessibility(),
    )
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
