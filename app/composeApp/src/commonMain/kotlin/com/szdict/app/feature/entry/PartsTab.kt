package com.szdict.app.feature.entry

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.kit.ExpandableFooter
import com.szdict.app.ui.kit.ExpanderToggle
import com.szdict.app.ui.kit.LocalDisplayPrefs
import com.szdict.app.ui.kit.SectionLabel
import com.szdict.app.ui.kit.TonedHanziText
import com.szdict.app.ui.kit.TonedReadingText
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.AppearsInItem
import com.szdict.core.api.CharacterParts
import com.szdict.core.api.EntryCard
import com.szdict.core.api.PartKind
import com.szdict.core.api.PartNode
import kotlinx.coroutines.launch

/**
 * PARTS tab: per character of the headword, what it is made of (its components, each expandable
 * level by level down to radicals and indivisible parts) and the characters it appears in, with a
 * sticky chip row to jump between characters and sections.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.partsTabItems(state: PartsTabState, actions: EntryActions, list: LazyListState) {
    stickyHeader(key = "parts-nav") { PartsNav(state, list) }
    state.characters.forEachIndexed { index, character ->
        item(key = PartsLayout.key(index, PartsSection.HEAD)) { CharacterHeading(state, index, character) }
        item(key = PartsLayout.key(index, PartsSection.MADE_OF)) { MadeOf(state, character, actions) }
        item(key = PartsLayout.key(index, PartsSection.APPEARS_IN)) { AppearsIn(state, character, actions) }
    }
}

private val sidePadding = Modifier.padding(horizontal = 16.dp)

private fun PartsTabState.partsOf(character: String): CharacterParts? =
    (made as? PartsLoad.Ready)?.characters?.firstOrNull { it.character == character }

// ---- Chip navigation (sticky, like the HSK level chips) ----

@Composable
private fun PartsNav(state: PartsTabState, list: LazyListState) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val scope = rememberCoroutineScope()
    var navHeight by remember { mutableIntStateOf(0) }
    val multi = state.characters.size > 1
    // The block under the chip row decides the selected chips; a tapped chip stays selected while it scrolls.
    var requested by remember { mutableStateOf<Pair<Int, PartsSection>?>(null) }
    val shown by remember(state) {
        derivedStateOf {
            val first = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index > 0 && it.offset + it.size > navHeight + 1 }?.index ?: 1
            PartsLayout.at(first, state.characters.size, hasNav = true)
        }
    }
    val (character, section) = requested ?: shown
    var request by remember { mutableIntStateOf(0) }
    var jumping by remember { mutableStateOf(false) }
    // A block near the end cannot reach the top: its chip stays selected until the user scrolls.
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling -> if (scrolling && !jumping) requested = null }
    }
    fun go(target: Pair<Int, PartsSection>) {
        val token = ++request
        requested = target
        jumping = true
        scope.launch {
            try {
                list.animateScrollToItem(PartsLayout.itemIndex(target.first, target.second, hasNav = true), -navHeight)
            } finally {
                if (request == token) jumping = false
            }
        }
    }
    Surface(Modifier.fillMaxWidth().onSizeChanged { navHeight = it.height }, color = palette.bg) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (multi) {
                state.characters.forEachIndexed { index, c ->
                    item(key = "char-$index") {
                        PartsChip(
                            selected = index == character,
                            description = strings.text("Jump to {0}", c),
                            onClick = { go(index to PartsSection.HEAD) },
                        ) { Text(c, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (index == character) palette.accent else palette.fg) }
                    }
                }
                item(key = "divider") { Box(Modifier.width(1.dp).height(24.dp).background(palette.border)) }
            }
            item(key = "made-of") {
                PartsChip(selected = section != PartsSection.APPEARS_IN, description = null, onClick = { go(character to PartsSection.MADE_OF) }) {
                    Text(strings.text("Made of"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        color = if (section != PartsSection.APPEARS_IN) palette.accent else palette.muted, maxLines = 1)
                }
            }
            item(key = "appears-in") {
                val count = state.appears[state.characters.getOrNull(character)]?.takeIf { it.loaded && it.total > 0 }?.total
                PartsChip(selected = section == PartsSection.APPEARS_IN, description = null, onClick = { go(character to PartsSection.APPEARS_IN) }) {
                    Text(strings.text("Appears in"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        color = if (section == PartsSection.APPEARS_IN) palette.accent else palette.muted, maxLines = 1)
                    count?.let { Text(it.toString(), style = MaterialTheme.typography.labelSmall, color = palette.muted, maxLines = 1) }
                }
            }
        }
    }
}

/** The HSK level chip's shape and states (HskScreen `LevelChip`), in the accent colour. */
@Composable
private fun PartsChip(selected: Boolean, description: String?, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val palette = LocalDictionaryPalette.current
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            color = if (selected) palette.accentSoft else palette.panel,
            border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) palette.accent else palette.border),
            modifier = Modifier.height(36.dp).semantics {
                role = Role.Tab
                this.selected = selected
                description?.let { contentDescription = it }
            },
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                content = content,
            )
        }
    }
}

// ---- Character heading ----

