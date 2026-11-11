package com.szdict.app.feature.settings

import com.szdict.core.api.UiLang

/** One headed block of the privacy policy. */
data class PolicySection(val heading: String, val paragraphs: List<String>)

/**
 * The bundled privacy policy in one UI language (SET-090, ARCH-199). The text is content, not
 * logic: one source file per language (`PrivacyPolicyEn.kt`, `PrivacyPolicyZhHans.kt`,
 * `PrivacyPolicyZhHant.kt`), all three sharing [PRIVACY_LAST_UPDATED]; the website's `/privacy`
 * page is rendered from the same sources.
 */
data class PrivacyPolicy(
    val lang: UiLang,
    val title: String,
    /** `Last updated: {date}` in this language, already formatted. */
    val lastUpdated: String,
    val summary: List<String>,
    val sections: List<PolicySection>,
)

/** ISO date of the current policy text; changes whenever any language's text changes. */
const val PRIVACY_LAST_UPDATED: String = "2026-11-11"

/** The policy in [lang]; every UI language has its own text (GLB-100), so there is no fallback. */
fun privacyPolicy(lang: UiLang): PrivacyPolicy = when (lang) {
    UiLang.EN -> PrivacyPolicyEn
    UiLang.ZH_HANS -> PrivacyPolicyZhHans
    UiLang.ZH_HANT -> PrivacyPolicyZhHant
}

val PrivacyPolicies: List<PrivacyPolicy> get() = UiLang.entries.map(::privacyPolicy)
