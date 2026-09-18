# 12 — Technical Architecture Recommendation

Everything in this document is `[REC]` unless explicitly marked otherwise. It may be replaced with a
better-justified design provided the `[MUST]`/`[SHOULD]` requirements in documents 01-11 and the budgets
in document 13 are met. Where a statement is **mandatory** (because product behaviour depends on it) it
is marked `[MUST]` and cross-references the behaviour it protects.

---

## 1. Cross-platform strategy

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
parsing, and all view-state reduction must be platform-shared**, because the behavioural specs are
exact and duplicating them invites divergence.

`ARCH-004` `[REC]` Web deserves special attention on **bundle size and dataset delivery**. Compose Wasm
plus a WASM SQLite plus a 10-16 MB dataset is a large first visit. Recommendation: ship the dataset to
OPFS on first run behind a visible progress state, cache it with a service worker, and treat the first
visit as an install step. If measurements show Compose Wasm cannot meet `PERF-070`, a thin React/Svelte
web front-end over a shared Wasm engine is the fallback (accepting a second UI implementation for Web
only).

---

## 2. Module boundaries

`ARCH-010` `[REC]` Layer as follows, with dependencies pointing downward only:

```
app-<platform>        entry point, DI wiring, platform integrations (widget, shortcuts, assistant)
  feature-search      search UI + its state reducer
  feature-entry       entry detail UI + route stack
  feature-hsk         HSK UI
  feature-camera      camera/OCR UI (mobile only)
  feature-reading     reading UI
  ui-kit              palette, cards, passage renderer, sheet, pills, skeletons
  domain              use cases: Search, AnalyseText, SpansAt, EntryDetail, HskList, AudioPlan
  dict-engine         matching, ranking, segmentation, romanisation parsing
  data-dictionary     dataset access, decoding, batching, caching
  data-assets         asset-pack access
  data-settings       persisted settings
  platform-ports      camera, recogniser, picker, photo-library writer, audio, TTS, clipboard, translate
```

`ARCH-011` `[MUST]` `dict-engine` and `domain` must have **no** UI or platform dependencies and must be
testable on the JVM/host with a fixture dataset (`ARCH-160`).

`ARCH-012` `[REC]` The reference app's single 1823-line view model owning search, reading, camera,
detail, HSK, audio, TTS, clipboard and settings must not be reproduced. Split per feature, with one
small shared component owning cross-feature concerns (data generation, active mode/script, playback
ownership).

`ARCH-013` `[REC]` Keep the `feature-camera` module out of the Web build entirely (`PROD-023`) rather
than stubbing it, so its OCR dependency does not reach the Web bundle.

---

## 3. Data-access layer

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
englishPostings(token, prefix): Postings
charPostings(char): Postings
within(entryId): List<SubSpan>
mergeGroup(id): MergeGroup
frequencies(words): Map<String, FreqInfo>  // batched
hskRows(range): List<HskRow>
popular(): List<Id>
syllableInventory(): Inventory
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
synchronous one.

---

## 4. Search / engine architecture

`ARCH-030` `[REC]` Pipeline per query, as pure stages:

```
raw input
  -> normalise (SRCH-010)                       pure, sync, microseconds
  -> classify (SRCH-020)                        pure, sync
  -> plan: a list of candidate sources          pure, sync
  -> execute plan: batched store reads          async, cancellable
  -> assign match classes                       pure
  -> rank (SRCH-070) then cap (SRCH-071)        pure
  -> hydrate visible window only                async, batched
  -> project to view state                      pure
```

`ARCH-031` `[MUST]` Ranking operates on lightweight projections (id, match weight, frequency, length,
proper-noun flag) — never on hydrated entries (`DATA-078`).

`ARCH-032` `[MUST]` The cap is applied after ranking (`SRCH-071`).

`ARCH-033` `[REC]` Chinese-side and English-side execution run **concurrently**, since both are needed
for the segment counts. Cancelling the query cancels both.

`ARCH-034` `[REC]` Hydration is windowed: the list asks for the entries it is about to display. The
result is a list of ids plus a hydration function, not a list of fully-built entries.

