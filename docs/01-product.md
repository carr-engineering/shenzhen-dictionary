# 01 — Product Overview / Master Product Specification

## 1. Purpose

`PROD-001` `[MUST]` The product is an **offline-first Chinese–English dictionary and reading aid**.
Its core promise: any Chinese text a learner encounters — typed, handwritten, pasted, photographed,
spoken (Android and iOS), or found inside another dictionary definition — can be turned into
accurate, tappable, pronounceable dictionary entries in one or two gestures, with no network access.

`PROD-002` `[MUST]` The product serves two primary audiences simultaneously and must not privilege
one at the expense of the other:

- **Learners** looking up words, browsing HSK vocabulary, checking pronunciation and tones.
- **Readers** encountering unsegmented running text (signs, menus, articles, subtitles, speech) who
  need the text broken into words and each word explained.

## 2. Design philosophy

`PROD-010` `[MUST]` **Offline, complete, and instant.** The entire dictionary, its indexes, HSK
data, pronunciation assets and recognition models are installed with the app on Android and iOS, and
downloaded once and cached on Web (`PROD-070`). No search, entry view, segmentation, or HSK browse
operation may require the network. Nothing in the UI may block on I/O that could have been
precomputed.

`PROD-011` `[MUST]` **Everything Chinese is tappable.** Any Chinese character rendered anywhere in
the app — search results, definitions, example sentences, OCR output, voice transcripts, pasted
passages, character decompositions — is a live entry point into the dictionary. A tap resolves *what
word this character belongs to at this position*, not merely the single character.

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
its licence and source link (`SET-040`).

## 3. Supported platforms

`PROD-020` `[MUST]` First-class targets: **Android** (phone + tablet), **iOS** (iPhone + iPad),
**Web** (modern evergreen browsers, mobile and desktop viewport widths).

`PROD-021` `[MUST]` Product behaviour is identical across platforms except where recorded in a
"Platform differences" section of a feature spec. The intentional divergences are:
- **OCR and Voice are Android + iOS only** (`PROD-022`, `PROD-023`);
- **asset delivery**: mobile installs everything up front, Web fetches some data lazily (`PROD-070`).

`PROD-022` `[MUST]` On Web, the OCR and Voice destinations are **omitted entirely** from the
navigation (`PROD-050`), together with their launcher shortcuts and share targets. Routes to them
fall back to Search (`GLB-112`).

`PROD-023` `[MUST]` No dead affordance: Web shows no disabled OCR or Voice control and no "not
supported on web" toast. **Handwriting input is available on all platforms**; on Web its model is
downloaded the first time handwriting is opened, with a loading state, and cached for offline use
(`HW-001`…, `PROD-070`).

`PROD-024` `[MUST]` Desktop (macOS/Windows/Linux native) is **out of scope**. Wide-viewport web is
the desktop story.

`PROD-025` `[SHOULD]` Minimum platform versions: Android 8.0 (API 26); iOS 16; Web = last two
versions of Chrome/Edge/Safari/Firefox. *(Raising the Android minimum is `Q-092`.)*

`PROD-030` `[MUST]` Explicit non-goals for v1: desktop-native apps (`PROD-024`), accounts, cloud
sync, spaced repetition, note-taking, a system-wide input method (handwriting is an in-app input
method only), pronunciation scoring, and translation of full sentences by the app itself (it may hand
off to an external translator — `MED-030`).

**Deferred (post-v1)**
- Android screen-assistant integration (see the Deferred list in
  [06 — HSK and settings](06-hsk-and-settings.md)).
- Character origins (see the Deferred list in [04 — Entry and media](04-entry-and-media.md)).

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

`PROD-043` `[MUST]` Changing mode is a full data-context switch: it re-runs the active search,
rebuilds any open passage analysis, and clears the entry-detail stack. Persisted per-mode state is
kept separately so switching back restores the previous context (`SET-013`).

`PROD-044` `[SHOULD]` Simplified/Traditional script preference is **orthogonal** to mode and applies
to both. Cantonese defaults to Traditional; Mandarin defaults to Simplified. The user can override
either (`SET-011`).

`PROD-045` `[MUST]` Where a Cantonese entry has no Cantonese-sourced definition, Mandarin
definitions are shown. In Cantonese mode, every Mandarin-sourced definition is labelled as such,
whether or not the entry also has Cantonese-sourced senses (`DATA-022`). The app must never present
an empty entry merely because one source lacked a gloss.

