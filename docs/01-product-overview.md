# 01 — Product Overview / Master Product Specification

## 1. Purpose

`PROD-001` `[MUST]` The product is an **offline-first Chinese–English dictionary and reading aid**.
Its core promise: any Chinese text a learner encounters — typed, pasted, photographed, or found
inside another dictionary definition — can be turned into accurate, tappable, pronounceable
dictionary entries in one or two gestures, with no network access.

`PROD-002` `[MUST]` The product serves two primary audiences simultaneously and must not privilege
one at the expense of the other:

- **Learners** looking up words, browsing HSK vocabulary, checking pronunciation and tones.
- **Readers** encountering unsegmented running text (signs, menus, articles, subtitles) who need
  the text broken into words and each word explained.

## 2. Design philosophy

`PROD-010` `[MUST]` **Offline, complete, and instant.** The entire dictionary, its indexes, HSK
data, and pronunciation assets ship with the app or are downloaded once. No search, entry view,
segmentation, or HSK browse operation may require the network. Nothing in the UI may block on I/O
that could have been precomputed.

`PROD-011` `[MUST]` **Everything Chinese is tappable.** Any Chinese character rendered anywhere in
the app — search results, definitions, example sentences, OCR output, pasted passages, character
decompositions — is a live entry point into the dictionary. A tap resolves *what word this character
belongs to at this position*, not merely the single character.

`PROD-012` `[MUST]` **Show the reading, always.** Pronunciation (Mandarin Pinyin or Cantonese
Jyutping, per mode) is displayed next to every headword, and tone information is available as
optional colour on both the characters and the romanisation.

`PROD-013` `[SHOULD]` **Ink-and-paper visual language.** Warm paper neutrals, near-black ink, a
single seal-red accent, hairline borders, no heavy elevation or chrome. Light and dark variants of
the same palette. Content scrolls edge-to-edge beneath translucent system bars with a soft scrim
rather than opaque bars. Concrete tokens: `GLB-060`.

`PROD-014` `[SHOULD]` **Never blank the screen for work in progress.** Loading states either keep
the previous content visible or present a shape-matched skeleton; they never replace populated
content with a spinner. See `GLB-030`.

`PROD-015` `[MUST]` **Latest intent wins.** Fast typing, rapid tapping, and quick navigation must
never produce results from a stale request. See `GLB-040`.

`PROD-016` `[SHOULD]` **Explain provenance.** Every bundled dataset is credited in-app with its
licence and source link (`SET-040`).

## 3. Supported platforms

`PROD-020` `[MUST]` First-class targets: **Android** (phone + tablet), **iOS** (iPhone + iPad),
**Web** (modern evergreen browsers, mobile and desktop viewport widths).

`PROD-021` `[MUST]` Product behaviour is identical across platforms except where recorded in a
"Platform differences" section of a feature spec. The only large intentional divergence is the
camera/OCR feature.

`PROD-022` `[MUST]` **Camera/OCR is Android + iOS only.** On Web, the camera entry points are
absent (not disabled-with-error). Web keeps the gallery/file entry point only if a Web OCR engine is
adopted; by default `PROD-023` applies.

`PROD-023` `[MUST]` On Web, the OCR access cards on the empty search screen are replaced by nothing
(the section is omitted). No dead affordance, no "not supported on web" toast.

`PROD-024` `[MUST]` Desktop (macOS/Windows/Linux native) is **out of scope**. Wide-viewport web is
the desktop story.

`PROD-025` `[SHOULD]` Minimum platform versions: Android 8.0 (API 26); iOS 16; Web = last two
versions of Chrome/Edge/Safari/Firefox.

`PROD-030` `[MUST]` Explicit non-goals for v1: accounts, cloud sync, spaced repetition, note-taking,
handwriting input method, speech recognition, pronunciation scoring, translation of full sentences
by the app itself (it may hand off to a system translator — `MED-040`).

## 4. Dictionary modes (Mandarin / Cantonese)

`PROD-040` `[MUST]` The app supports two **dictionary modes**: `MANDARIN` and `CANTONESE`. Exactly
one is active at a time. The mode is a persisted user setting (`SET-010`).

`PROD-041` `[MUST]` Mode selection changes:
- which dataset is queried (`DATA-010`);
- which romanisation is displayed and accepted as pronunciation input (Pinyin vs Jyutping);
- which tone palette applies (5 Mandarin tones vs 6 Cantonese tones — `GLB-064`);
- which definition sources appear and in what order (`ENT-050`);
- whether the HSK feature is offered (`HSK-002`).

