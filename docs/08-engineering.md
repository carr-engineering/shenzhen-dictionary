# 08 — Engineering: Architecture, Performance and Release

This document has three parts: the **Technical Architecture Recommendation** (`ARCH-`), the
**Performance Targets** (`PERF-`) and **Release and Repository Practice** (`REL-`). The performance part
is the **only** home of numeric performance and size budgets; other documents reference `PERF-` IDs
instead of restating numbers.

## Technical Architecture Recommendation

Everything in this part is `[REC]` unless explicitly marked otherwise. It may be replaced with a
better-justified design provided the `[MUST]`/`[SHOULD]` requirements in documents 01–07 and the budgets
of the performance part below are met. Where a statement is **mandatory** (because product behaviour
depends on it) it is marked `[MUST]` and cross-references the behaviour it protects.

---

### 1. Cross-platform strategy

`ARCH-001` `[REC]` **Recommended: Kotlin Multiplatform with Compose Multiplatform** for Android, iOS
and Web (Wasm), sharing UI as well as logic.

Rationale: the reference app is already Compose Multiplatform, so all UI behaviour in these specs is
directly expressible; Compose for iOS and Wasm are production-viable; one UI implementation means the
"identical behaviour across platforms" requirement (`PROD-021`) is met by construction rather than by
discipline; and SQLite/serialisation/coroutines all have mature KMP stories.

`ARCH-002` `[REC]` Credible alternatives, with the trade-off that matters here:

| Option | Gain | Cost |
|--------|------|------|
| KMP + Compose Multiplatform | one UI, one logic, strongest behaviour parity | Compose Wasm bundle size; iOS text-rendering edge cases with CJK; smaller iOS talent pool |
| KMP core + native UI (SwiftUI / Compose / React) | best-in-class per-platform feel and accessibility | three UI implementations of a very interaction-heavy spec; parity becomes a testing problem |
| Flutter | one UI, mature web/mobile story | must re-derive all logic; Dart FFI for SQLite; no reuse of the reference behaviour |
| React Native / Expo | web reuse, large ecosystem | worst fit for the data layer and for 120 fps text interaction |

`ARCH-003` `[MUST]` Whatever is chosen, the **dictionary engine, ranking, segmentation, romanisation
parsing, abbreviation spelling, combination (lattice) decoding, and all view-state reduction must be
platform-shared**, because the behavioural specs are exact and duplicating them invites divergence.

`ARCH-004` `[REC]` Web deserves special attention on **bundle size and dataset delivery**. Compose Wasm
plus a WASM SQLite plus a dataset of the size in `PERF-070` is a large first visit. Recommendation: ship
the active mode's dataset to OPFS on first run behind a visible progress state, keep it current with
per-release changesets (`ARCH-145`), and treat the first visit as an install step; everything else is
fetched when needed and only browser-cached (`ARCH-143`).
If measurements show Compose Wasm cannot meet `PERF-073`, a thin React/Svelte web front-end over a
shared Wasm engine is the fallback (accepting a second UI implementation for Web only).

`ARCH-005` `[MUST]` **Web CJK font.** Compose Wasm draws text with Skia on a canvas and cannot use the
browser's or the system's fonts, so Chinese renders only from fonts the app itself loads. The Web
build uses **Compose Multiplatform's automatic web font fallback** (1.12+), which downloads Noto
subsets from a third-party font host when text needs them (decided: `Q-117`). The Web build bundles
**no** CJK font; the fallback font is fetched when needed, is not part of the app shell or the offline
set (`ARCH-143`, `ARCH-145`), and its host is on the Web network allow-list of `GLB-090`. Text whose glyphs have not arrived yet renders once they do; the app does not
block start-up on the font. Mobile builds use the platform fonts plus the bundled OFL rare-character
fallback font of `PERF-070` (`Q-109`).

---

### 2. Module boundaries

`ARCH-010` `[REC]` Layer as follows, with dependencies pointing downward only:

```
app-<platform>        entry point, DI wiring, platform integrations (widget, shortcuts; Android:
                      share and process-text intents, ARCH-090)
web-edge              Worker rendering the SEO word pages (ARCH-146); not part of the app bundle
  feature-search      search UI + its state reducer
  feature-entry       entry detail UI + route stack
  feature-hsk         HSK UI
  feature-camera      OCR destination UI (mobile only)
  feature-voice       Voice destination UI (mobile only)
  feature-reading     reading UI
  feature-handwriting handwriting sheet UI, used by Search and Reading
  feature-settings    Settings route and attribution
  ui-kit              palette, cards, passage renderer (incl. ruby readings), sheet, pills, skeletons
  domain              use cases: Search, AnalyseText, SpansAt, EntryDetail, HskList, AudioPlan,
                      HandwritingCandidates, Transcribe
  dict-engine         matching, ranking, segmentation, romanisation parsing, abbreviation spelling,
                      lattice decoder
  data-dictionary     dataset access, decoding, batching, caching
  data-assets         installed asset access and voice-pack state (mobile); dataset updater
                      (ARCH-145) and lazy, hash-checked fetch (Web)
  data-settings       persisted settings
  platform-ports      camera, text recogniser, handwriting recogniser, audio capture, speech
                      recogniser, voice-model pack, picker, photo-library writer,
                      audio, TTS, clipboard, translate
```

`ARCH-011` `[MUST]` `dict-engine` and `domain` must have **no** UI or platform dependencies and must be
testable on the JVM/host with a fixture dataset (`ARCH-151`).

`ARCH-012` `[REC]` The reference app's single 1823-line view model owning search, reading, camera,
detail, HSK, audio, TTS, clipboard and settings must not be reproduced. Split per feature, with one
small shared component owning cross-feature concerns (data generation, active mode/script, display
preferences, playback ownership — `ARCH-041`).

`ARCH-013` `[REC]` Keep the `feature-camera` module out of the Web build entirely (`PROD-023`) rather
than stubbing it, so its OCR dependency does not reach the Web bundle.

`ARCH-014` `[MUST]` `feature-voice` is a **mobile-only** module, excluded from the Web build together
with its native dependencies (sherpa-onnx, onnxruntime) and the voice model (`PROD-022`).
`feature-handwriting` ships on all platforms; on Web its model is fetched lazily
(`ARCH-093`, `ARCH-143`). Both modules depend on ports only (`ARCH-093`…`ARCH-095`), never on a platform SDK
directly.

---

### 3. Data-access layer

`ARCH-020` `[REC]` A single `DictionaryStore` interface over the dataset, exposing **exactly** the query
shapes the specs need — nothing generic:

```
open(datasetRef): DatasetHandle            // validates schema (DATA-003)
metadata(): DatasetMetadata
entriesByIds(ids): List<Entry>             // batched, cached
projectionsByIds(ids): List<RankProjection>
headwordExact(keys): Map<String, List<Id>> // batched
headwordPrefix(prefix, limit, offset)
headwordContains(query, limit, offset)
readingExact(key) / readingPrefix(key)
abbrevPostings(keys): Map<String, Postings> // batched, first-letter index (DATA-151)
lmUnigrams(wordIds): Map<WordId, LogProb>   // batched (DATA-152)
bigramBlocks(leftIds): Map<WordId, Block>   // batched (DATA-153)
englishPostings(token, prefix): Postings
charPostings(char): Postings
within(entryId): List<SubSpan>
mergeGroup(id): MergeGroup
frequencies(words): Map<String, FreqInfo>  // batched
hskRows(range): List<HskRow>
popular(): List<Id>
syllableInventory(): Inventory             // incl. abbreviation table (DATA-150)
```

`ARCH-021` `[MUST]` **Every** multi-key operation is batched. Single-key convenience methods must be
implemented in terms of the batch form so an accidental N+1 is impossible (`SRCH-152`, `TXT-014`).

`ARCH-022` `[MUST]` Every query is **cancellable at the driver level** (`GLB-045`).

`ARCH-023` `[REC]` Two caches only:
- a bounded LRU of hydrated entries (about 2000 entries, roughly a few MB);
- a per-request memo of headword and frequency lookups (bounded, discarded when the request ends).
No global unbounded caches. Both are cleared when the data generation changes.

`ARCH-024` `[REC]` A request-scoped context carries the cancellation signal and the memos, so a helper
deep in segmentation can consult the memo without threading parameters everywhere.

`ARCH-025` `[MUST]` Decoding a blob (senses, notes, postings) happens lazily, only for entries that will
be rendered (`DATA-143`).

