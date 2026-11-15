package com.szdict.app.feature.entry

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.szdict.app.i18n.AppStrings
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.kit.ChipBarHorizontalPadding
import com.szdict.app.ui.kit.ChipBarVerticalPadding
import com.szdict.app.ui.kit.ExpandableFooter
import com.szdict.app.ui.kit.ExpanderToggle
import com.szdict.app.ui.kit.FollowSelectedChip
import com.szdict.app.ui.kit.LocalDisplayPrefs
import com.szdict.app.ui.kit.NavChip
import com.szdict.app.ui.kit.SectionLabel
import com.szdict.app.ui.kit.TonedHanziText
import com.szdict.app.ui.kit.TonedReadingText
import com.szdict.app.ui.kit.TranslucentChipBar
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.AppearsInItem
import com.szdict.core.api.CharacterParts
import com.szdict.core.api.DecompNode
import com.szdict.core.api.EntryCard
import com.szdict.core.api.PartKind
import com.szdict.core.api.PartNode
import com.szdict.core.api.PartPosition
import kotlinx.coroutines.launch

/**
 * The Parts section, last on the DICT tab: per character of the headword, what it is made of (its
 * components, each expandable level by level down to radicals and indivisible parts) and the
 * characters it appears in. Its chip row starts the section and scrolls in with it, then sticks to
 * the top of the list — which the section ends — while the blocks scroll beneath it.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.partsSectionItems(state: PartsState, actions: EntryActions, list: LazyListState) {
    item(key = "parts-label") {
        SectionLabel("PARTS", modifier = sidePadding.padding(top = 26.dp, bottom = 2.dp))
    }
    stickyHeader(key = "parts-nav") { PartsNav(state, list, actions) }
    state.characters.forEachIndexed { index, character ->
        item(key = PartsLayout.key(index, PartsSection.HEAD)) { CharacterHeading(state, index, character, actions) }
        item(key = PartsLayout.key(index, PartsSection.MADE_OF)) { MadeOf(state, character, actions) }
        item(key = PartsLayout.key(index, PartsSection.APPEARS_IN)) {
            Column {
                AppearsIn(state, character, actions)
                // Edge to edge between two characters' blocks, the same space above and below.
                if (index < state.characters.lastIndex) {
                    HorizontalDivider(Modifier.padding(top = BLOCK_GAP), thickness = Dp.Hairline, color = LocalDictionaryPalette.current.border)
                }
            }
        }
    }
}

/** Space on each side of the divider between two characters' blocks. */
private val BLOCK_GAP = 28.dp

private val sidePadding = Modifier.padding(horizontal = 16.dp)

private fun PartsState.partsOf(character: String): CharacterParts? =
    (made as? PartsLoad.Ready)?.characters?.firstOrNull { it.character == character }

// ---- Chip navigation (sticky and translucent: the HSK selector's bar and chips) ----

