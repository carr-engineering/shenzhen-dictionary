# 09 — Open Questions and Decisions

**These must be reviewed by a human before implementation.** Each item states what is unclear, why it
cannot be inferred confidently, the options, and a recommended resolution. The recommendation may be
adopted as-is, but it is a recommendation, not a decision.

Items are ordered by how much downstream work they block. Section 0 records questions that have been
decided; their original text is kept below with a **Decided** line so the reasoning stays visible.

---

## 0. Decision log (2026-10-10 review)

| Item | Decision | Applied in |
|------|----------|-----------|
| `Q-030` Cantonese HSK | HSK is shown in **both** modes; Cantonese mode joins HSK words to the Cantonese dataset and shows Jyutping | `HSK-002`, `HSK-090`…, `DATA-187`, `PROD-051` |
| `Q-040` Flash persistence | Session only (as recommended) | `CAM-038` |
| `Q-041` Saved photo resolution | Save the original capture; analysis uses the capped working image | `CAM-026`, `CAM-073`, `CAM-074` |
| `Q-050` OCR cards on Reading | Moot: OCR is a primary destination; access cards are removed everywhere | `CAM-006`…`CAM-009`, `SRCH-110` |
| `Q-070` Licence review | Reframed: choose the app's own open-source licence (`Q-100`); character origins deferred, which removes CJKVI IDS, chinese-lexicon and the glyph sources | `SET-040`, `SET-041` |
| `Q-072` Screen assistant | Deferred to post-v1 | `PROD-030`, `SET-070`…`SET-077` (Deferred list) |
| `Q-080` Provenance labels | Every Mandarin-sourced sense shown in Cantonese mode is labelled | `PROD-045`, `DATA-022` |
| `Q-090` Bundled vs downloaded | Mobile installs **everything** with the app (Play install-time asset packs, direct APK, iOS bundle); no runtime downloads; no INTERNET permission. Web lazily fetches large assets | `PROD-070`, `DATA-170`…, `ARCH-140`…`ARCH-143` |
| `Q-093` Stroke quiz | Kept as specified | `MED-060`…`MED-071` |
| Normalisation | The matching key uses NFKC (not NFC); dataset keys use the same form | `SRCH-010`, `GLB-001`, `DATA-071` |
| Navigation | Search · OCR · Voice · Reading · HSK (Web: Search · Reading · HSK); Settings is a card on the empty Search screen | `PROD-050`…, `SRCH-115`, `SRCH-116`, `SET-001`, `SET-005` |
| Pronunciation visibility | One global persisted toggle; hidden readings keep their space; tone colours unaffected | `GLB-120`…`GLB-122`, `SET-017` |
| Handwriting | In-app pad (bottom sheet) on Search and Reading, all platforms; ML Kit Digital Ink model bundled on mobile, repackaged and lazily loaded on Web | `HW-001`…, `ARCH-093` |
| Voice | Mobile-only destination; sherpa-onnx streaming Paraformer trilingual model installed with the app | `VOICE-001`…, `ARCH-094`, `ARCH-095` |
| Abbreviated pinyin | Any mix of full and abbreviated syllables; no separate "initials" class or group — each abbreviated syllable costs a score penalty | `SRCH-040`, `SRCH-050`…`SRCH-054` |
| Combinations | Pipeline-built bigram model (libime ARPA for Mandarin, KenLM-built for Cantonese) decoded by a shared pure-Kotlin lattice decoder | `SRCH-060`…, `TXT-026`, `DATA-150`…`DATA-155`, `PIPE-070`… |
| Repository | Single repository: app, pipeline and pinned data sources (incl. HSK word list); CI regenerates the dataset | `PIPE-160`…`PIPE-166`, `REL-*` |
| Versioning & branches | `YYYY.MM.DD-X`; trunk-based `main` | `REL-001`…`REL-020` |
| UI languages | en, zh-Hans, zh-Hant only | `GLB-100`…`GLB-103` |
| Routes | szdict.com; same routes in app and web; App Links / Universal Links | `GLB-110`…`GLB-112` |
| Deferred | Screen-assistant integration; character origins | `PROD-030` |

---

## Blocking: affects data pipeline design

