package com.szdict.recognition.ocr2

import com.szdict.recognition.OcrAlt
import com.szdict.recognition.OcrChar
import com.szdict.recognition.OcrImage
import com.szdict.recognition.OcrLine
import com.szdict.recognition.OcrResult
import com.szdict.recognition.Point
import com.szdict.recognition.Quad
import com.szdict.recognition.ocr.Box
import com.szdict.recognition.ocr.Geometry
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Image OCR with the PP-OCRv4 mobile detector, the PP-OCR orientation classifier and the PP-OCRv6
 * Small recogniser: a Kotlin port of the pipeline that was evaluated (RapidOCR 3.10 driven by
 * `ocr-baseline/tools/benchmark_models.py`, profile "v4"), extended to the app's contract.
 *
 *  1. The image is padded above and below when it is very flat, and enlarged for the detector so
 *     that its shorter side has 736 pixels, by at most a factor of two (sides rounded to multiples of 32).
 *  2. [DbPostProcess] turns the detector's probability map into line boxes.
 *  3. Every box is cut out with a perspective transform at its own size; a crop at least 1.5 times
 *     taller than wide is turned a quarter counter-clockwise, so a vertical column reads left to right.
 *  4. The classifier turns lines that are upside down.
 *  5. The recogniser reads the lines in batches ordered by aspect ratio, 48 pixels high and padded to
 *     the widest line of the batch; lines with a mean character probability under 0.5 are dropped.
 *  6. [CtcAlternatives] gives every character its alternatives and [CharSpans] its extent in the
 *     crop, which is mapped back to the image through the crop's transform.
 *
 * Differences from the reference: small images are enlarged by at most a factor of two (see
 * [Ocr2Options.detectorMaxGrow]); and, without effect on any reading, character boxes of lines the
 * classifier turned are turned with them (the reference leaves them mirrored), boxes are not
 * truncated to whole pixels, whitespace gets a box, and lines are listed in the app's reading order
 * (columns of a page left to right, rows top to bottom, then vertical columns right to left).
 */
