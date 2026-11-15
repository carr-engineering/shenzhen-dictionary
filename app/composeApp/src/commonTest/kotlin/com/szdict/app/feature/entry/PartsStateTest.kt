package com.szdict.app.feature.entry

import com.szdict.core.api.AppearsInPage
import com.szdict.core.api.DictError
import com.szdict.core.api.DictException
import com.szdict.core.api.FakeDictionary
import com.szdict.core.api.Script
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PartsStateTest {
    private val dict = FakeDictionary()
    private val s = Script.SIMPLIFIED

    private fun state(characters: List<String>, pageSize: Int = PartsState.PAGE_SIZE) = PartsState(
        characters = characters,
        loadParts = { dict.characterParts(it, s) },
        loadAppears = { c, offset, limit -> dict.appearsIn(c, s, offset, limit) },
        pageSize = pageSize,
    )

    @Test
    fun madeOfLoadsOnceAndKeepsCharactersWithoutData() = runTest {
        val st = state(listOf("你", "好", "我"))
        assertIs<PartsLoad.Loading>(st.made)
        st.ensureParts()
        val ready = assertIs<PartsLoad.Ready>(st.made)
        assertEquals(listOf("你", "好", "我"), ready.characters.map { it.character })
        assertFalse(ready.characters[2].available, "我 has no parts data: the tab says so")
        val ni = assertNotNull(ready.characters[0].root)
        assertEquals(listOf("亻", "尔"), ni.children.map { it.node.glyph })
        assertTrue(ni.children[0].isParentRadical)
        assertEquals(2, ni.children[1].partCount)
        st.ensureParts()   // no second read
        assertTrue(st.made === ready)
    }

    @Test
    fun unavailablePackIsANoteNotAFailure() = runTest {
        val st = PartsState(listOf("好"), { throw DictException(DictError.AssetMissing("packs/decomposition.db")) },
            { _, _, _ -> throw DictException(DictError.AssetMissing("packs/decomposition.db")) })
        st.ensureParts()
        assertIs<PartsLoad.Unavailable>(st.made)
        st.ensureAppears("好")
        val section = assertNotNull(st.appears["好"])
        assertTrue(section.unavailable && !section.loaded && !section.loadingMore)
    }

    @Test
    fun expansionIsLevelByLevelAndCollapsingForgetsTheLevelsBeneath() = runTest {
        val st = state(listOf("你"))
        st.ensureParts()
        val root = assertNotNull((st.made as PartsLoad.Ready).characters[0].root)
        val er = root.children[1]
        val ren = root.children[0]
        assertFalse(st.isExpanded("你", er))
        st.toggle("你", er)
        assertTrue(st.isExpanded("你", er))
        assertEquals(setOf("你/1"), st.expanded)
        st.toggle("你", ren)   // indivisible: nothing to expand
        assertEquals(setOf("你/1"), st.expanded)
        // A nested key beneath 尔 (as a deeper level would add) goes when 尔 collapses.
        val small = er.children[1]
        st.toggle("你", small.copy(children = listOf(small)))
        assertEquals(setOf("你/1", "你/1.1"), st.expanded)
        st.toggle("你", er)
        assertTrue(st.expanded.isEmpty())
    }

    @Test
    fun appearsInPagesUntilTheTotal() = runTest {
        val st = state(listOf("亻"), pageSize = 1)
        val section = assertNotNull(st.appears["亻"])
        assertFalse(section.hasMore, "nothing known before the first page")
        st.ensureAppears("亻")
        assertEquals(2, section.total)
        assertEquals(listOf("你"), section.items.map { it.character })
        assertTrue(section.hasMore)
        assertEquals(1, section.remaining)
        st.ensureAppears("亻")   // the first page is read once
        assertEquals(1, section.items.size)
        st.loadMore("亻")
        assertEquals(listOf("你", "们"), section.items.map { it.character })
        assertFalse(section.hasMore)
        st.loadMore("亻")
        assertEquals(2, section.items.size)
        // Every listed character with an entry is tappable.
        assertTrue(section.items.all { it.card != null })
    }

    @Test
    fun emptyAndShrinkingAppearsIn() = runTest {
        val st = state(listOf("我"))
        st.ensureAppears("我")
        val section = assertNotNull(st.appears["我"])
        assertTrue(section.loaded && section.total == 0 && !section.hasMore)
        // A page past the end (the data changed underneath) ends the list instead of asking again.
        val shrinking = AppearsInSection("x")
        shrinking.append(AppearsInPage("x", 5, 0, emptyList()))
        assertEquals(0, shrinking.total)
        assertFalse(shrinking.hasMore)
    }

    @Test
    fun partsEndTheDictListAfterSensesAndExamples() {
        // DICT list: senses 0, examples 1, `PARTS` label 2, sticky chip row 3, then per character
        // heading / made of / appears in.
        assertEquals(listOf(0, 1, 2, 3), listOf(PartsLayout.SENSES_INDEX, PartsLayout.EXAMPLES_INDEX, PartsLayout.LABEL_INDEX, PartsLayout.NAV_INDEX))
        assertEquals(4, PartsLayout.itemIndex(0, PartsSection.HEAD))
        assertEquals(5, PartsLayout.itemIndex(0, PartsSection.MADE_OF))
        assertEquals(9, PartsLayout.itemIndex(1, PartsSection.APPEARS_IN))
        // Above the blocks (senses, examples, label, the row itself) the first block is current.
        for (i in 0..4) assertEquals(0 to PartsSection.HEAD, PartsLayout.at(i, 2))
        assertEquals(0 to PartsSection.APPEARS_IN, PartsLayout.at(6, 2))
        assertEquals(1 to PartsSection.HEAD, PartsLayout.at(7, 2))
        assertEquals(1 to PartsSection.APPEARS_IN, PartsLayout.at(99, 2))
        for (c in 0..2) for (sec in PartsSection.entries) assertEquals(c to sec, PartsLayout.at(PartsLayout.itemIndex(c, sec), 3))
        assertEquals("parts-made_of-1", PartsLayout.key(1, PartsSection.MADE_OF))
    }

    @Test
    fun chipRowScrollsInFlowThenSticks() {
        // In flow while the senses, examples or label are at the top, and while the row itself is.
        assertFalse(PartsLayout.isStuck(0, 0))
        assertFalse(PartsLayout.isStuck(1, 500))
        assertFalse(PartsLayout.isStuck(PartsLayout.LABEL_INDEX, 30))
        assertFalse(PartsLayout.isStuck(PartsLayout.NAV_INDEX, 0))
        // Stuck as soon as its own place scrolls under the top, for the rest of the list.
        assertTrue(PartsLayout.isStuck(PartsLayout.NAV_INDEX, 1))
        assertTrue(PartsLayout.isStuck(PartsLayout.itemIndex(0, PartsSection.HEAD), 0))
        assertTrue(PartsLayout.isStuck(PartsLayout.itemIndex(4, PartsSection.APPEARS_IN), 200))
    }

    @Test
    fun oneChipPerCharacterAndSectionWithSomethingToShow() {
        val m = PartsSection.MADE_OF
        val a = PartsSection.APPEARS_IN
        // 你好: both characters have parts and are used elsewhere.
        val both = PartsLayout.chips(listOf(true, true), listOf(true, true))
        assertEquals(listOf(PartsChip(0, m), PartsChip(0, a), PartsChip(1, m), PartsChip(1, a)), both)
        // An indivisible character has no "Made of" chip, one used nowhere no "Appears in" chip.
        assertEquals(listOf(PartsChip(0, a), PartsChip(1, m)), PartsLayout.chips(listOf(false, true), listOf(true, false)))
        // 一 alone: a single chip, which the row does not show.
        assertEquals(1, PartsLayout.chips(listOf(false), listOf(true)).size)
        // The block at the top selects its chip; a heading counts as its "Made of".
        assertEquals(0, PartsLayout.selectedChip(both, 0 to PartsSection.HEAD))
        assertEquals(1, PartsLayout.selectedChip(both, 0 to a))
        assertEquals(2, PartsLayout.selectedChip(both, 1 to PartsSection.HEAD))
        assertEquals(3, PartsLayout.selectedChip(both, 1 to a))
        // Before the row sticks (senses at the top) the first chip is selected.
        assertEquals(0, PartsLayout.selectedChip(both, PartsLayout.at(PartsLayout.SENSES_INDEX, 2)))
        // Without a chip of its own the block keeps the one before it (or the first).
        val sparse = listOf(PartsChip(0, a), PartsChip(1, a))
        assertEquals(0, PartsLayout.selectedChip(sparse, 0 to m))
        assertEquals(0, PartsLayout.selectedChip(sparse, 1 to m))
        assertEquals(1, PartsLayout.selectedChip(sparse, 1 to a))
        // Targets are DICT list items: "Made of" jumps to the character's heading, "Appears in" to its section.
        assertEquals(7, PartsLayout.chipTarget(PartsChip(1, m)))
        assertEquals(PartsLayout.itemIndex(1, PartsSection.HEAD), PartsLayout.chipTarget(PartsChip(1, m)))
        assertEquals(PartsLayout.itemIndex(1, a), PartsLayout.chipTarget(PartsChip(1, a)))
        assertTrue(both.all { PartsLayout.chipTarget(it) > PartsLayout.NAV_INDEX }, "every target lies below the sticky row")
    }

    @Test
    fun noPartsSectionWithoutHanzi() {
        assertFalse(hasPartsSection(emptyList()), "a Latin-only headword (T, OK…) lists no Hanzi")
        assertTrue(hasPartsSection(listOf("你", "好")))
    }

    @Test
    fun partsIsNoLongerATab() {
        assertEquals(listOf(EntryTab.DICT, EntryTab.STROKES, EntryTab.WORDS), entryTabs(hasStrokes = true))
        // A route or deep link saved on the old PARTS tab opens DICT, where Parts now lives.
        assertEquals(EntryTab.DICT, restoredTab(entryTabs(true), "PARTS"))
        assertEquals(0, pageForTab(entryTabs(false), restoredTab(entryTabs(false), "PARTS")))
    }
}