`ARCH-035` `[MUST]` One in-flight search at a time, conflated to the latest (`GLB-041`). Implement as a
conflated channel plus latest-only collection, with request-generation guards on application of the
result (`GLB-040`) — cancellation alone must not be relied on.

`ARCH-036` `[REC]` Periodic cooperative cancellation checks inside long loops (ranking, intersection,
segmentation) — for example every 1024 items — so a superseded query stops promptly.

---

## 5. State management

`ARCH-040` `[REC]` **Unidirectional**: per-feature state holders expose an immutable state snapshot;
the UI sends intents; the holder reduces and re-emits. No two-way binding, no mutable shared models.

`ARCH-041` `[REC]` Per-feature state holders (`SearchStateHolder`, `EntryStackHolder`, `HskHolder`,
`CameraHolder`, `ReadingHolder`) plus a small `AppSessionHolder` owning: dictionary mode, script, theme,
tone colours, boundaries, data generation, and single-playback ownership.

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

## 6. Navigation

`ARCH-050` `[REC]` Primary destinations: a simple enum-driven switch, not a heavyweight navigation
graph — there are four of them and they hold state that must survive switching.

`ARCH-051` `[REC]` Detail presentation: an adaptive list-detail layout at medium and expanded widths,
full-screen at compact (`ENT-015`).

`ARCH-052` `[MUST]` Back handling is a **stack of handlers** with the precedence of `PROD-056`,
registered innermost-first, so the sheet wins over the route stack which wins over the platform.

`ARCH-053` `[REC]` On Web, mirror `(destination, query, detail stack depth, top entry natural key)` into
the URL and the history stack so browser back/forward behaves and links are shareable (`PROD-057`).
Use the **natural key** (`DATA-014`), not `entry_id`, in URLs.

`ARCH-054` `[MUST]` Saveable per-route state keyed by route key, released on pop (`GLB-051`,
`GLB-052`).

---

## 7. Text analysis architecture

`ARCH-070` `[REC]` Replace the reference app's server-side **passage registry** (an integer passage id
resolved against an LRU inside the engine) with an immutable `AnalysedPassage` value passed by
reference. It carries its own text, segments, and the data generation it was produced in.

Rationale: passage ids are a hidden global mutable cache whose entries expire under LRU pressure and
whose identity silently becomes invalid when the dataset changes. The reference app needs explicit
guards in five places for exactly this. An immutable value removes the failure mode.

`ARCH-071` `[MUST]` Span-candidate lookup takes the passage value and an offset (`TXT-020`).

`ARCH-072` `[REC]` Cache analysed passages keyed by (mode, text, data generation) in a small LRU purely
as an optimisation; a cache miss must be indistinguishable from a hit apart from timing.

---

## 8. Platform ports

`ARCH-090` `[MUST]` One narrow interface per platform capability, defined in shared code, implemented
per platform, injected at the app layer:

| Port | Android | iOS | Web |
|------|---------|-----|-----|
| `CameraController` (preview, capture, focus point, flash) | CameraX | AVFoundation | absent |
| `TextRecognizer` | ML Kit Chinese | Vision | absent |
| `ImagePicker` | Photo Picker | PHPicker | file input |
| `PhotoLibraryWriter` | MediaStore | PHPhotoLibrary (add-only) | absent |
| `ImageDecoder` (upright, capped) | ImageDecoder / BitmapFactory + Exif | ImageIO | createImageBitmap |
| `AudioPlayer` (sequence of clips) | MediaPlayer / ExoPlayer | AVAudioPlayer | Web Audio |
| `Speaker` (TTS) | TextToSpeech | AVSpeechSynthesizer | SpeechSynthesis |
| `Clipboard` (read/write, focus-gated) | ClipboardManager | UIPasteboard | Async Clipboard API |
| `Translator` (hand-off) | intents | translation sheet | absent |
| `SettingsStore` | DataStore | UserDefaults | IndexedDB / localStorage |
| `DatasetLocator` | assets / files dir | bundle / Documents | OPFS |

`ARCH-091` `[MUST]` Each port has a no-op implementation used in tests and on platforms lacking the
capability, so shared code never branches on platform (`GLB-035`).

