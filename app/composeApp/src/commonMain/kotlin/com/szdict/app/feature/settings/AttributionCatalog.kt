package com.szdict.app.feature.settings

import com.szdict.core.api.SourceInfo

internal data class CreditLink(val label: String, val url: String)

/**
 * One credited data source, model or engine (SET-040). [role] and [licence] are English string
 * keys (translated through `AppStrings`); [name] is a proper noun and stays as is. [match] lists
 * lowercase fragments of the dataset's `sources` names (SET-042) this credit describes, so the
 * card can show the exact version the installed dataset was built from.
 */
internal data class Credit(
    val name: String,
    val role: String,
    val licence: String,
    val links: List<CreditLink>,
    val match: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
)

internal data class LibraryCredit(val name: String, val version: String, val role: String, val licence: String, val url: String)

/** A credit joined with the version record of the installed dataset, when it has one. */
internal data class ResolvedCredit(val credit: Credit, val version: String, val date: String)

internal const val AttributionIntro =
    "This app combines independently licensed dictionaries, word lists, example sentences, recordings, stroke data and recognition models. Each keeps its own licence; the app's own code is Apache-2.0."

private const val CANTONESE_COUNTS = "Cantonese frequency counts and word pairs"

internal val DataSourceCredits: List<Credit> = listOf(
    Credit(
        "CC-CEDICT", "Mandarin headwords, Pinyin, English definitions, variants and notes", "CC BY-SA 4.0",
        listOf(CreditLink("MDBG / CC-CEDICT", "https://www.mdbg.net/chinese/dictionary?page=cc-cedict")),
        match = listOf("cc-cedict"), exclude = listOf("cantonese"),
    ),
    Credit(
        "CC-Canto", "Cantonese readings and definitions", "CC BY-SA 3.0",
        listOf(CreditLink("CC-Canto", "https://cccanto.org")),
        match = listOf("cc-canto", "cc-cedict cantonese"),
    ),
    Credit(
        "FrequencyWords", "Word frequency for ranking and segmentation (OpenSubtitles 2018 via OPUS)", "CC BY-SA 4.0",
        listOf(CreditLink("FrequencyWords", "https://github.com/hermitdave/FrequencyWords"), CreditLink("OPUS", "https://opus.nlpl.eu/")),
        match = listOf("frequencywords"),
    ),
    Credit(
        "HSK syllabus vocabulary parser",
        "HSK word levels, parts of speech, word-to-entry links and character levels from the HSK 3.0 (2026 update) syllabus of Chinese Testing International, plus definitions for the few HSK words CC-CEDICT lacks. Only facts are stored; the official syllabus is not redistributed.",
        "Code: MIT; data and patch entries: CC BY-SA 4.0",
        listOf(CreditLink("hsk-syllabus-vocabulary-parser", "https://github.com/Punpuf/hsk-syllabus-vocabulary-parser")),
        match = listOf("hsk syllabus"),
    ),
    Credit(
        "Unicode Unihan Database", "Fallback definitions and readings for characters with no dictionary entry", "Unicode License v3",
        listOf(CreditLink("Unihan", "https://www.unicode.org/charts/unihan.html"), CreditLink("Unicode License v3", "https://www.unicode.org/license.txt")),
        match = listOf("unihan"),
    ),
    Credit(
        "Unicode Character Database", "Maps radical forms to unified ideographs in character decomposition", "Unicode License v3",
        listOf(CreditLink("UCD", "https://www.unicode.org/ucd/"), CreditLink("Unicode License v3", "https://www.unicode.org/license.txt")),
        match = listOf("ucd ", "equivalentunifiedideograph", "cjkradicals"),
    ),
    Credit(
        "BabelStone IDS", "Character decomposition", "Free to use; credited by choice",
        listOf(CreditLink("BabelStone IDS", "https://www.babelstone.co.uk/CJK/IDS.HTML")),
        match = listOf("babelstone"),
    ),
    Credit(
        "Tatoeba", "Mandarin and Cantonese example sentences with English translations", "CC BY 2.0 FR",
        listOf(CreditLink("Tatoeba", "https://tatoeba.org"), CreditLink("Tatoeba Challenge", "https://github.com/Helsinki-NLP/Tatoeba-Challenge")),
        match = listOf("tatoeba"),
    ),
    Credit(
        "OpenCC", "Simplified and Traditional Chinese conversion data", "Apache-2.0",
        listOf(CreditLink("OpenCC", "https://github.com/BYVoid/OpenCC")),
        match = listOf("opencc"),
    ),
    Credit(
        "audio-cmn", "Recorded syllable and word pronunciations", "CC BY-SA",
        listOf(CreditLink("audio-cmn", "https://github.com/hugolpz/audio-cmn")),
        match = listOf("audio-cmn"),
    ),
    Credit(
        "hanzi-writer-data", "Stroke outlines, stroke order and medians", "MIT; Arphic Public License",
        listOf(CreditLink("hanzi-writer-data", "https://github.com/chanind/hanzi-writer-data")),
        match = listOf("hanzi-writer"),
    ),
    Credit(
        "Make Me a Hanzi", "Stroke-to-component mapping for character decomposition", "LGPL-3.0",
        listOf(CreditLink("Make Me a Hanzi", "https://github.com/skishore/makemeahanzi")),
        match = listOf("make me a hanzi", "makemeahanzi"),
    ),
    Credit(
        "libime language model", "Mandarin word pairs for multi-word search combinations", "LGPL-2.1-or-later",
        listOf(CreditLink("libime", "https://github.com/fcitx/libime")),
        match = listOf("libime"),
    ),
    Credit(
        "rime-cantonese", "Cantonese frequency table and language model", "CC BY 4.0",
        listOf(CreditLink("rime-cantonese", "https://github.com/rime/rime-cantonese")),
        match = listOf("rime-cantonese", "essay-cantonese"),
    ),
    Credit("HKCanCor", CANTONESE_COUNTS, "CC BY 4.0", listOf(CreditLink("HKCanCor", "https://github.com/fcbond/hkcancor")), match = listOf("hkcancor")),
    Credit(
        "Common Voice Cantonese", CANTONESE_COUNTS, "CC0",
        listOf(CreditLink("Common Voice", "https://commonvoice.mozilla.org")), match = listOf("common voice"),
    ),
    Credit(
        "UD_Cantonese-HK", CANTONESE_COUNTS, "CC BY-SA 4.0",
        listOf(CreditLink("Universal Dependencies", "https://universaldependencies.org/treebanks/yue_hk/")), match = listOf("ud_cantonese"),
    ),
    Credit(
        "Cantonese Wikipedia", CANTONESE_COUNTS, "CC BY-SA 4.0",
        listOf(CreditLink("zh-yue.wikipedia.org", "https://zh-yue.wikipedia.org")), match = listOf("wikipedia"),
    ),
)

