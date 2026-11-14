package com.szdict.core.engine

import com.szdict.core.api.Script
import com.szdict.core.store.Proj

/** Match classes and weights (SRCH-030). */
internal object MatchClass {
    const val EXACT = 1
    const val EXACT_TONELESS = 2
    const val PREFIX = 3
    const val CONTAINS = 4
    const val EN_PHRASE = 5
    const val EN_SENSE = 6
    const val EN_ENTRY = 7
}

/** A ranked candidate: a projection plus what the match earned (ARCH-031). Never a hydrated entry. */
internal class Candidate(
    val proj: Proj,
    val cls: Int,
    /** True when matched only through an alternative reading (SRCH-078). */
    val alt: Boolean = false,
    val cost: Double = 0.0,
    val altNote: Int = -1,
    val altKey: String? = null,
    /** Cantonese mode, English side: a CC-CEDICT row (SRCH-077). */
    val mandarinRow: Boolean = false,
    /** English side: index of the first matched sense, or -1. */
    val sense: Int = -1,
) {
    /** SRCH-072: best class, then main reading over alternative, then lowest token cost. */
    fun betterThan(o: Candidate): Boolean {
        if (cls != o.cls) return cls < o.cls
        if (alt != o.alt) return !alt
        return cost < o.cost
    }
}

internal object Ranking {
    /**
     * Frequency-rank caps per HSK level 1…7 (7 = band 7–9), index 0 unused: about the 40th percentile
     * of the Mandarin frequency ranks of the level's entries, so a level's words mostly rank at its cap
     * and the levels come out in order, while a word more frequent than the cap keeps its own rank.
     */
    val HSK_RANK_CAPS = intArrayOf(Int.MAX_VALUE, 300, 600, 1100, 1900, 3500, 6000, 11000)

    /** The rank lists are ordered by: the frequency rank, capped by the entry's lowest HSK level. */
    fun effectiveRank(p: Proj): Int {
        val l = p.hskLevel
        return if (l <= 0) p.freqRank else minOf(p.freqRank, HSK_RANK_CAPS[minOf(l, HSK_RANK_CAPS.size - 1)])
    }

    /** Effective rank, then the true frequency rank (SRCH-070 item 4). */
    fun compareRank(pa: Proj, pb: Proj): Int {
        val ea = effectiveRank(pa)
        val eb = effectiveRank(pb)
        if (ea != eb) return ea.compareTo(eb)
        return pa.freqRank.compareTo(pb.freqRank)
    }

    /**
     * The canonical comparator (SRCH-070), preceded by the Unicode-only tier (SRCH-079). In
     * Cantonese mode on the Chinese side ([cantoFirst]) CC-Canto entries come first inside each
     * match class (SRCH-076).
     */
    fun comparator(cantoFirst: Boolean): Comparator<Candidate> = Comparator { a, b ->
        val pa = a.proj
        val pb = b.proj
        if (pa.isUnihan != pb.isUnihan) return@Comparator if (pa.isUnihan) 1 else -1
        if (a.cls != b.cls) return@Comparator a.cls.compareTo(b.cls)
        if (cantoFirst && pa.hasCanto != pb.hasCanto) return@Comparator if (pa.hasCanto) -1 else 1
        if (a.alt != b.alt) return@Comparator if (a.alt) 1 else -1
        val c = a.cost.compareTo(b.cost)
        if (c != 0) return@Comparator c
        val r = compareRank(pa, pb)
        if (r != 0) return@Comparator r
        if (pa.charCount != pb.charCount) return@Comparator pa.charCount.compareTo(pb.charCount)
        if (pa.isProper != pb.isProper) return@Comparator if (pa.isProper) 1 else -1
        if (pa.readingRank != pb.readingRank) return@Comparator pa.readingRank.compareTo(pb.readingRank)
        pa.id.compareTo(pb.id)
    }

    /** Projection-only order for lists without match classes (groups, related sections; GLB-013). */
    fun projComparator(cantoFirst: Boolean): Comparator<Proj> = Comparator { pa, pb ->
        if (pa.isUnihan != pb.isUnihan) return@Comparator if (pa.isUnihan) 1 else -1
        if (cantoFirst && pa.hasCanto != pb.hasCanto) return@Comparator if (pa.hasCanto) -1 else 1
        val r = compareRank(pa, pb)
        if (r != 0) return@Comparator r
        if (pa.charCount != pb.charCount) return@Comparator pa.charCount.compareTo(pb.charCount)
        if (pa.isProper != pb.isProper) return@Comparator if (pa.isProper) 1 else -1
        if (pa.readingRank != pb.readingRank) return@Comparator pa.readingRank.compareTo(pb.readingRank)
        pa.id.compareTo(pb.id)
    }

    /**
     * The one entry that stands for a bare headword (a segment's default reading, a decomposition
     * row): dictionary entries before Unicode-only ones, then the dataset's primary-reading rank
     * (HSK level, the character's most common reading, common before proper noun), then the tie
     * rule of GLB-013 and the id.
     */
    val primary: Comparator<Proj> = Comparator { pa, pb ->
        if (pa.isUnihan != pb.isUnihan) return@Comparator if (pa.isUnihan) 1 else -1
        if (pa.readingRank != pb.readingRank) return@Comparator pa.readingRank.compareTo(pb.readingRank)
        if (pa.isProper != pb.isProper) return@Comparator if (pa.isProper) 1 else -1
        if (pa.freqRank != pb.freqRank) return@Comparator pa.freqRank.compareTo(pb.freqRank)
        pa.id.compareTo(pb.id)
    }

    fun mergeId(p: Proj, script: Script): Int = if (script == Script.SIMPLIFIED) p.mergeSimp else p.mergeTrad

    /**
     * Drops later members of a merge group already represented in the list (SRCH-084): a merged
     * card appears once, at the position of its best-ranked member.
     */
    fun <T> dedupeMerged(rows: List<T>, script: Script, proj: (T) -> Proj): List<T> {
        var seen: HashSet<Int>? = null
        var out: ArrayList<T>? = null
        for ((i, r) in rows.withIndex()) {
            val g = mergeId(proj(r), script)
            if (g >= 0) {
                val s = seen ?: HashSet<Int>().also { seen = it }
                if (!s.add(g)) {
                    if (out == null) out = ArrayList(rows.subList(0, i))
                    continue
                }
            }
            out?.add(r)
        }
        return out ?: rows
    }
}