`ARCH-026` `[REC]` The Web implementation is the same interface over WASM SQLite on OPFS. Because the
Web SQLite driver is often synchronous-in-a-worker, the store should run in a dedicated worker and expose
an async interface; the shared engine code must therefore be written against an async store, not a
synchronous one. On Web, `abbrevPostings`, `lmUnigrams` and `bigramBlocks` answer "not yet available"
until the lazily fetched data is present (`ARCH-143`); the engine then returns single-word results only.

---

### 4. Search / engine architecture

`ARCH-030` `[REC]` Pipeline per query, as pure stages:

```
raw input
  -> normalise (SRCH-010)                       pure, sync, microseconds
  -> classify (SRCH-020)                        pure, sync
  -> spell: token DAG (SRCH-050…054)            pure, sync
  -> plan: a list of candidate sources          pure, sync
  -> execute plan: batched store reads          async, cancellable
  -> assign match classes                       pure
  -> rank (SRCH-070) then cap (SRCH-071)        pure
  -> hydrate visible window only                async, batched
  -> project to view state                      pure
  -> lattice decode (TXT-026), when triggered   async: 2 batched reads, then pure k-best Viterbi
     (SRCH-060…068) -> Combinations group appended to the view state
```

The lattice decoder is **pure Kotlin in `commonMain`** — the same code on Android, iOS and Wasm; no
native IME library (librime, libime or similar) is linked at runtime. Single-word results are emitted
first and never wait for the decoder; the Combinations group is applied to the same request generation
when it completes (`PERF-025`). The same decoder serves text segmentation where `TXT-012` uses the
bigram model.

`ARCH-031` `[MUST]` Ranking operates on lightweight projections (id, match weight, frequency, length,
proper-noun flag) — never on hydrated entries (`DATA-078`).

`ARCH-032` `[MUST]` The cap is applied after ranking (`SRCH-071`).

`ARCH-033` `[REC]` Chinese-side and English-side execution run **concurrently**, since both are needed
for the segment counts. Cancelling the query cancels both, and the lattice decode with them.

`ARCH-034` `[REC]` Hydration is windowed: the list asks for the entries it is about to display. The
result is a list of ids plus a hydration function, not a list of fully-built entries.

`ARCH-035` `[MUST]` One in-flight search at a time, conflated to the latest (`GLB-041`). Implement as a
conflated channel plus latest-only collection, with request-generation guards on application of the
result (`GLB-040`) — cancellation alone must not be relied on.

`ARCH-036` `[REC]` Periodic cooperative cancellation checks inside long loops (ranking, intersection,
segmentation, lattice decoding) — for example every 1024 items — so a superseded query stops promptly.

---

### 5. State management

`ARCH-040` `[REC]` **Unidirectional**: per-feature state holders expose an immutable state snapshot;
the UI sends intents; the holder reduces and re-emits. No two-way binding, no mutable shared models.

`ARCH-041` `[REC]` Per-feature state holders (`SearchStateHolder`, `EntryStackHolder`, `HskHolder`,
`CameraHolder`, `VoiceHolder`, `ReadingHolder`, and a `HandwritingHolder` owned by whichever field
opened the handwriting sheet) plus a small `AppSessionHolder` owning: the persisted dictionary mode
(a linked route may carry a different mode of its own, `ARCH-053`), script, theme,
tone colours, boundaries, `show_pronunciation` (`GLB-120`, `SET-017`), `input_method` (`SET-019`),
`ui_language` (`GLB-102`, `SET-018`), data generation, and single-playback / audio-session ownership
(playback and TTS never overlap microphone capture, `MED-002`). `VoiceHolder` owns the transcript
(committed utterances plus one live utterance), the microphone state and the recogniser session; it
re-segments only the live utterance on each update (`VOICE-001`…).

`ARCH-042` `[MUST]` The **entry-detail route stack lives in state, not in a platform navigator**
(`ENT-010`). A platform navigator may mirror it for pane adaptation and browser history, but the stack
of routes and their content is app state.

`ARCH-043` `[MUST]` A route is removed from state only after its exit animation completes (`ENT-014`),
and pops are guarded by the expected route key so a double-back cannot pop two routes.

`ARCH-044` `[REC]` Model each async slice as an explicit `Idle | Loading | Ready | Error` value rather
than as parallel `isLoading`/`error`/`data` fields; this makes `GLB-030` (never blank populated content)
expressible as `Loading(previous)`.

`ARCH-045` `[MUST]` Data generation is part of every async request's identity (`GLB-044`).

---

### 6. Navigation

`ARCH-050` `[REC]` Primary destinations: a simple enum-driven switch, not a heavyweight navigation graph
— five on Android and iOS (`Search`, `OCR`, `Voice`, `Reading`, `HSK`) and three on Web (`Search`, `Reading`, `HSK`) per `PROD-050`, each holding state
that must survive switching. The enum is per platform build, so Web contains no OCR or Voice case
(`ARCH-013`, `ARCH-014`). **Settings is not a destination**: it is a full-screen route pushed over
Search from the Settings card (`SET-005`) and popped like an entry-detail route.

`ARCH-051` `[REC]` Detail presentation follows the single list-detail rule `GLB-125` (two-pane at
expanded width, single pane below); no other width threshold is defined here.

`ARCH-052` `[MUST]` Back handling is a **stack of handlers** with the precedence of `PROD-056`,
registered innermost-first: word-candidate sheet, then handwriting sheet, then OCR capture review, then
the entry-detail / Settings route stack, then the platform default. Closing the word sheet re-shows a
handwriting sheet it hid.

`ARCH-053` `[REC]` Routes and deep links follow the single route table `GLB-110`…`GLB-112`; the app does
not define URL shapes of its own. The route parser and builder live in shared code and are used by
in-app navigation, Web history, the widget, launcher shortcuts, and incoming links. Android App Links
and iOS Universal Links (`GLB-111`) are verified against `/.well-known/assetlinks.json` and
`/.well-known/apple-app-site-association`, both served from szdict.com and deployed by the release
workflow (`REL-040`). Every content route is `/{mode}/…` with the dictionary mode as its first path
segment (`/zh/…` Mandarin, `/yue/…` Cantonese, `GLB-110`); script is a viewer preference and never part
of the path. **Mode is route-scoped for links:** every route value records its mode, and a route opened
from a link (App Links / Universal Links, Web URLs, the widget) carries the link's mode — as do the
routes pushed from it — without changing the persisted dictionary mode (`GLB-110`, `PROD-043`,
`PROD-057`). Leaving that stack returns to the user's mode; only a real mode change by the user writes
the preference and clears the stack. URLs carry the **natural key** (`DATA-014`), never `entry_id`, and never passage, OCR,
voice or handwriting content (`GLB-112`). The edge-rendered word pages (`ARCH-146`, `GLB-113`) use
exactly the in-app word URLs, so one link serves search engines, the Web app and the installed apps.

`ARCH-054` `[MUST]` Saveable per-route state keyed by route key, released on pop
(`GLB-051`, `GLB-052`).

---

### 7. Text analysis architecture

`ARCH-070` `[REC]` Replace the reference app's engine-side **passage registry** (an integer passage id
resolved against an LRU inside the engine) with an immutable `AnalysedPassage` value passed by
reference. It carries its own text, segments, coverage bitset (`GLB-058`), and the data generation it
was produced in. `TXT-016` states the required behaviour; this is the recommended mechanism.

Rationale: passage ids are a hidden global mutable cache whose entries expire under LRU pressure and
whose identity silently becomes invalid when the dataset changes. The reference app needs explicit
guards in five places for exactly this. An immutable value removes the failure mode.

`ARCH-071` `[MUST]` Span-candidate lookup takes the passage value and an offset (`TXT-020`).

`ARCH-072` `[REC]` Cache analysed passages keyed by (mode, text, data generation) in a small LRU purely
as an optimisation; a cache miss must be indistinguishable from a hit apart from timing. A voice
transcript is a sequence of frozen `AnalysedPassage` values (committed utterances) plus one live value
replaced on every recogniser update; character offsets never move.

---

### 8. Platform ports

`ARCH-090` `[MUST]` One narrow interface per platform capability, defined in shared code, implemented
per platform, injected at the app layer:

