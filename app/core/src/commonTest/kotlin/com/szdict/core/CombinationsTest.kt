package com.szdict.core

import com.szdict.core.engine.Combinations
import com.szdict.core.engine.LatticeEdge
import com.szdict.core.engine.LatticePath
import com.szdict.core.store.BigramBlock
import com.szdict.core.store.Codec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure parts of the combinations (SRCH-062…065, DATA-156): page decoding, rescoring, policy, grouping. */
class CombinationsTest {
    private fun w(from: Int, to: Int, id: Int, head: String) = Combinations.Word(from, to, id, head)

    @Test
    fun groupingMergesSequencesWithTheSameBoundaries() {
        // jiu dian zhong: [九/就][点钟], [酒店][中/种] — rows ranked by their best sequence.
        val kept = listOf(
            listOf(w(0, 1, 9, "九"), w(1, 3, 20, "点钟")) to -10.0,
            listOf(w(0, 2, 30, "酒店"), w(2, 3, 40, "中")) to -11.0,
            listOf(w(0, 2, 30, "酒店"), w(2, 3, 41, "种")) to -11.5,
            listOf(w(0, 1, 10, "就"), w(1, 3, 20, "点钟")) to -12.0,
        )
        val rows = Combinations.group(kept)
        assertEquals(2, rows.size)
        assertEquals(listOf(listOf(9, 10), listOf(20)), rows[0].slots.map { it.toList() })
        assertEquals(listOf(listOf(30), listOf(40, 41)), rows[1].slots.map { it.toList() })
        assertEquals(-10.0, rows[0].score)
    }

    @Test
    fun groupingLimitsRowsAndAlternatives() {
        // Five homophones of one slot keep four; one headword with two entries (readings) counts once.
        val heads = listOf("去", "区", "取", "曲", "趣")
        val kept = heads.mapIndexed { i, h -> listOf(w(0, 1, 1, "我"), w(1, 2, 100 + i, h)) to -10.0 - i } +
            (listOf(w(0, 1, 1, "我"), w(1, 2, 999, "去")) to -20.0)
        val row = Combinations.group(kept).single()
        assertEquals(listOf(100, 101, 102, 103), row.slots[1].toList())
        // Rows beyond MAX_ROWS are dropped, in rank order.
        val many = (0 until 5).map { k -> listOf(w(0, 1 + k, k, "a$k"), w(1 + k, 9, 50 + k, "b$k")) to -1.0 * k }
        assertEquals(listOf(0, 1, 2), Combinations.group(many).map { it.slots[0][0] })
        assertEquals(Combinations.MAX_ROWS, 3)
        assertEquals(Combinations.MAX_ALTS, 4)
    }

    @Test
    fun policyHasFloorAndMarginOnly() {
        class C(val score: Double, val syl: Int)
        val cands = listOf(C(-10.0, 2), C(-13.9, 3), C(-14.1, 4), C(-13.5, 2), C(-30.0, 5))
        // margin 4 from the best; floor -6.5 per syllable (-13.5 >= -13.0 fails for 2 syllables)
        assertEquals(listOf(-10.0, -13.9), Combinations.select(cands, { it.score }, { it.syl }).map { it.score })
        // The optional minimum share is one argument away.
        val shared = Combinations.select(cands, { it.score }, { it.syl }, minShare = 0.05)
        assertEquals(listOf(-10.0), shared.map { it.score })
        assertTrue(Combinations.select(emptyList<C>(), { it.score }, { it.syl }).isEmpty())
    }

    @Test
    fun trigramPageDecodes() {
        // Page of w1 = 5 starting at w2 = 7: contexts (5,7) -> {3, 9}, (5,12) -> {4}.
        val values = DoubleArray(256) { -it / 8.0 }
        val blob = byteArrayOf(
            0, 8, 2, 3, 6, 1, 2, // w2 +0, backoff code 8, 2 successors: 3, 3+6=9, codes 1, 2
            5, 0, 1, 4, 16, // w2 +5 = 12, backoff code 0, 1 successor: 4, code 16
        )
        val got = LinkedHashMap<Long, BigramBlock>()
        Codec.trigramPage((5L shl 17) or 7L, blob, values, values) { k, b -> got[k] = b }
        assertEquals(listOf(Combinations.contextKey(5, 7), Combinations.contextKey(5, 12)), got.keys.toList())
        val a = got.getValue(Combinations.contextKey(5, 7))
        assertEquals(-1.0, a.backoff)
        assertContentEquals(intArrayOf(3, 9), a.successors)
        assertEquals(-0.25, a.lookup(9))
        assertEquals(-2.0, got.getValue(Combinations.contextKey(5, 12)).lookup(4))
    }

    @Test
    fun rescoringReplacesTheBigramConditional() {
        fun e(from: Int, to: Int, word: Int, uni: Double) = LatticeEdge(from, to, word, uni, 0.0, word, 1)
        // A = 1 2 3 (bigram score -6), B = 1 4 3 (-5): the trigram (1,2) -> 3 at -0.5 instead of the bigram
        // conditional of 3 after 2 (-3) lifts A to -3.5.
        val a = LatticePath(listOf(e(0, 1, 1, -2.0), e(1, 2, 2, -1.0), e(2, 3, 3, -9.0)), -6.0)
        val b = LatticePath(listOf(e(0, 1, 1, -2.0), e(1, 2, 4, -1.0), e(2, 3, 3, -9.0)), -5.0)
        val bigrams = mapOf(2 to BigramBlock(-1.0, intArrayOf(3), doubleArrayOf(-3.0)), 4 to BigramBlock(-1.0, intArrayOf(), doubleArrayOf()))
        assertEquals(setOf(Combinations.contextKey(1, 2), Combinations.contextKey(1, 4)), Combinations.contexts(listOf(a, b)))
        val tri = mapOf(
            Combinations.contextKey(1, 2) to BigramBlock(-0.7, intArrayOf(3), doubleArrayOf(-0.5)),
            // (1,4) has a row without 3: its backoff (-0.25) is added to the bigram conditional.
            Combinations.contextKey(1, 4) to BigramBlock(-0.25, intArrayOf(7), doubleArrayOf(-0.1)),
        )
        val out = Combinations.rescore(listOf(b, a), bigrams, tri)
        assertEquals(listOf(2, 4), out.map { it.edges[1].word })
        assertEquals(-3.5, out[0].score, 1e-9)
        assertEquals(-5.25, out[1].score, 1e-9)
        // Without trigram rows nothing changes.
        assertEquals(listOf(b, a), Combinations.rescore(listOf(b, a), bigrams, emptyMap()))
    }
}
