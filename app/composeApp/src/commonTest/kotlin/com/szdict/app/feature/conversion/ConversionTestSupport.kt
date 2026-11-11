package com.szdict.app.feature.conversion

import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.Dictionary
import com.szdict.core.api.FakeDictionary
import com.szdict.core.api.SegmentationMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * Runs everything pending, including work in `backgroundScope` (which `advanceUntilIdle` skips)
 * and short timeouts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.settle() {
    advanceTimeBy(5_000)
    runCurrent()
}

/** Converts 汉/漢, 语/語 and 软件/軟體 (phrases) and detects by those characters. */
class FakeScriptConverter : ScriptConverter {
    var fail = false
    val conversions = ArrayList<Triple<String, ScriptVariant, ScriptVariant>>()
    val detections = ArrayList<String>()

    override suspend fun detect(text: String): DetectedScript {
        detections += text
        val simplified = text.count { it in "汉语软" }
        val traditional = text.count { it in "漢語軟體" }
        return when {
            simplified + traditional == 0 -> DetectedScript.Neutral
            simplified > 0 && traditional > 0 -> DetectedScript(DetectedKind.MIXED, null, true)
            simplified > 0 -> DetectedScript(DetectedKind.SIMPLIFIED, ScriptVariant.SIMPLIFIED, true)
            else -> DetectedScript(DetectedKind.TRADITIONAL, ScriptVariant.TRADITIONAL, true)
        }
    }

    override suspend fun convert(text: String, source: ScriptVariant, target: ScriptVariant): Converted {
        conversions += Triple(text, source, target)
        if (fail) throw IllegalStateException("dictionaries unavailable")
        requireNotNull(ConversionRules.configs(source, target))
        val out = if (target.isSimplified) {
            text.replace("軟體", "软件").replace('漢', '汉').replace('語', '语')
        } else {
            val phrases = if (target == ScriptVariant.TRADITIONAL_TW_PHRASES) text.replace("软件", "軟體") else text
            phrases.replace('汉', '漢').replace('语', '語')
        }
        return Converted(out, listOf(SegmentMapping(0, text.length, 0, out.length)))
    }
}

class CountingDictionary(private val inner: Dictionary = FakeDictionary()) : Dictionary by inner {
    val analysed = ArrayList<String>()
    var fail = false

    override suspend fun analyse(text: String, segmentation: SegmentationMode): AnalysedPassage {
        analysed += text
        if (fail) throw IllegalStateException("analysis failed")
        return inner.analyse(text, segmentation)
    }
}
