# 02 — Global Conventions

Shared rules referenced by every feature specification. Feature specs must not restate these; they
reference the ID.

---

## 1. Text, script and characters

`GLB-001` `[MUST]` **Hanzi definition.** A code point is treated as Hanzi if it falls in
`U+3400–U+4DBF` (Ext A), `U+4E00–U+9FFF` (URO), `U+F900–U+FAFF` (Compatibility Ideographs),
`U+20000–U+323AF` (Ext B–I), or equals `U+3007` (〇). This exact set governs tappability,
segmentation runs, and character indexing everywhere in the app.

`GLB-002` `[MUST]` All character iteration, tap-target computation, offsets, and highlight ranges
operate on **code points**, never UTF-16 code units. A supplementary-plane Hanzi is one tappable
unit, and selecting it highlights the whole code point. Offsets exposed across API boundaries are
UTF-16 offsets into the source string for interoperability, but a tap anywhere inside a surrogate
pair normalises to the start of the code point.

`GLB-003` `[MUST]` **Script mode** is `SIMPLIFIED` or `TRADITIONAL`. For any entry, the *headword*
is the simplified or traditional form according to script mode; the *alternate form* is the other one,
shown in muted text beside the headword **only when it differs**.

`GLB-004` `[MUST]` Script mode affects display and grouping only. Search always matches both
simplified and traditional forms regardless of script mode (`SRCH-030`).

`GLB-005` `[MUST]` **Middle-dot normalisation.** `・` (U+30FB), `‧` (U+2027), `·` (U+00B7), `･`,
`•` all normalise to `·` for matching purposes. `马丁·路德·金` and `马丁・路德•金` are the same
headword. Precomputed in the dataset (`DATA-071`).

`GLB-006` `[MUST]` **Full-width and compatibility folding.** Latin letters inside headwords fold
via NFKC for matching: `Ｔ恤` matches `t恤`. Precomputed (`DATA-071`).

`GLB-007` `[MUST]` **ASCII case insensitivity.** Headwords containing Latin letters (`K金`, `AA制`,
`X光`) match case-insensitively in both directions. Handled by a case-folded index column in the
dataset — the app must **not** enumerate case permutations at query time (finding `F-020`).

---

## 2. Pronunciation and romanisation

`GLB-010` `[MUST]` The app is **romanisation-agnostic**. It knows only: a per-entry ordered list of
*syllables* (lowercase, toneless, ASCII+`ü`), a parallel list of *tone numbers*, a display string with
diacritics or trailing digits, and a canonical numbered string. It never contains a hard-coded
Mandarin syllable table; the syllable inventory and tone count arrive with the dataset (`DATA-070`).

`GLB-011` `[MUST]` Mandarin mode: display uses tone-marked Pinyin (`nǐ hǎo`), tones `1–5`
(5 = neutral). Cantonese mode: display uses Jyutping with trailing tone digits (`nei5 hou2`),
tones `1–6`.

`GLB-012` `[MUST]` **Pronunciation input accepts, per mode:** plain toneless (`nihao`, `neihou`),
digit-toned (`ni3hao3`, `nei5hou2`), diacritic-toned Mandarin (`nǐhǎo`), `u:`/`v` as `ü`, apostrophes
and spaces as explicit syllable boundaries, and mixed forms (`ni3 hao`). Parsing rules: `SRCH-040`.

`GLB-013` `[MUST]` Uppercase-initial romanisation marks a **proper-noun reading**. Proper-noun
readings sort after common readings at equal rank (`SRCH-070`).

`GLB-014` `[MUST]` **Non-Mandarin romanisation inside definitions must not be re-rendered as
Mandarin.** CEDICT prose contains bracketed romanisations for Cantonese (Jyutping), Hokkien
(Tâi-lô/POJ), Teochew, and Wade-Giles. A bracketed reading is converted to tone marks only when the
preceding context does not indicate a non-Mandarin system. Provenance must be resolved in the
pipeline (`DATA-053`) rather than re-derived from prose at runtime; the app renders what the dataset
labels.

`GLB-015` `[MUST]` Tone-mark placement rule for numbered→marked conversion (used only if the app
ever converts, e.g. for user input echo): mark the first `a` or `e`; otherwise the `o` of `ou`;
otherwise the last vowel among `i o u ü`. Precomputed forms are preferred (`DATA-010`).

