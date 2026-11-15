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

    // vivo V2528A (Android 16), from `dumpsys media.camera`: focal length / sensor width per camera.
    private val vivoMain = CameraSpec("0", minZoom = 1f, maxZoom = 10f, focal = 5.56f / 8.192f)
    private val vivoFront = CameraSpec("1", front = true, minZoom = 1f, maxZoom = 10f, focal = 3.09f / 5.2224f)
    private val vivoUltraWide = CameraSpec("3", minZoom = 1f, maxZoom = 10f, focal = 1.6f / 3.584f)
    private val vivoTele = CameraSpec("4", minZoom = 1f, maxZoom = 10f, focal = 13.85f / 6.5536f)
    private val vivoLogical = CameraSpec(
        "2", minZoom = 0.6f, maxZoom = 100f, focal = 5.56f / 8.192f,
        physicalFocals = listOf(vivoUltraWide.focal!!, vivoMain.focal!!, vivoTele.focal!!),
        physicalIds = setOf("3", "0", "4"),
    )

    @Test
    fun vivoAsAThirdPartyAppSeesItGetsDigitalStepsAndTheFrontCamera() {
        // `vendor.camera.aux.packagelist` keeps cameras 2–4 to the system camera app: the list holds 0 and 1.
        val plan = planLenses(listOf(vivoMain, vivoFront), defaultRearId = "0")
        assertEquals("0", plan.rearId)
        assertEquals(listOf("1×", "2×", "Front"), labels(plan.lenses))
        assertEquals(listOf("0@1.0", "0@2.0", "1"), plan.lenses.map { it.id })
        assertTrue(plan.lenses.last().front)
        assertEquals("0@1.0", plan.initial?.id)
    }

    @Test
    fun aLogicalCameraReachingMoreLensesIsOpenedInsteadOfTheDefault() {
        // The same phone were every camera listed: the logical camera 2 zooms 0.6×…100× across 3, 0 and 4.
        val plan = planLenses(listOf(vivoMain, vivoFront, vivoLogical, vivoUltraWide, vivoTele), defaultRearId = "0")
        assertEquals("2", plan.rearId)
        assertEquals(listOf("0.6×", "1×", "3×", "Front"), labels(plan.lenses))
        assertEquals(listOf("2@0.6", "2@1.0", "2@3.0", "1"), plan.lenses.map { it.id })
    }

    @Test
    fun separateRearCamerasAreListedByTheirFactor() {
        // No logical camera: the ultra-wide (focal/sensor 0.45 against 0.68) and the 3× telephoto by rebinding.
        val plan = planLenses(listOf(vivoMain, vivoFront, vivoUltraWide, vivoTele), defaultRearId = "0")
        assertEquals("0", plan.rearId)
        assertEquals(listOf("0.7×", "1×", "2×", "3×", "Front"), labels(plan.lenses))
        assertEquals(listOf("3", "0@1.0", "0@2.0", "4", "1"), plan.lenses.map { it.id })
        assertEquals(3f, plan.factors["4"])
    }

    @Test
    fun aLogicalDefaultCameraKeepsItsPhysicalLensesOutOfTheList() {
        // Pixel-style: camera 0 is itself the logical camera; its physical lenses are also listed.
        val pixel = vivoLogical.copy(id = "0", physicalIds = setOf("2", "3", "4"))
        val plan = planLenses(listOf(pixel, vivoFront, vivoUltraWide.copy(id = "2")), defaultRearId = "0")
        assertEquals(listOf("0.6×", "1×", "3×", "Front"), labels(plan.lenses))
    }

    @Test
    fun withoutAFrontCameraOrZoomThereIsNothingToChoose() {
        assertEquals(1, planLenses(listOf(CameraSpec("0")), "0").lenses.size)
        // A front camera alone (a tablet, say) opens as the only entry.
        val front = planLenses(listOf(vivoFront), null)
        assertEquals(null, front.rearId)
        assertEquals(listOf("Front"), labels(front.lenses))
    }

    @Test
    fun theFrontCameraIsSelectedByIdAndNamedWithoutZoom() {
        val plan = planLenses(listOf(vivoMain, vivoFront), "0")
        // The platform reports no zoom while the front camera is open.
        assertEquals(plan.lenses.last(), activeLens(plan.lenses, "1", null))
        assertEquals("Front", lensChipLabel(plan.lenses, "1", null))
        // Back on the rear camera, the live factor picks among the rear lenses only.
        assertEquals(plan.lenses[1], activeLens(plan.lenses, "1", 2.4f))
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
