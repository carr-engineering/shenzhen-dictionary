package com.szdict.app.feature.ocr

import com.szdict.app.ui.kit.WithTooltip
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.szdict.app.i18n.appStrings
import com.szdict.core.api.UiLang
import com.szdict.app.ui.theme.LocalDictionaryPalette
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

// ---------------------------------------------------------------- lenses (CAM-028)

/**
 * One entry of the lens menu: a camera, never a zoom level. [factor] is the lens's zoom relative to
 * the main wide camera (1 = `1×`, the ultra-wide about 0.6); null where the platform knows none (Web,
 * the front camera). [name], where set, is the label instead of the factor: a Web camera's name, or
 * with [facing] a facing name (`Back`, `Front`) the UI translates. [front] marks the front camera.
 */
data class CameraLens(
    val id: String,
    val factor: Float?,
    val name: String? = null,
    val front: Boolean = false,
    val facing: Boolean = false,
) {
    val label: String get() = name ?: factor?.let(::formatZoom).orEmpty()
}

/** The menu entry of the front camera (CAM-022): a named entry after the rear lenses. */
fun frontLens(id: String): CameraLens = CameraLens(id, factor = null, name = FrontCameraName, front = true, facing = true)

/** English facing names of the menu entries; the UI shows them through the string table. */
const val FrontCameraName = "Front"
const val BackCameraName = "Back"

/**
 * With a single rear lens beside the front camera the entries are told apart by facing, `Back` and
 * `Front`, not by a factor (CAM-028); with several rear lenses they keep their factors.
 */
fun withFacingNames(lenses: List<CameraLens>): List<CameraLens> {
    val rears = lenses.filter { !it.front }
    if (rears.size != 1 || lenses.none { it.front }) return lenses
    return lenses.map { if (it.front || it.name != null) it else it.copy(name = BackCameraName, facing = true) }
}

/**
 * What the lens menu needs to know of one camera the app can open (CAM-028). [focal] is the focal
 * length over the sensor width (proportional to the zoom the camera gives); [physicalFocals] the same
 * for each physical lens of a logical multi-camera ([physicalIds] their ids), empty for a single camera.
 * [intrinsicZoom] is the platform's own zoom relative to the default rear camera, used where [focal]
 * is unknown.
 */
data class CameraSpec(
    val id: String,
    val front: Boolean = false,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val focal: Float? = null,
    val physicalFocals: List<Float> = emptyList(),
    val physicalIds: Set<String> = emptySet(),
    val intrinsicZoom: Float? = null,
) {
    /** Lenses this camera reaches by zooming alone. */
    val lensCount: Int get() = if (physicalFocals.size >= 2) physicalFocals.size else 1
}

/**
 * The lens menu of a device: [rearId] is the camera opened by default (null without a rear camera),
 * [lenses] the menu, [factors] each camera's zoom `1` relative to the main camera's `1×`.
 */
data class LensPlan(val rearId: String?, val lenses: List<CameraLens>, val factors: Map<String, Float>) {
    /** The entry selected when the camera opens: `1×` of the rear camera, else the first. */
    val initial: CameraLens? get() = lenses.firstOrNull { !it.front && it.factor == 1f } ?: lenses.firstOrNull()
}

/**
 * Builds the lens menu from every camera the app can open (CAM-022, CAM-028): cameras only, no
 * digital zoom levels. The rear camera is the platform default ([defaultRearId]) unless a logical
 * multi-camera reaches more lenses by zoom, which is then opened instead; its physical lenses come
 * first (as zoom ratios of it), separate rear cameras whose factor no lens covers are added (factor
 * from focal length over sensor width relative to the main camera), and the front camera closes the
 * menu. A single rear lens beside the front camera is named `Back` ([withFacingNames]).
 */
fun planLenses(cameras: List<CameraSpec>, defaultRearId: String?): LensPlan {
    val rears = cameras.filter { !it.front }
    val defaultRear = rears.firstOrNull { it.id == defaultRearId } ?: rears.firstOrNull()
    val best = rears.maxByOrNull { it.lensCount }
    val main = if (best != null && defaultRear != null && best.lensCount > defaultRear.lensCount) best else defaultRear
    val lenses = mutableListOf<CameraLens>()
    val factors = HashMap<String, Float>()
    if (main != null) {
        factors[main.id] = 1f
        val steps = zoomSteps(main.minZoom, main.maxZoom, physicalRatios(main))
        val others = rears.filter { it.id != main.id && it.id !in main.physicalIds }.mapNotNull { other ->
            val raw = when {
                other.focal != null && main.focal != null && main.focal > 0f -> other.focal / main.focal
                other.intrinsicZoom != null && main.intrinsicZoom != null && main.intrinsicZoom > 0f -> other.intrinsicZoom / main.intrinsicZoom
                else -> return@mapNotNull null
            }
            other.id to snapLensFactor(raw).also { factors[other.id] = it }
        }
        lenses += lensOptions(steps, others, mainId = main.id)
    }
    cameras.firstOrNull { it.front }?.let { lenses += frontLens(it.id) }
    return LensPlan(main?.id, withFacingNames(lenses), factors)
}