`ARCH-092` `[REC]` `AudioPlayer.play(clips)` is a suspending call that completes when the sequence
finishes or is cancelled, so "currently playing" is derived from the call's lifetime rather than tracked
separately.

---

## 9. Permissions

`ARCH-100` `[MUST]` Permissions are requested **at the moment of use**, never at start-up
(`CAM-010`).

`ARCH-101` `[REC]` A shared `PermissionController` port with `status(kind)`, `request(kind)`, and
`openSettings()`, and a shared UI component rendering the three-state rationale panel (`CAM-011`), so
Android and iOS share the copy and the flow.

`ARCH-102` `[MUST]` Status is re-read on every resume (`CAM-013`).

`ARCH-103` `[MUST]` Declared permissions are the minimum set of `CAM-017`. No microphone, no broad
photo-library read, no location, no contacts.

---

## 10. Settings and persistence

`ARCH-110` `[REC]` One `SettingsStore` port exposing an observable snapshot plus an atomic update, over
platform key-value storage. Settings are a **stream**, so a change from any source (screen, widget
config) reaches every consumer.

`ARCH-111` `[MUST]` Unknown/corrupt values fall back per key without wiping the store (`SET-012`).

`ARCH-112` `[MUST]` The search query is debounced for persistence and flushed on lifecycle stop
(`SRCH-140`).

`ARCH-113` `[MUST]` Per-mode keys (script, last query) are namespaced by mode (`SET-011`, `SET-013`).

---

## 11. Error handling

`ARCH-120` `[REC]` Domain results are explicit `Result`-style values with a small typed error set
(`DatasetUnavailable`, `SchemaMismatch`, `RecordMissing`, `Cancelled`, `RecogniserFailed`,
`RecogniserTimedOut`, `PermissionDenied`, `AssetMissing`, `IoFailed`); the UI maps each to the copy of
`GLB-034`. Exceptions are for programming errors only.

`ARCH-121` `[MUST]` Cancellation is **never** an error state and never surfaces to the user.

`ARCH-122` `[MUST]` A feature failure leaves that feature recoverable and non-busy (`GLB-034`) and does
not affect other features.

`ARCH-123` `[MUST]` A missing optional asset is not an error (`GLB-035`).

`ARCH-124` `[MUST]` A missing dictionary record for a *linked* id (an HSK row, a redirect target) must
degrade that one row, not fail the screen (`HSK-067`).

---

## 12. Performance architecture

`ARCH-130` `[MUST]` No dictionary work on the UI thread (`GLB-046`). One shared background dispatcher
for dataset I/O with bounded parallelism, one for CPU-bound work (ranking, decoding, segmentation).

`ARCH-131` `[MUST]` **No "load the dictionary" phase.** The reference app starts a 22-entry in-memory
seed engine, then opens SQLite on a background thread and swaps the whole engine, invalidating all state
and re-running everything (finding `F-041`). The successor opens the dataset directly; opening is a file
open plus a metadata read, fast enough to do before the first query and to await in the search path.

`ARCH-132` `[REC]` Search results are a virtualised list; cards are hydrated in the visible window plus
a small margin.

`ARCH-133` `[REC]` Precompute nothing at start-up. Everything the app needs at start is: settings, the
dataset handle, and the restored query's results.

`ARCH-134` `[MUST]` Header collapse, list scrolling and pager paging must not trigger re-measure storms
(`ENT-022`).

`ARCH-135` `[REC]` Use baseline/startup profiles on Android and whatever equivalent AOT hinting the
chosen stack offers, since first-run JIT dominates cold search latency.

`ARCH-136` `[MUST]` Memory: no allocation of a single contiguous buffer above a few MB; no
whole-dataset or whole-index in-memory structure (`DATA-148`); the entry cache is bounded
(`ARCH-023`); bitmaps are capped at 2560 px on the long side and released on exit (`CAM-026`,
`CAM-093`).

---

## 13. Application size

`ARCH-140` `[MUST]` Ship the dataset **once** (`DATA-146`). Never bundle a raw source alongside a
processed artefact.

`ARCH-141` `[REC]` Make audio, strokes, origins and sentences downloadable packs (`PROD-071`). Reference
sizes: 65 MB audio, 30 MB strokes, 22 MB origins, 5.5 MB sentences — about 123 MB of optional assets.

