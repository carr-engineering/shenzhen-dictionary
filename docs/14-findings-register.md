# 14 — Findings Register

Every notable behaviour or implementation decision observed in the reference app, classified.
Ambiguous items live in [15 — Open Questions](15-open-questions.md).

Categories: **Preserve** (intentional, reproduce) · **Improve** (correct but can be better) ·
**Bug** (unintended, do not reproduce) · **Replace** (keep the behaviour, redesign the mechanism) ·
**New** (requested, does not exist yet).

---

## A. Application size and packaging

| ID | Finding | Class | Action |
|----|---------|-------|--------|
| `F-010` | The 90 MB SQLite dataset is shipped as an app asset **and** copied to the database directory on first run: about 180 MB on device for one dictionary. | Bug | `DATA-146` — read in place or download once. |
| `F-011` | An earlier build loaded a 6.3 MB gzipped JSON bundle with a full-string read, parsed it into a JSON tree, and built a complete in-memory index; it failed with a 33.5 MB contiguous-allocation error and retained hundreds of MB when it succeeded. | Bug | `DATA-148`, `PERF-081` — no in-memory index, ever. |
| `F-012` | The raw gzipped source bundle (6.3 MB) is shipped in the app alongside the processed database, for "reproducible rebuilds". | Bug | `PERF-071` — build inputs never ship. |
| `F-013` | About 123 MB of optional assets (65 MB audio, 30 MB strokes, 22 MB origins, 5.5 MB sentences) are bundled unconditionally. | Improve | `ARCH-141` — downloadable packs. |
| `F-014` | Packaging exclusions are expressed as an asset-name deny-list, so a newly added directory ships by default. | Bug | `PIPE-066`, `ARCH-144` — allow-list. |
| `F-015` | Historical glyph images are base64 data URIs inside JSON shards (about 33% inflation, and a whole-shard parse to read one glyph). | Improve | `MED-058`, `PIPE-064`. |
| `F-016` | A 58 MB legacy Hanzi-keyed audio directory lives inside the packaged assets tree and is excluded from the artefact only by an asset-name deny-list; it is a generator input, not a shipping asset. | Bug | `PIPE-066` — build inputs live outside the packaged tree. |

---

## B. Data layout and indexes

| ID | Finding | Class | Action |
|----|---------|-------|--------|
| `F-020` | ASCII case-insensitivity is implemented by enumerating up to 2^8 = 256 case permutations of the query at runtime and issuing a lookup for each. | Replace | `DATA-071`, `DATA-072` — a case-folded key column. |
| `F-021` | Variant merge groups are recomputed at query time: for each candidate, re-look-up its headword, re-fetch the matches, compare readings. Runs for every result list, sheet, and entry detail. | Replace | `DATA-080` — precomputed group ids. |
| `F-022` | Substring ("contains") matching uses a substring predicate over the whole headword index, which the query planner executes as a **full index scan** (about 6 ms per call on a desktop). A Hanzi query does it twice; entry detail a third time. | Bug | `DATA-074` — a real substring index. |
| `F-023` | Two reverse indexes (headword-by-entry, 8 MB; english-token-by-entry, 13.5 MB) are built and shipped but never used by any query. | Bug | `DATA-142` — remove; 21.5 MB saved. |
| `F-024` | The toneless reading is derived by stripping a trailing digit from each whitespace token, which corrupts readings containing literal digits: `11區 / 11 qu1` gets the plain reading `1 qu` and is unfindable by pronunciation. | Bug | `PIPE-022`. |
| `F-025` | Definitions, notes and HSK glosses are stored as JSON strings and `JSON.parse`d per row on every hydration, including all about 11000 HSK rows. | Replace | `DATA-143` — binary blobs. |
| `F-026` | Sub-span (compound) relationships are computed by enumerating every substring of a word and issuing a lookup per substring, on every entry open and every search group. | Replace | `DATA-101` — precomputed. |
| `F-027` | Segmentation issues one lookup per candidate substring: 2415 queries for a 100-character passage, 12604 for 500 characters. | Replace | `TXT-014` — batched (measured 27x and 49x faster). |
| `F-028` | Frequency log-probabilities are computed with a logarithm per candidate at segmentation time. | Improve | `DATA-113`. |
| `F-029` | The Mandarin syllable inventory and alias map are hard-coded in application source, so Cantonese support would require code changes. | Replace | `DATA-070` — data-driven. |

