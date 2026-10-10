# 02 — Global Conventions

Shared rules referenced by every feature specification. Feature specs must not restate these; they
reference the ID.

---

## 1. Text, script and characters

`GLB-001` `[MUST]` **Hanzi definition.** A code point is treated as Hanzi if it has the Unicode
property `Unified_Ideograph=Yes` (URO, Ext A, Ext B–J and the twelve unified ideographs inside the
Compatibility Ideographs block) or equals `U+3007` (〇). The set is generated from one pinned Unicode
Character Database version (at least Unicode 17.0, which adds Ext J) and compiled into shared code,
never taken from platform APIs whose Unicode version differs between Android, iOS and the browser.
This exact set governs tappability, segmentation runs, and character indexing everywhere in the app.
Kangxi Radicals (`U+2F00–U+2FDF`) and CJK Compatibility Ideographs (`U+F900–U+FAFF`,
`U+2F800–U+2FA1F`) are folded to their unified ideograph by NFKC in the matching key (`SRCH-010`) and
are then treated as that Hanzi; displayed text keeps the original code point. Dataset keys are
normalised in exactly the same way (`DATA-071`), so a folded query and a folded headword meet.

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
dataset — the app must **not** enumerate case permutations at query time (doing so multiplies
storage reads per keystroke).

