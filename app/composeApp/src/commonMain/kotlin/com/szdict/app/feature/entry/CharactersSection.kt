package com.szdict.app.feature.entry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.szdict.app.i18n.AppStrings
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.kit.LocalDisplayPrefs
import com.szdict.app.ui.kit.SectionLabel
import com.szdict.app.ui.kit.TonedHanziText
import com.szdict.app.ui.kit.TonedReadingText
import com.szdict.app.ui.theme.DictionaryPalette
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.app.ui.theme.LocalIsDark
import com.szdict.core.api.CharacterDecomposition
import com.szdict.core.api.DecompNode
import com.szdict.core.api.EntryDetail
import com.szdict.core.api.PartHighlight
import com.szdict.core.api.PartKind
import com.szdict.core.api.PartPosition
import com.szdict.core.api.UiLang
import engineering.carr.hanzianimator.core.GlyphPoint
import engineering.carr.hanzianimator.core.OutlineCommand
import engineering.carr.hanzianimator.core.StrokeOrder
import engineering.carr.hanzianimator.core.StrokeViewportTransform
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * The always-visible Characters section (ENT-120…129, ARCH-200): one block per distinct Hanzi of
 * the displayed headword — a large glyph whose strokes or regions are coloured by component, and
 * the expandable component tree beside it.
 */
@Composable
internal fun CharactersSection(
    detail: EntryDetail,
    derived: EntryDerived,
    strokes: Map<String, StrokeOrder>?,
    actions: EntryActions,
    modifier: Modifier = Modifier,
) {
    if (detail.characters.isEmpty()) return
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current

    // Read after the rest of the entry has rendered, never as part of the entry read (ENT-120).
    LaunchedEffect(derived) {
        if (derived.decomp !is DecompLoad.Loading) return@LaunchedEffect
        withFrameNanos { }
        derived.decomp = try {
            DecompLoad.Ready(actions.session.dictionary(actions.route.mode).decompose(detail.characters, actions.script, detail.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DecompLoad.Unavailable   // GLB-035: the rest of the entry is unaffected
        }
    }

    // Route-scoped: expanded parts and the selected part, keyed `character/path` (ENT-120).
    val expanded = rememberSaveableKeySet()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }

    val state = derived.decomp
    val blocks = remember(state) { (state as? DecompLoad.Ready)?.characters?.filter { it.available && it.root != null } }
    // ENT-124: paths that no longer exist after a script change are dropped.
    LaunchedEffect(blocks) {
        if (blocks == null) return@LaunchedEffect
        val roots = blocks.associate { it.character to it.root }
        expanded.value = expanded.value.filterTo(LinkedHashSet()) { key ->
            val root = roots[key.substringBefore('/')] ?: return@filterTo false
            findPart(root, key.substringAfter('/')) != null
        }
        selected = selected?.takeIf { key ->
            val character = key.substringBefore('/')
            val root = roots[character] ?: return@takeIf false
            pruneSelection(key.substringAfter('/'), root, blockKeys(expanded.value, character)) != null
        }
    }
    if (blocks != null && blocks.isEmpty()) return   // no listed character has a record: omitted

    Column(modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel(charactersTitle(strings.lang))
        when {
            state is DecompLoad.Unavailable ->
                Text(strings.text("Not available"), style = MaterialTheme.typography.bodyMedium, color = palette.muted)
            blocks == null -> detail.characters.forEach { CharacterBlockSkeleton() }
            else -> blocks.forEach { block ->
                val character = block.character
                CharacterBlock(
                    decomposition = block,
                    order = strokes?.get(character),
                    expanded = blockKeys(expanded.value, character),
                    selected = selected?.takeIf { it.substringBefore('/') == character }?.substringAfter('/'),
                    onToggle = { key, select ->
                        val full = "$character/$key"
                        val opening = full !in expanded.value
                        expanded.toggle(full)
                        if (opening && select) selected = full   // ENT-128: expanding a part selects it
                    },
                    onSelect = { key -> selected = key?.let { "$character/$it" } },
                    actions = actions,
                )
            }
        }
    }
}

private fun blockKeys(all: Set<String>, character: String): Set<String> {
    val prefix = "$character/"
    return all.filter { it.startsWith(prefix) }.mapTo(LinkedHashSet()) { it.substring(prefix.length) }
}

@Composable
private fun CharacterBlockSkeleton() {
    val palette = LocalDictionaryPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(128.dp).clip(MaterialTheme.shapes.medium).background(palette.bgSoft))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SkeletonBar(160.dp, 16.dp)
            SkeletonBar(120.dp, 14.dp)
        }
    }
}