| Port | Android | iOS | Web |
|------|---------|-----|-----|
| `CameraController` (preview, capture, focus point, flash) | CameraX | AVFoundation | absent |
| `TextRecognizer` | ML Kit Chinese (bundled model) | Vision | absent |
| `HandwritingRecognizer` (`ARCH-093`) | ML Kit Digital Ink, bundled model | ML Kit Digital Ink, bundled model | repackaged web model, lazily loaded |
| `AudioCapture` (`ARCH-094`) | AudioRecord | AVAudioEngine | absent |
| `SpeechRecognizer` (`ARCH-095`) | sherpa-onnx (AAR, JNI) | sherpa-onnx C API via Kotlin/Native | absent |
| `ImagePicker` | Photo Picker | PHPicker | file input |
| `PhotoLibraryWriter` | MediaStore | PHPhotoLibrary (add-only) | absent |
| `ImageDecoder` (upright, capped) | ImageDecoder / BitmapFactory + Exif | ImageIO | createImageBitmap |
| `AudioPlayer` (sequence of clips) | MediaPlayer / ExoPlayer | AVAudioPlayer | Web Audio |
| `Speaker` (TTS) | TextToSpeech | AVSpeechSynthesizer | SpeechSynthesis |
| `Clipboard` (read/write, focus-gated) | ClipboardManager | UIPasteboard | Async Clipboard API |
| `Translator` (hand-off, `MED-030`…) | `ACTION_PROCESS_TEXT` to Google Translate; web URL if not installed | Translation framework sheet (`.translationPresentation`); Google Translate web URL only when the language pair is unsupported | new tab to Google Translate |
| `SettingsStore` | DataStore | UserDefaults | IndexedDB / localStorage |
| `DatasetLocator` | install-time asset pack / APK assets | app bundle | OPFS (`ARCH-145`) |
| `VoiceModelPack` (state, request, progress, path — `VOICE-095`) | Play: on-demand asset pack via `AssetPackManager`; direct APK: always present | Background Assets `AssetPackManager`, Apple-hosted pack | absent |

Sharing into the app is **Android only** in v1 (decided: `Q-042`): the Android app layer handles the
incoming intents itself — `ACTION_SEND image/*` → OCR capture review (`SET-054`), `ACTION_SEND
text/plain` and `ACTION_PROCESS_TEXT` → Reading (`SET-056`, `TXT-080`), each consumed once per
`SET-055` — and dispatches them as ordinary intents to the feature state holders. iOS has no share
extension and Web no share target, so there is no shared share port. Shared content is never logged
(`GLB-092`).

`ARCH-091` `[MUST]` Each port has a no-op implementation used in tests and on platforms lacking the
capability, so shared code never branches on platform (`GLB-035`).

`ARCH-092` `[REC]` `AudioPlayer.play(clips)` is a suspending call that completes when the sequence
finishes or is cancelled, so "currently playing" is derived from the call's lifetime rather than tracked
separately.

`ARCH-093` `[MUST]` `HandwritingRecognizer`: `recognize(ink, model) → ranked candidates`, where `ink` is
the strokes of the current character (points with timestamps) and `model` is selected by mode and
script (simplified, traditional, Hong Kong — `HW-001`…). Android and iOS use the ML Kit Digital Ink
recogniser with the model files **bundled in the app** and loaded locally; the ML Kit model-download
path is never used. Web uses the same model repackaged for the web, fetched on first open of the
handwriting sheet (`ARCH-143`) with a loading state. Filtering candidates to dataset characters and
re-ranking them by dataset frequency happen in shared `domain` code, not in the port. Ink is held in
memory only and never logged or persisted (`GLB-092`).

`ARCH-094` `[MUST]` `AudioCapture`: start/stop a stream of **16 kHz mono PCM** frames. Android:
`AudioRecord` (voice-recognition source, resampled if the device cannot deliver 16 kHz); iOS:
`AVAudioEngine` input node with an `AVAudioConverter` to 16 kHz mono. Absent on Web. Capture stops when
the app is backgrounded or the Voice destination is left; samples are never written to storage
(`GLB-092`).

`ARCH-095` `[MUST]` `SpeechRecognizer`: a streaming session fed by `AudioCapture` that emits
**committed text** (an utterance closed by an endpoint), **live text** (the current utterance, append-only
under greedy decoding) and **endpoint events**. Android: sherpa-onnx via its Android AAR (JNI);
iOS: the sherpa-onnx C API called from Kotlin/Native (cinterop over the static framework built for
iOS). Model: the trilingual streaming Paraformer, int8 (`PERF-070`), located through
`VoiceModelPack` — the Play on-demand pack, the iOS Background Assets pack, or the copy bundled in the
direct APK (`ARCH-142`). While the pack is not present the session cannot start and Voice shows its
download state (`VOICE-095`, `GLB-035`). The session runs on its own background thread, loads the model
when Voice is entered and releases it when Voice is left. Absent on Web.

---

### 9. Permissions

`ARCH-100` `[MUST]` Permissions are requested **at the moment of use**, never at start-up: camera on
entering OCR (`CAM-010`), microphone on starting Voice (`VOICE-010`).

`ARCH-101` `[REC]` A shared `PermissionController` port with `status(kind)`, `request(kind)`, and
`openSettings()`, and a shared UI component rendering the three-state rationale panel (`CAM-011`), so
Android and iOS share the copy and the flow for camera and microphone alike.

`ARCH-102` `[MUST]` Status is re-read on every resume (`CAM-013`).

`ARCH-103` `[MUST]` Declared permissions are the minimum set of `CAM-017` and `ARCH-104`…`ARCH-107`.
The **microphone is used only by the Voice destination**; no other feature opens it. No broad
photo-library read, no location, no contacts, no network.

`ARCH-104` `[MUST]` The **merged** Android manifest of every mobile build contains exactly the
permissions of its flavour's allow-list (`ARCH-107`). Common to all flavours:
`android.permission.CAMERA`, `android.permission.RECORD_AUDIO`, and
`android.permission.WRITE_EXTERNAL_STORAGE` with `android:maxSdkVersion="28"`, used only by the legacy
photo-saving path on API 26–28 (`CAM-071`, `CAM-075`); API 29+ saves through `MediaStore` without a
permission. There is no `INTERNET` permission in any flavour (`PROD-070`, `GLB-090`): the Play voice
pack is downloaded by the Play Store, not by the app. `minSdkVersion` is **26** (decided: `Q-092`).

`ARCH-105` `[MUST]` Every permission or component contributed by a library through manifest merging
and not in the flavour's allow-list is removed in the app manifest with `tools:node="remove"`. Known
contributors at the time of writing:
- ML Kit Digital Ink Recognition merges `INTERNET` for its model download — removed; the bundled model
  is used (`ARCH-093`);
- ML Kit common pulls `transport-backend-cct` (Google `datatransport`), which merges `INTERNET` and its
  upload services and receivers — all removed;
- onnxruntime-android 1.29 and later merges `INTERNET`, `ACCESS_NETWORK_STATE` and a
  `TelemetryInitializer` provider — all three removed.
`[REC]` Also remove by default `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` and `FOREGROUND_SERVICE` (except
where `ARCH-107` allows it), and recheck the list on every dependency update, since the CI check of
`ARCH-106` fails on anything new.

`ARCH-106` `[MUST]` CI asserts the merged manifest's permission list **and** the merged
services, receivers and providers of each Android variant (Play AAB and direct APK) against its
allow-list in `ARCH-107`, and fails on any difference (`REL-030`). On iOS the app's `Info.plist`
declares exactly these usage strings: `NSCameraUsageDescription`, `NSMicrophoneUsageDescription` and
`NSPhotoLibraryAddUsageDescription` (add-only photo saving, `CAM-071`; decided: `Q-112`), each
translated per `GLB-100`. CI checks the plist keys the same way. The iOS deployment target is **iOS
26** (decided: `Q-116`).

`ARCH-107` `[MUST]` **Per-flavour allow-list** for the merged Android manifest:

| Flavour | Permissions | Additional library components |
|---------|-------------|-------------------------------|
| Play (AAB) | `CAMERA`, `RECORD_AUDIO`, `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion 28`), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Play Asset Delivery's extraction foreground service (type `dataSync`, disabled until used) and its receiver |
| Direct APK | `CAMERA`, `RECORD_AUDIO`, `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion 28`) | — |

Both flavours also keep the app's own components and reviewed AndroidX components (for example App
Startup's `InitializationProvider` and the profile-installer receiver); the complete list is a file in
the repository read by the CI check.

The two foreground-service permissions and components are merged by the Play Asset Delivery library
(asset-delivery 2.3), which is a Play-flavour-only dependency; they serve the on-demand voice pack
(`ARCH-142`) and nothing else. Because the Play flavour declares a foreground-service type, the Play
Console foreground-service declaration (data sync, for downloading the voice pack) is filed when Play
asks for it.