`GLB-008` `[MUST]` **Glyph locale and fallback font** (decided: `Q-109`). Chinese text is rendered with
an explicit language tag so that a device whose locale is Japanese or Korean never shows Japanese or
Korean glyph forms:
- dictionary content (headwords, alternate forms, definitions' Hanzi, examples, passages, HSK cards) is
  tagged by dictionary mode and script: Mandarin + Simplified → `zh-Hans`; Mandarin + Traditional →
  `zh-Hant`; Cantonese + Traditional → `zh-HK`; Cantonese + Simplified → `zh-Hans`. The alternate form
  beside a headword (`GLB-003`) is tagged by its own script with the same mode rule;
- text the user supplies (Reading draft, search field, OCR and voice text) is tagged like dictionary
  content for the current mode and script;
- UI strings are tagged with the active UI language (`GLB-100`).

The tag is set in shared UI code through the text style's locale list, and through `lang` attributes on
HTML pages (`GLB-113`). Because platform fonts do not cover every Hanzi of `GLB-001` (notably Ext B
and beyond), the app bundles one **OFL-licensed CJK fallback font**, used only after the platform's
fonts and subset to the Hanzi that occur in the dataset and are not reliably covered by the platform
fonts; on Web it is fetched when needed (`GLB-035`). A build check confirms that every Hanzi in the
dataset maps to a glyph in either the baseline platform fonts or the fallback font. Its size is a line
item of `PERF-070`; its licence is credited in `SET-040`.

---

## 2. Pronunciation and romanisation

`GLB-010` `[MUST]` The app is **romanisation-agnostic**. It knows only: a per-entry ordered list of
*syllables* (lowercase, toneless, ASCII+`ü`), a parallel list of *tone numbers*, a display string with
diacritics or trailing digits, and a canonical numbered string. It never contains a hard-coded
Mandarin syllable table; the syllable inventory and tone count arrive with the dataset (`DATA-070`).

`GLB-011` `[MUST]` Mandarin mode: display uses tone-marked Pinyin (`nǐ hǎo`), tones `1–5`
(5 = neutral). Cantonese mode: display uses Jyutping with trailing tone digits (`nei5 hou2`),
tones `1–6`. Whether readings are shown at all is governed by `GLB-120`.

`GLB-012` `[MUST]` **Pronunciation input accepts, per mode:** plain toneless (`nihao`, `neihou`),
digit-toned (`ni3hao3`, `nei5hou2`), diacritic-toned Mandarin (`nǐhǎo`), `u:`/`v` as `ü`, apostrophes
and spaces as explicit syllable boundaries, and mixed forms (`ni3 hao`). It also accepts
**abbreviated and mixed-abbreviation input**: any number of syllables, from one to all, may be typed
as an abbreviation (`nh`, `nih`, `nhao` all reach 你好; Jyutping multi-letter initials such as `ng`,
`gw`, `kw` count as abbreviations, as does their first letter). Fuller spellings outrank abbreviated
ones. Parsing rules: `SRCH-040` and `SRCH-050`…`SRCH-054`. Which spellings abbreviate which syllables
is data, not code (`DATA-150`), so the engine stays romanisation-agnostic (`GLB-010`).

`GLB-013` `[MUST]` Uppercase-initial romanisation marks a **proper-noun reading**. This is the single
normative tie position for proper-noun readings, referenced by `SRCH-070`, `SRCH-075`, `TXT-021` and
`ENT-054`: candidates are ordered by match class, then input token cost (abbreviated or literal tokens,
`SRCH-052`), then frequency, then headword length; **only when all of these are equal** does a common reading sort before a proper-noun reading; entry id is the final
tiebreak. Example: CC-CEDICT has 王 `wang2` "king" and 王 `Wang2` "surname Wang". The query `wang2`
matches both as exact matches with equal frequency (frequency is per written form) and equal length,
so 王 wáng "king" comes first and 王 Wáng "surname Wang" second. 黄 `huang2` / `Huang2` behaves the
same way. In Cantonese mode the flag derives from the entry's CC-CEDICT/CC-Canto Pinyin key, because
Jyutping is never capitalised.

`GLB-014` `[MUST]` **Non-Mandarin romanisation inside definitions must not be re-rendered as
Mandarin.** CEDICT prose contains bracketed romanisations for Cantonese (Jyutping), Hokkien
(Tâi-lô/POJ), Teochew, and Wade-Giles. A bracketed reading is converted to tone marks only when the
pipeline has classified it as Pinyin (`DATA-053`); provenance is resolved in the pipeline rather than
re-derived from prose at runtime, and the app renders what the dataset labels. In **Cantonese mode**
(definitions from CC-Canto, with CC-CEDICT senses carried over per `PROD-045`):
1. Jyutping readings inside CC-Canto senses are shown verbatim with tone digits;
2. a reference — a CC-CEDICT reference inside a carried-over CC-CEDICT sense, or a Pleco-style
   reference inside a CC-Canto sense (below) — that resolves to exactly one Cantonese entry shows that
   entry's Jyutping; an unresolved or ambiguous reference keeps its Pinyin tone marks with a
   `Mandarin` label and opens the disambiguation sheet when tapped (`DATA-054`, `ENT-054`);
3. a bare bracketed reading inside a carried-over CC-CEDICT sense stays Pinyin, labelled `Mandarin`.

**CC-Canto reference format** (decided: `Q-108`, from analysis of the pinned release). CC-Canto has no
pipe form, no CEDICT `[pin1]` form and no `{jyutping}` form. The pipeline handles exactly these shapes:
- **Pleco-style references** `H+pīnyīn [H+]` (e.g. `variant of 同tóng [同]`, `M: 个gè [个]`), which occur
  only in lines tagged `# adapted from cc-cedict`. They are matched case-insensitively by
  `(H+)([a-züāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜ']+) \[(H+)\]` (`H` = a Hanzi per `GLB-001`). The Hanzi are given in
  simplified form even under traditional headwords, so the pipeline maps them to traditional through
  CC-CEDICT and resolves the reference on (Hanzi, toneless Pinyin) against the Mandarin senses carried
  in the Cantonese dataset; the result is a `REF` with `romanization = pinyin` rendered by rule 2.
- **Bare Hanzi runs** in CC-Canto's own (non-adapted) senses, quoted or not, become a `REF` only when the
  run exactly equals a headword of the Cantonese dataset in either script; otherwise they stay plain
  `TEXT`. This is narrower than the CC-CEDICT rule (`DATA-052`) because CC-Canto prose uses Hanzi
  freely. A Jyutping in parentheses after such a run is shown verbatim (rule 1).
- **Register brackets** at the end of a CC-Canto definition (`[colloquial]`, `[written and
  colloquial]`, `[written]`) are stripped **before** reference scanning, so they are never read as a
  `[H+]` part, and are stored as register tags whose labels come from the label table (`DATA-060`,
  `PIPE-079`).

The pipeline performs the resolution and reports unresolved counts (`DATA-054`); the app only renders
the labelled segments.

`GLB-015` `[MUST]` **Tone-mark placement** for numbered→marked Pinyin conversion. Conversion is done
mainly by the pipeline (`PIPE-021`, precomputed forms `DATA-010`); the app converts only if it must
echo user input. First turn `u:` or `v` into `ü`, then put the mark on exactly one letter:
1. if the syllable contains `a` or `e`, mark it (`hao3` → hǎo, `mei2` → méi, `xie3` → xiě,
   `lüe4` → lüè);
2. otherwise, if it contains `ou`, mark the `o` (`dou1` → dōu);
3. otherwise, mark the last vowel (`gui4` → guì, `liu2` → liú, `duo1` → duō, `jiong3` → jiǒng,
   `nü3` → nǚ);
4. a syllabic nasal with no vowel (`m`, `n`, `ng`) takes the mark on the `m` or `n` as a combining
   diacritic (`m2` → ḿ, `n3` → ň, `ng2` → ńg).

Tone 5 (neutral) is unmarked (`ma5` → ma). An uppercase initial keeps its case (`Ou1` → Ōu).
Cantonese Jyutping is never converted; it keeps trailing digits (`GLB-011`).

---

## 3. Loading, empty, and error states

`GLB-030` `[MUST]` **Never blank populated content.** When a new request supersedes displayed
content, the previous content stays on screen until the replacement is ready. Applies to search
results on query/side/script/mode change, to the word-candidate sheet on re-selection, to entry
detail on script change (`ENT-092`), to passage re-analysis (`TXT-051`), and to the live voice
transcript as it updates.

`GLB-031` `[MUST]` **Shape-matched skeletons for first loads.** A first load with no prior content
shows a skeleton whose block sizes approximate the real content (entry detail: `ENT-018`;
HSK: `HSK-063`). Never a bare centred spinner on a full screen except where explicitly specified
(image decode: `CAM-040`; OCR in progress: `CAM-050`).

`GLB-032` `[MUST]` A skeleton keeps its navigation affordances live (back / close work while the
content loads).

`GLB-033` `[MUST]` **Progress for in-place refresh.** A surface that already shows content and is
being refreshed in place (word-candidate sheet re-selection `TXT-042`, search results being replaced,
entry re-fetch after a script change) may show an indeterminate 2 dp linear bar along its top edge,
never an overlay. The bar appears only if the refresh is still running **150 ms** after it started,
so fast refreshes never flash it; once shown it stays visible for at least **300 ms** so it never
flickers. It is never used for first loads, which show skeletons (`GLB-031`).

`GLB-034` `[MUST]` **Error text is inline, human, and actionable**, placed where the content would
have been, and prefixed with the operation: e.g. `Unable to load entry`, `Unable to search`,
`Unable to load word choices`, `Unable to analyze text`, `Unable to recognize text`. If the
underlying failure has a message, it is appended after a colon. A failed feature must leave the app
in a **recoverable, non-busy** state — retrying the same gesture must be possible with no restart.

`GLB-035` `[MUST]` **Graceful asset absence.** On mobile every asset family is installed with the app
(`PROD-070`) **except the voice model**, so for those families "absent" only means that no data exists
for this item (no recording, no stroke order, no examples): the corresponding control is disabled or
the section is omitted. Missing data never produces an error message, never blocks the entry from
rendering, and never removes the entry's core content.

Two cases have a **download or loading state** instead:
- **Voice model on mobile** (`VOICE-095`). Play builds receive it as an on-demand Play Asset Delivery
  pack and iOS as a Background Assets pack (iOS 26+); the direct APK bundles it. While the pack is
  missing, the Voice destination shows its download state (size, progress, retry) in place of the
  transcript; every other destination works normally.
- **Web.** The Web PWA stores only the app shell (code) and the dataset for offline use; the dataset is
  kept current by changesets (`ARCH-145`). Every other Web asset (handwriting model,
  abbreviation/combination data, audio, strokes, examples, the fallback font of `GLB-008`) is fetched
  when needed, kept in the browser cache, and not guaranteed offline. A feature that needs a fetched
  model (handwriting, combinations) shows an inline loading state on that feature while everything else
  keeps working, and a failed fetch follows `GLB-034`. Per-item media (a recording, stroke order,
  examples) behave as absent while pending or unavailable, without an error; a missing recording is
  spoken by TTS (`MED-011`).

`GLB-036` `[MUST]` **Empty vs no-result are different.** "Empty" = no query yet, and shows guidance
plus discovery content (`SRCH-110`). "No result" = a query that matched nothing, and shows a single
centred neutral message (`SRCH-113`).

---

## 4. Concurrency, cancellation, and staleness

`GLB-040` `[MUST]` **Latest-only for every user-driven async operation.** Each operation family
(search, combination decoding `SRCH-060`…, word-candidate lookup, entry detail, passage analysis,
OCR, handwriting recognition `HW-001`…, voice transcription and live re-segmentation `VOICE-001`…,
HSK load, audio playback, TTS) maintains a monotonically increasing request generation. A completed
result is applied **only if** its generation is still current *and* the state it was computed for
(query, script, mode, side, passage identity, route identity, stroke set, live utterance) still
matches. Cancellation alone is never treated as sufficient — a non-cooperative in-flight operation
must not be able to overwrite newer state when it finally returns.

`GLB-041` `[MUST]` **Conflate, don't queue.** While one search is executing, additional inputs
collapse to the single newest request. Rapidly typing then backspacing ten characters must complete
exactly one search — the last one. The same applies to handwriting recognition (one pending
recognition per stroke set) and to re-segmentation of the live voice utterance.

`GLB-042` `[MUST]` **Blank queries bypass any coalescing delay.** When the query becomes blank (field
cleared, last character deleted), the empty state (`SRCH-110`) is applied in the same frame, skipping
any coalescing window (`GLB-043`); every pending or in-flight non-blank search is superseded
(`GLB-041`), so stale results never flash. Example: with an 80 ms window, typing `ni` and then
clearing the field shows the empty-state content at once, and the searches for `n` and `ni` are
discarded.

`GLB-043` `[SHOULD]` No artificial input debounce by default. The target is that a query is cheap
enough to run per keystroke (`PERF-020`). A **coalescing window** W may exist for benchmarking and
low-end device tuning:
- trailing-edge debounce of W ms, W in `0–100`, default `0`; each keystroke restarts the timer;
- maximum wait `2×W` from the first pending keystroke, so continuous typing still updates results;
- never applied to blank queries (`GLB-042`), paste, the restored last query, side/script/mode
  changes, IME composition commit, or a committed handwriting candidate — these run immediately;
- set only from the developer screen or build configuration, never a user setting;
- traced as `dict.coalescing` (`PERF-100`);
- with W = 0, conflation (`GLB-041`) still guarantees that only the latest search completes.

`GLB-044` `[MUST]` **Data-context generation.** Replacing or reloading the dataset (mode switch, or the
Web PWA swapping in a dataset updated by a changeset, `ARCH-145`) bumps a data generation counter that
invalidates every outstanding request, every cached derived model, and every identifier that is only
meaningful within one dataset (notably passage ids and entry ids). Stale identifiers must never be
dereferenced against a new dataset; anything that must survive the swap keys on the natural key
(`DATA-212`). Auxiliary data becoming available later (on Web the handwriting model or
abbreviation/combination data; on mobile the voice pack, `VOICE-095`) does not bump the generation; it
re-runs the current request so the new results appear.

`GLB-045` `[MUST]` Long-running queries must be **cancellable at the storage layer**, not merely at
the coroutine/promise layer, so that abandoning a query stops the underlying scan.

`GLB-046` `[MUST]` No dictionary work on the UI thread. All lookup, ranking, hydration, segmentation,
tokenisation, combination (lattice) decoding, handwriting recognition, speech decoding and
transcript re-segmentation, and model building happen off the main thread. Only rendering and state
diffing happen on it.

---

## 5. State preservation

`GLB-050` `[MUST]` Within a session (including rotation and process recreation from a saved state
bundle), the following survive:
- active primary destination;
- the entry-detail stack: each route's identity, its selected tab, its header collapse fraction, and
  each tab's independent scroll position;
- the search query, search side, and each result page's own scroll position (`SRCH-180`…);
- the current input method (keyboard or handwriting, `SET-019`) and whether the handwriting sheet
  is visible;
- the OCR destination's state (live feed or reviewed capture, and its recognition result,
  `CAM-006`…`CAM-009`);
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
peek height (~256 dp), can be dragged to full height, and dismisses by drag-down, an explicit close
button, or back. The content behind it stays interactive and is bottom-inset by exactly the visible
sheet height. If the handwriting sheet is visible, opening the word sheet hides it and closing the
word sheet restores it (`HW-001`…). Full spec: `TXT-040`.