/** Shared between the rows and the glyph of one block. */
@Stable
private class BlockContext(
    val root: DecompNode,
    val expanded: Set<String>,
    val selected: String?,
    val active: String?,
    val onToggle: (String, Boolean) -> Unit,
    val onHover: (key: String, on: Boolean) -> Unit,
    val onSwatchPlaced: (String, Offset) -> Unit,
    val actions: EntryActions,
)

@Composable
private fun CharacterBlock(
    decomposition: CharacterDecomposition,
    order: StrokeOrder?,
    expanded: Set<String>,
    selected: String?,
    onToggle: (String, Boolean) -> Unit,
    onSelect: (String?) -> Unit,
    actions: EntryActions,
) {
    val root = decomposition.root ?: return
    val palette = LocalDictionaryPalette.current
    val dark = LocalIsDark.current
    val density = LocalDensity.current
    val glyphStrokes = remember(order) { order?.let(::GlyphStrokes) }
    var hovered by remember(root) { mutableStateOf<String?>(null) }
    // Hover / focus is transient and takes precedence over the selection while it lasts (ENT-128).
    val activeKey = hovered ?: selected
    val active = remember(root, activeKey) { activeKey?.let { findPart(root, it) }?.takeIf { it.path.isNotEmpty() } }

    var blockOrigin by remember { mutableStateOf(Offset.Unspecified) }
    var panelOrigin by remember { mutableStateOf(Offset.Unspecified) }
    var swatch by remember(activeKey) { mutableStateOf(Offset.Unspecified) }

    val context = BlockContext(
        root = root,
        expanded = expanded,
        selected = selected,
        active = active?.let { pathKey(it.path) },
        onToggle = onToggle,
        onHover = { key, on -> if (on) hovered = key else if (hovered == key) hovered = null },
        onSwatchPlaced = { key, position -> if (key == activeKey) swatch = position },
        actions = actions,
    )

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 600.dp
        val panelSize = if (compact) 128.dp else 160.dp
        val panelPx = with(density) { panelSize.toPx() }
        val connectorWidth = with(density) { 1.5.dp.toPx() }
        val content = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { blockOrigin = it.positionInRoot() }
            .drawWithContent {
                drawContent()
                // ENT-128: a connector links the active row's swatch to the nearest edge of its region.
                val part = active ?: return@drawWithContent
                if (blockOrigin == Offset.Unspecified || panelOrigin == Offset.Unspecified || swatch == Offset.Unspecified) return@drawWithContent
                val viewport = StrokeViewportTransform(panelPx, panelPx)
                val box = partBox(part.highlight, glyphStrokes, viewport) ?: return@drawWithContent
                val origin = panelOrigin - blockOrigin
                val from = swatch - blockOrigin
                val target = Box(box.left + origin.x, box.top + origin.y, box.right + origin.x, box.bottom + origin.y)
                val (x, y) = nearestPointOn(target, from.x, from.y)
                drawLine(palette.part(partColorIndex(part), dark), from, Offset(x, y), strokeWidth = connectorWidth, cap = StrokeCap.Round)
            }
        val panel: @Composable () -> Unit = {
            GlyphPanel(
                root = root,
                order = order,
                glyphStrokes = glyphStrokes,
                active = active,
                size = panelSize,
                modifier = Modifier.onGloballyPositioned { panelOrigin = it.positionInRoot() },
                onTap = { unitX, unitY, glyphX, glyphY ->
                    val hit = deepestPartAt(
                        parts = shownParts(root, expanded),
                        x = unitX,
                        y = unitY,
                        exact = { glyphStrokes?.covers(it) == true },
                        strokeHit = { index -> glyphStrokes?.hit(index, glyphX, glyphY, tolerance = 30f) == true },
                    )
                    onSelect(nextSelection(selected, hit))
                },
            )
        }
        if (compact) {
            // Compact: the glyph panel sits above the tree, centred (ENT-120).
            Column(content, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { panel() }
                PartTree(context)
            }
        } else {
            Row(content, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                panel()
                Column(Modifier.weight(1f)) { PartTree(context) }
            }
        }
    }
}