@Composable
private fun PartsNav(state: PartsState, list: LazyListState, actions: EntryActions) {
    val strings = appStrings()
    // Which sections are worth a chip needs every character's parts and "Appears in" total.
    // Reads run in the session scope: an item composed in passing (prefetch, a fling) and disposed
    // must not cancel a read the other items are waiting on.
    LaunchedEffect(state) {
        actions.session.scope.launch { state.ensureParts() }
        state.characters.forEach { c -> actions.session.scope.launch { state.ensureAppears(c) } }
    }
    val chips by remember(state) {
        derivedStateOf {
            val made = state.made
            val known = made !is PartsLoad.Loading && state.characters.all { c -> state.appears[c]?.let { it.loaded || it.unavailable } ?: true }
            if (!known) emptyList()
            else PartsLayout.chips(
                hasMadeOf = state.characters.map { c -> state.partsOf(c)?.root?.children?.isNotEmpty() == true },
                hasAppearsIn = state.characters.map { c -> state.appears[c]?.let { it.loaded && it.total > 0 } == true },
            )
        }
    }
    // One chip would have nowhere to jump to: no row at all. The sticky item keeps a hairline-thin
    // size so the list stays anchored on it, not on the heading below, when the row appears.
    if (chips.size < 2) {
        Spacer(Modifier.fillMaxWidth().height(1.dp))
        return
    }
    val scope = rememberCoroutineScope()
    val chipRow = rememberLazyListState()
    var navHeight by remember { mutableIntStateOf(0) }
    val multi = state.characters.size > 1
    // The block under the chip row decides the selected chip (the first one until the row sticks);
    // a tapped chip stays selected while the list scrolls to it.
    var requested by remember { mutableStateOf<Int?>(null) }
    val shown by remember(state, chips) {
        derivedStateOf {
            val first = list.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index > PartsLayout.NAV_INDEX && it.offset + it.size > navHeight + 1 }?.index ?: 0
            PartsLayout.selectedChip(chips, PartsLayout.at(first, state.characters.size))
        }
    }
    val selected = (requested ?: shown).coerceIn(0, chips.lastIndex)
    // Shadow only while stuck with content passing beneath; in flow the row is part of the page.
    val stuck by remember { derivedStateOf { PartsLayout.isStuck(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) } }
    var request by remember { mutableIntStateOf(0) }
    var jumping by remember { mutableStateOf(false) }
    // A block near the end cannot reach the top: its chip stays selected until the user scrolls.
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling -> if (scrolling && !jumping) requested = null }
    }
    FollowSelectedChip(chipRow, selected = selected, chipCount = chips.size)
    TranslucentChipBar(scrolledUnder = stuck, onBarHeight = { navHeight = it }) {
        LazyRow(
            state = chipRow,
            modifier = Modifier.fillMaxWidth().padding(vertical = ChipBarVerticalPadding),
            contentPadding = PaddingValues(horizontal = ChipBarHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(chips.size, key = { "chip-${chips[it].character}-${chips[it].section}" }) { i ->
                val chip = chips[i]
                val appears = chip.section == PartsSection.APPEARS_IN
                val section = strings.text(if (appears) "Appears in" else "Made of")
                val label = if (multi) "${state.characters[chip.character]} · $section" else section
                val count = if (appears) state.appears[state.characters[chip.character]]?.total else null
                NavChip(label = label, count = count, selected = i == selected, onClick = {
                    val token = ++request
                    requested = i
                    jumping = true
                    scope.launch {
                        try {
                            // The target lands just under the row, which sticks at the top.
                            list.animateScrollToItem(PartsLayout.chipTarget(chip), -navHeight)
                        } finally {
                            if (request == token) jumping = false
                        }
                    }
                })
            }
        }
    }
}

// ---- Character heading ----

