package com.szdict.app.feature.settings

import com.szdict.core.api.UiLang

internal val PrivacyPolicyEn = PrivacyPolicy(
    lang = UiLang.EN,
    title = "Privacy policy",
    lastUpdated = "Last updated: 11 November 2026",
    summary = listOf(
        "Shenzhen Dictionary (SZ Dict, 深圳词典) is an offline Chinese–English dictionary for Mandarin and Cantonese. This policy covers the Android and iOS apps and the web app at szdict.com.",
        "In short: there are no accounts, no advertising, and no telemetry or analytics. What you look up, read, photograph or say stays on your device.",
    ),
    sections = listOf(
        PolicySection(
            "No accounts, no tracking",
            listOf(
                "The app has no sign-in and asks for no name, email address or other identity. It contains no advertising, analytics, crash-reporting or tracking software, and the developer runs no service that receives your searches, text, images or audio.",
            ),
        ),
        PolicySection(
            "Network use",
            listOf(
                "Android and iOS: the app makes no network requests of its own. The Android app does not even request the internet permission. The only downloads are those your app store or operating system manages for you — installing and updating the app, and, where offered, the voice-recognition model.",
                "Web: the web app loads from szdict.com — the app itself, the dictionary dataset, the text-recognition model and other assets — and loads Chinese fonts from a third-party font host when the text on screen needs them. As with any website, these servers see your IP address and browser type when a file is requested. Search queries are not sent to any server, and passage text, recognised text and clipboard content are never placed in a web address.",
            ),
        ),
        PolicySection(
            "What is stored on your device",
            listOf(
                "Your preferences (dictionary mode, script, app language, theme, tone colours, pronunciation and word-boundary display, input method, photo saving), your most recent search query, the level choice of each home-screen widget, and the installed or cached dictionary datasets and recognition models.",
                "The app does not keep a search history, bookmarks, word lists, notes or any other content you create. Reading passages, recognised text and voice transcripts live in memory only and are gone when the app closes.",
                "The Android app is excluded from cloud backup and device-to-device transfer.",
            ),
        ),
        PolicySection(
            "Camera and microphone",
            listOf(
                "The camera is used only to recognise Chinese text in the OCR screen, and the microphone only to transcribe speech in the Voice screen. Both permissions are optional and requested when you first use the feature. Recognition runs entirely on your device; images and audio are not uploaded.",
                "Nothing captured is stored, with one exception: if you turn on “Save photos to gallery”, the photos you take in the app are saved to your device's photo library. Audio is never saved.",
            ),
        ),
        PolicySection(
            "Shared content and the clipboard",
            listOf(
                "Text or images you send to the app from another app — through the share menu or the “Look up in Shenzhen Dictionary” text-selection action — are processed on your device only and are not stored or logged.",
                "The clipboard is read only when you tap Paste or use the “Read clipboard” shortcut.",
            ),
        ),
        PolicySection(
            "Other apps and websites you open",
            listOf(
                "A few actions hand content to software outside this app, and only when you ask: Translate passes the selected sentence to your translation app (or to the Google Translate website in your browser), spoken sentences use the text-to-speech engine set on your device, and source and licence links open in your browser. Those apps and sites follow their own privacy policies.",
            ),
        ),
        PolicySection(
            "Your choices",
            listOf(
                "You can refuse or withdraw the camera and microphone permissions at any time in system settings; the rest of the app keeps working. Clearing the app's storage, or uninstalling it, removes everything it has stored. On the web, clearing site data for szdict.com does the same.",
            ),
        ),
        PolicySection(
            "Changes and contact",
            listOf(
                "This policy ships inside the app, so it can be read offline, and the same text is published at https://szdict.com/privacy. If it changes, the new text arrives with an app update and the date above changes with it.",
                "Questions about privacy can be sent through the developer contact on the app's store listing.",
            ),
        ),
    ),
)