/** Canvas-space box of a part: its exact strokes' bounds when usable, otherwise its region. */
private fun partBox(highlight: PartHighlight, strokes: GlyphStrokes?, viewport: StrokeViewportTransform): Box? {
    val glyphBox = strokes?.takeIf { it.covers(highlight.strokes) }?.boundsOf(highlight.strokes!!)
        ?: regionBox(highlight.region, 0f, 0f, StrokeOrder.GLYPH_SIZE)
    if (viewport.scale <= 0f) return null
    return Box(
        viewport.left + glyphBox.left * viewport.scale,
        viewport.top + glyphBox.top * viewport.scale,
        viewport.left + glyphBox.right * viewport.scale,
        viewport.top + glyphBox.bottom * viewport.scale,
    )
}

// ---- Glyph panel (ENT-128) ----

@Composable
private fun GlyphPanel(
    root: DecompNode,
    order: StrokeOrder?,
    glyphStrokes: GlyphStrokes?,
    active: DecompNode?,
    size: Dp,
    modifier: Modifier = Modifier,
    onTap: (unitX: Float, unitY: Float, glyphX: Float, glyphY: Float) -> Unit,
) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val dark = LocalIsDark.current
    val paths = remember(order) { order?.let(::strokePaths) }
    val measurer = rememberTextMeasurer()
    val description = remember(root, strings) { root.glyph + if (root.isRoot) "" else ", " + structureDescription(root, strings) }
    Surface(
        modifier = modifier
            .size(size)
            // One image element; tap-to-select is a pointer convenience, not a focus stop (ENT-125).
            .clearAndSetSemantics { contentDescription = description; role = Role.Image }
            .pointerInput(root, glyphStrokes) {
                detectTapGestures { position ->
                    val viewport = StrokeViewportTransform(this.size.width.toFloat(), this.size.height.toFloat())
                    if (viewport.scale <= 0f) return@detectTapGestures
                    val glyph = viewport.canvasToGlyph(GlyphPoint(position.x, position.y))
                    onTap(glyph.x / StrokeOrder.GLYPH_SIZE, glyph.y / StrokeOrder.GLYPH_SIZE, glyph.x, glyph.y)
                }
            },
        shape = MaterialTheme.shapes.medium,
        color = palette.panel,
        border = BorderStroke(1.dp, palette.border),
    ) {
        // Highlight changes cross-fade in 120 ms (ENT-128).
        Crossfade(targetState = active, animationSpec = tween(120), label = "glyph-highlight") { part ->
            Canvas(Modifier.fillMaxSize()) {
                val viewport = StrokeViewportTransform(this.size.width, this.size.height)
                if (viewport.scale <= 0f) return@Canvas
                val usable: (List<Int>?) -> Boolean = { glyphStrokes?.covers(it) == true }
                if (paths != null) {
                    val tints = strokeTints(paths.size, root, part, usable)
                    withTransform({
                        translate(viewport.left, viewport.top)
                        scale(viewport.scale, viewport.scale, pivot = Offset.Zero)
                    }) {
                        paths.forEachIndexed { index, path ->
                            val color = when (val tint = tints[index]) {
                                StrokeTint.Ink -> palette.fg
                                StrokeTint.Dimmed -> palette.fg.copy(alpha = 0.3f)
                                is StrokeTint.Part -> palette.part(tint.slot, dark)
                            }
                            drawPath(path, color, style = Fill)
                        }
                    }
                } else {
                    // No stroke graphics: the glyph is drawn from the font in fg.
                    val side = StrokeOrder.GLYPH_SIZE * viewport.scale
                    val layout = measurer.measure(root.glyph, TextStyle(color = palette.fg, fontSize = (side * 0.8f).toSp()), softWrap = false, maxLines = 1)
                    drawText(layout, topLeft = Offset((this.size.width - layout.size.width) / 2f, (this.size.height - layout.size.height) / 2f))
                }
                // Parts without exact strokes show their approximate region as a tinted overlay.
                val overlays = if (part != null) listOf(part).filter { !usable(it.highlight.strokes) }
                else root.children.filter { !usable(it.highlight.strokes) }
                for (overlay in overlays) {
                    val color = palette.part(partColorIndex(overlay), dark)
                    val path = regionPath(overlay.highlight, viewport)
                    drawPath(path, color.copy(alpha = if (part != null) 0.24f else 0.16f), style = Fill)
                    if (part != null) {
                        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), pathEffect = dash))
                    }
                }
            }
        }
    }
}

