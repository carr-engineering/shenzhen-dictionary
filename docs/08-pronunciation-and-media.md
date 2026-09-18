# 08 — Pronunciation, Audio, Sentences, Strokes and Origins

Optional asset families. All are subject to `GLB-035` (graceful absence).

---

## 1. Recorded pronunciation audio

`MED-001` `[MUST]` A pronunciation **play button** appears on every entry card and in the entry-detail
header. It is enabled only when an audio plan exists for that entry, shows a breathing pulse on the
speaker glyph while its own audio plays, and is otherwise a static outlined accent button.

`MED-002` `[MUST]` Only one playback is active at a time. Starting a new one cancels the previous one
and clears its playing state. Playback state is a single "currently playing entry id" so exactly one
button animates.

`MED-003` `[MUST]` An **audio plan** is an ordered list of asset paths plus a `gaps` flag. Playing a
plan plays its clips in sequence.

`MED-004` `[MUST]` Audio is addressed by **canonical numbered pronunciation**, never by characters, so
homophones share one verified recording and so entries with two readings (便宜 `pian2 yi5` vs
`bian4 yi2`) cannot pick the wrong clip.

`MED-005` `[MUST]` The canonical key is built by lowercasing each syllable, folding `u:` and `ü` to
`v`, requiring every syllable to carry an explicit tone digit, and joining with `_`
(`za2 zhi4` becomes `za2_zhi4`). A reading with any untoned syllable has no word key.

`MED-006` `[MUST]` Plan selection:
- **Multi-character entries** prefer a matching full-word recording; otherwise they fall back to the
  per-syllable sequence.
- **Single-character entries** prefer a complete per-syllable plan; a full-word recording is used only
  when the required syllable recording is missing.

`MED-007` `[MUST]` Per-syllable resolution folds `u:`/`ü` to `v`, requires an explicit tone digit, and
allows one fallback: a neutral-tone syllable (`5`) may use the tone-1 recording. Any unresolved
syllable sets `gaps = true` while the resolvable clips are still played.

`MED-008` `[MUST]` Availability for a list of visible cards is computed in one batched off-thread pass
per result set, not per card render, and is invalidated when the result set, query, script, mode or
dataset changes.

`MED-009` `[MUST]` Playback errors are silent — the button simply stops animating. No error text.

`MED-010` `[SHOULD]` Recorded audio is a large asset family (about 52 MB of word clips plus about
13 MB of syllable clips in the reference build). It should be an **on-demand download**, not bundled
(`PROD-071`, `PERF-070`). Until it is present, the play buttons fall back to TTS (`MED-011`).

`MED-011` `[SHOULD]` When no recording exists for an entry, the play button should speak the headword
via platform TTS instead of being disabled. *(New behaviour — `Q-060`.)*

`MED-012` `[MUST]` Ambiguous legacy recordings (a clip that cannot be attributed to exactly one
reading) must be excluded from the catalogue rather than guessed.

---

## 2. Text-to-speech (sentences)

`MED-020` `[MUST]` A **sentence action row** appears under every passage: play/stop (TTS), translate,
copy. Buttons are 40 dp outlined accent icon buttons; disabled when the text is blank.

`MED-021` `[MUST]` The play button becomes a stop button while that exact text is speaking, with a
cross-fade+scale icon transition. Tapping stop cancels; tapping play on a different text switches.

`MED-022` `[MUST]` TTS language follows the script/mode: Simplified Mandarin uses `zh-CN`, Traditional
Mandarin `zh-TW`, Cantonese `yue-HK` (falling back to `zh-HK`).

`MED-023` `[MUST]` TTS availability varies by device. Initialisation failure, a missing voice, or a
speak error must leave the UI consistent (button back to play) with no error dialog.

`MED-024` `[MUST]` TTS is stopped and released when the owning state holder is destroyed and when the
dataset is replaced.

`MED-025` `[MUST]` Sentence action rows appear on: the Search sentence card, the Reading passage, the
Camera `All text` card, and each Camera line card.

---

## 3. Translate hand-off

`MED-030` `[MUST]` The translate action hands the text to an **external** translator; the app never
translates sentences itself.