---

## C. Entry detail

| ID | Finding | Class | Action |
|----|---------|-------|--------|
| `F-030` | Related-word sections eagerly fetch up to 500 entries **each** (starts-with, contains, contains-reversed) plus all sub-spans, and hydrate all of them — up to about 1500 JSON-parsed entries per entry opened. | Bug | `ENT-064`, `DATA-104` — page them. |
| `F-031` | WORDS-tab rows hard-code the play button to enabled regardless of whether audio exists, so many buttons do nothing. | Bug | `ENT-065`. |
| `F-032` | `EntryDetailData.chars` — a per-character entry list with a lookup, a merge grouping and a hydration for **every character** of every opened headword — is computed and never rendered by any UI. | Bug | `ENT-081` — delete. |
| `F-033` | Redirect (`variant of`) resolution runs at read time with extra batched lookups per entry set. | Replace | `DATA-055`. |
| `F-034` | Definition prose is re-parsed into segments on every render, including the non-Mandarin-romanisation cue scan over 96 characters of preceding context. | Replace | `DATA-050`, `DATA-053`. |
| `F-035` | Structured note labels are shown as raw hyphenated source keys (`also-pr`, `tw-pr`, `cl`) lowercased. | Improve | `ENT-058`, `Q-020`. |
| `F-036` | The collapsing header over-measures the pager by the header height and translates it, so collapsing is placement-only. | Preserve | `ENT-022`. |
| `F-037` | Route-scoped saveable state restores tab, header collapse and per-tab scroll on back, keyed per visit rather than per entry id. | Preserve | `GLB-050`, `GLB-051`. |
| `F-038` | Pushing a route while the previous one is still loading replaces it instead of stacking skeletons. | Preserve | `ENT-012`. |
| `F-039` | An outgoing route stays alive until its exit animation completes, so it never flashes a placeholder. | Preserve | `ENT-014`. |

---

## D. Start-up and engine lifecycle

| ID | Finding | Class | Action |
|----|---------|-------|--------|
| `F-041` | Start-up runs a 22-entry in-memory seed dictionary, then opens SQLite on a background thread and **swaps the whole engine**, bumping a generation that invalidates search, reading, camera, HSK and detail state and re-runs everything. During the swap window the user can search a 22-word dictionary and get plausible-looking wrong answers. | Replace | `ARCH-131`, `PERF-061`. |
| `F-042` | The seed engine is a second complete implementation of the engine (in-memory index, ranking, segmentation) maintained in parallel with the SQLite one — two behaviours to keep in sync. | Replace | one engine over one store (`ARCH-020`). |
| `F-043` | Passage ids are integers into an engine-owned LRU registry; five separate call sites need explicit guards against a stale or evicted id. | Replace | `ARCH-070` — immutable passage values. |
| `F-044` | Every async operation family carries a request generation and re-validates the state it was computed for before applying; cancellation alone is explicitly not trusted. | Preserve | `GLB-040`. |
| `F-045` | Search requests are conflated to the latest through a conflated channel plus latest-only collection, so rapid typing completes exactly one search. | Preserve | `GLB-041`. |
| `F-046` | Storage reads carry a cancellation signal into the SQLite driver, and long loops checkpoint cooperatively every 1024 items. | Preserve | `GLB-045`, `ARCH-036`. |
| `F-047` | One 1823-line view model owns search, reading, camera, entry stack, HSK, audio, TTS, clipboard, settings and assistant state. | Replace | `ARCH-012`. |
| `F-048` | `dictionaryLoading` / `dictionaryStatus` are maintained in state (including the strings "Loading offline dictionary", "Full offline dictionary loaded", "Using seed dictionary: ...") but **no UI renders them**. Dead state left over from an earlier design. | Bug | Do not reproduce; there is no loading phase (`PERF-061`). |
| `F-049` | A `NavPill` component and a `PronunciationScoringPort` interface exist with no call sites. | Bug | Do not port dead code (`Q-094`). |