/** A logical camera's physical lenses relative to its `1×`: the physical lens closest to its own field of view. */
private fun physicalRatios(camera: CameraSpec): List<Float> {
    val reference = camera.focal ?: return emptyList()
    if (camera.physicalFocals.size < 2 || reference <= 0f) return emptyList()
    val anchor = camera.physicalFocals.minBy { abs(ln(it / reference)) }
    return camera.physicalFocals.map { it / anchor }
}

/** `0.5×`, `1×`, `1.4×`, `10×`: one decimal below 10, none from 10 up, no trailing `.0`. */
fun formatZoom(factor: Float): String {
    if (factor >= 9.95f) return "${factor.roundToInt()}×"
    val tenths = (factor * 10).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}×" else "${tenths / 10}.${tenths % 10}×"
}

/**
 * A physical lens's zoom relative to the main camera, as camera apps label it: about 1 is `1×`, a
 * telephoto close to a whole number is that number (2.9 → 3, 4.8 → 5), anything else keeps one decimal.
 */
fun snapLensFactor(ratio: Float): Float = when {
    abs(ratio - 1f) <= 0.1f -> 1f
    ratio > 1f && abs(ratio - ratio.roundToInt()) <= 0.25f -> ratio.roundToInt().toFloat()
    else -> (ratio * 10).roundToInt() / 10f
}

/**
 * Lens steps of a camera that zooms continuously across its lenses (a logical multi-camera on
 * Android), as zoom ratios within [minZoom]…[maxZoom]: one per physical lens, never a digital level.
 * [lensRatios] are the physical lenses' ratios when known; without them a minimum zoom below 1 still
 * stands for an ultra-wide lens. The ultra-wide step is the minimum zoom: the widest the camera opens
 * it (vivo, Pixel: `0.6×`).
 */
fun zoomSteps(minZoom: Float, maxZoom: Float, lensRatios: List<Float>): List<Float> {
    val steps = mutableListOf(1f.coerceIn(minZoom, maxZoom))
    for (ratio in lensRatios) {
        val step = if (ratio < 0.95f) minZoom else snapLensFactor(ratio)
        if (step <= maxZoom + 0.01f) steps += step.coerceAtMost(maxZoom)
    }
    if (steps.none { it < 0.95f } && minZoom < 0.95f && lensRatios.isEmpty()) steps += minZoom
    return distinctSteps(steps)
}

/**
 * Lens steps of an iOS virtual device (triple, dual-wide or dual camera), as display factors
 * relative to the wide camera. The device's own zoom factor 1 is its widest lens, and
 * [switchOvers] are the device zoom factors where the next lens takes over; with an ultra-wide the
 * first switch-over is the wide camera, so display = device factor / that switch-over.
 * Returns the device factor of `1×` and the display factors.
 */
fun virtualDeviceSteps(hasUltraWide: Boolean, switchOvers: List<Float>, maxZoom: Float): Pair<Float, List<Float>> {
    val base = if (hasUltraWide) switchOvers.firstOrNull() ?: 1f else 1f
    val steps = (listOf(1f) + switchOvers).filter { it <= maxZoom + 0.01f }.map { it / base }
    return base to distinctSteps(steps)
}

/** Separate cameras offered beside the main camera's steps: one whose factor a step already covers is left out. */
fun lensOptions(mainSteps: List<Float>, otherCameras: List<Pair<String, Float>>, mainId: String = "main"): List<CameraLens> {
    val lenses = mainSteps.map { CameraLens("$mainId@$it", it) }.toMutableList()
    for ((id, factor) in otherCameras) {
        if (lenses.none { abs(it.factor!! - factor) <= 0.1f * maxOf(1f, factor) }) lenses += CameraLens(id, factor)
    }
    return lenses.sortedBy { it.factor }
}

private fun distinctSteps(steps: List<Float>): List<Float> {
    val out = mutableListOf<Float>()
    for (step in steps.sorted()) if (out.none { abs(it - step) < 0.05f }) out += step
    return out
}

