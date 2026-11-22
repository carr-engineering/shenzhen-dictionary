package com.szdict.app.feature.entry

import com.szdict.app.ui.kit.WordLookup
import com.szdict.app.ui.kit.passageOffsetAt
import com.szdict.app.ui.kit.wordHover
import com.szdict.app.ui.kit.WithTooltip
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.szdict.app.WordSheetRequest
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.kit.ExpanderToggle
import com.szdict.app.ui.kit.LocalDisplayPrefs
import com.szdict.app.ui.kit.SectionLabel
import com.szdict.app.ui.kit.TonedReadingText
import com.szdict.app.ui.kit.codePointLength
import com.szdict.app.ui.kit.passageAnnotatedString
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.BlockKind
import com.szdict.core.api.DictMode
import com.szdict.core.api.EntryDetail
import com.szdict.core.api.Example
import com.szdict.core.api.MemberSection
import com.szdict.core.api.Script
import com.szdict.core.api.SegmentationMode
import com.szdict.core.api.Sense
import com.szdict.core.api.SourceBlock
import kotlin.coroutines.cancellation.CancellationException

/**
 * DICT tab content in ENT-040 order: senses and notes, Examples, then the Parts section ([parts],
 * null for a headword without Hanzi). Item positions are [PartsLayout]'s; no chip row at the top —
 * the Parts chip row starts the Parts section.
 */
internal fun LazyListScope.dictTabItems(
    detail: EntryDetail,
    derived: EntryDerived,
    parts: PartsState?,
    actions: EntryActions,
    list: LazyListState,
) {
    item(key = "dict-senses") {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            MemberSections(detail, actions)
        }
    }
    item(key = "dict-examples") { ExamplesSection(detail, derived, actions, Modifier.padding(horizontal = 16.dp)) }
    // ENT-127 (v2): a Grammar section slots in here, between Examples and Parts.
    if (parts != null) partsSectionItems(parts, actions, list)
}

/** One section per member with definitions (ENT-042…044); a non-merged entry has exactly one. */
@Composable
private fun MemberSections(detail: EntryDetail, actions: EntryActions) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val expanded = rememberSaveableKeySet()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        detail.members.forEachIndexed { memberIndex, member ->
            if (detail.merged && member.alternateForm != null) {
                // The label names the member's alternate-script form: `Traditional: 髮` (ENT-042).
                val scriptName = if (detail.script == Script.SIMPLIFIED) strings.text("Traditional") else strings.text("Simplified")
                SectionLabel("$scriptName: ${member.alternateForm}", modifier = Modifier.padding(top = if (memberIndex > 0) 6.dp else 0.dp))
            }
            member.redirect?.let { redirect ->
                val prose = remember(redirect) { redirectProse(redirect.segments, redirect.text) }
                ProseText(prose, style = MaterialTheme.typography.bodyMedium, color = palette.muted, onRef = actions.onRef)
            }
            member.blocks.forEachIndexed { blockIndex, block ->
                val key = "$memberIndex:$blockIndex"
                // ENT-111: saved flag stores the blocks toggled away from their default.
                val open = !block.collapsible || (block.collapsedByDefault == (key in expanded.value))
                BlockView(block, open, onToggle = { expanded.toggle(key) }, actions = actions)
            }
            MemberNotes(member, detail, actions)
        }
    }
}

@Composable
private fun MemberNotes(member: MemberSection, detail: EntryDetail, actions: EntryActions) {
    val strings = appStrings()
    val notes = remember(member, detail.altReadings) { visibleNotes(member.notes, detail.altReadings) }
    if (notes.isEmpty()) return
    val prose = remember(notes, strings.lang) { notesProse(notes, strings.lang) }
    ProseText(prose, style = MaterialTheme.typography.bodyMedium, color = LocalDictionaryPalette.current.muted, onRef = actions.onRef,
        modifier = Modifier.padding(top = 4.dp))
}