`MED-031` `[MUST]` Android: try, in order, a floating "tap to translate" activity via
`ACTION_TRANSLATE`, then `ACTION_PROCESS_TEXT`, then `ACTION_SEND` to the translator app, each with
source `zh` and target `en` hints. If none resolves, show a brief toast
`Google Translate isn't installed`. The translator package must be declared in the manifest's
`queries` block so it is visible.

`MED-032` `[MUST]` iOS: present the system translation sheet (`UITranslationSession` / the Translate
share action). Web: no translate action (the browser provides its own). Hiding the button on Web is
preferred over a dead affordance.

`MED-033` `[SHOULD]` The hard-coded activity class names of a third-party app are fragile; prefer
implicit intents and drop the component names. *(Improvement — `Q-061`.)*

---

## 4. Copy

`MED-035` `[MUST]` The copy action places the trimmed text on the clipboard as plain text. No
confirmation is required on platforms that show their own.

---

## 5. Example sentences

`MED-040` `[MUST]` The entry-detail DICT tab shows an `Examples` section with a count when example
sentences exist for the entry's **simplified** headword.

`MED-041` `[MUST]` Each example shows the Chinese sentence (in the active script) at 20 sp with 33 sp
line height, then its English translation in muted body text.

`MED-042` `[MUST]` Occurrences of the headword inside the sentence are highlighted with the
`highlight` background (every occurrence, not just the first).

`MED-043` `[MUST]` Every Hanzi in the sentence is an individual tap target that opens the
word-candidate sheet for that offset within the sentence, and the sheet's stepper walks the sentence
(`GLB-058`). Tap ranges must be per code point with correct offsets, including for supplementary
characters.

`MED-044` `[MUST]` Long-press selects text without following the tap (`GLB-056`).

`MED-045` `[MUST]` Five examples are shown initially, with an expander `+ N more examples`; once
expanded a `- Show fewer` toggle returns to five. The expanded flag is remembered per (headword,
example count) while the route lives.

`MED-046` `[MUST]` Examples are looked up by exact simplified headword and capped (20) at the data
layer.

`MED-047` `[SHOULD]` Examples should be ranked (shortest-first, or by containing the headword as a
standalone word) rather than by source order. *(Improvement — `Q-062`.)*

---

## 6. Character origins

`MED-050` `[MUST]` The DICT tab shows a `Character origin` section with one card per distinct character
of the displayed headword that has origin data.

`MED-051` `[MUST]` An origin card contains, in order:
- the character in accent title text;
- its **IDS decomposition** rendered as a structural layout (see `MED-053`) when an IDS string exists;
  otherwise up to 8 component chips; otherwise `Standalone form: X`;
- an optional character definition;
- an optional etymology note (max 4 lines, ellipsised);
- a horizontally scrolling **historical glyph strip** when glyph images exist, captioned
  `Historical forms` or `Historical forms of X` when the glyphs belong to a variant character;
  otherwise a fallback strip built from the components' own historical glyphs;
- provenance labels: `IDS: <source>` and `glyphs: <source>`.

`MED-052` `[MUST]` Components and the character in an IDS layout are **tappable chips**: tapping one
opens that character (`MED-055`).

`MED-053` `[MUST]` IDS rendering interprets the Ideographic Description Characters as real layout:
horizontal split, vertical split, enclosure/surround, overlay, and their variants, nesting recursively.
Placeholder components are omitted but the structure is preserved.

`MED-054` `[MUST]` Historical glyph images arrive as data URIs and are decoded lazily; a malformed or
non-image URI is skipped, never rendered as an error.

`MED-055` `[MUST]` Tapping a component character runs a lookup for that character: if a dictionary
entry exists it is pushed as a **nested detail route** (so Back returns to the word the component was
opened from); otherwise the app navigates to Search with that character as the query.

`MED-056` `[MUST]` Repeated component taps cancel obsolete lookups and cannot overlap with the
coordinated search work (`GLB-040`).

`MED-057` `[MUST]` Origin component preloading is bounded (at most 12 component origins per entry).