### `Q-081` — Cantonese frequency data
**Unclear:** Segmentation quality and result ranking both depend on word frequencies. The reference app
has Mandarin frequencies from an OpenSubtitles-derived list; no Cantonese equivalent was found in the
repository, and licensing/quality of Cantonese corpora is not established.
**Options:** (a) reuse Mandarin frequencies for shared surface forms; (b) source a Cantonese corpus;
(c) ship without frequencies and use greedy longest-match segmentation.
**Recommendation:** (a) for v1 — Cantonese and Mandarin share most written vocabulary, and greedy
segmentation is noticeably worse (`我们研究生命科学` mis-segments as `我们/研究生/命/科学`). Mark it in
metadata so the choice is visible, and revisit with (b).
**Blocks:** `DATA-114`, `TXT-012`.

### `Q-082` — Which Mandarin senses to carry onto a Cantonese entry
**Unclear:** When a headword has several Mandarin entries (different readings, different meanings) and
one Cantonese reading, which Mandarin senses belong to the Cantonese entry? Meaning-to-reading
correspondence between the two languages is not mechanically derivable.
**Options:** (a) carry the senses of all Mandarin entries for that headword, in frequency order;
(b) carry only the most frequent Mandarin entry's senses; (c) attempt a reading-correspondence mapping
(Mandarin-to-Cantonese initial/tone correspondences).
**Recommendation:** (a) — over-inclusive is better than wrong for a dictionary, and the provenance
label tells the user these are Mandarin glosses. Report the count so the scale of the problem is known.
**Blocks:** `PIPE-053`, `DATA-182`.

### `Q-080` — Labelling sense provenance in Cantonese mode
**Unclear:** Should every sense show its source, or only when an entry mixes sources? Always labelling
is noisy; never labelling misleads (a Mandarin gloss may not be idiomatic Cantonese).
**Options:** (a) label only when sources are mixed; (b) always label; (c) group under sub-headings
(`Cantonese` / `Also in Mandarin`).
**Recommendation:** (c) for entries that mix sources, (a)'s silence for single-source entries. It is
clearer than an inline tag and costs no extra data.
**Blocks:** `DATA-022`, `ENT-052`.
**Decided:** every Mandarin-sourced sense shown in Cantonese mode is labelled (see §0).

### `Q-030` — Is there a Cantonese equivalent of the HSK feature?
**Unclear:** HSK is Mandarin-specific. Cantonese mode would simply lose a whole primary destination.
**Options:** (a) hide the destination (three destinations in Cantonese mode); (b) find a Cantonese
graded vocabulary list and offer the same browser; (c) offer a generic frequency-band browser in both
modes.
**Recommendation:** (a) for v1 — a fabricated level system would be worse than none. (c) is an
attractive follow-up because it also gives Mandarin users a non-HSK browse path.
**Blocks:** `HSK-002`, `PROD-051`.
**Decided:** (b)-like — HSK is shown in both modes, joined to the Cantonese dataset (see §0).

### `Q-070` — Licence review of GPL/LGPL data sources
**Unclear:** The reference build includes CJKVI IDS (GPLv2) and Make Me a Hanzi (LGPL-3.0,
Arphic-derived) data, and chinese-lexicon which has **no licence file** in the pinned repository. It
also ships CC BY-SA dictionary data, whose share-alike clause reaches derived datasets.
**This is a legal question, not a technical one.** It must be answered before the successor ships.
**Options:** (a) legal review per source and drop what cannot be cleared; (b) drop the origins pack
entirely; (c) obtain explicit permission for chinese-lexicon.
**Recommendation:** (a), and treat the character-origins pack as the highest-risk item since three of
its four sources are the problematic ones. Ship v1 without origins if the review is not complete.
**Blocks:** `SET-041`, `PIPE-018`, whether the origins pack exists at all.

---

## Blocking: affects product scope
**Decided / reframed:** origins are deferred (removes the riskiest sources); the remaining question is the app licence itself, `Q-100`.

