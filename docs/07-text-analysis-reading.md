# 07 — Text Analysis, Segmentation and Reading

This document owns the **shared** text-analysis engine behaviour (segmentation, span candidates, the
word-candidate sheet, interactive passage rendering) used by Search, Reading, Camera and Entry Detail,
plus the Reading destination itself.

---

## 1. Segmentation

`TXT-010` `[MUST]` Analysing a text produces an ordered list of **segments**, each with a start
offset, a length, and the canonical reading of the entry chosen for it (used for tone colouring).

`TXT-011` `[MUST]` Segmentation operates on **runs**. In `HANZI` mode a run is a maximal sequence of
Hanzi; in `FULL` mode a run is a maximal sequence of non-whitespace characters. Search's Chinese side
uses `FULL` (so punctuation and Latin inside a pasted query participate); Reading and Camera use
`HANZI` (so surrounding prose is skipped).

`TXT-012` `[MUST]` **With frequency data (preferred): a DAG maximum-likelihood segmentation.**
Scanning the run right-to-left, `score[i] = max over lengths L of (logProb(word) + score[i+L])`, where
`logProb(word) = ln(freq(word)) - ln(totalFreq)` for words present in the dictionary,
`ln(1) - ln(totalFreq)` for dictionary words with no frequency entry, and a fixed penalty of `-20.0`
for a single unknown character. Ties prefer the **longer** word. Only lengths up to the dataset's
maximum headword length are considered.

`TXT-013` `[MUST]` **Without frequency data: greedy longest-match**, left to right, lengths from the
maximum headword length down to 2, falling back to a single character.

The two strategies differ observably and both must be supported: `我们研究生命科学` segments as
`我们 / 研究 / 生命 / 科学` with frequency data, and as `我们 / 研究生 / 命 / 科学` with greedy
longest-match.

`TXT-014` `[MUST]` Segmentation must be **bounded and batched**: all candidate substrings of a run are
looked up in a small number of batched queries, not one query per candidate. On the reference data a
100-character passage generated 2415 individual lookups (about 9 ms on desktop) versus 2 batched
queries (about 0.34 ms); a 500-character passage 12604 lookups (about 49 ms) versus 2 queries (about
1 ms). Batching is mandatory (finding `F-060`).

`TXT-015` `[MUST]` A passage longer than a defined limit is segmented in chunks at natural boundaries
(punctuation, then whitespace, then a hard cap) so no single operation exceeds the frame budget.
Recommended chunk: 400 characters.

`TXT-016` `[MUST]` An analysed passage is registered under an integer **passage id** with an LRU cache
keyed by (mode, text), capacity 128. Re-analysing the same text returns the same id. Passage ids are
**dataset-scoped** and are invalidated when the dataset generation changes (`GLB-044`).

`TXT-017` `[SHOULD]` The successor should prefer passing the analysed passage itself (immutable value)
across layers rather than an opaque server-side id, removing a whole class of staleness bugs.
*(Architecture note — `ARCH-070`.)*

---

## 2. Span candidates (what a tapped character can mean)

`TXT-020` `[MUST]` Tapping index `i` in a passage produces every dictionary word that **covers** `i`:
for every start `s` in `[max(runStart, i - (maxWordLen - 1)), i]` and every length `L` such that
`s + L > i` and `s + L <= runEnd` and `L <= maxWordLen`, the substring `[s, s+L)` if it is a dictionary
headword.

`TXT-021` `[MUST]` Candidate ordering:
1. the **default** candidate first — the segment the segmenter actually chose for this position;
2. then longer spans before shorter;
3. then common readings before proper-noun readings;
4. then frequency rank ascending;
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

## 3. Interactive passage rendering

`TXT-030` `[MUST]` A passage is rendered as **one** text layout (never one composable per character),
with:
- base colour `muted`; matched segments in `fg`; per-character tone colours when tone colouring is on
  and a segment's tone count equals its character count;
- the selected character's code-point range highlighted with `highlight` background and semibold
  weight;
- word-boundary underlines when the boundary setting is on: for each matched segment, an underline
  drawn per character with 1.6 dp stroke, inset 1.25 dp at the word's leading and trailing edges (so
  adjacent words read as separate strokes), coloured in alternating shades (`GLB-061`) that flip per
  word;
- taps resolved by hit-testing the text layout for a character offset, then normalising to the start of
  the nearest **visible** code point.