---

## 3. Loading, empty, and error states

`GLB-030` `[MUST]` **Never blank populated content.** When a new request supersedes displayed
content, the previous content stays on screen until the replacement is ready. Applies to search
results on query/side/script/mode change, to the word-candidate sheet on re-selection, and to entry
detail on script change.

`GLB-031` `[MUST]` **Shape-matched skeletons for first loads.** A first load with no prior content
shows a skeleton whose block sizes approximate the real content (entry detail: `ENT-016`;
HSK: `HSK-030`). Never a bare centred spinner on a full screen except where explicitly specified
(image decode: `CAM-054`; OCR in progress: `CAM-050`).

`GLB-032` `[MUST]` A skeleton keeps its navigation affordances live (back / close work while the
content loads).

`GLB-033` `[MUST]` **Progress for in-place refresh** is a 2 dp determinate-less linear indicator at
the top of the affected surface, not an overlay (word-candidate sheet: `TXT-042`).

`GLB-034` `[MUST]` **Error text is inline, human, and actionable**, placed where the content would
have been, and prefixed with the operation: e.g. `Unable to load entry`, `Unable to search`,
`Unable to load word choices`, `Unable to analyze text`, `Unable to recognize text`. If the
underlying failure has a message, it is appended after a colon. A failed feature must leave the app
in a **recoverable, non-busy** state — retrying the same gesture must be possible with no restart.

`GLB-035` `[MUST]` **Graceful asset absence.** If an optional asset family is missing (audio, strokes,
origins, examples), the corresponding control is disabled or the section is omitted. Missing assets
never produce an error message, never block the entry from rendering, and never remove the entry's
core content.

`GLB-036` `[MUST]` **Empty vs no-result are different.** "Empty" = no query yet, and shows guidance
plus discovery content (`SRCH-100`). "No result" = a query that matched nothing, and shows a single
centred neutral message (`SRCH-102`).

---

## 4. Concurrency, cancellation, and staleness

`GLB-040` `[MUST]` **Latest-only for every user-driven async operation.** Each operation family
(search, word-candidate lookup, entry detail, passage analysis, OCR, HSK load, audio playback, TTS)
maintains a monotonically increasing request generation. A completed result is applied **only if**
its generation is still current *and* the state it was computed for (query, script, mode, side,
passage identity, route identity) still matches. Cancellation alone is never treated as sufficient —
a non-cooperative in-flight operation must not be able to overwrite newer state when it finally
returns.

`GLB-041` `[MUST]` **Conflate, don't queue.** While one search is executing, additional inputs
collapse to the single newest request. Rapidly typing then backspacing ten characters must complete
exactly one search — the last one.

`GLB-042` `[MUST]` **Blank queries bypass any coalescing delay** and execute immediately.

`GLB-043` `[SHOULD]` No artificial input debounce by default. The target is that a query is cheap
enough to run per keystroke (`PERF-020`). A configurable coalescing window (0–100 ms) may exist for
benchmarking and for low-end device tuning, defaulting to `0`.

`GLB-044` `[MUST]` **Data-context generation.** Replacing or reloading the dataset (mode switch,
downloaded asset pack becoming available) bumps a data generation counter that invalidates every
outstanding request, every cached derived model, and every identifier that is only meaningful within
one dataset (notably passage ids and entry ids). Stale identifiers must never be dereferenced against
a new dataset.

`GLB-045` `[MUST]` Long-running queries must be **cancellable at the storage layer**, not merely at
the coroutine/promise layer, so that abandoning a query stops the underlying scan.

`GLB-046` `[MUST]` No dictionary work on the UI thread. All lookup, ranking, hydration, segmentation,
tokenisation, decoding, and model building happen off the main thread. Only rendering and state
diffing happen on it.

---

## 5. State preservation

`GLB-050` `[MUST]` Within a session (including rotation and process recreation from a saved state
bundle), the following survive:
- active primary destination;
- the entry-detail stack: each route's identity, its selected tab, its header collapse fraction, and
  each tab's independent scroll position;
- the search query, search side, and results scroll position;
- the camera capture and its recognition result;
- the reading draft text and its analysis.

