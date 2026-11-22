package com.szdict.app.feature.reading

import com.szdict.app.ui.kit.WithTooltip
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.szdict.app.AppSession
import com.szdict.app.Destination
import com.szdict.app.feature.conversion.ScriptConversionButton
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.kit.InputMethodGate
import com.szdict.app.ui.kit.InputMethodIcon
import com.szdict.app.ui.kit.LocalSheetInset
import com.szdict.app.ui.kit.PassageText
import com.szdict.app.ui.kit.PronunciationToggle
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.InputMethod
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The Reading destination (TXT-050). The signature is the contract with the app shell. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingScreen(session: AppSession, modifier: Modifier = Modifier) {
    val holder = remember(session) { ReadingStateHolder.of(session) }
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val settings by session.settings.collectAsState()
    val clipboardHasText by session.env.clipboard.hasText.collectAsState(initial = false)
    val playing by session.playing.collectAsState()
    val conversion = holder.conversion
    val draft = holder.draft
    val handwriting = settings.inputMethod == InputMethod.HANDWRITING

    val scroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val interactions = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }

    // Reselecting the destination scrolls to the top (PROD-053).
    val reselect = session.reselect.value
    val initialReselect = remember { reselect.first }
    LaunchedEffect(reselect) {
        if (reselect.second == Destination.READING && reselect.first != initialReselect) scroll.animateScrollTo(0)
    }

    fun showInputMethod(method: InputMethod) {
        if (method == InputMethod.HANDWRITING) {
            keyboard?.hide()
            session.handwritingSink = holder
            session.handwritingVisible = true
        } else {
            session.handwritingVisible = false
            keyboard?.show()
        }
    }

    // Tapping the focused field shows the current method again (HW-004).
    LaunchedEffect(interactions, handwriting) {
        interactions.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release && handwriting && session.wordSheet == null) {
                session.handwritingSink = holder
                session.handwritingVisible = true
            }
        }
    }
    DisposableEffect(holder) {
        onDispose { if (session.handwritingSink === holder) session.handwritingSink = null }
    }

    val bottomInset = WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 900.dp)   // GLB-067
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, top = topInset + 14.dp, bottom = 24.dp + bottomInset + LocalSheetInset.current),
        ) {
            ReadingField(
                holder = holder,
                focused = focused,
                interactions = interactions,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        val gained = state.isFocused && !focused
                        focused = state.isFocused
                        if (gained && session.settings.value.inputMethod == InputMethod.HANDWRITING) {
                            session.handwritingSink = holder
                            if (session.wordSheet == null) session.handwritingVisible = true
                        }
                    },
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { holder.paste() },
                    enabled = clipboardHasText,
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent),
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(strings.text("Paste"))
                }
                OutlinedButton(
                    onClick = {
                        holder.clear()
                        // The clear action re-focuses the field and re-shows the current method (HW-006).
                        focusRequester.requestFocus()
                        showInputMethod(settings.inputMethod)
                    },
                    enabled = draft.text.isNotBlank(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.accent),
                    border = BorderStroke(1.dp, palette.border),
                ) {
                    Icon(Icons.Rounded.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(strings.text("Clear"))
                }
                val switchLabel = strings.text(if (handwriting) "Switch to keyboard" else "Switch to handwriting")
                ActionButton(switchLabel, enabled = true, onClick = {
                    session.closeWordSheetOf(holder)
                    if (handwriting) {
                        session.switchHandwritingToKeyboard()
                        focusRequester.requestFocus()
                    } else {
                        session.updateSettings { it.copy(inputMethod = InputMethod.HANDWRITING) }
                        focusRequester.requestFocus()
                        showInputMethod(InputMethod.HANDWRITING)
                    }
                }) {
                    InputMethodIcon(keyboard = handwriting, label = switchLabel, tint = palette.accent)
                }
            }
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                PronunciationToggle(session)
                ScriptConversionButton(conversion)
            }

            val status: String? = when {
                holder.message != null -> holder.message
                conversion.analysisFailed -> "Unable to analyze text"
                conversion.notice != null -> conversion.notice?.label
                conversion.busy && conversion.passage == null -> "Reading…"
                else -> null
            }
            if (status != null) {
                Text(
                    text = strings.text(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))

            val passage = conversion.passage
            if (passage != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = palette.bgSoft,
                    border = BorderStroke(1.dp, palette.border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box {
                        PassageText(
                            passage = passage,
                            selectedOffset = holder.selectedOffset,
                            onTap = holder::onPassageTap,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                        InPlaceProgress(
                            active = conversion.busy,
                            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                val text = passage.text
                SentenceActionsRow(
                    enabled = text.isNotBlank(),
                    playing = playing != null && playing == holder.playbackKey(),
                    onPlay = holder::togglePlayback,
                    onTranslate = holder::translate,
                    onCopy = holder::copy,
                )
            }
        }
    }
}

@Composable
private fun ReadingField(
    holder: ReadingStateHolder,
    focused: Boolean,
    interactions: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val shape = RoundedCornerShape(12.dp)
    val fill = if (focused) palette.bg else palette.bgSoft
    val style = MaterialTheme.typography.bodyLarge.copy(color = palette.fg)
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val verticalPadding = 12.dp
    val heightLimit = with(density) {
        textMeasurer.measure(AnnotatedString("\n".repeat(7)), style).size.height.toDp() + verticalPadding * 2
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val selection = holder.draft.selection
    val previousSelection = remember { arrayOf(selection) }
    val caret = remember(selection) {
        val followStart = selection.end == previousSelection[0].end && selection.start != previousSelection[0].start
        previousSelection[0] = selection
        if (followStart) selection.start else selection.end
    }
    LaunchedEffect(focused, caret, layout) {
        val measured = layout ?: return@LaunchedEffect
        if (!focused || scroll.viewportSize == 0) return@LaunchedEffect
        val cursor = measured.getCursorRect(caret.coerceIn(0, measured.layoutInput.text.length))
        val padding = with(density) { verticalPadding.toPx() }
        val target = when {
            cursor.top < scroll.value -> cursor.top
            cursor.bottom + 2 * padding > scroll.value + scroll.viewportSize -> cursor.bottom + 2 * padding - scroll.viewportSize
            else -> return@LaunchedEffect
        }
        scroll.scrollTo(target.roundToInt().coerceIn(0, scroll.maxValue))
    }
    // No soft keyboard while handwriting is the current method (HW-004, HW-021).
    InputMethodGate {
        BasicTextField(
            value = holder.draft,
            onValueChange = holder::onDraftChange,
            modifier = modifier,
            minLines = 4,
            textStyle = style,
            interactionSource = interactions,
            cursorBrush = SolidColor(palette.accent),
            onTextLayout = { layout = it },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(max = heightLimit)
                        .clip(shape).background(fill)
                        .border(1.dp, if (focused) palette.accent else palette.border, shape)
                        .drawWithContent {
                            drawContent()
                            val edge = minOf(12.dp.toPx(), size.height / 2)
                            if (edge <= 0f) return@drawWithContent
                            val tint = fill.copy(alpha = 0.82f)
                            val edgeSize = Size(size.width, edge)
                            if (scroll.canScrollBackward) {
                                drawRect(Brush.verticalGradient(listOf(tint, Color.Transparent), endY = edge), size = edgeSize)
                            }
                            if (scroll.canScrollForward) {
                                val start = size.height - edge
                                drawRect(
                                    Brush.verticalGradient(listOf(Color.Transparent, tint), startY = start, endY = size.height),
                                    topLeft = Offset(0f, start), size = edgeSize,
                                )
                            }
                        }
                        // Insets travel with the text so the viewport reaches the visible edges.
                        .verticalScroll(scroll)
                        .padding(horizontal = 16.dp, vertical = verticalPadding),
                ) {
                    if (holder.draft.text.isEmpty()) {
                        Text(strings.text("Paste or type a Chinese passage…"), style = style, color = palette.muted)
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * In-place refresh bar (GLB-033): shown only when the refresh is still running 150 ms after it
 * started, and then for at least 300 ms. Reserves no space.
 */
@Composable
private fun InPlaceProgress(active: Boolean, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            delay(150)
            visible = true
        } else if (visible) {
            delay(300)
            visible = false
        }
    }
    if (visible) {
        val palette = LocalDictionaryPalette.current
        LinearProgressIndicator(modifier = modifier.height(2.dp), color = palette.accent, trackColor = Color.Transparent)
    }
}

/** Play/stop, translate, copy under the passage (MED-020): 40 dp outlined accent icon buttons. */
@Composable
private fun SentenceActionsRow(
    enabled: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    onTranslate: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = appStrings()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionButton(strings.text(if (playing) "Stop sentence playback" else "Play sentence"), enabled, onPlay) {
            AnimatedContent(
                targetState = playing,
                transitionSpec = {
                    (fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.86f)) togetherWith
                        (fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.86f))
                },
                label = "sentence-playback-icon",
            ) { isPlaying ->
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = strings.text(if (isPlaying) "Stop sentence playback" else "Play sentence"),
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        ActionButton(strings.text("Translate sentence"), enabled, onTranslate) { ActionIcon(Icons.Rounded.Translate, strings.text("Translate sentence")) }
        ActionButton(strings.text("Copy sentence"), enabled, onCopy) { ActionIcon(Icons.Rounded.ContentCopy, strings.text("Copy sentence")) }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, description: String) {
    Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(21.dp))
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val palette = LocalDictionaryPalette.current
    WithTooltip(label) {
        OutlinedIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.outlinedIconButtonColors(
                contentColor = palette.accent,
                disabledContentColor = palette.accent.copy(alpha = 0.35f),
            ),
            border = BorderStroke(1.dp, palette.border),
        ) { content() }
    }
}
