# 01 — Product Overview / Master Product Specification

## 1. Purpose

`PROD-001` `[MUST]` The product is an **offline-first Chinese–English dictionary and reading aid**.
Its core promise: any Chinese text a learner encounters — typed, handwritten, pasted, photographed or
found in an image, shared from another app (Android), spoken, or found inside another dictionary definition —
can be turned into accurate, tappable, pronounceable dictionary entries in one or two gestures, with
no network access.

`PROD-002` `[MUST]` The product serves two primary audiences simultaneously and must not privilege
one at the expense of the other:

- **Learners** looking up words, browsing HSK vocabulary, checking pronunciation and tones.
- **Readers** encountering unsegmented running text (signs, menus, articles, subtitles, speech) who
  need the text broken into words and each word explained.

## 2. Design philosophy

`PROD-010` `[MUST]` **Offline, complete, and instant.** On Android and iOS the entire dictionary,
its indexes, HSK data, pronunciation assets and the OCR and handwriting models are installed with the
app; the voice model is the only separate, one-time download (`PROD-070`). On Web the app shell and
dataset are stored for offline use, the OCR model is stored once first downloaded, and other assets
(including the handwriting model) are fetched when needed (`PROD-070`). No search, entry view,
segmentation, or HSK browse operation may require the network. Nothing in the UI may block on I/O that could have been
precomputed.

`PROD-011` `[MUST]` **Everything\* Chinese is tappable.** Any Chinese character rendered anywhere in
the app — search results, definitions, example sentences, OCR output, voice transcripts, pasted
passages, character decompositions — is a live entry point into the dictionary. A tap resolves *what
word this character belongs to at this position*, not merely the single character. \*note: for hanzis inside of a card for example, the card will be tapable taking the user to that word, but not the hanzi individually; this requirement is general guidance of what the app should offer and not a litereal obligation.

`PROD-012` `[MUST]` **Show the reading by default.** Pronunciation (Mandarin Pinyin or Cantonese
Jyutping, per mode) is displayed next to every headword, and tone information is available as
optional colour on both the characters and the romanisation. For self-testing, the user can hide all
readings everywhere with one global, persisted toggle; tone colours and audio playback are unaffected
(`GLB-120`).

`PROD-013` `[SHOULD]` **Ink-and-paper visual language.** Warm paper neutrals, near-black ink, a
single seal-red accent, hairline borders, no heavy elevation or chrome. Light and dark variants of
the same palette. Content scrolls edge-to-edge beneath translucent system bars with a soft scrim
rather than opaque bars. Concrete tokens: `GLB-060`.

`PROD-014` `[SHOULD]` **Never blank the screen for work in progress.** Loading states either keep
the previous content visible or present a shape-matched skeleton; they never replace populated
content with a spinner. See `GLB-030`.

`PROD-015` `[MUST]` **Latest intent wins.** Fast typing, rapid tapping, and quick navigation must
never produce results from a stale request. See `GLB-040`.

`PROD-016` `[SHOULD]` **Explain provenance.** Every bundled dataset and model is credited in-app with
its licence and source link (`SET-040`). This is done at a specific section, data source credits should only be included in the UI if that improves the user experience/provides important context.

`PROD-017` `[MUST]` **No user data is kept.** In the first iterations the app stores no user-curated
data — no history list, favourites, saved words or notes (`PROD-030`). Only preferences and the single
restored search query are persisted (`PROD-060`, `SRCH-143`). Reason: the app takes on no
responsibility for keeping user data safe while the core product is consolidated; data-storage
features may be added in a later iteration.

`PROD-018` `[MUST]` **One published privacy policy.** A single privacy policy is reachable at the
`/privacy` route (`GLB-110`) — in the app, which bundles its text so it works offline (in every UI
language), and at szdict.com/privacy with the same content. It is opened from Settings and the
attribution area and describes, without legal boilerplate in the spec: no accounts, no telemetry, the
network behaviour of `PROD-071`, what is stored on the device (preferences, the last query, cached
dataset and models), on-device-only camera and microphone use, and share intents (`SET-090`).