`PROD-042` `[MUST]` Mode selection **does not** change: navigation structure, search interaction
model, segmentation interaction model, camera workflow, entry-detail layout, settings, or any other
screen structure. The application logic is romanisation-agnostic; all language-specific knowledge
lives in data (`DATA-070`).

`PROD-043` `[MUST]` Changing mode is a full data-context switch: it re-runs the active search,
rebuilds any open passage analysis, and clears the entry-detail stack. Persisted per-mode state is
kept separately so switching back restores the previous context (`SET-013`).

`PROD-044` `[SHOULD]` Simplified/Traditional script preference is **orthogonal** to mode and applies
to both. Cantonese defaults to Traditional; Mandarin defaults to Simplified. The user can override
either (`SET-011`).

`PROD-045` `[MUST]` Where a Cantonese entry has no Cantonese-sourced definition, Mandarin
definitions are shown, labelled by provenance (`ENT-052`). The app must never present an empty entry
merely because one source lacked a gloss.

## 5. Major features

| Feature | Spec | Notes |
|---------|------|-------|
| Search (Chinese / pronunciation / English) | `03` | The primary surface; also the app's home |
| Entry detail (word page) | `04` | Definitions, related words, strokes, origins, examples |
| HSK browser | `05` | Mandarin mode only |
| Camera / OCR lookup | `06` | Android + iOS |
| Text analysis / Reading | `07` | Paste or receive a passage, tap through it |
| Pronunciation playback & TTS | `08` | Recorded audio for words/syllables, TTS for sentences |
| Settings & attribution | `09` | Script, mode, theme, tone colour, boundaries, auto-save, credits |
| Platform integrations | `09` | Share-image target, launcher shortcuts, home-screen widget, assistant |

## 6. Navigation model

`PROD-050` `[MUST]` A **flat primary navigation** of four destinations, in this order:
`Search`, `Reading`, `HSK`, `Settings`.

- Compact widths (< 600 dp / < 600 px): bottom navigation bar.
- Medium and expanded widths: navigation rail on the leading edge.

`PROD-051` `[MUST]` In Cantonese mode the `HSK` destination is hidden entirely (`HSK-002`),
leaving three destinations.

`PROD-052` `[MUST]` **Camera is not a primary destination.** It is reached from access cards on the
empty Search screen, from a launcher shortcut, from a share-image intent, and from the assistant
integration. It presents as a full-screen modal surface over the current destination, entering with a
horizontal slide from the trailing edge, and hides the primary navigation while open.

`PROD-053` `[MUST]` Tapping the already-selected primary destination scrolls that destination's
list/grid to the top (animated).

`PROD-054` `[MUST]` **Entry detail is a stack**, not a destination. Selecting a word pushes a
detail route. Tapping a word inside a detail pushes another route. Back pops one route. On expanded
widths a list-detail two-pane layout shows the list and the top detail route side by side, and a
close action clears the whole stack. Full rules: `ENT-010`…`ENT-018`.

`PROD-055` `[MUST]` Switching primary destination clears the entry-detail stack and dismisses any
open word-candidate sheet.

`PROD-056` `[MUST]` Back-navigation precedence, innermost first:
1. an open word-candidate bottom sheet dismisses;
2. the camera surface returns to the viewfinder (if reviewing a camera capture) or exits to Search;
3. the entry-detail stack pops one route;
4. the platform default (Android: leave the app; Web: browser history; iOS: no-op at root).

`PROD-057` `[SHOULD]` On Web, the primary destination, the entry-detail stack, and the active
search query are reflected in the URL so browser back/forward and deep links work (`ARCH-140`).

## 7. Global behaviour and persistence

`PROD-060` `[MUST]` Persisted across launches: dictionary mode, script, theme mode, tone colouring,
word-boundary display, camera auto-save, the last active search side, and the last search query.
Full list and semantics: `SET-010`.

`PROD-061` `[MUST]` Restored on launch: the last search query is re-executed so the user returns to
where they left off. The entry-detail stack, camera state, and reading passage are **not** restored
across process death (`SET-014`).

`PROD-062` `[MUST]` Preserved across configuration change / rotation / process recreation within a
session: active destination, entry-detail stack including each route's selected tab, header collapse
and per-tab scroll position, search query and scroll position, camera capture and its recognition
result. See `GLB-050`.

## 8. Offline expectations

