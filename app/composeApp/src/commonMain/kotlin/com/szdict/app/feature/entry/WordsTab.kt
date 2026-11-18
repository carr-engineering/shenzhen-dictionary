package com.szdict.app.feature.entry

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.szdict.app.i18n.AppStrings
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.kit.CardAudio
import com.szdict.app.ui.kit.EntryCardView
import com.szdict.app.ui.kit.ExpanderToggle
import com.szdict.app.ui.kit.FollowSelectedChip
import com.szdict.app.ui.kit.InsideWordsState
import com.szdict.app.ui.kit.NavChip
import com.szdict.app.ui.kit.SectionLabel
import com.szdict.app.ui.kit.ChipBarHorizontalPadding
import com.szdict.app.ui.kit.ChipBarVerticalPadding
import com.szdict.app.ui.kit.TranslucentChipBar
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.EntryCard
import com.szdict.core.api.RelatedKind
import kotlinx.coroutines.launch

internal const val RELATED_PAGE_SIZE = 25

/** Section title (ENT-060). */
internal fun relatedTitle(kind: RelatedKind, word: String, strings: AppStrings): String = when (kind) {
    RelatedKind.WITHIN -> strings.text("Within 「{0}」", word)
    RelatedKind.STARTING_WITH -> strings.text("Starting with 「{0}」", word)
    RelatedKind.CONTAINING -> strings.text("Containing 「{0}」", word)
    RelatedKind.CONTAINING_REVERSED -> strings.text("Containing reversed")
}

/** `Within` rows carry no inside-words button: the section already lists them (ENT-061). */
internal fun relatedRowHasInsideWords(kind: RelatedKind, card: EntryCard): Boolean =
    kind != RelatedKind.WITHIN && card.insideCount > 0

/**
 * Lazy-list index of each section's label in [wordsTabItems]: an optional sticky chip row, the top
 * spacer, then per section a gap (not before the first), the label, its rows and a `Show more` item.
 */
internal fun wordsLabelIndices(rowCounts: List<Int>, hasMore: List<Boolean>, nav: Boolean): IntArray {
    var n = if (nav) 2 else 1
    return IntArray(rowCounts.size) { i ->
        if (i > 0) n++
        val label = n
        n += 1 + rowCounts[i] + if (hasMore[i]) 1 else 0
        label
    }
}

/** The section shown under the chip row: the last one whose label is at or before [firstIndex]. */
internal fun wordsSectionAt(labels: IntArray, firstIndex: Int): Int {
    var shown = 0
    for (i in labels.indices) if (labels[i] <= firstIndex) shown = i
    return shown
}

/**
 * WORDS tab (ENT-060…066): paged sections of compact cards, emitted as individual lazy items so
 * long `Containing` lists stay cheap. Empty sections are omitted by the engine (ENT-100, ENT-101).
 * With two or more non-empty sections a sticky, translucent chip row (the HSK selector's bar and
 * chips) jumps between them; with one there is nothing to jump to and no row.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.wordsTabItems(derived: EntryDerived, actions: EntryActions, strings: AppStrings, list: LazyListState? = null) {
    val rowPadding = Modifier.padding(horizontal = 16.dp)
    val sections = derived.related
    val nav = list != null && sections.count { it.total > 0 || it.cards.isNotEmpty() } >= 2
    if (nav) {
        val labels = wordsLabelIndices(sections.map { it.cards.size }, sections.map { it.hasMore }, nav = true)
        stickyHeader(key = "words-nav") { WordsNav(sections, labels, list!!, strings) }
    }
    // The chip bar's shadow strip already spaces the first label from the row.
    item(key = "words-top-spacer") { Spacer(Modifier.height(if (nav) 4.dp else 12.dp)) }
    if (sections.isEmpty()) {
        item(key = "words-empty") {
            Text(strings.text("No related words."), color = LocalDictionaryPalette.current.muted, style = MaterialTheme.typography.bodyLarge, modifier = rowPadding)
        }
        return
    }
    sections.forEachIndexed { index, section ->
        val kind = section.kind
        if (index > 0) item(key = "words-gap-$kind") { Spacer(Modifier.height(16.dp)) }
        item(key = "words-label-$kind") {
            SectionLabel(relatedTitle(kind, section.word, strings), count = section.total, modifier = rowPadding)
            Spacer(Modifier.height(2.dp))
        }
        val cards = section.cards.toList()
        items(cards.size, key = { "words-$kind-${cards[it].id}" }) { i -> RelatedRow(cards[i], kind, actions, rowPadding) }
        if (section.hasMore) {
            item(key = "words-more-$kind") {
                val remaining = (section.total - section.cards.size).coerceAtLeast(1)
                // ENT-064: the last section keeps loading as the user scrolls to its end; earlier
                // sections page on demand so the sections after them stay reachable.
                if (index == sections.lastIndex) LaunchedEffect(section.fetched) { loadMore(section, derived, actions) }
                Box(rowPadding.fillMaxWidth().padding(top = 4.dp).alpha(if (section.loading) 0.5f else 1f)) {
                    ExpanderToggle(
                        label = strings.text("Show {0} more", minOf(remaining, RELATED_PAGE_SIZE)),
                        expanded = false,
                        onToggle = { loadMore(section, derived, actions) },
                    )
                }
            }
        }
    }
}

/**
 * ENT-066 chip row: one chip per section with its count. The section under the row is selected and
 * its chip kept in view as the list scrolls; a tapped chip stays selected while the list scrolls to it.
 */
