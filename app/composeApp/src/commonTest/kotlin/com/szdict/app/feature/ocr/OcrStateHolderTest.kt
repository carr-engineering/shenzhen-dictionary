package com.szdict.app.feature.ocr

import com.szdict.app.Destination
import com.szdict.app.feature.conversion.ScriptVariant
import com.szdict.app.feature.conversion.settle
import com.szdict.app.integration.IncomingIntents
import com.szdict.core.api.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class OcrStateHolderTest {
    // ---- image-input routing: five inputs, one review path ----

    @Test
    fun everyInputLeadsToTheSameReviewWithItsSource() = runTest {
        val f = OcrFixture(this)
        f.platform.images["content://a"] = 50
        f.result(50, line("你好"))
        f.result(100, line("我们"))
        f.session.selectDestination(Destination.OCR)

        f.holder.onCameraCaptured(byteArrayOf(1, 2, 3))
        settle()
        assertEquals(CaptureSource.CAMERA, f.holder.capture?.source)
        assertEquals("我们", f.holder.review?.text)
        assertTrue(f.holder.canSave)

        val inputs: List<Pair<ImageOrigin, () -> Unit>> = listOf(
            ImageOrigin.PICK to { f.holder.onPicked("content://a") },
            ImageOrigin.DROP to { f.holder.onDropped("content://a") },
            ImageOrigin.PASTE to { f.platform.clipboard = "content://a"; f.holder.paste() },
            ImageOrigin.SHARE to { IncomingIntents.deliverImage("content://a") },
        )
        for ((origin, input) in inputs) {
            val before = f.holder.capture
            input()
            settle()
            val capture = assertNotNull(f.holder.capture, "$origin")
            assertTrue(capture !== before, "$origin replaces the capture")
            assertEquals(origin, capture.origin)
            assertEquals(CaptureSource.GALLERY, capture.source)
            assertEquals("你好", f.holder.review?.text, "$origin")
            assertFalse(f.holder.canSave, "$origin is never saved")
            assertTrue((before!!.image as FakeImage).released, "the replaced bitmap is released")
            assertNotNull(f.session.ocrReviewBack)
        }
        // The shared image was consumed once (CAM-043).
        assertNull(IncomingIntents.pendingImageUri.value)
        assertEquals(5, f.platform.decoded.size)
    }

    @Test
    fun aGalleryInputShowsTheDecodeSpinnerThenItsOwnErrorText() = runTest {
        val f = OcrFixture(this)
        f.platform.decodeDelayMs = 100
        val messages = mapOf(
            ImageOrigin.PICK to "Could not open that image.",
            ImageOrigin.SHARE to "Could not open the shared image.",
            ImageOrigin.DROP to "Could not open the dropped image.",
            ImageOrigin.PASTE to "Could not open the pasted image.",
        )
        for ((origin, message) in messages) {
            f.holder.submit(ImageInput(origin, ImageRef.Uri("content://broken")))
            assertEquals(origin, f.holder.decoding)
            assertTrue(f.holder.galleryPending)
            assertNull(f.holder.inputError)
            settle()
            assertNull(f.holder.decoding)
            assertTrue(f.holder.galleryPending, "the error state stays until Back or another image")
            assertEquals(message, f.holder.inputError)
            assertFalse(f.holder.inReview)
        }
        // Back returns to the live feed (CAM-100).
        assertTrue(assertNotNull(f.session.ocrReviewBack)())
        assertFalse(f.holder.galleryPending)
        assertNull(f.holder.inputError)
        assertNull(f.session.ocrReviewBack)
    }

    @Test
    fun aCameraDecodeFailureLeavesTheViewfinderUsable() = runTest {
        val f = OcrFixture(this)
        f.platform.cameraWidth = 100
        f.holder.submit(ImageInput(ImageOrigin.CAMERA, ImageRef.Uri("content://broken")))
        assertFalse(f.holder.galleryPending)
        settle()
        assertEquals("Could not process the captured photo.", f.holder.inputError)
        assertFalse(f.holder.galleryPending)
        f.holder.onCameraError()
        assertEquals("Could not take a photo. Try again.", f.holder.inputError)
    }

    @Test
    fun dropAndPasteWithoutAnImageKeepTheCurrentState() = runTest {
        val f = OcrFixture(this)
        f.result(100, line("你好"))
        f.holder.onCameraCaptured(byteArrayOf(1))
        settle()
        val capture = f.holder.capture
        f.holder.onDropped(null)
        assertEquals("That file is not an image.", f.holder.notice)
        assertFalse(f.holder.paste())
        assertEquals("No image on the clipboard.", f.holder.notice)
        assertSame(capture, f.holder.capture)
        settle()
        assertNull(f.holder.notice)
    }

    @Test
    fun theOpenCameraRouteAndBackDiscardTheCapture() = runTest {
        val f = OcrFixture(this)
        f.result(100, line("你好"))
        f.holder.onCameraCaptured(byteArrayOf(1))
        settle()
        assertTrue(f.holder.inReview)
        IncomingIntents.deliverRoute(Route.Ocr)
        settle()
        assertFalse(f.holder.inReview)
        assertNull(f.holder.review)
        assertNull(IncomingIntents.pendingRoute.value)
        assertFalse(f.holder.backToInput())
    }

    // ---- recognition: latest-only, timeout, errors ----

    @Test
    fun onlyTheLatestCaptureIsRecognisedIntoTheReview() = runTest {
        val f = OcrFixture(this)
        f.platform.images["content://a"] = 50
        f.platform.images["content://b"] = 60
        f.result(50, line("你好"))
        f.result(60, line("我们"))
        f.recognizer.delayMs = 1_000
        f.holder.onPicked("content://a")
        runCurrent()
        advanceTimeBy(500)
        assertTrue(f.holder.recognising)
        assertEquals(listOf(50), f.recognizer.started)
        f.holder.onPicked("content://b")
        settle()
        assertFalse(f.holder.recognising)
        assertEquals("我们", f.holder.review?.text)
        // The superseded recognition was cancelled and never applied.
        assertEquals(listOf(60), f.recognizer.finished)
        assertEquals(listOf("我们"), f.dictionary.analysed)
    }

    @Test
    fun aRecognitionFinishingAfterReturningToTheCameraIsDropped() = runTest {
        val f = OcrFixture(this)
        f.result(100, line("你好"))
        f.recognizer.delayMs = 1_000
        f.holder.onCameraCaptured(byteArrayOf(1))
        runCurrent()
        advanceTimeBy(100)
        f.holder.backToInput()
        settle()
        assertFalse(f.holder.inReview)
        assertTrue(f.holder.lines.isEmpty())
        assertFalse(f.holder.recognising)
    }

    @Test
    fun aTimeoutAndAFailureKeepTheImageAndReportInline() = runTest {
        val f = OcrFixture(this, timeoutMs = 1_000)
        f.recognizer.delayMs = 3_000
        f.holder.onCameraCaptured(byteArrayOf(1))
        settle()
        assertTrue(f.holder.inReview)
        assertFalse(f.holder.recognising)
        assertEquals("Text recognition timed out. Try a smaller or clearer image.", f.holder.recognitionError?.first)

        f.recognizer.delayMs = 0
        f.recognizer.fail = true
        f.holder.onCameraCaptured(byteArrayOf(2))
        settle()
        assertEquals("Unable to recognize text" to "model broke", f.holder.recognitionError)
        // The next capture works again: the runner survived both.
        f.recognizer.fail = false
        f.result(100, line("你好"))
        f.holder.onCameraCaptured(byteArrayOf(3))
        settle()
        assertNull(f.holder.recognitionError)
        assertEquals("你好", f.holder.review?.text)
    }

    @Test
    fun linesWithoutHanziAreKeptButReported() = runTest {
        val f = OcrFixture(this)
        f.result(100, line("ABC"), line(""))
        f.holder.onCameraCaptured(byteArrayOf(1))
        settle()
        assertTrue(f.holder.noChinese)
        assertEquals(1, f.holder.lines.size)
        assertTrue(f.holder.canSave)   // CAM-120
    }

    @Test
    fun aLineThatCannotBeAnalysedStaysAsPlainText() = runTest {
        val f = OcrFixture(this)
        f.dictionary.fail = true
        f.result(100, line("你好"))
        f.holder.onCameraCaptured(byteArrayOf(1))
        settle()
        val review = assertNotNull(f.holder.review)
        assertNull(review.linePassages[0])
        assertTrue(review.passage.coveredOffsets.isEmpty())
    }

    // ---- selection, stepping and the correction layer ----

    @Test
    fun tappingAGlyphOpensTheSheetAndSteppingCrossesLines() = runTest {
        val f = OcrFixture(this)
        f.result(100, line("你好"), line("ABC", y = 20f), line("我们", y = 40f))
        f.holder.onCameraCaptured(byteArrayOf(1))
        settle()
        f.holder.tapChar(CharRef(0, 1))
        val sheet = assertNotNull(f.session.wordSheet)
        assertEquals(1, sheet.offset)
        assertEquals(CharRef(0, 1), f.holder.selected)
        assertEquals(1, f.holder.selectedOffsetIn(0))
        assertNull(f.holder.selectedOffsetIn(2))
        // Next covered character is on the third line; the views are asked to bring it into view.
        val next = assertNotNull(sheet.passage.nextCovered(sheet.offset))
        f.session.moveWordSheet(next)
        assertEquals(CharRef(2, 0), f.holder.selected)
        val focus = assertNotNull(f.holder.focusRequest)
        assertEquals(2, focus.line)
        assertEquals(CharRef(2, 0), focus.ref)
        assertTrue(f.holder.takeFocus(focus))
        assertFalse(f.holder.takeFocus(focus), "a returning screen does not replay the pan")
        // The same passage serves the Text view: switching keeps the selection.
        f.holder.changeViewMode(OcrViewMode.TEXT)
        assertEquals(0, f.holder.selectedOffsetIn(2))
        f.holder.tapText(0, 0)
        assertEquals(CharRef(0, 0), f.holder.selected)
    }

    @Test
    fun alternativesAreRankedThroughTheDictionaryAndKeepTheRecognisedReading() = runTest {
        val f = OcrFixture(this)
        // 您 is not in the dataset: filtered out. X is what the recogniser read: always offered.
        f.result(100, line("你X", alternatives = mapOf(0 to listOf("好", "您"), 1 to listOf("我"))))
        f.holder.onCameraCaptured(byteArrayOf(1))
        settle()
        assertTrue(f.holder.alternatives(CharRef(0, 0)).isEmpty(), "nothing until ranked")
        f.holder.loadAlternatives(CharRef(0, 0))
        f.holder.loadAlternatives(CharRef(0, 1))
        settle()
        assertEquals(
            listOf(AltChoice("你", current = true, recognised = true), AltChoice("好", current = false, recognised = false)),
            f.holder.alternatives(CharRef(0, 0)),
        )
        assertEquals(listOf("X", "我"), f.holder.alternatives(CharRef(0, 1)).map { it.text })
    }

    @Test
    fun aCharacterWithNothingElseToOfferHasNoAlternatives() = runTest {
        val f = OcrFixture(this)
        f.result(100, line("你"))
        f.holder.onCameraCaptured(byteArrayOf(1))
        settle()
        f.holder.loadAlternatives(CharRef(0, 0))
        settle()
        assertTrue(f.holder.alternatives(CharRef(0, 0)).isEmpty())
    }

    @Test
    fun pickingAnAlternativeReanalysesThatLineOnlyAndKeepsTheSelection() = runTest {
        val f = OcrFixture(this)
        f.result(100, line("你好"), line("我水", y = 20f, alternatives = mapOf(1 to listOf("们", "好"))))
        f.holder.onCameraCaptured(byteArrayOf(1))
        settle()
        assertEquals(listOf("你好", "我水"), f.dictionary.analysed)
        f.holder.tapChar(CharRef(1, 1))
        f.holder.loadAlternatives(CharRef(1, 1))
        settle()
        val before = assertNotNull(f.session.wordSheet)
        assertEquals(4, before.offset)

        f.holder.pickAlternative(CharRef(1, 1), "们")
        settle()
        // Only the corrected line went back to the dictionary.
        assertEquals(listOf("你好", "我水", "我们"), f.dictionary.analysed)
        assertEquals("你好\n我们", f.holder.review?.text)
        assertEquals("我们", f.holder.lines[1].text)
        assertEquals("我水", f.holder.lines[1].original.text, "the recognition result is untouched")
        // The sheet follows onto the new passage, same position.
        val after = assertNotNull(f.session.wordSheet)
        assertSame(f.holder.review?.passage, after.passage)
        assertEquals(4, after.offset)
        assertEquals(CharRef(1, 1), f.holder.selected)
        assertEquals(
            listOf("水" to false, "们" to true, "好" to false),
            f.holder.alternatives(CharRef(1, 1)).map { it.text to it.current },
        )
        assertTrue(f.holder.alternatives(CharRef(1, 1)).first().recognised)

        // Picking the recognised reading undoes the correction.
        f.holder.pickAlternative(CharRef(1, 1), "水")
        settle()
        assertEquals("你好\n我水", f.holder.review?.text)
        assertFalse(f.holder.lines[1].corrected)
        assertEquals(CharRef(1, 1), f.holder.selected)
        assertEquals(4, f.dictionary.analysed.size)
    }

    @Test
    fun quickCorrectionsOnTwoLinesBothLand() = runTest {
        val f = OcrFixture(this)
        f.result(100, line("你水", alternatives = mapOf(1 to listOf("好"))), line("我水", y = 20f, alternatives = mapOf(1 to listOf("们"))))
        f.holder.onCameraCaptured(byteArrayOf(1))
        settle()
        f.holder.pickAlternative(CharRef(0, 1), "好")
        f.holder.pickAlternative(CharRef(1, 1), "们")
        settle()
        assertEquals("你好\n我们", f.holder.review?.text)
        assertEquals(listOf("你好", "我们"), f.holder.review?.linePassages?.map { it?.text })
    }

    // ---- script conversion (CAM-135) ----

    @Test
    fun conversionConvertsEachLineSeparatelyForTheTextViewOnly() = runTest {
        val f = OcrFixture(this)
        f.result(100, line("汉语"), line("你好", y = 20f))
        f.holder.onCameraCaptured(byteArrayOf(1))
        settle()
        f.holder.tapChar(CharRef(0, 0))
        f.holder.convert(ScriptVariant.SIMPLIFIED, ScriptVariant.TRADITIONAL)
        assertNull(f.session.wordSheet, "converting clears the selection")
        settle()
        assertEquals("漢語\n你好", f.holder.textReview?.text)
        assertEquals("汉语\n你好", f.holder.review?.text, "the image view keeps the original lines")
        assertEquals(listOf("汉语", "你好"), f.converter.conversions.map { it.first })
        assertEquals("漢語", f.holder.displayedText(0))
        // A selection in the converted text is not a selection of the original lines.
        f.holder.changeViewMode(OcrViewMode.TEXT)
        f.holder.tapText(0, 1)
        assertEquals(1, f.holder.selectedOffsetIn(0))
        assertNull(f.holder.selected)
        f.holder.changeViewMode(OcrViewMode.IMAGE)
        assertNull(f.session.wordSheet)
        // Show original.
        f.holder.toggleConversion()
        settle()
        assertFalse(f.holder.showingConverted)
        assertEquals("汉语\n你好", f.holder.textReview?.text)
        // A new capture resets the conversion.
        f.holder.convert(ScriptVariant.SIMPLIFIED, ScriptVariant.TRADITIONAL)
        settle()
        f.holder.onCameraCaptured(byteArrayOf(2))
        settle()
        assertFalse(f.holder.conversion.isConverted)
    }

    // ---- save photo ----

    @Test
    fun aCaptureIsSavedOnceAndOnlyFromTheCamera() = runTest {
        val f = OcrFixture(this)
        f.platform.images["content://a"] = 50
        f.holder.onCameraCaptured(byteArrayOf(9, 9))
        settle()
        f.holder.savePhoto()
        assertEquals(SaveState.SAVING, f.holder.saveState)
        runCurrent()
        assertEquals(SaveState.SAVED, f.holder.saveState)
        assertEquals("Saved to Gallery", f.holder.notice)
        f.holder.savePhoto()
        settle()
        assertEquals(1, f.platform.saved.size)
        assertEquals(listOf<Byte>(9, 9), f.platform.saved[0].toList(), "the original encoded capture is written")

        // A failed save can be retried.
        f.platform.saveSucceeds = false
        f.holder.onCameraCaptured(byteArrayOf(7))
        settle()
        assertEquals(SaveState.IDLE, f.holder.saveState)
        f.holder.savePhoto()
        runCurrent()
        assertEquals(SaveState.FAILED, f.holder.saveState)
        assertEquals("Could not save the photo.", f.holder.notice)
        f.platform.saveSucceeds = true
        f.holder.savePhoto()
        runCurrent()
        assertEquals(SaveState.SAVED, f.holder.saveState)

        f.holder.onPicked("content://a")
        settle()
        f.holder.savePhoto()
        settle()
        assertEquals(3, f.platform.saved.size)
    }

    @Test
    fun autoSaveWritesEveryCameraCaptureAndNeverAGalleryImage() = runTest {
        val f = OcrFixture(this)
        settle()
        f.session.updateSettings { it.copy(cameraAutoSave = true) }
        f.platform.images["content://a"] = 50
        f.holder.onCameraCaptured(byteArrayOf(1))
        settle()
        assertEquals(SaveState.SAVED, f.holder.saveState)
        f.holder.savePhoto()
        settle()
        assertEquals(1, f.platform.saved.size, "the manual action shows the saved state instead of writing again")
        f.holder.onPicked("content://a")
        settle()
        assertEquals(1, f.platform.saved.size)
    }

    @Test
    fun closingReleasesTheRecogniser() = runTest {
        val f = OcrFixture(this)
        f.holder.onCameraCaptured(byteArrayOf(1))
        settle()
        f.holder.close()
        assertTrue(f.recognizer.closed)
        assertNull(f.session.ocrReviewBack)
    }
}
