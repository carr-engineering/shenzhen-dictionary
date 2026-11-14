package com.szdict.core.domain

import com.szdict.core.api.AppearsInItem
import com.szdict.core.api.AppearsInPage
import com.szdict.core.api.CharacterParts
import com.szdict.core.api.DecompNode
import com.szdict.core.api.DictError
import com.szdict.core.api.DictException
import com.szdict.core.api.PartNode
import com.szdict.core.api.RowRef
import com.szdict.core.api.Script
import com.szdict.core.engine.Ranking
import com.szdict.core.engine.cps
import com.szdict.core.engine.cpToString
import com.szdict.core.store.DecompRow
import com.szdict.core.store.DictionaryStore
import com.szdict.core.store.Proj
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The PARTS tab use cases: the decomposition tree of [Decomposer] with radical marks, and the
 * characters built directly from a component (`part_of`, SCHEMA note 23) in projection order.
 */
internal class PartsIndex(
    private val store: DictionaryStore,
    private val cards: CardBuilder,
    private val decomposer: Decomposer,
    private val cantonese: Boolean,
) {
    private val missing get() = DictException(DictError.AssetMissing("packs/decomposition.db"))

    suspend fun parts(characters: List<String>, script: Script, headwordEntryId: Int?): List<CharacterParts> {
        val trees = decomposer.decompose(characters, script, headwordEntryId?.let { store.entry(it) })
        // One batched read for the radical marks of every node of every tree.
        val glyphs = HashSet<String>()
        fun collect(n: DecompNode) { glyphs.add(n.glyph); n.children.forEach(::collect) }
        trees.forEach { t -> t.root?.let(::collect) }
        val rows = store.decompositions(glyphs) ?: throw missing
        return trees.map { t ->
            val root = t.root
            if (!t.available || root == null) CharacterParts(t.character, false, rows[t.character]?.radical, null)
            else CharacterParts(t.character, true, rows[t.character]?.radical, mark(root, rows, rows[t.character]?.radical))
        }
    }

    /** [parentRadical]: the Kangxi radical of the nearest encoded character above [n]. */
    private fun mark(n: DecompNode, rows: Map<String, DecompRow>, parentRadical: String?): PartNode {
        val row = rows[n.glyph]
        val group = isGroup(n)
        val radical = !group && isRadical(n.glyph, row)
        val ofParent = n.path.isNotEmpty() && !group && parentRadical != null &&
            (n.glyph == parentRadical || n.mapsTo == parentRadical || (radical && row?.radical == parentRadical))
        // A group is no character: its parts still belong to the character above it.
        val childRadical = if (group) parentRadical else row?.radical
        return PartNode(n, radical, ofParent, group, n.children.map { mark(it, rows, childRadical) })
    }

    // ---- Appears in ----

    private class Ordered(val character: String, val entryId: Int?)

    private val memoLock = Mutex()
    private val memo = LinkedHashMap<String, List<Ordered>>()

    suspend fun appearsIn(character: String, script: Script, offset: Int, limit: Int): AppearsInPage {
        val all = ordered(character, script)
        val from = offset.coerceIn(0, all.size)
        val page = all.subList(from, minOf(all.size, from + limit.coerceAtLeast(0)))
        val built = cards.build(page.mapNotNull { it.entryId }.map { RowRef(it) }, script, merge = false, exactInside = false).associateBy { it.id }
        return AppearsInPage(character, all.size, from, page.map { AppearsInItem(it.character, it.entryId?.let(built::get)) })
    }

    private suspend fun ordered(character: String, script: Script): List<Ordered> {
        val key = "${script.name}|$character"
        memoLock.withLock { memo.remove(key)?.let { memo[key] = it; return it } }
        val chars = store.partOf(character)?.cps()?.map(::cpToString)?.filter { it != character } ?: throw missing
        val forms = store.headwordForms(chars)
        val order = Ranking.projComparator(cantonese)
        val withEntry = ArrayList<Pair<String, Proj>>()
        val without = ArrayList<String>()
        for (c in chars) {
            val hits = forms[c].orEmpty()
            // Only characters written that way in the displayed script (妈 for simplified, 媽 for traditional).
            val own = hits.filter { (simp, trad, _) -> (if (script == Script.SIMPLIFIED) simp else trad) == c }
            when {
                own.isNotEmpty() -> withEntry.add(c to own.map { it.third }.minWith(Ranking.primary))
                hits.isEmpty() -> without.add(c)
            }
        }
        val list = withEntry.sortedWith { a, b -> order.compare(a.second, b.second) }.map { Ordered(it.first, it.second.id) } +
            without.map { Ordered(it, null) }
        memoLock.withLock {
            memo[key] = list
            while (memo.size > MEMO_SIZE) memo.remove(memo.keys.first())
        }
        return list
    }

    companion object {
        private const val MEMO_SIZE = 24

        /** The inline nested IDS of a group starts with an operator (⿰…⿻, ㇯). */
        fun isGroup(n: DecompNode): Boolean = n.glyph.cps().let { it.size > 1 && Ids.isOperator(it[0]) }

        /**
         * A Kangxi radical or one of its forms: a pack root (no IDS) whose radical is itself, or that
         * carries a curated radical/component name (亻 "person (left form)", 王 "jade").
         */
        fun isRadical(glyph: String, row: DecompRow?): Boolean =
            row != null && row.radical != null && row.ids.isEmpty() && (row.radical == glyph || row.name != null)
    }
}
