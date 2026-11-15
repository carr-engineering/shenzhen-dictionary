package com.szdict.app.feature.entry

import com.szdict.app.ui.kit.WithTooltip
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.szdict.app.i18n.AppStrings
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.kit.SegmentedPillRow
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.app.ui.theme.LocalIsDark
import engineering.carr.hanzianimator.compose.StrokeOrderCanvas
import engineering.carr.hanzianimator.compose.StrokeOrderController
import engineering.carr.hanzianimator.compose.StrokeOrderDefaults
import engineering.carr.hanzianimator.compose.StrokeOrderMode
import engineering.carr.hanzianimator.compose.StrokeOrderStyle
import engineering.carr.hanzianimator.compose.rememberStrokeOrderController
import engineering.carr.hanzianimator.core.StrokeOrder

/** The three modes of a practice pane (MED-062). */
internal enum class StrokePaneMode(val label: String) {
    PREVIEW("Preview"),
    ANIMATION("Animate"),
    PRACTICE("Practice"),
}

/** Panes per row at compact / medium / expanded widths (ENT-066). */
internal fun strokeColumns(width: Dp): Int = when {
    width < 600.dp -> 1
    width < 840.dp -> 2
    else -> 3
}

/** Canvas cap so a whole card fits on short screens (MED-060). */
internal fun strokeCanvasCap(windowHeight: Dp): Dp =
    if (windowHeight > 0.dp) (windowHeight - 260.dp).coerceIn(180.dp, 420.dp) else 420.dp

/**
 * STROKES tab (ENT-066, MED-060…070): one pane per distinct character with stroke data — static
 * preview, animated stroke order, and the guided practice quiz of shenzhen-hanzi-animator.
 */
@Composable
internal fun StrokesTab(orders: List<StrokeOrder>, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val canvasCap = strokeCanvasCap(windowHeight)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = strokeColumns(maxWidth)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            orders.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { order -> StrokePane(order, canvasCap, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun strokeStyle(): StrokeOrderStyle {
    val palette = LocalDictionaryPalette.current
    return StrokeOrderDefaults.style(
        dark = LocalIsDark.current,
        outlineColor = palette.muted.copy(alpha = 0.16f),
        completedStrokeColor = palette.fg.copy(alpha = 0.92f),
        activeStrokeColor = palette.fg.copy(alpha = 0.92f),
        radicalStrokeColor = palette.accent,
        hintColor = palette.accent.copy(alpha = 0.58f),
        traceColor = palette.tone(3),
        gridColor = palette.border.copy(alpha = 0.6f),
        // MED-069: no component colouring in practice; an empty palette restores radical colouring.
        componentColors = emptyList(),
    )
}

@Composable
private fun StrokePane(order: StrokeOrder, canvasCap: Dp, modifier: Modifier = Modifier) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val controller = rememberStrokeOrderController(order)
    var mode by rememberSaveable(order.character) { mutableStateOf(StrokePaneMode.PREVIEW) }
    val canPractice = order.supportsPractice   // MED-070: unusable medians still render, without practice

    LaunchedEffect(controller, mode) {
        when (mode) {
            StrokePaneMode.PREVIEW -> controller.showFullCharacter()
            StrokePaneMode.ANIMATION -> controller.play()
            StrokePaneMode.PRACTICE -> if (!controller.startQuiz()) mode = StrokePaneMode.PREVIEW
        }
    }

    Surface(modifier.padding(top = 4.dp), shape = RoundedCornerShape(16.dp), color = palette.panel, border = BorderStroke(1.dp, palette.border)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(order.character, style = MaterialTheme.typography.headlineSmall, color = palette.fg)
                Text(strings.text("{0} strokes", order.strokeCount), style = MaterialTheme.typography.bodyMedium, color = palette.muted)
            }
            Spacer(Modifier.height(8.dp))
            val modes = if (canPractice) StrokePaneMode.entries else StrokePaneMode.entries.filter { it != StrokePaneMode.PRACTICE }
            SegmentedPillRow(
                options = modes.map { strings.text(it.label) },
                selectedIndex = modes.indexOf(mode).coerceAtLeast(0),
                onSelect = { index -> modes.getOrNull(index)?.let { mode = it } },
            )
            Spacer(Modifier.height(8.dp))
            val canvasLabel = strings.text("Stroke order of {0}", order.character)
            StrokeOrderCanvas(
                controller = controller,
                modifier = Modifier.widthIn(max = canvasCap).fillMaxWidth().aspectRatio(1f).semantics { contentDescription = canvasLabel },
                style = strokeStyle(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                strokeFeedback(mode, controller, order, strings),
                style = MaterialTheme.typography.bodyMedium,
                color = if (controller.isQuizComplete) palette.tone(3) else palette.muted,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.heightIn(min = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (mode) {
                    StrokePaneMode.PREVIEW -> Unit
                    StrokePaneMode.ANIMATION ->
                        PaneButton(Icons.Rounded.Replay, strings.text("Replay stroke order")) { controller.play() }   // MED-063
                    StrokePaneMode.PRACTICE -> {
                        PaneButton(Icons.Rounded.Lightbulb, strings.text("Show next stroke hint"), enabled = !controller.isQuizComplete) { controller.showHint() }
                        PaneButton(Icons.Rounded.Replay, strings.text("Reset practice")) { controller.startQuiz() }   // MED-068
                        PaneButton(
                            if (controller.outlineVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            if (controller.outlineVisible) strings.text("Hide outline") else strings.text("Show outline"),
                        ) { controller.outlineVisible = !controller.outlineVisible }
                    }
                }
            }
        }
    }
}

/** Status line under the canvas (MED-064, MED-067). */
internal fun strokeFeedback(mode: StrokePaneMode, controller: StrokeOrderController, order: StrokeOrder, strings: AppStrings): String = when {
    mode == StrokePaneMode.PREVIEW -> strings.text("Stroke order preview")
    mode == StrokePaneMode.ANIMATION -> if (controller.isAnimating) strings.text("Playing stroke order") else strings.text("Stroke order")
    controller.mode != StrokeOrderMode.Quiz -> strings.text("Practice")
    controller.isQuizComplete -> strings.text("Complete!")
    controller.hintVisible -> strings.text("Follow the hint for stroke {0} of {1}", controller.currentStroke + 1, order.strokeCount)
    controller.mistakesOnCurrentStroke > 0 -> strings.text("Try stroke {0} again", controller.currentStroke + 1)
    else -> strings.text("Draw stroke {0} of {1}", controller.currentStroke + 1, order.strokeCount)
}

@Composable
private fun PaneButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalDictionaryPalette.current
    WithTooltip(description) {
        OutlinedIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = palette.accent, disabledContentColor = palette.accent.copy(alpha = 0.35f)),
            border = BorderStroke(1.dp, palette.border),
        ) { Icon(icon, contentDescription = description, modifier = Modifier.size(21.dp)) }
    }
}