---

### 10. Settings and persistence

`ARCH-110` `[REC]` One `SettingsStore` port exposing an observable snapshot plus an atomic update, over
platform key-value storage. Settings are a **stream**, so a change from any source (screen, widget
config, toggle buttons such as `show_pronunciation`) reaches every consumer.

`ARCH-111` `[MUST]` Unknown/corrupt values fall back per key without wiping the store (`SET-012`).

`ARCH-112` `[MUST]` The search query is debounced for persistence and flushed on lifecycle stop
(`SRCH-140`).

`ARCH-113` `[MUST]` Per-mode keys (script, last query) are namespaced by mode (`SET-011`, `SET-013`).

---

### 11. Error handling

`ARCH-120` `[REC]` Domain results are explicit `Result`-style values with a small typed error set
(`DatasetUnavailable`, `SchemaMismatch`, `RecordMissing`, `Cancelled`, `RecogniserFailed`,
`RecogniserTimedOut`, `PermissionDenied`, `AssetMissing`, `ModelLoadFailed`, `AudioCaptureFailed`,
`FetchFailed` (Web fetch or dataset update), `PackDownloadFailed` (voice pack, `VOICE-095`),
`IoFailed`); the UI maps each to the copy of `GLB-034`. Exceptions are for programming errors only.

`ARCH-121` `[MUST]` Cancellation is **never** an error state and never surfaces to the user.

`ARCH-122` `[MUST]` A feature failure leaves that feature recoverable and non-busy (`GLB-034`) and does
not affect other features.

`ARCH-123` `[MUST]` A missing optional asset is not an error (`GLB-035`). On mobile every asset except
the voice model is installed, so this applies to the voice pack before it is downloaded (`VOICE-095`)
and to Web assets not yet fetched or not cached while offline.

`ARCH-124` `[MUST]` A missing dictionary record for a *linked* id (an HSK row, a redirect target) must
degrade that one row, not fail the screen (`HSK-067`).

---

### 12. Performance architecture

`ARCH-130` `[MUST]` No dictionary work on the UI thread (`GLB-046`). One shared background dispatcher
for dataset I/O with bounded parallelism, one for CPU-bound work (ranking, decoding, segmentation,
lattice decoding, handwriting recognition). Speech decoding runs on its own thread (`ARCH-095`).

`ARCH-131` `[MUST]` **No "load the dictionary" phase.** The reference app starts a 22-entry in-memory
seed engine, then opens SQLite on a background thread and swaps the whole engine, invalidating all state
and re-running everything. The successor opens the dataset directly; opening is a file open plus a
metadata read, fast enough to do before the first query and to await in the search path.

`ARCH-132` `[REC]` Search results are a virtualised list; cards are hydrated in the visible window plus
a small margin.

`ARCH-133` `[REC]` Precompute nothing at start-up. Everything the app needs at start is: settings, the
dataset handle, and the restored query's results. The voice model, the handwriting model and the OCR
recogniser are loaded only when their surface is first used and released when it is left.

`ARCH-134` `[MUST]` Header collapse, list scrolling and pager paging (including the EN/ZH result
pager, `SRCH-180`…`SRCH-184`) must not trigger re-measure storms (`ENT-022`).

`ARCH-135` `[REC]` Use baseline/startup profiles on Android and whatever equivalent AOT hinting the
chosen stack offers, since first-run JIT dominates cold search latency.

`ARCH-136` `[MUST]` Memory: no single contiguous allocation above the `PERF-080` limit; no
whole-dataset or whole-index in-memory structure (`DATA-148`); the entry cache is bounded
(`ARCH-023`). Two explicit, bounded exceptions: (a) the working bitmap of the current OCR capture,
capped at 2560 px on the long side (`CAM-026`), which is kept while the user visits other destinations
and released only when the capture is replaced or discarded (`CAM-008`, `CAM-093`); (b) the voice
session — model weights and recogniser buffers — held only while Voice is active and released when it
is left (`PERF-057`). Camera hardware and preview buffers are released whenever OCR is left.

---

### 13. Application size, delivery and distribution

`ARCH-140` `[MUST]` Ship the dataset **once** (`DATA-146`). Never bundle a raw source alongside a
processed artefact.

`ARCH-141` `[MUST]` **Mobile installs everything except the voice model** (`PROD-070`). Android and
iOS builds contain every other asset the app uses — both mode datasets with their language-model and
abbreviation data, recorded audio, strokes, example sentences, the OCR model (Android), the handwriting
models, the script-conversion table and the bundled fonts. The **voice model is the only separate
download**, made through the store's own mechanism the first time the user opens Voice (`VOICE-095`);
the direct APK bundles it. There is no in-app downloader and no app-initiated network request on
mobile.

`ARCH-142` `[MUST]` Packaging per flavour (sizes: `PERF-073`):
- **Play (AAB):** the base module holds code, resources and native libraries (per-ABI and per-density
  splits); the large assets other than the voice model — datasets, audio, strokes, sentences,
  handwriting models, fonts — go in **install-time asset packs**, installed with the app and keeping
  the base module within Play's size limit. The voice model is one **on-demand asset pack** (about
  240 MB, well under Play's 1.5 GB per-pack limit), requested through `AssetPackManager` when the user
  starts the download from the Voice destination. The Play Store performs the download, so the app
  needs no `INTERNET` permission (`ARCH-104`). The pack size is shown before the download is
  requested; on mobile data Play holds a download above 200 MB until Wi-Fi unless the user confirms,
  so the app calls `showConfirmationDialog()` when Play reports that state.
- **iOS:** everything except the voice model inside the app bundle. The voice model is an
  **Apple-hosted Background Assets** asset pack, downloaded through the BackgroundAssets
  `AssetPackManager` when the user starts the download from Voice. Apple-hosted packs require iOS 26,
  which is the iOS minimum, so Voice is available on every supported iOS version (decided:
  `Q-116`). The system asks before a cellular download above 200 MB. On-Demand Resources are not used (deprecated in the
  iOS 27 SDK).
- **Direct APK:** everything, including the voice model, inside the APK; one APK per ABI. Voice never
  shows a download state in this flavour.
- `[REC]` All mobile builds: R8 in full mode, resource shrinking, and locale stripping per `GLB-103`.
The ML Kit Chinese OCR model is bundled on Android only and must not reach the Web build (`ARCH-013`).

`ARCH-143` `[MUST]` **Web fetches the other assets when needed**, on first use: the handwriting model
(first open of the handwriting sheet), the abbreviation and combination data (first multi-syllable
pinyin query; single-word results never wait for it), and the media families at the granularity defined
by the data contract (`DATA-170`…`DATA-173`). Every fetched file is verified against the SHA-256
recorded in the dataset metadata or build manifest before use and discarded on mismatch. These files are
kept in the browser (HTTP or service-worker) cache but are **not guaranteed offline**: only the app
shell and the dataset are (`ARCH-145`). The Web CJK font is fetched by Compose's font fallback from its
third-party host when text needs it and is likewise not guaranteed offline (`ARCH-005`). A fetch that fails or cannot run offline shows the feature's loading,
absent or error state (`GLB-035`; recorded audio falls back to TTS, `MED-011`) and is retried on next
use, never in a loop.

`ARCH-144` `[MUST]` Build-only inputs are excluded from artefacts by an explicit allow-list
(`PIPE-066`). The reference build relies on an asset-name deny-list, which silently ships anything a
new directory adds.

`ARCH-145` `[MUST]` **Web PWA dataset update** (`PROD-070`, `DATA-216`, `PIPE-167`):
- The active mode's dataset lives in OPFS and is opened by sqlite-wasm with the `opfs-sahpool` VFS in
  the dedicated store worker (`ARCH-026`). On first install the app calls
  `navigator.storage.persist()`; if storage is not persisted and the browser evicts the dataset, the
  next visit repeats the first-visit download with its progress state.
- The service worker fetches a small per-mode **manifest** (current `dataset_version`, full-file size
  and SHA-256, and the available changesets with their base version, size and SHA-256) when the app
  starts and periodically while it is open; it never blocks start-up or search.
- When the manifest names a newer version and a changeset from the installed version, the updater
  downloads it, verifies its SHA-256, applies it (`DATA-216`) to a **copy** of the installed dataset,
  verifies the result's SHA-256 against the manifest, and then **swaps atomically** (the active-file
  pointer changes in one write; the old file is deleted afterwards). The page is notified, and the new
  dataset is adopted as a new data generation (`GLB-044`, `ARCH-045`) at the next request; other open
  tabs are told to reload.
