package com.szdict.app.feature.hsk

import com.szdict.core.api.DictMode
import com.szdict.core.api.EntryCard
import com.szdict.core.api.HskCard
import com.szdict.core.api.HskCharCard
import com.szdict.core.api.HskLevelInfo
import com.szdict.core.api.Script

/** The two views of the HSK destination (HSK-016). */
enum class HskSection { WORDS, CHARACTERS }

/** A level of the selector and its header count (HSK-011, HSK-021): syllabus words or characters. */
data class HskLevelCount(val level: Int, val count: Int)

/** What a grid model was built for; a load is the latest only for its identity (HSK-062). */
data class HskIdentity(val mode: DictMode, val script: Script, val section: HskSection, val generation: Int)

sealed interface HskGridItem {
    /** Unique and stable across scrolling (HSK-026); a String so the lazy grid can save it. */
    val key: String
    val level: Int

    data class LevelHeader(override val level: Int, val count: Int) : HskGridItem {
        override val key: String get() = "header-$level"
    }

    data class Entry(override val key: String, override val level: Int, val card: EntryCard) : HskGridItem
}

/**
 * The flattened grid (HSK-010): every loaded level in ascending order, each introduced by a
 * full-width header. Rows keep the dataset's storage order (HSK-030, HSK-032); nothing is sorted here.
 */
class HskGridModel(
    val identity: HskIdentity,
    /** Every non-empty level of the dataset, for the selector — loaded or not. */
    val levels: List<HskLevelCount>,
    val items: List<HskGridItem>,
    val headerIndexByLevel: Map<Int, Int>,
    /** True once every level of [levels] has been loaded. */
    val complete: Boolean,
) {
    fun levelAt(index: Int): Int? = items.getOrNull(index)?.level ?: levels.firstOrNull()?.level
}

/** Selector levels for a section; levels with nothing to show are omitted (HSK-081). */
fun hskLevelCounts(infos: List<HskLevelInfo>, section: HskSection): List<HskLevelCount> =
    infos.map { HskLevelCount(it.level, if (section == HskSection.WORDS) it.wordCount else it.charCount) }
        .filter { it.count > 0 && it.level in 1..7 }
        .sortedBy { it.level }

fun hskWordItems(cards: List<HskCard>): List<HskGridItem.Entry> =
    cards.map { HskGridItem.Entry("w-${it.key}", it.level, it.card) }

fun hskCharItems(cards: List<HskCharCard>): List<HskGridItem.Entry> =
    cards.map { HskGridItem.Entry("c-${it.level}-${it.character}", it.level, it.card) }

/**
 * Groups [loaded] entries under their level headers. A level that is loaded but empty gets no
 * header (HSK-081); a level not loaded yet is simply absent until its rows arrive. A repeated key
 * is dropped rather than crashing the grid (HSK-026, HSK-067).
 */
fun buildHskGrid(
    identity: HskIdentity,
    levels: List<HskLevelCount>,
    loaded: Map<Int, List<HskGridItem.Entry>>,
): HskGridModel {
    val items = ArrayList<HskGridItem>()
    val headers = LinkedHashMap<Int, Int>()
    val seen = HashSet<String>()
    for (level in levels) {
        val entries = loaded[level.level]?.filter { it.level == level.level && seen.add(it.key) } ?: continue
        if (entries.isEmpty()) continue
        headers[level.level] = items.size
        items += HskGridItem.LevelHeader(level.level, level.count)
        items += entries
    }
    val shown = levels.filter { loaded[it.level]?.isEmpty() != true }
    return HskGridModel(identity, shown, items, headers, complete = levels.all { it.level in loaded })
}

/** HSK-020: 1 column below 600 dp, 2 at 600–839 dp, 3 from 840 dp. */
fun hskColumnCount(widthDp: Float): Int = when {
    widthDp >= 840f -> 3
    widthDp >= 600f -> 2
    else -> 1
}

/** Placeholder card heights of the loading skeleton (HSK-063). */
val HskSkeletonHeights: List<Int> = listOf(138, 164, 124, 178, 146, 156, 132, 172)