## 5. Major features

| Feature | Spec | Notes |
|---------|------|-------|
| Search (Chinese / pronunciation / English) | [03 — Search](03-search.md) | The primary surface and home; abbreviated pinyin, combinations, Word of the Day and Settings cards |
| Entry detail (word page) | [04 — Entry and media](04-entry-and-media.md) | Definitions, related words, strokes, examples |
| Pronunciation playback & TTS | [04 — Entry and media](04-entry-and-media.md) | Recorded audio for words/syllables, TTS for sentences |
| Text analysis / Reading | [05 — Reading and input](05-reading-and-input.md) | Paste or receive a passage, tap through it |
| OCR | [05 — Reading and input](05-reading-and-input.md) | Primary destination; Android + iOS |
| Voice transcription | [05 — Reading and input](05-reading-and-input.md) | Primary destination; Android + iOS |
| Handwriting input | [05 — Reading and input](05-reading-and-input.md) | Search and Reading fields; all platforms |
| HSK browser | [06 — HSK and settings](06-hsk-and-settings.md) | Both dictionary modes |
| Settings & attribution | [06 — HSK and settings](06-hsk-and-settings.md) | Script, mode, theme, tone colour, pronunciation visibility, app language, boundaries, auto-save, credits |
| Platform integrations | [06 — HSK and settings](06-hsk-and-settings.md) | Share-image target, launcher shortcuts, home-screen widget |

## 6. Navigation model

`PROD-050` `[MUST]` A **flat primary navigation**, in this order:
- Android and iOS — five destinations: `Search`, `OCR`, `Voice`, `Reading`, `HSK`;
- Web — three destinations: `Search`, `Reading`, `HSK` (`PROD-022`).

Compact widths (< 600 dp / < 600 px) use a bottom navigation bar; medium and expanded widths use a
navigation rail on the leading edge.

**Settings is not a destination.** It is reached from the Settings card on the empty Search screen
(`SRCH-116`, `SET-005`).

`PROD-051` `[MUST]` The same destinations are shown in both dictionary modes; HSK is present in
Cantonese mode too (`HSK-090`…).

`PROD-052` `[MUST]` **OCR is a primary destination** (`CAM-006`…`CAM-009`). Selecting it opens
straight into the live camera feed (or the permission rationale when camera access is not granted —
`CAM-010`…`CAM-015`), offers an image-picker button for recognising an existing image, and keeps the
primary navigation visible. The camera is released when the user leaves the destination. The
launcher shortcut and the share-image intent select the OCR destination (`SET-050`, `SET-054`).

`PROD-053` `[MUST]` Tapping the already-selected primary destination scrolls that destination's
list/grid to the top (animated). OCR and Voice have no list to scroll: re-tapping OCR while a
capture is being reviewed returns to the live feed; re-tapping Voice has no effect.

`PROD-054` `[MUST]` **Entry detail is a stack**, not a destination. Selecting a word pushes a
detail route. Tapping a word inside a detail pushes another route. Back pops one route. At expanded
widths the list-detail two-pane layout applies (`GLB-125`), and a close action clears the whole
stack. Full rules: `ENT-010`…`ENT-018`.

`PROD-055` `[MUST]` Switching primary destination clears the entry-detail stack, dismisses any
open word-candidate sheet, and closes the handwriting sheet.

`PROD-056` `[MUST]` Back-navigation precedence, innermost first:
1. an open word-candidate bottom sheet dismisses (restoring the handwriting sheet if it hid it);
2. an open handwriting sheet closes;
3. on OCR, a capture under review returns to the live feed;
4. the entry-detail stack pops one route (Settings, opened from Search, pops the same way —
   `SET-005`);
5. the platform default (Android: leave the app; Web: browser history; iOS: no-op at root).

`PROD-057` `[SHOULD]` The app and szdict.com share one route table (`GLB-110`): the same routes
address the same screens in the app and on the web, browser back/forward and deep links work on Web,
and the installed app opens szdict.com links (`GLB-111`). Reading text, OCR and voice content are
never placed in URLs (`GLB-112`).

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

`PROD-070` `[MUST]` On **Android and iOS**, everything the app uses — the dictionary dataset and
indexes, recorded audio, stroke data, example sentences, the OCR model, the handwriting model, the
voice model and the language model for combinations — is installed with the app (Play: install-time
asset packs; direct APK and iOS: inside the package). There are no runtime downloads, and mobile
builds declare no `INTERNET` permission (`ARCH-104`…`ARCH-106`). A freshly installed app with the
network permanently disabled supports every feature: search in all input modes (including
abbreviated pinyin and combinations), entry detail with definitions, related words, strokes and
examples, pronunciation playback, HSK browsing, OCR, voice transcription, handwriting, text analysis
and settings.

