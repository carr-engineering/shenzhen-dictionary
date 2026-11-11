package com.szdict.core.api

enum class ThemeMode(val stored: String) { SYSTEM("system"), LIGHT("light"), DARK("dark") }

enum class UiLanguageSetting(val stored: String) {
    SYSTEM("system"), EN("en"), ZH_HANS("zh-Hans"), ZH_HANT("zh-Hant");

    /** Resolves to a concrete UI language; `SYSTEM` maps the device locale tag (GLB-101). */
    fun resolve(systemLocaleTag: String): UiLang = when (this) {
        EN -> UiLang.EN
        ZH_HANS -> UiLang.ZH_HANS
        ZH_HANT -> UiLang.ZH_HANT
        SYSTEM -> {
            val parts = systemLocaleTag.replace('_', '-').split('-').map { it.lowercase() }
            when {
                parts.firstOrNull() !in setOf("zh", "yue", "cmn") -> UiLang.EN
                "hant" in parts -> UiLang.ZH_HANT
                "hans" in parts -> UiLang.ZH_HANS
                parts.any { it == "tw" || it == "hk" || it == "mo" } || parts.first() == "yue" -> UiLang.ZH_HANT
                else -> UiLang.ZH_HANS
            }
        }
    }
}

enum class InputMethod(val stored: String) { KEYBOARD("keyboard"), HANDWRITING("handwriting") }

/** Persisted settings (SET-010…SET-019) with their defaults. Immutable; copy to change. */
data class Settings(
    val dictionaryMode: DictMode = DictMode.MANDARIN,
    /** Script is stored per dictionary mode (SET-011). */
    val scriptMandarin: Script = Script.SIMPLIFIED,
    val scriptCantonese: Script = Script.TRADITIONAL,
    val uiLanguage: UiLanguageSetting = UiLanguageSetting.SYSTEM,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val toneColors: Boolean = true,
    val showPronunciation: Boolean = true,
    val showBoundaries: Boolean = true,
    val inputMethod: InputMethod = InputMethod.KEYBOARD,
    /** Remembered side; written only per SRCH-026. */
    val searchSide: SearchSide = SearchSide.ENGLISH,
    /** Last query is stored per dictionary mode (SET-013). */
    val lastQueryMandarin: String = "",
    val lastQueryCantonese: String = "",
    val cameraAutoSave: Boolean = false,
) {
    val script: Script get() = script(dictionaryMode)
    val lastSearchQuery: String get() = lastQuery(dictionaryMode)

    fun script(mode: DictMode): Script = if (mode == DictMode.MANDARIN) scriptMandarin else scriptCantonese
    fun lastQuery(mode: DictMode): String = if (mode == DictMode.MANDARIN) lastQueryMandarin else lastQueryCantonese

    fun withScript(mode: DictMode, script: Script): Settings =
        if (mode == DictMode.MANDARIN) copy(scriptMandarin = script) else copy(scriptCantonese = script)

    fun withLastQuery(mode: DictMode, query: String): Settings =
        if (mode == DictMode.MANDARIN) copy(lastQueryMandarin = query) else copy(lastQueryCantonese = query)
}

/** Storage keys (one string value per key). */
object SettingsKeys {
    const val DICTIONARY_MODE = "dictionary_mode"
    const val UI_LANGUAGE = "ui_language"
    const val THEME_MODE = "theme_mode"
    const val TONE_COLORS = "tone_colors"
    const val SHOW_PRONUNCIATION = "show_pronunciation"
    const val SHOW_BOUNDARIES = "show_boundaries"
    const val INPUT_METHOD = "input_method"
    const val SEARCH_SIDE = "search_side"
    const val CAMERA_AUTO_SAVE = "camera_auto_save"
    fun script(mode: DictMode) = "script.${mode.datasetName}"
    fun lastSearchQuery(mode: DictMode) = "last_search_query.${mode.datasetName}"

    val all: List<String> = listOf(
        DICTIONARY_MODE, script(DictMode.MANDARIN), script(DictMode.CANTONESE), UI_LANGUAGE, THEME_MODE,
        TONE_COLORS, SHOW_PRONUNCIATION, SHOW_BOUNDARIES, INPUT_METHOD, SEARCH_SIDE,
        lastSearchQuery(DictMode.MANDARIN), lastSearchQuery(DictMode.CANTONESE), CAMERA_AUTO_SAVE,
    )
}

/**
 * Per-key (de)serialisation. Decoding is tolerant (SET-012): an unknown, missing or corrupt value
 * yields that key's default and never affects the other keys.
 */
