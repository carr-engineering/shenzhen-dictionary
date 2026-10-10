# 05 — Reading and Input (Text Analysis, Camera/OCR, Handwriting, Voice)

## Text Analysis, Segmentation and Reading

This document owns the **shared** text-analysis engine behaviour (segmentation, span candidates, the
shared lattice decoder, the word-candidate sheet, interactive passage rendering including interlinear
readings) used by Search, Reading, OCR, Voice and Entry Detail, plus the Reading destination itself,
the OCR destination, handwriting input and voice transcription.

---

### 1. Segmentation

`TXT-010` `[MUST]` Analysing a text produces an ordered list of **segments**, each with a start
offset, a length, and the canonical reading of the entry chosen for it (used for tone colouring and
interlinear readings).

`TXT-011` `[MUST]` Segmentation operates on **runs**. In `HANZI` mode a run is a maximal sequence of
Hanzi; in `FULL` mode a run is a maximal sequence of non-whitespace characters. Search's Chinese side
uses `FULL` (so punctuation and Latin inside a pasted query participate); Reading, OCR and Voice use
`HANZI` (so surrounding prose and English words are skipped).

`TXT-012` `[MUST]` **With frequency data (preferred): a DAG maximum-likelihood segmentation.**
Scanning the run right-to-left, `score[i] = max over lengths L of (logProb(word) + score[i+L])`, where
`logProb(word) = ln(freq(word)) - ln(totalFreq)` for words present in the dictionary,
`ln(1) - ln(totalFreq)` for dictionary words with no frequency entry, and a fixed penalty of `-20.0`
for a single unknown character. Ties prefer the **longer** word. Only lengths up to `maxWordLen` are
considered, where `maxWordLen` is the dataset's `max_word_len` metadata value (`DATA-004`) — the
segmentation window, which may be shorter than the longest headword. Longer headwords are still
found by Search but are never produced by segmentation or span enumeration.

When the dataset carries a bigram model (`has_bigram_model`, `DATA-155`), passage segmentation
`[SHOULD]` instead use the shared lattice decoder (`TXT-026`) with `k = 1` over the character
lattice, scoring with the bigram and unigram tables (`DATA-152`…`DATA-154`) and the same
unknown-character penalty. Without the bigram model the unigram DAG above applies.

`TXT-013` `[MUST]` **Without frequency data: greedy longest-match**, left to right, lengths from
`maxWordLen` (`DATA-004`) down to 2, falling back to a single character.

The two strategies differ observably and both must be supported: `我们研究生命科学` segments as
`我们 / 研究 / 生命 / 科学` with frequency data, and as `我们 / 研究生 / 命 / 科学` with greedy
longest-match.

`TXT-014` `[MUST]` Segmentation must be **bounded and batched**: all candidate substrings of a run are
looked up in a small number of batched queries, not one query per candidate. Rationale: on the
reference data a 100-character passage generated about 2,400 individual lookups and a 500-character
passage about 12,600, against 2 batched queries in either case.

`TXT-015` `[MUST]` A passage longer than a defined limit is segmented in chunks at natural boundaries
(punctuation, then whitespace, then a hard cap) so no single operation exceeds the frame budget.
Recommended chunk: 400 characters.

`TXT-016` `[MUST]` Re-analysing the same text in the same mode and data generation yields an
equivalent analysis (same segments, same span candidates). An analysis produced against a previous
data generation (`GLB-044`) is never used to answer lookups against the current one. How analyses
are identified and cached is an architecture concern (`ARCH-070`, `ARCH-072`).

`TXT-017` `[SHOULD]` The analysed passage itself (an immutable value) should be passed across layers
rather than an opaque engine-side id, removing a whole class of staleness bugs.
*(Architecture note — `ARCH-070`.)*

---

### 2. Span candidates (what a tapped character can mean)

`TXT-020` `[MUST]` Tapping index `i` in a passage produces every dictionary word that **covers** `i`:
for every start `s` in `[max(runStart, i - (maxWordLen - 1)), i]` and every length `L` such that
`s + L > i` and `s + L <= runEnd` and `L <= maxWordLen` (`maxWordLen` as in `TXT-012`), the substring
`[s, s+L)` if it is a dictionary headword. A character is **covered** when this set is non-empty.

`TXT-021` `[MUST]` Candidate ordering:
1. the **default** candidate first — the segment the segmenter actually chose for this position;
2. then longer spans before shorter;
3. then frequency rank ascending;
4. then common readings before proper-noun readings at otherwise equal rank (the single tie rule of
   `GLB-013`);
5. then start offset ascending;
6. then entry id ascending.

`TXT-022` `[MUST]` When there are **no** candidates (the character is not part of any dictionary word),
the result instead carries up to 50 entries in which that character **appears**, ordered by frequency
then id. The sheet header changes from `Words at 「X」` to `「X」 appears in`.

`TXT-023` `[MUST]` When the position is not part of a run at all (whitespace/punctuation in `HANZI`
mode), the result is empty and the sheet header reads `At 「X」`, with the body `No dictionary entry`.

`TXT-024` `[MUST]` Candidates undergo variant merging under the active script mode (`SRCH-080`), keyed
by (start, length, displayed headword, displayed reading).

`TXT-025` `[MUST]` Span-candidate enumeration must be answerable from precomputed data (`DATA-100`) or
a single batched lookup of the at-most `maxWordLen^2 / 2` substrings, never one query per substring.

---

### 3. Shared lattice decoder

`TXT-026` `[MUST]` One **lattice decoder**, written once in shared (common) code and identical on
Android, iOS and Web — no native input-method library — finds the best word sequences through a
position lattice:
- **Input:** a lattice whose nodes are positions (character offsets for a passage; token offsets for
  romanised input) and whose edges are candidate dictionary words spanning `[i, j)`, each with its
  language-model word id (`DATA-152`) and an optional caller-supplied edge penalty (for example the
  abbreviation and partial-syllable penalties of `SRCH-050`…`SRCH-054`, or the unknown-character
  penalty of `TXT-012`).
- **Scoring:** the sum over the path of `log P(w | previous word)` — the bigram value when the pair
  exists in the bigram table (`DATA-153`, decoded through the quantisation codebook `DATA-154`),
  otherwise the left word's backoff weight plus the unigram log-probability (`DATA-152`) — plus the
  edge penalties. When the dataset has no bigram model (`DATA-155`), scoring is unigram only.