---

## E. Search behaviour

| ID | Finding | Class | Action |
|----|---------|-------|--------|
| `F-050` | Duplicate HSK rows within a level (14 in the reference data, e.g. 局 and 料 twice in level 6) render as duplicate cards. Only the part-of-speech string is de-duplicated, not the rows. | Bug | `HSK-027`, `DATA-123`. |
| `F-051` | 9 HSK rows have no dictionary link and render as dead, untappable cards. | Bug | `HSK-056`, `PIPE-044`. |
| `F-052` | The HSK list reads all 11105 rows, JSON-parses each, hydrates about 11000 entries, builds the sort model, and computes audio availability for all of them before showing anything. | Replace | `HSK-066`, `PERF-041`. |
| `F-053` | The HSK load throws if any linked entry id is missing, taking down the whole screen. | Bug | `HSK-067`. |
| `F-054` | The syllabus's own (tone-sandhi) reading is discarded for linked rows, so the pedagogically taught form is never shown. | Improve | `HSK-054`, `Q-031`. |
| `F-055` | Both search sides are computed for every query so the segment counts are live. | Preserve | `SRCH-002`. |
| `F-056` | The side auto-switches to Chinese when the query has Hanzi or explicit tones, and the remembered preference is only written for ambiguous Latin queries. | Preserve | `SRCH-024`, `SRCH-026`. |
| `F-057` | The result cap of 1200 is applied **after** ranking; an earlier implementation applied a SQL limit first and dropped rank-29 results (1029 of the top 1200 omitted for `x`, 1159 for `to`). | Preserve (the fix) | `SRCH-071`, `PIPE-140`. |
| `F-058` | The dictionary-initialism reading fallback (`Txu` finds `T恤`) is a deliberate accommodation of CC-CEDICT readings containing literal Latin initials. | Preserve | `SRCH-041`. |
| `F-059` | Parent-reading promotion in sub-span groups, with capitalisation significant. | Preserve | `SRCH-104`. |
| `F-060` | Sentence-mode grouping renders multiple readings of one segment as one card with dividers, so the sentence reads one-card-per-word. | Preserve | `SRCH-099`. |
| `F-061` | A separate, gentler normalisation is used for the display/segmentation text than for matching, so pasted punctuation survives display. | Preserve | `SRCH-012`. |
| `F-062` | The category chip row is hidden when there is only one category, and the side toggle is hidden for a blank query. | Preserve | `SRCH-090`. |
| `F-063` | Popular words are a hard-coded list of 50 Chinese strings in application source, resolved to ids by 50 headword lookups at every blank query, with a frequency-sorted SQL scan as a fallback. | Replace | `DATA-090`. |
| `F-064` | Definition clamping counts fully hidden definitions and shows a `+N` accent badge measured from the text layout. | Preserve | `SRCH-095`. |
| `F-065` | The English index's last-token prefix matching plus per-sense posting indices, enabling "all tokens in one sense" ranking without re-tokenising. | Preserve | `SRCH-047`, `DATA-076`. |
| `F-066` | No search history beyond the single restored query. | Preserve | `SRCH-143`, `Q-013`. |

---

## F. Text analysis and reading