@Composable
private fun WordsNav(sections: List<RelatedSection>, labels: IntArray, list: LazyListState, strings: AppStrings) {
    val scope = rememberCoroutineScope()
    val chipRow = rememberLazyListState()
    var navHeight by remember { mutableIntStateOf(0) }
    var requested by remember { mutableStateOf<Int?>(null) }
    var request by remember { mutableIntStateOf(0) }
    val shown by remember(labels) {
        derivedStateOf {
            val first = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index > 0 && it.offset + it.size > navHeight + 1 }?.index ?: 0
            wordsSectionAt(labels, first)
        }
    }
    val selected = requested ?: shown
    val scrolledUnder by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
    FollowSelectedChip(chipRow, selected = selected, chipCount = sections.size)
    TranslucentChipBar(scrolledUnder = scrolledUnder, onBarHeight = { navHeight = it }) {
        LazyRow(
            state = chipRow,
            modifier = Modifier.fillMaxWidth().padding(vertical = ChipBarVerticalPadding),
            contentPadding = PaddingValues(horizontal = ChipBarHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(sections.size, key = { "words-chip-${sections[it].kind}" }) { i ->
                val section = sections[i]
                NavChip(relatedTitle(section.kind, section.word, strings), section.total, selected = i == selected, onClick = {
                    val token = ++request
                    requested = i
                    scope.launch {
                        try {
                            // The label lands just under the sticky row.
                            list.animateScrollToItem(labels[i], -navHeight)
                        } finally {
                            if (request == token) requested = null
                        }
                    }
                })
            }
        }
    }
}

/** Fetches the next page of a section (ENT-064); failures leave the section as it is. */
private fun loadMore(section: RelatedSection, derived: EntryDerived, actions: EntryActions) {
    if (section.loading || !section.hasMore) return
    section.loading = true
    actions.session.scope.launch {
        try {
            val page = actions.session.dictionary(actions.route.mode)
                .related(derived.detail.id, section.kind, actions.script, offset = section.fetched, limit = RELATED_PAGE_SIZE)
            section.append(page)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // GLB-034: stays recoverable — the button can be tapped again.
        } finally {
            section.loading = false
        }
    }
}

@Composable
private fun RelatedRow(card: EntryCard, kind: RelatedKind, actions: EntryActions, modifier: Modifier) {
    val session = actions.session
    val mode = actions.route.mode
    val playing by session.playing.collectAsState()
    // ENT-065: the button reflects actual availability (recording or TTS).
    val audio: (EntryCard) -> CardAudio? = { c ->
        if (c.audio.hasRecording || c.audio.ttsText.isNotBlank()) CardAudio(playing == c.id) { session.play(c.id, c.audio, mode) } else null
    }
    var insideOpen by rememberSaveable(card.id, card.insideCount) { mutableStateOf(false) }
    val inside = if (relatedRowHasInsideWords(kind, card)) {
        InsideWordsState(
            expanded = insideOpen,
            onToggle = { insideOpen = !insideOpen },
            load = { session.dictionary(mode).insideWords(card.id, actions.script) },
            onOpen = { actions.openEntry(it.id) },
            audio = audio,
        )
    } else null
    EntryCardView(card = card, onClick = { actions.openEntry(card.id) }, modifier = modifier, compact = true, audio = audio(card), inside = inside)
}