`GLB-051` `[MUST]` Route-scoped saved state is keyed by a **unique per-visit route key**, not by
entry id. Opening the same word twice at different stack depths yields independent scroll/tab state.

`GLB-052` `[MUST]` Popping a route releases its saved state. Pushing a nested route must not dispose
the parent's saved state.

---

## 6. Interaction conventions

`GLB-055` `[MUST]` **One-tap-one-selection.** Tapping a Chinese character selects exactly that
character; any prior selection is cleared. There is no multi-select.

`GLB-056` `[MUST]` **Long press = text selection.** Long-pressing definitions, headwords,
pronunciation, and example sentences enters native text selection and must **not** activate the
link/tap behaviour of the pressed span. A normal tap on a link still follows it.

`GLB-057` `[MUST]` **Sheet-based disambiguation.** Whenever a tapped position could belong to more
than one dictionary word, the choice is presented in a non-modal bottom sheet that opens at a compact
peek height (~240 dp), can be dragged to full height, and dismisses by drag-down, an explicit close
button, or back. The content behind it stays interactive and is bottom-inset by exactly the visible
sheet height. Full spec: `TXT-040`.

`GLB-058` `[MUST]` **Character stepping.** Where a selection sits inside a known text (search
passage, reading passage, example sentence, OCR line), the sheet header offers previous/next controls
that move the selection to the adjacent *visible* character (skipping whitespace, including
punctuation/Latin/emoji), disabled at the ends. In the camera image view, stepping also pans the
image to centre the newly selected glyph.

`GLB-059` `[MUST]` **Focus discipline.** Leaving the search field to open an entry drops field focus
so the soft keyboard does not reopen when returning. Clearing the query with the clear button
re-focuses the field and re-shows the keyboard. The primary navigation hides while the soft keyboard
is visible.

---

## 7. Visual design tokens

`GLB-060` `[SHOULD]` Palette (light / dark):

| Token | Light | Dark | Use |
|-------|-------|------|-----|
| `bg` | `#FAF7F1` | `#17140F` | screen background |
| `bgSoft` | `#F2EEE5` | `#242019` | inert rows, skeleton blocks, segmented track |
| `panel` | `#FFFDF8` | `#1E1A14` | cards, sheets, bars |
| `fg` | `#221F1A` | `#EAE4D8` | primary text |
| `muted` | `#6F695E` | `#A69E8F` | secondary text, labels |
| `border` | `#DDD6C8` | `#3C362B` | hairline borders, dividers, baselines |
| `accent` | `#A53A2B` | `#D46A58` | seal red: links, active segment, play buttons |
| `accentSoft` | `#F3E4DF` | `#43241D` | pill/expander wash |
| `onAccent` | `#FFFFFF` | `#1B0E0B` | text on accent |
| `highlight` | `#F4E9C8` | `#4A3F1E` | selected character background, in-sentence match |
| `indigoGray` | `#5A6478` | `#97A0B4` | secondary accent |

`GLB-061` `[SHOULD]` Word-boundary underline shades: `segA = #1E6FD0` at 25% / 35% alpha, `segB` at
7% / 13% alpha, alternating per word.

`GLB-062` `[SHOULD]` Corner radii: 4 / 8 / 10 / 12 / 16 dp (extraSmall…extraLarge). Cards use 10 dp,
the floating search surface 16 dp, pills fully rounded.

`GLB-063` `[SHOULD]` Mandarin tone colours (light / dark): T1 `#B3382C`/`#E0685A`,
T2 `#B07514`/`#D99C2E`, T3 `#33803F`/`#63AC70`, T4 `#2B5F9E`/`#6F9FD6`,
T5 (neutral) `#8A857C`/`#9C968B`.

`GLB-064` `[MUST]` Cantonese mode requires **six** tone colours. Recommended: reuse T1–T4 for
Jyutping 1–4, add T5 `#7A5A24`/`#C49A4E` and T6 `#5A6478`/`#97A0B4`. The palette must be indexed by
tone number with a safe fallback for out-of-range values.

