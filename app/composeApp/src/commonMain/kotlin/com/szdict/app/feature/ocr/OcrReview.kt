package com.szdict.app.feature.ocr

import com.szdict.app.ui.kit.WithTooltip
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import com.szdict.app.AppSession
import com.szdict.app.ImagePaneMaxWidth
import com.szdict.app.feature.conversion.ScriptConversionButton
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.kit.LocalSheetInset
import com.szdict.app.ui.kit.PassageText
import com.szdict.app.ui.kit.passageTextStyle
import com.szdict.app.ui.kit.wordHover
import com.szdict.app.ui.kit.PronunciationToggle
import com.szdict.app.ui.kit.SegmentedPillRow
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.recognition.Quad

/** Review of a capture (CAM-060…CAM-070): header and the Image / Text views. */
@Composable
internal fun ReviewPhase(session: AppSession, holder: OcrStateHolder, pickImage: () -> Unit) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val capture = holder.capture ?: return
    val density = LocalDensity.current
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val headerHeight = with(density) { headerHeightPx.toDp() }
    val listState = holder.listState
    val shadowAlpha by animateFloatAsState(
        targetValue = if (holder.viewMode == OcrViewMode.TEXT && listState.canScrollBackward) 1f else 0f,
        animationSpec = tween(180),
        label = "ocr-header-shadow",
    )
    val hasAllTextCard = holder.lines.any { it.text.isNotBlank() }

    // Stepping from the sheet scrolls the text list to the owning line, below the header (CAM-070).
    val focus = holder.focusRequest
    LaunchedEffect(focus, holder.viewMode) {
        if (focus != null && holder.viewMode == OcrViewMode.TEXT && holder.takeFocus(focus)) {
            listState.animateScrollToItem(focus.line + if (hasAllTextCard) 1 else 0, -headerHeightPx)
        }
    }

    // On a wide window the review keeps a comfortable width, centred (GLB-067).
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = ImagePaneMaxWidth).fillMaxSize()) {
            when (holder.viewMode) {
                OcrViewMode.IMAGE -> OcrImageViewer(
                    holder = holder,
                    capture = capture,
                    // A page over the destination takes the wheel for itself.
                    wheelEnabled = session.routes.isEmpty(),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp, top = headerHeight + 8.dp, end = 16.dp, bottom = 12.dp)
                        .clip(MaterialTheme.shapes.large),
                )
                OcrViewMode.TEXT -> Box(Modifier.fillMaxSize().padding(bottom = LocalSheetInset.current)) {
                    OcrTextList(session, holder, topPadding = headerHeight + 8.dp)
                }
            }

            if (holder.recognising) {
                val download = holder.modelDownload
                val indicator = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = headerHeight + 8.dp)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                Box(indicator, contentAlignment = Alignment.TopCenter) {
                    if (download is ModelDownload.Failed || (download is ModelDownload.InProgress && download.fraction != null)) {
                        ModelDownloadPanel(download, holder::retryModelDownload)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = palette.accent, trackColor = palette.border)
                    }
                }
            }
            val error = holder.recognitionError
            val message = when {
                error != null -> strings.text(error.first) + (error.second?.let { ": $it" } ?: "")
                holder.noChinese && !holder.recognising -> strings.text("No Chinese text found. Retake to try again.")
                else -> null
            }
            if (message != null) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 24.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = palette.panel,
                    border = BorderStroke(1.dp, palette.border),
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.fg,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                ReviewHeader(holder, pickImage, onHeight = { headerHeightPx = it })
                // CAM-061: the gradient shadow fades in only when the text list can scroll up.
                Box(
                    Modifier.fillMaxWidth().height(8.dp).background(
                        Brush.verticalGradient(listOf(palette.border.copy(alpha = 0.52f * shadowAlpha), palette.border.copy(alpha = 0f))),
                    ),
                )
            }
        }
    }
}

