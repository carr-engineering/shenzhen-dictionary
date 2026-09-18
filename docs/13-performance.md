# 13 — Performance Targets

Performance is an explicit goal of the rewrite. This document defines the budgets, the reference
hardware, and how each is measured.

---

## 1. Reference conditions

`PERF-001` `[MUST]` **Reference device (Android):** a mid-range 2022 phone, roughly Pixel 6a class
(8 cores, 6 GB RAM, UFS 3.1). **Reference device (iOS):** iPhone SE 3rd generation. **Reference Web:**
desktop Chrome on a 2020-class laptop, plus the Android reference device's Chrome.

`PERF-002` `[MUST]` Budgets are stated for a **release build** with the dataset already installed and
the app warm (process alive, dataset opened) unless the budget says "cold".

`PERF-003` `[MUST]` Percentiles are over at least 20 iterations, reported as p50/p95, with the stated
hard ceiling never exceeded.

`PERF-004` `[MUST]` Every budget below is a CI-enforceable benchmark, not an aspiration. A regression
beyond 20% on p50 or beyond the ceiling fails the build.

---

## 2. Search latency

`PERF-020` `[MUST]`

| Metric | p50 | p95 | Ceiling |
|--------|-----|-----|---------|
| Keystroke to results in state (`input_to_ready`) | 40 ms | 90 ms | 200 ms |
| Keystroke to first rendered frame (`input_to_frame`) | 60 ms | 120 ms | 250 ms |
| Blank-query (popular words) to frame | 30 ms | 60 ms | 120 ms |
| Side toggle to frame (response reusable) | 16 ms | 32 ms | 80 ms |
| Worst-case query class (single common English token, e.g. `to`) | 90 ms | 150 ms | 300 ms |
| Pasted 100-character sentence to frame | 120 ms | 220 ms | 400 ms |

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

`PERF-024` `[MUST]` Post-change targets for the same operations on the **reference phone**: substring
match at most 5 ms; English ranked at most 40 ms; batched hydration of one visible window (about 20
entries) at most 3 ms; 100-character segmentation at most 20 ms.

---

## 3. Entry detail

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

## 4. HSK

`PERF-040` `[MUST]`

| Metric | p50 | p95 | Ceiling |
|--------|-----|-----|---------|
| Destination tap to first frame (cold, first visit) | 150 ms | 300 ms | 600 ms |
| Destination tap to first frame (warm, cached model) | 16 ms | 40 ms | 80 ms |
| Level chip tap to that level on screen | 1 frame + scroll | | |

`PERF-041` `[MUST]` The cold path must not read all 11105 rows, hydrate about 11000 entries, or compute
audio availability before the first frame (`HSK-066`). The first frame requires only the rows in the
first viewport.

---

## 5. Text analysis

`PERF-050` `[MUST]`

| Metric | p50 | p95 | Ceiling |
|--------|-----|-----|---------|
| Analyse 100 characters | 15 ms | 30 ms | 60 ms |
| Analyse 500 characters | 60 ms | 120 ms | 250 ms |
| Tap a character to sheet populated | 30 ms | 60 ms | 120 ms |
| Step to adjacent character | 20 ms | 45 ms | 100 ms |

---

## 6. Camera / OCR

`PERF-055` `[MUST]`

| Metric | p50 | p95 | Ceiling |
|--------|-----|-----|---------|
| Camera surface open to first preview frame | 300 ms | 600 ms | 1200 ms |
| Shutter tap to captured image on screen | 250 ms | 500 ms | 1000 ms |
| Tap-to-focus to reticle drawn | 1 frame | 2 frames | 3 frames |
| Capture to recognition overlay drawn (8 MP source) | 700 ms | 1300 ms | 2500 ms |
| Recognition to all lines segmented | 150 ms | 300 ms | 600 ms |
| Save to photo library (background, non-blocking) | 300 ms | 800 ms | 3000 ms |
| Tap a glyph in the image to sheet populated | 40 ms | 80 ms | 150 ms |
| Pinch/pan | 60 fps sustained | | |