`GLB-058` `[MUST]` **Character stepping.** Where a selection sits inside a known text (search
passage, reading passage, voice transcript, example sentence, OCR line), the sheet header offers
previous/next controls. They move the selection to the nearest character in that direction that is
**covered by a word**: the character is a dictionary word on its own, or forms a word together with
neighbouring characters (its word candidates are non-empty). Whitespace, punctuation, Latin, digits,
emoji and uncovered Hanzi are skipped. A control is disabled when no covered character exists in its
direction. Coverage is computed once per analysed passage (for example as a bitset), not per step.
Tapping an uncovered character directly is still allowed (`TXT-022`, `TXT-023`). In the camera image
view, stepping also pans the image to centre the newly selected glyph. Step latency: `PERF-050`.

`GLB-059` `[MUST]` **Focus discipline.** Leaving the search field to open an entry drops field focus
so neither the soft keyboard nor the handwriting sheet reopens when returning. Clearing the query
with the clear button re-focuses the field and re-shows the current input method (soft keyboard or
handwriting sheet). The primary navigation hides while the soft keyboard **or the handwriting sheet**
is visible (`HW-001`…).

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
colour. Never partially colour a mismatched pair. Tone colouring is independent of pronunciation
visibility: when readings are hidden (`GLB-120`) the characters keep their tone colours if tone
colouring is on.