- **Search:** k-best Viterbi. Hypotheses are recombined on (node, last word); hypotheses scoring
  worse than the best at the same node by more than a beam width are dropped; at most a fixed number
  of candidate words per span is kept. `k`, the per-span cap and the beam width are caller
  parameters (Search's values: `SRCH-060`…`SRCH-068`; passage segmentation uses `k = 1`).
- **Output:** up to `k` distinct paths, best first, each with its score; ties broken
  deterministically (longer first word, then lower word id) so all platforms return identical
  results.
- **Data access:** language-model data is read in a bounded number of batched reads (one for the
  lattice's words, one for the bigram blocks of the distinct left words), never per edge, and no
  whole-table in-memory structure is built (`DATA-148`).
- It runs off the UI thread and is cancellable (`GLB-046`).

Users: multi-word combinations on Search (`SRCH-060`…`SRCH-068`) and, optionally, passage
segmentation (`TXT-012`).

---

### 4. Interactive passage rendering

`TXT-030` `[MUST]` A passage is rendered as **one** text layout (never one composable per character),
with:
- base colour `muted`; matched segments in `fg`; per-character tone colours when tone colouring is on
  and a segment's tone count equals its character count (`GLB-066`);
- the selected character's code-point range highlighted with `highlight` background and semibold
  weight;
- word-boundary underlines when the boundary setting is on: for each matched segment, an underline
  drawn per character with 1.6 dp stroke, inset 1.25 dp at the word's leading and trailing edges (so
  adjacent words read as separate strokes), coloured in alternating shades (`GLB-061`) that flip per
  word;
- interlinear readings above matched segments (`TXT-034`);
- taps resolved by hit-testing the text layout for a character offset, then normalising to the start of
  the nearest **visible** code point.

`TXT-031` `[MUST]` Underlines and interlinear readings are drawn from the layout's per-glyph bounding
boxes so they follow line wrapping correctly.

`TXT-032` `[MUST]` This renderer is shared verbatim by: the Search passage card, the Reading passage,
each OCR text-view line (`CAM-069`) and each Voice transcript utterance (`VOICE-040`).

`TXT-033` `[MUST]` **Tappable characters** are all visible characters — punctuation, Latin, digits and
emoji included; only space, tab, CR and LF are excluded. **Stepping** (`GLB-058`) visits only
**covered** characters (`TXT-020`): previous/next move to the nearest covered character in that
direction, skipping whitespace, punctuation, Latin, digits, emoji and uncovered Hanzi. Coverage is
computed once per analysed passage from the same batched lookups as segmentation (`TXT-014`), for
example as a bitset over code points, never by a lookup per step. Tapping an uncovered character is
still allowed (`TXT-022`, `TXT-023`).

`TXT-034` `[MUST]` **Interlinear (ruby) readings** (`GLB-121`). The Reading passage, the Search
passage card, OCR text-view lines and the Voice transcript render readings above the Hanzi:
- each reading is the canonical reading of the entry chosen for the segment (`TXT-010`) — Pinyin with
  tone marks in Mandarin mode, Jyutping with tone digits in Cantonese mode — so no extra lookup is
  needed;
- when the segment's syllable count equals its character count, each syllable is centred over its
  character; otherwise the whole reading is centred over the segment's glyphs on the line where the
  segment starts, ellipsised if wider than the space available;
- unmatched characters, punctuation, Latin, digits and emoji carry no reading;
- a reading takes its character's tone colour when tone colouring applies (`GLB-066`), otherwise
  `muted`; ruby text is about half the base text size;
- every line reserves a ruby band, so the line height does not depend on whether a given line has
  readings;
- the OCR image view overlay has no ruby.

`TXT-035` `[MUST]` Ruby visibility and interaction:
- when pronunciation is hidden (`GLB-120`) readings are not drawn but the ruby band stays reserved, so
  toggling never reflows the passage; tone colours and underlines are unaffected;
- a tap on the ruby band hit-tests to the character beneath it; the selection highlight covers the
  character and its reading;
- a new analysis of the same passage (live voice utterance, mode or script change) replaces readings
  in the same frame as segmentation and tone colours;
- screen-reader labels follow `GLB-122`;
- the Reading and Voice destinations show the pronunciation toggle button (`GLB-120`).

---

### 5. The word-candidate sheet

`TXT-040` `[MUST]` A **non-modal** bottom sheet, opened at a compact peek height of 256 dp
(`GLB-057`), draggable to full height, capped at 720 dp wide, `panel` coloured, with a 32x4 dp drag
handle.

`TXT-041` `[MUST]` Header row: the title (`Words at 「X」` / `「X」 appears in` / `At 「X」`), then the
previous/next character stepper group (`GLB-058`, `TXT-033`; omitted when there is no navigable text
context), then a close button.

`TXT-042` `[MUST]` While a new lookup for the same context is in flight, the in-place progress
indicator (`GLB-033`) appears under the header and the previous rows stay visible and dimmed-disabled.
The sheet does **not** close and reopen when the selection moves.

`TXT-043` `[MUST]` Rows are compact entry cards (`SRCH-097`) including merge variants, definitions and
audio buttons. Empty state (after loading finishes): `No dictionary entry`.

`TXT-044` `[MUST]` The screen behind the sheet is padded at the bottom by exactly the currently
**visible** sheet height, live during the drag, so content is never hidden behind it and the padding
collapses to zero when it hides. The same rule applies to the handwriting sheet (`HW-021`); only one
of the two sheets is visible at a time.

`TXT-045` `[MUST]` The sheet closes on: the close button, a drag down to hidden, back, tapping a row
(which opens the entry), changing destination, changing script or dictionary mode, and any change to
the underlying passage. A programmatic hide must not be mistaken for a user dismissal.
**Exception:** updates of the Voice transcript — the live utterance growing or being re-segmented,
or an utterance being committed — do not close the sheet; it stays open and refreshes in place
(`VOICE-041`).

`TXT-046` `[MUST]` The bottom safe inset is applied as a trailing list item inside the sheet, not as
padding on the sheet, so the drag handle stays at the top edge and the full list remains reachable.

`TXT-047` `[MUST]` Opening the sheet while the handwriting sheet is visible hides the handwriting
sheet; closing the word sheet restores it (`HW-020`).

---

### 6. Reading destination

`TXT-050` `[MUST]` The Reading destination contains, top to bottom:
- a multi-line outlined text field (minimum 4 lines) with the placeholder
  `Paste or type a Chinese passage…`, carrying the input-method button (`HW-003`) as a trailing icon
  button;
- a wrapping button row: `Paste` (filled accent, disabled when the clipboard has no usable text),
  `Clear` (outlined, disabled when the draft is blank), and the pronunciation toggle (`GLB-120`);
- optional status text (clipboard message, `Reading…`, or an error);
- when analysed: the passage card (`TXT-030`, with interlinear readings `TXT-034`) and the sentence
  action row (`MED-020`): play/stop, translate — an external hand-off per `MED-030`…`MED-033`
  (Google Translate on Android and Web, Apple Translation on iOS) — and copy.

`TXT-051` `[MUST]` Typing, pasting or committing a handwriting candidate (`HW-015`) analyses the text
**automatically** as soon as it is non-blank; there is no separate "Analyze" button. Each edit
supersedes any in-flight analysis (`GLB-040`); the previous result stays on screen, with in-place
progress (`GLB-033`), until the new analysis replaces it (`GLB-030`). When the draft becomes blank
the result is removed at once.

`TXT-052` `[MUST]` `Paste` reads the clipboard, sets the draft, and analyses. When the clipboard has
no usable text it shows `Clipboard empty or unavailable` (only when invoked from a shortcut) and
otherwise silently updates availability.

`TXT-053` `[MUST]` Clipboard access is attempted **only while the app window has focus**, to respect
platform clipboard-access restrictions and avoid the system's "pasted from" toast on every launch.

`TXT-054` `[MUST]` Usable clipboard text = the primary clip has a text-like MIME type
(`text/plain`, `text/html`, `text/*`), at least one item, and the coerced text is non-blank.

`TXT-055` `[MUST]` Availability is refreshed when the window gains focus and when the primary clip
changes while focused.

`TXT-056` `[MUST]` The default draft is empty; there is no sample text.

`TXT-057` `[MUST]` A launcher shortcut `Read clipboard` opens the Reading destination and pastes
(`SET-050`).

`TXT-059` `[MUST]` Web platform: the clipboard read requires a user gesture and may require permission;
the `Paste` button must be present and must surface a permission failure inline rather than appearing
broken.

`TXT-080` `[MUST]` **Shared text** (`SET-056`; **Android only** — iOS has no share target in v1,
decided: `Q-042`). Text received from another app — `ACTION_SEND` `text/plain` or
`ACTION_PROCESS_TEXT` from a text-selection menu — selects the Reading destination and:
- **replaces** the current draft with the received text, exactly as `Open in Reading` does from Voice
  (`VOICE-050`); there is no merge and no prompt;
- closes an open word-candidate sheet (`TXT-045`) and analyses the new draft automatically
  (`TXT-051`), chunked like any long passage (`TXT-015`);
- does not focus the field, so no keyboard or handwriting sheet appears by itself;
- is **consumed once** (the `SET-055` pattern): the intent action is cleared on handling, so
  activity recreation, rotation or a later activation never re-applies it.

Text that is blank after trimming opens Reading with the draft unchanged. `ACTION_PROCESS_TEXT` is
handled read-only: the app never returns modified text to the calling app. The clipboard is not
touched, and the received text is never logged (`GLB-090`). Received **images** go to the OCR
destination instead (`CAM-002`).

---

### 7. Performance

`TXT-060` `[MUST]` Targets for analysis, tap-to-sheet and stepping: `PERF-050`.

`TXT-061` `[MUST]` Analysis, candidate enumeration, lattice decoding and hydration all run off the UI
thread and are cancellable.

---

### 8. Edge cases (all `[MUST]`)

`TXT-070` A stepper direction is disabled when no covered character (`TXT-033`) exists in that
direction — for example after a tap on the first covered character, or on punctuation that precedes
every word.

`TXT-071` A supplementary-plane Hanzi is one tappable unit and its highlight covers both UTF-16 units.

`TXT-072` A passage containing only punctuation produces one inert group and, in Search, `isSentence`
true with a single inert row.

`TXT-073` Adjacent unmatched segments merge into one inert group; a matched segment between them splits
them.

`TXT-074` A text whose analysis fails leaves the draft intact, shows the error, and lets the user retry
by editing.

`TXT-075` A span lookup against an analysis from a previous data generation returns an empty span
result, never throws.

`TXT-076` Tapping the same character twice re-opens (or keeps open) the same sheet without flicker.

`TXT-077` Segmenting a run longer than `maxWordLen` works by construction; runs shorter than it are
handled without index errors.

---

### Deferred (post-v1) and withdrawn

- `TXT-058` — withdrawn: there is no OCR entry point on Reading; OCR is reached only through its own
  destination, its shortcut and image sharing (`CAM-002`; decided: `Q-050`).

## Camera / OCR

**Android and iOS only** (`PROD-022`). Read [02 — Global Conventions](02-global-conventions.md) and
the Text Analysis part of this document first — the tap-to-word behaviour is shared.

This spec contains four **new requirements** that do not exist in the reference app: tap to focus
(`CAM-030`), explicit flash control (`CAM-035`), saving the captured image to the photo library
(`CAM-071`), and an auto-save setting (`CAM-080`). OCR is also a **primary destination** rather than
the reference app's modal surface (`CAM-006`…`CAM-009`).

---

### 1. Destination and entry points

`CAM-001` `[MUST]` Camera/OCR is the content of the `OCR` primary destination (`PROD-050`,
`PROD-052`), not a surface presented over another destination. It uses the standard destination
transition, and the primary navigation stays visible in every phase; content is laid out in the area
above the bottom bar (or beside the rail).

`CAM-002` `[MUST]` Entry points:
1. the `OCR` navigation destination;
2. a static launcher/quick-action shortcut `Open camera` (`SET-050`), which selects the OCR
   destination in the capture phase with the live feed, discarding any retained capture;
3. **Android only:** an image shared from another app — `ACTION_SEND` `image/*` (`SET-054`) — which
   selects the OCR destination and shows the shared image in review (`source = GALLERY`), replacing
   any retained capture. iOS has no share target in v1 (decided: `Q-042`).

There are no other entry points: neither the empty Search screen nor the Reading destination has OCR
access cards (decided: `Q-050`); recognising an existing image is done with the image-picker button
(`CAM-007`). Shared **text** goes to Reading (`TXT-080`).

`CAM-003` `[MUST]` Selecting the destination from the navigation shows the retained state (`CAM-008`):
the review of the retained capture if one exists, otherwise the live feed. Starting a new capture
(shutter, `Retake`, picking or receiving an image) replaces any previous capture, recognition result,
selection and error.

`CAM-004` `[MUST]` The destination has exactly two phases:
- **Capture phase** — no image yet: either the live viewfinder (source = camera) or a decode
  spinner/error (source = gallery/share).
- **Review phase** — an image exists: the image with recognition overlay, or the text list.

`CAM-005` `[MUST]` `source` is `CAMERA` or `GALLERY` and determines the review header's primary
action and whether the image can be saved (`CAM-072`).

`CAM-006` `[MUST]` Opening the OCR destination with no retained capture goes **straight to the live
camera feed** — no intermediate screen. If camera access is not granted, the viewfinder frame shows
the permission rationale panel instead (`CAM-010`…`CAM-015`); the image-picker button stays
available.

`CAM-007` `[MUST]` An **image-picker** icon button sits on the viewfinder, bottom-leading beside the
shutter, labelled `Choose from gallery`. It opens the platform photo picker (`CAM-016`); a picked
image goes to review with `source = GALLERY`. It is available in every capture-phase state, including
the permission panel and the camera-unavailable state (`CAM-015`).

`CAM-008` `[MUST]` **State retained, hardware released.** Leaving the OCR destination for another
destination keeps the capture state within the session: the captured image, its recognition result,
the Image/Text view choice, zoom/pan, text-list scroll position and flash state (`GLB-050`). An open
word-candidate sheet closes (`TXT-045`). The camera device and preview are released when the
destination is left (torch off) and reacquired on return when the capture phase is shown, re-checking
permission (`CAM-013`) and re-applying the flash state (`CAM-038`). A recognition in flight when the
user leaves runs to completion and its result is shown on return. Nothing is persisted across
launches (`SET-014`).

`CAM-009` `[MUST]` Re-tapping the already-selected OCR destination while reviewing a capture returns to
the live feed (`PROD-053`); in the capture phase it has no effect. On Web the destination and all its
entry points are absent (`PROD-022`).

---

### 2. Permission handling

`CAM-010` `[MUST]` The camera permission is requested **lazily**, only when the viewfinder is first
shown (the first visit to the OCR destination) — never at app start.

`CAM-011` `[MUST]` Pre-grant state shows a permission prompt panel inside the viewfinder frame: a
camera icon, the rationale `Camera access is needed to recognize Chinese text in photos.`, and one
button:
- first time: `Allow camera` (triggers the system prompt);
- after a soft denial: `Try again`;
- after a permanent denial: `Open app settings` (deep-links to the app's settings page).

`CAM-012` `[MUST]` Permanent denial is detected by the platform's "should show rationale" signal being
false after a denial (Android) / the authorisation status being `denied` or `restricted` (iOS).

`CAM-013` `[MUST]` Permission state is re-checked on every resume and on every return to the
destination, so granting it in system settings and returning starts the viewfinder without further
interaction.

`CAM-014` `[MUST]` The "already asked" flag survives configuration change so the copy does not revert
to `Allow camera` after a rotation.

`CAM-015` `[MUST]` If the camera device cannot be bound (no camera, hardware in use, emulator without
a camera), the frame shows `The camera is unavailable on this device.` and the image-picker path
remains available.

`CAM-016` `[MUST]` The gallery path uses the platform's **photo picker** (Android Photo Picker /
iOS `PHPickerViewController`), which grants access to the single chosen image only and therefore
requires **no** media permission. A broad photo-library read permission must not be requested.

`CAM-017` `[MUST]` Declared permissions (the merged-manifest policy and its CI check are
`ARCH-104`…`ARCH-106`):
- camera — `android.permission.CAMERA`; `NSCameraUsageDescription`;
- microphone — `android.permission.RECORD_AUDIO`; `NSMicrophoneUsageDescription` — used **only** by
  Voice transcription (`VOICE-010`), never by the camera;
- saving to the gallery — Android needs no permission when writing through `MediaStore` on API 29+,
  and `WRITE_EXTERNAL_STORAGE` with `android:maxSdkVersion="28"` for API 26–28 (minimum API 26:
  `PROD-025`; decided: `Q-092`); iOS needs `NSPhotoLibraryAddUsageDescription` (add-only; decided:
  `Q-112`).

Broad photo-library read permissions must never be declared.

---

### 3. Viewfinder

`CAM-020` `[MUST]` The preview is a **4:3** frame, centred in the area above the navigation,
horizontally inset 20 dp, with large corner rounding. 4:3 is deliberate: 16:9 modes crop sensor rows,
and OCR wants the full sensor area. The preview and the capture share the same aspect ratio so what
the user frames is exactly what is recognised.

`CAM-021` `[MUST]` Preview scaling is fit-centre (letterboxed), never cropped.

`CAM-022` `[MUST]` The rear camera is used by default. No front/rear toggle in v1.

`CAM-023` `[MUST]` A 72 dp circular accent shutter button sits bottom-centre inside the frame. While a
capture is in flight it is disabled and shows a 30 dp progress indicator.

`CAM-024` `[MUST]` Below the frame: any error text. (The image picker is on the viewfinder:
see `CAM-007`.)

`CAM-025` `[MUST]` The camera is bound only while the capture phase of the OCR destination is visible
and the app is in the foreground. It is released when the user leaves the destination (`CAM-008`) or
the app is backgrounded, and rebound on return. It may stay bound during review for a fast `Retake`,
with the torch off. A capture that completes after release is discarded and its resources closed.

`CAM-026` `[MUST]` Captures deliver an **upright** working image for analysis: the sensor rotation is
applied, EXIF is resolved, and the longest side is capped at 2560 px (bounding a decoded frame at
roughly 26 MB — an explicit exception to the single-allocation limit of `PERF-080`). All recognition,
overlay and hit-testing coordinates are in this upright working image's pixel space. The original
capture is kept separately for saving (`CAM-073`, `CAM-074`); it is never used for analysis.

`CAM-027` `[MUST]` Capture failure shows `Could not take a photo. Try again.`; post-processing failure
shows `Could not process the captured photo.` Both leave the viewfinder usable.

#### 3.1 Tap to focus — NEW

`CAM-030` `[MUST]` Tapping a point in the preview requests **autofocus and auto-exposure metering at
that point**.

`CAM-031` `[MUST]` The tap coordinate is converted from view space to sensor/preview metering space
using the same fit-centre transform as the preview, so the focus point is exactly under the finger.

`CAM-032` `[MUST]` A focus reticle is drawn at the tap point: it appears immediately, animates a small
scale-in, then indicates the outcome (converged / failed) and fades out after about 1.2 s.

`CAM-033` `[MUST]` The focus request has a timeout of about 5 s, after which the camera returns to
continuous autofocus. Tapping again cancels and replaces an in-flight request.

`CAM-034` `[MUST]` Where the device does not support point metering, the tap is a no-op with no
reticle and no error.

#### 3.2 Flash / torch — NEW

`CAM-035` `[MUST]` A flash control in the viewfinder's top-trailing corner cycles or toggles the
illumination mode. Minimum required behaviour: an explicit **on/off** toggle that the user controls.

`CAM-036` `[SHOULD]` Offer three states — `Off`, `On`, `Auto` — with icons, cycling on tap, and label
the current state for accessibility.

`CAM-037` `[MUST]` The control is hidden when the active camera reports no flash unit.

`CAM-038` `[MUST]` The chosen state persists for the session and is re-applied when the camera is
rebound after backgrounding or after returning to the destination. It is **not** persisted across
launches: every launch starts at `Off`, matching platform camera apps (decided: `Q-040`).

`CAM-039` `[MUST]` `On` means the flash fires at capture. If a torch mode is offered for framing in
low light it must be visually distinct from capture-flash and must be turned off whenever the camera
is released (`CAM-025`).

---

### 4. Image ingestion (picker / share)

`CAM-040` `[MUST]` Picking or receiving an image immediately switches the destination to the capture
phase with `source = GALLERY` and shows a centred spinner while decoding.

`CAM-041` `[MUST]` Decoding applies EXIF orientation and downscales to the same 2560 px cap, using a
hardware-independent (software) allocation so the bitmap can be read by the recogniser.

`CAM-042` `[MUST]` Decode failure shows the error text plus a `Choose another image` button; back (or
the `Camera` action) returns to the live feed. Messages: `Could not open that image.` (picker) /
`Could not open the shared image.` (share intent).

`CAM-043` `[MUST]` A shared image (Android) is **consumed** once — the intent action is cleared on
handling — so activity recreation, rotation or a later activation does not re-handle the same image.

`CAM-044` `[MUST]` Decoding must not be tied to a composable's lifetime; it runs on a scope that
survives the picker's own recomposition so a rotation mid-decode does not lose the image.

---

### 5. Recognition

`CAM-050` `[MUST]` Recognition runs on a background thread against the upright working image, with a
**20 s timeout**. While it runs, the review phase shows the image with a centred spinner over it; the
header and view toggle remain usable.

`CAM-051` `[MUST]` The recogniser must be a **Chinese-capable, on-device, offline** text recogniser
that makes no network request (mobile builds have no network permission, `ARCH-104`). Android: ML Kit
Chinese text recognition in its **bundled** variant (model inside the app, never downloaded at
runtime). iOS: Apple Vision (`VNRecognizeTextRequest` with `zh-Hans`/`zh-Hant`).

`CAM-052` `[MUST]` The recognition result is a list of **lines**. Each line has:
- `text` — the concatenation of its symbol texts, **not** the platform's line text. Platform line text
  joins elements with spaces and would desynchronise character offsets from geometry.
- `quad` — four corner points in clockwise order from top-left, in upright-image pixels (falls back to
  the axis-aligned bounding box when corner points are unavailable).
- `chars` — one entry per recognised symbol: its text (usually one Hanzi, possibly a surrogate pair or
  rarely a multi-glyph cluster), its UTF-16 start offset into `text`, and its quad.
- `hasHanzi` — whether the line contains any Hanzi (`GLB-001`).

`CAM-053` `[MUST]` When a recogniser returns elements without symbols (a real ML Kit behaviour for
some CJK outputs), fall back to per-code-point splitting of the element text sharing the element's
quad. Never drop the line.

`CAM-054` `[MUST]` Lines with no characters are dropped. If no surviving line contains Hanzi, show the
inline message `No Chinese text found. Retake to try again.` while still displaying the image.

`CAM-055` `[MUST]` Each recognised line's text is analysed into word segments (`TXT-010`) so its
characters are tappable. Analysis failure for one line leaves that line untappable (rendered as plain
muted text) without failing the whole capture.

`CAM-056` `[MUST]` Recognition failure shows `Unable to recognize text` (plus detail) and, for a
timeout, `Text recognition timed out. Try a smaller or clearer image.` The image stays on screen.

`CAM-057` `[MUST]` Because analyses are scoped to a data generation (`GLB-044`, `TXT-016`), replacing
the dataset while a capture is on screen must **re-run** the analysis against the new dataset (the
raw image and recognition result are dataset-independent). Taps are ignored until the re-analysis
completes.

---

### 6. Review phase

`CAM-060` `[MUST]` A translucent result header pinned to the top holds:
- a primary action: `Retake` (source = camera, returns to the live feed) or `Pick new image`
  (source = gallery), plus, for source = gallery, a `Camera` action returning to the live feed;
- the new `Save photo` action (`CAM-072`);
- a two-segment `Image` / `Text` view toggle.

`CAM-061` `[MUST]` Its bottom gradient shadow fades in only when the text list can scroll up.

#### 6.1 Image view

`CAM-062` `[MUST]` The image is displayed fit-centre on black, with the recognition overlay drawn in
the same transform:
- each line's quad filled at 10% accent and stroked in accent (1.5 dp, scaled down by the zoom so the
  stroke stays hairline);
