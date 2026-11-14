package com.szdict.app.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.EntryCard
import com.szdict.core.api.InsideWord

/**
 * Display preferences every card and passage needs (GLB-066, GLB-120). Provided once by the app
 * shell through [LocalDisplayPrefs]. [showPronunciation] applies to the readings above passages
 * only ([PassageText]); cards and entry pages always show theirs.
 */
data class DisplayPrefs(
    val toneColors: Boolean = true,
    val showPronunciation: Boolean = true,
    val showBoundaries: Boolean = true,
)

val LocalDisplayPrefs = androidx.compose.runtime.compositionLocalOf { DisplayPrefs() }

/** Audio hooks for a card's play button (ENT-070): null [onPlay] hides the button. */
data class CardAudio(val playing: Boolean, val onPlay: () -> Unit)

/**
 * The one shared card (ENT-070): search results, compact rows, sheet rows, HSK cards, WORDS rows.
 * [inside] adds the inside-words footer strip and its rows inside the card (ENT-073, SRCH-105).
 */
@Composable
fun EntryCardView(
    card: EntryCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    compact: Boolean = false,
    containerColor: Color = Color.Unspecified,
    audio: CardAudio? = null,
    maxDefinitionLines: Int = DEFAULT_DEFINITION_LINES,
    showHsk: Boolean = true,
    inside: InsideWordsState? = null,
) {
    val palette = LocalDictionaryPalette.current
    val color = if (containerColor == Color.Unspecified) palette.panel else containerColor
    val border = BorderStroke(1.dp, if (selected) palette.accent else palette.border)
    val cardModifier = modifier.fillMaxWidth().padding(vertical = 4.dp)
    if (inside != null && card.insideCount > 0 && card.hanziCount >= 2) {
        Surface(modifier = cardModifier, shape = MaterialTheme.shapes.medium, color = color, border = border) {
            Column {
                Box(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
                    EntryCardContent(card, compact, audio, maxDefinitionLines, showHsk)
                }
                InsideWordsFooter(card.headword, card.insideCount, inside, maxDefinitionLines)
            }
        }
    } else {
        Surface(onClick = onClick, modifier = cardModifier, shape = MaterialTheme.shapes.medium, color = color, border = border) {
            EntryCardContent(card, compact, audio, maxDefinitionLines, showHsk)
        }
    }
}