- **Full download** replaces the changeset when the installed version is more than the manifest's
  supported number of versions behind, when no changeset exists, or when applying or verifying fails.
  `[REC]` Also prefer the full download when the changeset is larger than half the full file.
- A failed or interrupted update leaves the installed dataset untouched and in use; it is retried at
  the next check, never in a loop.
- Apart from the app shell (code only; the CJK font is not part of it, `ARCH-005`), the dataset is the
  **only** content kept offline (`ARCH-143`).

`ARCH-146` `[MUST]` **Edge-rendered SEO word pages** (`GLB-113`):
- A Cloudflare Worker on szdict.com answers `/zh/word/{natural-key}` and `/yue/word/{natural-key}` —
  exactly the in-app word routes (`GLB-110`) — with a server-rendered HTML page: headword in both
  scripts, readings, short definitions, HSK level, `lang` set to `zh-Hans`, `zh-Hant` or `yue`, a
  canonical URL per entry and mode, schema.org `DefinedTerm` markup, and a link that loads the Web
  app at the same URL. On a device with the app installed, App Links / Universal Links open the app
  instead (`GLB-111`); in a browser where the PWA is installed, its service worker answers the
  navigation with the app shell.
- `[REC]` The Worker reads a per-release **page table** produced by the pipeline (one row per entry and
  mode with the already-decoded fields the page shows) from D1, or from sharded files in static
  assets / R2; it does not run the dictionary engine.
- Responses carry a long `Cache-Control` for the edge and browsers, keyed to the dataset version so a
  release replaces them. Unknown keys return 404 with `noindex` and a link to Search; search result
  pages and every other route are not indexed. A **sitemap index** (at most 50,000 URLs per sitemap)
  lists every word page per mode.
- Pages set no cookies, carry no analytics and log nothing beyond the platform's defaults (`GLB-090`).
- Static pre-rendered files were rejected: with about 250,000 pages, hosting file-count limits rule
  them out (Cloudflare Pages / Workers static assets 20,000 files on the free plan and 100,000 on paid;
  Netlify about 25,000; GitHub Pages a 1 GB site limit).

---

### 14. Testability

`ARCH-150` `[REC]` Test pyramid:

| Level | What | Where |
|-------|------|-------|
| Unit (host) | normalisation, classification, romanisation parsing, abbreviation spelling (token DAG), lattice decoder, ranking comparator, merge logic, segment parsing, sub-span ordering, HSK sorting, audio-plan selection, stroke recognition, OCR hit-testing geometry, handwriting candidate filtering and re-ranking, transcript commit and live re-segmentation, route parsing (including route-scoped link mode, `ARCH-053`), widget word selection, Android share-intent routing, Web dataset update (changeset apply, hash check, swap, full-download fallback) | shared, no platform |
| Engine contract (host) | the golden-query suite of `PIPE-140` (including abbreviation and combination queries) against a small fixture dataset **and** against the real dataset in CI | shared |
| State-holder (host) | latest-only behaviour, generation guards, route stack transitions, error recovery, settings restoration, voice transcript updates from a scripted fake `SpeechRecognizer` | shared |
| UI (instrumented) | card layout, skeletons, sheet behaviour, handwriting sheet / word sheet interplay, tappable text offsets, tab/scroll restoration, back precedence | per platform |
| Build checks | merged-manifest permissions, locale list, artefact sizes (`REL-030`) | CI |
| Macrobenchmark | the budgets of the performance part (`PERF-101`) | Android first, iOS second |

`ARCH-151` `[MUST]` A **fixture dataset** small enough to live in the repository (a few hundred entries,
covering variants, redirects, HSK rows, multi-reading words, Latin headwords, supplementary-plane
characters, and a tiny bigram table) must be buildable by the pipeline in CI, so engine tests do not
need the full dataset.

`ARCH-152` `[MUST]` The concurrency requirements of `GLB-040` and `GLB-041` are testable and must be
tested with a deterministic virtual clock: an intentionally slow superseded query must not overwrite a
newer result; ten keystrokes plus backspaces must complete exactly one search; a non-cooperative
in-flight operation must not clobber newer state.

`ARCH-153` `[REC]` Snapshot-test the card and passage renderers per theme, per script and per UI
language, including tone colouring on and off, boundary underlines on and off, and pronunciation shown
and hidden (`GLB-120`), with and without ruby readings (`GLB-121`).

`ARCH-154` `[MUST]` The pipeline's tokenisation, folding, and reading-normalisation rules are pinned by
**shared test vectors** consumed by both the pipeline tests and the app tests (`PIPE-024`).

---

### 15. Accessibility implementation

`ARCH-160` `[MUST]` Interactive text (passages, definitions with links, example sentences) must expose
per-target semantics, not one opaque node (`GLB-084`). This is the single hardest accessibility problem
in the app and must be designed in, not retrofitted: a wrapping passage of tappable characters needs
per-character accessibility nodes with meaningful labels ("character X, tap for words at this
position").

`ARCH-161` `[MUST]` Respect the platform's reduce-motion and font-scale settings
(`GLB-082`, `GLB-085`).

`ARCH-162` `[REC]` Add an accessibility CI check for content labels on every icon-only control
(`GLB-080`), in all three UI languages.

---

### 16. Observability

`ARCH-170` `[REC]` Named trace sections around the stages of `PERF-100`, emitted in release builds via
the platform's low-overhead tracing, so the macrobenchmarks (`PERF-101`) can attribute latency without
a special build. Traces are **local only**: they are read by profilers and the developer screen and are
never uploaded.

`ARCH-171` `[REC]` A hidden developer screen, reachable **only** from Settings (long-press the version;
no route, shortcut or deep link), reporting: dataset id, version, schema version, content hash, entry
count, installed or fetched assets, cache hit rates, and the last N query timings. This replaced-by-nothing diagnostic is cheap and makes field reports
actionable.

`ARCH-172` `[MUST]` No telemetry: nothing in the app transmits behaviour, queries, OCR text, clipboard
content, images, audio, transcripts or handwriting (`GLB-090`, `GLB-092`).

`ARCH-173` `[MUST]` No crash reporting in v1. Crashes are diagnosed from user-submitted reports and the
developer screen; nothing is collected automatically.

---

### 17. Do not reproduce (reference-app mistakes)

The reference implementation these specs were derived from made the mistakes below. Each is prevented
by the cited requirement; this list exists so they are not reintroduced.

`ARCH-180` The two-phase seed-engine-then-swap start-up (`ARCH-131`).

`ARCH-181` Shipping the dataset twice and copying 90 MB on first run (`DATA-146`).

`ARCH-182` Runtime prose parsing, redirect resolution, merge-group computation, ASCII case expansion and
sub-span enumeration (`PIPE-100`).

`ARCH-183` Per-row JSON parsing on hot paths (`DATA-143`).

`ARCH-184` The full-index scan for substring matching (`DATA-074`).

`ARCH-185` The god view model (`ARCH-012`).

`ARCH-186` The engine-owned mutable passage registry (`ARCH-070`).

`ARCH-187` Unused reverse indexes costing 21.5 MB (`DATA-142`).

`ARCH-188` Eager hydration of up to 1500 related entries per entry open, and of about 11000 entries plus
their audio availability before the HSK list can render (`ENT-064`, `HSK-066`).

`ARCH-189` Computing per-character entry lists for every opened entry that no UI renders (`ENT-081`).

Further mistakes:
- Building a whole-dictionary in-memory index from one large read (it failed on a 33.5 MB contiguous
  allocation) (`DATA-148`, `PERF-081`).
- Shipping build inputs — the raw gzipped source bundle and a legacy audio directory — guarded only by
  an asset-name deny-list (`PERF-071`, `ARCH-144`, `PIPE-066`).
- ASCII case-insensitivity by enumerating up to 256 case permutations of the query (`DATA-071`,
  `DATA-072`).
- Deriving toneless readings by stripping trailing digits, which corrupts readings with literal digits
  such as `11區` (`PIPE-022`).
- One store query per candidate substring during segmentation (`TXT-014`, `ARCH-021`).
- Syllable inventory, alias map and popular-word list hard-coded in application source (`DATA-070`,
  `DATA-090`).
- A second, parallel engine implementation, plus dead state and code (loading-status strings no UI
  renders, unused components, and ports with nothing wired, such as a pronunciation-scoring
  placeholder) (`ARCH-020`; decided: `Q-094` — no such port in v1).
- Play buttons hard-enabled whether or not audio exists (`ENT-065`).
- HSK duplicate rows rendered twice, unlinked rows as dead cards, and one missing linked entry failing
  the whole screen (`HSK-027`, `HSK-056`, `ARCH-124`).
- Deep links by `entry_id`, which is not stable across dataset versions (`DATA-212`).

Deferred (post-v1): architecture for character origins (`PROD-030`); no `ARCH` or `PERF` IDs depend on
it.

## Performance Targets

Performance is an explicit goal of the rewrite. This part defines the budgets, the reference hardware,
and how each is measured. It is the only place numeric budgets are stated.

---

### 1. Reference conditions

`PERF-001` `[MUST]` **Reference device (Android):** a mid-range 2022 phone, roughly Pixel 6a class
(8 cores, 6 GB RAM, UFS 3.1) — the "reference phone" of the feature documents. **Reference device
(iOS):** iPhone SE 3rd generation. **Reference Web:** desktop Chrome on a 2020-class laptop, plus the
Android reference device's Chrome.

`PERF-002` `[MUST]` Budgets are stated for a **release build** with the dataset already installed and
the app warm (process alive, dataset opened) unless the budget says "cold".

`PERF-003` `[MUST]` Percentiles are over at least 20 iterations, reported as p50/p95, with the stated
hard ceiling never exceeded.

`PERF-004` `[MUST]` Every budget below is a CI-enforceable benchmark, not an aspiration. A regression
beyond 20% on p50 or beyond the ceiling fails the build.

**Note on reference-app measurements.** Figures attributed to the "reference app"
(`PERF-023`, `PERF-071`, `PERF-072`, and in feature documents) were measured once on the existing implementation,
mostly on a desktop host. They are **rationale only**: they explain why a budget or a data-layer rule
exists and how far the old design was from it. They are not targets and are not re-measured; the
successor is judged only against the budgets in this part. For scale, the reference app shipped a
90 MB dataset and copied it on first run (about 180 MB on device), bundled about 123 MB of media, and
occupied about 305 MB installed. The mistakes behind these numbers are listed in `ARCH-180`…`ARCH-189`
and the list that follows them.

---

### 2. Search latency

`PERF-020` `[MUST]` These rows define "typed-through" search (`PROD-080`, `SRCH-150`):

| Metric | p50 | p95 | Ceiling |
|--------|-----|-----|---------|
| Keystroke to results in state (`input_to_ready`) | 40 ms | 90 ms | 200 ms |
| Keystroke to first rendered frame (`input_to_frame`) | 60 ms | 120 ms | 250 ms |
| Blank-query (popular words) to frame | 30 ms | 60 ms | 120 ms |
| Side toggle or page swipe to frame (response reusable) | 16 ms | 32 ms | 80 ms |
| Worst-case query class (single common English token, e.g. `to`) | 90 ms | 150 ms | 300 ms |
| Pasted 100-character sentence to frame | 120 ms | 220 ms | 400 ms |

Abbreviated pinyin input (`SRCH-050`…`SRCH-054`) is held to the same rows for its single-word results.

`PERF-021` `[MUST]` Measured by driving a real query character by character through the UI and reading
the trace sections of `PERF-100`. Reference benchmark query: typing `zhongguore` one character at a
time, asserting the final state is the expected result count.

`PERF-022` `[MUST]` No frame in the typing sequence may exceed the display's frame budget (about 8 ms
of main-thread work at 120 Hz, 16 ms at 60 Hz). Jank frames during typing: zero at p95.

