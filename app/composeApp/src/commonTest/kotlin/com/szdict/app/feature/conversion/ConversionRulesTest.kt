package com.szdict.app.feature.conversion

import com.szdict.core.api.DictMode
import com.szdict.core.api.Script
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversionRulesTest {
    private val s = ScriptVariant.SIMPLIFIED
    private val t = ScriptVariant.TRADITIONAL
    private val hk = ScriptVariant.TRADITIONAL_HK
    private val tw = ScriptVariant.TRADITIONAL_TW
    private val twp = ScriptVariant.TRADITIONAL_TW_PHRASES

    @Test
    fun configTableMatchesTheSpec() {
        // TXT-083 / ARCH-196, row by row.
        assertNull(ConversionRules.configs(s, s))
        assertEquals(listOf("s2t"), ConversionRules.configs(s, t))
        assertEquals(listOf("s2hk"), ConversionRules.configs(s, hk))
        assertEquals(listOf("s2tw"), ConversionRules.configs(s, tw))
        assertEquals(listOf("s2twp"), ConversionRules.configs(s, twp))

        assertEquals(listOf("t2s"), ConversionRules.configs(t, s))
        assertNull(ConversionRules.configs(t, t))
        assertEquals(listOf("t2hk"), ConversionRules.configs(t, hk))
        assertEquals(listOf("t2tw"), ConversionRules.configs(t, tw))
        assertNull(ConversionRules.configs(t, twp))

        assertEquals(listOf("hk2s"), ConversionRules.configs(hk, s))
        assertEquals(listOf("hk2t"), ConversionRules.configs(hk, t))
        assertNull(ConversionRules.configs(hk, hk))
        assertEquals(listOf("hk2t", "t2tw"), ConversionRules.configs(hk, tw))
        assertNull(ConversionRules.configs(hk, twp))

        assertEquals(listOf("tw2s"), ConversionRules.configs(tw, s))
        assertEquals(listOf("tw2t"), ConversionRules.configs(tw, t))
        assertEquals(listOf("tw2t", "t2hk"), ConversionRules.configs(tw, hk))
        assertNull(ConversionRules.configs(tw, tw))
        assertNull(ConversionRules.configs(tw, twp))
    }

    @Test
    fun onlyReleasedConfigsAreUsed() {
        assertEquals(
            setOf("s2t", "s2hk", "s2tw", "s2twp", "t2s", "t2hk", "t2tw", "hk2s", "hk2t", "tw2s", "tw2t"),
            ConversionRules.allConfigs,
        )
    }

    @Test
    fun taiwanWithPhrasesOnlyFromSimplified() {
        assertTrue(ConversionRules.isTargetEnabled(s, twp))
        for (source in listOf(t, hk, tw)) assertFalse(ConversionRules.isTargetEnabled(source, twp), "$source")
        assertFalse(twp in ScriptVariant.sources)
        assertEquals(5, ScriptVariant.targets.size)
    }

    @Test
    fun targetEqualToSourceIsDisabled() {
        for (source in ScriptVariant.sources) assertFalse(ConversionRules.isTargetEnabled(source, source))
    }

    @Test
    fun sourceChangeMovesADisabledTargetToTheDefault() {
        // Simplified → Taiwan with phrases selected, then the source becomes Traditional (HK).
        assertEquals(s, ConversionRules.coerceTarget(hk, twp, DictMode.MANDARIN))
        // Target equal to the new source.
        assertEquals(t, ConversionRules.coerceTarget(s, s, DictMode.MANDARIN))
        assertEquals(hk, ConversionRules.coerceTarget(s, s, DictMode.CANTONESE))
        // A still-valid target is kept.
        assertEquals(tw, ConversionRules.coerceTarget(hk, tw, DictMode.MANDARIN))
    }

    @Test
    fun mainActionFollowsDetection() {
        val simplified = ConversionSelection(DetectedScript(DetectedKind.SIMPLIFIED, s, true))
        assertEquals(ConversionAction.CONVERT_TO_TRADITIONAL, simplified.mainAction(Script.TRADITIONAL, DictMode.MANDARIN))
        assertEquals(ConversionPair(s, t), simplified.proposal(Script.TRADITIONAL, DictMode.MANDARIN))
        assertEquals(ConversionPair(s, hk), simplified.proposal(Script.TRADITIONAL, DictMode.CANTONESE))

        for (variant in listOf(t, hk, tw)) {
            val traditional = ConversionSelection(DetectedScript(DetectedKind.TRADITIONAL, variant, true))
            assertEquals(ConversionAction.CONVERT_TO_SIMPLIFIED, traditional.mainAction(Script.SIMPLIFIED, DictMode.MANDARIN))
            assertEquals(ConversionPair(variant, s), traditional.proposal(Script.SIMPLIFIED, DictMode.CANTONESE))
        }
    }

    @Test
    fun neutralMixedAndLowConfidenceAssumeTheUsersScript() {
        val undetermined = listOf(
            null,
            DetectedScript.Neutral,
            DetectedScript(DetectedKind.MIXED, null, true),
            DetectedScript(DetectedKind.SIMPLIFIED, null, false),   // fewer than 3 distinguishing characters
        )
        for (d in undetermined) {
            val selection = ConversionSelection(d)
            assertEquals(ConversionAction.CONVERT_TO_TRADITIONAL, selection.mainAction(Script.SIMPLIFIED, DictMode.MANDARIN), "$d")
            assertEquals(ConversionAction.CONVERT_TO_SIMPLIFIED, selection.mainAction(Script.TRADITIONAL, DictMode.CANTONESE), "$d")
            assertEquals(ConversionPair(t, s), selection.dialogDefaults(Script.TRADITIONAL, DictMode.MANDARIN))
        }
    }

    @Test
    fun showOriginalThenOffersTheConfirmedPairAgain() {
        val detected = DetectedScript(DetectedKind.SIMPLIFIED, s, true)
        var selection = ConversionSelection(detected).confirmedWith(s, twp)
        assertEquals(ConversionAction.SHOW_ORIGINAL, selection.mainAction(Script.SIMPLIFIED, DictMode.MANDARIN))
        assertEquals(ConversionPair(s, twp), selection.applied)

        selection = selection.toggled(Script.SIMPLIFIED, DictMode.MANDARIN)
        assertFalse(selection.isConverted)
        assertEquals(ConversionAction.CONVERT_TO_TRADITIONAL, selection.mainAction(Script.SIMPLIFIED, DictMode.MANDARIN))

        selection = selection.toggled(Script.SIMPLIFIED, DictMode.MANDARIN)
        assertEquals(ConversionPair(s, twp), selection.applied)   // not the detection default s2t
    }

    @Test
    fun confirmingADisabledPairChangesNothing() {
        val selection = ConversionSelection()
        assertEquals(selection, selection.confirmedWith(hk, twp))
        assertEquals(selection, selection.confirmedWith(s, s))
    }

    @Test
    fun composedMapCoversBothTextsAcrossStages() {
        // Stage 1: "abc" → "aXYc" (b → XY); stage 2: "aXYc" → "aZc" (XY → Z).
        val first = Converted("aXYc", listOf(SegmentMapping(0, 1, 0, 1), SegmentMapping(1, 2, 1, 3), SegmentMapping(2, 3, 3, 4)))
        val second = Converted("aZc", listOf(SegmentMapping(0, 1, 0, 1), SegmentMapping(1, 3, 1, 2), SegmentMapping(3, 4, 2, 3)))
        val composed = OpenCcScriptConverter.compose(first, second)
        assertEquals("aZc", composed.text)
        assertEquals(listOf(SegmentMapping(0, 1, 0, 1), SegmentMapping(1, 2, 1, 2), SegmentMapping(2, 3, 2, 3)), composed.segmentMap)

        // A first-stage phrase split by second-stage entries is merged into one entry.
        val phrase = Converted("PQ", listOf(SegmentMapping(0, 3, 0, 2)))
        val perChar = Converted("pq", listOf(SegmentMapping(0, 1, 0, 1), SegmentMapping(1, 2, 1, 2)))
        assertEquals(listOf(SegmentMapping(0, 3, 0, 2)), OpenCcScriptConverter.compose(phrase, perChar).segmentMap)
        assertEquals(0..1, OpenCcScriptConverter.compose(phrase, perChar).targetRangeOf(2))
        assertEquals(0..2, OpenCcScriptConverter.compose(phrase, perChar).sourceRangeOf(1))
    }

    @Test
    fun unpairedSurrogatesAreReplacedInPlace() {
        assertEquals("a�b", OpenCcScriptConverter.wellFormed("a\uD840b"))
        assertEquals("a�", OpenCcScriptConverter.wellFormed("a\uDC00"))
        val ok = "神𠀀"
        assertTrue(OpenCcScriptConverter.wellFormed(ok) === ok)
    }
}