## 3. Supported platforms

`PROD-020` `[MUST]` First-class targets: **Android** (phone + tablet), **iOS** (iPhone + iPad),
**Web** (modern evergreen browsers, mobile and desktop viewport widths).

`PROD-021` `[MUST]` Product behaviour is identical across platforms except where recorded in a
"Platform differences" section of a feature spec. The intentional divergences are:
- **Voice is mobile only**: Android and iOS (`PROD-022`, `PROD-023`);
- **OCR engine**: OCR exists on every platform with the same inputs (`PROD-052`), but the engine
  differs — ML Kit text recognition bundled on Android, Apple Vision on iOS, and the ML Kit model
  repackaged for the browser on Web (`CAM-130`…, `ARCH-197`);
- **asset delivery**: mobile installs everything up front except the voice model, which Play and iOS
  builds download once on demand; Web keeps the app shell, the dataset and, once first downloaded,
  the OCR model offline, and fetches other assets when needed (`PROD-070`);
- **share targets** exist on Android only; iOS has no share extension in v1 (`PROD-103`, `PROD-111`;
  decided: `Q-042`).

`PROD-022` `[MUST]` On Web, the Voice destination is **omitted entirely** from the navigation
(`PROD-050`), together with its launcher shortcut; the route to it falls back to Search (`GLB-112`).
OCR **is** a Web destination (`/ocr` no longer falls back on Web): its camera uses the browser's
camera permission, and flash and tap-to-focus are offered only where the browser supports them
(`CAM-130`…).

`PROD-023` `[MUST]` No dead affordance: no platform shows a disabled Voice control or a "not
supported" toast for an omitted destination, and no OCR input method is shown where the platform
cannot provide it (e.g. no camera on a device without one). On Web the OCR model (~2.6 MB) is
downloaded the first time OCR is opened, with a loading state, then stored for offline use and
versioned: when online the app checks the model manifest and downloads only a changed version
(`DATA-196`, `ARCH-197`). **Handwriting input is available on all platforms**; on
Web its model is fetched the first time handwriting is opened, with a loading state, and kept in the
browser cache, and should be available offline (`HW-001`…, `PROD-070`, `GLB-035`).

`PROD-024` `[MUST]` Desktop (macOS/Windows/Linux native) is **out of scope**. Wide-viewport web is
the desktop story.

`PROD-025` `[SHOULD]` Minimum platform versions: Android 8.0 (API 26; decided: `Q-092`); iOS 26
(decided: `Q-116`), so every supported iOS version offers Voice and Apple Translation; Web = last two
versions of Chrome/Edge/Safari/Firefox.

`PROD-030` `[MUST]` Explicit non-goals for v1:
- desktop-native apps (`PROD-024`);
- accounts and cloud sync;
- **user-curated data** in the early iterations: no search history list, favourites, saved or
  starred words, or notes (`PROD-017`; the single restored query of `SRCH-143` is not a history);
- spaced repetition and flashcards (they need user-data storage beyond `PROD-017`);
- a system-wide input method (handwriting is an in-app input method only);
- **pronunciation scoring**, completely: no scoring feature and no placeholder interface, port or
  stub for one;
- a screen-assistant integration (removed from the product, not deferred);
- translation of full sentences by the app itself — it hands off to an external translator: Google
  Translate on Android and Web, Apple Translation on iOS, which is always available on iOS 26 (the
  Google Translate web page is used there only when the language pair is unsupported; `MED-030`).

**Deferred (post-v1)**
- Character origins (see the Deferred list in [04 — Entry and media](04-entry-and-media.md)).
  Character **decomposition** is not deferred: it ships in v1 (`ENT-120`…`ENT-125`).
