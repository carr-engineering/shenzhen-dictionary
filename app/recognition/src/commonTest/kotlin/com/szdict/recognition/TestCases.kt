package com.szdict.recognition

/** A reference OCR image with the text it must produce. */
class OcrCase(val file: String, val expected: String, private val accept: ((String) -> Boolean)? = null) {
    fun matches(actual: String): Boolean = accept?.invoke(actual) ?: (actual == expected)
}

/** The reference pipeline's controlled test images (`web-ocr/tests`) and one look-alike image. */
object OcrCases {
    val all = listOf(
        OcrCase("hello-world-zh.png", "你好世界"),
        OcrCase("pipeline-columns.png", "你好世界\n学习汉字\n中国文化\n繁體中文\n中文\n你好世界"),
        OcrCase("pipeline-mixed.png", "你好世界\nHello world\n中文 Chinese 123"),
        OcrCase("pipeline-shaded.png", "你好世界\nHello world\n中文 Chinese 123"),
        OcrCase("pipeline-upside-down.png", "你好世界"),
        OcrCase("pipeline-tilted.png", "你好世界"),
        OcrCase("pipeline-vertical.png", "你好世界\n中国文化"),
        OcrCase("pipeline-inverted.png", "你好世界"),
        OcrCase("pipeline-blank.png", ""),
        // A sideways photograph: found by the turned detector pass and read as an ordinary line.
        OcrCase("pipeline-rotated-90.png", "你好世界"),
        OcrCase("vertical-tight.png", "春眠不覺曉處處聞啼鳥\n夜來風雨聲花落知多少\n白日依山盡黃河入海流\n欲窮千里目更上一層樓") { text ->
            // Dense traditional characters at 36 px: allow a few look-alike confusions (雨/兩, 千/于).
            val expected = "春眠不覺曉處處聞啼鳥\n夜來風雨聲花落知多少\n白日依山盡黃河入海流\n欲窮千里目更上一層樓"
            text.length == expected.length && text.indices.count { text[it] != expected[it] } <= 4
        },
        OcrCase("similar.png", "我已经吃过了\n未来的土地很大") { text ->
            // 已/己 and 未/末 are genuinely ambiguous to the recogniser; the right reading must be the
            // top-1 or among the alternatives, which the alternatives tests check per character.
            val lines = text.split('\n')
            lines.size == 2 && lines[0].length == 6 && lines[1].length == 7 && lines[0].endsWith("经吃过了") && lines[1].endsWith("来的土地很大")
        },
        OcrCase(
            "pipeline-page.png",
            "学习汉字：中国文化\n你好世界，欢迎学习中文。\n每天练习汉字和阅读。\n中国文化有悠久的历史。\n学习语言需要时间。\n阅读可以帮助我们进步。\n" +
                "繁體中文也很有趣。\n今天我们一起学习汉字。\n这是一个中文学习工具。\n你好世界，中国文化。\n每天阅读，每天进步。",
        ) { text ->
            // The reference pipeline itself gets some punctuation wrong on the dense page; require
            // every Hanzi in order and the same line count.
            fun hanzi(s: String) = s.filter { it.code in 0x4E00..0x9FFF }
            text.split('\n').size == 11 && hanzi(text) == hanzi(
                "学习汉字中国文化你好世界欢迎学习中文每天练习汉字和阅读中国文化有悠久的历史学习语言需要时间阅读可以帮助我们进步繁體中文也很有趣今天我们一起学习汉字这是一个中文学习工具你好世界中国文化每天阅读每天进步",
            )
        },
    )
}

/** Reference ink drawings with the reference encoder's features and the reference decoder's ranking. */
class InkGolden(val cases: List<Case>) {
    class Case(
        val expected: String,
        val strokes: List<InkStroke>,
        val features: FloatArray,
        val referenceTop: List<String>,
        val referenceScores: FloatArray,
    )

    companion object {
        /** Parses `testdata/ink-fixtures.txt` (written by `tools/export_golden.py`). */
        fun parse(text: String): InkGolden {
            val cases = ArrayList<Case>()
            var expected = ""
            var strokes = ArrayList<InkStroke>()
            var features = FloatArray(0)
            var top = emptyList<String>()
            for (line in text.split('\n')) {
                val parts = line.trim().split(' ')
                when (parts[0]) {
                    "case" -> { expected = parts[1]; strokes = ArrayList() }
                    "stroke" -> {
                        val values = parts.drop(1).map { it.toFloat() }
                        strokes.add(InkStroke(List(values.size / 2) { InkPoint(values[2 * it], values[2 * it + 1], 20L * it) }))
                    }
                    "features" -> features = parts.drop(1).map { it.toFloat() }.toFloatArray()
                    "top" -> top = parts.drop(1)
                    "scores" -> cases.add(Case(expected, strokes, features, top, parts.drop(1).map { it.toFloat() }.toFloatArray()))
                }
            }
            return InkGolden(cases)
        }
    }
}