### `Q-072` — Keep the Android screen-assistant integration?
**Unclear:** Its value is real (OCR any screen) but it costs two services, a stub recognition service,
an extra process, an always-running component, and it degrades unpredictably across OEMs and Android
versions (screenshots return null under `FLAG_SECURE`, disabled context sharing, enterprise policy, or
OEM gaps). No usage data exists.
**Options:** (a) drop it from v1; (b) keep it as-is; (c) replace it with the much cheaper share-image
path plus documentation ("share a screenshot to the app").
**Recommendation:** (c) — the share target already covers screenshots with a fraction of the surface
area. Revisit (b) if users ask.
**Blocks:** `SET-070` to `SET-077`.
**Decided:** (a) for v1 — deferred to post-v1.

### `Q-071` — iOS home-screen widget in v1?
**Unclear:** The Android widget is a complete, polished feature (per-instance level masks, deterministic
daily word, audio without opening the database). Reproducing it on iOS is a meaningful chunk of work
that duplicates none of the shared code.
**Options:** (a) Android only in v1; (b) both; (c) neither in v1.
**Recommendation:** (a). The widget projection dataset (`DATA-130`) is platform-neutral, so adding iOS
later is cheap.
**Blocks:** `SET-068`.

### `Q-013` — Search history
**Unclear:** The reference app restores exactly one last query and has no history list. It is unclear
whether that is a deliberate minimalism or simply unbuilt.
**Options:** (a) keep one restored query; (b) add a recent-queries list on the empty screen; (c) add
recents plus saved/starred words.
**Recommendation:** (b) as a small, high-value addition to the empty state (recents above Popular
words), with a clear action. (c) is a separate feature needing its own spec.
**Blocks:** `SRCH-143`, the empty-state layout.

### `Q-050` — OCR entry points on the Reading destination
**Unclear:** "Read this text" and "read this photo" are the same user intent, but OCR access cards exist
only on the empty Search screen.
**Recommendation:** add them to the Reading destination's empty state. Low risk, clearly aligned with
the product's philosophy.
**Blocks:** `TXT-058` (withdrawn).

---

## Non-blocking: behaviour details
**Decided:** moot — OCR is now a primary destination and the access cards are removed.

### `Q-012` — No-result copy when the other side has results
**Unclear:** Today both sides show `No matches` independently; a user on the English side of a Hanzi
query sees an empty screen while Chinese results exist behind the toggle.
**Recommendation:** show `No Chinese matches — 12 English matches` with the count acting as the switch.
Needs copy review.
**Blocks:** `SRCH-114`.

### `Q-020` — Note label wording
**Unclear:** Notes currently render their raw source keys (`also-pr`, `tw-pr`, `cl`, `abbr-for`).
**Recommendation:** a data-driven label table (`DATA-060`) with human copy: `classifier`,
`also pronounced`, `Taiwan pronunciation`, `abbreviation for`, `also written`, `see`, `see also`,
`variant of`, `old variant of`. Needs a copy pass by someone who knows the dictionary conventions.
**Blocks:** `ENT-058`.

### `Q-031` — Show the HSK syllabus reading when it differs
**Unclear:** For about 257 rows the syllabus teaches a tone-sandhi form (`bú kè qi`, `yíbàn`,
`xuéshēng`) that differs from the dictionary reading. The app discards it. Learners are examined on the
syllabus form.
**Options:** (a) keep discarding it; (b) show it as a secondary `HSK reading` line on the entry page;
(c) show it on HSK cards only.
**Recommendation:** (b) — it is genuinely useful information and the data is already there. Needs a
learner/teacher sanity check that the syllabus forms are not simply errors.
**Blocks:** `HSK-054`.

### `Q-032` — Show HSK syllabus glosses for linked entries
**Unclear:** The syllabus has its own glosses, sometimes clearer and level-appropriate, sometimes just a
subset. Linked cards show only dictionary definitions.
**Recommendation:** do not show them by default (duplication and noise), but keep them in the dataset so
this can be revisited with real examples.
**Blocks:** `HSK-055`.

### `Q-040` — Does the flash setting persist across launches?
**Recommendation:** no — match platform camera apps, which reset to the default each session. Persist
only for the session (`CAM-038`).
**Decided:** as recommended.

### `Q-041` — Which resolution is saved to the photo library?
**Unclear:** The capture pipeline downscales to 2560 px on the long side for OCR. A user saving a photo
probably expects full camera resolution.
**Options:** (a) retain the full-resolution capture separately and save that; (b) save the 2560 px
working copy.
**Recommendation:** (a) — memory cost is transient and a downscaled photo in someone's camera roll is a
surprise. If (a) proves too memory-hungry on low-end devices, (b) is acceptable but must be documented
in the UI copy.
**Blocks:** `CAM-074`.
**Decided:** (a) — save the original capture (`CAM-073`).