- the selected character's quad filled at 35% accent and stroked in `onAccent`.

`CAM-063` `[MUST]` Pinch-to-zoom between 1x and 8x with pan. The pinch centroid stays stationary.
Translation is clamped so the image always covers the viewport (no rubber-band gaps).

`CAM-064` `[MUST]` Double-tap toggles between 1x and 2.5x, zooming toward the tapped point.

`CAM-065` `[MUST]` A single tap maps the view point back through (pan, zoom, fit-scale, letterbox) to
image pixels and hit-tests the recognised symbols:
- a point inside a symbol's quad wins immediately (convex containment, winding taken from the quad so
  rotated text works);
- otherwise the nearest symbol centre within a slop radius of 24 dp (divided by zoom and fit-scale, so
  the tolerance is constant in screen space) is selected;
- whitespace-only symbols are never selectable; a symbol's selectable offset is its first
  non-whitespace code point.

`CAM-066` `[MUST]` Selecting a character opens the word-candidate sheet for that (line, offset)
(`TXT-040`). The selection highlight moves immediately, before the lookup resolves.

`CAM-067` `[MUST]` Stepping to the previous/next character from the sheet header moves to the nearest
covered character (`GLB-058`, `TXT-033`) in reading order, crossing line boundaries, and also
**animates the image pan** (220 ms) so the newly selected glyph is centred in the viewport.

