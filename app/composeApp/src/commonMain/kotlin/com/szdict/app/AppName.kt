package com.szdict.app

import com.szdict.core.api.UiLang

/**
 * The app's name per interface language. English has a short form for tight places (launcher
 * label, home-screen name, collapsed rail, PWA `short_name`) and the full form where there is room
 * (expanded rail header, browser tab title, PWA `name`); Chinese always uses 深圳词典 / 深圳詞典.
 *
 * Mirrored outside Kotlin, keep in step: Android `strings.xml` of `values`, `values-b+zh+Hans` and
 * `values-b+zh+Hant` (`app_name`), iOS `Info.plist` and the `InfoPlist.strings` of each `.lproj`
 * (`CFBundleDisplayName`), and the Web start page
 * (`wasmJsMain/resources/index.html`); the PWA manifest is written by `stageWebPwa`, which reads
 * [EN_SHORT] and [EN_FULL] from this file. Legal text (the privacy policy) names the app in its own words.
 */
object AppName {
    const val EN_SHORT = "SZ Dict"
    const val EN_FULL = "Shenzhen Dictionary"
    const val ZH_HANS = "深圳词典"
    const val ZH_HANT = "深圳詞典"

    /** Where space is tight. */
    fun short(lang: UiLang): String = when (lang) {
        UiLang.EN -> EN_SHORT
        UiLang.ZH_HANS -> ZH_HANS
        UiLang.ZH_HANT -> ZH_HANT
    }

    /** Where there is room. */
    fun full(lang: UiLang): String = when (lang) {
        UiLang.EN -> EN_FULL
        UiLang.ZH_HANS -> ZH_HANS
        UiLang.ZH_HANT -> ZH_HANT
    }
}
