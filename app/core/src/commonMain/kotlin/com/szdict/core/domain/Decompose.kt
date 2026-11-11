package com.szdict.core.domain

import com.szdict.core.api.CharacterDecomposition
import com.szdict.core.api.DecompNode
import com.szdict.core.api.DictError
import com.szdict.core.api.DictException
import com.szdict.core.api.EntryCard
import com.szdict.core.api.PartHighlight
import com.szdict.core.api.PartKind
import com.szdict.core.api.PartPosition
import com.szdict.core.api.Region
import com.szdict.core.api.RowRef
import com.szdict.core.api.Script
import com.szdict.core.engine.Ranking
import com.szdict.core.engine.cps
import com.szdict.core.engine.cpString
import com.szdict.core.engine.cpToString
import com.szdict.core.store.DecompRow
import com.szdict.core.store.DictionaryStore
import com.szdict.core.store.EntryRow

/** IDS geometry (ENT-129, ARCH-200): positions and approximate regions from the operator alone. Pure. */
internal object Ids {
    fun isOperator(cp: Int): Boolean = cp in 0x2FF0..0x2FFF || cp == 0x31EF

    fun arity(cp: Int): Int = when (cp) {
        0x2FF2, 0x2FF3 -> 3
        0x2FFE, 0x2FFF -> 1
        else -> 2
    }

    fun position(op: Int, slot: Int): PartPosition = when (op) {
        0x2FF0 -> if (slot == 0) PartPosition.LEFT else PartPosition.RIGHT
        0x2FF1 -> if (slot == 0) PartPosition.TOP else PartPosition.BOTTOM
        0x2FF2 -> when (slot) { 0 -> PartPosition.LEFT; 1 -> PartPosition.MIDDLE; else -> PartPosition.RIGHT }
        0x2FF3 -> when (slot) { 0 -> PartPosition.TOP; 1 -> PartPosition.MIDDLE; else -> PartPosition.BOTTOM }
        in 0x2FF4..0x2FFA -> if (slot == 0) PartPosition.OUTER else PartPosition.INNER
        else -> PartPosition.PART
    }

    private fun sub(r: Region, x0: Float, y0: Float, x1: Float, y1: Float): Region {
        val w = r.right - r.left
        val h = r.bottom - r.top
        return Region(r.left + w * x0, r.top + h * y0, r.left + w * x1, r.top + h * y1)
    }

    /** Region (and cutout, for an enclosing part) of operand [slot] of [op] inside [parent]. */
    fun region(op: Int, slot: Int, parent: Region): Pair<Region, Region?> = when (op) {
        0x2FF0 -> (if (slot == 0) sub(parent, 0f, 0f, 0.5f, 1f) else sub(parent, 0.5f, 0f, 1f, 1f)) to null
        0x2FF1 -> (if (slot == 0) sub(parent, 0f, 0f, 1f, 0.5f) else sub(parent, 0f, 0.5f, 1f, 1f)) to null
        0x2FF2 -> sub(parent, slot / 3f, 0f, (slot + 1) / 3f, 1f) to null
        0x2FF3 -> sub(parent, 0f, slot / 3f, 1f, (slot + 1) / 3f) to null
        in 0x2FF4..0x2FFA -> {
            val inner = when (op) {
                0x2FF4 -> sub(parent, 0.25f, 0.25f, 0.75f, 0.75f) // full surround
                0x2FF5 -> sub(parent, 0.25f, 0.35f, 0.75f, 1f)    // open at the bottom
                0x2FF6 -> sub(parent, 0.25f, 0f, 0.75f, 0.65f)    // open at the top
                0x2FF7 -> sub(parent, 0.35f, 0.25f, 1f, 0.75f)    // open at the right
                0x2FF8 -> sub(parent, 0.35f, 0.35f, 1f, 1f)       // open at the lower right
                0x2FF9 -> sub(parent, 0f, 0.35f, 0.65f, 1f)       // open at the lower left
                else -> sub(parent, 0.35f, 0f, 1f, 0.65f)         // open at the upper right
            }
            if (slot == 0) parent to inner else inner to null
        }
        else -> parent to null
    }

    /** A parsed IDS: a leaf component or an operator with operands. */
    class Tree(val op: Int, val leaf: String?, val children: List<Tree>, val text: String)