/** The selected entry: the one the user picked (or opened with). A pinch never changes it. */
fun activeLens(lenses: List<CameraLens>, selected: String?): CameraLens? =
    lenses.firstOrNull { it.id == selected } ?: lenses.firstOrNull()

// ---------------------------------------------------------------- flash (CAM-035…CAM-039)

/** The menu offers the supported modes in a fixed order, `Off` first; nothing at all when only `Off` is possible. */
fun flashMenu(supported: Set<FlashMode>): List<FlashMode> {
    if (supported.none { it != FlashMode.OFF }) return emptyList()
    return FlashMode.entries.filter { it == FlashMode.OFF || it in supported }
}

/** The mode applied to the active camera: the chosen one where it can do it, otherwise `Off`. */
fun effectiveFlash(chosen: FlashMode, supported: Set<FlashMode>): FlashMode =
    if (chosen == FlashMode.OFF || chosen in supported) chosen else FlashMode.OFF

/** The capture flash for a mode: `Flashlight` already lights the scene, so the flash itself stays off. */
fun captureFlash(mode: FlashMode): FlashMode = if (mode == FlashMode.TORCH) FlashMode.OFF else mode

internal fun FlashMode.icon(): ImageVector = when (this) {
    FlashMode.OFF -> Icons.Rounded.FlashOff
    FlashMode.AUTO -> Icons.Rounded.FlashAuto
    FlashMode.ON -> Icons.Rounded.FlashOn
    FlashMode.TORCH -> Icons.Rounded.FlashlightOn
}

// ---------------------------------------------------------------- viewfinder controls

/** CAM-035: the flash button shows the current mode; a tap opens the menu of the supported modes. */
@Composable
internal fun FlashControl(mode: FlashMode, supported: Set<FlashMode>, onSelect: (FlashMode) -> Unit, modifier: Modifier = Modifier) {
    val entries = flashMenu(supported)
    if (entries.isEmpty()) return
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    var open by remember { mutableStateOf(false) }
    val label = strings.text("Flash")
    val state = strings.text(mode.label)
    Box(modifier) {
        WithTooltip(label) {
            Surface(
                onClick = { open = true },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.42f),
                contentColor = Color.White,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                modifier = Modifier.size(44.dp).semantics {
                    contentDescription = label
                    stateDescription = state
                },
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(mode.icon(), contentDescription = null, modifier = Modifier.size(22.dp)) }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = palette.panel, border = BorderStroke(1.dp, palette.border)) {
            for (entry in entries) {
                DropdownMenuItem(
                    text = { Text(strings.text(entry.label)) },
                    leadingIcon = { Icon(entry.icon(), contentDescription = null) },
                    trailingIcon = { if (entry == mode) Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.accent) },
                    onClick = { open = false; onSelect(entry) },
                    modifier = Modifier.semantics { selected = entry == mode },
                )
            }
        }
    }
}

/** CAM-028: a compact chip with the current lens (or live zoom); a tap opens the lens menu. Shown with two or more lenses. */
@Composable
internal fun LensControl(handle: CameraHandle, modifier: Modifier = Modifier) {
    val lenses = handle.lenses
    if (lenses.size < 2) return
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    var open by remember { mutableStateOf(false) }
    val current = activeLens(lenses, handle.lens)
    // Facing names read `Back` / `Front` in English; the tables key them as `Back camera` / `Front camera`
    // (`Back` alone is the navigation action, 返回).
    val name = { lens: CameraLens ->
        if (!lens.facing || strings.lang == UiLang.EN) lens.label else strings.text("${lens.label} camera")
    }
    // The selected camera, fixed while pinching (CAM-028).
    val chip = current?.let(name).orEmpty()
    val label = strings.text("Camera lens")
    Box(modifier) {
        Surface(
            onClick = { open = true },
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.42f),
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
            modifier = Modifier.height(34.dp).widthIn(min = 52.dp, max = 200.dp).semantics {
                contentDescription = label
                stateDescription = chip
            },
        ) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chip,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = palette.panel, border = BorderStroke(1.dp, palette.border)) {
            for ((index, lens) in lenses.withIndex()) {
                val selected = lens.id == current?.id
                // The front camera follows several rear lenses set apart (CAM-022); `Back` / `Front` need no divider.
                if (lens.front && index > 1) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = palette.border)
                DropdownMenuItem(
                    text = { Text(name(lens), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = if (lens.front) ({ Icon(Icons.Rounded.Cameraswitch, contentDescription = null) }) else null,
                    trailingIcon = { if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.accent) },
                    onClick = { open = false; handle.selectLens(lens) },
                    modifier = Modifier.widthIn(max = 320.dp).semantics { this.selected = selected },
                )
            }
        }
    }
}
