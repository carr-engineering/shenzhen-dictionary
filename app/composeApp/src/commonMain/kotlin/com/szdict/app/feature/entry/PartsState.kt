package com.szdict.app.feature.entry

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.szdict.core.api.AppearsInItem
import com.szdict.core.api.AppearsInPage
import com.szdict.core.api.CharacterParts
import com.szdict.core.api.PartNode
import kotlin.coroutines.cancellation.CancellationException

/** "Made of" read state of the PARTS tab. */
internal sealed interface PartsLoad {
    data object Loading : PartsLoad
    /** The decomposition pack is absent or could not be fetched (Web offline): the tab says so. */
    data object Unavailable : PartsLoad
    data class Ready(val characters: List<CharacterParts>) : PartsLoad
}

/** "Appears in" of one character: the pages loaded so far (first page, then "show more"). */
@Stable
internal class AppearsInSection(val character: String) {
    var loaded by mutableStateOf(false)
        internal set
    var unavailable by mutableStateOf(false)
        internal set
    var total by mutableStateOf(0)
        internal set
    var loadingMore by mutableStateOf(false)
        internal set
    val items = mutableStateListOf<AppearsInItem>()
    val hasMore: Boolean get() = loaded && items.size < total
    val remaining: Int get() = (total - items.size).coerceAtLeast(0)

    internal fun append(page: AppearsInPage) {
        total = page.total
        val seen = items.mapTo(HashSet()) { it.character }
        page.items.forEach { if (seen.add(it.character)) items += it }
        // A short page past the end (data changed underneath) ends the list.
        if (page.items.isEmpty()) total = items.size
        loaded = true
    }
}

/**
 * State of one route's PARTS tab: the decomposition of every listed character, the expanded parts
 * (`character/path` keys, level by level) and each character's "Appears in" pages. Kept for the
 * route's life by [PartsStates] so coming back from a nested entry shows it at once.
 */
@Stable
internal class PartsTabState(
    val characters: List<String>,
    private val loadParts: suspend (List<String>) -> List<CharacterParts>,
    private val loadAppears: suspend (character: String, offset: Int, limit: Int) -> AppearsInPage,
    private val pageSize: Int = PAGE_SIZE,
) {
    var made by mutableStateOf<PartsLoad>(PartsLoad.Loading)
        private set
    val appears: Map<String, AppearsInSection> = characters.associateWith { AppearsInSection(it) }
    var expanded by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Reads the trees once; failures leave the tab usable with an "unavailable" note (GLB-035). */
    private var partsLoading = false

    suspend fun ensureParts() {
        if (made !is PartsLoad.Loading || partsLoading) return
        partsLoading = true
        made = try {
            PartsLoad.Ready(loadParts(characters))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PartsLoad.Unavailable
        } finally {
            partsLoading = false
        }
    }

    /** First page of [character]'s "Appears in", once. */
    suspend fun ensureAppears(character: String) {
        val section = appears[character] ?: return
        if (section.loaded || section.unavailable || section.loadingMore) return
        fetch(section)
    }

    suspend fun loadMore(character: String) {
        val section = appears[character] ?: return
        if (!section.hasMore || section.loadingMore) return
        fetch(section)
    }

    private suspend fun fetch(section: AppearsInSection) {
        section.loadingMore = true
        try {
            section.append(loadAppears(section.character, section.items.size, pageSize))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!section.loaded) section.unavailable = true
        } finally {
            section.loadingMore = false
        }
    }

    fun isExpanded(character: String, node: PartNode): Boolean = partKey(character, node) in expanded

    fun toggle(character: String, node: PartNode) {
        if (node.children.isEmpty()) return
        val key = partKey(character, node)
        // Collapsing a part also forgets what was open beneath it, so it reopens one level deep.
        expanded = if (key in expanded) expanded.filterTo(LinkedHashSet()) { it != key && !it.startsWith("$key.") } else expanded + key
    }

    companion object {
        const val PAGE_SIZE = 30

        fun partKey(character: String, node: PartNode): String = character + "/" + node.node.path.joinToString(".")
    }
}

/** Route-scoped PARTS states, bounded like [EntryRouteCache]; main-thread only. */
internal object PartsStates {
    private const val MAX = 12
    private val states = LinkedHashMap<EntryDerived, PartsTabState>()

    fun of(derived: EntryDerived, create: () -> PartsTabState): PartsTabState {
        states.remove(derived)?.let { states[derived] = it; return it }
        val created = create()
        states[derived] = created
        while (states.size > MAX) states.remove(states.keys.first())
        return created
    }
}

// ---- Navigation chips: which list item each chip targets (pure, tested) ----

/** One block per character: its heading, its "Made of" and its "Appears in" items, after the chip row. */
internal enum class PartsSection { HEAD, MADE_OF, APPEARS_IN }

internal object PartsLayout {
    /** Index of the sticky chip row; present only when [hasNav]. */
    fun itemIndex(characterIndex: Int, section: PartsSection, hasNav: Boolean): Int =
        (if (hasNav) 1 else 0) + characterIndex * PartsSection.entries.size + section.ordinal

    /** The (character index, section) shown at the top of the list for the first visible item. */
    fun at(firstVisible: Int, characters: Int, hasNav: Boolean): Pair<Int, PartsSection> {
        val i = (firstVisible - if (hasNav) 1 else 0).coerceAtLeast(0)
        val per = PartsSection.entries.size
        return (i / per).coerceIn(0, (characters - 1).coerceAtLeast(0)) to PartsSection.entries[i % per]
    }

    fun key(characterIndex: Int, section: PartsSection): String = "parts-${section.name.lowercase()}-$characterIndex"

    /**
     * The chip row: one chip per (character, section) worth jumping to — "Made of" for a character
     * with parts, "Appears in" for one used in other characters — in reading order. Fewer than two
     * chips means no row at all.
     */
    fun chips(hasMadeOf: List<Boolean>, hasAppearsIn: List<Boolean>): List<PartsChip> = buildList {
        for (i in hasMadeOf.indices) {
            if (hasMadeOf[i]) add(PartsChip(i, PartsSection.MADE_OF))
            if (hasAppearsIn.getOrElse(i) { false }) add(PartsChip(i, PartsSection.APPEARS_IN))
        }
    }

    /** The chip of the block at the top of the list (a heading counts as its "Made of"): the last chip at or before it. */
    fun selectedChip(chips: List<PartsChip>, at: Pair<Int, PartsSection>): Int {
        fun order(c: Int, s: PartsSection) = c * 2 + if (s == PartsSection.APPEARS_IN) 1 else 0
        val here = order(at.first, at.second)
        return chips.indexOfLast { order(it.character, it.section) <= here }.coerceAtLeast(0)
    }

    /** List item a chip scrolls to: a "Made of" chip brings its character's heading under the row. */
    fun chipTarget(chip: PartsChip): Int =
        itemIndex(chip.character, if (chip.section == PartsSection.APPEARS_IN) PartsSection.APPEARS_IN else PartsSection.HEAD, hasNav = true)
}

/** One chip of the PARTS chip row ([PartsLayout.chips]). */
internal data class PartsChip(val character: Int, val section: PartsSection)
