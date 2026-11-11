package com.szdict.app.feature.search

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.szdict.app.AppSession
import com.szdict.app.Destination
import com.szdict.app.DetailRoute
import com.szdict.app.WordSheetRequest
import com.szdict.app.i18n.AppStrings
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.kit.CardAudio
import com.szdict.app.ui.kit.EntryCardGroup
import com.szdict.app.ui.kit.EntryCardView
import com.szdict.app.ui.kit.InertRow
import com.szdict.app.ui.kit.InsideWordsState
import com.szdict.app.ui.kit.LocalSheetInset
import com.szdict.app.ui.kit.SectionLabel
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.DictMode
import com.szdict.core.api.EntryCard
import com.szdict.core.api.InputMethod
import com.szdict.core.api.Script
import com.szdict.core.api.SearchSide
import com.szdict.core.api.SectionKind
import com.szdict.core.api.WordGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Playback key of the passage card's sentence (MED-021). */
private data class SentenceKey(val text: String)

/** Rows before the end of the hydrated window at which the next window is requested (SRCH-073). */
private const val PREFETCH = 12

/**
 * Search destination (docs/03 §7–§12): floating search control, two swipeable result pages, and
 * the empty state.
 */
@Composable
fun SearchScreen(session: AppSession, twoPane: Boolean, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val holder = remember(session) { SearchStateHolder(session, scope) }
    val lists = listOf(rememberLazyListState(), rememberLazyListState())
    val emptyList = rememberLazyListState()
    val pager = rememberPagerState(initialPage = holder.side.ordinal) { 2 }
    DisposableEffect(holder) {
        holder.onScrollToTop = {
            lists.forEach { it.requestScrollToItem(0) }
            emptyList.requestScrollToItem(0)
        }
        holder.start()
        onDispose { holder.close() }
    }

    val settings by session.settings.collectAsState()
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }
    val exitAnchor = remember { FocusRequester() }
    val handwriting = settings.inputMethod == InputMethod.HANDWRITING
    val cantonese = settings.dictionaryMode == DictMode.CANTONESE

    val displayed = holder.displayed
    // The outgoing results stay drawable while they cross-fade to the empty state (SRCH-112).
    val lastShown = remember { arrayOfNulls<DisplayedSearch>(1) }
    if (displayed != null) lastShown[0] = displayed
    val shown = displayed ?: lastShown[0]
    val resultsVisible = displayed != null

    // ---- pager <-> side (SRCH-180, SRCH-181) ----
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    val wasVisible = remember { booleanArrayOf(false) }
    LaunchedEffect(holder.side, resultsVisible) {
        val instant = !wasVisible[0]
        wasVisible[0] = resultsVisible
        if (!resultsVisible) return@LaunchedEffect
        val target = holder.side.ordinal
        if (pager.currentPage == target && !pager.isScrollInProgress) return@LaunchedEffect
        if (dragged) return@LaunchedEffect   // the drag wins; where it settles is a manual change
        if (instant) pager.scrollToPage(target) else pager.animateScrollToPage(target)
    }
    LaunchedEffect(pager, holder) {
        snapshotFlow { pager.isScrollInProgress to pager.settledPage }.collect { (moving, page) ->
            if (!moving && holder.displayed != null) holder.onManualSide(SearchSide.entries[page])
        }
    }

    // ---- input method (HW-003, HW-004, SRCH-121, SRCH-122) ----
    var keyboardShowTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(keyboardShowTick) {
        if (keyboardShowTick == 0) return@LaunchedEffect
        // The platform input session restarts when the interceptor changes; show once it has.
        withFrameNanos { }
        withFrameNanos { }
        keyboard?.show()
    }
    val showHandwriting = {
        session.handwritingSink = holder.handwritingSink
        keyboard?.hide()
        session.handwritingVisible = true
    }
    val showCurrentMethod = { if (handwriting) showHandwriting() else { keyboard?.show(); Unit } }
    val leaveField = {
        runCatching { exitAnchor.requestFocus() }
        keyboard?.hide()
        session.handwritingVisible = false
    }
    val openEntry: (EntryCard) -> Unit = { card ->
        leaveField()
        session.openEntry(card.id, session.mode)
    }

    // ---- clipboard (SRCH-124) ----
    val clipboard = session.env.clipboard
    val canPaste by clipboard.hasText.collectAsState(initial = false)
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { if (windowFocused) clipboard.refresh() }

    // ---- Word of the Day follows the local date (SRCH-115) ----
    LaunchedEffect(holder) {
        while (true) {
            delay(30_000)
            holder.refreshDay()
        }
    }

    val hasError = holder.error != null
    val plans = remember(shown, hasError) {
        shown?.let { listOf(buildPagePlan(it.chinese, hasError), buildPagePlan(it.english, hasError)) }
    }
    val activeList: () -> LazyListState = { if (holder.displayed != null) lists[pager.currentPage] else emptyList }
    val elevated by remember { derivedStateOf { activeList().canScrollBackward } }

    // ---- reselect: scroll to top (PROD-053) ----
    val reselect = session.reselect.value
    val initialReselect = remember { reselect }
    LaunchedEffect(reselect) {
        if (reselect != initialReselect && reselect.second == Destination.SEARCH) activeList().animateScrollToItem(0)
    }

    // ---- insets (GLB-072, GLB-073) ----
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(56.dp) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentPadding = PaddingValues(
        start = 16.dp, end = 16.dp,
        top = statusTop + 10.dp + headerHeight + 8.dp,
        bottom = 24.dp + maxOf(LocalSheetInset.current, imeBottom, navBottom),
    )

    val playing = session.playing.collectAsState()
    val selectedId = if (twoPane) (session.routes.lastOrNull() as? DetailRoute.Entry)?.entryId else null
    val context = CardContext(holder, session, playing, selectedId, openEntry)

    Box(modifier.fillMaxSize()) {
        // Focus parks here when a result is opened, so no input method reopens on return (GLB-059).
        Box(Modifier.size(1.dp).focusRequester(exitAnchor).focusable())

        Crossfade(targetState = resultsVisible, modifier = Modifier.fillMaxSize(), animationSpec = tween(180), label = "search-empty-results") { results ->
            if (results) {
                if (shown != null && plans != null) {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { it }) { page ->
                        ResultPage(
                            shown = shown,
                            side = if (page == 0) shown.chinese else shown.english,
                            plan = plans[page],
                            listState = lists[page],
                            contentPadding = contentPadding,
                            error = holder.error,
                            context = context,
                            onPassageTap = { offset ->
                                val passage = shown.result.chinese.passage
                                if (passage != null) {
                                    keyboard?.hide()
                                    session.openWordSheet(WordSheetRequest(holder, passage, offset, shown.mode))
                                }
                            },
                        )
                    }
                }
            } else {
                EmptyStatePage(holder, emptyList, contentPadding, cantonese, context, onSettings = {
                    leaveField()
                    session.openSettings()
                })
            }
        }

        SearchControl(
            field = holder.input,
            onFieldChange = holder::onFieldChange,
            cantonese = cantonese,
            handwriting = handwriting,
            showPronunciation = settings.showPronunciation,
            canPaste = canPaste,
            indicatorVisible = holder.query.isNotBlank() && (resultsVisible || holder.searching),
            chineseCount = displayed?.takeIf { holder.countsCurrent }?.result?.chinese?.count,
            englishCount = displayed?.takeIf { holder.countsCurrent }?.result?.english?.count,
            pageFraction = { pager.currentPage + pager.currentPageOffsetFraction },
            selectedPage = pager.currentPage,
            chips = if (resultsVisible) plans?.get(pager.currentPage)?.chips.orEmpty() else emptyList(),
            elevated = elevated,
            fieldFocus = fieldFocus,
            onFieldFocused = { if (handwriting) showHandwriting() },
            onFieldTapped = showCurrentMethod,
            onPaste = {
                val text = clipboard.readText()
                // No usable text: only refresh availability so the button disappears (SRCH-124).
                if (text.isNullOrBlank()) clipboard.refresh() else holder.paste(text)
            },
            onClear = {
                holder.clear()
                runCatching { fieldFocus.requestFocus() }
                showCurrentMethod()
            },
            onToggleInputMethod = {
                val next = if (handwriting) InputMethod.KEYBOARD else InputMethod.HANDWRITING
                session.updateSettings { it.copy(inputMethod = next) }
                runCatching { fieldFocus.requestFocus() }
                if (next == InputMethod.HANDWRITING) {
                    showHandwriting()
                } else {
                    session.handwritingVisible = false
                    keyboardShowTick++
                }
            },
            onTogglePronunciation = { session.updateSettings { it.copy(showPronunciation = !it.showPronunciation) } },
            onImeSearch = { keyboard?.hide() },   // SRCH-120
            onSelectPage = { page -> if (resultsVisible) holder.onManualSide(SearchSide.entries[page]) },
            onChip = { chip -> scope.launch { lists[pager.currentPage].animateScrollToItem(chip.itemIndex) } },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
                .zIndex(2f),
        )
    }
}