/**
 * The character large, then (in a word, where the page header is not about this character) its
 * reading, provenance tag and first sense, and a link to its own entry — one entry pushed, several
 * offered in the candidate sheet; then its structure, radical, curated name and stroke count.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterHeading(state: PartsState, index: Int, character: String, actions: EntryActions) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val prefs = LocalDisplayPrefs.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { actions.session.scope.launch { state.ensureParts() } }
    val parts = state.partsOf(character)
    val root = parts?.root
    val node = root?.node
    val card = node?.card
    val word = state.characters.size > 1
    val entryIds = node?.entryIds.orEmpty().filter { it != actions.route.entryId }
    val open: (() -> Unit)? = if (!word || entryIds.isEmpty() || card == null) null else ({
        val ids = node!!.entryIds
        if (ids.size == 1) actions.openEntry(ids[0])
        else scope.launch {
            val cards = runCatching { actions.session.dictionary(actions.route.mode).entryCards(ids, actions.script) }.getOrDefault(emptyList())
            val ordered = cards.sortedBy { if (it.id == card.id) 0 else 1 }
            if (ordered.size > 1) actions.openCards(strings.text("Words at 「{0}」", character), character, ordered)
            else actions.openEntry(ordered.firstOrNull()?.id ?: ids[0])
        }
    })
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = if (index == 0) 0.dp else BLOCK_GAP - 4.dp)   // + the row's own 4 dp
            .then(if (open != null) Modifier.clickable(role = Role.Button, onClick = open) else Modifier)
            .semantics(mergeDescendants = true) { heading() }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (card != null) TonedHanziText(character, card.reading, prefs.toneColors && card.toneColourable, MaterialTheme.typography.displaySmall, color = palette.fg)
        else Text(character, style = MaterialTheme.typography.displaySmall, color = palette.fg)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (word && card != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    if (!card.noReading && !card.reading.isEmpty) TonedReadingText(card.reading, prefs.toneColors, MaterialTheme.typography.bodyLarge, color = palette.accent)
                    // Unihan-only or HSK-patch characters say where the sense comes from (ENT-126).
                    card.provenance?.let { Text(it.text(strings.lang), style = MaterialTheme.typography.labelSmall, color = palette.muted) }
                }
                card.definitions.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted) }
            }
            val facts = buildList {
                // In words: the IDS operators (⿰…) are not in every bundled font.
                node?.operator?.let { add(strings.text(operatorName(it))) }
                parts?.radical?.let { add(strings.text("radical {0}", it)) }
                node?.strokeCount?.let { add(strings.text("{0} strokes", it)) }
            }
            if (facts.isNotEmpty() || !node?.radicalName.isNullOrBlank()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                    // A character that is a radical itself carries its curated name (女: woman).
                    node?.radicalName?.takeIf { it.isNotBlank() }?.let { NameTag(it) }
                }
            }
        }
        if (open != null) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.muted, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun NameTag(text: String) {
    val palette = LocalDictionaryPalette.current
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = palette.muted,
        modifier = Modifier.background(palette.bgSoft, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---- Made of ----

@Composable
private fun MadeOf(state: PartsState, character: String, actions: EntryActions) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    Column(sidePadding.fillMaxWidth().padding(top = 16.dp)) {
        SectionLabel("Made of")
        Spacer(Modifier.height(6.dp))
        when (val load = state.made) {
            PartsLoad.Loading -> repeat(2) { SkeletonCard() }
            PartsLoad.Unavailable -> Note(strings.text("Not available"))
            is PartsLoad.Ready -> {
                val parts = load.characters.firstOrNull { it.character == character }?.takeIf { it.available }
                val root = parts?.root
                when {
                    root == null -> Note(strings.text("No parts data for this character"))
                    root.children.isEmpty() -> Note(strings.text("No smaller parts: {0} is indivisible", character))
                    else -> {
                        root.children.forEach { part -> PartCard { PartWithParts(state, character, part, depth = 0, actions) } }
                        // The radical when no listed part stands for it (年 = 𠂉 + 㐄, radical 干): named apart, quietly.
                        parts.radicalPart?.let { radical ->
                            PartCard(quiet = true) { PartRow(radical, depth = 0, actions, note = strings.text("Radical of {0}, not one of the parts listed", character)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PartCard(quiet: Boolean = false, content: @Composable () -> Unit) {
    val palette = LocalDictionaryPalette.current
    Surface(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (quiet) palette.bg else palette.panel,
        border = BorderStroke(1.dp, palette.border),
    ) {
        Column { content() }
    }
}

/** A part's row and, when it can be split further, the "+ N more parts within" footer with its parts beneath. */
@Composable
private fun PartWithParts(state: PartsState, character: String, part: PartNode, depth: Int, actions: EntryActions) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    PartRow(part, depth, actions)
    if (part.children.isEmpty()) return
    val glyph = partLabel(part)
    ExpandableFooter(
        label = strings.text("+ {0} more parts within", part.partCount),
        spoken = strings.text("Show {0} parts within {1}", part.partCount, glyph),
        expanded = state.isExpanded(character, part),
        onToggle = { state.toggle(character, part) },
    ) {
        part.children.forEach { child ->
            HorizontalDivider(color = palette.border)
            // One indent step per level behind a 2 dp bar; capped so deep trees keep their width.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = (10 + 8 * minOf(depth, 3)).dp)) {
                Box(Modifier.padding(vertical = 8.dp).width(2.dp).fillMaxHeight().background(palette.border))
                Column(Modifier.weight(1f)) { PartWithParts(state, character, child, depth + 1, actions) }
            }
        }
    }
}

private fun partLabel(part: PartNode): String = when {
    part.isGroup -> part.children.joinToString("") { partLabel(it) }
    else -> displayGlyph(part.node)
}

private fun isTappable(part: PartNode): Boolean =
    !part.isGroup && part.node.entryIds.isNotEmpty() && (part.node.kind == PartKind.CHARACTER || part.node.kind == PartKind.RADICAL_FORM)

