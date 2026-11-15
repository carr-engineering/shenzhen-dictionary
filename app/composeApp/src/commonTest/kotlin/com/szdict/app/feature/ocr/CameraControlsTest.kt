package com.szdict.app.feature.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraControlsTest {
    private fun labels(lenses: List<CameraLens>) = lenses.map { it.label }

    @Test
    fun zoomLabelsHaveOneDecimalAndNoTrailingZero() {
        assertEquals("1×", formatZoom(1f))
        assertEquals("0.5×", formatZoom(0.5f))
        assertEquals("0.6×", formatZoom(0.55f))
        assertEquals("1.4×", formatZoom(1.44f))
        assertEquals("2×", formatZoom(1.999f))
        assertEquals("5×", formatZoom(5f))
        assertEquals("10×", formatZoom(9.96f))
        assertEquals("15×", formatZoom(15.2f))
    }

    @Test
    fun lensFactorsSnapToTheNumbersCameraAppsShow() {
        assertEquals(1f, snapLensFactor(1.07f))
        assertEquals(3f, snapLensFactor(2.86f))
        assertEquals(5f, snapLensFactor(4.8f))
        assertEquals(2.5f, snapLensFactor(2.5f))
        assertEquals(0.6f, snapLensFactor(0.57f))
    }

    @Test
    fun logicalCameraWithPhysicalLensesOffersEachLens() {
        // Pixel-style: ultra-wide (clamped to the minimum zoom), wide, 5× telephoto.
        val steps = zoomSteps(minZoom = 0.6f, maxZoom = 20f, lensRatios = listOf(0.52f, 1f, 4.8f))
        assertEquals(listOf(0.6f, 1f, 5f), steps)
        assertEquals(listOf("0.6×", "1×", "5×"), labels(lensOptions(steps, emptyList())))
    }

    @Test
    fun withoutATelephotoADigitalTwoTimesIsOffered() {
        assertEquals(listOf(0.5f, 1f, 2f), zoomSteps(0.5f, 8f, listOf(0.5f, 1f)))
    }

    @Test
    fun withoutLensInformationTheFallbackIsMinimumOneAndTwo() {
        assertEquals(listOf(0.6f, 1f, 2f), zoomSteps(0.6f, 8f, emptyList()))
        assertEquals(listOf(1f, 2f), zoomSteps(1f, 4f, emptyList()))
        // A camera that cannot zoom has a single step: no chip.
        assertEquals(listOf(1f), zoomSteps(1f, 1f, emptyList()))
    }

    @Test
    fun lensesBeyondTheMaximumZoomAreLeftOut() {
        assertEquals(listOf(1f, 2f), zoomSteps(1f, 4f, listOf(1f, 10f)))
    }

    @Test
    fun separateCamerasJoinTheMainCamerasSteps() {
        // Main camera zooms 1×…8× only; a separate ultra-wide camera is listed as its own entry.
        val lenses = lensOptions(zoomSteps(1f, 8f, emptyList()), listOf("2" to 0.55f, "3" to 2.05f), mainId = "0")
        assertEquals(listOf("0.6×", "1×", "2×"), labels(lenses))
        assertEquals(listOf("2", "0@1.0", "0@2.0"), lenses.map { it.id })
    }

    @Test
    fun virtualDeviceStepsAreRelativeToTheWideCamera() {
        // Triple camera with a 3× telephoto: the wide takes over at 2, the telephoto at 6.
        val (base, steps) = virtualDeviceSteps(hasUltraWide = true, switchOvers = listOf(2f, 6f), maxZoom = 123f)
        assertEquals(2f, base)
        assertEquals(listOf(0.5f, 1f, 3f), steps)
        // Dual-wide: ultra-wide + wide, plus a digital 2×.
        assertEquals(2f to listOf(0.5f, 1f, 2f), virtualDeviceSteps(true, listOf(2f), 95f))
        // Dual: wide + 2× telephoto; the device's own 1 is the wide camera.
        assertEquals(1f to listOf(1f, 2f), virtualDeviceSteps(false, listOf(2f), 16f))
    }

    @Test
    fun theChipShowsTheLiveFactorAndTheMenuTheLensBelowIt() {
        val lenses = lensOptions(listOf(0.5f, 1f, 3f), emptyList())
        assertEquals("1.4×", lensChipLabel(lenses, lenses[1].id, 1.42f))
        assertEquals(lenses[1], activeLens(lenses, lenses[0].id, 1.42f))
        assertEquals(lenses[2], activeLens(lenses, null, 3f))
        assertEquals(lenses[0], activeLens(lenses, null, 0.5f))
        // Below every lens (a rounding below the ultra-wide): still the widest.
        assertEquals(lenses[0], activeLens(lenses, null, 0.4f))
    }

    @Test
    fun namedCamerasShowTheirName() {
        val cameras = listOf(CameraLens("a", null, "Back camera"), CameraLens("b", null, "Camera 2"))
        assertEquals("Camera 2", lensChipLabel(cameras, "b", null))
        assertEquals(cameras[0], activeLens(cameras, "missing", null))
        assertEquals("Back camera", cameras[0].label)
    }

    @Test
    fun flashMenuListsOnlySupportedModesInOrder() {
        assertEquals(emptyList(), flashMenu(emptySet()))
        assertEquals(emptyList(), flashMenu(setOf(FlashMode.OFF)))
        assertEquals(listOf(FlashMode.OFF, FlashMode.TORCH), flashMenu(setOf(FlashMode.TORCH)))
        assertEquals(FlashMode.entries, flashMenu(setOf(FlashMode.TORCH, FlashMode.ON, FlashMode.AUTO)))
    }

    @Test
    fun flashHandleStateFollowsTheSupportedModes() {
        val handle = CameraHandle()
        assertFalse(handle.hasFlash)
        handle.flashModes = setOf(FlashMode.TORCH)
        assertTrue(handle.hasFlash)
        handle.reset()
        assertFalse(handle.hasFlash)
    }

    @Test
    fun anUnsupportedChoiceAppliesAsOffAndComesBackWithTheLens() {
        val full = setOf(FlashMode.AUTO, FlashMode.ON, FlashMode.TORCH)
        assertEquals(FlashMode.ON, effectiveFlash(FlashMode.ON, full))
        // An ultra-wide without flash: Off while it is active, the choice itself is kept.
        assertEquals(FlashMode.OFF, effectiveFlash(FlashMode.ON, emptySet()))
        assertEquals(FlashMode.OFF, effectiveFlash(FlashMode.AUTO, setOf(FlashMode.TORCH)))
        assertEquals(FlashMode.TORCH, effectiveFlash(FlashMode.TORCH, setOf(FlashMode.TORCH)))
    }

    @Test
    fun flashlightIsTheTorchWithTheCaptureFlashOff() {
        assertEquals(FlashMode.OFF, captureFlash(FlashMode.TORCH))
        assertEquals(FlashMode.AUTO, captureFlash(FlashMode.AUTO))
        assertEquals(FlashMode.ON, captureFlash(FlashMode.ON))
    }

    @Test
    fun selectingALensRecordsItAndAsksThePlatform() {
        val handle = CameraHandle()
        val switched = mutableListOf<String>()
        handle.lensSwitch = { switched += it.id }
        val lens = CameraLens("0@2.0", 2f)
        handle.selectLens(lens)
        assertEquals("0@2.0", handle.lens)
        assertEquals(listOf("0@2.0"), switched)
    }
}