    /** Splits an IDS string into tokens: operators, `&…;` / `{…}` synthetic ids, `〾X`, single code points. */
    fun tokens(ids: String): List<String> {
        val cps = ids.cps()
        val out = ArrayList<String>()
        var i = 0
        while (i < cps.size) {
            val cp = cps[i]
            when {
                cp == '&'.code || cp == '{'.code -> {
                    val close = if (cp == '&'.code) ';'.code else '}'.code
                    var j = i + 1
                    while (j < cps.size && cps[j] != close) j++
                    out.add(cps.cpString(i, minOf(j + 1, cps.size)))
                    i = j + 1
                }
                cp == 0x303E && i + 1 < cps.size -> {
                    out.add(cps.cpString(i, i + 2))
                    i += 2
                }
                else -> {
                    out.add(cpToString(cp))
                    i++
                }
            }
        }
        return out
    }

    fun parse(ids: String): Tree? {
        val toks = tokens(ids)
        if (toks.isEmpty()) return null
        var pos = 0
        fun node(depth: Int): Tree? {
            if (pos >= toks.size || depth > 16) return null
            val t = toks[pos++]
            val cp = t.cps()[0]
            if (t.length <= 2 && isOperator(cp)) {
                val start = pos - 1
                val kids = ArrayList<Tree>()
                repeat(arity(cp)) { node(depth + 1)?.let { kids.add(it) } }
                return Tree(cp, null, kids, toks.subList(start, pos).joinToString(""))
            }
            return Tree(0, t, emptyList(), t)
        }
        return node(0)
    }
}

/** The `Decompose` use case (ENT-120…ENT-129): tree, positions and highlights; one batched lookup per level. */
internal class Decomposer(private val store: DictionaryStore, private val cards: CardBuilder) {
    private class Build(
        val path: List<Int>, val component: String, val position: PartPosition?, val region: Region, val cutout: Region?,
        val ancestors: Set<String>, val synthetic: Ids.Tree?,
    ) {
        var row: DecompRow? = null
        var tree: Ids.Tree? = null
        var kids: List<Build> = emptyList()
        var entryIds: List<Int> = emptyList()
        var shown: Int = -1
        var mapsTo: String? = null
    }

    private fun kindOf(component: String): PartKind {
        val cp = component.cps().firstOrNull() ?: return PartKind.UNKNOWN
        return when {
            component == "？" || component == "?" -> PartKind.UNKNOWN
            cp == '&'.code || cp == '{'.code -> PartKind.UNENCODED
            cp in 0x2E80..0x2FDF -> PartKind.RADICAL_FORM
            else -> PartKind.CHARACTER
        }
    }

    private fun lookupForm(component: String): String =
        if (component.length >= 2 && component[0] == '〾') component.substring(1) else component