@Composable
private fun ReviewHeader(holder: OcrStateHolder, pickImage: () -> Unit, onHeight: (Int) -> Unit) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val camera = holder.capture?.source == CaptureSource.CAMERA
    var wide by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    Surface(
        Modifier.fillMaxWidth().onSizeChanged {
            onHeight(it.height)
            wide = with(density) { it.width.toDp() } >= 440.dp
        },
        color = palette.bg.copy(alpha = 0.88f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, top = statusBarTop + 8.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The primary action keeps its label where there is room; on a narrow phone the gallery
            // variant, which shares the row with two more actions, is its icon alone.
            val labelled = camera || wide
            val label = strings.text(if (camera) "Retake" else "Pick new image")
            val icon = if (camera) Icons.Rounded.Refresh else Icons.Rounded.PhotoLibrary
            val onClick: () -> Unit = if (camera) ({ holder.backToInput() }) else pickImage
            if (labelled) {
                TextButton(onClick = onClick) {
                    Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(label, color = palette.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                WithTooltip(label, Modifier.padding(start = 4.dp)) {
                    IconButton(onClick = onClick) {
                        Icon(icon, contentDescription = label, tint = palette.accent, modifier = Modifier.size(22.dp))
                    }
                }
            }
            if (!camera) {
                WithTooltip(strings.text("Camera")) {
                    IconButton(onClick = { holder.backToInput() }) {
                        Icon(Icons.Rounded.PhotoCamera, contentDescription = strings.text("Camera"), tint = palette.accent, modifier = Modifier.size(22.dp))
                    }
                }
            }
            SaveButton(holder)
            Spacer(Modifier.weight(1f))
            SegmentedPillRow(
                options = listOf(strings.text("Image"), strings.text("Text")),
                selectedIndex = if (holder.viewMode == OcrViewMode.IMAGE) 0 else 1,
                onSelect = { holder.changeViewMode(if (it == 0) OcrViewMode.IMAGE else OcrViewMode.TEXT) },
            )
        }
    }
}

/** CAM-134: `Downloading text recognition… 45%` with the size, or the failure with `Retry`. */
@Composable
private fun ModelDownloadPanel(download: ModelDownload, onRetry: () -> Unit) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    Surface(
        modifier = Modifier.padding(24.dp).widthIn(max = 360.dp),
        shape = MaterialTheme.shapes.medium,
        color = palette.panel,
        border = BorderStroke(1.dp, palette.border),
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (download) {
                is ModelDownload.InProgress -> {
                    val fraction = (download.fraction ?: 0f).coerceIn(0f, 1f)
                    Text(
                        strings.text("Downloading text recognition… {0}%", (fraction * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.fg,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = palette.accent, trackColor = palette.border)
                    Spacer(Modifier.height(8.dp))
                    Text(megabytes(download.totalBytes), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                }
                is ModelDownload.Failed -> {
                    val size = download.totalBytes
                    Text(
                        if (size != null) strings.text("Text recognition needs a one-time download of {0}. Check your connection and try again.", megabytes(size))
                        else strings.text("Text recognition needs a one-time download. Check your connection and try again."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.fg,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onRetry) { Text(strings.text("Retry"), color = palette.accent) }
                }
            }
        }
    }
}

private fun megabytes(bytes: Long): String {
    val tenths = (bytes * 10 + 524_288) / 1_048_576
    return "${tenths / 10}.${tenths % 10} MB"
}

/** CAM-072: enabled for camera captures only; progress while writing; a saved state afterwards. */
@Composable
private fun SaveButton(holder: OcrStateHolder) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val state = holder.saveState
    val save = rememberSaveAction(holder::savePhoto)
    WithTooltip(strings.text(if (state == SaveState.SAVED) "Photo saved" else "Save photo")) {
        IconButton(
            onClick = save,
            enabled = holder.canSave && state != SaveState.SAVING && state != SaveState.SAVED,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = palette.accent,
                disabledContentColor = if (state == SaveState.SAVED) palette.accent else palette.muted.copy(alpha = 0.45f),
            ),
        ) {
            when (state) {
                SaveState.SAVING -> CircularProgressIndicator(color = palette.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                SaveState.SAVED -> Icon(Icons.Rounded.Check, contentDescription = strings.text("Photo saved"), modifier = Modifier.size(22.dp))
                else -> Icon(Icons.Rounded.SaveAlt, contentDescription = strings.text("Save photo"), modifier = Modifier.size(22.dp))
            }
        }
    }
}

private val TapSlop = 24.dp

/**
 * The capture, fit-centre on black, with the recognition overlay in the same transform (CAM-062),
 * pinch zoom 1x–8x with pan (CAM-063), double-tap zoom (CAM-064) and glyph hit-testing (CAM-065).
 * The overlay lives inside the zoomed layer, so it only deals with the fit transform.
 */
@Composable
private fun OcrImageViewer(holder: OcrStateHolder, capture: OcrCapture, wheelEnabled: Boolean, modifier: Modifier = Modifier) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val density = LocalDensity.current
    val image = capture.image
    var container by remember { mutableStateOf(IntSize.Zero) }
    val viewport = remember(image, container) { ImageViewport(image.width, image.height, container.width.toFloat(), container.height.toFloat()) }
    val lines = holder.lines
    val ocrLines = remember(lines) { lines.map { it.original } }
    val selected = holder.selected

    // Stepping pans so the newly selected glyph is centred, 220 ms (CAM-067).
    val focus = holder.focusRequest
    LaunchedEffect(focus, viewport) {
        val ref = focus?.ref ?: return@LaunchedEffect
        if (!viewport.valid) return@LaunchedEffect
        val quad = ocrLines.getOrNull(ref.line)?.chars?.getOrNull(ref.char)?.box ?: return@LaunchedEffect
        if (!holder.takeFocus(focus)) return@LaunchedEffect
        val start = holder.translation
        val target = viewport.centreOn(Offset(quad.center.x, quad.center.y), holder.zoom)
        animate(0f, 1f, animationSpec = tween(220)) { progress, _ -> holder.translation = start + (target - start) * progress }
    }

    Box(
        modifier
            .background(Color.Black)
            .clipToBounds()
            .onSizeChanged { container = it }
            // Trackpad pinch and Ctrl/Cmd + wheel zoom about the pointer; two-finger scroll pans (CAM-063).
            .wheelGestures(
                enabled = wheelEnabled && viewport.valid,
                onZoom = { position, factor ->
                    val (zoom, translation) = viewport.transform(holder.zoom, holder.translation, position, Offset.Zero, factor)
                    holder.zoom = zoom
                    holder.translation = translation
                },
                onPan = { pan -> holder.translation = viewport.clamp(holder.translation + pan, holder.zoom) },
            )
            .pointerInput(viewport) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    if (!viewport.valid) return@detectTransformGestures
                    val (zoom, translation) = viewport.transform(holder.zoom, holder.translation, centroid, pan, gestureZoom)
                    holder.zoom = zoom
                    holder.translation = translation
                }
            }
            .wordHover(capture) { position ->
                if (!viewport.valid || !wheelEnabled) return@wordHover null
                val point = viewport.toImage(position, holder.zoom, holder.translation)
                hitTestChar(ocrLines, point.x, point.y, 0f)?.let(holder::hoverChar)
            }
            .pointerInput(viewport, ocrLines) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (!viewport.valid) return@detectTapGestures
                        val (zoom, translation) = viewport.doubleTap(holder.zoom, tap)
                        holder.zoom = zoom
                        holder.translation = translation
                    },
                    onTap = { tap ->
                        if (!viewport.valid) return@detectTapGestures
                        val point = viewport.toImage(tap, holder.zoom, holder.translation)
                        val slop = viewport.slop(with(density) { TapSlop.toPx() }, holder.zoom)
                        hitTestChar(ocrLines, point.x, point.y, slop)?.let(holder::tapChar)
                    },
                )
            },
    ) {
        val zoom = holder.zoom
        val translation = holder.translation
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = translation.x
                translationY = translation.y
                transformOrigin = TransformOrigin(0f, 0f)
            },
        ) {
            image.bitmap?.let { bitmap ->
                Image(bitmap, contentDescription = strings.text("Captured photo"), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Canvas(Modifier.fillMaxSize()) {
                val fit = ImageViewport(image.width, image.height, size.width, size.height)
                if (!fit.valid) return@Canvas
                val s0 = fit.fitScale
                val b = fit.letterbox
                fun map(x: Float, y: Float) = Offset(b.x + x * s0, b.y + y * s0)
                fun path(quad: Quad) = Path().apply {
                    val p = quad.points
                    val first = map(p[0].x, p[0].y)
                    moveTo(first.x, first.y)
                    for (i in 1 until 4) map(p[i].x, p[i].y).let { lineTo(it.x, it.y) }
                    close()
                }
                // Hairline whatever the zoom.
                val hairline = 1.5.dp.toPx() / zoom
                val dots = PathEffect.dashPathEffect(floatArrayOf(hairline * 0.1f, hairline * 3.2f))
                lines.forEachIndexed { lineIndex, line ->
                    if (lineIndex != selected?.line) {
                        drawPath(path(line.original.box), palette.accent.copy(alpha = 0.35f), style = Stroke(hairline))
                    }
                    line.original.chars.forEachIndexed glyph@{ charIndex, char ->
                        if (lineIndex == selected?.line && charIndex == selected.char) return@glyph
                        val corrected = line.isCorrected(charIndex)
                        if (!corrected && !char.isUncertain()) return@glyph
                        // Marker just under the glyph: dotted where the recogniser was unsure, solid once corrected.
                        val q = char.box
                        val lift = 0.06f
                        val from = map(q.bottomLeft.x + (q.topLeft.x - q.bottomLeft.x) * lift, q.bottomLeft.y + (q.topLeft.y - q.bottomLeft.y) * lift)
                        val to = map(q.bottomRight.x + (q.topRight.x - q.bottomRight.x) * lift, q.bottomRight.y + (q.topRight.y - q.bottomRight.y) * lift)
                        val inset = (to - from) * 0.14f
                        drawLine(
                            color = if (corrected) palette.accent else palette.onAccent,
                            start = from + inset,
                            end = to - inset,
                            strokeWidth = hairline * 1.5f,
                            cap = StrokeCap.Round,
                            pathEffect = if (corrected) null else dots,
                        )
                    }
                }
                selected?.let { ref ->
                    val quad = lines.getOrNull(ref.line)?.original?.chars?.getOrNull(ref.char)?.box ?: return@let
                    val points = quad.points.map { map(it.x, it.y) }
                    val left = points.minOf { it.x }
                    val top = points.minOf { it.y }
                    val width = points.maxOf { it.x } - left
                    val height = points.maxOf { it.y } - top
                    val padding = maxOf(4.dp.toPx() / zoom, minOf(width, height) * 0.04f)
                    drawRoundRect(
                        color = palette.accent.copy(alpha = 0.72f),
                        topLeft = Offset(left - padding, top - padding),
                        size = Size(width + 2 * padding, height + 2 * padding),
                        cornerRadius = CornerRadius(4.dp.toPx() / zoom),
                        style = Stroke(hairline),
                    )
                }
            }
        }
    }
}