`PERF-023` `[MUST]` Evidence from the reference implementation, measured on a **desktop** host (the
phone is far slower), showing why the data-layer changes are mandatory rather than optional:

| Operation | Reference cost (desktop p50) |
|-----------|------------------------------|
| Headword exact lookup | 0.006 ms |
| Headword prefix (single char, 101 rows) | 0.023 ms |
| **Headword contains (full index scan)** | **5.6-6.2 ms** |
| Reading prefix (`shi`, 1200 rows) | 0.47 ms |
| English prefix (`to`, 5000 postings) | 3.6 ms |
| **English ranked to 1200 (`to`)** | **19.5 ms** |
| Hydrate 1200 entries, one query each, with JSON decode | 7.3 ms |
| Hydrate 1200 entries, batched 500, with JSON decode | 2.1 ms |
| Full corrected English pipeline for `to` (33817 candidates) | 83.6 ms |
| HSK: read 11105 rows and JSON-decode | 16.8 ms |
| Segment a 100-char passage, one query per candidate | 9.2 ms |
| Segment a 100-char passage, batched | 0.34 ms |
| Segment a 500-char passage, one query per candidate | 48.8 ms |
| Segment a 500-char passage, batched | 1.0 ms |

A Hanzi query performs the substring scan **twice** (contains, contains-reversed), and entry detail a
third time — about 18 ms of pure scan on a desktop, plausibly 150-400 ms on the reference phone.

`PERF-024` `[MUST]` Post-change component targets for the same operations on the **reference phone**:
substring match at most 5 ms; English ranked at most 40 ms; batched hydration of one visible window
(about 20 entries) at most 3 ms. Passage segmentation has no separate number: it is part of the
`Analyse` rows of `PERF-050`.

`PERF-025` `[MUST]` **Abbreviated pinyin and combinations** on the reference phone
(`SRCH-050`…`SRCH-054`, `SRCH-060`…`SRCH-068`, `TXT-026`):

| Metric | p50 | p95 | Ceiling |
|--------|-----|-----|---------|
| Keystroke to Combinations group in state (`dict.lattice` included) | 20 ms | 80 ms | 200 ms |

Single-word results do **not** wait for the decoder: they meet `PERF-020` on their own, and the
Combinations group is added when ready without moving content above it. On Web the first
multi-syllable query's lazy fetch of the combination data (`ARCH-143`) is excluded from this budget;
single-word results are not delayed by it.

---

### 3. Entry detail

`PERF-030` `[MUST]`

| Metric | p50 | p95 | Ceiling |
|--------|-----|-----|---------|
| Tap to skeleton visible | 1 frame | 1 frame | 2 frames |
| Tap to content rendered | 60 ms | 120 ms | 250 ms |
| Tab switch (already loaded) | 1 frame | 2 frames | 3 frames |
| Nested route push to content | 60 ms | 120 ms | 250 ms |
| Back to previous route restored (tab + scroll) | 1 frame | 2 frames | 3 frames |
| Definition-reference tap to sheet populated | 50 ms | 100 ms | 200 ms |

`PERF-031` `[MUST]` The first page of related words counts toward "content rendered"; later pages must
not.

---

### 4. HSK

`PERF-040` `[MUST]` The single home of HSK budgets (`HSK-065` references it):

| Metric | p50 | p95 | Ceiling |
|--------|-----|-----|---------|
| Destination tap to first frame (cold, first visit) | 150 ms | 300 ms | 600 ms |
| Destination tap to first frame (warm, cached model) | 16 ms | 40 ms | 80 ms |
| Level chip tap to that level on screen | 1 frame + scroll | | |

`PERF-041` `[MUST]` The cold path must not read all 11105 rows, hydrate about 11000 entries, or compute
audio availability before the first frame (`HSK-066`). The first frame requires only the rows in the
first viewport.

---

### 5. Text analysis

`PERF-050` `[MUST]` The single home of text-analysis budgets (`TXT-060` references it):

| Metric | p50 | p95 | Ceiling |
|--------|-----|-----|---------|
| Analyse 100 characters | 15 ms | 30 ms | 60 ms |
| Analyse 500 characters | 60 ms | 120 ms | 250 ms |
| Tap a character to sheet populated | 30 ms | 60 ms | 120 ms |
| Step to adjacent covered character (`GLB-058`) | 20 ms | 45 ms | 100 ms |

---

### 6. Camera / OCR

`PERF-055` `[MUST]`

| Metric | p50 | p95 | Ceiling |
|--------|-----|-----|---------|
| OCR destination selected to first preview frame (permission granted) | 300 ms | 600 ms | 1200 ms |
| Shutter tap to captured image on screen | 250 ms | 500 ms | 1000 ms |
| Tap-to-focus to reticle drawn | 1 frame | 2 frames | 3 frames |
| Capture to recognition overlay drawn (8 MP source) | 700 ms | 1300 ms | 2500 ms |
| Recognition to all lines segmented | 150 ms | 300 ms | 600 ms |
| Save to photo library (background, non-blocking) | 300 ms | 800 ms | 3000 ms |
| Tap a glyph in the image to sheet populated | 40 ms | 80 ms | 150 ms |
| Pinch/pan | 60 fps sustained | | |