### `Q-042` — iOS share extension for images
**Unclear:** Android registers as a share target for `image/*`. The iOS equivalent is a share extension,
which is a separate target with its own bundle and cannot easily share the app's dataset handle.
**Recommendation:** defer to post-v1; the in-app picker covers the same need with less machinery.

### `Q-060` — TTS fallback for words with no recording
**Unclear:** Today the play button is simply disabled, which reads as broken.
**Recommendation:** fall back to TTS for the headword, with no visual distinction. Needs a quality check
that platform Mandarin/Cantonese TTS pronounces isolated words acceptably (tone-3 sandhi and neutral
tones are common failure cases).
**Blocks:** `MED-011`.

### `Q-061` — Translate hand-off mechanism
**Unclear:** The reference implementation targets hard-coded third-party activity class names, which
break when that app changes.
**Recommendation:** implicit intents only; accept a chooser; drop the component names.
**Blocks:** `MED-033`.

### `Q-062` — Example-sentence ordering
**Unclear:** Examples appear in source order. Some are much better illustrations than others.
**Recommendation:** rank by (contains the headword as a standalone segmented word, then shorter first),
computed in the pipeline. Needs a spot check that the ranked top-5 are actually better.
**Blocks:** `MED-047`.

---

## Non-blocking: platform and delivery

### `Q-090` — Which asset packs are bundled versus downloaded?
**Unclear:** The specs require the core dictionary to be bundled and permit packs to be downloaded, but
the split has product consequences (a first run with no audio at all may feel broken).
**Options:** (a) bundle the syllable audio (13 MB, covers every word by concatenation) and download the
rest; (b) download everything; (c) bundle nothing optional and prompt on first use.
**Recommendation:** (a) — syllable audio alone gives every entry a usable pronunciation, and it is the
smallest pack with the widest coverage.
**Blocks:** `ARCH-141`, `PERF-070`.
**Decided:** none of the options — on mobile everything is installed with the app; Web lazily fetches large assets.

### `Q-091` — Web dataset delivery and offline expectations
**Unclear:** A 10-16 MB dataset over the network on first visit is a real cost. Is the Web target
expected to work fully offline (PWA, installed) or is it a "try it in the browser" surface?
**Recommendation:** treat Web as a full PWA: service worker, dataset in OPFS, visible one-time install
step. If the product intent is a lightweight preview instead, the architecture changes materially (a
server-side search API), so this needs an explicit answer.
**Blocks:** `ARCH-004`, `PERF-060`.

### `Q-092` — Minimum Android API level
**Unclear:** The reference app targets API 26+. API 26-28 costs a legacy photo-saving path
(`CAM-075`) and misses several conveniences.
**Recommendation:** raise the minimum to API 29 (Android 10) unless install-base data says otherwise;
it removes the legacy storage path entirely.

### `Q-093` — Does the successor keep the stroke-practice quiz?
**Unclear:** It is a substantial, high-quality feature (about 1200 lines of geometry and recognition)
that is arguably a different product from a dictionary, and its data is the largest non-audio pack.
**Options:** (a) keep it as specified; (b) keep animation only, drop the quiz; (c) drop the tab.
**Recommendation:** (a) — it is finished, well-specified, and differentiating. But confirm it is in
scope, because reimplementing it is a meaningful fraction of the total build.
**Blocks:** `MED-060` to `MED-071`, the strokes pack.
**Decided:** (a) — kept.

### `Q-094` — Pronunciation scoring boundary
**Unclear:** The reference project documents a `PronunciationScoringPort` as a future boundary
(Hanzi plus reference reading plus 16 kHz mono PCM in, a scoring result out) but nothing is wired.
**Recommendation:** leave it out of v1 entirely, including the placeholder interface. Add it when the
feature is actually specified; a port with no implementation is a comment, not architecture.

---

## New questions from the 2026-10 review