/** CAM-069: the `All text` card, then one card per line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OcrTextList(session: AppSession, holder: OcrStateHolder, topPadding: Dp) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val playing by session.playing.collectAsState()
    val review = holder.textReview
    val lines = holder.lines
    val hasText = lines.any { it.text.isNotBlank() }
    LazyColumn(
        state = holder.listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPadding, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (hasText) {
            item(key = "all-text") {
                Card {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(strings.text("All text"), style = MaterialTheme.typography.labelMedium, color = palette.muted)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            holder.displayedText(null),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.fg,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            SentenceActions(holder, line = null, playing = playing != null && playing == holder.playbackKey(null))
                            // The readings above the lines below: this view only.
                            PronunciationToggle(session)
                            // CAM-135: the split button applies to the whole capture.
                            ScriptConversionButton(
                                selection = holder.conversion,
                                enabled = holder.conversionEnabled,
                                onMainAction = holder::toggleConversion,
                                onConvert = holder::convert,
                            )
                        }
                        holder.conversionNotice?.let { notice ->
                            Text(strings.text(notice.label), style = MaterialTheme.typography.bodySmall, color = palette.muted, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    if (holder.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = palette.accent, trackColor = Color.Transparent)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
            val text = review?.texts?.getOrNull(index) ?: line.text
            val passage = review?.linePassages?.getOrNull(index)?.takeIf { it.text == text }
            Card {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    if (passage != null) {
                        PassageText(
                            passage = passage,
                            selectedOffset = holder.selectedOffsetIn(index),
                            onTap = { holder.tapText(index, it) },
                            style = passageTextStyle(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        // CAM-055: a line that could not be analysed is plain muted text.
                        Text(text, style = passageTextStyle(), color = palette.muted, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(10.dp))
                    SentenceActions(holder, line = index, playing = playing != null && playing == holder.playbackKey(index))
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val palette = LocalDictionaryPalette.current
    Surface(
        modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = palette.panel,
        border = BorderStroke(1.dp, palette.border),
    ) { Box { content() } }
}

/** Play/stop, translate, copy on the displayed text (MED-020). */
@Composable
private fun SentenceActions(holder: OcrStateHolder, line: Int?, playing: Boolean) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val enabled = holder.displayedText(line).isNotBlank()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = palette.accent, disabledContentColor = palette.accent.copy(alpha = 0.35f))
        val border = BorderStroke(1.dp, palette.border)
        WithTooltip(strings.text(if (playing) "Stop sentence playback" else "Play sentence")) {
            OutlinedIconButton(onClick = { holder.togglePlayback(line) }, enabled = enabled, modifier = Modifier.size(40.dp), colors = colors, border = border) {
                Icon(
                    if (playing) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = strings.text(if (playing) "Stop sentence playback" else "Play sentence"),
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        WithTooltip(strings.text("Translate sentence")) {
            OutlinedIconButton(onClick = { holder.translate(line) }, enabled = enabled, modifier = Modifier.size(40.dp), colors = colors, border = border) {
                Icon(Icons.Rounded.Translate, contentDescription = strings.text("Translate sentence"), modifier = Modifier.size(21.dp))
            }
        }
        WithTooltip(strings.text("Copy sentence")) {
            OutlinedIconButton(onClick = { holder.copy(line) }, enabled = enabled, modifier = Modifier.size(40.dp), colors = colors, border = border) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = strings.text("Copy sentence"), modifier = Modifier.size(21.dp))
            }
        }
    }
}

