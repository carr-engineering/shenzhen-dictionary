package com.szdict.recognition.ocr

import com.szdict.recognition.OcrAlt
import com.szdict.recognition.OcrChar
import com.szdict.recognition.OcrImage
import com.szdict.recognition.OcrLine
import com.szdict.recognition.OcrResult
import com.szdict.recognition.Point
import com.szdict.recognition.Quad
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Raw detector head output: `rows x cols x 7` unsigned bytes with their quantisation. */
internal class DetectorOutput(val data: ByteArray, val rows: Int, val cols: Int, val scale: Float, val zeroPoint: Int)

/** Per-frame output of a sequence model: `frames x classes` unsigned bytes with their quantisation. */
internal class SequenceOutput(val data: ByteArray, val frames: Int, val classes: Int, val scale: Float, val zeroPoint: Int)

/** @property emissionOffset see [CharLayout]. */
internal enum class SequenceModel(val assetPrefix: String, val emissionOffset: Double) {
    SCRIPT("script", 0.0),
    HANI("hani", 3.9),
    LATN("latn", 2.0),
}

/**
 * The neural graphs the pipeline needs. Implemented with LiteRT on Android; everything else in the
 * pipeline is plain Kotlin.
 */
internal interface OcrNets {
    /** Runs the text detector on a [width] x [height] luminance image (both multiples of 32). */
    fun detect(gray: ByteArray, width: Int, height: Int): DetectorOutput

    /**
     * Runs a line model on a normalised line of height 24 and the given [width] (a multiple of 4),
     * passed column-major (`columns[x * 24 + y]`). [SequenceModel.SCRIPT] returns class
     * probabilities per frame; the recognisers return pre-softmax logits per frame.
     */
    fun sequence(model: SequenceModel, columns: ByteArray, width: Int): SequenceOutput

    fun close()
}

internal class OcrOptions(
    /** Number of candidates kept per character (including the top-1). */
    val alternatives: Int = 8,
    /**
     * The working image, from which lines are cut for the recogniser, is capped to this many pixels
     * on its longer side. (Detection runs on a copy of at most 1280 pixels.)
     */
    val maxSide: Int = 2560,
    val maxLines: Int = 200,
    /** Height the ink is scaled to inside the 24-px recogniser input. */
    val inkHeight: Int = 20,
    /** Whether small text gets a second, finer detector pass. */
    val fineDetection: Boolean = true,
)

/**
 * Image OCR around the extracted detector, script/direction classifier and Chinese/Latin line
 * recognisers: a Kotlin port of the reference browser pipeline (`worker.js`), extended with
 * per-character boxes and per-character alternatives taken from the recogniser's frame logits.
 */