/** What every card on the screen needs: audio, selection, navigation and expander state. */
internal class CardContext(
    val holder: SearchStateHolder,
    val session: AppSession,
    private val playing: State<Any?>,
    val selectedId: Int?,
    val open: (EntryCard) -> Unit,
) {
    val playingKey: Any? get() = playing.value

    val audio: (EntryCard) -> CardAudio? = { card ->
        CardAudio(playing = playing.value == card.id) { session.play(card.id, card.audio, session.mode) }
    }

    /** Inside-words expander for [card] (SRCH-105); rows come from [entryId]'s `within` list. */
    fun inside(card: EntryCard, entryId: Int = card.id, word: String = card.headword): InsideWordsState? {
        if (card.insideCount <= 0 || card.hanziCount < 2) return null
        val key = InsideKey(word, card.insideCount)
        return InsideWordsState(
            expanded = holder.insideExpanded[key] == true,
            onToggle = { holder.toggleInside(key) },
            load = { holder.insideWords(entryId) },
            onOpen = open,   // SRCH-131: the row's entry, not the parent
            audio = audio,
        )
    }
}

// ---- page plan: the list items of one page and where each section starts ----

internal sealed interface Block {
    val count: Int get() = 1

    data object Error : Block
    data object NoMatches : Block
    data object Passage : Block
    data object Capped : Block
    class Header(val state: SectionState, val label: String) : Block
    class Groups(val state: SectionState) : Block { override val count get() = state.section.groups.size }
    class Combinations(val state: SectionState) : Block { override val count get() = state.section.combinations.size }
    class Rows(val state: SectionState) : Block { override val count get() = state.total }
}