`TXT-031` `[MUST]` Underlines are drawn from the layout's per-glyph bounding boxes so they follow line
wrapping correctly.

`TXT-032` `[MUST]` This renderer is shared verbatim by: the Search sentence card, the Reading passage,
and each Camera text line.

`TXT-033` `[MUST]` **Visible characters** for stepping and tapping include punctuation, Latin, digits
and emoji, and exclude only space, tab, CR and LF.

---

## 4. The word-candidate sheet

`TXT-040` `[MUST]` A **non-modal** bottom sheet, opened at a compact peek height of 240 dp, draggable
to full height, capped at 720 dp wide, `panel` coloured, with a 32x4 dp drag handle.

`TXT-041` `[MUST]` Header row: the title (`Words at 「X」` / `「X」 appears in` / `At 「X」`), then the
previous/next character stepper group (`GLB-058`, omitted when there is no navigable text context),
then a close button.

`TXT-042` `[MUST]` While a new lookup for the same context is in flight, a 2 dp linear progress
indicator appears under the header and the previous rows stay visible and dimmed-disabled. The sheet
does **not** close and reopen when the selection moves.

`TXT-043` `[MUST]` Rows are compact entry cards (`SRCH-097`) including merge variants, definitions and
audio buttons. Empty state (after loading finishes): `No dictionary entry`.

`TXT-044` `[MUST]` The screen behind the sheet is padded at the bottom by exactly the currently
**visible** sheet height, live during the drag, so content is never hidden behind it and the padding
collapses to zero when it hides.

`TXT-045` `[MUST]` The sheet closes on: the close button, a drag down to hidden, back, tapping a row
(which opens the entry), changing destination, changing script or dictionary mode, and any change to
the underlying passage. A programmatic hide must not be mistaken for a user dismissal.

`TXT-046` `[MUST]` The bottom safe inset is applied as a trailing list item inside the sheet, not as
padding on the sheet, so the drag handle stays at the top edge and the full list remains reachable.

---

## 5. Reading destination

`TXT-050` `[MUST]` The Reading destination contains, top to bottom:
- a multi-line outlined text field (minimum 4 lines) with the placeholder
  `Paste or type a Chinese passage…`;
- a wrapping button row: `Paste` (filled accent, disabled when the clipboard has no usable text) and
  `Clear` (outlined, disabled when the draft is blank);
- optional status text (clipboard message, `Reading…`, or an error);
- when analysed: the passage card (`TXT-030`) and the sentence action row (`MED-030`).

`TXT-051` `[MUST]` Typing or pasting analyses the text **automatically** as soon as it is non-blank;
there is no separate "Analyze" button. Editing cancels the previous analysis and clears the previous
result.

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
(`SET-051`).

`TXT-058` `[SHOULD]` The Reading destination should offer the same OCR access cards as the empty
Search screen, since "read this text" and "read this photo" are the same intent.
*(Improvement — `Q-050`.)*

`TXT-059` `[MUST]` Web platform: the clipboard read requires a user gesture and may require permission;
the `Paste` button must be present and must surface a permission failure inline rather than appearing
broken.

---

## 6. Performance

`TXT-060` `[MUST]` Targets: `PERF-040`. Analysing a 100-character passage: at most 30 ms. Opening the
sheet for a tapped character: at most 60 ms p95.

`TXT-061` `[MUST]` Analysis, candidate enumeration and hydration all run off the UI thread and are
cancellable.

---

## 7. Edge cases (all `[MUST]`)

`TXT-070` A tap on the very first or last character disables the corresponding stepper direction.

`TXT-071` A supplementary-plane Hanzi is one tappable unit and its highlight covers both UTF-16 units.

`TXT-072` A passage containing only punctuation produces one inert group and, in Search, `isSentence`
true with a single inert row.

`TXT-073` Adjacent unmatched segments merge into one inert group; a matched segment between them splits
them.

`TXT-074` A text whose analysis fails leaves the draft intact, shows the error, and lets the user retry
by editing.

`TXT-075` A stale passage id (from a previous dataset) must return an empty span result, never throw.

`TXT-076` Tapping the same character twice re-opens (or keeps open) the same sheet without flicker.

`TXT-077` Segmenting a run longer than the maximum headword length works by construction; runs shorter
than the maximum are handled without index errors.
