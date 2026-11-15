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
 * Not thread-safe; the recogniser serialises calls.
 */
internal interface Ocr2Nets {
    /** `[1, 3, height, width]` (both multiples of 32) -> the `height x width` text probability map. */
    fun detect(height: Int, width: Int, fill: (FloatSink) -> Unit): FloatArray

    /** `[batch, 3, 48, 192]` -> `batch x 2` probabilities of the line being upright / turned by 180 degrees. */
    fun classify(batch: Int, fill: (FloatSink) -> Unit): FloatArray

    /** `[batch, 3, 48, width]` -> per-frame class probabilities; class 0 is the CTC blank. */
    fun recognize(batch: Int, width: Int, fill: (FloatSink) -> Unit): RecognitionOutput

    fun close()
}

/**
 * Parameters of the pipeline. The defaults are the evaluated configuration (RapidOCR 3.10 with the
 * PP-OCRv4 detector profile, see `ocr-baseline/tools/benchmark_models.py`); change them only together
 * with a new evaluation.
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
     * Upper bound of `lines x frames x classes` per recogniser call. A batch above it is run in
     * several calls, each padded to the whole batch's width, which leaves the results unchanged.
     */
    val maxOutputElements: Int = 6_000_000,
)