internal class PagePlan(val blocks: List<Block>, val chips: List<CategoryChip>)

/** Section-label key for sections that carry one (SRCH-102, SRCH-064, SRCH-076); null = unlabelled. */
private fun headerLabel(kind: SectionKind, afterCombinations: Boolean): String? = when (kind) {
    SectionKind.GROUPS, SectionKind.MATCHES, SectionKind.ENGLISH -> null
    SectionKind.MORE_MATCHES -> if (afterCombinations) "More matches" else null
    SectionKind.COMBINATIONS -> "Combinations"
    SectionKind.PATTERN -> "Pattern"
    SectionKind.STARTS_WITH -> "Starts"
    SectionKind.CONTAINS -> "Contains"
    SectionKind.RELATED -> "Related matches"
    SectionKind.ENGLISH_CC_CANTO -> "Cantonese (CC-Canto)"
    SectionKind.ENGLISH_CC_CEDICT -> "Mandarin (CC-CEDICT)"
    SectionKind.ENGLISH_UNIHAN -> "Unicode (Unihan)"
}

private fun chipLabel(kind: SectionKind, sentence: Boolean): String = when (kind) {
    SectionKind.GROUPS -> if (sentence) "Words" else "Exact"
    SectionKind.MATCHES, SectionKind.ENGLISH -> "Exact"
    SectionKind.COMBINATIONS -> "Combinations"
    SectionKind.MORE_MATCHES, SectionKind.STARTS_WITH -> "Starts"
    SectionKind.PATTERN -> "Pattern"
    SectionKind.CONTAINS -> "Contains"
    SectionKind.RELATED -> "Related"
    SectionKind.ENGLISH_CC_CANTO -> "CC-Canto"
    SectionKind.ENGLISH_CC_CEDICT -> "CC-CEDICT"
    SectionKind.ENGLISH_UNIHAN -> "Unicode (Unihan)"
}