`MED-058` `[SHOULD]` Origins are about 22 MB in the reference build, mostly embedded glyph images.
They should be an on-demand asset pack, and the glyph images should be re-encoded (WebP/AVIF, small
fixed size) rather than shipped as base64 data URIs inside JSON — base64 inflates payload by about
33% and forces a full-shard JSON parse to read one record (finding `F-070`).

---

## 7. Stroke order and practice

`MED-060` `[MUST]` The `STROKES` tab shows one practice pane per distinct character of the displayed
headword with valid stroke data (1/2/3 columns by width). The practice canvas is capped so a whole
card fits on short screens (window height minus 260 dp, clamped to 180-420 dp).

`MED-061` `[MUST]` Stroke data per character: an ordered list of stroke outlines (filled paths), an
ordered list of **medians** (polylines through each stroke's centre), and an optional radical stroke
index list. Coordinates arrive in a y-up 1024-unit space with a standard offset; rendering normalises
them into a centred y-down viewport via `y' = 900 - y`. Outlines, medians, touch input, hints and
completed strokes must all share that one transform.

`MED-062` `[MUST]` Three modes per pane: **Preview** (static outline), **Animation**, **Practice**.

`MED-063` `[MUST]` Animation reveals each stroke's real filled outline progressively by masking it with
its median, in stroke order, with a configurable inter-stroke delay (default 120 ms) and a per-stroke
duration derived from the median's length. Replay restarts from the first stroke.

`MED-064` `[MUST]` Practice is a **touch-first guided quiz**: the user traces the expected stroke; the
attempt is accepted or rejected; accepted strokes stay drawn; the expected index advances; completing
the last stroke completes the character with a brief pulse.

`MED-065` `[MUST]` Stroke acceptance criteria (all must hold, with a leniency multiplier):
- start point within tolerance of the median's start (default 250 units);
- end point within tolerance of the median's end (default 250);
- average sampled distance to the median within tolerance (default 350, halved when the outline is
  visible);
- maximum sampled distance within tolerance (default 260);
- coverage: at least 60% of the median's resampled points have an input point within 150 units;
- length ratio between 0.35 and 2.35 of the median length (padded to be forgiving on very short
  strokes);
- direction similarity positive (a reversed trace is rejected);
- normalised shape distance within 0.40;
- the trace must not match a **later** stroke more closely (with an 8-unit advantage margin for the
  expected stroke).
Both forward and backward traces are evaluated so a stroke drawn the wrong way is explicitly rejected
rather than silently matched.

`MED-066` `[MUST]` Sampling uses 30 resampled points (minimum 8); adjacent duplicate input points are
removed first.

`MED-067` `[MUST]` A rejected attempt increments the mistake counters, briefly flashes the failed
trace (about 220 ms), and after **2** mistakes on the current stroke shows an animated hint tracing the
expected median (about 520 ms). Mistake count and hint reset when the stroke is accepted.

`MED-068` `[MUST]` A reset action clears accepted strokes and mistakes and returns to the start of
practice. Outline visibility is independently toggleable per character.

`MED-069` `[MUST]` Intentional boundaries: one difficulty level, no saved history, no scores, no
component shortcuts (the packaged stroke assets carry no component maps).

`MED-070` `[MUST]` Malformed medians must not drop the stroke outlines: a stroke with an unusable
median still renders and is skipped for practice. A record with no stroke paths at all is rejected and
the `STROKES` tab does not appear for that character.

`MED-071` `[SHOULD]` Stroke data is about 30 MB in the reference build and should be an on-demand asset
pack, stored as a compact binary geometry format rather than 256 JSON shards that must be parsed to
read one character (finding `F-071`).

---

## 8. Asset access patterns

`MED-080` `[MUST]` Assets are addressed by content key, not by scanning: strokes and origins by
`codePoint mod 256` shard; example sentences by a word index shard (`codePoint mod 64`) yielding
sentence ids, then sentence shards of 500 rows.

`MED-081` `[MUST]` Shards are cached in memory once read, per shard, for the lifetime of the process.

`MED-082` `[MUST]` Reading an asset never blocks the UI thread and never fails the entry.

`MED-083` `[SHOULD]` The successor should replace whole-shard JSON reads with random-access records
(one seek + one decode per character) so reading one character's strokes does not parse a shard
containing hundreds. See `DATA-171`.
