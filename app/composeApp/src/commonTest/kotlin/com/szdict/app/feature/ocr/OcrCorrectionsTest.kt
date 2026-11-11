package com.szdict.app.feature.ocr

import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.DictMode
import com.szdict.core.api.SegmentationMode
import com.szdict.recognition.OcrAlt
import com.szdict.recognition.OcrChar
import com.szdict.recognition.OcrLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OcrCorrectionsTest {
    @Test
    fun anUncorrectedLineIsTheRecognisedText() {
        val line = CorrectedLine(line("你好吗"))
        assertEquals("你好吗", line.text)
        assertFalse(line.corrected)
        assertEquals(listOf(0, 1, 2), (0..2).map(line::charStart))
        assertEquals(1, line.charIndexAt(1))
        assertNull(line.charIndexAt(3))
    }

    @Test
    fun pickingAnAlternativeReplacesOnlyThatPositionAndTheOriginalUndoesIt() {
        val original = line("我己好", alternatives = mapOf(1 to listOf("已", "巳")))
        val line = CorrectedLine(original)
        val picked = line.withPick(1, "已")
        assertEquals("我已好", picked.text)
        assertTrue(picked.isCorrected(1))
        assertFalse(picked.isCorrected(0))
        assertSame(original, picked.original)
        assertEquals("我己好", picked.original.text)
        // Changing the pick, then picking the recognised reading: no correction left.
        assertEquals("我巳好", picked.withPick(1, "巳").text)
        val undone = picked.withPick(1, "己")
        assertEquals("我己好", undone.text)
        assertFalse(undone.corrected)
    }

    @Test
    fun onlyOfferedReadingsCanBePicked() {
        val line = CorrectedLine(line("我己好", alternatives = mapOf(1 to listOf("已"))))
        assertSame(line, line.withPick(1, "人"))
        assertSame(line, line.withPick(1, ""))
        assertSame(line, line.withPick(9, "已"))
        assertSame(line, line.withPick(1, "己"))
    }

    @Test
    fun offsetsStayMappedWhenACorrectionHasAnotherLength() {
        // 𠮷 is a surrogate pair: picking it at index 1 shifts later UTF-16 offsets, not symbol indices.
        val base = line("土吉好")
        val chars = base.chars.toMutableList()
        chars[1] = chars[1].copy(alternatives = listOf(OcrAlt("吉", 0.5f), OcrAlt("𠮷", 0.4f)))
        val line = CorrectedLine(OcrLine(base.text, base.box, chars))
        val picked = line.withPick(1, "𠮷")
        assertEquals("土𠮷好", picked.text)
        assertEquals(listOf(0, 1, 3), (0..2).map(picked::charStart))
        assertEquals(1, picked.charIndexAt(1))
        assertEquals(1, picked.charIndexAt(2))
        assertEquals(2, picked.charIndexAt(3))
        assertEquals(3, picked.selectableOffset(2))
        // Positions before and after keep their text and index.
        assertEquals(line.chars[0], picked.chars[0])
        assertEquals(line.chars[2], picked.chars[2])
        // Undo restores the original offsets.
        assertEquals(listOf(0, 1, 2), (0..2).map(picked.withPick(1, "吉")::charStart))
    }

    @Test
    fun whitespaceSymbolsHaveNoSelectableOffset() {
        val base = line("你好")
        val line = CorrectedLine(OcrLine(" 你好", base.box, listOf(OcrChar(" ", base.box, 1f, listOf(OcrAlt(" ", 1f)))) + base.chars))
        assertNull(line.selectableOffset(0))
        assertEquals(1, line.selectableOffset(1))
    }

    @Test
    fun theReviewPassageJoinsLinesAndShiftsTheirAnalyses() {
        fun passage(text: String, covered: List<Int>) =
            AnalysedPassage(text, DictMode.MANDARIN, SegmentationMode.HANZI, 7, emptyList(), covered)
        val review = ReviewText(listOf("你好", "ABC", "我们"), listOf(passage("你好", listOf(0, 1)), null, passage("我们", listOf(0, 1))), DictMode.MANDARIN, 7)
        assertEquals("你好\nABC\n我们", review.text)
        assertEquals(listOf(0, 1, 7, 8), review.passage.coveredOffsets)
        assertEquals(listOf(0, 3, 7), (0..2).map(review::lineStart))
        assertEquals(0, review.lineAt(1))
        assertEquals(1, review.lineAt(3))
        assertEquals(2, review.lineAt(8))
        assertEquals(1, review.offsetInLine(8))
        // Stepping crosses the unanalysed line (CAM-070).
        assertEquals(7, review.passage.nextCovered(1))
        assertEquals(1, review.passage.previousCovered(7))
        // A stale line analysis (other text) is ignored rather than misapplied.
        val stale = ReviewText(listOf("你们"), listOf(passage("你好", listOf(0, 1))), DictMode.MANDARIN, 7)
        assertTrue(stale.passage.coveredOffsets.isEmpty())
    }
}