- **Grammar lessons — v2.** Grammar points from the 2025 HSK grammar list plus lessons from other
  sources, shipped as separately licensed data files with per-item source and licence; own
  explanations and examples pass a review status before shipping. Discovery in the HSK destination
  (per level) and an entry's Grammar section. v1 only keeps its architecture from blocking them
  (`ARCH-198`, `ENT-127`, `HSK-099`, `PIPE-083`). Flashcards built on them come later still and
  require user-data storage beyond `PROD-017`.
- **HSK character-level UI.** The 2025 syllabus character list is recorded in the v1 dataset
  (`DATA-194`, `HSK-098`), but how it is shown is open (`Q-120`); no UI in v1 unless that question
  decides one.
- An iOS share extension (sharing images or text into the app is Android only in v1; `Q-042`).
- Fuzzy pinyin matching (`Q-105`).

## 4. Dictionary modes (Mandarin / Cantonese)

`PROD-040` `[MUST]` The app supports two **dictionary modes**: `MANDARIN` and `CANTONESE`. Exactly
one is active at a time. The mode is a persisted user setting (`SET-010`).

`PROD-041` `[MUST]` Mode selection changes:
- which dataset is queried (`DATA-010`);
- which romanisation is displayed and accepted as pronunciation input (Pinyin vs Jyutping);
- which tone palette applies (5 Mandarin tones vs 6 Cantonese tones — `GLB-064`);
- which definition sources appear and in what order (`DATA-022`);
- how HSK words are presented: HSK is offered in both modes; in Cantonese mode the same HSK word
  list is shown with Jyutping readings and Cantonese-first definitions (`HSK-090`…).

`PROD-042` `[MUST]` Mode selection **does not** change: navigation structure, search interaction
model, segmentation interaction model, OCR, voice and handwriting workflows, entry-detail layout,
settings, or any other screen structure. The application logic is romanisation-agnostic; all
language-specific knowledge lives in data (`DATA-070`).

`PROD-043` `[MUST]` Changing the user's mode is a full data-context switch: it re-runs the active
search, rebuilds any open passage analysis, and clears the entry-detail stack. Only a real change of
the persisted mode does this; opening a link whose route carries the other mode (`PROD-057`) is not a
mode change and neither clears the stack nor alters the setting. Persisted per-mode state is kept
separately so switching back restores the previous context (`SET-013`).

`PROD-044` `[SHOULD]` Simplified/Traditional script preference is **orthogonal** to mode and applies
to both. Cantonese defaults to Traditional; Mandarin defaults to Simplified. The user can override
either (`SET-011`). Independently of that preference, the text shown in Reading, the OCR text view
and the Voice transcript can be converted between scripts on demand (`PROD-112`).

`PROD-045` `[MUST]` Where a Cantonese entry has no Cantonese-sourced definition, Mandarin
definitions are shown. In Cantonese mode, definitions are labelled **per source block, not per
sense**: the Cantonese (CC-Canto) block comes first; the Mandarin (CC-CEDICT) senses follow as one
labelled block showing their pinyin, collapsed when a Cantonese block exists (`ENT-110`…`ENT-116`, `DATA-022`; decided:
`Q-080`). The app must never present an empty entry merely because one source lacked a gloss.
Headwords with no Cantonese reading at all are still shown in Cantonese mode, with their Mandarin
senses; do not show a "No Cantonese reading" note (decided: `Q-115`). In both modes, a character with
no CC-CEDICT or CC-Canto definition shows its **Unicode (Unihan)** definition and readings as a
fallback block; characters known only to Unihan are full, searchable, openable entries ranked after
dictionary entries (`ENT-126`, `SRCH-079`, `DATA-190`).

## 5. Major features