internal fun buildPagePlan(side: SideState, hasError: Boolean): PagePlan {
    val sections = side.sections.filter { it.total > 0 || it.section.groups.isNotEmpty() || it.section.combinations.isNotEmpty() }
    if (sections.isEmpty()) return PagePlan(listOf(Block.NoMatches), emptyList())   // SRCH-113
    val blocks = ArrayList<Block>()
    val chips = ArrayList<CategoryChip>()
    var index = 0
    fun add(block: Block) { blocks += block; index += block.count }
    if (hasError) add(Block.Error)
    val sentence = side.result.isSentence
    val passageIndex = if (sentence && side.result.passage != null) index.also { add(Block.Passage) } else -1
    var previous: SectionKind? = null
    for (state in sections) {
        val kind = state.section.kind
        val anchor = if (kind == SectionKind.GROUPS && passageIndex >= 0) passageIndex else index
        headerLabel(kind, previous == SectionKind.COMBINATIONS)?.let { add(Block.Header(state, it)) }
        val count = when (kind) {
            SectionKind.GROUPS -> { add(Block.Groups(state)); state.section.count }
            SectionKind.COMBINATIONS -> { add(Block.Combinations(state)); state.section.combinations.size }
            else -> { add(Block.Rows(state)); state.section.count }
        }
        chips += CategoryChip(chipLabel(kind, sentence), count, anchor)
        previous = kind
    }
    if (side.result.capped) add(Block.Capped)
    return PagePlan(blocks, chips)
}

private fun errorLine(strings: AppStrings, error: SearchError): String =
    strings.text("Unable to search") + (error.detail?.let { ": $it" } ?: "")   // GLB-034

@Composable
private fun sectionTitle(block: Block.Header, shown: DisplayedSearch): String {
    val strings = appStrings()
    val section = block.state.section
    val word = section.labelArg ?: shown.result.displayText
    return when (section.kind) {
        SectionKind.PATTERN -> strings.text(
            if (shown.mode == DictMode.CANTONESE) "Hanzi + Jyutping pattern matches" else "Hanzi + Pinyin pattern matches",
        )
        SectionKind.STARTS_WITH -> strings.text("Starts with 「{0}」", word)
        SectionKind.CONTAINS -> strings.text("Contains 「{0}」 elsewhere", word)
        else -> strings.text(block.label)
    }
}

// ---- result page ----

