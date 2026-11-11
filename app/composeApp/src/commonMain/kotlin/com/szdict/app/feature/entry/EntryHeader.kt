package com.szdict.app.feature.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.szdict.app.AppSession
import com.szdict.app.DetailRoute
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.kit.HskBadge
import com.szdict.app.ui.kit.LocalDisplayPrefs
import com.szdict.app.ui.kit.PlayButton
import com.szdict.app.ui.kit.TonedHanziText
import com.szdict.app.ui.kit.TonedReadingText
import com.szdict.app.ui.kit.codePointStrings
import com.szdict.app.ui.kit.tonedReading
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.EntryDetail

/** Header above the tab strip (ENT-020…ENT-024). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EntryHeader(
    session: AppSession,
    route: DetailRoute.Entry,
    detail: EntryDetail,
    assetsLoading: Boolean,
    status: String?,
    modifier: Modifier = Modifier,
) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val prefs = LocalDisplayPrefs.current
    val card = detail.card
    val playing by session.playing.collectAsState()
    val toneColors = prefs.toneColors && card.toneColourable && !card.noReading

    Column(modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(4.dp))
        // ENT-021: headword and alternate forms are selectable; buttons stay outside the container.
        SelectionContainer {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TonedHanziText(
                    text = card.headword,
                    reading = card.reading,
                    toneColors = toneColors,
                    // ENT-020 item 1: steps down past 8 code points and wraps (ENT-104).
                    style = if (codePointStrings(card.headword).size > 8) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.fg,
                    modifier = Modifier.align(Alignment.Bottom),
                )
                if (card.alternateForms.isNotEmpty()) {
                    Text(
                        card.alternateForms.joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.muted,
                        modifier = Modifier.align(Alignment.Bottom).padding(bottom = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PlayButton(
                enabled = card.audio.hasRecording || card.audio.ttsText.isNotBlank(),   // MED-001
                playing = playing == card.id,
                onClick = { session.play(card.id, card.audio, route.mode) },
            )
            Row(Modifier.weight(1f)) {
                when {
                    // ENT-116: not a reading, so it stays visible when readings are hidden.
                    card.noReading -> Text(strings.text("No Cantonese reading"), style = MaterialTheme.typography.titleMedium, color = palette.muted)
                    prefs.showPronunciation -> SelectionContainer {
                        TonedReadingText(card.reading, prefs.toneColors, MaterialTheme.typography.titleMedium, color = palette.accent, fontWeight = FontWeight.Medium)
                    }
                    // GLB-120: measured as if shown, not drawn, not selectable (ENT-021).
                    else -> TonedReadingText(card.reading, prefs.toneColors, MaterialTheme.typography.titleMedium, visible = false,
                        color = palette.accent, fontWeight = FontWeight.Medium)
                }
            }
            PronunciationToggle(prefs.showPronunciation) {
                session.updateSettings { it.copy(showPronunciation = !it.showPronunciation) }
            }
        }
        if (detail.altReadings.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            AltReadingsLine(detail)
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (detail.hskBadges.isNotEmpty()) {
                HskBadge(
                    levels = detail.hskBadges.map { it.level },
                    // ENT-020 item 4: each level carries its own grammatical class.
                    posLabels = detail.hskBadges.mapNotNull { badge ->
                        badge.partOfSpeechText(strings.lang).takeIf { it.isNotBlank() }?.let { badge.level to it }
                    }.toMap(),
                )
            }
            if (assetsLoading) {
                SkeletonBar(58.dp, 12.dp, Modifier.align(Alignment.CenterVertically).testTag("entry-assets-loading-placeholder"))   // ENT-024
            }
        }
        Spacer(Modifier.height(10.dp))
        status?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = palette.muted, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

/** `Taiwan pronunciation lè sè • Also pronounced …` (ENT-020 item 3): labels stay, readings follow GLB-120. */
@Composable
private fun AltReadingsLine(detail: EntryDetail) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val prefs = LocalDisplayPrefs.current
    val (line, ranges) = remember(detail.altReadings, strings.lang) { altReadingsLine(detail.altReadings, strings.lang) }
    val text = remember(line, ranges, prefs, palette) {
        buildAnnotatedString {
            var at = 0
            for (range in ranges) {
                append(line.substring(at, range.start))
                if (prefs.showPronunciation) {
                    pushStyle(SpanStyle(color = palette.accent))
                    append(tonedReading(range.alt.reading, prefs.toneColors, palette))
                    pop()
                } else {
                    pushStyle(SpanStyle(color = Color.Transparent))
                    append(range.alt.reading.display)
                    pop()
                }
                at = range.end
            }
            append(line.substring(at))
        }
    }
    val labelsOnly = remember(detail.altReadings, strings.lang) { detail.altReadings.joinToString(", ") { it.label.text(strings.lang) } }
    val modifier = if (prefs.showPronunciation) Modifier else Modifier.semantics { contentDescription = labelsOnly }   // GLB-122
    if (prefs.showPronunciation) {
        SelectionContainer { Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = palette.muted) }
    } else {
        Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
    }
}

/** The global pronunciation toggle (GLB-120) at the trailing end of the reading row. */
@Composable
internal fun PronunciationToggle(shown: Boolean, onToggle: () -> Unit) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val state = if (shown) strings.text("Shown") else strings.text("Hidden")
    IconButton(onClick = onToggle, modifier = Modifier.semantics { stateDescription = state }) {
        Icon(
            imageVector = if (shown) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
            contentDescription = if (shown) strings.text("Hide pronunciation") else strings.text("Show pronunciation"),
            tint = palette.muted,
            modifier = Modifier.size(22.dp),
        )
    }
}
