package com.szdict.app.feature.handwriting

import androidx.compose.runtime.snapshots.Snapshot
import com.szdict.app.HandwritingSink
import com.szdict.app.WordSheetRequest
import com.szdict.app.feature.conversion.settle
import com.szdict.app.feature.ocr.OcrFixture
import com.szdict.core.api.DictMode
import com.szdict.core.api.Script
import com.szdict.core.api.SegmentationMode
import com.szdict.recognition.HandwritingRecognizer
import com.szdict.recognition.InkCandidate
import com.szdict.recognition.InkModel
import com.szdict.recognition.InkStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class HandwritingStateTest {
    /** Candidates depend on the number of strokes, so a stale result is recognisable. */
    private class FakeInk : HandwritingRecognizer {
        val byStrokes = mapOf(
            1 to listOf("一", "你", "X"),
            2 to listOf("好", "你好", "您"),
            3 to listOf("我", "们", "你", "好", "生", "学", "水", "吃", "饭", "行", "发", "命", "科"),
        )
        val calls = ArrayList<Triple<Int, InkModel, Int>>()
        val finished = ArrayList<Int>()
        var delayMs = 0L
        var fail = false
        var closed = false

        override suspend fun recognize(strokes: List<InkStroke>, writingArea: Pair<Float, Float>, model: InkModel, limit: Int): List<InkCandidate> {
            calls += Triple(strokes.size, model, limit)
            if (delayMs > 0) delay(delayMs)
            if (fail) throw IllegalStateException("model broke")
            finished += strokes.size
            return byStrokes[strokes.size].orEmpty().mapIndexed { i, text -> InkCandidate(text, -i.toFloat()) }
        }

        override fun close() { closed = true }
    }

    private class Sink : HandwritingSink {
        val inserted = ArrayList<String>()
        var deletes = 0
        override fun insert(text: String) { inserted += text }
        override fun deleteBackward() { deletes++ }
    }

    private class Fixture(scope: TestScope, observe: Boolean = false) {
        val base = OcrFixture(scope)
        val session = base.session
        val ink = FakeInk()
        val sink = Sink().also { session.handwritingSink = it }
        val state = HandwritingState(session, { ink }, observeSession = observe)

        fun stroke(vararg points: Pair<Float, Float>) {
            state.beginStroke(points[0].first, points[0].second, 0)
            points.drop(1).forEachIndexed { i, p -> state.extendStroke(p.first, p.second, 10L * (i + 1)) }
            state.endStroke()
        }
    }

    @Test
    fun switchToKeyboardSetsTheMethodClosesTheSheetAndRequestsTheKeyboard() = runTest {
        val f = Fixture(this)
        f.session.updateSettings { it.copy(inputMethod = com.szdict.core.api.InputMethod.HANDWRITING) }
        f.session.handwritingVisible = true
        val before = f.session.keyboardRequest
        f.session.switchHandwritingToKeyboard()
        assertEquals(com.szdict.core.api.InputMethod.KEYBOARD, f.session.settings.value.inputMethod)
        assertFalse(f.session.handwritingVisible)
        assertEquals(before + 1, f.session.keyboardRequest)
        assertTrue(f.session.handwritingSink === f.sink, "the field keeps receiving input")
    }

    @Test
    fun aStrokeIsRecordedWithItsSamplesAndRecognisedOnPointerUp() = runTest {
        val f = Fixture(this)
        f.state.beginStroke(1f, 2f, 100)
        f.state.extendStroke(3f, 4f, 110)
        f.state.extendStroke(3f, 4f, 120)   // no movement: not a new sample
        assertEquals(2, f.state.live.size)
        assertTrue(f.state.strokes.isEmpty())
        assertFalse(f.state.isEmpty)
        settle()
        assertTrue(f.ink.calls.isEmpty(), "nothing is recognised before the stroke ends")
        f.state.endStroke()
        assertTrue(f.state.live.isEmpty())
        assertEquals(1, f.state.strokes.size)
        assertEquals(listOf(100L, 110L), f.state.strokes[0].points.map { it.t })
        assertTrue(f.state.busy)
        settle()
        assertFalse(f.state.busy)
        // Non-dataset candidates (一, X) are dropped (HW-032); more are requested than shown.
        assertEquals(listOf("你"), f.state.candidates)
        assertEquals(Triple(1, InkModel.SIMPLIFIED, HandwritingState.REQUESTED), f.ink.calls.single())
    }

    @Test
    fun everyStrokeReRecognisesTheWholeInkAndOnlyTheLatestSetIsShown() = runTest {
        val f = Fixture(this)
        f.ink.delayMs = 1_000
        f.stroke(0f to 0f, 5f to 5f)
        runCurrent()
        advanceTimeBy(300)
        f.stroke(5f to 0f, 0f to 5f)
        runCurrent()
        advanceTimeBy(300)
        f.stroke(0f to 3f, 5f to 3f)
        settle()
        assertEquals(listOf(1, 2, 3), f.ink.calls.map { it.first })
        assertEquals(listOf(3), f.ink.finished, "older stroke sets were superseded")
        assertEquals(HandwritingState.SHOWN, f.state.candidates.size)
        assertEquals("我", f.state.candidates.first())
    }

    @Test
    fun multiCharacterCandidatesAreKeptWhenTheRecogniserReturnsThem() = runTest {
        val f = Fixture(this)
        f.stroke(0f to 0f, 1f to 1f)
        f.stroke(2f to 0f, 3f to 1f)
        settle()
        assertEquals(listOf("好", "你好"), f.state.candidates)
    }

    @Test
    fun undoRemovesTheLastStrokeAndClearRemovesEverything() = runTest {
        val f = Fixture(this)
        f.stroke(0f to 0f, 1f to 1f)
        f.stroke(2f to 0f, 3f to 1f)
        settle()
        f.state.undo()
        assertEquals(1, f.state.strokes.size)
        settle()
        assertEquals(listOf("你"), f.state.candidates)
        f.state.undo()
        assertTrue(f.state.isEmpty)
        assertTrue(f.state.candidates.isEmpty())
        f.state.undo()   // nothing left: no effect
        f.stroke(0f to 0f, 1f to 1f)
        f.state.beginStroke(4f, 4f, 0)
        f.state.clear()
        assertTrue(f.state.isEmpty)
        assertTrue(f.state.candidates.isEmpty())
        settle()
        assertTrue(f.state.candidates.isEmpty(), "a recognition in flight for cleared ink is never shown")
        assertFalse(f.state.busy)
    }

    @Test
    fun tappingACandidateInsertsItAndClearsThePad() = runTest {
        val f = Fixture(this)
        f.stroke(0f to 0f, 1f to 1f)
        f.stroke(2f to 0f, 3f to 1f)
        settle()
        f.state.commit("你好")
        assertEquals(listOf("你好"), f.sink.inserted)
        assertTrue(f.state.isEmpty)
        assertTrue(f.state.candidates.isEmpty())
    }

    @Test
    fun inkIsNeverCommittedByItself() = runTest {
        val f = Fixture(this)
        f.stroke(0f to 0f, 1f to 1f)
        settle()
        // A long pause, more writing, undo, clear, delete: nothing reaches the field.
        advanceTimeBy(60_000)
        f.stroke(2f to 0f, 3f to 1f)
        settle()
        f.state.undo()
        settle()
        f.state.delete()
        f.state.clear()
        settle()
        assertTrue(f.sink.inserted.isEmpty())
        assertEquals(1, f.sink.deletes)
    }

    @Test
    fun deleteEditsTheFieldAndLeavesTheInk() = runTest {
        val f = Fixture(this)
        f.stroke(0f to 0f, 1f to 1f)
        settle()
        f.state.delete()
        assertEquals(1, f.sink.deletes)
        assertEquals(1, f.state.strokes.size)
        assertEquals(listOf("你"), f.state.candidates)
    }

    @Test
    fun aCancelledGestureLeavesNoStroke() = runTest {
        val f = Fixture(this)
        f.state.beginStroke(0f, 0f, 0)
        f.state.extendStroke(1f, 1f, 5)
        f.state.cancelStroke()
        f.state.endStroke()
        assertTrue(f.state.isEmpty)
        settle()
        assertTrue(f.ink.calls.isEmpty())
    }

    @Test
    fun aRecogniserFailureIsReportedAndRecovers() = runTest {
        val f = Fixture(this)
        f.ink.fail = true
        f.stroke(0f to 0f, 1f to 1f)
        settle()
        assertTrue(f.state.failed)
        assertTrue(f.state.candidates.isEmpty())
        f.ink.fail = false
        f.stroke(2f to 0f, 3f to 1f)
        settle()
        assertFalse(f.state.failed)
        assertEquals(listOf("好", "你好"), f.state.candidates)
    }

    @Test
    fun theModelFollowsModeAndScript() {
        assertEquals(InkModel.SIMPLIFIED, inkModel(DictMode.MANDARIN, Script.SIMPLIFIED))
        assertEquals(InkModel.TRADITIONAL, inkModel(DictMode.MANDARIN, Script.TRADITIONAL))
        assertEquals(InkModel.HONG_KONG, inkModel(DictMode.CANTONESE, Script.TRADITIONAL))
        assertEquals(InkModel.SIMPLIFIED, inkModel(DictMode.CANTONESE, Script.SIMPLIFIED))
    }

    @Test
    fun closingTheSheetDiscardsTheInkButTheWordSheetOnlyHidesIt() = runTest {
        val f = Fixture(this, observe = true)
        fun flush() {
            Snapshot.sendApplyNotifications()
            settle()
        }
        f.session.handwritingVisible = true
        flush()
        f.stroke(0f to 0f, 1f to 1f)
        flush()
        // The word sheet hides the handwriting sheet programmatically: the ink is kept (HW-020).
        val passage = f.base.dictionary.analyse("你好", SegmentationMode.HANZI)
        f.session.openWordSheet(WordSheetRequest(this, passage, 0, DictMode.MANDARIN))
        flush()
        assertFalse(f.session.handwritingVisible)
        assertEquals(1, f.state.strokes.size)
        f.session.closeWordSheet()
        flush()
        assertTrue(f.session.handwritingVisible)
        assertEquals(1, f.state.strokes.size)
        // Closing the sheet discards it (HW-024).
        f.session.handwritingVisible = false
        flush()
        assertTrue(f.state.isEmpty)
        assertTrue(f.state.candidates.isEmpty())
    }

    @Test
    fun aScriptChangeClearsTheInk() = runTest {
        val f = Fixture(this, observe = true)
        settle()
        f.session.handwritingVisible = true
        Snapshot.sendApplyNotifications()
        f.stroke(0f to 0f, 1f to 1f)
        settle()
        f.session.updateSettings { it.copy(scriptMandarin = Script.TRADITIONAL) }
        settle()
        assertTrue(f.state.isEmpty)
        f.stroke(0f to 0f, 1f to 1f)
        settle()
        assertEquals(InkModel.TRADITIONAL, f.ink.calls.last().second)
        f.state.close()
        assertTrue(f.ink.closed)
    }
}