`PERF-056` `[MUST]` The 20 s recognition timeout (`CAM-050`) stays as a backstop but must never be
reached under the reference conditions.

`PERF-057` `[MUST]` **Voice** (`VOICE-001`…), on the reference phone:

| Metric | Budget |
|--------|--------|
| Entering Voice to model loaded and listening (cold) | ≤ 3 s |
| Speech onset to first live text shown | ≤ 1.2 s |
| Recogniser update to live utterance re-segmented and re-coloured | within the `Analyse 100 characters` row of `PERF-050` |
| Peak memory during a voice session (Android PSS) | ≈ 450 MB — an explicit exception to `PERF-080` |
| Leaving Voice to model released | memory back to steady state within one GC |

Updates never block a frame (`PERF-022`); a live utterance longer than 100 characters is not expected
because endpointing commits it (`ARCH-095`).

`PERF-058` `[MUST]` **Handwriting** (`HW-001`…): candidate row refreshed **≤ 50 ms** (p95) after each
finished stroke, measured as `hw.recognize` plus filtering and re-ranking. On Web the first open's lazy
model fetch (`ARCH-143`) is excluded and shown as a loading state.

---

### 7. Start-up

`PERF-060` `[MUST]`

| Metric | p50 | p95 | Ceiling |
|--------|-----|-----|---------|
| Cold start to interactive search field (Android) | 400 ms | 700 ms | 1200 ms |
| Cold start to restored query's results rendered | 600 ms | 1000 ms | 1800 ms |
| Warm start to interactive | 120 ms | 250 ms | 500 ms |
| Dataset open (file open plus metadata read) | 10 ms | 30 ms | 80 ms |
| Web first visit to interactive (dataset already in OPFS) | 1.5 s | 3 s | 5 s |
| Web first-ever visit including dataset download (assume 20 Mbit/s) | 12 s | 25 s | — |

`PERF-061` `[MUST]` There is **no** visible "loading dictionary" phase (`ARCH-131`), and cold start
loads none of the voice, handwriting or OCR models (`ARCH-133`).

---

### 8. Size

`PERF-070` `[MUST]` Line-item budgets (arm64-v8a for native code; MB = 10^6 bytes):

| Item | Budget | Ships in |
|------|--------|----------|
| Core app: code, resources (3 UI languages, `GLB-103`) | 25 MB download | all mobile builds |
| Web code (initial payload, no dataset) | 4 MB compressed | Web |
| Dataset, per mode (entries and indexes, excluding the items below), installed | 16 MB | mobile: both modes; Web: active mode |
| Language model + abbreviation index, per mode (`DATA-150`…`DATA-155`) | ≤ 4 MB | mobile: in the dataset; Web: lazy |
| Recorded audio (words plus syllables) | 65 MB | mobile; Web: lazy |
| Strokes | 20 MB re-encoded | mobile; Web: lazy |
| Example sentences (Mandarin, and Cantonese per `MED-048`) | 6 MB | mobile; Web: lazy |
| OCR model (ML Kit Chinese) | 20 MB | Android only (iOS uses Vision) |
| Handwriting models (simplified, traditional, Hong Kong) | 30 MB total (estimate; re-baseline at first integration) | mobile; Web: repackaged, one model per script, lazy |
| Voice model (trilingual streaming Paraformer, int8: encoder, decoder, tokens) | ~240 MB | Play: on-demand asset pack; iOS: Background Assets pack; direct APK: bundled (`ARCH-142`) |
| sherpa-onnx + onnxruntime native libraries | 10–15 MB per ABI | mobile only |
| Script conversion table for voice display (OpenCC) | 1 MB | mobile only |
| Rare-character fallback font (OFL CJK, subset to dataset characters the platform fonts may lack; text tagged `zh-Hans` / `zh-Hant` / `zh-HK` — decided: `Q-109`) | ≤ 8 MB (estimate; re-baseline when the subset is built) | all mobile builds |

`PERF-071` `[MUST]` The raw gzipped source bundle (6.3 MB in the reference build) must not ship.

`PERF-072` `[MUST]` Unused indexes must not ship (21.5 MB in the reference build — `DATA-142`).

`PERF-073` `[MUST]` Per-flavour budgets; CI fails a build that exceeds them (`REL-030`):

| Flavour | Contents | Download | Installed |
|---------|----------|----------|-----------|
| Play AAB — base module (arm64-v8a split) | core app, OCR model, sherpa-onnx + onnxruntime | 60 MB | 80 MB |
| Play AAB — install-time asset packs | both datasets with LM data, audio, strokes, sentences, handwriting models, conversion table, fallback font | 180 MB | 180 MB |
| Play AAB — as installed from the store | base + install-time packs, without the voice model | 240 MB | 260 MB |
| Play AAB — on-demand voice pack (`ARCH-142`, `VOICE-095`) | voice model | 240 MB | 240 MB |
| Direct APK (one per ABI; arm64-v8a measured) | everything, voice model included | 480 MB | 500 MB |
| iOS (App Store, thinned for the device) | everything except the OCR model and the voice model | 230 MB | 250 MB |
| iOS — Background Assets voice pack | voice model | 240 MB | 240 MB |
| Web — initial payload | code only | 4 MB compressed | — |
| Web — first visit, one mode | code + active-mode dataset into OPFS | 20 MB | 20 MB in OPFS |
| Web — dataset update per release (`ARCH-145`) | changeset (`DATA-216`); the full dataset when the changeset would exceed half of it | ≤ 50% of the dataset line | in place in OPFS |
| Web — on first use | LM + abbreviation data, handwriting model, media (`ARCH-143`) | per `PERF-070` line | browser cache, not guaranteed offline |

The voice model is the largest single asset. Play and iOS installs leave it out and fetch it once,
through the store's own delivery, when the user first opens Voice; from then on Voice works offline.
The direct APK carries it, so it has no download step.

---

### 9. Memory

`PERF-080` `[MUST]`

| Metric | Budget |
|--------|--------|
| Steady-state after search and browsing (Android PSS) | 150 MB |
| Peak during OCR of an 8 MP image | 320 MB |
| Peak during a voice session | `PERF-057` (≈ 450 MB) |
| Largest single contiguous allocation | 8 MB, except the capped OCR analysis bitmap (`CAM-026`, about 20 MB at 2560 × 1920) and the voice model and recogniser buffers (`PERF-057`) — `ARCH-136` |
| Entry cache | bounded, about 2000 entries |
| After leaving OCR | camera hardware and preview buffers released; steady state plus at most the retained capture bitmap (`CAM-008`, `CAM-093`) within one GC |
| After leaving Voice | back to steady state within one GC (`PERF-057`) |

`PERF-081` `[MUST]` No whole-dataset or whole-index in-memory structure (`DATA-148`). The reference
app's earlier in-memory build failed with a 33.5 MB contiguous-allocation failure and retained hundreds
of MB.

`PERF-082` `[MUST]` Web: no more than 300 MB of Wasm heap steady-state; the dataset is read from OPFS,
never loaded whole.

---

### 10. Instrumentation

`PERF-100` `[MUST]` Named trace sections, emitted in release builds and kept on the device (`ARCH-170`):

| Section | Covers |
|---------|--------|
| `dict.input_to_ready` | async: keystroke to results applied to state |
| `dict.input_to_frame` | async: keystroke to the frame that shows them |
| `dict.coalescing` | time spent in the coalescing window |
| `dict.classify` | normalisation plus classification |
| `dict.store_read` | all dataset reads for the request |
| `dict.rank` | candidate ranking |
| `dict.hydrate` | batched entry hydration |
| `dict.decode` | blob decoding |
| `dict.merge_group` | variant grouping |
| `dict.lattice` | abbreviation spelling plus combination decoding (`PERF-025`) |
| `dict.segment` | segmentation and sub-span enumeration |
| `dict.state_emit` | state reduction and emission |
| `dict.hsk_build` | HSK model build |
| `dict.hsk_tab_to_frame` | async: destination tap to first HSK frame |
| `dict.entry_open` | async: tap to entry content rendered |
| `dict.ocr` | recognition |
| `hw.recognize` | handwriting recognition of the current ink (`PERF-058`) |
| `voice.decode` | speech decoding of one audio chunk (`PERF-057`) |
| `voice.resegment` | re-segmentation and re-colouring of the live utterance |