`PERF-056` `[MUST]` The 20 s recognition timeout (`CAM-050`) stays as a backstop but must never be
reached under the reference conditions.

---

## 7. Start-up

`PERF-060` `[MUST]`

| Metric | p50 | p95 | Ceiling |
|--------|-----|-----|---------|
| Cold start to interactive search field (Android) | 400 ms | 700 ms | 1200 ms |
| Cold start to restored query's results rendered | 600 ms | 1000 ms | 1800 ms |
| Warm start to interactive | 120 ms | 250 ms | 500 ms |
| Dataset open (file open plus metadata read) | 10 ms | 30 ms | 80 ms |
| Web first visit to interactive (dataset already in OPFS) | 1.5 s | 3 s | 5 s |
| Web first-ever visit including dataset download (assume 20 Mbit/s) | 12 s | 25 s | — |

`PERF-061` `[MUST]` There is **no** visible "loading dictionary" phase (`ARCH-131`).

---

## 8. Size

`PERF-070` `[MUST]`

| Artefact | Budget | Reference app |
|----------|--------|---------------|
| Mandarin dataset, installed | 16 MB | 90 MB, 180 MB installed (shipped and copied) |
| Cantonese dataset, installed | 16 MB | n/a |
| Android download size, core (one dataset, OCR model, no optional packs) | 60 MB | not measured; packaged assets alone are about 215 MB uncompressed |
| Android installed, core | 90 MB | about 305 MB (215 MB assets plus a 90 MB copy of the database) |
| iOS download, core | 70 MB | n/a |
| Web initial payload (code, no dataset) | 4 MB compressed | n/a |
| Optional pack: audio (words plus syllables) | 65 MB | 65 MB bundled |
| Optional pack: strokes | 20 MB re-encoded | 30 MB bundled |
| Optional pack: origins | 12 MB re-encoded | 22 MB bundled |
| Optional pack: sentences | 6 MB | 5.5 MB bundled |

`PERF-071` `[MUST]` The raw gzipped source bundle (6.3 MB in the reference build) must not ship.

`PERF-072` `[MUST]` Unused indexes must not ship (21.5 MB in the reference build — `DATA-142`).

---

## 9. Memory

`PERF-080` `[MUST]`

| Metric | Budget |
|--------|--------|
| Steady-state after search and browsing (Android PSS) | 150 MB |
| Peak during OCR of an 8 MP image | 320 MB |
| Largest single contiguous allocation | 8 MB |
| Entry cache | bounded, about 2000 entries |
| Retained after leaving the camera surface | back to steady state within one GC |

`PERF-081` `[MUST]` No whole-dataset or whole-index in-memory structure (`DATA-148`). The reference
app's earlier in-memory build failed with a 33.5 MB contiguous-allocation failure and retained hundreds
of MB (finding `F-011`).

`PERF-082` `[MUST]` Web: no more than 300 MB of Wasm heap steady-state; the dataset is read from OPFS,
never loaded whole.

---

## 10. Instrumentation

`PERF-100` `[MUST]` Named trace sections, emitted in release builds:

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
| `dict.segment` | segmentation and sub-span enumeration |
| `dict.state_emit` | state reduction and emission |
| `dict.hsk_build` | HSK model build |
| `dict.hsk_tab_to_frame` | async: destination tap to first HSK frame |
| `dict.entry_open` | async: tap to entry content rendered |
| `dict.ocr` | recognition |

`PERF-101` `[MUST]` The macrobenchmark suite asserts the budgets above using these sections plus frame
timing. Minimum suite: rapid typing, blank-query start, entry open plus nested push plus back,
HSK cold and warm entry, paste-and-analyse, camera capture to overlay.

`PERF-102` `[SHOULD]` Also track the **candidate-set sizes** per query class in the report (for example
33817 candidates for `to`), because a ranking regression usually shows up there first.