internal val ModelCredits: List<Credit> = listOf(
    Credit("sherpa-onnx", "Voice recognition runtime", "Apache-2.0", listOf(CreditLink("sherpa-onnx", "https://github.com/k2-fsa/sherpa-onnx"))),
    Credit("ONNX Runtime", "Inference runtime used by sherpa-onnx", "MIT", listOf(CreditLink("ONNX Runtime", "https://github.com/microsoft/onnxruntime"))),
    Credit(
        "Streaming Paraformer (zh / Cantonese / en)", "Voice recognition model", "Apache-2.0",
        listOf(CreditLink("ModelScope", "https://modelscope.cn/models/dengcunqin/speech_paraformer-large_asr_nat-zh-cantonese-en-16k-vocab8501-online")),
    ),
    Credit("LiteRT", "On-device runtime for text and handwriting recognition", "Apache-2.0", listOf(CreditLink("LiteRT", "https://github.com/google-ai-edge/LiteRT"))),
    Credit("opencc-kmp", "Simplified and Traditional Chinese conversion engine", "Apache-2.0", emptyList()),
)

internal val LibraryCredits: List<LibraryCredit> = listOf(
    LibraryCredit("Compose Multiplatform", "1.11.1", "Shared UI runtime, Foundation, Material 3, resources, and icons", "Apache-2.0", "https://github.com/JetBrains/compose-multiplatform"),
    LibraryCredit("Compose UI BackHandler", "1.11.1", "Platform-neutral back-event handling", "Apache-2.0", "https://github.com/JetBrains/compose-multiplatform"),
    LibraryCredit("Kotlin", "2.4.0", "Application language and standard library", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
    LibraryCredit("kotlinx.coroutines", "1.10.2", "Structured concurrency, cancellation, and state flows", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    LibraryCredit("kotlinx.serialization", "1.9.0", "JSON decoding for packaged dictionary assets", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
    LibraryCredit("AndroidX SQLite (bundled)", "2.7.1", "Read-only access to the dictionary datasets", "Apache-2.0; SQLite: public domain", "https://developer.android.com/jetpack/androidx/releases/sqlite"),
    LibraryCredit("AndroidX Activity Compose", "1.12.2", "Android activity and Compose integration", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/activity"),
    LibraryCredit("AndroidX DataStore Preferences", "1.2.1", "Persistent app settings", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/datastore"),
    LibraryCredit("AndroidX Glance", "1.1.1", "Home screen widgets", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/glance"),
    LibraryCredit("LiteRT", "1.4.2", "On-device runtime for text and handwriting recognition", "Apache-2.0", "https://github.com/google-ai-edge/LiteRT"),
)

/**
 * Joins the static credits with the installed dataset's `sources` record (SET-042). Dataset
 * sources no credit describes are appended as their own cards, so nothing bundled goes uncredited.
 */
internal fun resolveCredits(credits: List<Credit>, sources: List<SourceInfo>): List<ResolvedCredit> {
    val used = HashSet<SourceInfo>()
    val resolved = credits.map { credit ->
        val source = sources.firstOrNull { source ->
            val name = source.name.lowercase()
            credit.match.any { it in name } && credit.exclude.none { it in name }
        }
        if (source != null) used += source
        ResolvedCredit(credit, source?.version.orEmpty(), source?.date.orEmpty())
    }
    val extra = sources.filter { it !in used && resolved.none { r -> r.credit.name.equals(it.name, ignoreCase = true) } }.map {
        ResolvedCredit(
            Credit(it.name, it.role, it.licence, if (it.url.isBlank()) emptyList() else listOf(CreditLink(it.name, it.url))),
            it.version, it.date,
        )
    }
    return resolved + extra
}