### `Q-100` — App licence and store compatibility
**Unclear:** The project is open source and may adopt a copyleft licence. GPL-family licences conflict
with Apple App Store terms (FSF position), and the bundled data carries its own terms (CC BY-SA 4.0
CC-CEDICT/CC-Canto with a no-technical-restrictions clause, LGPL-2.1+ libime language model, Arphic
Public License stroke data in hanzi-writer-data, CC BY-SA zh-yue Wikipedia).
**Options:** (a) GPL-3.0-or-later with an explicit App Store exception; (b) MPL-2.0 (file-level copyleft,
App Store friendly); (c) Apache-2.0 code with data under its own licences.
**Recommendation:** (b) for code; keep datasets and models as separate files with their own licences
listed in the attribution source (`REL-052`).
**Blocks:** `REL-050`, `SET-041`.

### `Q-101` — Voice model licence wording
**Unclear:** The model card of the trilingual Paraformer export has no licence; the upstream base model
is Apache-2.0, while some FunASR weights fall under the FunASR Model License. The attribution row
(`SET-040`) needs the exact licence text.
**Recommendation:** ask the model publisher (dengcunqin / ModelScope) which licence applies and record it.
**Blocks:** `SET-040`.

### `Q-102` — HSK word list redistribution
**Unclear:** The HSK word list file now lives in the repository (`PIPE-161`). The syllabus is a
government standard; the redistribution terms of the specific file are not recorded.
**Recommendation:** record the file's source and terms in its source note before the first public
release.
**Blocks:** `PIPE-161`, `SET-040`.

### `Q-103` — Mandarin language-model provenance
**Unclear:** libime's `lm_sc.arpa` is LGPL-2.1+ but its training corpus is undocumented.
**Options:** (a) accept it; (b) build our own Mandarin model from documented corpora (OpenSubtitles,
zh Wikipedia) with the same pipeline.
**Recommendation:** (a) for v1; the ARPA importer is source-agnostic, so (b) is a later swap.
**Blocks:** `PIPE-070`.

### `Q-104` — Cantonese bigram quality
**Unclear:** Written-Cantonese corpora are small; the quality gate (`PIPE-075`) threshold and test set
are placeholders. words.hk example sentences have conflicting licence statements.
**Recommendation:** measure with the golden set before release; fall back to Mandarin bigrams for shared
written forms if below threshold; do not use words.hk until its licence is confirmed.
**Blocks:** `PIPE-074`, `PIPE-075`.

### `Q-105` — Fuzzy pinyin
**Unclear:** Should z/zh, c/ch, s/sh, n/l (and Cantonese n/l) be interchangeable? It is cheap through
the alias data but increases ambiguity.
**Recommendation:** not in v1; revisit after abbreviation search ships.

### `Q-106` — Combinations: position and activation
**Unclear:** Whether the Combinations group sits where the spec currently puts it — after whole-query
matches (EXACT / EXACT_TONELESS) and before PREFIX matches — or elsewhere, and whether it runs for every query of 2+ tokens or only when no single entry covers the
whole query.
**Recommendation:** keep the current position and run it for every 2+ token query; check with
real listening use.
**Blocks:** `SRCH-060`…`SRCH-068`.

### `Q-107` — Route details
**Unclear:** (a) dictionary mode as a path segment (`/yue/word/…`) or the `mode=` parameter
(`GLB-110`); (b) the length above which a query counts as a passage and is kept out of the URL
(`GLB-112`).
**Recommendation:** query parameter; passages = any query classified as a sentence (`SRCH-035`).

### `Q-108` — CC-Canto reference format
**Unclear:** `GLB-014` assumes CC-Canto glosses embed references like CC-CEDICT. Verify against the
pinned release before building the reference resolver.
**Blocks:** `GLB-014`, `PIPE-053`.

### `Q-109` — CJK font and glyph locale
**Unclear:** Without locale tagging, devices with a Japanese locale may render Japanese glyph forms;
Ext B+ characters may render as boxes with system fonts.
**Recommendation:** tag text by script (zh-Hans / zh-Hant / zh-HK) and bundle an OFL fallback font for
rare characters; measure size against `PERF-070`.

### `Q-110` — HSK per-level counts
**Unclear:** `HSK-003` counts may not match GF 0025-2021 (500 / 772 / 973 / 1000 / 1071 / 1140 / 5636).
Confirm which edition the committed word list follows.
**Blocks:** `HSK-003`, gate 8 of `DATA-200`.