`GLB-067` `[SHOULD]` Content is constrained to a maximum readable width (~900 dp) and centred on
wide viewports; in two-pane layouts the limit applies per pane (`GLB-125`). The word-candidate sheet
caps at ~720 dp.

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
list above it remain visible; the primary navigation hides while the keyboard is up. The handwriting
sheet is treated exactly like the soft keyboard: its visible height is the bottom inset, and the
primary navigation hides while it is visible (`HW-001`…).

`GLB-074` `[MUST]` Floating headers (search control, HSK level selector, camera result header) are
translucent (~88% `bg`) with a bottom-only gradient shadow that fades in only when the content behind
them can scroll up.

---

## 9. Accessibility

`GLB-080` `[MUST]` Every icon-only control has a descriptive label: `Play pronunciation`,
`Stop sentence playback`, `Clear search`, `Paste clipboard into search`, `Previous character`,
`Next character`, `Close word options`, `Back`, `Close detail`, `Take photo`, `Toggle flash`,
`Save photo`, `Choose image`, the input-method button (`Switch to handwriting` / `Switch to
keyboard`, naming the mode a tap switches to), `Close handwriting`, `Undo stroke`,
`Clear handwriting`, the pronunciation toggle (`Hide pronunciation` / `Show pronunciation`, also
exposed as a toggle state), and the voice control (`Start listening` / `Stop listening`).

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
passages expose per-character actions rather than one opaque blob. When pronunciation is hidden, the
reading is omitted from these labels (`GLB-122`).

