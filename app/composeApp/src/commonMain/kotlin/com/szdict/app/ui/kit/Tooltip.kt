package com.szdict.app.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The standard Material 3 plain tooltip around an icon-only control: shown on pointer hover and on
 * long-press. [label] is the control's content description (the same translated string); the tooltip
 * adds no semantics of its own beyond the long-click action TooltipBox contributes, so the control
 * keeps announcing its description once. [modifier] is applied to the wrapper (use it for layout
 * placement such as `align` / `weight`); the wrapper takes the content's size.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithTooltip(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // TooltipBox applies its own modifier to an inner anchor, where parent-scoped modifiers such as
    // `align` / `weight` have no effect: the outer Box carries them to the parent layout instead.
    Box(modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
            content = content,
        )
    }
}
