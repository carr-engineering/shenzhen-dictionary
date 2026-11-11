package com.szdict.recognition.ocr

import com.szdict.recognition.OcrAlt
import com.szdict.recognition.OcrChar
import com.szdict.recognition.OcrImage
import com.szdict.recognition.OcrLine
import com.szdict.recognition.OcrResult
import com.szdict.recognition.Point
import com.szdict.recognition.Quad
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
    /** The working image is capped to this many pixels on its longer side. */
    val maxSide: Int = 1600,
    val maxLines: Int = 200,
    /** Height the ink is scaled to inside the 24-px recogniser input. */
    val inkHeight: Int = 20,
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

    /** One glyph of a vertical column: rows `[a, b)` of the column crop, at strip x `[offset, offset + width)`. */
    private class Cell(val a: Int, val b: Int, val offset: Int, val width: Int)

    private class Line(
        var quad: Array<DoubleArray>,
        var box: Box,
        val chars: MutableList<OcrChar>,
        val vertical: Boolean,
        val angle: Double,
    ) {
        val text: String get() = chars.joinToString("") { it.text }
    }

    /** @param checkpoint called between lines; throw from it to cancel. */
    fun recognize(image: OcrImage, checkpoint: () -> Unit = {}): OcrResult {
        var page = Gray.fromArgb(image)
        val longest = max(page.w, page.h)
        var shrink = 1.0
        if (longest > options.maxSide) {
            shrink = options.maxSide.toDouble() / longest
            page = page.resize(max(1, (page.w * shrink).roundToInt()), max(1, (page.h * shrink).roundToInt()))
        }
        val backX = image.width.toDouble() / page.w
        val backY = image.height.toDouble() / page.h
        if (page.cornerLevel() < 110) page.invert()
        checkpoint()

        val regions = Geometry.verticalColumns(detect(page)).take(options.maxLines)
        val lines = ArrayList<Line>()
        for (region in regions) {
            checkpoint()
            var crop = (if (region.vertical) verticalCrop(page, region) else cropRegion(page, region)) ?: continue
            val scores = classify(crop)
            var script = if (scores[0] + scores[1] > scores[2] + scores[3] + scores[31]) SequenceModel.LATN else SequenceModel.HANI
            if (region.vertical) script = SequenceModel.HANI
            val upsideDown = !region.vertical && scores[38] + scores[39] > max(.5f, scores[36] + scores[37])
            if (upsideDown) crop = flip(crop)
            val line = read(script, crop, page)
            trace?.invoke(
                "region cx=${region.cx.toInt()} cy=${region.cy.toInt()} w=${region.w.toInt()} h=${region.h.toInt()} angle=${(region.angle * 100).toInt() / 100.0} " +
                    "score=${(region.score * 100).toInt() / 100.0} n=${region.proposals} vertical=${region.vertical} script=$script flipped=$upsideDown -> ${line?.text}",
            )
            lines.add(line ?: continue)
        }
        val assembled = assembleRows(deduplicate(lines))
        val ordered = Geometry.readingOrder(assembled, assembled.isNotEmpty() && assembled.all { it.vertical }) { it.box }
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

    private fun detect(page: Gray): List<Region> {
        val w = page.w
        val h = page.h
        val side = min(1280, max(640, max(w, h)))
        val scale = side.toDouble() / max(w, h)
        val dw = max(32, ceil(w * scale / 32).toInt() * 32)
        val dh = max(32, ceil(h * scale / 32).toInt() * 32)
        val small = page.resize(dw, dh)
        if (small.cornerLevel() < 110) small.invert()
        val out = nets.detect(small.px, dw, dh)
        check(out.rows == dh / 16 && out.cols == dw / 16) { "Detector output dimensions do not match input" }
        val proposals = Geometry.decodeDetector(out.data, out.rows, out.cols, out.scale.toDouble(), out.zeroPoint, w.toDouble() / dw, h.toDouble() / dh)
        return Geometry.absorbFragments(Geometry.mergeProposals(proposals).filter { it.w > 4 && it.h > 5 && it.h < max(w, h) * 1.3 })
    }

    private fun cropRegion(page: Gray, detected: Region): Crop? {
        var region = detected
        val w = max(1, ceil(region.w + 4).toInt())
        val h = max(1, ceil(region.h + 4).toInt())
        var ink = InkAnalysis.inkBounds(page.rotatedCrop(region.cx, region.cy, region.angle, w, h))
        if (ink.box == null) return null
        if (!region.vertical && region.w > region.h * 3) {
            // The detector's angle is coarse; a residual tilt over a long line shrinks and skews the glyphs.
            val skew = InkAnalysis.residualSkew(ink.mask, w, h)
            if (skew != 0.0) {
                region = region.copy(angle = region.angle + skew)
                ink = InkAnalysis.inkBounds(page.rotatedCrop(region.cx, region.cy, region.angle, w, h))
            }
        }
        val box = ink.box ?: return null
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
        return Crop(InkAnalysis.normalizeContrast(ink.gray), box, refined, region.cx, region.cy, region.angle)
    }

    /** Cuts a vertical column into glyph cells and lays them out left to right as one text line. */
    private fun verticalCrop(page: Gray, region: Region): Crop? {
        val crop = cropRegion(page, region) ?: return null
        val (x0, y0, x1, y1) = crop.box
        val ink = InkAnalysis.inkBounds(crop.gray)
        val bands = ArrayList<IntArray>()
        var first = -1
        var last = -1
        val gap = max(4.0, region.charHeight * .3)
        for (y in y0 until y1) {
            var count = 0
            for (x in x0 until x1) count += ink.mask[y * crop.gray.w + x]
            if (count >= 2) { if (first < 0) first = y; last = y }
            if (first >= 0 && (y - last > gap || y == y1 - 1)) {
                if (last - first > 3) bands.add(intArrayOf(first, last + 1))
                first = -1
            }
        }
        if (bands.isEmpty()) return null
        val cells = ArrayList<Cell>()
        var offset = 0
        for ((a, b) in bands) {
            val width = max(10, ((x1 - x0) * 20.0 / (b - a)).roundToInt())
            cells.add(Cell(a, b, offset, width))
            offset += width + 2
        }
        val strip = Gray(offset, 24)
        strip.px.fill(255.toByte())
        for (cell in cells) {
            val glyph = crop.gray.resample(x0.toDouble(), cell.a.toDouble(), x1.toDouble(), cell.b.toDouble(), cell.width, 20)
            for (y in 0 until 20) glyph.px.copyInto(strip.px, (y + 2) * strip.w + cell.offset, y * cell.width, (y + 1) * cell.width)
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

    private fun read(model: SequenceModel, crop: Crop, page: Gray): Line? {
        val labels = if (model == SequenceModel.HANI) haniLabels else latnLabels
        val strip = normalized(crop, 2048)
        val out = nets.sequence(model, strip.columns, strip.width)
        val emissions = trimSpaces(CtcDecoder.decode(out.data, out.frames, out.classes, labels.size, out.scale, options.alternatives, labels.indexOf(" ")), labels)
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
            chars.add(OcrChar(alternatives[0].text, quadOf(quad), alternatives[0].score, alternatives))
        }
        var quad = Geometry.corners(crop.region)
        if (crop.flipped) quad = arrayOf(quad[2], quad[3], quad[0], quad[1])
        val b = Geometry.bounds(crop.region)
        val box = Box(b.x0.coerceIn(0.0, page.w.toDouble()), b.y0.coerceIn(0.0, page.h.toDouble()), b.x1.coerceIn(0.0, page.w.toDouble()), b.y1.coerceIn(0.0, page.h.toDouble()))
        return Line(quad, box, chars, crop.cells != null, crop.region.angle)
    }

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
        val ranked = lines.sortedByDescending { it.text.codePointCount() }
        return ranked.filterIndexed { i, line ->
            (0 until i).none {
                val other = ranked[it]
                val shared = Geometry.overlap(other.box, line.box)
                (other.text.contains(line.text) && shared > .8) ||
                    (shared > .5 && other.vertical == line.vertical && other.text.codePointCount() > line.text.codePointCount())
            }
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