object SettingsCodec {
    fun decode(stored: Map<String, String?>): Settings {
        val d = Settings()
        return Settings(
            dictionaryMode = mode(stored[SettingsKeys.DICTIONARY_MODE]) ?: d.dictionaryMode,
            scriptMandarin = script(stored[SettingsKeys.script(DictMode.MANDARIN)]) ?: d.scriptMandarin,
            scriptCantonese = script(stored[SettingsKeys.script(DictMode.CANTONESE)]) ?: d.scriptCantonese,
            uiLanguage = UiLanguageSetting.entries.firstOrNull { it.stored.equals(stored[SettingsKeys.UI_LANGUAGE]?.trim(), true) } ?: d.uiLanguage,
            themeMode = ThemeMode.entries.firstOrNull { it.stored.equals(stored[SettingsKeys.THEME_MODE]?.trim(), true) } ?: d.themeMode,
            toneColors = bool(stored[SettingsKeys.TONE_COLORS]) ?: d.toneColors,
            showPronunciation = bool(stored[SettingsKeys.SHOW_PRONUNCIATION]) ?: d.showPronunciation,
            showBoundaries = bool(stored[SettingsKeys.SHOW_BOUNDARIES]) ?: d.showBoundaries,
            inputMethod = InputMethod.entries.firstOrNull { it.stored.equals(stored[SettingsKeys.INPUT_METHOD]?.trim(), true) } ?: d.inputMethod,
            searchSide = side(stored[SettingsKeys.SEARCH_SIDE]) ?: d.searchSide,
            lastQueryMandarin = query(stored[SettingsKeys.lastSearchQuery(DictMode.MANDARIN)]),
            lastQueryCantonese = query(stored[SettingsKeys.lastSearchQuery(DictMode.CANTONESE)]),
            cameraAutoSave = bool(stored[SettingsKeys.CAMERA_AUTO_SAVE]) ?: d.cameraAutoSave,
        )
    }

    fun encode(s: Settings): Map<String, String> = linkedMapOf(
        SettingsKeys.DICTIONARY_MODE to s.dictionaryMode.datasetName,
        SettingsKeys.script(DictMode.MANDARIN) to encodeScript(s.scriptMandarin),
        SettingsKeys.script(DictMode.CANTONESE) to encodeScript(s.scriptCantonese),
        SettingsKeys.UI_LANGUAGE to s.uiLanguage.stored,
        SettingsKeys.THEME_MODE to s.themeMode.stored,
        SettingsKeys.TONE_COLORS to s.toneColors.toString(),
        SettingsKeys.SHOW_PRONUNCIATION to s.showPronunciation.toString(),
        SettingsKeys.SHOW_BOUNDARIES to s.showBoundaries.toString(),
        SettingsKeys.INPUT_METHOD to s.inputMethod.stored,
        SettingsKeys.SEARCH_SIDE to encodeSide(s.searchSide),
        SettingsKeys.lastSearchQuery(DictMode.MANDARIN) to s.lastQueryMandarin,
        SettingsKeys.lastSearchQuery(DictMode.CANTONESE) to s.lastQueryCantonese,
        SettingsKeys.CAMERA_AUTO_SAVE to s.cameraAutoSave.toString(),
    )

    /** Only the entries whose value differs between [old] and [new] — what a store needs to write. */
    fun diff(old: Settings, new: Settings): Map<String, String> {
        val a = encode(old)
        return encode(new).filter { (k, v) -> a[k] != v }
    }

    fun encodeScript(s: Script) = if (s == Script.SIMPLIFIED) "simplified" else "traditional"
    fun encodeSide(s: SearchSide) = if (s == SearchSide.CHINESE) "chinese" else "english"

    fun mode(v: String?): DictMode? = DictMode.entries.firstOrNull { it.datasetName.equals(v?.trim(), true) || it.routeSegment.equals(v?.trim(), true) }
    fun script(v: String?): Script? = when (v?.trim()?.lowercase()) {
        "simplified" -> Script.SIMPLIFIED
        "traditional" -> Script.TRADITIONAL
        else -> null
    }
    fun side(v: String?): SearchSide? = when (v?.trim()?.lowercase()) {
        "chinese", "zh" -> SearchSide.CHINESE
        "english", "en" -> SearchSide.ENGLISH
        else -> null
    }
    fun bool(v: String?): Boolean? = when (v?.trim()?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    /** A restored query is line-break-normalised before use (SRCH-141). */
    fun query(v: String?): String = normaliseLineBreaks(v ?: "")

    /** SRCH-011: every line break becomes a single space. */
    fun normaliseLineBreaks(text: String): String {
        if (text.none { it == '\n' || it == '\r' || it == '\u000B' || it == '\u000C' || it == '\u0085' || it == ' ' || it == ' ' }) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when (c) {
                '\r' -> { sb.append(' '); if (i + 1 < text.length && text[i + 1] == '\n') i++ }
                '\n', '\u000B', '\u000C', '\u0085', ' ', ' ' -> sb.append(' ')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