internal class Ocr2Pipeline(
    private val nets: Ocr2Nets,
    /** The recogniser's symbols in class order, without the blank (class 0) and the final space class. */
    labels: List<String>,
    private val options: Ocr2Options = Ocr2Options(),
) {
    /** Milliseconds spent per stage in the last [recognize] call (diagnostics). */
    val timings = LinkedHashMap<String, Double>()

    /** Texts by class index. */
    private val symbols: Array<String> = Array(labels.size + 2) { if (it == 0) "" else if (it <= labels.size) labels[it - 1] else " " }

    private inline fun <T> timed(stage: String, block: () -> T): T {
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        try {
            return block()
        } finally {
            timings[stage] = (timings[stage] ?: 0.0) + mark.elapsedNow().inWholeMicroseconds / 1000.0
        }
    }

    private class Crop(
        val box: TextBox,
        /** The crop as the recogniser sees it. */
        var image: BgrImage,
        /** Crop (before any turn) to detector-image coordinates. */
        val toImage: Homography,
        /** Width of the crop before any turn. */
        val cutWidth: Int,
        /** Turned a quarter counter-clockwise because it was tall. */
        val tall: Boolean,
        /** Turned by 180 degrees by the classifier. */
        var flipped: Boolean = false,
    )

    private class Reading(val symbols: List<Symbol>, val frames: Int, val aspect: Double, val batchAspect: Double)

    /** @param checkpoint called between stages and batches; throw from it to cancel. */
    fun recognize(image: OcrImage, checkpoint: () -> Unit = {}): OcrResult {
        timings.clear()
        var page = timed("prepare") { BgrImage.fromArgb(image) }
        val longest = max(page.width, page.height)
        if (longest > options.maxSide) {
            val shrink = options.maxSide.toDouble() / longest
            page = timed("prepare") { page.resize(max(1, (page.width * shrink).roundToInt()), max(1, (page.height * shrink).roundToInt())) }
        }
        val empty = OcrResult(image.width, image.height, emptyList())
        // Tiny images are enlarged; coordinates are scaled back by these ratios.
        var ratioX = 1.0
        var ratioY = 1.0
        if (min(page.width, page.height) < options.minSide) {
            val grow = options.minSide.toDouble() / min(page.width, page.height)
            val w = roundTo32((page.width * grow).toInt())
            val h = roundTo32((page.height * grow).toInt())
            if (w <= 0 || h <= 0) return empty
            ratioX = page.width.toDouble() / w
            ratioY = page.height.toDouble() / h
            page = timed("prepare") { page.resize(w, h) }
        }
        val originalWidth = page.width * ratioX
        val originalHeight = page.height * ratioY
        // A very flat image is padded so that the detector sees its text at a workable height.
        var padding = 0
        if (page.height <= FLAT_HEIGHT || page.width.toDouble() / page.height > FLAT_ASPECT) {
            padding = abs(max((page.width / FLAT_ASPECT).toInt(), FLAT_HEIGHT) * 2 - page.height) / 2
            page = timed("prepare") { page.padVertically(padding) }
        }
        checkpoint()

        val boxes = detect(page)
        if (boxes.isEmpty()) return empty
        checkpoint()
        val crops = timed("crop") { boxes.map { cut(page, it) } }
        checkpoint()
        if (options.classify) classify(crops, checkpoint)
        val readings = read(crops, checkpoint)

        val scaleX = ratioX * image.width / originalWidth
        val scaleY = ratioY * image.height / originalHeight
        val horizontal = ArrayList<OcrLine>()
        val vertical = ArrayList<OcrLine>()
        timed("layout") {
            for ((index, crop) in crops.withIndex()) {
                val reading = readings[index] ?: continue
                val texts = reading.symbols.map { symbols[it.label] }
                if (texts.all { it.isBlank() }) continue
                if (reading.symbols.sumOf { it.probability.toDouble() } / reading.symbols.size < options.textScore) continue
                val spans = CharSpans.compute(
                    texts,
                    IntArray(reading.symbols.size) { reading.symbols[it].frame },
                    reading.frames * reading.aspect / reading.batchAspect,
                    crop.image.width,
                )
                val point = DoubleArray(2)
                fun toPage(x: Double, y: Double): Point {
                    var cx = x
                    var cy = y
                    if (crop.flipped) { cx = crop.image.width - cx; cy = crop.image.height - cy }
                    if (crop.tall) { val t = cx; cx = crop.cutWidth - cy; cy = t }
                    crop.toImage.apply(cx, cy, point)
                    return Point((point[0] * scaleX).toFloat(), ((point[1] - padding) * scaleY).toFloat())
                }
                val height = crop.image.height.toDouble()
                val chars = reading.symbols.mapIndexed { i, symbol ->
                    val l = spans[i][0]
                    val r = spans[i][1]
                    // Upright glyphs of a column lie on their left side in the turned crop.
                    val quad = if (crop.tall) Quad(toPage(l, height), toPage(l, 0.0), toPage(r, 0.0), toPage(r, height))
                    else Quad(toPage(l, 0.0), toPage(r, 0.0), toPage(r, height), toPage(l, height))
                    // Whitespace is not a reading to offer for a glyph.
                    val candidates = symbol.labels.indices.filter { it == 0 || !symbols[symbol.labels[it]].isBlank() }.take(options.alternatives)
                    OcrChar(
                        text = texts[i],
                        box = quad,
                        confidence = symbol.scores[0],
                        alternatives = candidates.map { OcrAlt(symbols[symbol.labels[it]], symbol.scores[it]) },
                    )
                }
                val p = crop.box.points
                fun corner(i: Int): Point {
                    val x = (p[i * 2] * ratioX).coerceIn(0.0, originalWidth)
                    val y = ((p[i * 2 + 1] - padding) * ratioY).coerceIn(0.0, originalHeight)
                    return Point((x * image.width / originalWidth).toFloat(), (y * image.height / originalHeight).toFloat())
                }
                val quad = if (crop.flipped) Quad(corner(2), corner(3), corner(0), corner(1)) else Quad(corner(0), corner(1), corner(2), corner(3))
                (if (crop.tall) vertical else horizontal).add(OcrLine(texts.joinToString(""), quad, chars, vertical = crop.tall))
            }
        }
        // Ordered by the lines' own extents (length x thickness about the centre), so that the rows
        // of a tilted page do not overlap as their upright bounding boxes would. (A column's quad is in
        // image order, so this holds for both kinds.)
        fun bounds(line: OcrLine): Box {
            val q = line.box
            val c = q.center
            val along = hypot(q.topRight.x - q.topLeft.x, q.topRight.y - q.topLeft.y) / 2.0
            val across = hypot(q.bottomLeft.x - q.topLeft.x, q.bottomLeft.y - q.topLeft.y) / 2.0
            return Box(c.x - along, c.y - across, c.x + along, c.y + across)
        }
        return OcrResult(image.width, image.height, Geometry.readingOrder(horizontal, false, ::bounds) + Geometry.readingOrder(vertical, true, ::bounds))
    }

    private fun detect(page: BgrImage): List<TextBox> {
        val shorter = min(page.width, page.height)
        val grow = if (shorter < options.detectorMinSide) min(options.detectorMinSide.toDouble() / shorter, options.detectorMaxGrow) else 1.0
        val width = roundTo32((page.width * grow).toInt())
        val height = roundTo32((page.height * grow).toInt())
        if (width <= 0 || height <= 0) return emptyList()
        val input = timed("prepare") { page.resize(width, height) }
        val map = timed("detect") { nets.detect(height, width) { fill(it, input, 0, width, DETECTOR_TABLES) } }
        return timed("boxes") {
            DbPostProcess.boxes(
                map, width, height, page.width, page.height,
                options.pixelThreshold, options.boxThreshold, options.unclipRatio, options.maxCandidates,
            )
        }
    }

    private fun cut(page: BgrImage, box: TextBox): Crop {
        val p = box.points
        val width = max(DbPostProcess.distance(p, 0, 1), DbPostProcess.distance(p, 2, 3)).toInt().coerceAtLeast(1)
        val height = max(DbPostProcess.distance(p, 0, 3), DbPostProcess.distance(p, 1, 2)).toInt().coerceAtLeast(1)
        val toImage = Homography.toRectangle(p, width.toDouble(), height.toDouble()).inverse()
        val image = page.warp(toImage, width, height)
        val tall = height.toDouble() / width >= TALL
        return Crop(box, if (tall) image.rotate90() else image, toImage, width, tall)
    }

    private fun aspect(image: BgrImage): Double = image.width / image.height.toDouble()

    private fun classify(crops: List<Crop>, checkpoint: () -> Unit) {
        val order = crops.indices.sortedBy { aspect(crops[it].image) }
        for (start in order.indices step options.batch) {
            checkpoint()
            val batch = order.subList(start, min(order.size, start + options.batch))
            val scores = timed("classify") {
                nets.classify(batch.size) { sink ->
                    for ((n, index) in batch.withIndex()) {
                        val image = crops[index].image
                        val width = min(CLASSIFIER_WIDTH, ceil(LINE_HEIGHT * aspect(image)).toInt()).coerceAtLeast(1)
                        fill(sink, image.resize(width, LINE_HEIGHT), n * 3 * LINE_HEIGHT * CLASSIFIER_WIDTH, CLASSIFIER_WIDTH, LINE_TABLES)
                    }
                }
            }
            for ((n, index) in batch.withIndex()) {
                val turned = scores[n * 2 + 1]
                if (turned > scores[n * 2] && turned > options.classifierThreshold) {
                    val crop = crops[index]
                    crop.image = crop.image.rotate180()
                    crop.flipped = true
                }
            }
        }
    }

    private fun read(crops: List<Crop>, checkpoint: () -> Unit): Array<Reading?> {
        val out = arrayOfNulls<Reading>(crops.size)
        val order = crops.indices.sortedBy { aspect(crops[it].image) }
        for (start in order.indices step options.batch) {
            val batch = order.subList(start, min(order.size, start + options.batch))
            var batchAspect = RECOGNISER_WIDTH.toDouble() / LINE_HEIGHT
            for (index in batch) batchAspect = max(batchAspect, aspect(crops[index].image))
            val width = (LINE_HEIGHT * batchAspect).toInt()
            // Calls are split by output size only; every call is padded to the batch's width.
            val perLine = (width / 8 + 2).toLong() * symbols.size
            val chunk = (options.maxOutputElements / perLine).toInt().coerceIn(1, batch.size)
            for (from in batch.indices step chunk) {
                checkpoint()
                val part = batch.subList(from, min(batch.size, from + chunk))
                val output = timed("recognise") {
                    nets.recognize(part.size, width) { sink ->
                        for ((n, index) in part.withIndex()) {
                            val image = crops[index].image
                            val w = min(width, ceil(LINE_HEIGHT * aspect(image)).toInt()).coerceAtLeast(1)
                            fill(sink, image.resize(w, LINE_HEIGHT), n * 3 * LINE_HEIGHT * width, width, LINE_TABLES)
                        }
                    }
                }
                timed("decode") {
                    for ((n, index) in part.withIndex()) {
                        val decoded = CtcAlternatives.decode(output.data, n * output.frames * output.classes, output.frames, output.classes, options.alternatives + WHITESPACE_CLASSES)
                        if (decoded.isNotEmpty()) out[index] = Reading(decoded, output.frames, aspect(crops[index].image), batchAspect)
                    }
                }
            }
        }
        return out
    }

    /**
     * Writes [image] as three normalised planes (B, G, R) into a tensor whose rows are [rowWidth]
     * wide, starting at [offset]; columns beyond the image keep the tensor's zeros.
     */
    private fun fill(sink: FloatSink, image: BgrImage, offset: Int, rowWidth: Int, tables: Array<FloatArray>) {
        val row = FloatArray(image.width)
        val data = image.data
        for (channel in 0 until 3) {
            val table = tables[channel]
            val plane = offset + channel * image.height * rowWidth
            for (y in 0 until image.height) {
                var p = y * image.width * 3 + channel
                for (x in 0 until image.width) {
                    row[x] = table[data[p].toInt() and 0xFF]
                    p += 3
                }
                sink.put(plane + y * rowWidth, row, image.width)
            }
        }
    }

    private companion object {
        const val LINE_HEIGHT = 48
        const val RECOGNISER_WIDTH = 320
        const val CLASSIFIER_WIDTH = 192
        const val TALL = 1.5
        const val FLAT_HEIGHT = 30
        const val FLAT_ASPECT = 8.0

        /** Whitespace symbols of the recogniser (space, ideographic space): decoded on top of the alternatives wanted, then left out. */
        const val WHITESPACE_CLASSES = 2

        /** Nearest multiple of 32, halves to the even multiple. */
        fun roundTo32(v: Int): Int = (round(v / 32.0) * 32).toInt()

        /** The detector's input normalisation: ImageNet mean and deviation applied to B, G, R in that order. */
        val DETECTOR_TABLES: Array<FloatArray> = run {
            val mean = doubleArrayOf(0.485, 0.456, 0.406)
            val deviation = doubleArrayOf(0.229, 0.224, 0.225)
            Array(3) { c -> FloatArray(256) { ((it * (1 / 255.0) - mean[c]) / deviation[c]).toFloat() } }
        }

        /** The classifier's and the recogniser's input range, -1 to 1. */
        val LINE_TABLES: Array<FloatArray> = FloatArray(256) { (it / 255f - 0.5f) / 0.5f }.let { arrayOf(it, it, it) }
    }
}