| Feature | Spec | Notes |
|---------|------|-------|
| Search (Chinese / pronunciation / English) | [03 — Search](03-search.md) | The primary surface and home; abbreviated pinyin, alternative pronunciations, combinations, a words-inside button on every card of 2+ hanzi, Word of the Day and Settings cards |
| Entry detail (word page) | [04 — Entry and media](04-entry-and-media.md) | Definitions (CC-CEDICT, CC-Canto, Unicode (Unihan) as fallback), related words, character decomposition ("Characters" section), strokes, examples |
| Pronunciation playback & TTS | [04 — Entry and media](04-entry-and-media.md) | Recorded audio for words/syllables, TTS for sentences |
| Text analysis / Reading | [05 — Reading and input](05-reading-and-input.md) | Paste, type or receive shared text, tap through it |
| OCR | [05 — Reading and input](05-reading-and-input.md) | Primary destination; all platforms; camera, file pick, drag and drop, paste |
| Voice transcription | [05 — Reading and input](05-reading-and-input.md) | Primary destination; Android + iOS; model downloaded on first use in Play and iOS builds |
| Script conversion | [05 — Reading and input](05-reading-and-input.md) | Simplified ↔ Traditional (OpenCC standard, Hong Kong, Taiwan) on Reading, OCR text and Voice transcript; all platforms |
| Handwriting input | [05 — Reading and input](05-reading-and-input.md) | Search and Reading fields; all platforms |
| HSK browser | [06 — HSK and settings](06-hsk-and-settings.md) | Both dictionary modes; variants of HSK words carry the same badge |
| Settings & attribution | [06 — HSK and settings](06-hsk-and-settings.md) | Script, mode, theme, tone colour, pronunciation visibility, app language, boundaries, auto-save, credits, privacy policy |
| Platform integrations | [06 — HSK and settings](06-hsk-and-settings.md) | Share targets on Android (image → OCR, text → Reading), launcher shortcuts, Android home-screen widget |

## 6. Navigation model

`PROD-050` `[MUST]` A **flat primary navigation**, in this order:
- Android and iOS — five destinations: `Search`, `OCR`, `Voice`, `Reading`, `HSK`;
- Web — four destinations: `Search`, `OCR`, `Reading`, `HSK` (`PROD-022`).

Compact widths (< 600 dp / < 600 px) use a bottom navigation bar; medium and expanded widths use a
navigation rail on the leading edge.

**Settings is not a destination.** It is reached from the Settings card on the empty Search screen
(`SRCH-116`, `SET-005`).

`PROD-051` `[MUST]` The same destinations are shown in both dictionary modes; HSK is present in
Cantonese mode too (`HSK-090`…).

`PROD-052` `[MUST]` **OCR is a primary destination** on every platform (`CAM-006`…`CAM-009`,
`CAM-130`…). Every platform offers the same inputs: the live camera feed, picking an image from local
files, dragging and dropping an image, and pasting an image (Ctrl/Cmd+V or the platform paste action).
At compact widths, selecting it opens straight into the live camera feed (or the permission rationale
when camera access is not granted — `CAM-010`…`CAM-015`) with an image-picker button; at expanded
widths (`GLB-125`) it opens on a large drop zone with pick and paste buttons, the camera being a
secondary option. The primary navigation stays visible, and the camera is released when the user
leaves the destination. The launcher shortcut and, on Android, a shared image select the OCR
destination (`SET-050`, `SET-054`).

`PROD-053` `[MUST]` Tapping the already-selected primary destination scrolls that destination's
list/grid to the top (animated). OCR and Voice have no list to scroll: re-tapping OCR while a
capture is being reviewed returns to its input screen (the live feed, or the drop zone at expanded
widths); re-tapping Voice has no effect.

`PROD-054` `[MUST]` **Entry detail is a stack**, not a destination. Selecting a word pushes a
detail route. Tapping a word inside a detail pushes another route. Back pops one route. At expanded
widths the list-detail two-pane layout applies (`GLB-125`), and a close action clears the whole
stack. Full rules: `ENT-010`…`ENT-018`.

`PROD-055` `[MUST]` Switching primary destination clears the entry-detail stack, dismisses any
open word-candidate sheet, and closes the handwriting sheet.