    suspend fun decompose(characters: List<String>, script: Script, headword: EntryRow?): List<CharacterDecomposition> {
        if (!store.hasDecomposition()) throw DictException(DictError.AssetMissing("packs/decomposition.db"))
        val full = Region(0f, 0f, 1f, 1f)
        val tops = characters.map { Build(emptyList(), it, null, full, null, emptySet(), null) }
        var level: List<Build> = tops
        var depth = 0
        while (level.isNotEmpty() && depth < 12) {
            val real = level.filter { it.synthetic == null }
            val rows = store.decompositions(real.map { it.component }.toSet()) ?: emptyMap()
            // One batched headword lookup per level (ENT-122).
            val lookups = real.filter { kindOf(it.component) == PartKind.CHARACTER || kindOf(it.component) == PartKind.RADICAL_FORM }
            val hits = store.headwordExact(lookups.map { lookupForm(it.component) }.toSet())
            val order = Ranking.primary
            for (b in lookups) {
                val hs = hits[lookupForm(b.component)].orEmpty().map { it.proj }.distinctBy { it.id }.sortedWith(order)
                b.entryIds = hs.map { it.id }
                b.shown = hs.firstOrNull()?.id ?: -1
            }
            val next = ArrayList<Build>()
            for (b in level) {
                val tree: Ids.Tree? = if (b.synthetic != null) b.synthetic else {
                    val row = rows[b.component]
                    b.row = row
                    if (row == null || row.ids.isEmpty() || row.ids == b.component || b.component in b.ancestors) null
                    else Ids.parse(row.ids)?.takeIf { it.leaf == null && it.children.isNotEmpty() }
                }
                b.tree = tree
                if (tree == null) continue
                val parts = b.row?.parts
                var leafIndex = 0
                fun leafCount(t: Ids.Tree): Int = if (t.leaf != null) 1 else t.children.sumOf { leafCount(it) }
                val useParts = b.synthetic == null && parts != null && parts.size == leafCount(tree)
                val anc = if (b.synthetic == null) b.ancestors + b.component else b.ancestors
                b.kids = tree.children.mapIndexed { slot, child ->
                    val (region, cutout) = Ids.region(tree.op, slot, b.region)
                    val pos = Ids.position(tree.op, slot)
                    if (child.leaf != null) {
                        val comp = if (useParts) parts!![leafIndex] else child.leaf
                        leafIndex++
                        Build(b.path + slot, comp, pos, region, cutout, anc, null)
                    } else {
                        leafIndex += leafCount(child)
                        Build(b.path + slot, child.text, pos, region, cutout, anc, child)
                    }
                }
                next.addAll(b.kids)
            }
            level = next
            depth++
        }

        // Top-level reading choice: the entry whose reading matches the headword's syllable (ENT-122).
        val all = ArrayList<Build>()
        fun collect(b: Build) { all.add(b); b.kids.forEach(::collect) }
        tops.forEach(::collect)
        if (headword != null && headword.syllables.size == headword.charCount) {
            val head = headword.headword(script).cps()
            val candidates = store.entries(tops.flatMap { it.entryIds }.toSet())
            for (t in tops) {
                val idx = head.indexOf(t.component.cps().firstOrNull() ?: -1)
                if (idx < 0 || t.entryIds.size < 2) continue
                val syl = headword.syllables[idx]
                val tone = headword.tones.getOrElse(idx) { 0 }
                val match = t.entryIds.firstOrNull { id -> candidates[id]?.let { it.syllables.size == 1 && it.syllables[0] == syl && it.tones.getOrElse(0) { 0 } == tone } == true }
                    ?: t.entryIds.firstOrNull { id -> candidates[id]?.let { it.syllables.size == 1 && it.syllables[0] == syl } == true }
                if (match != null) t.shown = match
            }
        }
        val shownCards = cards.build(all.filter { it.shown >= 0 }.map { RowRef(it.shown) }.distinct(), script, merge = false, exactInside = false).associateBy { it.id }
        val forms = store.entries(all.filter { kindOf(it.component) == PartKind.RADICAL_FORM && it.shown >= 0 }.map { it.shown }.toSet())

        fun freeze(b: Build, topMatches: List<List<Int>?>?): DecompNode {
            val kind = if (b.synthetic != null) PartKind.CHARACTER else kindOf(b.component)
            val card: EntryCard? = shownCards[b.shown]
            val mapsTo = when {
                b.component.length >= 2 && b.component[0] == '〾' -> b.component.substring(1)
                kind == PartKind.RADICAL_FORM -> forms[b.shown]?.headword(script)
                else -> null
            }
            val strokes = topMatches?.let { m ->
                if (b.path.isEmpty()) m.indices.toList()
                else m.indices.filter { i -> m[i]?.let { p -> p.size >= b.path.size && p.subList(0, b.path.size) == b.path } == true }.takeIf { it.isNotEmpty() }
            }
            val tree = b.tree
            return DecompNode(
                path = b.path, kind = kind, glyph = if (mapsTo != null && kind != PartKind.RADICAL_FORM) mapsTo else b.component, mapsTo = mapsTo,
                position = b.position, operator = tree?.let { cpToString(it.op) }, ids = if (tree == null) "" else (b.row?.ids ?: tree.text),
                isRoot = tree == null, card = card, entryIds = b.entryIds, radicalName = b.row?.name, svgPath = b.row?.svg,
                strokeCount = b.row?.strokes, highlight = PartHighlight(strokes, b.region, b.cutout),
                children = b.kids.map { freeze(it, topMatches) },
            )
        }
        return tops.map { t ->
            if (t.row == null) CharacterDecomposition(t.component, false, null)
            else CharacterDecomposition(t.component, true, freeze(t, t.row?.matches))
        }
    }
}
