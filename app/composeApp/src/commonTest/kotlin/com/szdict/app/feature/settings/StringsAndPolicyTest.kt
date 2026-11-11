package com.szdict.app.feature.settings

import com.szdict.app.i18n.AppStrings
import com.szdict.app.i18n.SimplifiedChineseStrings
import com.szdict.app.i18n.TraditionalChineseStrings
import com.szdict.core.api.SourceInfo
import com.szdict.core.api.UiLang
import com.szdict.core.api.UiLanguageSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StringsAndPolicyTest {
    private val placeholder = Regex("\\{(\\d+)\\}")
    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.toSortedSet()

    @Test
    fun everySimplifiedKeyHasATraditionalEntry() {
        val missing = SimplifiedChineseStrings.keys - TraditionalChineseStrings.keys
        assertTrue(missing.isEmpty(), "zh-Hant entries missing for: $missing")
        val blank = TraditionalChineseStrings.filterValues { it.isBlank() }.keys
        assertTrue(blank.isEmpty(), "blank zh-Hant entries: $blank")
    }

    @Test
    fun translationsKeepTheirPlaceholders() {
        for ((table, name) in listOf(SimplifiedChineseStrings to "zh-Hans", TraditionalChineseStrings to "zh-Hant")) {
            val wrong = table.filter { (key, value) -> placeholders(key) != placeholders(value) }.keys
            assertTrue(wrong.isEmpty(), "$name placeholders differ for: $wrong")
        }
    }

    @Test
    fun traditionalTableUsesTraditionalVocabulary() {
        val hant = AppStrings(UiLang.ZH_HANT)
        assertEquals("設定", hant.text("Settings"))
        assertEquals("搜尋", hant.text("Search"))
        assertEquals("貼上", hant.text("Paste"))
        assertEquals("隱私權政策", hant.text("Privacy policy"))
        assertEquals("版本 1.2", hant.text("Version {0}", "1.2"))
        assertEquals("设置", AppStrings(UiLang.ZH_HANS).text("Settings"))
        // GLB-101: a string with no translation falls back to English, never to an empty label.
        assertEquals("Some untranslated label", hant.text("Some untranslated label"))
    }

    @Test
    fun settingsStringsAreTranslatedInBothChineseTables() {
        val keys = listOf(
            "Settings", "Dictionary", "Default script", "App language", "System", "Theme", "Light", "Dark", "Auto", "Tone coloring",
            "Show pronunciation", "Reading boundaries", "Save photos to gallery", "Privacy policy", "About & Attribution", "Version {0}",
            "Dataset {0}", "Open-source library licenses", "Data sources", "Models and engines", "Words", "Characters", "No HSK data",
            "Unable to load HSK words", "Choose HSK levels", "Word unavailable", AttributionIntro,
        ) + DataSourceCredits.map { it.role } + ModelCredits.map { it.role } + LibraryCredits.map { it.role }
        for (key in keys) {
            assertTrue(key in SimplifiedChineseStrings, "zh-Hans missing: $key")
            assertTrue(key in TraditionalChineseStrings, "zh-Hant missing: $key")
        }
    }

    @Test
    fun languageOptionsAreNamedInTheirOwnLanguage() {
        assertEquals(listOf("跟随系统", "English", "简体中文", "繁體中文"), UiLanguageSetting.entries.map { uiLanguageLabel(it, "跟随系统") })
    }

    @Test
    fun privacyPolicyIsBundledInAllThreeLanguages() {
        val policies = PrivacyPolicies
        assertEquals(UiLang.entries.toList(), policies.map { it.lang })
        val english = privacyPolicy(UiLang.EN)
        for (policy in policies) {
            assertTrue(policy.title.isNotBlank())
            // One dated source per language, all at the same revision (ARCH-199).
            assertTrue("2026" in policy.lastUpdated && "11" in policy.lastUpdated, policy.lastUpdated)
            assertEquals(english.summary.size, policy.summary.size, "summary of ${policy.lang}")
            assertEquals(english.sections.size, policy.sections.size, "sections of ${policy.lang}")
            policy.sections.forEachIndexed { index, section ->
                assertTrue(section.heading.isNotBlank())
                assertEquals(english.sections[index].paragraphs.size, section.paragraphs.size, "section $index of ${policy.lang}")
                assertTrue(section.paragraphs.all { it.length > 20 })
            }
            assertTrue(policy.sections.any { s -> s.paragraphs.any { "szdict.com/privacy" in it } })
        }
        assertEquals("2026-11-11", PRIVACY_LAST_UPDATED)
        // The texts are genuinely different languages, not copies.
        assertEquals(3, policies.map { it.title }.toSet().size)
        assertTrue(privacyPolicy(UiLang.ZH_HANT).sections.first().paragraphs.first().contains("應用程式"))
        assertTrue(privacyPolicy(UiLang.ZH_HANS).sections.first().paragraphs.first().contains("应用"))
        // SET-090: the points the policy must state.
        val text = (english.summary + english.sections.flatMap { it.paragraphs }).joinToString(" ").lowercase()
        for (needle in listOf("no accounts", "telemetry", "no network requests", "szdict.com", "font host", "preferences",
            "most recent search query", "camera", "microphone", "save photos to gallery", "share menu", "bookmarks")) {
            assertTrue(needle in text, "policy does not mention: $needle")
        }
    }

    @Test
    fun creditsCarryTheInstalledDatasetVersions() {
        val sources = listOf(
            SourceInfo("CC-CEDICT", "1.0", "2026-10-09T09:12:50Z", "CC-BY-SA-4.0", "https://www.mdbg.net", ""),
            SourceInfo("CC-CEDICT Cantonese readings", "2015-09-23", "", "CC-BY-SA-3.0", "https://cccanto.org", ""),
            SourceInfo("HSK syllabus vocabulary parser (HSK 3.0, 2026 update) + HSK parser patch", "commit 2adf7c9", "", "MIT", "https://github.com", ""),
            SourceInfo("Some new corpus", "7", "", "CC0", "https://example.org", "extra counts"),
        )
        val resolved = resolveCredits(DataSourceCredits, sources)
        fun version(name: String) = resolved.first { it.credit.name == name }.version
        assertEquals("1.0", version("CC-CEDICT"))
        assertEquals("2015-09-23", version("CC-Canto"))
        assertEquals("commit 2adf7c9", version("HSK syllabus vocabulary parser"))
        assertEquals("", version("Tatoeba"))
        // A dataset source no credit describes still gets its own card (SET-040).
        val extra = resolved.last()
        assertEquals("Some new corpus", extra.credit.name)
        assertEquals("extra counts", extra.credit.role)
        assertEquals(DataSourceCredits.size + 1, resolved.size)
        assertTrue(DataSourceCredits.all { it.licence.isNotBlank() && it.links.isNotEmpty() })
        assertEquals(DataSourceCredits.size, DataSourceCredits.map { it.name }.toSet().size)
        assertEquals(LibraryCredits.size, LibraryCredits.map { it.name }.toSet().size)
    }
}