`GLB-085` `[SHOULD]` Respect the platform reduce-motion setting: route transitions, sheet motion,
pulse animations, and the stroke animation reduce to cross-fades or static states.

`GLB-086` `[SHOULD]` Contrast: body and label text meets WCAG AA against its background in both
themes. Tone colours are exempt (decorative) but the underlying text colour without tone colouring
must meet AA.

---

## 10. Analytics, logging, diagnostics

`GLB-090` `[MUST]` **No telemetry.** The app contains no analytics, telemetry or crash-reporting SDK
of its own and makes no network request except those listed in `PROD-071`. Nothing may transmit
queries, OCR text, clipboard content, images, audio, transcripts or handwriting. Performance traces
(`GLB-091`, `ARCH-170`, `PERF-100`) are local only. Network use per platform:
- **Android:** no build declares the `INTERNET` permission (`ARCH-104`…`ARCH-107`), so third-party SDK
  telemetry cannot leave the device either. In Play builds the voice pack is downloaded by the Play
  Store (Play Asset Delivery), not by the app; the Play flavour carries only the foreground-service
  permissions that delivery library merges (`ARCH-107`). The direct APK bundles the voice model.
- **iOS:** the app makes no network request of its own; the voice pack is a Background Assets pack that
  the operating system downloads from Apple's servers (`VOICE-095`).