/** Region rectangle minus the cut-out of an enclosing part, in canvas space. */
private fun DrawScope.regionPath(highlight: PartHighlight, viewport: StrokeViewportTransform): Path {
    val side = StrokeOrder.GLYPH_SIZE * viewport.scale
    fun rect(box: Box) = Rect(box.left, box.top, box.right, box.bottom)
    return Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(rect(regionBox(highlight.region, viewport.left, viewport.top, side)))
        highlight.cutout?.let { addRect(rect(regionBox(it, viewport.left, viewport.top, side))) }
    }
}

/** Platform paths of every stroke outline, in glyph units. */
internal fun strokePaths(order: StrokeOrder): List<Path> = order.strokes.map { stroke ->
    Path().apply {
        val outline = stroke.outline
        var at = 0
        fun next(): Float = outline.coordinateAt(at++)
        repeat(outline.commandCount) { index ->
            when (outline.commandAt(index)) {
                OutlineCommand.MOVE -> moveTo(next(), next())
                OutlineCommand.LINE -> lineTo(next(), next())
                OutlineCommand.QUAD -> quadraticTo(next(), next(), next(), next())
                OutlineCommand.CUBIC -> cubicTo(next(), next(), next(), next(), next(), next())
                OutlineCommand.CLOSE -> close()
            }
        }
    }
}

// ---- Tree (ENT-121…123) ----

@Composable
private fun PartTree(context: BlockContext) {
    val root = context.root
    Column {
        PartRow(root, context, topLevel = true)
        if (!root.isRoot && root.children.isNotEmpty()) {
            StructureLine(root)
            root.children.forEach { PartSubtree(it, context, depth = 1) }
        }
    }
}

@Composable
private fun PartSubtree(node: DecompNode, context: BlockContext, depth: Int) {
    val key = pathKey(node.path)
    PartRow(node, context, topLevel = false)
    val open = isExpandable(node) && key in context.expanded
    // Collapsed content leaves the composition, so it is not focusable (ENT-121).
    AnimatedVisibility(open, enter = expandVertically(tween(180)) + fadeIn(tween(180)), exit = shrinkVertically(tween(180)) + fadeOut(tween(120))) {
        val palette = LocalDictionaryPalette.current
        // 16 dp per level behind a 2 dp indent bar; the visual indent is capped at four levels.
        val indented = depth <= 4
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = if (indented) 5.dp else 0.dp)) {
            if (indented) Box(Modifier.width(2.dp).fillMaxHeight().background(palette.border))
            Column(Modifier.padding(start = if (indented) 9.dp else 0.dp)) {
                StructureLine(node)
                node.children.forEach { PartSubtree(it, context, depth + 1) }
            }
        }
    }
}

private fun isExpandable(node: DecompNode): Boolean =
    !node.isRoot && node.children.isNotEmpty() && node.kind != PartKind.UNKNOWN && node.kind != PartKind.UNENCODED

private fun isTappable(node: DecompNode): Boolean =
    node.entryIds.isNotEmpty() && (node.kind == PartKind.CHARACTER || node.kind == PartKind.RADICAL_FORM)

