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

    private fun state(characters: List<String>, pageSize: Int = PartsTabState.PAGE_SIZE) = PartsTabState(
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
        val st = PartsTabState(listOf("好"), { throw DictException(DictError.AssetMissing("packs/decomposition.db")) },
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
    fun chipTargetsAndTheSectionAtTheTop() {
        // Sticky chip row at 0, then heading / made of / appears in per character.
        assertEquals(1, PartsLayout.itemIndex(0, PartsSection.HEAD, hasNav = true))
        assertEquals(2, PartsLayout.itemIndex(0, PartsSection.MADE_OF, hasNav = true))
        assertEquals(6, PartsLayout.itemIndex(1, PartsSection.APPEARS_IN, hasNav = true))
        assertEquals(0 to PartsSection.HEAD, PartsLayout.at(0, 2, hasNav = true))
        assertEquals(0 to PartsSection.APPEARS_IN, PartsLayout.at(3, 2, hasNav = true))
        assertEquals(1 to PartsSection.HEAD, PartsLayout.at(4, 2, hasNav = true))
        assertEquals(1 to PartsSection.APPEARS_IN, PartsLayout.at(99, 2, hasNav = true))
        for (c in 0..2) for (sec in PartsSection.entries) assertEquals(c to sec, PartsLayout.at(PartsLayout.itemIndex(c, sec, true), 3, true))
        assertEquals("parts-made_of-1", PartsLayout.key(1, PartsSection.MADE_OF))
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
        // Without a chip of its own the block keeps the one before it (or the first).
        val sparse = listOf(PartsChip(0, a), PartsChip(1, a))
        assertEquals(0, PartsLayout.selectedChip(sparse, 0 to m))
        assertEquals(0, PartsLayout.selectedChip(sparse, 1 to m))
        assertEquals(1, PartsLayout.selectedChip(sparse, 1 to a))
        // "Made of" jumps to the character's heading, "Appears in" to its section.
        assertEquals(PartsLayout.itemIndex(1, PartsSection.HEAD, true), PartsLayout.chipTarget(PartsChip(1, m)))
        assertEquals(PartsLayout.itemIndex(1, a, true), PartsLayout.chipTarget(PartsChip(1, a)))
    }

    @Test
    fun partsTabNeedsHanzi() {
        assertEquals(listOf(EntryTab.DICT, EntryTab.WORDS, EntryTab.PARTS), entryTabs(hasStrokes = false, hasParts = true))
        assertEquals(listOf(EntryTab.DICT, EntryTab.STROKES, EntryTab.WORDS, EntryTab.PARTS), entryTabs(hasStrokes = true, hasParts = true))
        assertEquals(listOf(EntryTab.DICT, EntryTab.WORDS), entryTabs(hasStrokes = false, hasParts = false))
        assertEquals(EntryTab.PARTS, restoredTab(entryTabs(true, true), "PARTS"))
        assertEquals(EntryTab.DICT, restoredTab(entryTabs(true, false), "PARTS"))
    }
}
