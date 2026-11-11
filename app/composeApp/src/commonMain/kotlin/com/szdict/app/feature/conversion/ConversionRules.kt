package com.szdict.app.feature.conversion

import com.szdict.core.api.DictMode
import com.szdict.core.api.Script

/**
 * The script options of the conversion dialog (TXT-083). The first four are valid sources
 * ("Text is:"); all five are targets ("Convert to:").
 */
enum class ScriptVariant(val label: String) {
    SIMPLIFIED("Simplified"),
    TRADITIONAL("Traditional (OpenCC standard)"),
    TRADITIONAL_HK("Traditional (Hong Kong)"),
    TRADITIONAL_TW("Traditional (Taiwan)"),

    /** Target only, and only from [SIMPLIFIED] (OpenCC `s2twp`): converts vocabulary too. */
    TRADITIONAL_TW_PHRASES("Traditional (Taiwan, with phrases)");

    val isSimplified: Boolean get() = this == SIMPLIFIED
    val isTraditional: Boolean get() = this != SIMPLIFIED

    companion object {
        val sources: List<ScriptVariant> = listOf(SIMPLIFIED, TRADITIONAL, TRADITIONAL_HK, TRADITIONAL_TW)
        val targets: List<ScriptVariant> = entries
    }
}

/** Result of on-device script detection (TXT-082). */
enum class DetectedKind { SIMPLIFIED, TRADITIONAL, MIXED, NEUTRAL }

/**
 * [variant] is the detected source option, or null when the text is Neutral, Mixed or has fewer
 * than three distinguishing characters ([confident] false) — then the user's script is assumed.
 */
data class DetectedScript(val kind: DetectedKind, val variant: ScriptVariant?, val confident: Boolean) {
    companion object {
        val Neutral = DetectedScript(DetectedKind.NEUTRAL, null, false)
    }
}

/** What a tap on the split button's main segment does; [label] is its English text and a11y label (GLB-080). */
enum class ConversionAction(val label: String) {
    CONVERT_TO_TRADITIONAL("Convert to Traditional"),
    CONVERT_TO_SIMPLIFIED("Convert to Simplified"),
    SHOW_ORIGINAL("Show original"),
}

/** Pure rules of TXT-082 / TXT-083 / ARCH-196. */
object ConversionRules {
    /**
     * OpenCC configurations to run in order for [source] → [target], or null when the pair is not
     * offered (same script, or Taiwan-with-phrases from a Traditional source).
     */
    fun configs(source: ScriptVariant, target: ScriptVariant): List<String>? = when (source) {
        ScriptVariant.SIMPLIFIED -> when (target) {
            ScriptVariant.SIMPLIFIED -> null
            ScriptVariant.TRADITIONAL -> listOf("s2t")
            ScriptVariant.TRADITIONAL_HK -> listOf("s2hk")
            ScriptVariant.TRADITIONAL_TW -> listOf("s2tw")
            ScriptVariant.TRADITIONAL_TW_PHRASES -> listOf("s2twp")
        }
        ScriptVariant.TRADITIONAL -> when (target) {
            ScriptVariant.SIMPLIFIED -> listOf("t2s")
            ScriptVariant.TRADITIONAL_HK -> listOf("t2hk")
            ScriptVariant.TRADITIONAL_TW -> listOf("t2tw")
            else -> null
        }
        ScriptVariant.TRADITIONAL_HK -> when (target) {
            ScriptVariant.SIMPLIFIED -> listOf("hk2s")
            ScriptVariant.TRADITIONAL -> listOf("hk2t")
            ScriptVariant.TRADITIONAL_TW -> listOf("hk2t", "t2tw")
            else -> null
        }
        ScriptVariant.TRADITIONAL_TW -> when (target) {
            ScriptVariant.SIMPLIFIED -> listOf("tw2s")
            ScriptVariant.TRADITIONAL -> listOf("tw2t")
            ScriptVariant.TRADITIONAL_HK -> listOf("tw2t", "t2hk")
            else -> null
        }
        // Not a source option.
        ScriptVariant.TRADITIONAL_TW_PHRASES -> null
    }