/** `⿰ 亻 木` in titleMedium with the operator muted, announced by its spoken name (ENT-121, ENT-125). */
@Composable
private fun StructureLine(node: DecompNode) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val operator = node.operator ?: return
    val text = remember(node, palette) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = palette.muted)) { append(operator) }
            node.children.forEach { append(" "); append(displayGlyph(it)) }
        }
    }
    val description = remember(node, strings) { structureDescription(node, strings) }
    Text(text, style = MaterialTheme.typography.titleMedium, color = palette.fg,
        modifier = Modifier.padding(vertical = 4.dp).clearAndSetSemantics { contentDescription = description })
}

internal fun displayGlyph(node: DecompNode): String = when (node.kind) {
    PartKind.UNKNOWN -> "？"
    PartKind.UNENCODED -> "▢"
    else -> node.glyph
}

/** One row of the tree (ENT-122): marker, glyph, reading, short definition; chevron apart. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartRow(node: DecompNode, context: BlockContext, topLevel: Boolean) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val prefs = LocalDisplayPrefs.current
    val dark = LocalIsDark.current
    val scope = rememberCoroutineScope()
    val key = pathKey(node.path)
    val card = node.card
    val isActive = !topLevel && context.active == key
    val isSelected = !topLevel && context.selected == key
    val expandable = !topLevel && isExpandable(node)
    val open = expandable && key in context.expanded
    val showColours = !topLevel
    val partColor = palette.part(partColorIndex(node), dark)

    val interaction = remember(key) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    if (!topLevel) {
        LaunchedEffect(hovered || focused) { context.onHover(key, hovered || focused) }
    }

    val description = remember(node, strings) { partDescription(node, topLevel, showReading = true, strings) }
    val stateText = when {
        !expandable -> null
        open -> strings.text("Expanded")
        else -> strings.text("Collapsed")
    }
    val open1: () -> Unit = {
        when {
            node.entryIds.size == 1 -> context.actions.openEntry(node.entryIds[0])
            node.entryIds.size > 1 -> scope.launch {
                // ENT-122: several entries open the candidate sheet, the row's shown entry first.
                val cards = runCatching {
                    context.actions.session.dictionary(context.actions.route.mode).entryCards(node.entryIds, context.actions.script)
                }.getOrDefault(emptyList())
                val ordered = cards.sortedBy { if (it.id == card?.id) 0 else 1 }
                val form = node.mapsTo ?: node.glyph
                when {
                    ordered.size > 1 -> context.actions.openCards(strings.text("Words at 「{0}」", form), form, ordered)
                    else -> context.actions.openEntry(ordered.firstOrNull()?.id ?: node.entryIds[0])
                }
            }
        }
    }
    val rowModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(if (isActive || isSelected) palette.highlight else Color.Transparent)
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        val body = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .then(
                if (isTappable(node)) Modifier.clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = open1)
                else Modifier.hoverable(interaction),
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (!topLevel) selected = isSelected
                stateText?.let { stateDescription = it }
            }
            .padding(horizontal = 6.dp, vertical = 6.dp)
        FlowRow(body, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            if (showColours) {
                // Part marker: swatch in the part colour plus its position — never colour alone (ENT-128/129).
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(
                        Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(partColor)
                            .then(
                                if (isActive) Modifier.onGloballyPositioned {
                                    context.onSwatchPlaced(key, it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f))
                                } else Modifier,
                            ),
                    )
                    node.position?.let {
                        Text(positionLabel(it, strings), style = MaterialTheme.typography.labelSmall, color = palette.muted)
                    }
                }
            }
            val glyphStyle = if (topLevel) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
            when (node.kind) {
                PartKind.UNKNOWN -> Text("？", style = glyphStyle, color = palette.muted)
                PartKind.UNENCODED -> UnencodedGlyph(node.svgPath, if (topLevel) 26.dp else 22.dp)
                else -> {
                    val toneColours = prefs.toneColors && card != null && card.toneColourable && !card.noReading &&
                        node.mapsTo == null
                    if (card != null) TonedHanziText(node.glyph, card.reading, toneColours, glyphStyle, color = palette.fg)
                    else Text(node.glyph, style = glyphStyle, color = palette.fg)
                    // Radical forms and variation indicators are linked as the ideograph they map to (ENT-123).
                    node.mapsTo?.takeIf { it != node.glyph }?.let {
                        Text("= $it", style = MaterialTheme.typography.titleMedium, color = palette.muted)
                    }
                }
            }
            if (card != null && !card.noReading && !card.reading.isEmpty) {
                TonedReadingText(card.reading, prefs.toneColors, MaterialTheme.typography.bodyMedium, color = palette.accent)
            }
            card?.provenance?.let { Text(it.text(strings.lang), style = MaterialTheme.typography.labelSmall, color = palette.muted) }
            card?.definitions?.firstOrNull()?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            node.radicalName?.takeIf { it.isNotBlank() }?.let { TagLabel(it) }
            when (node.kind) {
                PartKind.UNENCODED -> Text(strings.text("Unencoded component"), style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                PartKind.UNKNOWN -> Text(strings.text("Unknown part"), style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                else -> Unit
            }
        }
        if (expandable) {
            // A separate 48 dp tap target that never opens the entry (ENT-122).
            IconButton(onClick = { context.onToggle(key, true) }, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = if (open) strings.text("Collapse {0}", displayGlyph(node)) else strings.text("Expand {0}", displayGlyph(node)),
                    tint = palette.muted,
                    modifier = Modifier.rotate(if (open) 180f else 0f),
                )
            }
        }
    }
}

@Composable
private fun TagLabel(text: String) {
    val palette = LocalDictionaryPalette.current
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = palette.muted,
        modifier = Modifier.background(palette.bgSoft, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** An unencoded component drawn from its bundled SVG path (1024 em box), tinted like text (ENT-123). */
