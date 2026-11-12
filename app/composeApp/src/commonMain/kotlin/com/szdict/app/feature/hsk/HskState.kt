package com.szdict.app.feature.hsk

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.szdict.app.AppSession
import com.szdict.app.Destination
import com.szdict.core.api.DictMode
import com.szdict.core.api.Script
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Session-scoped HSK state: the chosen view (HSK-016), one cached grid model and scroll position
 * per view (HSK-061, HSK-070), and latest-only loading (HSK-062). It lives as long as the session,
 * so leaving and returning to the destination — or a configuration change — rebuilds nothing.
 * Nothing here is persisted across launches (HSK-071, SET-014).
 */
class HskState internal constructor(private val session: AppSession) {
    var section by mutableStateOf(HskSection.WORDS)

    /** Mode of a link-opened HSK screen (`/{mode}/hsk`, GLB-110); null = the persisted mode. */
    var linkMode by mutableStateOf<DictMode?>(null)
        private set

    /** A level to bring to the top once its header exists (deep link, or a chip tapped while loading). */
    var pendingLevel by mutableStateOf<Int?>(null)

    /** The level at the top of the Words grid once it is scrolled — the `{level}` of the route (GLB-110); null at the top. */
    var shownLevel by mutableStateOf<Int?>(null)

    class Slot internal constructor() {
        var model by mutableStateOf<HskGridModel?>(null)
            internal set
        var loading by mutableStateOf(false)
            internal set
        var error by mutableStateOf<String?>(null)
            internal set
        val grid = LazyStaggeredGridState()
        internal var wanted: HskIdentity? = null
        internal var job: Job? = null
    }

    private val slots = HskSection.entries.associateWith { Slot() }
    private var linkWatch: Job? = null

    fun slot(section: HskSection): Slot = slots.getValue(section)

    /**
     * Opens the destination's view for a route (`/{mode}/hsk[/{level}]`). The mode is route-scoped:
     * it lasts until the user leaves the HSK destination and never touches the persisted mode.
     */
    fun openRoute(mode: DictMode, level: Int?) {
        linkMode = mode.takeIf { it != session.mode }
        section = HskSection.WORDS
        pendingLevel = level
        linkWatch?.cancel()
        linkWatch = if (linkMode == null) null else session.scope.launch {
            snapshotFlow { session.destination to session.mode }.first { (destination, persisted) ->
                destination != Destination.HSK || persisted == linkMode
            }
            linkMode = null
        }
    }

    /** Starts (or keeps) the load for this identity; a newer identity supersedes an in-flight one. */
    fun ensure(section: HskSection, mode: DictMode, script: Script) {
        val slot = slot(section)
        val identity = HskIdentity(mode, script, section, session.dataGeneration)
        if (slot.wanted == identity && (slot.job?.isActive == true || slot.model?.identity == identity)) return
        slot.job?.cancel()
        slot.wanted = identity
        slot.loading = true
        slot.error = null
        slot.job = session.scope.launch {
            try {
                load(slot, identity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (slot.wanted == identity) slot.error = e.message ?: e.toString()
            } finally {
                if (slot.wanted == identity) slot.loading = false
            }
        }
    }

    private suspend fun load(slot: Slot, identity: HskIdentity) {
        val dictionary = session.dictionary(identity.mode)
        val levels = hskLevelCounts(dictionary.hskLevels(), identity.section)
        val loaded = LinkedHashMap<Int, List<HskGridItem.Entry>>()
        val previous = slot.model
        // First visit: publish level by level so the first frame needs level 1 only (PERF-041).
        // A rebuild (mode or script change) keeps the previous grid until the new one is whole (GLB-030).
        val progressive = previous == null || previous.items.isEmpty()
        for (level in levels) {
            val range = level.level..level.level
            loaded[level.level] = if (identity.section == HskSection.WORDS) {
                hskWordItems(dictionary.hsk(range, identity.script))
            } else {
                hskCharItems(dictionary.hskChars(range, identity.script))
            }
            if (slot.wanted != identity) return
            if (progressive) slot.model = buildHskGrid(identity, levels, HashMap(loaded))
        }
        if (slot.wanted != identity) return
        val model = buildHskGrid(identity, levels, loaded)
        val topLevel = previous?.levelAt(slot.grid.firstVisibleItemIndex)
        slot.model = model
        // HSK-085: after a mode switch, show the level that was at the top before it.
        if (!progressive && previous != null && previous.identity.mode != identity.mode && topLevel != null) {
            slot.grid.requestScrollToItem(model.headerIndexByLevel[topLevel] ?: 0)
        }
    }
}

/** One [HskState] per session (there is one session per process). */
object HskStates {
    private var owner: AppSession? = null
    private var state: HskState? = null

    fun of(session: AppSession): HskState {
        val current = state
        if (current != null && owner === session) return current
        return HskState(session).also { owner = session; state = it }
    }
}