    /** A target is selectable unless it equals the source or is Taiwan-with-phrases from a Traditional source. */
    fun isTargetEnabled(source: ScriptVariant, target: ScriptVariant): Boolean = configs(source, target) != null

    /** Every OpenCC configuration the app can load. */
    val allConfigs: Set<String> = ScriptVariant.sources
        .flatMap { s -> ScriptVariant.targets.mapNotNull { t -> configs(s, t) }.flatten() }
        .toSet()

    /** The detected source, or the user's script when detection is Neutral, Mixed or low-confidence. */
    fun assumedSource(detected: DetectedScript?, userScript: Script): ScriptVariant =
        detected?.variant ?: if (userScript == Script.SIMPLIFIED) ScriptVariant.SIMPLIFIED else ScriptVariant.TRADITIONAL

    /**
     * The main action's default target: the other script; Traditional means Hong Kong in Cantonese
     * mode and OpenCC standard in Mandarin mode.
     */
    fun defaultTarget(source: ScriptVariant, mode: DictMode): ScriptVariant = when {
        source.isTraditional -> ScriptVariant.SIMPLIFIED
        mode == DictMode.CANTONESE -> ScriptVariant.TRADITIONAL_HK
        else -> ScriptVariant.TRADITIONAL
    }

    /** Keeps [target] when [source] allows it, else moves to the default target (TXT-083). */
    fun coerceTarget(source: ScriptVariant, target: ScriptVariant, mode: DictMode): ScriptVariant =
        if (isTargetEnabled(source, target)) target else defaultTarget(source, mode)
}

/** A chosen conversion. */
data class ConversionPair(val source: ScriptVariant, val target: ScriptVariant)

/**
 * The script-conversion view state of one passage (GLB-009, TXT-084): never persisted, never in a
 * URL. A surface holding several texts (OCR lines, voice utterances) keeps one selection for all.
 *
 * - [detected]: detection of the current original text;
 * - [confirmed]: source and target last confirmed in the dialog for this passage;
 * - [applied]: the conversion being shown, or null while the original is shown. It stays while the
 *   passage is edited and is dropped by a new passage ([ConversionSelection.None]).
 */
data class ConversionSelection(
    val detected: DetectedScript? = null,
    val confirmed: ConversionPair? = null,
    val applied: ConversionPair? = null,
) {
    val isConverted: Boolean get() = applied != null

    /** What the main action would convert with now (TXT-081): the confirmed pair, else detection. */
    fun proposal(userScript: Script, mode: DictMode): ConversionPair {
        confirmed?.let { return it }
        val source = ConversionRules.assumedSource(detected, userScript)
        return ConversionPair(source, ConversionRules.defaultTarget(source, mode))
    }

    fun mainAction(userScript: Script, mode: DictMode): ConversionAction = when {
        isConverted -> ConversionAction.SHOW_ORIGINAL
        proposal(userScript, mode).target.isSimplified -> ConversionAction.CONVERT_TO_SIMPLIFIED
        else -> ConversionAction.CONVERT_TO_TRADITIONAL
    }

    /** Main action tapped: convert with the proposal, or show the original. */
    fun toggled(userScript: Script, mode: DictMode): ConversionSelection =
        if (isConverted) copy(applied = null) else copy(applied = proposal(userScript, mode))

    /** `Convert` pressed in the dialog. Returns this unchanged when the pair is not offered. */
    fun confirmedWith(source: ScriptVariant, target: ScriptVariant): ConversionSelection {
        if (!ConversionRules.isTargetEnabled(source, target)) return this
        val pair = ConversionPair(source, target)
        return copy(confirmed = pair, applied = pair)
    }

    fun showingOriginal(): ConversionSelection = copy(applied = null)

    fun withDetection(detected: DetectedScript?): ConversionSelection = copy(detected = detected)

    /** The preselected options of the dialog (TXT-083). */
    fun dialogDefaults(userScript: Script, mode: DictMode): ConversionPair = applied ?: proposal(userScript, mode)

    companion object {
        /** A new passage: unconverted, fresh detection. */
        val None = ConversionSelection()
    }
}
