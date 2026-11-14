package com.szdict.app.ui.kit

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The passage layout decisions (GLB-120): sizes per state and the placement of readings. Text
 * measurement is not available in unit tests, so line breaking is checked on a fixed-advance model
 * fed with the same metrics the renderer gives Compose.
 */
class PassageLayoutTest {
    private val fontSize = PassageFontSize.value

    @Test
    fun hidingReadingsOnlyChangesLineHeight() {
        for (size in listOf(22f, fontSize, 34f)) {
            val shown = passageMetrics(size, showReadings = true)
            val hidden = passageMetrics(size, showReadings = false)
            assertEquals(shown.fontSize, hidden.fontSize)
            assertEquals(shown.letterSpacingEm, hidden.letterSpacingEm)
            assertTrue(hidden.lineHeight < shown.lineHeight, "the reading band is gone when hidden")
            assertEquals(size * PLAIN_LINE_HEIGHT_EM, hidden.lineHeight, 0.001f)
            // The band holds a reading line (about 1.2 em of the reading size) above the hanzi's ascent.
            assertEquals(shown.rubyFontSize * RUBY_BAND_EM, shown.lineHeight - hidden.lineHeight, 0.001f)
        }
    }

    @Test
    fun passageIsBiggerThanTheHeadlineButNotHuge() {
        // Headline small is 24 sp; the passage is about 15–20 % larger, readings stay 12 sp.
        assertTrue(fontSize in 24f * 1.14f..24f * 1.2f)
        assertEquals(12f, passageMetrics(fontSize, true).rubyFontSize, 0.01f)
    }

    /** Character kinds of the fixed-advance model, with their advance in hanzi ems. */
    private fun advance(c: Char, matched: Boolean, metrics: PassageMetrics): Float {
        val base = when {
            c.code < 0x80 -> 0.55f
            else -> 1f
        }
        return (base + if (matched) metrics.letterSpacingEm else 0f) * metrics.fontSize
    }

    /** Greedy line breaking per character (CJK breaks anywhere): (line, x) of each character. */
    private fun layout(text: String, matched: (Int) -> Boolean, width: Float, metrics: PassageMetrics): List<Pair<Int, Float>> {
        var line = 0
        var x = 0f
        return text.indices.map { i ->
            val a = advance(text[i], matched(i), metrics)
            if (x > 0f && x + a > width) {
                line++
                x = 0f
            }
            (line to x).also { x += a }
        }
    }

    @Test
    fun everyCharacterKeepsItsLineAndColumnWhenReadingsAreToggled() {
        val text = "今天天气很好，我们去公园散步吧。Wi-Fi密码是1234，双胞胎在广场上唱歌。"
        val matched = { i: Int -> text[i].code >= 0x4E00 }
        for (dpWidth in listOf(280f, 360f, 412f, 600f)) {
            val shown = layout(text, matched, dpWidth, passageMetrics(fontSize, true))
            val hidden = layout(text, matched, dpWidth, passageMetrics(fontSize, false))
            assertEquals(shown, hidden, "width $dpWidth")
            assertTrue(shown.last().first > 0, "the sample wraps at $dpWidth")
        }
    }

    // Widths of readings in reading ems, measured on Roboto Medium / SF Pro Medium (the wider).
    private val em = mapOf(
        "de" to 1.20f, "guó" to 1.98f, "zhōng" to 2.78f, "shuāng" to 3.28f, "zhuàng" to 3.27f,
        "chuáng" to 3.29f, "bāo" to 1.85f, "tāi" to 1.62f, "guǎng" to 2.73f, "chǎng" to 2.74f,
        "gwong2" to 3.64f, "dung1" to 2.56f, "ngaang6" to 3.89f, "gwaang2" to 4.08f, "kwaang3" to 4.03f,
    )

    /** Slots for consecutive characters of one line at the default size (density 1: px = sp). */
    private fun row(vararg readings: String, line: Int = 0, startCell: Int = 0): List<RubySlot> {
        val metrics = passageMetrics(fontSize, true)
        val cell = metrics.fontSize * (1f + metrics.letterSpacingEm)
        return readings.mapIndexed { k, r ->
            RubySlot(line, (startCell + k + 0.5f) * cell, cell, em.getValue(r) * metrics.rubyFontSize, SYLLABLE_MIN_SCALE)
        }
    }

