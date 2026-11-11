package com.szdict.app.feature.entry

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class EntryTabsTest {
    @Test
    fun strokesTabOnlyWithStrokeData() {
        assertEquals(listOf(EntryTab.DICT, EntryTab.WORDS), entryTabs(hasStrokes = false))
        assertEquals(listOf(EntryTab.DICT, EntryTab.STROKES, EntryTab.WORDS), entryTabs(hasStrokes = true))
    }

    @Test
    fun restoredTabFallsBackToDictWhenUnavailable() {
        val without = entryTabs(hasStrokes = false)
        assertEquals(EntryTab.DICT, restoredTab(without, "STROKES"))   // ENT-034
        assertEquals(EntryTab.WORDS, restoredTab(without, "WORDS"))
        assertEquals(EntryTab.DICT, restoredTab(without, null))
        assertEquals(EntryTab.DICT, restoredTab(without, "garbage"))
        assertEquals(EntryTab.STROKES, restoredTab(entryTabs(true), "STROKES"))
    }

    @Test
    fun pageFollowsTheRouteTabAcrossTabSetChanges() {
        val with = entryTabs(true)
        val without = entryTabs(false)
        assertEquals(2, pageForTab(with, EntryTab.WORDS))
        assertEquals(1, pageForTab(without, EntryTab.WORDS))
        assertEquals(0, pageForTab(without, EntryTab.STROKES))
        assertEquals("WORDS", tabKey(without, 1))
        assertEquals("stale-2", tabKey(without, 2))   // ENT-035
    }

    @Test
    fun strokePaneLayoutRules() {
        assertEquals(1, strokeColumns(599.dp))
        assertEquals(2, strokeColumns(600.dp))
        assertEquals(3, strokeColumns(840.dp))
        assertEquals(180.dp, strokeCanvasCap(400.dp))   // MED-060: clamped to 180–420
        assertEquals(340.dp, strokeCanvasCap(600.dp))
        assertEquals(420.dp, strokeCanvasCap(1200.dp))
    }
}