`ARCH-142` `[REC]` On Android use per-ABI and per-density splits, R8 in full mode, and resource
shrinking; consider Play Asset Delivery for packs. On iOS use On-Demand Resources. On Web, lazy-load
each pack.

`ARCH-143` `[REC]` The bundled OCR model is about 15-20 MB on Android; keep it, but confirm it is not
also pulled into the Web build (`ARCH-013`).

`ARCH-144` `[MUST]` Build-only inputs are excluded from artefacts by an explicit allow-list
(`PIPE-066`). The reference build relies on an asset-name deny-list, which silently ships anything a
new directory adds.

---

## 14. Testability

`ARCH-150` `[REC]` Test pyramid:

| Level | What | Where |
|-------|------|-------|
| Unit (host) | normalisation, classification, romanisation parsing, ranking comparator, merge logic, segment parsing, sub-span ordering, HSK sorting, audio-plan selection, stroke recognition, OCR hit-testing geometry, widget word selection | shared, no platform |
| Engine contract (host) | the golden-query suite of `PIPE-140` against a small fixture dataset **and** against the real dataset in CI | shared |
| State-holder (host) | latest-only behaviour, generation guards, route stack transitions, error recovery, settings restoration | shared |
| UI (instrumented) | card layout, skeletons, sheet behaviour, tappable text offsets, tab/scroll restoration, back precedence | per platform |
| Macrobenchmark | the budgets of document 13 | Android first, iOS second |

`ARCH-151` `[MUST]` A **fixture dataset** small enough to live in the repository (a few hundred entries,
covering variants, redirects, HSK rows, multi-reading words, Latin headwords, supplementary-plane
characters) must be buildable by the pipeline in CI, so engine tests do not need the full dataset.

`ARCH-152` `[MUST]` The concurrency requirements of `GLB-040` and `GLB-041` are testable and must be
tested with a deterministic virtual clock: an intentionally slow superseded query must not overwrite a
newer result; ten keystrokes plus backspaces must complete exactly one search; a non-cooperative
in-flight operation must not clobber newer state.

`ARCH-153` `[REC]` Snapshot-test the card and passage renderers per theme and per script, including tone
colouring on and off and boundary underlines on and off.

`ARCH-154` `[MUST]` The pipeline's tokenisation, folding, and reading-normalisation rules are pinned by
**shared test vectors** consumed by both the pipeline tests and the app tests (`PIPE-024`).

---

## 15. Accessibility implementation

`ARCH-160` `[MUST]` Interactive text (passages, definitions with links, example sentences) must expose
per-target semantics, not one opaque node (`GLB-084`). This is the single hardest accessibility problem
in the app and must be designed in, not retrofitted: a wrapping passage of tappable characters needs
per-character accessibility nodes with meaningful labels ("character X, tap for words at this
position").

`ARCH-161` `[MUST]` Respect the platform's reduce-motion and font-scale settings (`GLB-082`,
`GLB-085`).

`ARCH-162` `[REC]` Add an accessibility CI check for content labels on every icon-only control
(`GLB-080`).

---

## 16. Observability

`ARCH-170` `[REC]` Named trace sections around the stages of `PERF-100`, emitted in release builds via
the platform's low-overhead tracing, so the macrobenchmarks in document 13 can attribute latency without
a special build.

`ARCH-171` `[REC]` A hidden developer screen (long-press the version in Settings) reporting: dataset id,
version, schema version, content hash, entry count, available packs, cache hit rates, and the last N
query timings. This replaced-by-nothing diagnostic is cheap and makes field reports actionable.

`ARCH-172` `[MUST]` No behavioural telemetry by default; nothing that could transmit queries, OCR text,
clipboard content, or images (`GLB-090`, `GLB-092`).

`ARCH-173` `[REC]` Crash reporting is acceptable if opt-in and if breadcrumbs carry no user content.

---

## 17. Explicitly rejected from the reference implementation

`ARCH-180` The two-phase seed-engine-then-swap start-up (`ARCH-131`, finding `F-041`).

`ARCH-181` Shipping the dataset twice and copying 90 MB on first run (`DATA-146`, finding `F-010`).

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