internal class OcrPipeline(
    private val nets: OcrNets,
    private val haniLabels: List<String>,
    private val latnLabels: List<String>,
    private val options: OcrOptions = OcrOptions(),
    /** Optional diagnostics sink (one message per detected region). */
    private val trace: ((String) -> Unit)? = null,
) {
    /** Ink threshold for the page being read (see InkAnalysis.inkBounds). */
    private var inkThreshold = 18

    /** A prepared line crop, upright and contrast-normalised. */
    private class Crop(
        val gray: Gray,
        /** Ink box in [gray]. */
        val box: IntArray,
        /** Refined rotated rectangle of the ink in working-image pixels. */
        val region: Region,
        /** Centre and angle the crop raster was cut with. */
        val cx: Double,
        val cy: Double,
        val angle: Double,
        /** True when [gray] was rotated by 180 degrees after cutting. */
        val flipped: Boolean = false,
        /** Vertical columns: the glyph cells laid out side by side in [gray]. */
        val cells: List<Cell>? = null,
        /** Vertical columns: the raster the cells index into. */
        val source: Crop? = null,
    )

    /** One glyph of a vertical column: rows `[a, b)` of the column crop, drawn `width x height` at strip x `offset`. */
    private class Cell(val a: Int, val b: Int, val offset: Int, val width: Int, val height: Int)

    private class Line(
        var quad: Array<DoubleArray>,
        var box: Box,
        val chars: MutableList<OcrChar>,
        val vertical: Boolean,
        val angle: Double,
    ) {
        val text: String get() = chars.joinToString("") { it.text }
        val confidence: Double get() = chars.sumOf { it.confidence.toDouble() } / max(1, chars.size)
    }

    /** Line regions in page pixels: [rows] from the upright detector pass, [columns] from the turned one. */
    private class Layout(val rows: List<Region>, val columns: List<Region>)

    /** @param checkpoint called between lines; throw from it to cancel. */
    fun recognize(image: OcrImage, checkpoint: () -> Unit = {}): OcrResult {
        var page = Gray.fromArgb(image)
        val longest = max(page.w, page.h)
        if (longest > options.maxSide) {
            val shrink = options.maxSide.toDouble() / longest
            page = page.resize(max(1, (page.w * shrink).roundToInt()), max(1, (page.h * shrink).roundToInt()))
        }
        val backX = image.width.toDouble() / page.w
        val backY = image.height.toDouble() / page.h
        if (page.cornerLevel() < 110) page.invert()
        checkpoint()

        val layout = detectLayout(page)
        checkpoint()
        val lines = readRegions(page, Geometry.verticalColumns(layout.rows).take(options.maxLines), checkpoint) +
            readColumns(page, layout.columns.take(options.maxLines), checkpoint)
        val selected = select(lines)
        levelBoxes(selected)
        val assembled = assembleRows(selected)
        // Horizontal text in reading order, then vertical columns right to left.
        val ordered = Geometry.readingOrder(assembled.filter { !it.vertical }, false) { it.box } +
            Geometry.readingOrder(assembled.filter { it.vertical }, true) { it.box }
        fun scaled(q: Quad): Quad = if (backX == 1.0 && backY == 1.0) q else Quad(
            Point((q.topLeft.x * backX).toFloat(), (q.topLeft.y * backY).toFloat()),
            Point((q.topRight.x * backX).toFloat(), (q.topRight.y * backY).toFloat()),
            Point((q.bottomRight.x * backX).toFloat(), (q.bottomRight.y * backY).toFloat()),
            Point((q.bottomLeft.x * backX).toFloat(), (q.bottomLeft.y * backY).toFloat()),
        )
        return OcrResult(
            image.width,
            image.height,
            ordered.map { line ->
                OcrLine(
                    text = line.text,
                    box = scaled(quadOf(line.quad)),
                    chars = line.chars.map { it.copy(box = scaled(it.box)) },
                    vertical = line.vertical,
                )
            },
        )
    }

    private companion object {
        const val STRONG_PROPOSAL = 0.65
        const val WEAK_PROPOSAL = 0.35

        /** A region at least this many times longer than tall holds several glyphs. */
        const val MULTI_GLYPH = 2.5

        /** Line height in detector pixels under which the detector starts to miss text. */
        const val SMALL_TEXT = 20.0
    }

    private fun readRegions(page: Gray, regions: List<Region>, checkpoint: () -> Unit): List<Line> {
        val lines = ArrayList<Line>()
        for (region in regions) {
            checkpoint()
            val line = readRegion(page, region)
            trace?.invoke(
                "region cx=${region.cx.toInt()} cy=${region.cy.toInt()} w=${region.w.toInt()} h=${region.h.toInt()} angle=${(region.angle * 100).toInt() / 100.0} " +
                    "score=${(region.score * 100).toInt() / 100.0} n=${region.proposals} vertical=${region.vertical} -> ${line?.text}",
            )
            lines.add(line ?: continue)
        }
        return lines
    }

    /**
     * Crops, classifies and reads one detected region. The classifier reports the script and one of
     * four orientations of the line: upright, upside down, or vertical writing laid on its side
     * (glyphs turned a quarter turn, either way round). The recogniser reads vertical writing in
     * that form natively, so a column needs no glyph segmentation.
     */
    private fun readRegion(page: Gray, region: Region): Line? {
        var crop = (if (region.vertical) verticalCrop(page, region) else cropRegion(page, region)) ?: return null
        var script = SequenceModel.HANI
        var column = false
        if (!region.vertical) {
            val scores = classify(crop)
            trace?.invoke("  classes " + scores.indices.filter { scores[it] > .03f }.joinToString(" ") { "$it:${(scores[it] * 100).toInt()}" })
            if (scores[0] + scores[1] > scores[2] + scores[3] + scores[31]) script = SequenceModel.LATN
            // One or two glyphs are too little to judge orientation from.
            if (crop.box[2] - crop.box[0] >= (crop.box[3] - crop.box[1]) * 1.8) {
                if (scores[38] + scores[39] > max(.5f, scores[36] + scores[37])) crop = flip(crop)
                column = scores[37] + scores[39] > scores[36] + scores[38]
            }
        }
        return read(script, crop, page, column)
    }

    /**
     * Finds text lines along both axes. The detector only finds horizontal lines, so it runs twice:
     * on the page, and on the page turned a quarter turn, where vertical columns (and the lines of
     * a page photographed sideways) lie horizontally. Glyph blocks look like text to it either way
     * round, so a turned detection only counts as a column where the upright pass found no proper
     * lines; this is decided per region, which lets a column stand beside a block of horizontal text.
     */
    private fun detectLayout(page: Gray): Layout {
        val (upright, fine) = detectRegions(page)
        val turnedPage = page.rotate90()
        // Turned (x', y') is page (y', pageHeight - x'); a turned line runs up the page.
        val turned = detectRegions(turnedPage, fine).first.map { it.copy(cx = it.cy, cy = page.h - it.cx, angle = it.angle - PI / 2) }
        val rowBoxes = upright.filter { it.w >= it.h * MULTI_GLYPH }.map { Geometry.bounds(it) }
        val columns = turned.filter { r ->
            if (r.w < r.h * MULTI_GLYPH) return@filter false
            val box = Geometry.bounds(r)
            rowBoxes.sumOf { intersection(it, box) } < box.width * box.height * .3
        }
        val columnBoxes = columns.map { Geometry.bounds(it) }
        // Stray glyph-sized upright detections inside a column belong to the column.
        val rows = upright.filter { r -> r.w >= r.h * MULTI_GLYPH || columnBoxes.none { r.cx > it.x0 && r.cx < it.x1 && r.cy > it.y0 && r.cy < it.y1 } }
        trace?.invoke("layout: ${rows.size} upright regions, ${columns.size} columns of ${turned.size} turned regions")
        return Layout(
            Geometry.joinCollinear(rows.map { extendAlongInk(page, it, columnBoxes) }),
            Geometry.joinCollinear(columns.map { extendAlongInk(page, it, rowBoxes) }),
        )
    }

    private fun intersection(a: Box, b: Box): Double =
        max(0.0, min(a.x1, b.x1) - max(a.x0, b.x0)) * max(0.0, min(a.y1, b.y1) - max(a.y0, b.y0))

    /** Reads turned-pass regions: natively on their side first, by glyph cells if that reads poorly. */
    private fun readColumns(page: Gray, columns: List<Region>, checkpoint: () -> Unit): List<Line> {
        val lines = ArrayList<Line>()
        for (r in columns) {
            checkpoint()
            var best = readRegion(page, r)
            if (best == null || best.confidence < .5) {
                val sliced = readRegion(page, Region(r.cx, r.cy, r.h, r.w, r.angle + PI / 2, r.score, r.proposals, vertical = true, charHeight = r.h))
                if (sliced != null && (best == null || sliced.confidence > best.confidence)) best = sliced
            }
            trace?.invoke("column cx=${r.cx.toInt()} cy=${r.cy.toInt()} w=${r.h.toInt()} h=${r.w.toInt()} -> ${best?.text} (${best?.vertical}, ${best?.confidence})")
            if (best != null && best.confidence > .25) lines.add(best)
        }
        return lines
    }

    /**
     * Resolves competing readings of the same area: duplicates within one orientation first, then,
     * between lines of different orientation or detector pass, the reading with more confident
     * characters wins and lines mostly covered by winners are dropped.
     */
    private fun select(lines: List<Line>): List<Line> {
        val ranked = deduplicate(lines).sortedByDescending { it.chars.size * it.confidence }
        val kept = ArrayList<Line>()
        for (candidate in ranked) {
            val area = max(1.0, Geometry.area(candidate.quad.toList()))
            var covered = 0.0
            for (other in kept) {
                if (other.vertical == candidate.vertical && abs(other.angle - candidate.angle) < .5) continue
                covered += Geometry.intersectionArea(other.quad, candidate.quad)
            }
            if (covered / area < .3) kept.add(candidate)
        }
        return kept
    }

    /**
     * Detector proposals merged into line regions, in the given raster's pixels. The first pass sees
     * the page at 640 to 1280 pixels; when it finds small text (or nothing), a second pass at twice
     * that size or at the page's full resolution reads the lines too small for the first.
     */
    private fun detectRegions(page: Gray, fine: Boolean? = null): Pair<List<Region>, Boolean> {
        val longest = max(page.w, page.h)
        val coarseSide = min(1280, max(640, longest))
        var proposals = propose(page, coarseSide)
        var usedFine = false
        // Small pages are magnified for the second pass, large ones seen at full resolution.
        val fineSide = min(options.maxSide, max(longest, coarseSide * 2))
        if (options.fineDetection && fineSide > coarseSide * 1.25) {
            val toDetector = coarseSide.toDouble() / longest
            val small = proposals.count { it.score >= STRONG_PROPOSAL && it.h * toDetector < SMALL_TEXT }
            if (fine ?: (small >= 3 || proposals.none { it.score >= STRONG_PROPOSAL })) {
                // At that size the coarse pass places lines only roughly (its cells are 16 pixels):
                // small text comes from the fine pass alone, large text from the coarse one.
                proposals = proposals.filter { it.h * toDetector >= SMALL_TEXT * 1.25 } + propose(page, fineSide)
                usedFine = true
            }
        }
        val regions = Geometry.absorbFragments(Geometry.mergeProposals(proposals, STRONG_PROPOSAL).filter { it.w > 4 && it.h > 5 && it.h < longest * 1.3 })
        return regions to usedFine
    }

    /** One detector pass with the page scaled to [side] pixels on its longer side. */
    private fun propose(page: Gray, side: Int): List<Region> {
        val w = page.w
        val h = page.h
        val scale = side.toDouble() / max(w, h)
        val dw = max(32, ceil(w * scale / 32).toInt() * 32)
        val dh = max(32, ceil(h * scale / 32).toInt() * 32)
        val small = page.resize(dw, dh)
        if (small.cornerLevel() < 110) small.invert()
        val out = nets.detect(small.px, dw, dh)
        check(out.rows == dh / 16 && out.cols == dw / 16) { "Detector output dimensions do not match input" }
        // Weak proposals bridge the glyphs the detector is unsure about (thin strokes, dense characters).
        return Geometry.decodeDetector(out.data, out.rows, out.cols, out.scale.toDouble(), out.zeroPoint, w.toDouble() / dw, h.toDouble() / dh, WEAK_PROPOSAL)
    }

    /**
     * Stretches a line region over ink that continues it. The detector misses some glyphs outright
     * (dense traditional characters in thin type, trailing punctuation); a text line nevertheless
     * runs on for as long as there is ink on its baseline without a gap wider than a glyph, and
     * stops where a line of the other direction ([obstacles]) begins.
     */
    private fun extendAlongInk(page: Gray, region: Region, obstacles: List<Box>): Region {
        if (region.w < region.h * 1.2) return region // single glyphs may be parts of a vertical column
        val reach = (region.h * 8).roundToInt()
        val core = ceil(region.w).toInt()
        val w = core + 2 * reach
        val pad = max(3, ceil(region.h * .35).toInt())
        val h = max(4, ceil(region.h).toInt()) + 2 * pad
        val ink = InkAnalysis.inkBounds(page.rotatedCrop(region.cx, region.cy, region.angle, w, h), inkThreshold)
        if (ink.box == null) return region
        val (top, bottom) = InkAnalysis.textRows(ink.mask, w, h, pad + (region.h * .2).toInt(), h - pad - (region.h * .2).toInt(), reach, reach + core)
        val inked = BooleanArray(w) { x ->
            var any = false
            for (y in top until bottom) if (ink.mask[y * w + x].toInt() != 0) { any = true; break }
            any
        }
        val limit = max(3, (region.h * .9).roundToInt())
        val cosA = cos(region.angle)
        val sinA = sin(region.angle)
        fun blocked(x: Int): Boolean {
            if (obstacles.isEmpty()) return false
            val px = region.cx + (x + .5 - w / 2.0) * cosA
            val py = region.cy + (x + .5 - w / 2.0) * sinA
            return obstacles.any { px > it.x0 && px < it.x1 && py > it.y0 && py < it.y1 }
        }
        // Walks outward over runs of inked columns and stops at the first run that is not a glyph
        // of this line: one that crosses the text band (see InkAnalysis.crossesBand).
        fun walk(from: Int, step: Int): Int {
            var edge = from
            var blank = 0
            var x = from
            while (x in 0 until w && blank <= limit && !blocked(x)) {
                if (!inked[x]) { blank++; x += step; continue }
                var end = x
                while (end in 0 until w && inked[end]) end += step
                if (InkAnalysis.crossesBand(ink.mask, w, h, min(x, end + 1), max(x + 1, end), top, bottom)) break
                edge = end
                blank = 0
                x = end
            }
            return edge
        }
        val right = walk(w - reach, 1)
        val left = walk(reach - 1, -1) + 1
        val growRight = (right - (w - reach)).toDouble()
        val growLeft = (reach - left).toDouble()
        if (growRight <= 0 && growLeft <= 0) return region
        return region.stretched((growRight - growLeft) / 2, region.w + growLeft + growRight)
    }

    private fun cropRegion(page: Gray, detected: Region): Crop? {
        var region = detected
        // The detector's height is rough, more so for small text: cut the line with headroom and
        // let the ink decide where the line really begins and ends (see InkAnalysis.lineBand).
        val pad = if (region.vertical) 2 else max(2, ceil(region.h * .35).toInt())
        val w = max(1, ceil(region.w + 4).toInt())
        val h = max(1, ceil(region.h).toInt() + 2 * pad)
        var ink = InkAnalysis.inkBounds(page.rotatedCrop(region.cx, region.cy, region.angle, w, h), inkThreshold)
        if (ink.box == null) return null
        if (!region.vertical && region.w > region.h * 3) {
            // The detector's angle is coarse; a residual tilt over a long line shrinks and skews the glyphs.
            val skew = InkAnalysis.residualSkew(ink.mask, w, h)
            if (skew != 0.0) {
                region = region.copy(angle = region.angle + skew)
                ink = InkAnalysis.inkBounds(page.rotatedCrop(region.cx, region.cy, region.angle, w, h), inkThreshold)
            }
        }
        if (!region.vertical) InkAnalysis.dropSolid(ink.mask, w, h, rows = region.w > region.h * 3)
        // Rows are judged where the detector itself saw the text, not on what was added at the ends.
        val anchorW = if (region.anchorW < 0) region.w else region.anchorW
        val colLo = (w / 2.0 + region.anchorShift - anchorW / 2).toInt()
        val colHi = ceil(w / 2.0 + region.anchorShift + anchorW / 2).toInt()
        val box = (if (region.vertical) ink.box else InkAnalysis.lineBand(ink.mask, w, h, pad, h - pad, colLo, colHi)) ?: return null
        val c = cos(region.angle)
        val s = sin(region.angle)
        val u = (box[0] + box[2]) / 2.0 - w / 2.0
        val v = (box[1] + box[3]) / 2.0 - h / 2.0
        val refined = region.copy(
            cx = region.cx + u * c - v * s,
            cy = region.cy + u * s + v * c,
            w = (box[2] - box[0]).toDouble(),
            h = (box[3] - box[1]).toDouble(),
        )
        return Crop(InkAnalysis.normalizeContrast(ink), box, refined, region.cx, region.cy, region.angle)
    }

    /** Cuts a vertical column into glyph cells and lays them out left to right as one text line. */
    private fun verticalCrop(page: Gray, region: Region): Crop? {
        val crop = cropRegion(page, region) ?: return null
        val (x0, y0, x1, y1) = crop.box
        val ink = InkAnalysis.inkBounds(crop.gray)
        val cells = ArrayList<Cell>()
        var offset = 0
        val columnWidth = x1 - x0
        for ((a, b) in ColumnSlicer.slice(ink.mask, crop.gray.w, x0, y0, x1, y1)) {
            // A flat glyph (一) keeps its shape: the cell is at least most of a glyph square tall.
            val extent = max((b - a).toDouble(), columnWidth * .85)
            val width = max(10, (columnWidth * 20.0 / extent).roundToInt())
            cells.add(Cell(a, b, offset, width, max(1, ((b - a) * 20.0 / extent).roundToInt())))
            offset += width + 2
        }
        if (cells.isEmpty()) return null
        val strip = Gray(offset, 24)
        strip.px.fill(255.toByte())
        for (cell in cells) {
            val glyph = crop.gray.resample(x0.toDouble(), cell.a.toDouble(), x1.toDouble(), cell.b.toDouble(), cell.width, cell.height)
            val top = 2 + (20 - cell.height) / 2
            for (y in 0 until cell.height) glyph.px.copyInto(strip.px, (y + top) * strip.w + cell.offset, y * cell.width, (y + 1) * cell.width)
        }
        return Crop(strip, intArrayOf(0, 2, offset, 22), crop.region, crop.cx, crop.cy, crop.angle, cells = cells, source = crop)
    }

    private fun flip(crop: Crop): Crop {
        val (x0, y0, x1, y1) = crop.box
        val w = crop.gray.w
        val h = crop.gray.h
        return Crop(crop.gray.rotate180(), intArrayOf(w - x1, h - y1, w - x0, h - y0), crop.region, crop.cx, crop.cy, crop.angle, flipped = true)
    }

    /** The recogniser input: ink scaled to 20 px height at (6, 2) on a white 24-px-high strip. */
    private class Strip(val columns: ByteArray, val width: Int, val inkWidth: Int)

    private fun normalized(crop: Crop, maxWidth: Int): Strip {
        val (x0, y0, x1, y1) = crop.box
        val ih = y1 - y0
        val target = if (crop.cells != null) 20 else options.inkHeight
        val top = (24 - target) / 2
        val inkWidth = min(maxWidth - 16, max(4, ((x1 - x0) * target.toDouble() / ih).roundToInt()))
        val width = ceil((inkWidth + 16) / 4.0).toInt() * 4
        val scaled = crop.gray.resample(x0.toDouble(), y0.toDouble(), x1.toDouble(), y1.toDouble(), inkWidth, target)
        val columns = ByteArray(width * 24) { 255.toByte() }
        for (x in 0 until inkWidth) for (y in 0 until target) columns[(x + 6) * 24 + y + top] = scaled.px[y * inkWidth + x]
        return Strip(columns, width, inkWidth)
    }

    /** Mean script/direction class probabilities over the line's interior frames. */
    private fun classify(crop: Crop): FloatArray {
        val strip = normalized(crop, 512)
        val out = nets.sequence(SequenceModel.SCRIPT, strip.columns, strip.width)
        val scores = FloatArray(max(43, out.classes))
        var count = 0
        for (t in 0 until out.frames) {
            if (out.frames < 7 || (t >= 3 && t < out.frames - 3)) {
                for (k in 0 until out.classes) scores[k] += (out.data[t * out.classes + k].toInt() and 0xFF) / 256f
                count++
            }
        }
        for (k in scores.indices) scores[k] /= max(1, count)
        return scores
    }

    private fun read(model: SequenceModel, crop: Crop, page: Gray, column: Boolean = false): Line? {
        val labels = if (model == SequenceModel.HANI) haniLabels else latnLabels
        val strip = normalized(crop, 2048)
        val out = nets.sequence(model, strip.columns, strip.width)
        trace?.invoke("STRIP ${strip.width} " + strip.columns.joinToString("") { ((it.toInt() and 0xFF) / 16).toString(16) })
        var emissions = trimSpaces(CtcDecoder.decode(out.data, out.frames, out.classes, labels.size, out.scale, options.alternatives, labels.indexOf(" ")), labels)
        // Glyph spacing in a column is leading, not word spacing.
        if (column) emissions = emissions.filter { !labels[it.label].isBlank() }
        if (emissions.isEmpty()) return null
        val spans = CharLayout.spans(strip.columns, strip.width, emissions.map { it.peakFrame }, emissions.map { labels[it.label].isBlank() }, model.emissionOffset)
        val (x0, y0, x1, y1) = crop.box
        val chars = ArrayList<OcrChar>(emissions.size)
        emissions.forEachIndexed { i, e ->
            val alternatives = List(e.labels.size) { OcrAlt(labels[e.labels[it]], e.scores[it]) }
            val quad = if (crop.cells != null) {
                val source = crop.source!!
                val centre = (spans[i][0] + spans[i][1]) / 2 - 6
                val cell = crop.cells.minByOrNull { abs(it.offset + it.width / 2.0 - centre) }!!
                cropQuad(source, source.box[0].toDouble(), cell.a.toDouble(), source.box[2].toDouble(), cell.b.toDouble())
            } else {
                val ua = (x0 + (spans[i][0] - 6) * (x1 - x0) / strip.inkWidth).coerceIn(x0.toDouble(), x1.toDouble())
                val ub = (x0 + (spans[i][1] - 6) * (x1 - x0) / strip.inkWidth).coerceIn(x0.toDouble(), x1.toDouble())
                cropQuad(crop, ua, y0.toDouble(), ub, y1.toDouble())
            }
            chars.add(OcrChar(alternatives[0].text, quadOf(if (column) upright(quad) else quad), alternatives[0].score, alternatives))
        }
        var quad = Geometry.corners(crop.region)
        if (crop.flipped) quad = arrayOf(quad[2], quad[3], quad[0], quad[1])
        if (column) quad = upright(quad)
        val b = Geometry.bounds(crop.region)
        val box = Box(b.x0.coerceIn(0.0, page.w.toDouble()), b.y0.coerceIn(0.0, page.h.toDouble()), b.x1.coerceIn(0.0, page.w.toDouble()), b.y1.coerceIn(0.0, page.h.toDouble()))
        return Line(quad, box, chars, crop.cells != null || column, crop.region.angle)
    }

    /**
     * A column read on its side has its top at the line's left and the page's right-hand side up:
     * the line-relative corners (TL, TR, BR, BL) are the glyphs' (TR, BR, BL, TL).
     */
    private fun upright(q: Array<DoubleArray>): Array<DoubleArray> = arrayOf(q[3], q[0], q[1], q[2])

    /** Drops leading and trailing space symbols (the reference trims the decoded text). */
    private fun trimSpaces(emissions: List<Emission>, labels: List<String>): List<Emission> {
        var from = 0
        var to = emissions.size
        while (from < to && labels[emissions[from].label].isBlank()) from++
        while (to > from && labels[emissions[to - 1].label].isBlank()) to--
        return emissions.subList(from, to)
    }

    /** Maps the crop-raster rectangle `[ua, ub) x [va, vb)` to working-image corners, glyph-relative order. */
    private fun cropQuad(crop: Crop, ua: Double, va: Double, ub: Double, vb: Double): Array<DoubleArray> {
        val w = crop.gray.w
        val h = crop.gray.h
        val c = cos(crop.angle)
        val s = sin(crop.angle)
        fun map(u0: Double, v0: Double): DoubleArray {
            val u = (if (crop.flipped) w - u0 else u0) - w / 2.0
            val v = (if (crop.flipped) h - v0 else v0) - h / 2.0
            return doubleArrayOf(crop.cx + u * c - v * s, crop.cy + u * s + v * c)
        }
        return arrayOf(map(ua, va), map(ub, va), map(ub, vb), map(ua, vb))
    }

    private fun quadOf(q: Array<DoubleArray>) = Quad(
        Point(q[0][0].toFloat(), q[0][1].toFloat()),
        Point(q[1][0].toFloat(), q[1][1].toFloat()),
        Point(q[2][0].toFloat(), q[2][1].toFloat()),
        Point(q[3][0].toFloat(), q[3][1].toFloat()),
    )

    /**
     * Drops a line that duplicates a longer one: either its text is contained in a longer line
     * covering the same area, or most of it lies inside a longer line (a partial second detection
     * of the same row, which reads as garbage).
     */
    private fun deduplicate(lines: List<Line>): List<Line> {
        val ranked = lines.sortedWith(compareByDescending<Line> { it.text.codePointCount() }.thenByDescending { it.confidence })
        return ranked.filterIndexed { i, line ->
            (0 until i).none {
                val other = ranked[it]
                // Rotated rectangles, not their bounding boxes: neighbouring lines of tilted text
                // have heavily overlapping bounding boxes without sharing any area.
                val shared = Geometry.intersectionArea(other.quad, line.quad) /
                    max(1.0, min(Geometry.area(other.quad.toList()), Geometry.area(line.quad.toList())))
                (other.text.contains(line.text) && shared > .8) ||
                    (shared > .5 && other.vertical == line.vertical)
            }
        }
    }

    /**
     * On a tilted page the bounding boxes of neighbouring lines overlap and say little about rows
     * and columns. Row assembly and reading order therefore work on boxes taken in the page's own
     * frame: every line is turned back by the median tilt of the horizontal lines.
     */
    private fun levelBoxes(lines: List<Line>) {
        val angles = lines.filter { !it.vertical && abs(it.angle) < .8 }.map { it.angle }.sorted()
        if (angles.isEmpty()) return
        val tilt = angles[angles.size / 2]
        if (abs(tilt) < .03) return
        val c = cos(-tilt)
        val s = sin(-tilt)
        for (line in lines) {
            val xs = line.quad.map { it[0] * c - it[1] * s }
            val ys = line.quad.map { it[0] * s + it[1] * c }
            line.box = Box(xs.min(), ys.min(), xs.max(), ys.max())
        }
    }

    /** Joins fragments of one visual row that the detector reported separately. */
    private fun assembleRows(lines: List<Line>): List<Line> {
        val out = ArrayList<Line>()
        for (line in lines.sortedBy { it.box.x0 }) {
            val height = line.box.height
            val previous = out.firstOrNull { p ->
                !p.vertical && !line.vertical &&
                    abs((p.box.y0 + p.box.y1 - line.box.y0 - line.box.y1) / 2) < max(height, p.box.height) * .3 &&
                    line.box.x0 >= p.box.x1 - 2 && line.box.x0 - p.box.x1 < max(height, p.box.height) * .9
            }
            if (previous == null) { out.add(line); continue }
            val gap = line.box.x0 - previous.box.x1
            val needsSpace = gap > height * .12 && !(isHan(previous.text.lastCodePoint()) && isHan(line.text.firstCodePoint()))
            if (needsSpace) {
                val a = previous.chars.last().box
                val b = line.chars.first().box
                val space = Quad(a.topRight, b.topLeft, b.bottomLeft, a.bottomRight)
                previous.chars.add(OcrChar(" ", space, 1f, listOf(OcrAlt(" ", 1f))))
            }
            previous.chars.addAll(line.chars)
            previous.box = previous.box.union(line.box)
            previous.quad = unionQuad(previous.quad, line.quad)
        }
        return out
    }

    /** Smallest rectangle, in the first quad's orientation, containing both quads. */
    private fun unionQuad(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val dx = a[1][0] - a[0][0]
        val dy = a[1][1] - a[0][1]
        val length = kotlin.math.hypot(dx, dy)
        if (length < 1e-6) return a
        val c = dx / length
        val s = dy / length
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        var top = Double.POSITIVE_INFINITY
        var bottom = Double.NEGATIVE_INFINITY
        for (p in a + b) {
            val u = p[0] * c + p[1] * s
            val v = -p[0] * s + p[1] * c
            lo = min(lo, u); hi = max(hi, u); top = min(top, v); bottom = max(bottom, v)
        }
        fun back(u: Double, v: Double) = doubleArrayOf(u * c - v * s, u * s + v * c)
        return arrayOf(back(lo, top), back(hi, top), back(hi, bottom), back(lo, bottom))
    }
}

internal fun String.codePointCount(): Int {
    var n = 0
    var i = 0
    while (i < length) {
        if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) i++
        i++
        n++
    }
    return n
}

internal fun String.firstCodePoint(): Int =
    if (isEmpty()) -1 else if (length > 1 && this[0].isHighSurrogate() && this[1].isLowSurrogate()) codePoint(this[0], this[1]) else this[0].code

internal fun String.lastCodePoint(): Int =
    if (isEmpty()) -1 else if (length > 1 && this[length - 1].isLowSurrogate() && this[length - 2].isHighSurrogate()) codePoint(this[length - 2], this[length - 1]) else this[length - 1].code

private fun codePoint(high: Char, low: Char): Int = ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000

/** Han ideographs (URO, Extension A, compatibility, supplementary planes). */
internal fun isHan(codePoint: Int): Boolean =
    codePoint in 0x3400..0x4DBF || codePoint in 0x4E00..0x9FFF || codePoint in 0xF900..0xFAFF ||
        codePoint in 0x20000..0x2FA1F || codePoint in 0x30000..0x3347F || codePoint == 0x3007