- **Web:** the app fetches only from `szdict.com` — the app shell, the dataset manifest and changesets
  (`ARCH-145`), and assets fetched when needed (`GLB-035`); never from a third-party host.

`GLB-091` `[SHOULD]` Structured, named performance trace sections must be emitted around: query
classification, storage read, hydration, ranking, variant grouping, combination decoding,
segmentation, state emission, HSK model build, handwriting recognition, speech decoding, and the
input→first-frame span. Names and use: `PERF-100`.

`GLB-092` `[MUST]` Diagnostic logging must never log clipboard text, OCR text, image bytes,
handwriting strokes, audio, voice transcripts, or file URIs beyond scheme/authority.

---

## 11. UI languages

`GLB-100` `[MUST]` **UI strings** are provided in exactly three languages, identified by script
subtag: English (`en`), Simplified Chinese (`zh-Hans`) and Traditional Chinese (`zh-Hant`). Resources
live in Android `values/`, `values-b+zh+Hans/`, `values-b+zh+Hant/`; iOS `en.lproj`, `zh-Hans.lproj`,
`zh-Hant.lproj`; on Web the document's `lang` attribute is set to the active UI language. Quoted UI
copy in these specifications is the English source text of a string key; every user-visible label
(navigation labels, HSK part-of-speech labels, error texts, accessibility labels) has all three
translations. Note-kind and domain/register-tag labels are data, not string keys: they come from the
dataset's label table (`DATA-060`, built by `PIPE-079`), which carries the `en`, `zh-Hans` and
`zh-Hant` copy for each label and is selected by the active UI language. The UI language is **independent** of dictionary mode, script mode and dictionary
content: it never changes definitions, readings or which form is the headword.

`GLB-101` `[MUST]` **Locale mapping and fallback.** With the `System` setting (`GLB-102`) the app
takes the first supported match from the platform's preferred languages (Web: `navigator.languages`):
`zh-Hans`, `zh-CN`, `zh-SG`, `zh-MY` and bare `zh` → `zh-Hans`; `zh-Hant`, `zh-TW`, `zh-HK`, `zh-MO` →
`zh-Hant`; any other language → `en`. A string missing in a Chinese locale falls back to English, never
to a key name or an empty label.

`GLB-102` `[MUST]` **App language setting.** Settings offers `App language` with `System`, `English`,
`简体中文`, `繁體中文` (each language named in itself), persisted as `ui_language` (`SET-018`). On
Android 13+ and iOS it uses the platform's per-app language mechanism so the choice also appears in
system settings; older Android versions and Web apply it in-app. Changing it takes effect immediately
and does not change dictionary mode or script mode.

`GLB-103` `[MUST]` **Locale stripping at build time.** No other locale ships. Android:
`androidResources { localeFilters += listOf("en", "b+zh+Hans", "b+zh+Hant"); generateLocaleConfig =
true }` (with `unqualifiedResLocale=en` in `res/resources.properties`), so translations bundled by
libraries are removed and the generated locale config lists only these three. iOS:
`CFBundleLocalizations` = `en`, `zh-Hans`, `zh-Hant`, and only those `.lproj` folders are bundled. Web
ships only these three string tables. CI checks the shipped locale list of each build (`REL-030`).

---

## 12. Routes and deep links

`GLB-110` `[MUST]` **One route table** is shared by the apps and the Web build at `https://szdict.com`
(decided: `Q-107`). The app's in-app navigation, Web URLs, widget and shortcut deep links all resolve
through it. `{mode}` is the dictionary mode as a path segment: `zh` (Mandarin) or `yue` (Cantonese).

| Route | Opens | Platforms |
|-------|-------|-----------|
| `/{mode}` | Search, empty state (`SRCH-110`), in that mode | all |
| `/{mode}/search?q=…&side=en\|zh` | Search with that query and side, in that mode | all |
| `/{mode}/word/{natural-key}` | Search with that entry pushed on the detail stack; natural key per `DATA-014` / `DATA-212`, from that mode's dataset. On Web, also the indexable word page (`GLB-113`) | all |
| `/{mode}/hsk`, `/{mode}/hsk/{level}` | HSK, optionally at level 1–7, in that mode | all |
| `/` | Search, empty state, in the current mode | all |
| `/reading` | Reading (empty draft) | all |
| `/ocr` | OCR destination, live feed (`CAM-006`) | mobile |
| `/voice` | Voice destination | Android; iOS 26+ (`VOICE-095`) |
| `/settings` | Settings (pushed from Search, `SET-005`) | all |