@Composable
private fun UnencodedGlyph(svgPath: String?, size: Dp) {
    val palette = LocalDictionaryPalette.current
    val path = remember(svgPath) { svgPath?.let { runCatching { PathParser().parsePathString(it).toPath() }.getOrNull() } }
    Canvas(Modifier.size(size)) {
        if (path == null) {
            drawRect(palette.muted, size = Size(this.size.width, this.size.height), style = Stroke(1.dp.toPx()))
            return@Canvas
        }
        val scale = this.size.minDimension / StrokeOrder.GLYPH_SIZE
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) { drawPath(path, palette.fg, style = Fill) }
    }
}

// ---- Labels (ENT-125, ENT-129) ----

/**
 * Section title (ENT-120). Spelled out per language rather than looked up by its English text,
 * which the HSK destination's `Characters` list already translates differently.
 */
internal fun charactersTitle(lang: UiLang): String = when (lang) {
    UiLang.EN -> "Characters"
    UiLang.ZH_HANS -> "汉字构成"
    UiLang.ZH_HANT -> "漢字構成"
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

/** `left to right: 亻 left, 木 right` (ENT-125). */
internal fun structureDescription(node: DecompNode, strings: AppStrings): String =
    strings.text(operatorName(node.operator)) + ": " + node.children.joinToString(", ") { child ->
        val glyph = when (child.kind) {
            PartKind.UNKNOWN -> strings.text("Unknown part")
            PartKind.UNENCODED -> strings.text("Unencoded component")
            else -> child.glyph
        }
        glyph + (child.position?.let { " " + positionLabel(it, strings) } ?: "")
    }

/** `left part, 亻, rén, person, radical person` (ENT-125); the reading is omitted when hidden (GLB-122). */
internal fun partDescription(node: DecompNode, topLevel: Boolean, showReading: Boolean, strings: AppStrings): String {
    val parts = ArrayList<String>()
    if (!topLevel) node.position?.let { parts += strings.text("{0} part", positionLabel(it, strings)) }
    when (node.kind) {
        PartKind.UNKNOWN -> parts += strings.text("Unknown part")
        PartKind.UNENCODED -> parts += strings.text("Unencoded component")
        else -> {
            parts += node.glyph
            node.mapsTo?.takeIf { it != node.glyph }?.let { parts += it }
        }
    }
    val card = node.card
    if (showReading && card != null && !card.noReading && card.reading.display.isNotBlank()) parts += card.reading.display
    card?.definitions?.firstOrNull()?.let { parts += it }
    node.radicalName?.takeIf { it.isNotBlank() }?.let { parts += strings.text("radical {0}", it) }
    return parts.joinToString(", ")
}
