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
import androidx.compose.material.icons.rounded.Check
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
import com.szdict.app.ui.theme.LocalDictionaryPalette
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------- lenses (CAM-028)

/**
 * One entry of the lens menu. [factor] is the zoom relative to the main wide camera (1 = `1×`,
 * the ultra-wide about 0.5); null where the platform knows no zoom (Web), and [name] is shown instead.
 */
data class CameraLens(val id: String, val factor: Float?, val name: String? = null) {
    val label: String get() = factor?.let(::formatZoom) ?: name.orEmpty()
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
 * Android), as zoom ratios within [minZoom]…[maxZoom]. [lensRatios] are the physical lenses' ratios
 * when known; without them the steps are the widest zoom (where it is below 1), `1×` and a digital
 * `2×`. A digital `2×` is also offered when no lens is longer than the main one, as camera apps do.
 */
fun zoomSteps(minZoom: Float, maxZoom: Float, lensRatios: List<Float>): List<Float> {
    val steps = mutableListOf(1f.coerceIn(minZoom, maxZoom))
    for (ratio in lensRatios) {
        val step = if (ratio < 0.95f) maxOf(ratio, minZoom) else snapLensFactor(ratio)
        if (step <= maxZoom + 0.01f) steps += step.coerceAtMost(maxZoom)
    }
    if (steps.none { it < 0.95f } && minZoom < 0.95f && lensRatios.isEmpty()) steps += minZoom
    if (steps.none { it > 1.05f } && maxZoom >= 2f) steps += 2f
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
    val steps = (listOf(1f) + switchOvers).filter { it <= maxZoom + 0.01f }.map { it / base }.toMutableList()
    if (steps.none { it > 1.05f } && maxZoom >= 2f * base) steps += 2f
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

/**
 * The lens shown as selected: with a live [zoom], the longest lens at or below it (a pinch from
 * `1×` to `1.8×` is still on `1×`); otherwise the one the user picked.
 */
fun activeLens(lenses: List<CameraLens>, selected: String?, zoom: Float?): CameraLens? {
    if (zoom != null && lenses.isNotEmpty() && lenses.all { it.factor != null }) {
        return lenses.lastOrNull { it.factor!! <= zoom + 0.02f } ?: lenses.first()
    }
    return lenses.firstOrNull { it.id == selected } ?: lenses.firstOrNull()
}

/** The chip shows the live zoom factor, as system camera apps do; the camera's name where there is no zoom. */
fun lensChipLabel(lenses: List<CameraLens>, selected: String?, zoom: Float?): String {
    if (zoom != null && lenses.any { it.factor != null }) return formatZoom(zoom)
    return activeLens(lenses, selected, zoom)?.label.orEmpty()
}

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
    val current = activeLens(lenses, handle.lens, handle.zoom)
    val chip = lensChipLabel(lenses, handle.lens, handle.zoom)
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
            for (lens in lenses) {
                val selected = lens.id == current?.id
                DropdownMenuItem(
                    text = { Text(lens.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.accent) },
                    onClick = { open = false; handle.selectLens(lens) },
                    modifier = Modifier.widthIn(max = 320.dp).semantics { this.selected = selected },
                )
            }
        }
    }
}