**Mode rule.** A route carries the mode segment **if and only if the URL itself names dictionary
content** — a query, an entry or an HSK level, plus the empty Search screen that shows mode-specific
discovery content. Routes that name only a destination (`/`, `/reading`, `/ocr`, `/voice`, `/settings`)
carry no mode segment and keep the current mode; their content (a draft, a capture, a transcript) is
never in the URL (`GLB-112`). Consequences:
- opening a mode-prefixed route whose mode differs from the current one switches the dictionary mode
  exactly as the in-app switch does (`PROD-043`, persisted per `SET-010`), then opens the content;
- the app writes only canonical URLs: on a mode-scoped screen the URL always carries the current mode,
  `/` is replaced by `/{mode}` once Search is shown, and a mode switch on a mode-scoped screen replaces
  the current history entry with the same route under the new mode;
- `/zh/word/…` and `/yue/word/…` are different entries in different datasets; a natural key is looked
  up only in the dataset of the route's mode.

**Script is not in the URL.** Simplified/Traditional is a viewer preference (`PROD-044`, `SET-011`):
the same link shows the headword in each viewer's own script mode (`GLB-003`), and opening a link never
changes the viewer's script mode.

On Web, browser history mirrors the destination, the query and the entry-detail stack: switching
destination and pushing an entry add history entries, query edits replace the current entry (no entry
per keystroke), and browser back follows `PROD-056`.

`GLB-111` `[MUST]` **App Links and Universal Links.** The installed app opens `https://szdict.com`
links for every route in `GLB-110` that the platform offers, and for no other path, so the
`/.well-known/` files, `robots.txt`, sitemaps and static assets always stay on the web.
- **Android:** an intent filter with `android:autoVerify="true"` for the host, with
  `android:pathPrefix="/zh/"`, `android:pathPrefix="/yue/"` and `android:path` entries for `/`, `/zh`,
  `/yue`, `/reading`, `/ocr`, `/voice` and `/settings`; verified by `/.well-known/assetlinks.json`
  listing the certificate fingerprint of the single signing key shared by the Play and direct-APK
  builds (decided: `Q-113`).
- **iOS:** the Associated Domains entitlement `applinks:szdict.com`, verified by
  `/.well-known/apple-app-site-association`, served as JSON over HTTPS without redirects, whose
  `components` list the same patterns (`/`, `/zh`, `/zh/*`, `/yue`, `/yue/*`, `/reading`, `/ocr`,
  `/voice`, `/settings`).

Verification is done by the operating system, so it needs no `INTERNET` permission in the app. Both
files are deployed with the Web build (`REL-040`). Without the app installed, the same link opens the
Web build — for a word route, the word page of `GLB-113`.

`GLB-112` `[MUST]` **Fallback and no content in URLs.** An unknown route, a malformed parameter, an
unknown natural key, or a route to a destination the platform does not offer (`/ocr` and `/voice` on
Web; `/voice` on iOS below 26, `VOICE-095`) falls back to Search without an error page; an unknown
path under a valid mode segment falls back to that mode's Search (`/{mode}`), anything else to `/`.
Passage text, the reading draft, OCR text, voice transcripts, handwriting and clipboard content are
**never** placed in a URL; `q` is written only for a query typed by keyboard or handwriting, and is
omitted when the query is pasted or is classified as a passage (`isSentence`, `SRCH-035`). Inside the
running Web app the URL changes through the History API only (no request is made). `szdict.com`
hosting serves the app shell for every route except a word page requested outside the running app
(`GLB-113`), and does not log query strings (`GLB-090`).

`GLB-113` `[MUST]` **Indexable word pages (Web, SEO).** Every entry of each mode's dataset has a public,
indexable page at the same URL as its in-app route, `/{mode}/word/{natural-key}` (`GLB-110`), so a
search-engine result, a shared link and the app all use one address. Architecture: `ARCH-146`.
- **Edge-rendered.** The page is rendered at the edge from the published dataset (not one static
  file per entry, which hosting file-count limits rule out) and served with long-lived caching keyed to
  the dataset version. When the Web app's service worker already controls the browser, it answers
  navigations to word routes with the app shell like any other route; otherwise the edge returns the
  word page. An unknown natural key returns HTTP 404 with a link to that mode's Search, never an empty
  `200`.