/** Glyph, reading, short meaning and marks of one part; opens the part's entry when it has one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartRow(part: PartNode, depth: Int, actions: EntryActions, note: String? = null) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val prefs = LocalDisplayPrefs.current
    val node = part.node
    val card: EntryCard? = node.card.takeIf { !part.isGroup }
    val tappable = isTappable(part)
    val glyphStyle = if (depth == 0) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (tappable) Modifier.clickable(role = Role.Button) { actions.openEntry(card?.id ?: node.entryIds[0]) } else Modifier)
            .semantics(mergeDescendants = true) {}
            .heightIn(min = 48.dp)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.widthIn(min = if (depth == 0) 44.dp else 36.dp), contentAlignment = Alignment.Center) {
            when {
                part.isGroup -> Text(partLabel(part), style = MaterialTheme.typography.titleMedium, color = palette.fg)
                node.kind == PartKind.UNKNOWN || node.kind == PartKind.UNENCODED -> ComponentPlaceholder(depth)
                card != null -> TonedHanziText(node.glyph, card.reading, prefs.toneColors && card.toneColourable && node.mapsTo == null, glyphStyle, color = palette.fg)
                else -> Text(node.glyph, style = glyphStyle, color = palette.fg, textAlign = TextAlign.Center)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                if (card != null && !card.noReading && !card.reading.isEmpty) {
                    TonedReadingText(card.reading, prefs.toneColors, MaterialTheme.typography.bodyLarge, color = palette.accent)
                }
                node.mapsTo?.takeIf { it != node.glyph }?.let { Text("= $it", style = MaterialTheme.typography.bodyMedium, color = palette.muted) }
                node.position?.let { Text(positionLabel(it, strings), style = MaterialTheme.typography.labelSmall, color = palette.muted) }
                PartTags(part)
            }
            if (node.kind == PartKind.UNENCODED) {
                // What the sources say the piece is ("bottom of 年"), then what it is not: no error look.
                node.radicalName?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.fg) }
                Text(strings.text("Component without a character of its own"), style = MaterialTheme.typography.bodySmall, color = palette.muted)
            } else {
                val gloss = when {
                    part.isGroup -> node.operator?.let { strings.text(operatorName(it)) }?.let { strings.text("Group of parts, {0}", it) }
                    node.kind == PartKind.UNKNOWN -> strings.text("Unknown part")
                    else -> card?.definitions?.firstOrNull()
                }
                gloss?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted) }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.muted) }
        }
    }
}

/** Radical marks (the parent's radical in the accent colour) and curated component names. */
@Composable
private fun PartTags(part: PartNode) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    // An unencoded component's name is its description, shown as its gloss.
    val name = part.node.radicalName?.takeIf { it.isNotBlank() && part.node.kind != PartKind.UNENCODED }
    val text = when {
        part.isRadical || part.isParentRadical -> name?.let { strings.text("radical {0}", it) } ?: strings.text("Radical")
        else -> name
    } ?: return
    val strong = part.isParentRadical
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (strong) FontWeight.SemiBold else null,
        color = if (strong) palette.accent else palette.muted,
        modifier = Modifier
            .background(if (strong) palette.accentSoft else palette.bgSoft, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---- Appears in ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearsIn(state: PartsState, character: String, actions: EntryActions) {
    val strings = appStrings()
    val section = state.appears[character] ?: return
    LaunchedEffect(section) { actions.session.scope.launch { state.ensureAppears(character) } }
    Column(sidePadding.fillMaxWidth().padding(top = 20.dp)) {
        SectionLabel("Appears in", count = section.total.takeIf { section.loaded && it > 0 })
        Spacer(Modifier.height(8.dp))
        when {
            section.unavailable -> Note(strings.text("Not available"))
            !section.loaded -> SkeletonCard()
            section.total == 0 -> Note(strings.text("Not a part of any other character"))
            else -> {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // Two columns on a phone, more as the pane widens; glosses wrap in full and the
                    // tiles of a row share its height.
                    val gap = 8.dp
                    val columns = maxOf(2, ((maxWidth + gap) / (APPEARS_TILE_MIN + gap)).toInt())
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        section.items.chunked(columns).forEach { row ->
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                                row.forEach { AppearsTile(it, Modifier.weight(1f).fillMaxHeight(), actions) }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                if (section.hasMore) {
                    Box(Modifier.padding(top = 10.dp).alpha(if (section.loadingMore) 0.5f else 1f)) {
                        ExpanderToggle(
                            label = strings.text("Show {0} more", minOf(section.remaining, PartsState.PAGE_SIZE)),
                            expanded = false,
                            onToggle = { actions.session.scope.launch { state.loadMore(character) } },
                        )
                    }
                }
            }
        }
    }
}

private val APPEARS_TILE_MIN = 168.dp

/**
 * One character built from the part: the glyph with its reading beside it, then the gloss wrapped
 * to as many lines as it needs; inert (muted, on the recessed tone) without an entry.
 */
@Composable
private fun AppearsTile(item: AppearsInItem, modifier: Modifier, actions: EntryActions) {
    val palette = LocalDictionaryPalette.current
    val prefs = LocalDisplayPrefs.current
    val card = item.card
    val shape = RoundedCornerShape(10.dp)
    val border = BorderStroke(1.dp, palette.border)
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (card != null) TonedHanziText(item.character, card.reading, prefs.toneColors && card.toneColourable, MaterialTheme.typography.headlineSmall, color = palette.fg)
                else Text(item.character, style = MaterialTheme.typography.headlineSmall, color = palette.muted)
                if (card != null && !card.noReading && !card.reading.isEmpty) {
                    TonedReadingText(card.reading, prefs.toneColors, MaterialTheme.typography.bodyMedium, color = palette.accent)
                }
            }
            card?.definitions?.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.muted) }
        }
    }
    if (card != null) Surface(onClick = { actions.openEntry(card.id) }, modifier = modifier, shape = shape, color = palette.panel, border = border, content = content)
    else Surface(modifier = modifier, shape = shape, color = palette.bgSoft, border = border, content = content)
}