`CAM-068` `[MUST]` Zoom and pan reset when the image changes.

#### 6.2 Text view

`CAM-069` `[MUST]` A vertical list of cards:
- first, when any text was recognised, an `All text` card with the concatenation of all line texts
  (newline-joined) and the sentence action row (play / translate / copy, `MED-020`; translate is the
  external hand-off of `MED-030`…`MED-033`);
- then one card per line: the line rendered as an interactive segmented passage (word-boundary
  underlines, tone colours, interlinear readings, tappable characters — `TXT-030`, `TXT-034`), or
  plain muted text when the line could not be analysed; each with its own sentence action row.

`CAM-070` `[MUST]` Selecting a character in the text view opens the same sheet. Stepping moves to the
nearest covered character (`GLB-058`, `TXT-033`) in reading order across lines, skipping lines with no
covered character, and scrolls the list to the owning line, offset by the header height. A direction
is disabled when no covered character exists in any line in that direction.

#### 6.3 Save the captured image — NEW

`CAM-071` `[MUST]` The user can save the captured photograph to the device's **user-accessible photo
library / gallery**, so it survives app uninstall. App-private storage is not acceptable.

`CAM-072` `[MUST]` A `Save photo` action in the review header performs the save. It is:
- enabled only when the image came from the camera (there is nothing to save for an image already in
  the library, and for a shared image the app is not the owner);