/** A provenance block (ENT-110…116, ENT-126): label row, optional Pinyin, collapsible Mandarin block. */
@Composable
private fun BlockView(block: SourceBlock, open: Boolean, onToggle: () -> Unit, actions: EntryActions) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val prefs = LocalDisplayPrefs.current
    val singleGroup = block.readingGroups.singleOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (block.showLabel) {
            val stateText = if (open) strings.text("Expanded") else strings.text("Collapsed")
            val rowModifier = if (block.collapsible) {
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onToggle)
                    .semantics { stateDescription = stateText }
            } else Modifier.fillMaxWidth().padding(top = 4.dp)
            Row(rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(
                    block.label.text(strings.lang),
                    count = if (block.collapsible) block.senses.size else null,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // ENT-110/112: one Mandarin entry → its Pinyin sits on the label row.
                if (block.kind == BlockKind.MANDARIN && singleGroup != null && !singleGroup.reading.isEmpty) {
                    TonedReadingText(singleGroup.reading, prefs.toneColors, MaterialTheme.typography.bodyMedium, color = palette.accent)
                }
                if (block.collapsible) {
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = palette.muted,
                        modifier = Modifier.size(22.dp).rotate(if (open) 180f else 0f))
                }
            }
        }
        // Collapsed senses leave the composition, so they are not focusable (ENT-111).
        AnimatedVisibility(open, enter = expandVertically(tween(180)) + fadeIn(tween(180)), exit = shrinkVertically(tween(180)) + fadeOut(tween(120))) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (block.readingGroups.size > 1) {
                    // Several Mandarin entries: each introduced by its own Pinyin (ENT-112, ENT-113).
                    block.readingGroups.forEach { group ->
                        TonedReadingText(group.reading, prefs.toneColors, MaterialTheme.typography.titleSmall, color = palette.accent, modifier = Modifier.padding(top = 2.dp))
                        SenseList(group.senses, actions)
                    }
                } else {
                    SenseList(block.senses, actions)
                }
            }
        }
    }
}

/** Numbered senses, never truncated (ENT-041); numbering is continuous as stored (ENT-043). */
@Composable
private fun SenseList(senses: List<Sense>, actions: EntryActions) {
    val lang = appStrings().lang
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        senses.forEach { sense ->
            val prose = remember(sense, lang) { senseProse(sense.number, sense.tags.map { it.text(lang) }, sense.segments, sense.text) }
            ProseText(prose, onRef = actions.onRef)
        }
    }
}

// ---- Examples (MED-040…048) ----

