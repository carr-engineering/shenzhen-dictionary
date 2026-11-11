package com.szdict.app.feature.conversion

import com.szdict.core.api.DictMode
import com.szdict.core.api.Script
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ConversionStateTest {
    private class Fixture(scope: TestScope) {
        val converter = FakeScriptConverter()
        val dictionary = CountingDictionary()
        var script = Script.SIMPLIFIED
        var mode = DictMode.MANDARIN
        var passageChanges = 0
        val state = ConversionState(
            scope = scope.backgroundScope,
            converter = converter,
            dictionary = { dictionary },
            userScript = { script },
            mode = { mode },
            onPassageChanging = { passageChanges++ },
            analysisContext = StandardTestDispatcher(scope.testScheduler),
        )
    }

    @Test
    fun analysesTheOriginalAndDetectsItsScript() = runTest {
        val f = Fixture(this)
        f.state.setText("汉语")
        assertTrue(f.state.busy)
        advanceUntilIdle()
        assertFalse(f.state.busy)
        assertEquals("汉语", f.state.passage?.text)
        assertEquals(DetectedKind.SIMPLIFIED, f.state.selection.detected?.kind)
        assertEquals(ConversionAction.CONVERT_TO_TRADITIONAL, f.state.mainAction)
        // Detection never converts by itself.
        assertTrue(f.converter.conversions.isEmpty())
        assertNull(f.state.converted)
    }

    @Test
    fun conversionIsAViewOfTheOriginal() = runTest {
        val f = Fixture(this)
        f.state.setText("汉语")
        advanceUntilIdle()
        f.state.toggle()
        // GLB-030: the previous analysis stays until the new one is ready.
        assertEquals("汉语", f.state.passage?.text)
        assertTrue(f.state.busy)
        advanceUntilIdle()

        assertEquals("汉语", f.state.original)                 // the original is kept untouched
        assertEquals("漢語", f.state.passage?.text)            // analysis follows the displayed text
        assertEquals("漢語", f.state.displayedText)
        assertEquals(ConversionAction.SHOW_ORIGINAL, f.state.mainAction)
        assertEquals(Triple("汉语", ScriptVariant.SIMPLIFIED, ScriptVariant.TRADITIONAL), f.converter.conversions.single())

        f.state.toggle()                                       // Show original
        advanceUntilIdle()
        assertEquals("汉语", f.state.passage?.text)
        assertNull(f.state.converted)
        assertEquals(ConversionAction.CONVERT_TO_TRADITIONAL, f.state.mainAction)
    }

    @Test
    fun conversionAlwaysStartsFromTheOriginalNeverFromConvertedText() = runTest {
        val f = Fixture(this)
        f.state.setText("软件")
        advanceUntilIdle()
        f.state.convert(ScriptVariant.SIMPLIFIED, ScriptVariant.TRADITIONAL)
        advanceUntilIdle()
        f.state.convert(ScriptVariant.SIMPLIFIED, ScriptVariant.TRADITIONAL_TW_PHRASES)
        advanceUntilIdle()
        assertEquals(listOf("软件", "软件"), f.converter.conversions.map { it.first })
        assertEquals("軟體", f.state.passage?.text)
    }

    @Test
    fun cantoneseModeDefaultsToHongKongTraditional() = runTest {
        val f = Fixture(this)
        f.mode = DictMode.CANTONESE
        f.state.setText("汉语")
        advanceUntilIdle()
        f.state.toggle()
        advanceUntilIdle()
        assertEquals(ScriptVariant.TRADITIONAL_HK, f.converter.conversions.single().third)
    }

    @Test
    fun conversionStaysOnWhileTyping() = runTest {
        val f = Fixture(this)
        f.state.setText("汉")
        advanceUntilIdle()
        f.state.convert(ScriptVariant.SIMPLIFIED, ScriptVariant.TRADITIONAL_TW)
        advanceUntilIdle()

        f.state.setText("汉语")                                // an edit of the same passage
        advanceUntilIdle()
        assertEquals(ConversionAction.SHOW_ORIGINAL, f.state.mainAction)
        assertEquals("漢語", f.state.passage?.text)
        assertEquals(Triple("汉语", ScriptVariant.SIMPLIFIED, ScriptVariant.TRADITIONAL_TW), f.converter.conversions.last())
        assertEquals("汉语", f.state.original)
    }

    @Test
    fun aNewPassageStartsUnconvertedWithFreshDetection() = runTest {
        val f = Fixture(this)
        f.state.setText("汉语")
        advanceUntilIdle()
        f.state.convert(ScriptVariant.SIMPLIFIED, ScriptVariant.TRADITIONAL_TW_PHRASES)
        advanceUntilIdle()
        val conversionsBefore = f.converter.conversions.size

        f.state.setText("漢語", newPassage = true)             // Paste / shared text
        assertFalse(f.state.selection.isConverted)
        assertNull(f.state.selection.confirmed)
        advanceUntilIdle()
        assertEquals("漢語", f.state.passage?.text)
        assertEquals(conversionsBefore, f.converter.conversions.size)
        assertEquals(DetectedKind.TRADITIONAL, f.state.selection.detected?.kind)
        assertEquals(ConversionAction.CONVERT_TO_SIMPLIFIED, f.state.mainAction)

        f.state.toggle()
        advanceUntilIdle()
        f.state.setText("", newPassage = true)                 // Clear
        assertEquals(ConversionSelection.None, f.state.selection)
        assertNull(f.state.passage)
        assertFalse(f.state.enabled)
    }

    @Test
    fun blankTextRemovesTheResultAtOnce() = runTest {
        val f = Fixture(this)
        f.state.setText("汉语")
        advanceUntilIdle()
        assertNotNull(f.state.passage)
        f.state.setText("汉语汉语")
        f.state.setText("  ")
        assertNull(f.state.passage)                            // no waiting for a job
        assertFalse(f.state.busy)
        advanceUntilIdle()
        assertNull(f.state.passage)                            // the superseded analysis never lands
        assertFalse(f.dictionary.analysed.contains("汉语汉语"))
    }

    @Test
    fun analysisIsLatestOnly() = runTest {
        val f = Fixture(this)
        for (i in 1..10) f.state.setText("汉".repeat(i))
        advanceUntilIdle()
        assertEquals(listOf("汉".repeat(10)), f.dictionary.analysed)   // ten edits, one analysis
        assertEquals("汉".repeat(10), f.state.passage?.text)
        assertEquals(10, f.passageChanges)
    }

    @Test
    fun everyDisplayedPassageChangeIsAnnounced() = runTest {
        val f = Fixture(this)
        f.state.setText("汉语")
        advanceUntilIdle()
        val before = f.passageChanges
        f.state.toggle()                                       // convert
        f.state.convert(ScriptVariant.SIMPLIFIED, ScriptVariant.TRADITIONAL_HK)   // change options
        f.state.showOriginal()
        assertEquals(before + 3, f.passageChanges)             // the word sheet closes each time (TXT-045)
        advanceUntilIdle()
    }

    @Test
    fun nothingToConvertStillShowsOriginalAction() = runTest {
        val f = Fixture(this)
        f.state.setText("你好")
        advanceUntilIdle()
        f.state.toggle()
        advanceUntilIdle()
        assertEquals(ConversionNotice.NOTHING_TO_CONVERT, f.state.notice)
        assertEquals(ConversionAction.SHOW_ORIGINAL, f.state.mainAction)
        f.state.toggle()
        advanceUntilIdle()
        assertNull(f.state.notice)
    }

    @Test
    fun aFailedConversionKeepsTheOriginalOnScreen() = runTest {
        val f = Fixture(this)
        f.state.setText("汉语")
        advanceUntilIdle()
        f.converter.fail = true
        f.state.toggle()
        advanceUntilIdle()
        assertEquals(ConversionNotice.FAILED, f.state.notice)
        assertEquals("汉语", f.state.passage?.text)
        assertFalse(f.state.selection.isConverted)
        assertNull(f.state.converted)
    }

    @Test
    fun aFailedAnalysisIsReportedAndRetriedByEditing() = runTest {
        val f = Fixture(this)
        f.dictionary.fail = true
        f.state.setText("汉语")
        advanceUntilIdle()
        assertTrue(f.state.analysisFailed)
        assertNull(f.state.passage)
        assertEquals("汉语", f.state.original)
        f.dictionary.fail = false
        f.state.setText("汉语。")
        advanceUntilIdle()
        assertFalse(f.state.analysisFailed)
        assertEquals("汉语。", f.state.passage?.text)
    }

    @Test
    fun taiwanWithPhrasesIsRefusedFromATraditionalSource() = runTest {
        val f = Fixture(this)
        f.state.setText("漢語")
        advanceUntilIdle()
        f.state.convert(ScriptVariant.TRADITIONAL_HK, ScriptVariant.TRADITIONAL_TW_PHRASES)
        advanceUntilIdle()
        assertFalse(f.state.selection.isConverted)
        assertTrue(f.converter.conversions.isEmpty())
    }

    @Test
    fun longPassagesAreAnalysedInChunksAsOnePassage() = runTest {
        val f = Fixture(this)
        val sentence = "我们研究生命科学。"
        val text = sentence.repeat(120)                        // 1,080 characters
        f.state.setText(text)
        advanceUntilIdle()
        val passage = assertNotNull(f.state.passage)
        assertEquals(text, passage.text)
        assertTrue(f.dictionary.analysed.size >= 3)
        assertTrue(f.dictionary.analysed.all { it.length <= PassageChunker.CHUNK })
        assertEquals(text, f.dictionary.analysed.joinToString(""))
        // Offsets are relative to the whole passage, ascending and in range.
        assertTrue(passage.segments.zipWithNext().all { (a, b) -> a.end <= b.start })
        assertTrue(passage.segments.last().end <= text.length)
        assertEquals(passage.coveredOffsets.sorted(), passage.coveredOffsets)
    }

    @Test
    fun chunksCutAtPunctuationThenWhitespaceThenTheCap() {
        val text = "汉".repeat(390) + "。" + "语".repeat(300) + " " + "字".repeat(500)
        val chunks = PassageChunker.chunks(text)
        assertEquals(0 until 391, chunks[0])                   // after the full stop
        assertEquals(' ', text[chunks[1].last])                // after the space
        assertEquals(text.length, chunks.sumOf { it.count() })
        assertTrue(chunks.all { it.count() in 1..PassageChunker.CHUNK })
        assertEquals(listOf(0 until 5), PassageChunker.chunks("short"))
        // A surrogate pair is never split at the hard cap.
        val astral = "𠀀".repeat(300)
        for (range in PassageChunker.chunks(astral, limit = 401)) assertEquals(0, range.count() % 2)
    }
}