@Composable
private fun CharacterHeading(state: PartsTabState, index: Int, character: String) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val prefs = LocalDisplayPrefs.current
    LaunchedEffect(state) { state.ensureParts() }
    val parts = state.partsOf(character)
    val root = parts?.root
    val card = root?.node?.card
    Column(sidePadding.fillMaxWidth().padding(top = if (index == 0) 4.dp else 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (card != null) TonedHanziText(character, card.reading, prefs.toneColors && card.toneColourable, MaterialTheme.typography.displaySmall, color = palette.fg)
            else Text(character, style = MaterialTheme.typography.displaySmall, color = palette.fg)
            Column(Modifier.weight(1f)) {
                if (state.characters.size > 1 && card != null) {
                    if (!card.noReading && !card.reading.isEmpty) TonedReadingText(card.reading, prefs.toneColors, MaterialTheme.typography.bodyLarge, color = palette.accent)
                    card.definitions.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                val facts = buildList {
                    // In words: the IDS operators (⿰…) are not in every bundled font.
                    root?.node?.operator?.let { add(strings.text(operatorName(it))) }
                    parts?.radical?.let { add(strings.text("radical {0}", it)) }
                    root?.node?.strokeCount?.let { add(strings.text("{0} strokes", it)) }
                }
                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = palette.muted)
            }
        }
    }
}

// ---- Made of ----

@Composable
private fun MadeOf(state: PartsTabState, character: String, actions: EntryActions) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    Column(sidePadding.fillMaxWidth().padding(top = 16.dp)) {
        SectionLabel("Made of")
        Spacer(Modifier.height(6.dp))
        when (val load = state.made) {
            PartsLoad.Loading -> repeat(2) { SkeletonCard() }
            PartsLoad.Unavailable -> Note(strings.text("Not available"))
            is PartsLoad.Ready -> {
                val root = load.characters.firstOrNull { it.character == character }?.takeIf { it.available }?.root
                when {
                    root == null -> Note(strings.text("No parts data for this character"))
                    root.children.isEmpty() -> Note(strings.text("No smaller parts: {0} is indivisible", character))
                    else -> root.children.forEach { part ->
                        Surface(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = palette.panel,
                            border = BorderStroke(1.dp, palette.border),
                        ) {
                            Column { PartWithParts(state, character, part, depth = 0, actions) }
                        }
                    }
                }
            }
        }
    }
}

/** A part's row and, when it can be split further, the "+ N more parts within" footer with its parts beneath. */
@Composable
private fun PartWithParts(state: PartsTabState, character: String, part: PartNode, depth: Int, actions: EntryActions) {
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
private fun PartRow(part: PartNode, depth: Int, actions: EntryActions) {
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
                node.kind == PartKind.UNKNOWN || node.kind == PartKind.UNENCODED -> Text(displayGlyph(node), style = glyphStyle, color = palette.muted)
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
            val gloss = when {
                part.isGroup -> node.operator?.let { strings.text(operatorName(it)) }?.let { strings.text("Group of parts, {0}", it) }
                node.kind == PartKind.UNENCODED -> strings.text("Unencoded component")
                node.kind == PartKind.UNKNOWN -> strings.text("Unknown part")
                else -> card?.definitions?.firstOrNull()
            }
            gloss?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Radical marks (the parent's radical in the accent colour) and curated component names. */
@Composable
private fun PartTags(part: PartNode) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val name = part.node.radicalName?.takeIf { it.isNotBlank() }
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
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(if (strong) palette.accentSoft else palette.bgSoft, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---- Appears in ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearsIn(state: PartsTabState, character: String, actions: EntryActions) {
    val strings = appStrings()
    val section = state.appears[character] ?: return
    LaunchedEffect(section) { state.ensureAppears(character) }
    Column(sidePadding.fillMaxWidth().padding(top = 20.dp)) {
        SectionLabel("Appears in", count = section.total.takeIf { section.loaded && it > 0 })
        Spacer(Modifier.height(8.dp))
        when {
            section.unavailable -> Note(strings.text("Not available"))
            !section.loaded -> SkeletonCard()
            section.total == 0 -> Note(strings.text("Not a part of any other character"))
            else -> {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val gap = 8.dp
                    val columns = maxOf(3, ((maxWidth + gap) / (96.dp + gap)).toInt())
                    val tile = (maxWidth - gap * (columns - 1)) / columns
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                        section.items.forEach { AppearsTile(it, Modifier.width(tile), actions) }
                    }
                }
                if (section.hasMore) {
                    Box(Modifier.padding(top = 10.dp).alpha(if (section.loadingMore) 0.5f else 1f)) {
                        ExpanderToggle(
                            label = strings.text("Show {0} more", minOf(section.remaining, PartsTabState.PAGE_SIZE)),
                            expanded = false,
                            onToggle = { actions.session.scope.launch { state.loadMore(character) } },
                        )
                    }
                }
            }
        }
    }
}

/** One character built from the part: glyph, reading and gloss; inert (muted) without an entry. */
@Composable
private fun AppearsTile(item: AppearsInItem, modifier: Modifier, actions: EntryActions) {
    val palette = LocalDictionaryPalette.current
    val prefs = LocalDisplayPrefs.current
    val card = item.card
    val shape = RoundedCornerShape(10.dp)
    val border = BorderStroke(1.dp, palette.border)
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (card != null) TonedHanziText(item.character, card.reading, prefs.toneColors && card.toneColourable, MaterialTheme.typography.headlineSmall, color = palette.fg)
            else Text(item.character, style = MaterialTheme.typography.headlineSmall, color = palette.muted)
            if (card != null && !card.noReading && !card.reading.isEmpty) {
                TonedReadingText(card.reading, prefs.toneColors, MaterialTheme.typography.bodySmall, color = palette.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                card?.definitions?.firstOrNull() ?: "—",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
    if (card != null) Surface(onClick = { actions.openEntry(card.id) }, modifier = modifier, shape = shape, color = palette.panel, border = border, content = content)
    else Surface(modifier = modifier, shape = shape, color = palette.bgSoft, border = border, content = content)
}

// ---- Small pieces ----

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