`PROD-056` `[MUST]` Back-navigation precedence, innermost first:
1. an open word-candidate bottom sheet dismisses (restoring the handwriting sheet if it hid it);
2. an open handwriting sheet closes;
3. on OCR, a capture under review returns to its input screen (`PROD-053`);
4. the entry-detail stack pops one route (Settings, opened from Search, pops the same way —
   `SET-005`);
5. the platform default (Android: leave the app; Web: browser history; iOS: no-op at root).

`PROD-057` `[SHOULD]` The app and szdict.com share one route table (`GLB-110`): the same routes
address the same screens in the app and on the web, browser back/forward and deep links work on Web,
and the installed app opens szdict.com links (`GLB-111`). The dictionary mode is the first path
segment — `/zh/…` for Mandarin, `/yue/…` for Cantonese (e.g. `/zh/word/{natural-key}`,
`/yue/search?q=`); the script preference is a viewer setting and never part of the path (decided:
`Q-107`). A link's mode applies to that route only: the entry route, and routes pushed from it, carry
their own mode and show their content in it, while the persisted dictionary mode is unchanged;
leaving that stack returns to the user's mode. This holds for App Links / Universal Links, web links
and the widget (`GLB-110`; decided: `Q-118`). Each word route is also an indexable, edge-rendered word page on szdict.com that links into
the app (`GLB-113`). Reading text, OCR and voice content, and queries classified as passages
(`SRCH-035`), are never placed in URLs (`GLB-112`).

## 7. Global behaviour and persistence

`PROD-060` `[MUST]` Persisted across launches: dictionary mode, script, theme mode, tone colouring,
pronunciation visibility (`show_pronunciation`, `SET-017`), app language (`ui_language`,
`SET-018`), input method (`input_method`, `SET-019`), word-boundary display, camera auto-save, the
last active search side, and the last search query. Full list and semantics: `SET-010`.

`PROD-061` `[MUST]` On a cold launch (after the user or the OS has ended the task), the last search
query is re-executed so the user returns to where they left off. The entry-detail stack, OCR
capture, voice transcript and reading passage are **not** restored on a cold launch (`SET-014`).
This does not apply to system-initiated process recreation from saved state, which `PROD-062`
covers.