// ---- Small pieces ----

/** Stand-in glyph of a component with no code point: a quiet rounded square, not a question mark. */
@Composable
private fun ComponentPlaceholder(depth: Int) {
    val palette = LocalDictionaryPalette.current
    val size = if (depth == 0) 30.dp else 24.dp
    Box(
        Modifier
            .size(size)
            .background(palette.bgSoft, RoundedCornerShape(6.dp))
            .border(BorderStroke(1.dp, palette.border), RoundedCornerShape(6.dp)),
    )
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalDictionaryPalette.current.muted, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun SkeletonCard() {
    val palette = LocalDictionaryPalette.current
    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(64.dp), shape = MaterialTheme.shapes.medium, color = palette.panel,
        border = BorderStroke(1.dp, palette.border)) {
        Row(Modifier.padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SkeletonBar(36.dp, 36.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBar(90.dp, 14.dp)
                SkeletonBar(150.dp, 12.dp)
            }
        }
    }
}

// ---- Labels ----

internal fun displayGlyph(node: DecompNode): String = when (node.kind) {
    PartKind.UNKNOWN -> "？"
    PartKind.UNENCODED -> "▢"
    else -> node.glyph
}

internal fun positionLabel(position: PartPosition, strings: AppStrings): String = strings.text(
    when (position) {
        PartPosition.LEFT -> "left"
        PartPosition.RIGHT -> "right"
        PartPosition.TOP -> "top"
        PartPosition.BOTTOM -> "bottom"
        PartPosition.MIDDLE -> "middle"
        PartPosition.OUTER -> "outer"
        PartPosition.INNER -> "inner"
        PartPosition.PART -> "part"
    },
)

/** Spoken name of an IDS operator. */
internal fun operatorName(operator: String?): String = when (operator) {
    "⿰" -> "left to right"
    "⿱" -> "top to bottom"
    "⿲" -> "left, middle, right"
    "⿳" -> "top, middle, bottom"
    "⿴" -> "surrounding"
    "⿵" -> "surrounding from above"
    "⿶" -> "surrounding from below"
    "⿷" -> "surrounding from the left"
    "⿸" -> "surrounding from the upper left"
    "⿹" -> "surrounding from the upper right"
    "⿺" -> "surrounding from the lower left"
    "⿻" -> "overlaid"
    else -> "combined"
}
