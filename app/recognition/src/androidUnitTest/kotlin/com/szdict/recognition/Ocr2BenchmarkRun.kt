package com.szdict.recognition

import com.szdict.recognition.ocr2.Ocr2Options
import java.io.File
import kotlin.test.Test

/**
 * Runs the pipeline over a directory of images and writes one result file per image in the format
 * `ocr-baseline/tools/score.py` reads, so the Kotlin pipeline is scored exactly like the candidates
 * of the model evaluation:
 *
 *   RECOGNITION_OCR2_IMAGES=<dir of name.png> RECOGNITION_OCR2_OUT=<dir> \
 *     ./gradlew :recognition:testDebugUnitTest --tests '*Ocr2BenchmarkRun*'
 *   python3 ocr-baseline/tools/score.py <ground truth dir> <dir>
 *
 * An image `x.jpg.png` is reported as file `x.jpg`. `<images>/scales.tsv` (name, scale) records by how
 * much an image was reduced from the labelled original. RECOGNITION_OCR2_OPTIONS takes
 * `classify=false`, `grow=<factor>`, `detmax=<pixels>`, `batch=<lines>`, `calls=<lines per recogniser call>` and
 * `threads=<n>` (comma separated) for experiments.
 * Skipped without the two directories.
 */
class Ocr2BenchmarkRun {
    @Test
    fun run() {
        val images = System.getenv("RECOGNITION_OCR2_IMAGES")?.let(::File) ?: return
        val out = System.getenv("RECOGNITION_OCR2_OUT")?.let(::File) ?: return
        val settings = System.getenv("RECOGNITION_OCR2_OPTIONS").orEmpty().split(',').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        out.mkdirs()
        val scales = File(images, "scales.tsv").takeIf { it.isFile }?.readLines().orEmpty().filter { it.isNotBlank() }.associate { it.substringBefore('\t') to it.substringAfter('\t') }
        val defaults = Ocr2Options()
        val options = Ocr2Options(
            classify = settings["classify"]?.toBoolean() ?: defaults.classify,
            linesPerCall = settings["calls"]?.toInt() ?: defaults.linesPerCall,
            detectorMaxGrow = settings["grow"]?.toDouble() ?: defaults.detectorMaxGrow,
            detectorMaxSide = settings["detmax"]?.toInt() ?: defaults.detectorMaxSide,
            batch = settings["batch"]?.toInt() ?: defaults.batch,
        )
        val nets = Ocr2Host.nets(settings["threads"]?.toInt() ?: 4)
        try {
            val pipeline = Ocr2Host.pipeline(nets, options)
            val files = images.listFiles { f -> f.extension == "png" }.orEmpty().sortedBy { it.name }
            check(files.isNotEmpty()) { "No images in $images" }
            pipeline.recognize(Ocr2Host.image(files.first()))
            val totals = LinkedHashMap<String, Double>()
            for (file in files) {
                val name = file.name.removeSuffix(".png")
                val image = Ocr2Host.image(file)
                val start = System.nanoTime()
                val result = pipeline.recognize(image)
                val ms = (System.nanoTime() - start) / 1e6
                pipeline.timings.forEach { (stage, v) -> totals[stage] = (totals[stage] ?: 0.0) + v }
                File(out, "$name.json").writeText(json(name, scales[name] ?: "1.0", result, ms, pipeline.timings) + "\n")
            }
            val report = totals.entries.joinToString("\n") { "${it.key}\t${"%.1f".format(it.value / files.size)}" }
            File(out, "stages.tsv").writeText("stage\tms/image\n$report\ntotal\t${"%.1f".format(totals.values.sum() / files.size)}\n")
            println("ocr2 ${files.size} images, ms/image by stage:\n$report")
        } finally {
            nets.close()
        }
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    private fun box(q: Quad): String = q.points.joinToString(",", "[", "]") { "${it.x},${it.y}" }

    private fun json(name: String, scale: String, result: OcrResult, ms: Double, stages: Map<String, Double>): String {
        val lines = result.lines.joinToString(",") { line ->
            val chars = line.chars.joinToString(",") { c ->
                val alts = c.alternatives.joinToString(",") { "[${quote(it.text)},${it.score}]" }
                "{\"text\":${quote(c.text)},\"box\":${box(c.box)},\"conf\":${c.confidence},\"alts\":[$alts]}"
            }
            "{\"text\":${quote(line.text)},\"box\":${box(line.box)},\"chars\":[$chars],\"vertical\":${line.vertical}}"
        }
        val timing = stages.entries.joinToString(",") { "${quote(it.key)}:${it.value}" }
        return "{\"file\":${quote(name)},\"width\":${result.width},\"height\":${result.height},\"scale\":$scale," +
            "\"ours\":{\"ms\":$ms,\"stages\":{$timing},\"lines\":[$lines]},\"candidate\":\"kotlin-ocr2\"}"
    }
}