@Composable
private fun ExamplesSection(detail: EntryDetail, derived: EntryDerived, actions: EntryActions, modifier: Modifier = Modifier) {
    val strings = appStrings()
    // Read after the entry has rendered; absence is silent (GLB-035, ENT-108).
    LaunchedEffect(derived) {
        if (derived.examples != null) return@LaunchedEffect
        derived.examples = try {
            actions.session.dictionary(actions.route.mode).examples(detail.id, detail.script).items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
    val examples = derived.examples.orEmpty()
    if (examples.isEmpty()) return
    // MED-045: remembered per (headword, example count) while the route lives.
    var showAll by rememberSaveable(detail.card.headword, examples.size) { mutableStateOf(false) }
    val visible = if (showAll) examples else examples.take(5)
    Column(modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(strings.text("Examples"), count = examples.size)
        visible.forEach { example -> ExampleRow(example, derived, actions) }
        if (examples.size > visible.size) {
            ExpanderToggle(strings.text("{0} more examples", examples.size - visible.size), expanded = false, onToggle = { showAll = true })
        } else if (showAll && examples.size > 5) {
            ExpanderToggle(strings.text("Show fewer"), expanded = true, onToggle = { showAll = false })
        }
    }
}

@Composable
private fun ExampleRow(example: Example, derived: EntryDerived, actions: EntryActions) {
    val palette = LocalDictionaryPalette.current
    val prefs = LocalDisplayPrefs.current
    val session = actions.session
    val mode = actions.route.mode
    val owner = "entry-example-${actions.route.key}"
    var passage by remember(example) { mutableStateOf(derived.passages[example.id]) }
    LaunchedEffect(example) {
        if (passage != null) return@LaunchedEffect
        val analysed = try {
            session.dictionary(mode).analyse(example.text, SegmentationMode.HANZI)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (analysed != null) {
            derived.passages[example.id] = analysed
            passage = analysed
        }
    }
    val current = passage
    val selected = session.selectionFor(owner, current)
    val text = remember(example, current, selected, prefs.toneColors, palette) {
        exampleAnnotatedString(example, current, selected, prefs.toneColors, palette) { offset ->
            current?.let { session.toggleWordSheet(WordSheetRequest(owner, it, it.normaliseOffset(offset), mode, nested = true)) }
        }
    }
    Column {
        // Long press selects; a tap on a character opens the word sheet for that offset (MED-043/044).
        SelectionContainer {
            var layout by remember(example) { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 33.sp),
                color = palette.fg,
                onTextLayout = { layout = it },
                modifier = Modifier.wordHover(current) { position ->
                    val source = current ?: return@wordHover null
                    layout?.let { passageOffsetAt(it, source, position) }?.let { WordLookup(source, it) }
                },
            )
        }
        SelectionContainer { Text(example.translation, style = MaterialTheme.typography.bodyMedium, color = palette.muted) }
        SentenceActionsRow(example.text, key = "entry-example-${actions.route.key}-${example.id}", actions = actions, modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * Example sentence text: tone colours from the analysed passage when present, every headword
 * occurrence highlighted (MED-042), and one tap range per covered code point (MED-043).
 */
internal fun exampleAnnotatedString(
    example: Example,
    passage: AnalysedPassage?,
    selected: Int?,
    toneColors: Boolean,
    palette: com.szdict.app.ui.theme.DictionaryPalette,
    onTap: (Int) -> Unit,
) = buildAnnotatedString {
    val text = example.text
    if (passage != null && passage.text == text) {
        append(passageAnnotatedString(passage, null, toneColors, palette, ruby = false))
    } else {
        append(text)
    }
    for (range in example.highlights) {
        val start = range.start.coerceIn(0, text.length)
        val end = range.end.coerceIn(start, text.length)
        if (end > start) addStyle(SpanStyle(background = palette.hlBg), start, end)
    }
    if (passage != null && passage.text == text) {
        for (offset in exampleTapOffsets(text, passage.coveredOffsets)) {
            addLink(
                // Explicit empty styles: a character keeps its tone colour and gets no link decoration.
                LinkAnnotation.Clickable(tag = "example-hanzi-$offset", styles = TextLinkStyles(style = SpanStyle()), linkInteractionListener = { onTap(offset) }),
                offset, offset + codePointLength(text, offset),
            )
        }
        selected?.takeIf { it in text.indices }?.let {
            addStyle(SpanStyle(background = palette.accentSoft), it, it + codePointLength(text, it))
        }
    }
}

/** Tap targets of an example: every Hanzi code-point start, covered or not (MED-043). */
internal fun exampleTapOffsets(text: String, covered: List<Int>): List<Int> {
    val out = ArrayList<Int>()
    val coveredSet = covered.toHashSet()
    var i = 0
    while (i < text.length) {
        val len = if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
        val cp = if (len == 2) 0x10000 + ((text[i].code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) else text[i].code
        if (i in coveredSet || isHanziCodePoint(cp)) out += i
        i += len
    }
    return out
}

internal fun isHanziCodePoint(cp: Int): Boolean =
    cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x323AF || cp in 0xF900..0xFAFF || cp == 0x3007

// ---- Sentence actions (MED-020…035) ----

/** Source language of the translate hand-off (MED-030). */
internal fun translateSourceTag(mode: DictMode, script: Script): String = when {
    mode == DictMode.CANTONESE -> "yue"
    script == Script.TRADITIONAL -> "zh-TW"
    else -> "zh-CN"
}

/** Play/stop (TTS), translate hand-off, copy: 40 dp outlined accent buttons (MED-020). */
@Composable
internal fun SentenceActionsRow(text: String, key: Any, actions: EntryActions, modifier: Modifier = Modifier) {
    val strings = appStrings()
    val session = actions.session
    val playing by session.playing.collectAsState()
    val enabled = text.isNotBlank()
    val speaking = playing == key
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionButton(strings.text(if (speaking) "Stop sentence playback" else "Play sentence"), enabled, onClick = { session.speak(key, text.trim(), actions.route.mode) }) {
            AnimatedContent(
                targetState = speaking,
                transitionSpec = {
                    (fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.86f)) togetherWith (fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.86f))
                },
                label = "sentence-playback-icon",
            ) { isSpeaking ->
                Icon(
                    if (isSpeaking) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = if (isSpeaking) strings.text("Stop sentence playback") else strings.text("Play sentence"),
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        ActionButton(strings.text("Translate sentence"), enabled, onClick = { session.env.translator.translate(text.trim(), translateSourceTag(actions.route.mode, actions.script)) }) {
            SmallIcon(Icons.Rounded.Translate, strings.text("Translate sentence"))
        }
        ActionButton(strings.text("Copy sentence"), enabled, onClick = { session.env.clipboard.writeText(text.trim()) }) {
            SmallIcon(Icons.Rounded.ContentCopy, strings.text("Copy sentence"))
        }
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, description: String) {
    Icon(icon, contentDescription = description, modifier = Modifier.size(21.dp))
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val palette = LocalDictionaryPalette.current
    WithTooltip(label) {
        OutlinedIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = palette.accent, disabledContentColor = palette.accent.copy(alpha = 0.35f)),
            border = BorderStroke(1.dp, palette.border),
            content = content,
        )
    }
}
