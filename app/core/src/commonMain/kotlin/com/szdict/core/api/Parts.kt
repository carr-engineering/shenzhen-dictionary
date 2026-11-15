package com.szdict.core.api

// ---- Parts section of the DICT tab: components of a character and the characters built from it ----

/**
 * One component row of the Parts section: the decomposition row ([node], ENT-121…123) plus what the
 * tab marks on it. [children] mirrors `node.children` (same order) with the marks applied; a node
 * with no children is indivisible (a radical, a stroke, an unencoded or unknown component).
 */
data class PartNode(
    val node: DecompNode,
    /** A Kangxi radical or one of its forms (亻 for 人, 氵 for 水). */
    val isRadical: Boolean,
    /** The indexing radical of the character this part belongs to (女 in 好, 亻 in 你). */
    val isParentRadical: Boolean,
    /**
     * An unnamed group of parts written inline in the parent's IDS (`⿲月贝凡` in 赢): never an
     * entry, drawn as its parts; [DecompNode.glyph] is the IDS text.
     */
    val isGroup: Boolean,
    val children: List<PartNode>,
) {
    /** Immediate parts: the count behind a "+ N more parts within" expander. */
    val partCount: Int get() = children.size
}

/** Parts of one character of the headword; [available] false = the pack has no record for it. */
data class CharacterParts(
    val character: String,
    val available: Boolean,
    /** Kangxi radical of the character (`decomp.radical`), null when unknown. */
    val radical: String?,
    val root: PartNode?,
    /**
     * The radical as a part of its own, when none of the parts the tab lists ([root]'s children and
     * the parts of its inline groups) is the radical or a form of it (年 = ⿱𠂉㐄, radical 干): the
     * tab still names it. Null when a listed part is the radical, or the character has no parts.
     */
    val radicalPart: PartNode? = null,
)

/** A character built directly from the component; [card] null = no dictionary entry (not tappable). */
data class AppearsInItem(val character: String, val card: EntryCard?)

/**
 * One page of the characters that use a component directly (`part_of`, SCHEMA note 23), in the
 * projection order of their standing entries (GLB-013), characters without an entry last.
 * [total] counts every character shown for the script, not only this page.
 */
data class AppearsInPage(val character: String, val total: Int, val offset: Int, val items: List<AppearsInItem>) {
    val hasMore: Boolean get() = offset + items.size < total
}