`GLB-065` `[SHOULD]` HSK level colours 1–7 (light): `#33803F`, `#1F7F74`, `#2B5F9E`, `#5D4D9C`,
`#9C3F74`, `#B3382C`, `#7A5A24`; dark: `#63AC70`, `#4FAB9E`, `#6F9FD6`, `#9688CE`, `#C677A2`,
`#E0685A`, `#C49A4E`. Matching paper tints for card backgrounds (light): `#EDF3E8`, `#E8F2EE`,
`#E9EFF5`, `#EEEBF4`, `#F5EAF0`, `#F7EBE7`, `#F4EEE1`; dark: `#1E2A1B`, `#182A26`, `#1A222D`,
`#221E2E`, `#2C1D26`, `#2E1B17`, `#2A2417`.

`GLB-066` `[MUST]` **Tone colouring is applied only when it can be applied correctly**: the
character count and the syllable count must match exactly, otherwise the text renders in its normal
colour. Never partially colour a mismatched pair.

`GLB-067` `[SHOULD]` Content is constrained to a maximum readable width (~900 dp) and centred on
wide viewports. The word-candidate sheet caps at ~720 dp.

---

## 8. Edge-to-edge and system UI

`GLB-070` `[MUST]` The app draws edge-to-edge. System bars are transparent; their icon contrast
follows the resolved theme.

`GLB-071` `[MUST]` Content scrolls **beneath** the status bar; a top-aligned vertical gradient scrim
from `bg` to transparent (status-bar height + 12 dp) keeps it legible. No opaque top strip.

`GLB-072` `[MUST]` Lists apply top content padding equal to the status-bar inset plus the height of
any floating header, and bottom padding equal to the bottom safe inset plus 16–24 dp, so first and
last items are fully reachable.

`GLB-073` `[MUST]` The soft keyboard inset is consumed by the scaffold so the focused field and the
list above it remain visible; the primary navigation hides while the keyboard is up.

`GLB-074` `[MUST]` Floating headers (search control, HSK level selector, camera result header) are
translucent (~88% `bg`) with a bottom-only gradient shadow that fades in only when the content behind
them can scroll up.

---

## 9. Accessibility

`GLB-080` `[MUST]` Every icon-only control has a descriptive label: `Play pronunciation`,
`Stop sentence playback`, `Clear search`, `Paste clipboard into search`, `Previous character`,
`Next character`, `Close word options`, `Back`, `Close detail`, `Take photo`, `Toggle flash`,
`Save photo`.

`GLB-081` `[MUST]` Interactive targets are at least 40×40 dp; the compact pills and chips that render
smaller must expand their touch area to 40 dp. The camera shutter is 72 dp.

`GLB-082` `[MUST]` Text scales with the platform font-size setting. Headwords, definitions, and
pronunciation must remain readable and must wrap rather than clip at the largest supported scale.
Long headwords (CC-CEDICT contains headwords up to 19 characters) step down one type size above 8
characters and wrap across lines.

`GLB-083` `[MUST]` Colour is never the only carrier of meaning. Tone colouring is decorative and
off-switchable; word boundaries are conveyed by underlines (not colour alone) and are
off-switchable; matched vs unmatched spans differ in both colour and, for unmatched runs, an explicit
`NO MATCH` label.

`GLB-084` `[MUST]` A screen reader must be able to reach every result card, read headword +
pronunciation + definitions as one coherent label, and activate it. Tappable characters inside
passages expose per-character actions rather than one opaque blob.

`GLB-085` `[SHOULD]` Respect the platform reduce-motion setting: route transitions, sheet motion,
pulse animations, and the stroke animation reduce to cross-fades or static states.

`GLB-086` `[SHOULD]` Contrast: body and label text meets WCAG AA against its background in both
themes. Tone colours are exempt (decorative) but the underlying text colour without tone colouring
must meet AA.

---

## 10. Analytics, logging, diagnostics

`GLB-090` `[SHOULD]` No behavioural telemetry is required for v1. If added, it must be opt-in and
must never transmit dictionary queries, OCR text, clipboard content, or images.

`GLB-091` `[SHOULD]` Structured, named performance trace sections must be emitted around: query
classification, storage read, hydration, ranking, variant grouping, segmentation, state emission,
HSK model build, and the input→first-frame span. Names and use: `PERF-100`.

`GLB-092` `[MUST]` Diagnostic logging must never log clipboard text, OCR text, image bytes, or file
URIs beyond scheme/authority.