/** Several readings of one word as one card with hairline dividers (SRCH-099). */
@Composable
fun EntryCardGroup(
    cards: List<EntryCard>,
    onClick: (EntryCard) -> Unit,
    modifier: Modifier = Modifier,
    selectedId: Int? = null,
    audio: (EntryCard) -> CardAudio? = { null },
    inside: InsideWordsState? = null,
) {
    if (cards.isEmpty()) return
    if (cards.size == 1) {
        EntryCardView(cards[0], { onClick(cards[0]) }, modifier, selected = cards[0].id == selectedId, audio = audio(cards[0]), inside = inside)
        return
    }
    val palette = LocalDictionaryPalette.current
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = palette.panel,
        border = BorderStroke(1.dp, if (cards.any { it.id == selectedId }) palette.accent else palette.border),
    ) {
        Column {
            cards.forEachIndexed { index, card ->
                Box(Modifier.fillMaxWidth().clickable { onClick(card) }) {
                    EntryCardContent(card, false, audio(card), DEFAULT_DEFINITION_LINES, true)
                }
                if (index < cards.lastIndex) HorizontalDivider(color = palette.border, modifier = Modifier.padding(horizontal = 13.dp))
            }
            val last = cards.last()
            if (inside != null && last.insideCount > 0 && last.hanziCount >= 2) {
                InsideWordsFooter(last.headword, last.insideCount, inside, DEFAULT_DEFINITION_LINES)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryCardContent(card: EntryCard, compact: Boolean, audio: CardAudio?, maxDefinitionLines: Int, showHsk: Boolean, badgeBackground: Color = Color.Unspecified) {
    val palette = LocalDictionaryPalette.current
    val prefs = LocalDisplayPrefs.current
    val strings = appStrings()
    val reading = card.mandarinRow?.reading ?: card.reading
    Column(Modifier.padding(horizontal = 13.dp, vertical = if (compact) 8.dp else 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Column(Modifier.weight(1f)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    TonedHanziText(
                        text = card.headword,
                        reading = card.reading,
                        toneColors = prefs.toneColors && card.toneColourable,
                        style = when {
                            compact -> MaterialTheme.typography.titleMedium
                            card.headword.length > 8 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleLarge
                        },
                        color = palette.fg,
                        modifier = Modifier.align(Alignment.Bottom),
                    )
                    if (card.alternateForms.isNotEmpty()) {
                        Text(
                            card.alternateForms.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.muted,
                            modifier = Modifier.align(Alignment.Bottom).padding(bottom = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(1.dp))
                if (card.mandarinRow != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(strings.text("Mandarin"), style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                        TonedReadingText(reading, prefs.toneColors, MaterialTheme.typography.bodyMedium,
                            color = palette.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (card.noReading) {
                    Text(strings.text("No Cantonese reading"), style = MaterialTheme.typography.bodyMedium, color = palette.muted, maxLines = 1)
                } else if (card.mandarinRow == null) {
                    TonedReadingText(reading, prefs.toneColors, MaterialTheme.typography.bodyMedium,
                        color = palette.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                card.matchedAlt?.let { alt ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(alt.label.text(strings.lang), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                        TonedReadingText(alt.reading, prefs.toneColors, MaterialTheme.typography.bodySmall,
                            color = palette.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (audio != null) PlayButton(enabled = true, playing = audio.playing, onClick = audio.onPlay)
        }
        Spacer(Modifier.height(5.dp))
        card.redirectHeaders.forEach { note ->
            Text(note, style = MaterialTheme.typography.bodyMedium, color = palette.muted, modifier = Modifier.padding(bottom = 5.dp))
        }
        card.provenance?.let {
            Text(it.text(strings.lang), style = MaterialTheme.typography.labelSmall, color = palette.muted, modifier = Modifier.padding(bottom = 3.dp))
        }
        val definitions = card.mandarinRow?.definitions ?: card.definitions
        NumberedDefinitions(definitions, maxDefinitionLines, badgeBackground = badgeBackground)
        if (definitions.isEmpty() && card.notes.isNotEmpty()) {
            Text(
                card.notes.joinToString(" • ") { "${it.label.text(strings.lang)} ${it.text}".trim() },
                style = MaterialTheme.typography.bodyMedium, color = palette.muted, maxLines = maxDefinitionLines, overflow = TextOverflow.Ellipsis,
            )
        }
        if (showHsk && card.hskLevels.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            HskBadge(levels = card.hskLevels, compact = compact)
        }
    }
}

/** Definition line limit of cards on the page; the word sheet passes [Int.MAX_VALUE] (no limit). */
const val DEFAULT_DEFINITION_LINES = 4

/** All definitions as one numbered paragraph, clamped with a `+N` badge (SRCH-094, SRCH-095). */
@Composable
fun NumberedDefinitions(definitions: List<String>, maxLines: Int, modifier: Modifier = Modifier, badgeBackground: Color = Color.Unspecified) {
    if (definitions.isEmpty()) return
    val palette = LocalDictionaryPalette.current
    val starts = remember(definitions) { IntArray(definitions.size) }
    val text = remember(definitions, palette) {
        buildAnnotatedString {
            definitions.forEachIndexed { index, definition ->
                if (index > 0) append("  ")
                starts[index] = length
                withStyle(SpanStyle(color = palette.fg, fontWeight = FontWeight.Bold)) { append("${index + 1}. ") }
                append(definition)
            }
        }
    }
    var hidden by remember(text, maxLines) { mutableIntStateOf(0) }
    Box(modifier) {
        Text(
            text, style = MaterialTheme.typography.bodyMedium, color = palette.muted, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout ->
                val newHidden = if (layout.hasVisualOverflow && layout.lineCount > 0) {
                    val visibleEnd = layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)
                    starts.count { it >= visibleEnd }
                } else 0
                if (newHidden != hidden) hidden = newHidden
            },
        )
        if (hidden > 0) {
            Text("+$hidden", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = palette.accent,
                modifier = Modifier.align(Alignment.BottomEnd).background(if (badgeBackground == Color.Unspecified) palette.panel else badgeBackground).padding(start = 4.dp))
        }
    }
}

/** Unmatched run (SRCH-098): not tappable, labelled `NO MATCH`. */
@Composable
fun InertRow(text: String, modifier: Modifier = Modifier) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    Surface(modifier.fillMaxWidth().padding(vertical = 4.dp), shape = MaterialTheme.shapes.medium, color = palette.bgSoft,
        border = BorderStroke(1.dp, palette.border)) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text, style = MaterialTheme.typography.titleMedium, color = palette.muted)
            Text(strings.text("NO MATCH"), style = MaterialTheme.typography.labelSmall, letterSpacing = 0.8.sp, color = palette.muted,
                modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/**
 * Inside-words expander state, owned by the surface (ENT-073): whether it is expanded, the rows
 * (loaded when it first opens), and the row callbacks.
 */
class InsideWordsState(
    val expanded: Boolean,
    val onToggle: () -> Unit,
    val load: suspend () -> List<InsideWord>,
    val onOpen: (EntryCard) -> Unit,
    val audio: (EntryCard) -> CardAudio? = { null },
)

/**
 * The footer strip of a card with words inside its headword (ENT-073): a hairline, then the count
 * and a chevron as one tap target that only toggles; the rows open beneath it, inside the card.
 */
@Composable
private fun InsideWordsFooter(word: String, count: Int, state: InsideWordsState, maxDefinitionLines: Int) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val spoken = strings.text("Show {0} words within {1}", count, word)
    val stateText = if (state.expanded) strings.text("Expanded") else strings.text("Collapsed")
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = palette.border)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = state.onToggle)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    stateDescription = stateText
                }
                .heightIn(min = 40.dp)
                .padding(start = 13.dp, end = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                strings.text("+ {0} more words within", count),
                style = MaterialTheme.typography.labelLarge,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = palette.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (state.expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(22.dp),
            )
        }
        AnimatedVisibility(
            visible = state.expanded,
            enter = expandVertically(tween(200), expandFrom = Alignment.Top) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(160), shrinkTowards = Alignment.Top) + fadeOut(tween(110)),
        ) {
            var rows by remember { mutableStateOf<List<InsideWord>?>(null) }
            LaunchedEffect(Unit) { rows = runCatching { state.load() }.getOrDefault(emptyList()) }
            // Flush rows on the recessed tone, each its own entry (SRCH-131).
            Column(Modifier.fillMaxWidth().background(palette.highlightPaper)) {
                rows.orEmpty().forEach { row ->
                    HorizontalDivider(color = palette.border)
                    Box(Modifier.fillMaxWidth().clickable { state.onOpen(row.card) }) {
                        EntryCardContent(row.card, true, state.audio(row.card), maxDefinitionLines, true, palette.highlightPaper)
                    }
                }
            }
        }
    }
}