On **Web**, the app and its dataset are stored for offline use on first visit (`PERF-060`). The
handwriting model and the abbreviation/combination data are fetched lazily on first use (opening
handwriting; the first multi-syllable pinyin query), without delaying single-word results, and
cached for offline use thereafter.

`PROD-071` `[MUST]` The app never requires the network for correctness. Network use is limited to:
- Android and iOS: none from the app itself. Attribution links open in the system browser, and text
  handed to an external translator (`MED-030`) is processed by that app.
- Web: loading the app and dataset, the lazy fetches in `PROD-070`, and opening attribution links
  and translator hand-offs in a new browser tab.

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
search keys, abbreviation keys, tone arrays, English tokenisation, folded forms, ASCII case handling,
character postings, sub-word (compound) relationships, variant/redirect resolution, HSK joins (both
modes), ranking metadata, the combination language model, and the maximum headword length. The
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
`graduate student` → English side is active by default → ranked results, exact-phrase matches first →
tap a card → entry detail with numbered senses, HSK badge, pronunciation button, related words.

`PROD-101` **Look up by pronunciation.** Type `nihao`, `ni3hao3`, `nǐ hǎo`, or abbreviated `nh` /
`nhao` → the app recognises parseable romanisation, with any mix of full and abbreviated syllables
(`SRCH-050`…`SRCH-054`), and switches to the Chinese side automatically when the input carries
explicit tones or characters or reads as abbreviated romanisation → exact-reading matches rank above
longer prefix matches, and fuller spellings above abbreviations → for multi-syllable input a
`Combinations` group offers the most likely multi-word sequences (e.g. `wxhn` → 我 喜欢 你), each
word individually tappable (`SRCH-060`…`SRCH-068`).

`PROD-102` **Paste a sentence into search.** Paste `我们研究生命科学` → the query is recognised as
Chinese text → a segmented passage card appears above the results with word boundaries underlined →
each matched word appears as a full result card, in text order → unmatched runs appear as inert
"no match" rows → tapping any character opens a bottom sheet of every dictionary word covering that
position, longest and most likely first.

`PROD-103` **Photograph a sign.** Tap the `OCR` destination → the live camera feed opens at once
(first time: permission rationale, then grant) → tap to focus, toggle flash → shutter, or pick an
existing image with the image-picker button → the photo is shown with recognised text boxed →
optionally the original photo is saved to the user's gallery → tap a character in the photo → the
word sheet opens → tap a word → entry detail. A `Text` view lists the recognised lines as tappable,
segmented passages. Re-tapping `OCR` returns to the live feed. Sharing an image to the app, or the
launcher shortcut, lands in the same destination.

`PROD-104` **Browse HSK.** HSK destination, in either dictionary mode → level chips → a level-tinted
masonry grid of word cards in pronunciation order (Jyutping in Cantonese mode) → tap a card → the
canonical dictionary entry, with the HSK level and part-of-speech shown per level.

`PROD-105` **Follow a definition into another word.** In an entry's definition, a Chinese reference
(e.g. `variant of 一點|一点[yi1 dian3]`) is rendered as a link → tapping it opens a sheet offering the
referenced entry plus every word contained inside it → selecting one pushes a nested detail route →
Back returns to the original word with its tab and scroll position intact.

`PROD-106` **Study a character.** Entry detail → `STROKES` tab → animated stroke order, then a
guided finger-trace quiz with per-stroke validation and hints after repeated misses.

`PROD-107` **Word of the day.** The empty Search screen shows a Word of the Day card (`SRCH-115`);
tapping it opens that entry. On Android, a home-screen widget (`SET-060`) shows one HSK word per day
drawn from the levels the user selected for that widget instance; tapping it opens that exact entry,
and a speaker button plays its pronunciation. Card and widget use the same deterministic daily pick
(`SET-063`).

`PROD-108` **Transcribe speech** (Android, iOS). Tap the `Voice` destination (first time:
microphone permission rationale, then grant) → speak Mandarin, Cantonese or English → live text
appears already segmented and tone-coloured and updates as words arrive; each finished utterance is
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