`PROD-062` `[MUST]` Within a session — across configuration change, rotation, and Android
system-initiated process recreation from saved state — the active destination, the entry-detail
stack (with each route's tab, header collapse and scroll positions), the search query and scroll
position, and the OCR capture with its recognition result are preserved. Normative list: `GLB-050`.

## 8. Offline expectations

`PROD-070` `[MUST]` On **Android and iOS**, everything the app uses except the voice model — the
dictionary dataset and indexes, recorded audio, stroke data, example sentences, the OCR model, the
handwriting model, the language model for combinations, the script-conversion dictionaries
(`DATA-195`), the privacy policy text and the bundled fonts — is installed with the
app. The **voice (ASR) model is the only separate download** (`VOICE-095`):
- Play builds: an on-demand Play Asset Delivery pack, downloaded by the Play Store when the user
  first opens Voice, with its size disclosed before the download;
- iOS: an Apple-hosted Background Assets pack (available on every supported iOS version, `PROD-025`);
- direct APK: bundled inside the package, so there is no download at all.

While the pack is missing, the Voice destination shows a download state instead of the microphone
(`GLB-035`, `VOICE-095`). Android builds declare no `INTERNET` permission — Play asset delivery
downloads through the Play Store — and each flavour's permissions are fixed by an allow-list
(`ARCH-104`…`ARCH-107`). A freshly installed app with the network permanently disabled supports every
feature: search in all input modes (including abbreviated pinyin and combinations), entry detail
with definitions, related words, strokes and examples, pronunciation playback, HSK browsing, OCR,
handwriting, text analysis, script conversion and settings — and voice transcription once the voice model is present
(always, in the direct APK).

On **Web**, the app is a PWA whose offline set is the **app shell, the dataset and the OCR model**. The app shell and dataset are stored on first visit (`PERF-060`, `ARCH-145`),
and when a new dataset is published the app updates itself by applying a per-release changeset
rather than downloading the whole dataset again (`DATA-216`, `PIPE-167`). The OCR model (~2.6 MB) is
downloaded the first time OCR is used and then stored for offline use like the dataset. The OCR model is versioned: a manifest carries
its version and SHA-256, and when online the app downloads a new model only when the manifest has
changed (`DATA-196`, `ARCH-197`). All other Web assets — the handwriting model, the CJK font, the abbreviation/combination
language-model data, recorded audio and stroke data — are fetched when first needed, without
delaying single-word results, and kept in the browser cache, but are not guaranteed to be available
offline (`GLB-035`); while a recording loads or is unavailable, TTS stands in (`MED-011`). Web
bundles no CJK font: Compose Multiplatform's automatic font fallback downloads Noto subsets from a
third-party font server when needed (`ARCH-005`; decided: `Q-117`). Android and iOS keep their
bundled fonts, including the rare-character fallback font (`Q-109`).

`PROD-071` `[MUST]` The app never requires the network for correctness. Network use is limited to:
- Android and iOS: none from the app itself. The voice-model download is performed by the platform
  (Play Store, Background Assets). Attribution links open in the system browser. Text handed to the
  translator (`MED-030`) is processed by that app: Google Translate on Android (falling back to the
  Google Translate web page in the browser when the app is not installed), Apple Translation on iOS
  (falling back to that web page only when the language pair is unsupported).
- Web: loading the app and dataset, dataset update checks and changesets, the OCR model manifest
  check and model downloads, the on-demand fetches in `PROD-070` (including the CJK font from its
  third-party font host, `GLB-090`), and opening attribution links and the Google Translate hand-off
  in a new browser tab. The camera image used for OCR never leaves the device.

## 9. Performance goals (summary)

`PROD-080` `[MUST]` Search must feel **typed-through**: results for each keystroke render within
the `PERF-020` budgets on the reference phone (abbreviated pinyin and combinations: `PERF-025`).
Budget tables and measurement method: [08 — Engineering](08-engineering.md), performance part.

`PROD-081` `[MUST]` Cold start to an interactive, searchable field must not depend on loading the
dictionary into memory. There is no "loading the full dictionary" phase visible to the user; the
reference app's seed-engine-then-swap behaviour is removed (`ARCH-131`).

`PROD-082` `[MUST]` The installed size of each dictionary dataset with core indexes must be a small
fraction of the reference app's ~180 MB on-device dictionary footprint. Budgets: `PERF-070`;
per-flavour app sizes including the voice and handwriting models: `PERF-073`.

## 10. Relationship to the data pipeline

`PROD-090` `[MUST]` The application does **not** parse, join, reconcile, normalise, or clean raw
dictionary sources at runtime. It consumes a **pre-built, versioned, immutable dataset** whose exact
shape is defined in [07 — Data](07-data.md).

`PROD-091` `[MUST]` The pipeline lives in the same repository as the app (`PIPE-160`…`PIPE-166`),
but the **data contract remains the boundary** between them: the app knows only the contract; the
pipeline knows only the contract. Neither may assume the other's internals.

`PROD-092` `[MUST]` Everything deterministic is precomputed: romanisation variants, plain/toneless
search keys, abbreviation keys (including those of alternative pronunciations), tone arrays, English
tokenisation, folded forms, ASCII case handling, character postings, sub-word (compound)
relationships, character decompositions, variant/redirect resolution, HSK joins (both modes, including
levels carried over to variants), ranking metadata, the combination language model, and the maximum
headword length. The
runtime is reduced to indexed lookup, ranking of already-scored candidates, decoding, and rendering.
Migration list: `PIPE-100`.

`PROD-093` `[MUST]` The pipeline's inputs, outputs, joins and validation gates are specified in
[07 — Data](07-data.md). Its sources are pinned in the repository, and CI regenerates the dataset
whenever the sources or the pipeline change, running the validation gates and golden queries before
the app build consumes the result (`PIPE-160`…`PIPE-166`).

`PROD-094` `[MUST]` Dataset versioning is explicit and checked at open time; a mismatched dataset is
a hard, reported error, never a silent partial read (`DATA-200`).

## 11. Key user journeys

`PROD-100` **Look up an English word.** Open app → Search (already focused destination) → type
`graduate student` → English side is active by default → ranked results, exact-phrase matches first
(in Cantonese mode, CC-Canto-sourced entries are listed before CC-CEDICT entries, each ranked within
its group, and a match found only in Mandarin senses appears once per CC-CEDICT headword and pinyin
rather than once per Jyutping reading — `DATA-076`; decided: `Q-119`) → optionally open a card's
words-inside button to see the sub-words and single characters inside it with their definitions
(every card of 2+ hanzi has it, whatever the query kind — `SRCH-105`, `ENT-073`) → tap a card → entry
detail with numbered senses, HSK badge, pronunciation button, related words.

`PROD-101` **Look up by pronunciation.** Type `nihao`, `ni3hao3`, `nǐ hǎo`, or abbreviated `nh` /
`nhao` → the app recognises parseable romanisation, with any mix of full and abbreviated syllables
(`SRCH-050`…`SRCH-054`), and switches to the Chinese side automatically when the input carries
explicit tones or characters or reads as abbreviated romanisation → exact-reading matches rank above
longer prefix matches, and fuller spellings above abbreviations → alternative pronunciations recorded
in the dictionary (`also pr.`, `Taiwan pr.`) match too, in full, toneless and abbreviated form, and
such a card shows the matched reading with its label ("also pronounced …" / "Taiwan pronunciation …"),
ranked after main-reading matches of the same match class (`SRCH-078`, `DATA-192`) → for
multi-syllable input a `Combinations` group offers the most likely multi-word sequences (e.g. `wxhn`
→ 我 喜欢 你), each word individually tappable (`SRCH-060`…`SRCH-068`).

`PROD-102` **Paste a sentence into search.** Paste `我们研究生命科学` → the query is recognised as
Chinese text → a segmented passage card appears above the results with word boundaries underlined →
each matched word appears as a full result card, in text order → unmatched runs appear as inert
"no match" rows → tapping any character opens a bottom sheet of every dictionary word covering that
position, longest and most likely first.

`PROD-103` **Photograph a sign** (all platforms). Tap the `OCR` destination → on a phone the live
camera feed opens at once (first time: permission rationale, then grant; on Web, the browser's camera
prompt and, the first time, the OCR model download with a loading state) → tap to focus, toggle flash
where supported → shutter; or, on any platform, pick an existing image, drag and drop one, or paste
one (on wide layouts these come first, around a large drop zone) → the photo is shown with
recognised text boxed → optionally the original photo is saved to the user's gallery → tap a
character in the photo → the word sheet opens → tap a word → entry detail. A `Text` view lists the
recognised lines as tappable, segmented passages, which can be converted between scripts
(`PROD-112`). Re-tapping `OCR` returns to the input screen. The launcher shortcut lands in the same
destination, and on Android sharing an image to the app from another app opens it there for review
(`SET-054`).

`PROD-104` **Browse HSK.** HSK destination, in either dictionary mode → level chips → a level-tinted
masonry grid of word cards in pronunciation order (Jyutping in Cantonese mode) → tap a card → the
canonical dictionary entry, with the HSK level and part-of-speech shown per level. Every HSK word is
linked to a CC-CEDICT entry (in Cantonese mode, the Cantonese dataset's copy of it); the pipeline
fails the build on any unlinked row (`HSK-056`, `HSK-090`; decided: `Q-118`). Variants of an HSK word
(plain, old and erhua variants, up to two hops, in either direction) show the same HSK badge wherever
they appear, but the grid lists only the syllabus words (`HSK-097`, `DATA-193`).

`PROD-105` **Follow a definition into another word.** In an entry's definition, a Chinese reference
(e.g. `variant of 一點|一点[yi1 dian3]`) is rendered as a link → tapping it opens a sheet offering the
referenced entry plus every word contained inside it → selecting one pushes a nested detail route →
Back returns to the original word with its tab and scroll position intact.

`PROD-106` **Study a character.** Entry detail → `STROKES` tab → animated stroke order, then a
guided finger-trace quiz with per-stroke validation and hints after repeated misses. Or, on the
`DICT` tab, expand the **Characters** section at the bottom → each hanzi of the word shows its
structure (e.g. ⿰亻木) and immediate components, each with its glyph, reading and short definition →
expand a component to see its own parts, down to root components → tap any part to open its entry
(`ENT-120`…`ENT-125`).

`PROD-107` **Word of the day.** The empty Search screen shows a Word of the Day card (`SRCH-115`);
tapping it opens that entry. On Android, a home-screen widget (`SET-060`) shows one HSK word per day
drawn from the levels the user selected for that widget instance; tapping it opens that exact entry,
and a speaker button plays its pronunciation. Card and widget use the same deterministic daily pick, however the search screen word of the day is selected based on the entire dataset, and not just HSK
(`SET-063`).

`PROD-108` **Transcribe speech** (Android, iOS). Tap the `Voice` destination (first time, in
Play and iOS builds: the voice model downloads, with its size shown first — `VOICE-095`; then the
microphone permission rationale and grant) → speak Mandarin, Cantonese or English → live text
appears already segmented and tone-coloured and updates as words arrive (in Traditional characters
when the script setting is Traditional, with "Show original" available — `VOICE-096`); each finished utterance is
committed on its own line and never moves → tap any hanzi → the word sheet opens, keeping its
selection while the live text grows → tap a word → entry detail. The pronunciation toggle shows or
hides interlinear readings (`GLB-120`, `GLB-121`). Leaving the destination stops the microphone
(`VOICE-001`…).

`PROD-109` **Look up a character by handwriting.** In Search (or Reading), tap the input-method
button → the handwriting sheet replaces the keyboard → draw a character; the candidate row refreshes
after each stroke (undo stroke and clear are available) → tap a candidate → it is inserted at the
cursor and results update exactly as for typed input → keep drawing, or tap a result → entry
detail. Tapping the button again switches back to the keyboard; the choice persists
(`input_method`, `SET-019`; `HW-001`…).

`PROD-110` **Self-test.** Tap the pronunciation toggle → readings disappear for every hanzi in the
app, their space kept and tone colours unchanged → open the HSK destination and browse a level,
recalling each reading → tap a card; the entry's reading stays hidden but the play button still
plays it → tap the toggle again to check. The setting persists across launches (`GLB-120`,
`SET-017`).

`PROD-111` **Read shared text** (Android only; `Q-042`). In another app, select Chinese text → Share
to the app, or use its text-selection menu entry → the Reading destination opens with the text in its
field, replacing any draft, already analysed → tap through it as in Reading (`SET-056`, `TXT-080`).

`PROD-112` **Convert a passage's script** (all platforms). In Reading, the OCR `Text` view or the Voice
transcript, the split button to the right of the action buttons offers the conversion that fits the
detected script ("Convert to Traditional" for Simplified text, "Convert to Simplified" for
Traditional text; for mixed, neutral or too-short text it follows the user's script setting) → tap it
→ the displayed text is converted and re-analysed, keeping word boundaries and tone colours → the main
action becomes "Show original", which restores it. The split button's menu opens a dialog to set the
source ("Text is:" Simplified, Traditional, Hong Kong or Taiwan Traditional; detected value
preselected) and target (also Taiwan with phrases, e.g. 软件 → 軟體). Conversion is a view state:
the Reading draft keeps the original text (`TXT-081`…`TXT-084`, `CAM-130`…, `VOICE-096`). Conversion
runs on the device with OpenCC, built as a separate Kotlin Multiplatform library (`ARCH-196`).