- **Content.** Headword in both scripts (Simplified first on `/zh/`, Traditional first on `/yue/`, the
  defaults of `PROD-044`), the mode's reading, senses with notes and labels, and links to the word
  pages of referenced entries; dataset attribution as in `SET-040`. No third-party requests, cookies or
  analytics (`GLB-090`).
- **Language and canonical.** The `html` element's `lang` is `zh-Hans` on `/zh/` pages and `yue` on
  `/yue/` pages; Chinese spans are tagged per `GLB-008` (a Traditional form on a `/zh/` page is
  `zh-Hant`) and English definitions `en`. Each page declares itself canonical
  (`<link rel="canonical">` to its own `/{mode}/word/{natural-key}` URL); one entry has one canonical
  URL per mode, and the `/zh/` and `/yue/` pages of a headword are separate canonicals.
- **Structured data.** Each page carries schema.org `DefinedTerm` JSON-LD (term name, description from
  the first senses, `inDefinedTermSet` naming the source dictionary).
- **Sitemaps.** `/sitemap.xml` is a sitemap index generated per release, listing per-mode sitemaps of
  at most 50,000 URLs each, with `lastmod` taken from the release that last changed the entry
  (`PIPE-167`). `robots.txt` lists the sitemap index.
- **Not indexed.** Search routes and every other app-shell response are not indexable
  (`X-Robots-Tag: noindex`; `robots.txt` disallows `/zh/search` and `/yue/search`).
- **Into the app.** Each page has a prominent link that opens the same route in the Web app (which then
  stores the dataset for offline use, `ARCH-145`). Where the native app is installed, the link opens it
  through App Links / Universal Links (`GLB-111`).

---

## 13. Pronunciation visibility

`GLB-120` `[MUST]` **Global pronunciation toggle.** One persisted setting, `show_pronunciation`
(`SET-017`, default shown, `PROD-012`), shows or hides readings for **all** Hanzi in the app. A
toggle button for it appears on Search, Reading, Voice, the entry header and HSK; every button
reflects and changes the same state. When hidden, no reading is shown on result cards and compact
rows, the word-candidate sheet, the entry header, HSK cards, readings inside definitions (reading and
reference segments, `DATA-050`; the Hanzi stay) or passages (`GLB-121`). Hidden readings keep their
space reserved, so switching the toggle never moves the layout. There is no per-item reveal. Tone
colours (`GLB-066`) and audio controls are unaffected, and pronunciation **input** is unaffected:
romanised queries still work and the search field shows what the user typed.

`GLB-121` `[MUST]` **Interlinear (ruby) readings for passages.** Passages in Reading, the Search passage
card, the voice transcript and the OCR text view render the reading above each Hanzi, taken from the entry chosen by
segmentation (`TXT-034`, `TXT-035`): one syllable per character when the counts match, otherwise the
word's reading centred over the word. Tone colours follow `GLB-066`. Hanzi not covered by any word
get no reading. The ruby line keeps its height when pronunciation is hidden (`GLB-120`).

`GLB-122` `[MUST]` **Screen readers.** When pronunciation is hidden, accessibility labels omit the
reading (`GLB-084`), so the toggle also works for self-testing with TalkBack or VoiceOver; the toggle
announces its state. When shown, a character's or word's label includes its reading.

---

## 14. Window size

`GLB-125` `[MUST]` **List-detail layout.** At a window width of at least **840 dp** (Web: 840 CSS px,
the expanded width class) Search, HSK and Reading use a two-pane list-detail layout: the list or
passage on one side, the top entry-detail route on the other. Below 840 the layout is single-pane and
entry detail is pushed full screen. Window width is used, not screen width, so split screen and
resized browser windows switch accordingly. Each pane applies its own maximum width (`GLB-067`), and
the word-candidate sheet anchors to the pane holding the tapped text. `PROD-054`, `ENT-015`,
`ENT-019`, `SRCH-096` and `ARCH-051` reference this rule.