    private val gap = 2f

    private fun assertNoOverlap(slots: List<RubySlot>, placements: List<RubyPlacement>) {
        val order = slots.indices.sortedBy { slots[it].center }
        for (k in 1 until order.size) {
            val a = order[k - 1]
            val b = order[k]
            if (slots[a].line != slots[b].line) continue
            val aRight = placements[a].left + slots[a].width * placements[a].scale
            assertTrue(aRight + gap <= placements[b].left + 0.01f, "readings $a and $b overlap")
        }
    }

    @Test
    fun ordinarySyllablesAreCentredAtFullSize() {
        val slots = row("zhōng", "guó", "de", "guǎng", "chǎng")
        val placements = placeRuby(slots, 0f, 1000f, gap)
        for ((s, p) in slots.zip(placements)) {
            assertEquals(1f, p.scale)
            assertEquals(s.center, p.left + s.width / 2f, 0.01f)
        }
        assertNoOverlap(slots, placements)
    }

    @Test
    fun aLongSyllableBorrowsItsNeighboursRoomAndStaysFullSize() {
        val slots = row("bāo", "shuāng", "tāi")
        val placements = placeRuby(slots, 0f, 1000f, gap)
        assertTrue(slots[1].width > slots[1].cellWidth - gap, "shuāng is wider than its cell")
        assertEquals(1f, placements[1].scale)
        assertEquals(slots[1].center, placements[1].left + slots[1].width / 2f, 0.01f)
        assertNoOverlap(slots, placements)
    }

    @Test
    fun aLongSyllableAtTheStartOfALineIsShiftedNotCropped() {
        val slots = row("zhuàng", "de")
        val placements = placeRuby(slots, 0f, 1000f, gap)
        assertEquals(1f, placements[0].scale)
        assertTrue(placements[0].left >= 0f)
        assertNoOverlap(slots, placements)
    }

    @Test
    fun adjacentLongSyllablesShrinkSlightlyAndAlone() {
        val slots = row("de", "shuāng", "chuáng", "de")
        val placements = placeRuby(slots, 0f, 1000f, gap)
        assertEquals(1f, placements[0].scale)
        assertEquals(1f, placements[3].scale)
        for (i in 1..2) assertTrue(placements[i].scale in 0.85f..1f, "pinyin scale ${placements[i].scale}")
        assertNoOverlap(slots, placements)
    }

    @Test
    fun jyutpingWorstCaseStaysAboveTheFloor() {
        val slots = row("de", "gwong2", "dung1", "kwaang3", "gwaang2", "ngaang6")
        val placements = placeRuby(slots, 0f, 1000f, gap)
        assertTrue(placements.all { it.scale >= SYLLABLE_MIN_SCALE })
        // gwong2 next to short neighbours keeps its full size; the longest three in a row shrink, without touching.
        assertEquals(1f, placements[1].scale)
        assertEquals(1f, placements[2].scale)
        assertNoOverlap(slots, placements)
    }

    @Test
    fun readingsOnDifferentLinesDoNotInteract() {
        val first = row("shuāng", line = 0, startCell = 3)
        val second = row("chuáng", line = 1, startCell = 3)
        val placements = placeRuby(first + second, 0f, 1000f, gap)
        // Same x, different lines: each is centred at full size, as if alone.
        for ((s, p) in (first + second).zip(placements)) {
            assertEquals(1f, p.scale)
            assertTrue(abs(s.center - (p.left + s.width / 2f)) < 0.01f)
        }
    }

    @Test
    fun aWholeWordReadingExtendsIntoFreeRoomInsteadOfBeingCropped() {
        val metrics = passageMetrics(fontSize, true)
        val cell = metrics.fontSize * (1f + metrics.letterSpacingEm)
        val word = RubySlot(0, cell * 2f, cell * 2f, cell * 2.6f, WORD_MIN_SCALE)
        val placements = placeRuby(listOf(word), 0f, cell * 10f, gap)
        assertEquals(1f, placements[0].scale)
        assertEquals(word.center, placements[0].left + word.width / 2f, 0.01f)
    }
}