`PERF-101` `[MUST]` The macrobenchmark suite asserts the budgets above using these sections plus frame
timing. Minimum suite: rapid typing, abbreviated and multi-syllable typing (for example `nhao`,
`wxhn`), blank-query start, entry open plus nested push plus back, HSK cold and warm entry,
paste-and-analyse, camera capture to overlay, a scripted handwriting stroke sequence, and voice replay
of a recorded fixture through `AudioCapture`'s test implementation.

`PERF-102` `[SHOULD]` Also track the **candidate-set sizes** per query class in the report (for example
33817 candidates for `to`), because a ranking regression usually shows up there first.

## Release and Repository Practice

The app, the dataset pipeline and its pinned sources live in one public repository
(`PIPE-160`…`PIPE-166`). This part defines how versions are named, how changes reach `main`, and how a
tag becomes a release on every platform.

---

### 1. Versions

`REL-001` `[MUST]` One **date-based version** per release, shared by all platforms: `YYYY.MM.DD-X`,
where the date is the day the release is cut (UTC) and `X` is a counter starting at 1 for that date
(`2026.10.24-1`). `X` is at most 99. The dataset version is separate (`PIPE-160`…`PIPE-166`); Settings
shows both (`SET-040`).

`REL-002` `[MUST]` **Android:** `versionName = "2026.10.24-1"`; `versionCode = YYYYMMDD × 100 + X`
(`2026102401`). This is monotonic and stays under Google Play's 2,100,000,000 limit through 2099. The
Play and direct-APK builds of a release carry the same values.

`REL-003` `[MUST]` **iOS:** `CFBundleShortVersionString = "2026.10.24"` (App Store versions allow only
period-separated integers, so `-1` cannot appear) and `CFBundleVersion = "X"` (`"1"`). If App Store
Connect rejects zero-padded components, iOS drops the padding (`2026.1.5`); comparison is numeric
either way. **Web:** the build embeds the full version string.

`REL-004` `[MUST]` **Git tag** = the full version (`2026.10.24-1`), annotated and signed, on the exact
commit built. Pushing such a tag is the only release trigger (`REL-040`).

---

### 2. Branching (public repository)

`REL-010` `[MUST]` **Trunk-based development.** One long-lived branch, `main`, always releasable.
There is no `develop` branch and there are no long-lived release branches.

`REL-011` `[MUST]` `main` is protected: changes arrive only by pull request (no direct pushes, admins
included); the required CI checks of `REL-030` must pass on a branch up to date with `main`; at least one
approving review is required (from `CODEOWNERS` for owned paths); merges are **squash merges** only,
keeping a linear history; release tags are signed.

`REL-012` `[MUST]` **Hotfixes** are made only when `main` contains unreleasable work. Branch from the
release tag (`hotfix/2026.10.24`), fix through a PR into that branch, tag `2026.10.24-2` (same date,
next counter, so it sorts after the release it replaces), then cherry-pick the fix into `main` by PR
and delete the hotfix branch. Otherwise the fix lands on `main` and ships in the next release.

---

### 3. Local workflow

`REL-020` `[SHOULD]` Contributors and maintainers work on **short-lived branches** named `feat/…`,
`fix/…`, `docs/…` or `data/…`, created from a freshly fetched `main`; rebase onto `main` instead of
merging it in; open a **draft PR early**; delete the branch after merge; use **git worktrees** for
parallel work instead of switching branches in one checkout. Nobody commits directly to `main`, locally
or remotely.

---

### 4. Continuous integration on pull requests

`REL-030` `[MUST]` Required checks on every PR (GitHub Actions):
1. build all targets: Play AAB, direct APK, iOS (simulator and device archive), Web, and the
   word-page Worker (`ARCH-146`);
2. unit, state-holder and engine tests (`ARCH-150`, `ARCH-151`);
3. dataset build when its inputs changed (otherwise the cached artefact keyed by input hash), with the
   validation gates, the golden queries (`PIPE-140`) and the LM quality gate (`PIPE-070`…`PIPE-076`);
4. merged-manifest permission and component check against the per-flavour allow-list, and iOS
   `Info.plist` usage-string check (`ARCH-106`, `ARCH-107`);
5. locale check: each artefact ships exactly `en`, `zh-Hans`, `zh-Hant`, with every string key
   translated (`GLB-100`, `GLB-103`);
6. size check of each flavour against `PERF-073`;
7. licence check with REUSE, and attribution completeness (`REL-052`);
8. DCO sign-off on every commit (`REL-051`).

`REL-031` `[SHOULD]` Dependency updates arrive as PRs from **Renovate or Dependabot** (grouped, with
pinned versions and lock files) and pass the same checks; data-source updates arrive through the
scheduled pipeline PR (`PIPE-160`…`PIPE-166`).

---

### 5. Release on tag

`REL-040` `[MUST]` A signed version tag (`REL-004`) starts the release workflow, which:
1. builds the dataset from the tagged commit (or reuses the CI artefact with the same input hash);
2. builds and signs the **direct APK** (everything bundled, voice model included, one per ABI) and the
   **AAB** with its install-time asset packs and the on-demand voice pack (`ARCH-142`), and uploads the
   AAB to the Play **internal** track;
3. builds iOS with **fastlane** and uploads it to **TestFlight**, and uploads the Apple-hosted Background
   Assets voice pack to App Store Connect when its version changed (`ARCH-142`);
4. deploys the Web build to **szdict.com**, including `/.well-known/assetlinks.json` and
   `/.well-known/apple-app-site-association` with path patterns for the `/zh/…` and `/yue/…` routes
   (`GLB-110`, `GLB-111`), and `robots.txt`;
5. publishes the Web dataset artefacts: the full dataset per mode, the changeset from each supported
   earlier version (`DATA-216`, `PIPE-167`) and the manifest (`ARCH-145`) — the manifest last, after
   every file it names is in place;
6. deploys the word-page Worker and its per-release page table (`ARCH-146`), then publishes the
   **sitemap index** and its sitemaps (`GLB-113`);
7. creates a **GitHub Release** with the direct APKs, a `SHA256SUMS` file, a **build-provenance
   attestation** for each artefact, and release notes generated from the labels of the merged PRs.
Promotion beyond the internal track and TestFlight is a manual step.

`REL-041` `[MUST]` **One Android signing key** signs both the Play and the direct-APK builds: Play App
Signing is set up with the project's own app-signing key (not a Google-generated one), and the same key
signs the direct APK, so `assetlinks.json` lists a single certificate fingerprint (decided: `Q-113`).
Signing material (that key, the Play upload key, the Play service account, the App Store Connect API
key, the fastlane match passphrase, and the deploy token for szdict.com and the Worker) is stored only
as secrets of a **protected GitHub Environment** that requires maintainer approval and is available
only to tag-triggered release jobs. PR workflows never see it, and no key is committed.

`REL-042` `[MUST]` **Large binaries are kept out of git history**: the voice and handwriting models,
the source language model and corpora, and other large third-party inputs are fetched by CI from
pinned, checksummed storage listed in the lock file (`PIPE-160`…`PIPE-166`), cached by checksum, and a
SHA-256 mismatch fails the build. `[REC]` A pre-commit or CI guard rejects new files above a few MB.

`REL-043` `[SHOULD]` Release builds are reproducible from the tag: toolchain versions are pinned
(Gradle wrapper with checksum, JDK, Kotlin, Xcode), and the published SHA-256 sums let anyone compare a
rebuild.

---

### 6. Repository hygiene

`REL-050` `[MUST]` The repository contains: `LICENSE` — the app's own code and assets under
**Apache-2.0** (decided: `Q-100`); datasets and models are separate files under their own licences
(CC BY-SA 4.0 dictionaries and the derived dataset, the LGPL-2.1+ language-model table as a separate
replaceable file, the Arphic stroke data, the CC BY-SA Cantonese tables), each with its licence text
alongside and listed in the attribution source of `REL-052` — `README`, `CONTRIBUTING` (workflow of `REL-020`, DCO, how to run the pipeline),
`CODE_OF_CONDUCT`, `SECURITY.md` (private vulnerability reporting), issue and PR templates,
`CODEOWNERS`, `CHANGELOG` (assembled from release notes), and REUSE licence metadata for every file
including committed data sources.

`REL-051` `[MUST]` Every commit carries a **DCO sign-off** (`Signed-off-by:`), checked in CI.

`REL-052` `[MUST]` **Attributions** for data sources, models and runtime libraries are generated from
**one** machine-readable source in the repository, which feeds the Settings credits and licence dialog
(`SET-040`, `SET-043`), the README credits and the release notes. CI fails when a shipped asset or
dependency has no entry.
