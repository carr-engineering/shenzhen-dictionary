package com.szdict.app.feature.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.theme.LocalDictionaryPalette
import kotlinx.coroutines.awaitCancellation

/** One category chip of the visible page (SRCH-090): label, count, and the list item it scrolls to. */
internal data class CategoryChip(val label: String, val count: Int, val itemIndex: Int)

/** Blocks the platform IME session, so no soft keyboard shows while handwriting is current (HW-004). */
@OptIn(ExperimentalComposeUiApi::class)
private val BlockSoftKeyboard = PlatformTextInputInterceptor { _, _ -> awaitCancellation() }

@OptIn(ExperimentalComposeUiApi::class)
private val PassThroughKeyboard = PlatformTextInputInterceptor { request, next -> next.startInputMethod(request) }

/**
 * The floating search control (SRCH-090…093): field with paste/clear and the input-method button,
 * the pronunciation toggle, the two-segment section indicator and the visible page's category chips.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SearchControl(
    field: TextFieldValue,
    onFieldChange: (TextFieldValue) -> Unit,
    cantonese: Boolean,
    handwriting: Boolean,
    showPronunciation: Boolean,
    canPaste: Boolean,
    indicatorVisible: Boolean,
    chineseCount: Int?,
    englishCount: Int?,
    /** Pager position in pages, 0 = Chinese … 1 = English; read in the draw phase. */
    pageFraction: () -> Float,
    selectedPage: Int,
    chips: List<CategoryChip>,
    elevated: Boolean,
    fieldFocus: FocusRequester,
    onFieldFocused: () -> Unit,
    onFieldTapped: () -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onToggleInputMethod: () -> Unit,
    onTogglePronunciation: () -> Unit,
    onImeSearch: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onChip: (CategoryChip) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val elevation by animateDpAsState(if (elevated) 8.dp else 3.dp, tween(160), label = "search-shadow")
    val interactions = remember { MutableInteractionSource() }
    LaunchedEffect(interactions) {
        interactions.interactions.collect { if (it is PressInteraction.Release) onFieldTapped() }
    }
    Surface(
        modifier = modifier.clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = palette.panel,
        border = BorderStroke(1.dp, palette.border),
        shadowElevation = elevation,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InterceptPlatformTextInput(if (handwriting) BlockSoftKeyboard else PassThroughKeyboard) {
                    TextField(
                        value = field,
                        onValueChange = onFieldChange,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(fieldFocus)
                            .onFocusChanged { if (it.isFocused) onFieldFocused() },
                        singleLine = true,
                        interactionSource = interactions,
                        placeholder = {
                            Text(
                                strings.text(if (cantonese) "English · Jyutping · 漢字" else "English · Pinyin · 汉字"),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 2.dp)) {
                                when {
                                    field.text.isNotEmpty() ->
                                        ControlIcon(Icons.Rounded.Close, strings.text("Clear search"), palette.muted, onClear)
                                    canPaste ->
                                        ControlIcon(Icons.Rounded.ContentPaste, strings.text("Paste clipboard into search"), palette.accent, onPaste)
                                }
                                ControlIcon(
                                    if (handwriting) Icons.Rounded.Keyboard else Icons.Rounded.Draw,
                                    strings.text(if (handwriting) "Switch to keyboard" else "Switch to handwriting"),
                                    palette.muted, onToggleInputMethod,
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onImeSearch() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = palette.panel,
                            unfocusedContainerColor = palette.panel,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = palette.accent,
                            focusedTextColor = palette.fg,
                            unfocusedTextColor = palette.fg,
                            focusedPlaceholderColor = palette.muted,
                            unfocusedPlaceholderColor = palette.muted,
                        ),
                    )
                }
                Box(Modifier.size(width = 1.dp, height = 28.dp).drawBehind { drawRect(palette.border) })
                PronunciationToggle(showPronunciation, onTogglePronunciation, Modifier.padding(horizontal = 4.dp))
            }
            AnimatedVisibility(
                visible = indicatorVisible,
                enter = fadeIn(tween(150)) + expandVertically(tween(190), expandFrom = Alignment.Top),
                exit = fadeOut(tween(110)) + shrinkVertically(tween(160), shrinkTowards = Alignment.Top),
            ) {
                Column {
                    HorizontalDivider(color = palette.border)
                    SectionIndicator(chineseCount, englishCount, pageFraction, selectedPage, onSelectPage)
                }
            }
            AnimatedVisibility(
                visible = indicatorVisible && chips.size > 1,
                enter = fadeIn(tween(150)) + expandVertically(tween(190), expandFrom = Alignment.Top),
                exit = fadeOut(tween(110)) + shrinkVertically(tween(160), shrinkTowards = Alignment.Top),
            ) {
                // Keep the last non-trivial chip row on screen while it shrinks away.
                val shown = remember { arrayOf(chips) }
                if (chips.size > 1) shown[0] = chips
                Column {
                    HorizontalDivider(color = palette.border)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                        shown[0].forEach { chip -> CategoryChipView(chip) { onChip(chip) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlIcon(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(onClick = onClick, shape = CircleShape, color = Color.Transparent, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** GLB-120/122: labelled by what a tap does, and exposed as a toggle. */
@Composable
private fun PronunciationToggle(shown: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val label = strings.text(if (shown) "Hide pronunciation" else "Show pronunciation")
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .toggleable(value = shown, role = Role.Switch, onValueChange = { onToggle() })
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (shown) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
            contentDescription = null,
            tint = if (shown) palette.accent else palette.muted,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Two-segment indicator joined to the field (SRCH-091, SRCH-180, SRCH-184): the accent fill follows
 * the pager offset; segments are tabs; Left/Right move between pages while it has focus.
 */
@Composable
private fun SectionIndicator(
    chineseCount: Int?,
    englishCount: Int?,
    pageFraction: () -> Float,
    selectedPage: Int,
    onSelectPage: (Int) -> Unit,
) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .drawBehind {
                val half = size.width / 2f
                drawRect(palette.accent, topLeft = Offset(half * pageFraction().coerceIn(0f, 1f), 0f), size = Size(half, size.height))
            }
            .selectableGroup()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onSelectPage(0); true }
                    Key.DirectionRight -> { onSelectPage(1); true }
                    else -> false
                }
            },
    ) {
        listOf("CHINESE" to chineseCount, "ENGLISH" to englishCount).forEachIndexed { page, (name, count) ->
            val label = if (count != null) strings.text("$name ({0})", count) else strings.text(name)
            val spoken = if (count != null) strings.text("{0}, {1} results", strings.text(name), count) else strings.text(name)
            val active = (1f - kotlin.math.abs(pageFraction() - page)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = selectedPage == page, role = Role.Tab, onClick = { onSelectPage(page) })
                    .semantics { contentDescription = spoken },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                    color = lerp(palette.muted, palette.onAccent, active),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CategoryChipView(chip: CategoryChip, onClick: () -> Unit) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    // The pill renders small; its touch area is 40 dp high (GLB-081).
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.heightIn(min = 40.dp)) {
            Box(Modifier.padding(horizontal = 3.dp).heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
                Surface(shape = CircleShape, color = palette.bgSoft, border = BorderStroke(1.dp, palette.border)) {
                    Text(
                        "${strings.text(chip.label)} ${chip.count}",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.fg,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}