| ID | Finding | Class | Action |
|----|---------|-------|--------|
| `F-070` | DAG maximum-likelihood segmentation with frequency data, greedy longest-match without, tie-breaking to the longer word. | Preserve | `TXT-012`, `TXT-013`. |
| `F-071` | Stroke and origin assets are 256 whole-JSON shards; reading one character parses a shard of hundreds of records. | Improve | `DATA-171`, `MED-083`. |
| `F-072` | One wrapping text layout per passage with per-glyph underline drawing and offset-aware tap resolution, shared by search, reading and camera. | Preserve | `TXT-030`, `TXT-032`. |
| `F-073` | Word-boundary underlines alternate between two shades per word and are inset at word edges. | Preserve | `GLB-061`, `TXT-030`. |
| `F-074` | The candidate sheet keeps previous rows visible with a thin progress bar while the next lookup runs, instead of closing and reopening. | Preserve | `TXT-042`. |
| `F-075` | The screen behind the sheet is padded by the live visible sheet height during the drag. | Preserve | `TXT-044`. |
| `F-076` | Character stepping skips whitespace but includes punctuation, Latin and emoji. | Preserve | `TXT-033`. |
| `F-077` | Clipboard is read only while the window has focus. | Preserve | `TXT-053`. |
| `F-078` | The Reading destination has no OCR entry point even though it is the same intent. | Improve | `TXT-058`, `Q-050`. |
| `F-079` | Reading analyses automatically on input; the README-era "Read" button no longer exists. | Preserve | `TXT-051`. |

---

## G. Camera and OCR

| ID | Finding | Class | Action |
|----|---------|-------|--------|
| `F-080` | Line text is rebuilt from recognised symbols rather than taken from the platform's line text, because the platform joins elements with spaces and would desynchronise offsets from geometry. | Preserve | `CAM-052`. |
| `F-081` | Elements without symbols fall back to per-code-point splitting sharing the element quad, rather than dropping the line. | Preserve | `CAM-053`. |
| `F-082` | Quad containment uses the quad's own winding, so rotated text hit-tests correctly; nearest-centre fallback within a zoom-compensated slop. | Preserve | `CAM-065`. |
| `F-083` | Zoom/pan maths keeps the pinch centroid stationary and clamps translation so the image always covers the viewport. | Preserve | `CAM-063`. |
| `F-084` | Stepping between characters pans the image to centre the new glyph. | Preserve | `CAM-067`. |
| `F-085` | 4:3 capture is chosen deliberately (16:9 crops sensor rows) and preview and capture share it. | Preserve | `CAM-020`. |
| `F-086` | Images are capped at 2560 px on the long side, EXIF-corrected, and decoded with a software allocator. | Preserve | `CAM-026`, `CAM-041`. |
| `F-087` | Share/assistant intents are consumed on handling so recreation does not re-process the image. | Preserve | `CAM-043`, `SET-052`. |
| `F-088` | Back from a camera capture returns to the viewfinder, not out of the surface. | Preserve | `CAM-100`. |
| `F-089` | No tap-to-focus. | New | `CAM-030`. |
| `F-090` | No flash/torch control. | New | `CAM-035`. |
| `F-091` | No way to save the captured photo; captures are lost when the surface is left. | New | `CAM-071`. |
| `F-092` | No auto-save setting. | New | `CAM-080`. |
| `F-093` | The recogniser is re-run against the new dataset when the engine is swapped, because passage ids are dataset-scoped. | Preserve (as behaviour) / Replace (as mechanism) | `CAM-057`, `ARCH-070`. |
| `F-094` | Gallery decoding runs on the activity's scope, not the composable's, so a rotation mid-decode does not lose the image. | Preserve | `CAM-044`. |

---

## H. Pronunciation, media, assets