`PROD-070` `[MUST]` A cold-installed app with the network permanently disabled must support: search
in all input modes, entry detail with definitions/related words/strokes/origins/examples,
pronunciation playback, HSK browsing, camera OCR, text analysis, and settings.

`PROD-071` `[MUST]` The app never requires the network for correctness. Network use is limited to:
optional asset-pack download, opening an attribution link in the browser, and handing text to an
external translator.

## 9. Performance goals (summary)

`PROD-080` `[MUST]` Search must feel **typed-through**: results for each keystroke render within one
or two frames of the keystroke on a mid-range 2022 phone. Budget table and measurement method:
[13 — Performance](13-performance.md).

`PROD-081` `[MUST]` Cold start to an interactive, searchable field must not depend on loading the
dictionary into memory. There is no "loading the full dictionary" phase visible to the user; the
reference app's seed-engine-then-swap behaviour is removed (`ARCH-050`, finding `F-041`).

`PROD-082` `[MUST]` Installed size for one dictionary mode with core indexes must be a small
fraction of the reference app's ~180 MB on-device dictionary footprint. Target: `PERF-060`.

## 10. Relationship to the external data pipeline

`PROD-090` `[MUST]` The application does **not** parse, join, reconcile, normalise, or clean raw
dictionary sources at runtime. It consumes a **pre-built, versioned, immutable dataset** whose exact
shape is defined in [10 — Data Contract](10-data-contract.md).

`PROD-091` `[MUST]` The dataset and the app are independently implementable. The app knows only the
contract; the pipeline knows only the contract. Neither may assume the other's internals.

`PROD-092` `[MUST]` Everything deterministic is precomputed: romanisation variants, plain/toneless
search keys, tone arrays, English tokenisation, folded forms, ASCII case handling, character
postings, sub-word (compound) relationships, variant/redirect resolution, HSK joins, ranking
metadata, and the maximum headword length. The runtime is reduced to indexed lookup, ranking of
already-scored candidates, and rendering. Migration list: `PIPE-100`.

`PROD-093` `[MUST]` The pipeline is **not** implemented as part of this specification work, but its
inputs, outputs, joins, and validation gates are specified in
[11 — Data Pipeline](11-data-pipeline.md) in enough detail to implement independently.

`PROD-094` `[MUST]` Dataset versioning is explicit and checked at open time; a mismatched dataset is
a hard, reported error, never a silent partial read (`DATA-200`).

## 11. Key user journeys

`PROD-100` **Look up an English word.** Open app → Search (already focused destination) → type
`graduate student` → English side is active by default → ranked results, exact-phrase matches first →
tap a card → entry detail with numbered senses, HSK badge, pronunciation button, related words.

`PROD-101` **Look up by pronunciation.** Type `nihao`, `ni3hao3`, or `nǐ hǎo` → the app recognises
parseable romanisation and switches to the Chinese side automatically when the input carries explicit
tones or characters → exact-reading matches rank above longer prefix matches.

`PROD-102` **Paste a sentence into search.** Paste `我们研究生命科学` → the query is recognised as
Chinese text → a segmented passage card appears above the results with word boundaries underlined →
each matched word appears as a full result card, in text order → unmatched runs appear as inert
"no match" rows → tapping any character opens a bottom sheet of every dictionary word covering that
position, longest and most likely first.

`PROD-103` **Photograph a sign.** Search → *Scan with camera* → grant permission → tap to focus,
toggle flash → shutter → the photo is shown with recognised text boxed → optionally the original
photo is saved to the user's gallery → tap a character in the photo → the word sheet opens → tap a
word → entry detail. A `Text` view lists the recognised lines as tappable, segmented passages.

`PROD-104` **Browse HSK.** HSK destination → level chips → a level-tinted masonry grid of word cards
in pronunciation order → tap a card → the canonical dictionary entry, with the HSK level and
part-of-speech shown per level.

`PROD-105` **Follow a definition into another word.** In an entry's definition, a Chinese reference
(e.g. `variant of 一點|一点[yi1 dian3]`) is rendered as a link → tapping it opens a sheet offering the
referenced entry plus every word contained inside it → selecting one pushes a nested detail route →
Back returns to the original word with its tab and scroll position intact.

`PROD-106` **Study a character.** Entry detail → `STROKES` tab → animated stroke order, then a
guided finger-trace quiz with per-stroke validation and hints after repeated misses.

`PROD-107` **Word of the day.** A home-screen widget (Android) shows one HSK word per day drawn from
the levels the user selected for that widget instance; tapping it opens that exact entry; a speaker
button plays its pronunciation.