@Composable
private fun ResultPage(
    shown: DisplayedSearch,
    side: SideState,
    plan: PagePlan,
    listState: LazyListState,
    contentPadding: PaddingValues,
    error: SearchError?,
    context: CardContext,
    onPassageTap: (Int) -> Unit,
) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val session = context.session
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        plan.blocks.forEach { block ->
            when (block) {
                Block.NoMatches -> item(key = "none", contentType = "message") {
                    // SRCH-113: the error text if any, otherwise `No matches`; nothing about the other page.
                    Box(Modifier.fillParentMaxWidth().padding(vertical = 42.dp), contentAlignment = Alignment.Center) {
                        Text(
                            error?.let { errorLine(strings, it) } ?: strings.text("No matches"),
                            style = MaterialTheme.typography.titleMedium, color = palette.muted, textAlign = TextAlign.Center,
                        )
                    }
                }
                Block.Error -> item(key = "error", contentType = "message") {
                    // SRCH-172: the previous results stay; the failure is stated inline above them.
                    Text(
                        error?.let { errorLine(strings, it) }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium, color = palette.accent,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                Block.Passage -> item(key = "passage", contentType = "passage") {
                    val passage = side.result.passage ?: return@item
                    val text = passage.text
                    PassageCard(
                        passage = passage,
                        selectedOffset = session.selectionFor(context.holder, passage),
                        speaking = context.playingKey == SentenceKey(text),
                        onTap = onPassageTap,
                        onSpeak = { session.speak(SentenceKey(text), text, shown.mode) },
                        onTranslate = { session.env.translator.translate(text, translateTag(shown.mode, shown.result.script)) },
                        onCopy = { session.env.clipboard.writeText(text) },
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Block.Capped -> item(key = "capped", contentType = "message") {
                    Text(
                        strings.text("Showing the first {0} results", side.result.sections.sumOf { it.refs.size.coerceAtLeast(it.cards.size) }),
                        style = MaterialTheme.typography.bodySmall, color = palette.muted,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                is Block.Header -> item(key = "h:${block.state.side.ordinal}:${block.state.section.key}", contentType = "header") {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        SectionLabel(
                            text = sectionTitle(block, shown),
                            count = if (block.state.section.kind == SectionKind.COMBINATIONS) block.state.section.combinations.size else block.state.section.count,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                }
                is Block.Groups -> {
                    val groups = block.state.section.groups
                    items(count = groups.size, key = { "g:${block.state.section.key}:$it" }, contentType = { "group" }) { index ->
                        GroupRow(groups[index], side.result.isSentence, context)
                    }
                }
                is Block.Combinations -> {
                    val combinations = block.state.section.combinations
                    items(count = combinations.size, key = { "c:${block.state.section.key}:$it" }, contentType = { "combination" }) { index ->
                        CombinationCard(combinations[index], context.selectedId, context.open)
                    }
                }
                is Block.Rows -> sectionRows(block.state, context)
            }
        }
    }
}

/** Ranked rows of one section, hydrated in windows as the list scrolls (SRCH-073, SRCH-152). */
private fun LazyListScope.sectionRows(state: SectionState, context: CardContext) {
    items(count = state.total, key = { state.keys[it] }, contentType = { "card" }) { index ->
        val loaded = state.cards.size
        if (loaded < state.total && index + PREFETCH >= loaded) {
            LaunchedEffect(state, loaded) { context.holder.loadMore(state) }
        }
        if (index >= loaded) {
            CardPlaceholder()
        } else {
            val card = state.cards[index]
            if (card != null) {
                EntryCardView(
                    card = card,
                    onClick = { context.open(card) },
                    selected = card.id == context.selectedId,   // SRCH-096
                    audio = context.audio(card),
                    inside = context.inside(card),
                )
            }
        }
    }
}

/** One exact or segmented group (SRCH-033, SRCH-098, SRCH-099, SRCH-103). */
@Composable
private fun GroupRow(group: WordGroup, sentence: Boolean, context: CardContext) {
    if (group.inert || group.cards.isEmpty()) {
        InertRow(group.text)
        return
    }
    // One inside-words button per headword, after its last card, fed by the group's sub-spans.
    val insideOf = group.insideOf
    val last = group.cards.last().let { if (insideOf != null) it.copy(insideCount = group.insideCount) else it.copy(insideCount = 0) }
    val inside = if (insideOf != null) context.inside(last, insideOf, group.text) else null
    val cards = group.cards.dropLast(1) + last
    if (sentence && cards.size > 1) {
        EntryCardGroup(cards, context.open, selectedId = context.selectedId, audio = context.audio, inside = inside)
    } else {
        Column {
            cards.forEachIndexed { index, card ->
                EntryCardView(
                    card = card,
                    onClick = { context.open(card) },
                    selected = card.id == context.selectedId,
                    audio = context.audio(card),
                    inside = if (index == cards.lastIndex) inside else null,
                )
            }
        }
    }
}

/** MED-030 source language: `zh-CN` Simplified Mandarin, `zh-TW` Traditional Mandarin, `yue` Cantonese. */
private fun translateTag(mode: DictMode, script: Script): String = when {
    mode == DictMode.CANTONESE -> "yue"
    script == Script.SIMPLIFIED -> "zh-CN"
    else -> "zh-TW"
}

// ---- empty state (SRCH-110…116) ----

@Composable
private fun EmptyStatePage(
    holder: SearchStateHolder,
    listState: LazyListState,
    contentPadding: PaddingValues,
    cantonese: Boolean,
    context: CardContext,
    onSettings: () -> Unit,
) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val wordOfDay = holder.wordOfDay
    val popular = holder.popular
    val error = holder.error ?: holder.emptyError
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "hint", contentType = "hint") {
            Column {
                Text(
                    strings.text(
                        if (cantonese) "Type English, Jyutping (neihou, nh or nei5 hou2), Hanzi, or paste a sentence."
                        else "Type English, Pinyin (nihao, nh or nǐ hǎo), Hanzi, or paste a sentence.",
                    ),
                    style = MaterialTheme.typography.bodyMedium, color = palette.muted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                if (error != null) {
                    Text(errorLine(strings, error), style = MaterialTheme.typography.bodyMedium, color = palette.accent,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
        if (wordOfDay != null) {
            item(key = "wotd", contentType = "wotd") {
                Column {
                    EmptyHeading("Word of the Day")
                    EntryCardView(
                        card = wordOfDay,
                        onClick = { context.open(wordOfDay) },
                        selected = wordOfDay.id == context.selectedId,
                        audio = context.audio(wordOfDay),
                        inside = context.inside(wordOfDay),
                    )
                }
            }
        }
        item(key = "settings", contentType = "settings") { SettingsCard(onSettings, Modifier.padding(top = 6.dp)) }
        if (popular.isNotEmpty()) {
            item(key = "popular", contentType = "heading") { EmptyHeading("Popular words") }
            items(popular, key = { "p:${it.id}" }, contentType = { "card" }) { card ->
                EntryCardView(
                    card = card,
                    onClick = { context.open(card) },
                    selected = card.id == context.selectedId,
                    audio = context.audio(card),
                    inside = context.inside(card),
                )
            }
        }
    }
}