/**
 * The other characters that could stand at the selected position, best first, as the first row of
 * the word sheet (the sheet's header slot). The current reading is filled; picking another
 * replaces the character in its line (the sheet refreshes on the same position), and picking the
 * recognised reading — marked with a dot — undoes the correction. Absent when there is nothing to
 * choose from and while the Text view shows converted text.
 */
@Composable
internal fun AlternativesRow(holder: OcrStateHolder) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    // Corrections address the original lines; while the Text view shows converted text there is nothing to pick.
    val ref = holder.selected?.takeIf { !(holder.viewMode == OcrViewMode.TEXT && holder.showingConverted) }
    LaunchedEffect(ref) { ref?.let(holder::loadAlternatives) }
    val choices = ref?.let(holder::alternatives).orEmpty()
    var shown by remember { mutableStateOf(choices) }
    var shownRef by remember { mutableStateOf(ref) }
    if (choices.isNotEmpty()) {
        shown = choices
        shownRef = ref
    }
    val rowState = rememberLazyListState()
    LaunchedEffect(shownRef) { rowState.scrollToItem(0) }
    AnimatedVisibility(
        visible = choices.isNotEmpty(),
        enter = fadeIn(tween(140)) + expandVertically(tween(160)),
        exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
    ) {
        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, bottom = 8.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The label scrolls away with the chips.
            item(key = "label") {
                Text(
                    strings.text("Could also be"),
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.muted,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            itemsIndexed(shown, key = { _, choice -> choice.text }) { index, choice ->
                    val label = strings.text("Alternative {0}, {1} of {2}", choice.text, index + 1, shown.size) +
                        if (choice.recognised) ", " + strings.text("recognized") else ""
                    Surface(
                        onClick = { shownRef?.let { holder.pickAlternative(it, choice.text) } },
                        shape = RoundedCornerShape(10.dp),
                        color = if (choice.current) palette.accent else palette.bgSoft,
                        contentColor = if (choice.current) palette.onAccent else palette.fg,
                        border = if (choice.current) null else BorderStroke(1.dp, palette.border),
                        modifier = Modifier.size(40.dp).semantics {
                            contentDescription = label
                            selected = choice.current
                            role = Role.RadioButton
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(choice.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, maxLines = 1)
                            if (choice.recognised) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(5.dp)
                                        .background(if (choice.current) palette.onAccent else palette.accent, CircleShape),
                                )
                            }
                        }
                    }
                }
        }
    }
}
