package com.szdict.app.feature.entry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.CharacterDecomposition
import com.szdict.core.api.EntryCard
import com.szdict.core.api.EntryDetail
import com.szdict.core.api.Example
import com.szdict.core.api.RelatedKind
import com.szdict.core.api.RelatedPage
import engineering.carr.hanzianimator.core.StrokeOrder

/** Content state of a route (ENT-010). */
internal sealed interface EntryLoad {
    data object Loading : EntryLoad
    data class Ready(val detail: EntryDetail) : EntryLoad
    data class Error(val message: String) : EntryLoad
}

/** Decomposition read state (ENT-125). */
internal sealed interface DecompLoad {
    data object Loading : DecompLoad
    data object Unavailable : DecompLoad
    data class Ready(val characters: List<CharacterDecomposition>) : DecompLoad
}

/** One WORDS-tab section with its loaded pages (ENT-064). */
@Stable
internal class RelatedSection(first: RelatedPage) {
    val kind: RelatedKind = first.kind
    val word: String = first.word
    var total by mutableStateOf(first.total)
    val cards = mutableStateListOf<EntryCard>().apply { addAll(first.cards) }
    var loading by mutableStateOf(false)

    /** Rows fetched so far, including ones dropped by de-duplication: the next page's offset. */
    var fetched: Int = first.offset + first.cards.size

    val hasMore: Boolean get() = fetched < total

    fun append(page: RelatedPage) {
        total = page.total
        fetched = page.offset + page.cards.size
        val seen = cards.mapTo(HashSet()) { it.id }
        page.cards.forEach { if (seen.add(it.id)) cards += it }
        if (page.cards.isEmpty()) total = minOf(total, fetched)
    }
}

/** Everything derived from one [EntryDetail] instance; replaced when the entry is re-fetched (ENT-092). */
@Stable
internal class EntryDerived(val detail: EntryDetail) {
    var examples by mutableStateOf<List<Example>?>(null)
    val passages = HashMap<Int, AnalysedPassage>()
    var decomp by mutableStateOf<DecompLoad>(DecompLoad.Loading)
    val related: List<RelatedSection> = detail.related.filter { it.total > 0 && it.cards.isNotEmpty() }.map { page ->
        // ENT-062: one entry never appears twice in a section.
        RelatedSection(page.copy(cards = page.cards.distinctBy { it.id }))
    }
}

/**
 * In-memory content of a route, kept while the route is covered by a nested one so coming back
 * shows it at once with its saved scroll positions (ENT-033, MED-081). Bounded; main-thread only.
 */
internal class EntryRouteCache private constructor() {
    var derived: EntryDerived? = null
    var strokes: Map<String, StrokeOrder>? = null
    var strokesFor: List<String> = emptyList()

    fun derivedFor(detail: EntryDetail): EntryDerived =
        derived?.takeIf { it.detail === detail } ?: EntryDerived(detail).also { derived = it }

    companion object {
        private const val MAX_ROUTES = 12
        private val routes = LinkedHashMap<Long, EntryRouteCache>()

        fun of(routeKey: Long): EntryRouteCache {
            routes.remove(routeKey)?.let {
                routes[routeKey] = it
                return it
            }
            val created = EntryRouteCache()
            routes[routeKey] = created
            while (routes.size > MAX_ROUTES) routes.remove(routes.keys.first())
            return created
        }
    }
}

/** A saveable set of string keys (expanded parts, expanded blocks…): route-scoped state (GLB-050). */
@Composable
internal fun rememberSaveableKeySet(vararg inputs: Any?): MutableState<Set<String>> =
    rememberSaveable(*inputs, saver = listSaver(save = { it.value.toList() }, restore = { mutableStateOf(it.toSet()) })) {
        mutableStateOf(emptySet())
    }

internal fun MutableState<Set<String>>.toggle(key: String) {
    value = if (key in value) value - key else value + key
}
