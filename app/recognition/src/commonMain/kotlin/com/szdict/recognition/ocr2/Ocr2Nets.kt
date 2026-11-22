package com.szdict.recognition.ocr2

/** A zero-initialised float tensor being filled by the pipeline, in the runtime's own memory. */
internal interface FloatSink {
    /** Copies `source[0 until count]` to elements `offset until offset + count`. */
    fun put(offset: Int, source: FloatArray, count: Int)
}

/** The recogniser's output for one batch: `batch x frames x classes` probabilities, row-major. */
internal class RecognitionOutput(val data: FloatArray, val frames: Int, val classes: Int)

/**
 * The three neural graphs of the pipeline: PP-OCRv4 mobile detector, PP-OCR mobile v2.0 orientation
 * classifier and PP-OCRv6 Small recogniser, all float32 NCHW with channels in B, G, R order.
 * Implemented with ONNX Runtime on Android (Java API) and iOS (C API); everything else is plain Kotlin.
 * Calls may suspend so a browser backend can await inference in a worker. Native backends run
 * their existing synchronous sessions inside these calls. Not thread-safe; the recogniser serialises calls.
 */
internal interface Ocr2Nets {
    /** `[1, 3, height, width]` (both multiples of 32) -> the `height x width` text probability map. */
    suspend fun detect(height: Int, width: Int, fill: (FloatSink) -> Unit): FloatArray

    /** `[batch, 3, 48, 192]` -> `batch x 2` probabilities of the line being upright / turned by 180 degrees. */
    suspend fun classify(batch: Int, fill: (FloatSink) -> Unit): FloatArray

    /** `[batch, 3, 48, width]` -> per-frame class probabilities; class 0 is the CTC blank. */
    suspend fun recognize(batch: Int, width: Int, fill: (FloatSink) -> Unit): RecognitionOutput

    fun close()
}

/**
 * Parameters of the pipeline. The defaults are the evaluated configuration (RapidOCR 3.10 with the
 * PP-OCRv4 detector profile, see `ocr-baseline/tools/benchmark_models.py`) with one addition,
 * [detectorMaxGrow]; change them only together with a new evaluation.
 */
internal class Ocr2Options(
    /** Number of candidates kept per character (including the top-1). */
    val alternatives: Int = 8,
    /** The image is reduced to this many pixels on its longer side before anything else. */
    val maxSide: Int = 2560,
    /** Images whose shorter side is below this are enlarged to it. */
    val minSide: Int = 30,
    /** The detector sees the image enlarged so that its shorter side is at least this long. */
    val detectorMinSide: Int = 736,
    /**
     * ... but never by more than this factor. The evaluated configuration has no such limit
     * (`Double.MAX_VALUE`): it enlarges a 208 x 53 crop of four characters to 2880 x 736, where the
     * detector no longer sees them as one line. With the limit the labelled set scores the same or
     * slightly better on every measure (line F 59.0 against 58.3 %, Han top-1 86.9 against 86.6 %).
     */
    val detectorMaxGrow: Double = 2.0,
    /**
     * The detector sees the image reduced so that its longer side is at most this long; lines are
     * still cut from the full image for the recogniser. No limit by default, as evaluated: at 1,280
     * a 2,560-pixel photograph is detected four times faster and the labelled set scores the same
     * (line F 59.5 %), but small text in street photographs is lost (12 lines become 2 on a road sign).
     */
    val detectorMaxSide: Int = Int.MAX_VALUE,
    /** Probability above which a pixel of the detector's map is text. */
    val pixelThreshold: Float = 0.3f,
    /** Mean probability inside a candidate box under which it is dropped. */
    val boxThreshold: Float = 0.6f,
    /** How far a text kernel is grown back to the full line: offset = area x ratio / perimeter. */
    val unclipRatio: Double = 1.5,
    val maxCandidates: Int = 1000,
    /** Whether lines are checked for being upside down. */
    val classify: Boolean = true,
    /** Probability of "turned by 180 degrees" above which a line is turned back. */
    val classifierThreshold: Float = 0.9f,
    /** Lines whose mean character probability is below this are dropped. */
    val textScore: Float = 0.5f,
    /** Lines per recogniser (and classifier) batch; a batch is padded to its widest line. */
    val batch: Int = 6,
    /**
     * Lines per recogniser call. Every line is padded to the width of its whole batch whatever this
     * is, so the results do not depend on it (to 1e-6 in the probabilities); one line per call is
     * 12 to 17 % faster than six on ONNX Runtime's CPU provider and keeps the output tensor
     * (`lines x frames x 18,710 classes`) small.
     */
    val linesPerCall: Int = 1,
)