| ID | Finding | Class | Action |
|----|---------|-------|--------|
| `F-100` | Audio is keyed by canonical numbered reading, never by characters, so homophones share recordings and two-reading words cannot pick the wrong clip. | Preserve | `MED-004`. |
| `F-101` | Multi-character entries prefer a full-word recording; single characters prefer a complete syllable plan. Neutral tone falls back to tone 1. | Preserve | `MED-006`, `MED-007`. |
| `F-102` | Ambiguous legacy recordings are excluded until reviewed. | Preserve | `MED-012`. |
| `F-103` | Availability for a whole result set is computed in one batched off-thread pass. | Preserve | `MED-008`. |
| `F-104` | When no recording exists the play button is simply disabled, with no TTS fallback for words. | Improve | `MED-011`, `Q-060`. |
| `F-105` | Stroke practice validates start, end, average and max distance, coverage, length ratio, direction, shape, and whether the trace better matches a later stroke — in both directions. | Preserve | `MED-065`. |
| `F-106` | Hints appear after two mistakes on the current stroke and reset on acceptance. | Preserve | `MED-067`. |
| `F-107` | Malformed medians do not drop stroke outlines. | Preserve | `MED-070`. |
| `F-108` | IDS decompositions are rendered as real nested layout, with tappable component chips. | Preserve | `MED-053`. |
| `F-109` | Translate hand-off targets hard-coded third-party activity class names. | Improve | `MED-033`, `Q-061`. |
| `F-110` | Examples are shown in source order, capped at 20, first 5 visible. | Improve | `MED-047`, `Q-062`. |

---

## I. Platform integration

| ID | Finding | Class | Action |
|----|---------|-------|--------|
| `F-120` | The word-of-the-day widget derives its word deterministically from the local date XOR the level mask, per widget instance, and reads a small dedicated projection rather than the dictionary. | Preserve | `SET-063`, `SET-066`. |
| `F-121` | The widget plays pronunciation without opening the dictionary database. | Preserve | `SET-065`. |
| `F-122` | The widget deep-links by **entry id**, which is not stable across dataset versions. | Bug | `DATA-212` — use the natural key. |
| `F-123` | The assistant integration needs two services, a third stub service, an extra process, and a compatibility recognition service, and degrades on many OEMs. | Improve (scope) | `SET-077`, `Q-072`. |
| `F-124` | Launcher shortcuts consume their intent action on handling. | Preserve | `SET-052`. |
| `F-125` | Settings persist script, tone colours, boundaries, theme, side and last query; a script/tone/theme change triggers a widget refresh. | Preserve | `SET-010`, `SET-015`. |
| `F-126` | Script preference is a single global value, so adding Cantonese (which wants Traditional) would fight the Mandarin default. | Improve | `SET-011` — per-mode. |
| `F-127` | Edge-to-edge with a status-bar gradient scrim rather than an opaque bar; navigation hides with the keyboard. | Preserve | `GLB-070` to `GLB-074`. |
| `F-128` | Selecting the current destination scrolls it to top. | Preserve | `PROD-053`. |
| `F-129` | Attribution lists every dataset with role, licence and links, plus a runtime-library licence dialog. | Preserve | `SET-040`, `SET-043`. |

---

## J. Cross-cutting UX

| ID | Finding | Class | Action |
|----|---------|-------|--------|
| `F-140` | Populated content is never blanked while a replacement loads. | Preserve | `GLB-030`. |
| `F-141` | Shape-matched skeletons for entry detail and HSK. | Preserve | `GLB-031`. |
| `F-142` | Tone colouring is skipped entirely when character and syllable counts disagree. | Preserve | `GLB-066`. |
| `F-143` | Long press selects text without following a link. | Preserve | `GLB-056`. |
| `F-144` | Search field focus is dropped before navigating so the keyboard does not reopen. | Preserve | `GLB-059`. |
| `F-145` | Code-point-correct handling of supplementary-plane characters throughout (tap targets, highlights, stepping). | Preserve | `GLB-002`. |
| `F-146` | Middle-dot and full-width folding for headword matching. | Preserve | `GLB-005`, `GLB-006`. |
| `F-147` | Non-Mandarin bracketed romanisations are not re-rendered as Pinyin, decided by scanning preceding prose cues. | Preserve (behaviour) / Replace (mechanism) | `GLB-014`, `DATA-053`. |
| `F-148` | The tone palette has exactly 5 entries and the HSK palette exactly 7, indexed with a fallback. | Improve | `GLB-064` — 6 tones for Cantonese. |
| `F-149` | Content is capped at about 900 dp wide and centred; the sheet at 720 dp. | Preserve | `GLB-067`. |
| `F-150` | No dictionary work on the UI thread anywhere. | Preserve | `GLB-046`. |
