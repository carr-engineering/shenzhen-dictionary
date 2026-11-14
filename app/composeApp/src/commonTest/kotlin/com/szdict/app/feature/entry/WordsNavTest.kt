package com.szdict.app.feature.entry

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WordsNavTest {
    @Test
    fun labelIndicesFollowTheEmittedItems() {
        // nav(0) spacer(1) | label(2) 3 rows(3..5) more(6) | gap(7) label(8) 2 rows(9,10) | gap(11) label(12) …
        assertContentEquals(intArrayOf(2, 8, 12), wordsLabelIndices(listOf(3, 2, 25), listOf(true, false, true), nav = true))
        // Without the chip row everything moves up by one.
        assertContentEquals(intArrayOf(1, 7, 11), wordsLabelIndices(listOf(3, 2, 25), listOf(true, false, true), nav = false))
    }

    @Test
    fun sectionUnderTheChipRow() {
        val labels = intArrayOf(2, 8, 12)
        assertEquals(0, wordsSectionAt(labels, 0))
        assertEquals(0, wordsSectionAt(labels, 7)) // the gap still ends the first section
        assertEquals(1, wordsSectionAt(labels, 8))
        assertEquals(2, wordsSectionAt(labels, 40))
    }
}