- shown as a progress state while writing;
- confirmed with a brief inline confirmation (`Saved to Photos` / `Saved to Gallery`);
- reported inline on failure (`Could not save the photo.`) with the action still available to retry;
- idempotent for one capture: after a successful save the action shows a saved state and does not
  write a duplicate.

`CAM-073` `[MUST]` The saved image is the **original captured photograph**, not the 2560 px working
image used for analysis (`CAM-026`): full captured resolution, with sensor rotation applied (or
recorded as orientation metadata) and no OCR boxes, no segmentation underlines, no selection
highlight, and no UI overlay of any kind. The overlay exists only in the view layer.

`CAM-074` `[MUST]` The original capture is held as the camera's **encoded** output (in memory or in an
app-private cache file), never as a second decoded bitmap, and is deleted when the capture is
replaced, when a new capture starts, or when the process ends. It is never shared or logged.

`CAM-075` `[MUST]` Platform mechanics:
- **Android**: insert into `MediaStore.Images` under `Pictures/<AppName>/` with `IS_PENDING` during the
  write, JPEG at quality 92 (or the platform's lossless capture format when available), a
  timestamp-based display name, and correct `DATE_TAKEN`. No permission on API 29+; on API 26–28
  (the minimum is API 26, `PROD-025`) use the legacy path with the `WRITE_EXTERNAL_STORAGE`
  declaration scoped to `maxSdkVersion 28` (`CAM-017`).
- **iOS**: `PHPhotoLibrary` add-only request (`addOnlyAccessLevel`), creating an asset in the user's
  library; if the user has previously denied add access, show the failure message with a
  `Open Settings` affordance.

`CAM-076` `[MUST]` The save never blocks the UI thread and never blocks OCR.

#### 6.4 Auto-save setting — NEW

`CAM-080` `[MUST]` A persisted boolean setting, default **off**, labelled `Save photos to gallery`
with the helper text `Automatically save pictures you take in the app.` It lives in Settings
(`SET-010`).

`CAM-081` `[MUST]` When enabled, every **camera** capture is saved automatically, immediately after
capture, without further interaction. Picked and shared images are never auto-saved.

`CAM-082` `[MUST]` With auto-save on, the manual `Save photo` action shows the already-saved state for
that capture rather than offering a duplicate write.

`CAM-083` `[MUST]` The first auto-save on iOS may trigger the system add-only permission prompt. If it
is denied, auto-save is reported inline once per capture, the setting stays on, and the manual action
remains available. The app must not silently drop saves.

`CAM-084` `[MUST]` Auto-save failures must never block or delay showing the recognition result.

---

### 7. Lifecycle and background behaviour

`CAM-090` `[MUST]` Backgrounding during the capture phase releases the camera; returning rebinds it and
re-checks permission (`CAM-013`) and re-applies the flash state (`CAM-038`). Leaving the destination
behaves the same way (`CAM-008`).

`CAM-091` `[MUST]` Backgrounding or leaving the destination during the review phase keeps the image
and the recognition result; returning shows them without re-running OCR.

`CAM-092` `[MUST]` The recogniser is closed when the owning state holder is destroyed.

`CAM-093` `[MUST]` The working bitmap must be retained across configuration change and destination
switches without being re-decoded, and must be released as soon as its capture is replaced or
discarded (`CAM-003`).

---

### 8. Back navigation

`CAM-100` `[MUST]` Precedence within the OCR destination (`PROD-056`):
1. an open word-candidate sheet dismisses;
2. reviewing a capture (any source), or a gallery/share decode spinner or error, returns to the live
   feed, discarding that capture;
3. in the live feed, back follows the general precedence of `PROD-056` (ultimately the platform
   default).

---

### 9. Platform differences

| Concern | Android | iOS | Web |
|---------|---------|-----|-----|
| OCR destination | Required | Required | **Absent** (`PROD-022`) |
| Recogniser | ML Kit Chinese, bundled model (on-device) | Apple Vision | n/a |
| Image picker | Photo Picker (no permission) | PHPicker (no permission) | n/a |
| Save to library | MediaStore (`Pictures/<App>/`) | PHPhotoLibrary add-only | n/a |
| Save permission | none (API 29+); `WRITE_EXTERNAL_STORAGE` `maxSdkVersion 28` (API 26–28) | add-only usage description | n/a |
| Share-image target | `ACTION_SEND` `image/*` (`SET-054`) | none in v1 (`Q-042`) | n/a |
| Launcher shortcut | static shortcut | Home Screen quick action | n/a |

`CAM-110` `[MUST]` The camera, picker, recogniser and photo-library writer are each behind a small
platform-abstraction port so shared UI and state code are identical across platforms (`ARCH-090`).

---

### 10. Edge cases (all `[MUST]`)

`CAM-120` A capture with no recognised text still shows the photo, the `No Chinese text found` message,
and the working `Save photo` action.

`CAM-121` Rotated or skewed text: quads are non-axis-aligned; containment uses the quad's winding so
hit-testing still works.

`CAM-122` A recognised symbol without geometry is ignored for hit-testing but still contributes its
text to the line and to the text view.

`CAM-123` Non-Hanzi symbols (Latin, digits, punctuation) and uncovered Hanzi are selectable by tap —
the sheet shows `No dictionary entry` or the `「X」 appears in` list (`TXT-022`, `TXT-023`) — but
stepping skips them (`GLB-058`).

`CAM-124` A very large image (over the cap) is downscaled before recognition; overlay coordinates are
in the downscaled space, so overlay and image always agree.

`CAM-125` Tapping between two lines selects the nearest symbol within slop, or nothing at all.

`CAM-126` Rapid taps keep only the latest lookup (`GLB-040`).

`CAM-127` A capture arriving after the camera has been released (destination left or app backgrounded)
is discarded.

`CAM-128` Saving the same capture twice is prevented (`CAM-072`); saving after a retake saves the new
capture.

`CAM-129` If the photo library is full or unavailable, the save fails with the inline message and the
app continues normally.

## Handwriting input

Handwriting is an **in-app** input method for the Search field and the Reading field on Android, iOS
and Web. It is not a system-wide input method (`PROD-030`), and it is an input *method*, not a query
type: what it inserts is ordinary text, classified like typed text (`SRCH-001`).

---

### 1. Availability and the input-method button

`HW-001` `[MUST]` Handwriting is available in the Search field (`SRCH-090`) and the Reading field
(`TXT-050`) on Android, iOS and Web, in both dictionary modes.

`HW-002` `[MUST]` The current input method is one global value, `keyboard` or `handwriting`, shared by
both fields and persisted across launches as `input_method` (`SET-019`, default `keyboard`).

`HW-003` `[MUST]` **Input-method button.** Each field carries a trailing icon button. Its icon shows
the method a tap switches **to** (a pen while the keyboard is current, a keyboard while handwriting is
current), labelled `Switch to handwriting` / `Switch to keyboard`. **Every tap** switches the current
method to the other one, persists it, focuses the field and **shows** the new method — the soft
keyboard, or the handwriting sheet with the soft keyboard suppressed — opening it even when nothing
was visible before.

`HW-004` `[MUST]` **Tapping the text field shows the current method**: with `keyboard`, the soft
keyboard; with `handwriting`, the handwriting sheet, with the soft keyboard suppressed. The field keeps
focus, a visible caret, cursor placement and text selection in both methods.

`HW-005` `[MUST]` A hardware keyboard keeps working while handwriting is the current method; typed
characters are inserted as usual.

`HW-006` `[MUST]` The clear action of a field re-focuses it and re-shows the current method
(`GLB-059`, `SRCH-121`): the handwriting sheet when the method is `handwriting`.

---

### 2. The handwriting sheet

`HW-010` `[MUST]` A standard **non-modal** bottom sheet, `panel` coloured, capped at the same width as
the word-candidate sheet (`GLB-067`), with **no drag handle** and no drag-to-resize, and an `X` close
icon button at its top-trailing corner. Content behind it stays interactive (the field, results, the
passage).

`HW-011` `[MUST]` Contents, top to bottom:
- the **candidate row**: a horizontally scrolling row of candidates, best first, the first visually
  emphasised; empty while there is no ink; plus the close button;
- the **writing pad**: a framed area filling the sheet width, with a faint centre guide;
- a control row: `Undo stroke`, `Clear` (removes all ink), and `Delete` — a backspace key that
  deletes the character before the cursor in the field (or the selection), so the user can correct
  text without switching to the keyboard.

`HW-012` `[MUST]` The pad accepts finger, stylus and mouse input and draws ink in `fg` as the pointer
moves. A stroke ends on pointer up; each finished stroke triggers recognition of the whole current
ink.

`HW-013` `[MUST]` The candidate row shows up to 10 candidates after filtering and re-ranking
(`HW-032`). A candidate may be one character or several, when the user wrote several.

`HW-014` `[MUST]` `Undo stroke` removes the last stroke and re-recognises; `Clear` removes all ink and
candidates. Both are disabled when the pad is empty. `Delete` is enabled when there is text before
the cursor (or a selection); it edits the field only and leaves the ink untouched, and the edit
triggers search or analysis like any other edit.

`HW-015` `[MUST]` Tapping a candidate inserts it at the cursor (replacing any selection), clears the
pad, keeps the field focused and the sheet open, and triggers the normal per-keystroke search
(Search) or automatic analysis (Reading, `TXT-051`) exactly as typing would. A candidate commit is
never subject to the coalescing window (`GLB-043`).

`HW-016` `[MUST]` **Ink is never committed automatically.** The app never inserts a candidate by
itself — not the top candidate after a pause in writing, not when the user starts writing another
character, and not when the sheet closes. Text enters the field only when the user taps a candidate
(`HW-015`). Strokes written after a pause are added to the same ink and recognised together, so
writing several characters yields multi-character candidates (`HW-013`). Ink stays until a
candidate is tapped, `Clear` is used, or the sheet is closed (which discards it, `HW-024`).

---

### 3. Interplay with the screen

`HW-020` `[MUST]` Opening the word-candidate sheet (for example by tapping a passage character in
Reading or Search) **hides** the handwriting sheet, keeping its ink; closing the word sheet restores
it. The hide is programmatic and is not a dismissal (`TXT-045`). Only one of the two sheets is
visible at a time.

`HW-021` `[MUST]` The content behind is bottom-inset by exactly the visible sheet's height
(`TXT-044`); the soft keyboard is never shown together with the handwriting sheet.

`HW-022` `[MUST]` The primary navigation hides while the handwriting sheet is visible, as it does for
the soft keyboard (`GLB-059`, `GLB-073`, `SRCH-123`).

`HW-023` `[MUST]` Back order (`PROD-056`): an open word sheet closes first (restoring the handwriting
sheet); then the handwriting sheet closes; then the general precedence continues.

`HW-024` `[MUST]` The handwriting sheet closes, discarding its ink, on: the `X` button, back,
switching destination (`PROD-055`), opening an entry from results (the field drops focus, `GLB-059`,
`SRCH-122`; returning does not reopen it), switching to the keyboard (`HW-003`), and a dictionary mode
or script change. Closing it does not change the persisted input method.

`HW-025` `[MUST]` Rotation and configuration change keep the sheet's visibility and its ink
(`GLB-050`).

---

### 4. Recogniser

`HW-030` `[MUST]` Recognition is behind the `HandwritingRecognizer` port (`ARCH-093`) and uses the
**ML Kit Digital Ink Recognition** model:
- **Android and iOS:** the model weights are bundled in the app; there is no download and no network
  request.
- **Web:** a repackaged version of the same model is downloaded **lazily**, the first time the sheet
  is opened, then cached in persistent browser storage and verified by hash; later opens work
  offline. While it loads the pad shows `Loading handwriting…` with progress; on failure it shows
  `Handwriting is unavailable. Check your connection and try again.` with `Retry`, and the keyboard
  remains usable (`GLB-034`, `GLB-035`).

`HW-031` `[MUST]` The model is chosen by dictionary mode and script:

| Mode | Script | Model |
|------|--------|-------|
| Mandarin | Simplified | simplified Chinese |
| Mandarin | Traditional | traditional Chinese |
| Cantonese | Traditional | Hong Kong (traditional, with Cantonese characters) |
| Cantonese | Simplified | simplified Chinese |

A mode or script change switches the model and clears the ink.

`HW-032` `[MUST]` Candidate post-processing: the recogniser is asked for more candidates than are
shown; candidates containing any character absent from the active dataset (including non-Hanzi) are
dropped; the survivors are re-ranked by combining recogniser confidence with dataset character
frequency, so that among similar shapes common characters come first, while the recogniser's top
surviving candidate stays within the first three.

`HW-033` `[SHOULD]` Up to 20 characters before the cursor are passed to the recogniser as writing
context where the model supports it.

`HW-034` `[MUST]` Recognition runs off the UI thread and is latest-only per ink state (`GLB-040`): a
result computed for an older stroke set is never shown. Budget: `PERF-058`.

`HW-035` `[MUST]` The model is loaded on first open of the sheet (off the UI thread, with the loading
state of `HW-030` if it is not ready) and may be released under memory pressure or when the app is
backgrounded; it is reloaded on next use.

`HW-036` `[MUST]` A recogniser failure shows an inline message in the pad; typing via the keyboard is
unaffected.

---

### 5. Privacy and accessibility

`HW-040` `[MUST]` Strokes and ink are never logged, persisted or transmitted (`GLB-090`, `GLB-092`).
On Web the model download is the only network request handwriting makes and carries no user data.

`HW-041` `[MUST]` Labels (`GLB-080`): `Switch to handwriting`, `Switch to keyboard`,
`Close handwriting`, `Undo stroke`, `Clear writing`, `Delete`, the pad `Handwriting area`; each
candidate is announced as its text with its position (`Candidate 1 of 8`).

---

### 6. Platform differences

| Concern | Android | iOS | Web |
|---------|---------|-----|-----|
| Availability | Search, Reading | Search, Reading | Search, Reading |
| Model delivery | bundled in the app | bundled in the app | lazily downloaded on first open, cached |
| Soft keyboard suppression | IME not shown for the focused field | empty input view | `inputmode="none"` |

## Voice transcription

**Android and iOS only.** Voice is a primary destination on mobile; Web omits it entirely
(`PROD-022`, `PROD-023`). It transcribes Mandarin, Cantonese and English speech live and turns the
transcript into an interactive passage: segmented, tone-coloured, with readings, every Hanzi tappable.

---

### 1. Destination

`VOICE-001` `[MUST]` Voice is a primary destination (`PROD-050`) on Android and on every supported
iOS version (the iOS minimum is 26, `PROD-025`). On Web the destination, its route and any entry
point are absent; its route falls back per `GLB-112`.

`VOICE-002` `[MUST]` Layout, top to bottom:
- a header with the title `Voice`, the pronunciation toggle (`GLB-120`), and the actions `Copy`,
  `Clear` and `Open in Reading` (`VOICE-050`);
- the transcript area (`VOICE-030`), scrolling; with no transcript, the hint
  `Tap the microphone and speak Mandarin, Cantonese or English.`;
- a status line (`Listening…`, `Preparing…`, or an error);
- a bottom-centre 72 dp circular accent **start/stop** button.

While the voice model is not yet on the device, the transcript area shows the model download panel
instead and the start button is disabled (`VOICE-095`).

`VOICE-003` `[MUST]` Opening the destination never starts listening; listening starts only when the
user taps start. The button is labelled `Start listening` / `Stop listening` and, while listening,
shows a live input-level indication.

---

### 2. Microphone permission

`VOICE-010` `[MUST]` The microphone permission (Android `RECORD_AUDIO`; iOS
`NSMicrophoneUsageDescription`, `CAM-017`) is requested **lazily**, on the first tap of start — never
at app start and never on merely opening the destination.

`VOICE-011` `[MUST]` Denial handling reuses the camera patterns and the shared rationale component
(`CAM-011`…`CAM-014`, `ARCH-101`): after a denial the transcript area shows a microphone icon, the
rationale `Microphone access is needed to transcribe speech. Audio never leaves your device.`, and
`Try again` (soft denial) or `Open app settings` (permanent denial); permission is re-checked on every
resume.

`VOICE-012` `[MUST]` If the microphone cannot be opened (in use by a call or another app, no input
device), the status line shows `The microphone is unavailable right now.` and the button returns to
`Start listening`.

---

### 3. Recogniser and model

`VOICE-020` `[MUST]` Speech recognition is behind the `SpeechRecognizer` port (`ARCH-095`), fed by the
`AudioCapture` port (`ARCH-094`, 16 kHz mono PCM). The model is the sherpa-onnx **streaming
Paraformer trilingual** model (Mandarin, Cantonese, English) in its int8 form; the runtime is
sherpa-onnx with onnxruntime, included in the mobile builds only (`ARCH-014`).

`VOICE-021` `[MUST]` The model is the **only asset delivered after install** on mobile; every other
asset is installed with the app (`PROD-070`). Delivery per build: an on-demand Play Asset Delivery
pack on Google Play, inside the APK for direct downloads, and an Apple-hosted Background Assets pack
on iOS (`VOICE-095`). The download is performed by Google Play or by the system; the app itself
makes no network request for it, and recognition never uses the network. Size: `PERF-073`.

`VOICE-095` `[MUST]` **Voice model download state.**
- **Google Play build:** the pack is requested through Play Asset Delivery when the user **first
  opens** the Voice destination — never at install or app start. The download panel states the
  download size (`Voice transcription needs a one-time download of {size}.`, with the size reported
  by Play) and shows progress (`Downloading voice model… 45%`). When Play holds the download on
  mobile data (packs over 200 MB wait for Wi-Fi), the panel shows `Waiting for Wi-Fi` with
  `Download now`, which opens Play's own confirmation dialog; the app never bypasses it. Play's
  foreground-service permissions are allowed for this flavour only (`ARCH-107`); no `INTERNET`
  permission is added.
- **iOS:** the pack is an Apple-hosted Background Assets pack, requested when the user first opens
  the destination, with the same panel (size, progress, error). Background Assets packs need iOS 26,
  which is the iOS minimum (`PROD-025`; decided: `Q-116`).
- **Direct APK:** the model is inside the APK; there is no download state.
- A failed or cancelled download shows `Could not download the voice model.` with `Retry`
  (`GLB-034`). Leaving the destination does not cancel a download in progress; returning shows its
  current state. If the platform later removes the pack (for example after app data is cleared), the
  destination returns to the download panel.
- Once the pack is installed the model loads as in `VOICE-022`. The rest of the app never waits for
  or depends on the pack (`GLB-035`). The download carries no user data (`GLB-090`).

`VOICE-022` `[MUST]` Once the model is on the device, it is loaded off the UI thread when the
destination is opened, and released when the user leaves it (`VOICE-061`). If start is tapped
before loading completes, the status shows `Preparing…`; captured audio is buffered in memory and
recognised once the model is ready. Budgets: `PERF-057`.

`VOICE-023` `[MUST]` A missing or corrupt model in the direct APK, or a missing or corrupt pack in
the Play or iOS build configuration, is a **build error** (CI checks it). On Play and iOS a pack
that is not yet on the device is the download state of `VOICE-095`, not an error. If loading an
installed model nevertheless fails at runtime, the destination shows
`Voice transcription is unavailable.` and the rest of the app is unaffected (`ARCH-122`).

`VOICE-024` `[MUST]` Decoding uses **greedy search** only, so recognition within an utterance is
append-only: tokens are appended and never revised, and the character offsets of displayed text
never move.

`VOICE-025` `[MUST]` **Endpointing** commits the live utterance after about 1.2 s of trailing silence
following speech, or when it reaches 20 s; a longer silence with nothing decoded resets without
committing. Stopping commits a non-empty live utterance. These values are configuration constants.

`VOICE-026` `[MUST]` Spoken language is detected automatically and may switch within an utterance;
there is no language selector. Analysis always uses the active dictionary mode's dataset.

---

### 4. Transcript

`VOICE-030` `[MUST]` The transcript is an ordered list of **committed utterances** plus at most one
**live utterance**. Each utterance is one line; no punctuation is inserted in v1.

`VOICE-031` `[MUST]` Because committed utterances are frozen and the live utterance is append-only, a
(utterance, offset) position that has been displayed stays valid until the transcript is cleared.

`VOICE-032` `[MUST]` A committed utterance is analysed **once** (`TXT-010`, `HANZI` run mode) when it
is committed, and its analysis is frozen. It is re-analysed only when the dictionary mode, script or
data generation changes (`GLB-044`), in which case every utterance is re-analysed.

`VOICE-033` `[MUST]` The live utterance is re-segmented, re-coloured and given readings on **every**
recogniser update and is **shown immediately** with segmentation underlines, tone colours and
interlinear readings — no muted or tentative styling — updating as new tokens arrive. Analyses are
latest-only (`GLB-040`); while a newer analysis is pending, the previous one stays applied to its
prefix and newly appended characters render unmatched until it arrives (`GLB-030`).

`VOICE-034` `[MUST]` **Script.** The recogniser outputs simplified characters plus Cantonese
colloquial characters. Analysis runs on the recognised text. For display, a matched segment shows the
matched entry's headword in the user's script (`SET-011`); an unmatched Hanzi is converted with a
character-level OpenCC table shipped with the app (simplified → traditional in Mandarin mode,
simplified → Hong Kong traditional in Cantonese mode). Conversion is one code point for one code
point, so display offsets equal analysis offsets. `Copy` and `Open in Reading` use the displayed text.

`VOICE-035` `[MUST]` English words are displayed as recognised but are not analysed (`HANZI` run
mode, `TXT-011`); tapping them behaves as `TXT-023`.

---

### 5. Interaction

`VOICE-040` `[MUST]` Each utterance is rendered with the shared passage renderer
(`TXT-030`, `TXT-032`) and interlinear readings (`TXT-034`, `TXT-035`). The list of utterances is
virtualised so long sessions scroll smoothly. Tapping any character, in a committed or the live utterance, opens the
word-candidate sheet (`TXT-040`) anchored by (utterance, offset).

`VOICE-041` `[MUST]` The sheet **stays open** while the transcript changes (exception in `TXT-045`):
when the live utterance holding the selection is re-segmented, the sheet keeps the selected
character and refreshes its candidates in place (`TXT-042`); committing that utterance keeps it as
well. The sheet closes on `Clear` and on the general triggers of `TXT-045`.

`VOICE-042` `[MUST]` Stepping (`GLB-058`, `TXT-033`) crosses utterances in transcript order and skips
uncovered characters; the live utterance's coverage is recomputed with each analysis, so a direction
may become enabled as speech arrives.

`VOICE-043` `[MUST]` While listening, the transcript keeps the live line in view, unless the user has
scrolled away from the end or the word sheet is open — then nothing moves under the user, and a
`Jump to latest` control appears.

---

### 6. Actions

`VOICE-050` `[MUST]`
- `Copy` copies the transcript as displayed, one line per utterance, including the live utterance.
- `Clear` empties the transcript, closes the word sheet and, if listening, continues with a fresh
  live utterance.
- `Open in Reading` stops listening, switches to the Reading destination, replaces its draft with the
  transcript and analyses it (`TXT-051`).

All three are disabled while the transcript is empty.

---

### 7. Lifecycle and audio

`VOICE-060` `[MUST]` The transcript is kept while switching destinations and across configuration
change within a session (`GLB-050`), and is **not** persisted across launches (`SET-014`).

`VOICE-061` `[MUST]` Leaving the destination stops listening (committing the live utterance) and
releases the audio capture and the model. Returning reloads the model but does not restart listening.

`VOICE-062` `[MUST]` Backgrounding the app, or an audio interruption (call, another app taking the
microphone or audio focus), stops listening immediately and commits the live utterance. There is no
background recording.

`VOICE-063` `[MUST]` Recording and playback are exclusive (`MED-002`): starting to listen stops any
pronunciation playback or TTS; while listening, play buttons (in the word sheet and elsewhere) are
disabled.

---

### 8. Privacy

`VOICE-070` `[MUST]` Audio exists only in memory while being recognised. It is never written to disk,
logged or transmitted. Transcripts are never logged or transmitted (`GLB-090`, `GLB-092`) and are not
persisted (`VOICE-060`).

---

### 9. Concurrency and performance

`VOICE-080` `[MUST]` Operation families (`GLB-040`): `voice transcription` (only the latest recogniser
output is rendered) and `live re-segmentation` (only the latest analysis of the live utterance is
applied); the word-candidate lookup family is shared with other passages. Analysis of a committed
utterance is never superseded by live updates.

`VOICE-081` `[MUST]` Audio capture runs on its own thread and must not drop frames under load;
recognition and analysis run off the UI thread.

`VOICE-082` `[MUST]` Budgets: model load, first partial result and the voice-session memory exception
— `PERF-057`; live re-analysis — `PERF-050`.

---

### 10. Edge cases (all `[MUST]`)

`VOICE-090` Silence only produces no utterance.

`VOICE-091` An utterance that is entirely English is shown as a line with no tappable words.

`VOICE-092` A mode or script change while listening re-analyses all utterances; listening continues.

`VOICE-093` Stopping and starting again appends to the existing transcript.

`VOICE-094` A selection in the live utterance survives that utterance's commit and the next
utterance's start.

---

### 11. Platform differences

| Concern | Android | iOS | Web |
|---------|---------|-----|-----|
| Destination | Required | Required | **Absent** |
| Audio capture | `AudioRecord` | `AVAudioEngine` | n/a |
| Model delivery | on-demand Play Asset Delivery pack, fetched on first open (Play) / inside APK (direct) | Apple-hosted Background Assets pack, fetched on first open | n/a |
| Permission | `RECORD_AUDIO` | `NSMicrophoneUsageDescription` | n/a |
