package com.szdict.app.feature.conversion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Real conversions and detection with the staged OpenCC dictionaries (host JVM classpath). */
class OpenCcScriptConverterTest {
    private val converter = OpenCcScriptConverter(::readOpenCcClasspathResource)
    private val s = ScriptVariant.SIMPLIFIED
    private val t = ScriptVariant.TRADITIONAL
    private val hk = ScriptVariant.TRADITIONAL_HK
    private val tw = ScriptVariant.TRADITIONAL_TW
    private val twp = ScriptVariant.TRADITIONAL_TW_PHRASES

    @Test
    fun simplifiedToTraditional() = runTest {
        assertEquals("漢字", converter.convert("汉字", s, t).text)
        assertEquals("汉字", converter.convert("漢字", t, s).text)
    }

    @Test
    fun taiwanWithPhrasesConvertsVocabulary() = runTest {
        assertEquals("軟體", converter.convert("软件", s, twp).text)
        assertEquals("軟件", converter.convert("软件", s, tw).text)   // characters only
    }

    @Test
    fun everyOfferedPairLoadsAndConverts() = runTest {
        for (source in ScriptVariant.sources) for (target in ScriptVariant.targets) {
            if (!ConversionRules.isTargetEnabled(source, target)) continue
            val text = if (source == s) "这里的鼠标和软件很好用，头发也干了。" else "這裏的滑鼠和軟體很好用，頭髮也乾了。"
            val result = converter.convert(text, source, target)
            assertTrue(result.text.isNotEmpty(), "$source → $target")
            // The segment map is ordered and covers both texts completely.
            var sourcePos = 0
            var targetPos = 0
            for (m in result.segmentMap) {
                assertEquals(sourcePos, m.sourceStart, "$source → $target")
                assertEquals(targetPos, m.targetStart, "$source → $target")
                sourcePos = m.sourceEnd
                targetPos = m.targetEnd
            }
            assertEquals(text.length, sourcePos, "$source → $target")
            assertEquals(result.text.length, targetPos, "$source → $target")
        }
    }

    @Test
    fun regionalVariants() = runTest {
        assertEquals("這裏", converter.convert("这里", s, hk).text)
        assertEquals("這裡", converter.convert("这里", s, tw).text)
        // Hong Kong → Taiwan chains hk2t and t2tw, never through Simplified.
        assertEquals("這裡", converter.convert("這裏", hk, tw).text)
        assertEquals("這裏", converter.convert("這裡", tw, hk).text)
    }

    @Test
    fun segmentMapFollowsPhraseConversion() = runTest {
        val result = converter.convert("好软件。\n汉字", s, twp)
        assertEquals("好軟體。\n漢字", result.text)
        // The tapped 體 maps back into the phrase 软件, 字 on the second line to itself.
        val phrase = result.sourceRangeOf(result.text.indexOf('體'))!!
        assertTrue(phrase.first <= 2 && phrase.last >= 2)
        assertEquals(6..6, result.sourceRangeOf(6))
        assertEquals(6..6, result.targetRangeOf(6))
    }

    @Test
    fun detectsSimplifiedAndTraditionalSentences() = runTest {
        val simplified = converter.detect("这个软件的功能还没开发完，请过几天再来试试。")
        assertEquals(DetectedKind.SIMPLIFIED, simplified.kind)
        assertEquals(s, simplified.variant)
        assertTrue(simplified.confident)

        val traditional = converter.detect("這個軟體的功能還沒開發完，請過幾天再來試試。")
        assertEquals(DetectedKind.TRADITIONAL, traditional.kind)
        assertTrue(traditional.variant!!.isTraditional)
        assertTrue(traditional.confident)
    }

    @Test
    fun neutralMixedAndLowConfidenceHaveNoVariant() = runTest {
        assertEquals(DetectedKind.NEUTRAL, converter.detect("你好，我是人。Hello 123").kind)
        assertEquals(null, converter.detect("你好").variant)
        // Fewer than three distinguishing characters.
        val low = converter.detect("你们好")
        assertFalse(low.confident)
        assertEquals(null, low.variant)
        val mixed = converter.detect("这个软件开发还没完，這個軟體開發還沒完。")
        assertEquals(DetectedKind.MIXED, mixed.kind)
        assertEquals(null, mixed.variant)
    }

    @Test
    fun malformedTextAndDisabledPairs() = runTest {
        assertEquals("漢�字", converter.convert("汉\uD840字", s, t).text)
        assertEquals(DetectedKind.NEUTRAL, converter.detect("\uDC00").kind)
        assertFailsWith<IllegalArgumentException> { converter.convert("漢字", hk, twp) }
        assertEquals("", converter.convert("", s, t).text)
    }
}