### `Q-111` — Web media delivery
**Unclear:** Granularity of lazy media fetches on Web (per record vs per shard) and whether TTS should
stand in while a clip downloads.
**Blocks:** `DATA-170`…`DATA-173`, `MED-084`.

### `Q-112` — Photo saving on iOS
**Unclear:** Saving to the photo library on iOS needs `NSPhotoLibraryAddUsageDescription`, beyond the
"camera + microphone only" goal. Keep photo saving on iOS?
**Recommendation:** keep it; it is add-only and requested only when the user saves.
**Blocks:** `ARCH-106`, `CAM-017`.

### `Q-113` — Signing keys for Play and direct APK
**Unclear:** If the direct APK is signed with a different key than Play's app-signing key, users cannot
switch install source without uninstalling.
**Recommendation:** use the same upload/app-signing key for both (Play App Signing with your own key),
and list it in `assetlinks.json`.
**Blocks:** `REL-041`, `GLB-111`.

### `Q-114` — Design choices made while writing the spec (confirm)
- Handwriting sheet has a Delete (backspace) button; ink is never auto-committed (`HW-011`, `HW-014`,
  `HW-016`).
- Cantonese mode with Simplified script uses the simplified handwriting model (`HW-031`).
- Voice `Open in Reading` replaces the Reading draft (`VOICE-050`).
- HSK in Cantonese mode is ordered by Jyutping (`HSK-093`).
- The widget follows the dictionary mode (`SET-062`).
- `PERF-025` p50 20 ms and ceiling 200 ms (only p95 80 ms was specified).
- Back from the OCR live feed or from Voice goes to the platform default, not to Search (`PROD-056`).
- Romanisation (including abbreviations) switches to the Chinese page only when the English page has
  no match, so typing English words like `ca` or `stu` does not flip pages; full pinyin such as `nihao`
  still lands on Chinese (`SRCH-024`).
- A single-token query never uses abbreviation, so `x` keeps its current results (`SRCH-050`…).
- Literal Latin tokens (T恤) cost the same as an abbreviation and never mix with abbreviations.
- The 12-token limit applies only to paths with abbreviated or literal tokens.
- A combination identical to a whole-query single entry is dropped (`nh` shows 你好, not 你 + 好), and
  combinations are not counted in the page's result count.

---

## Flagged by the review, not changed

These were raised by the complexity and coherence reviews. They were **not** approved for change and
remain as specified; listed here so they can be revisited.

**Complexity (possible simplifications):** custom binary encodings inside SQLite (`DATA-141`…`DATA-144`,
`DATA-171`); substring index and `within` table alongside cheaper runtime lookups (`DATA-074`,
`DATA-075`, `DATA-100`…`DATA-104`); variant merge-group machinery (`SRCH-080`…`SRCH-086`); generation
counters and storage-level cancellation (`GLB-040`…`GLB-045`, `ARCH-036`); about 40 CI-enforced
performance budgets on reference devices; pixel and animation values written as MUSTs; Web via
Compose-Wasm; dataset N/N-1 versioning; pipeline diff gates; several ranking comparators; fully ranking
both search sides on every keystroke; result-list chips and expanders; a custom entry-detail stack;
HSK browser chrome; the "Read clipboard" launcher shortcut; audio-plan rules; the 4-value provenance
enum.

**Spirit of the project:**
- Google Translate is hard-coded in the translate hand-off (`MED-031`); a generic chooser fits better
  (see `Q-061`).
- Compose-Wasm renders to a canvas, which weakens screen readers, native text selection, find-in-page
  and browser translation on Web.
- hanzi-writer-data stroke data is under the Arphic Public License, not MIT; Make Me a Hanzi's
  LGPL/Arphic split should be verified per file; audio-cmn's CC BY-SA version is unstated.
- Readings are still missing in some places: example sentences (`MED-041`), the ellipsised reading in
  result cards (`SRCH-094`).
- Cantonese gaps: example sentences are Mandarin Tatoeba (`MED-040`); there is no Cantonese recorded
  audio (`DATA-186`); the proper-noun flag comes from Pinyin capitalisation.
