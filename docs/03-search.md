# 03 — Search

Search is the app's home surface and its most important feature. Read
[02 — Global Conventions](02-global-conventions.md) first.

---

## 1. Scope and structure

`SRCH-001` `[MUST]` A single text field accepts **all** input kinds — English, romanised
pronunciation, Chinese characters, mixed Chinese+romanisation, and whole pasted sentences. There is no
input-type selector.

`SRCH-002` `[MUST]` Results are organised into two **sides**: `CHINESE` and `ENGLISH`, selected by a
two-segment toggle joined to the search field. Both sides are computed for every non-blank query so
each segment can display its own result count.

`SRCH-003` `[MUST]` The Chinese side answers "what Chinese words match what I typed". The English
side answers "which entries have definitions matching my words".

`SRCH-004` `[SHOULD]` Search result presentation and full entry presentation are specified separately
([04 — Entry Detail](04-entry-and-media.md)) because they differ in structure: a result is a compact card
with clamped definitions, an entry is a paged surface. They **share one card component** for
compact/related rows (`ENT-070`).

---

## 2. Query normalisation

`SRCH-010` `[MUST]` Raw input is normalised once, deterministically, before any matching: (I suppose this is for use for the chinese search, right? this needs to be more clear so that the rule objectives/context is more clear)
1. Unicode NFC. (what is this?)
2. Lowercase.
3. Right single quote (U+2019) becomes ASCII apostrophe. (include the char for reference; explain the why of the rule)
4. `u:` becomes `ü`.
5. Every character that is **not** Hanzi (`GLB-001`), `a-z`, `0-9`, `'`, whitespace, `ü`, or a
   tone-marked vowel (the 24 combinations of `a e i o u ü` with the four Mandarin tone marks) is
   replaced by a single space. (Explain the goal of this rule)
6. Runs of whitespace collapse to one space; the result is trimmed.

Examples: `  NI3   HAO3!! ` becomes `ni3 hao3`; a string of only CJK punctuation becomes empty.

`SRCH-011` `[MUST]` Line breaks in pasted input — LF, CRLF, CR, vertical tab (U+000B), form feed
(U+000C), NEL (U+0085), LS (U+2028), PS (U+2029) — each become a single space before the field value
is stored, so the field stays single-line and no characters are lost.

`SRCH-012` `[MUST]` For the Chinese side's **display text** (the segmented passage card and the
"starts with"/"contains" labels), a *separate*, gentler normalisation is used: NFC + whitespace
collapse + trim only. Punctuation the user pasted is preserved for display and for passage
segmentation. Only matching uses `SRCH-010`.

`SRCH-013` `[MUST]` English tokenisation: lowercase, split on any run of characters outside
`[a-z0-9]`, drop empty tokens, then apply a single naive plural stem — drop a trailing `s` when the
token is longer than 3 characters and does not end in `ss`. `Graduate students` becomes
`["graduate", "student"]`. This rule is the contract; the dataset's English index must be built with
the identical rule (`DATA-060`).

---

## 3. Query classification

`SRCH-020` `[MUST]` The normalised query is classified into exactly one kind:

| Kind | Condition | Effective search |
|------|-----------|------------------|
| `EMPTY` | normalised query is empty | popular words |
| `HANZI` | contains Hanzi and every other character is a space | Chinese text search |
| `MIXED` | contains Hanzi **and** Latin/digits | Chinese pattern search |
| `LATIN` + parseable | no Hanzi, parses as romanisation | pronunciation search |
| `LATIN` + unparseable | no Hanzi, does not parse | English search |

`SRCH-021` `[MUST]` A `LATIN` query that parses as romanisation is **also** run as an English search
(both sides are always computed), and vice versa. Classification decides which side is *auto-selected*
(`SRCH-024`), not which searches run.

`SRCH-022` `[MUST]` `MIXED` classification only proceeds to pattern search when every non-Hanzi
character is a plausible romanisation character (`a-z`, tone digits, `'`, whitespace, `ü`,
tone-marked vowel). Otherwise the query is treated as `HANZI` for the Chinese side (the Latin noise is
ignored) and as English for the English side.

`SRCH-023` `[MUST]` In `MIXED` classification, each maximal Latin run is parsed as romanisation. Runs
that fail to parse are recorded as **ignored tokens**; if any token is ignored, pattern search is
skipped for that query (the pattern would be meaningless). Only the final Latin run may end in a
partial (incomplete) syllable.

`SRCH-024` `[MUST]` **Automatic side selection.** On every query *change* (not on manual side
toggle), the side is chosen automatically:
- if the query contains Hanzi **or** the romanisation parse carries at least one explicit tone, choose
  `CHINESE`;
- otherwise choose the user's last remembered side.

`SRCH-025` `[MUST]` A manual side toggle is authoritative for the current query and does not get
overridden by the automatic rule until the query changes.

`SRCH-026` `[MUST]` The remembered side is persisted **only** when the current query was not forced
to Chinese — i.e. only ambiguous Latin queries teach the preference. Typing Hanzi must not permanently
flip the user's default to Chinese.

`SRCH-027` `[MUST]` Default remembered side on a fresh install: `ENGLISH`.

---

## 4. Matching rules

### 4.1 Match classes and their weights

`SRCH-030` `[MUST]` Every candidate is assigned the **best** (lowest-weight) match class it qualifies
for:

| Weight | Class | Meaning |
|--------|-------|---------|
| 1 | `EXACT` | headword equals the query (either script), or fully-toned romanisation equals the entry's reading |
| 2 | `EXACT_TONELESS` | toneless romanisation equals the entry's full reading |
| 3 | `PREFIX` | headword or reading starts with the query but is longer |
| 4 | `CONTAINS` | query occurs inside the headword at position greater than 0 |
| 5 | `EN_PHRASE` | the query's token sequence occurs contiguously in one sense |
| 6 | `EN_SENSE` | all query tokens occur in one sense (last token may prefix-match) |
| 7 | `EN_ENTRY` | all query tokens occur somewhere in the entry, across senses |

`SRCH-031` `[MUST]` Both simplified and traditional forms are matched for exact, prefix and contains,
regardless of script mode.

`SRCH-032` `[MUST]` Folded and ASCII-case-folded forms participate in exact and prefix matching
(`GLB-005` to `GLB-007`).

### 4.2 Chinese text (`HANZI`) search

`SRCH-033` `[MUST]` The Chinese side for a Hanzi query produces, in this order:
1. **Exact group** — if the whole query is a dictionary headword, one group containing every entry
   (every reading) for that headword.
2. **Segmented groups** — otherwise, the query is segmented into words (`TXT-010`) and each segment
   becomes a group: matched segments carry their entries; consecutive unmatched segments merge into a
   single *inert* group.
3. **Pattern matches** (`MIXED` only) — see `SRCH-036`.
4. **Starts with** — headwords strictly longer than the query that begin with it.
5. **Contains** — headwords containing the query at position 1 or later.

`SRCH-034` `[MUST]` A group whose text is longer than one character also carries its **sub-spans**:
every dictionary word contained inside the group text, excluding the full span itself, ordered
longest-first, then by start position, then by frequency rank, then by id.

`SRCH-035` `[MUST]` `isSentence` is true when there is more than one group, or when the single group
is inert and there was no exact match. `isSentence` drives the segmented-passage card (`SRCH-100`) and
the "Words" vs "Exact" category label.

### 4.3 Mixed Hanzi + pronunciation pattern search

`SRCH-036` `[MUST]` A mixed query becomes an ordered **pattern** of items, each either a specific
Hanzi or a syllable constraint (plain syllable + optional tone + partial flag). At most 8 pattern
alternatives are explored when a Latin run has several valid segmentations.

`SRCH-037` `[MUST]` A candidate entry matches a pattern when:
- its character count is at least the pattern length;
- its character count equals its syllable count (one syllable per character — entries with
  digits/Latin in the headword are excluded);
- for each position: a Hanzi item equals the simplified **or** traditional character at that position;
  a syllable item equals (or, if partial, prefixes) the syllable at that position and, if the item
  carries a tone, that tone matches.

`SRCH-038` `[MUST]` Pattern match class is `EXACT` when the entry length equals the pattern length,
`PREFIX` otherwise.

`SRCH-039` `[MUST]` Candidates for pattern search are drawn from the character posting list of the
pattern's **first Hanzi item**. For `我xiang qu` the candidate set is entries containing 我.

### 4.4 Pronunciation search

`SRCH-040` `[MUST]` Romanisation parsing:
- Input is normalised to a letter stream plus two sparse maps: tone-by-diacritic (at the vowel's
  index) and tone-by-digit (at the syllable's end index), plus a set of **hard boundaries** at every
  explicit separator (space, apostrophe) and after every tone digit.
- The letter stream is segmented into syllables from the dataset's syllable inventory, longest-first,
  never crossing a hard boundary.
- A syllable's tone is its digit tone if present, else its single diacritic tone; **two diacritics
  inside one syllable invalidate that segmentation**.
- Aliases are applied (Mandarin: `lue` to `lüe`, `nue` to `nüe`; `v` to `ü`).
- If partial matching is allowed and the trailing remainder is a **proper prefix** of some inventory
  syllable (and is not itself a syllable or alias, and carries no explicit tone digit at the end), it
  is emitted as a final partial syllable with tone 0.
- At most **8** segmentations are retained; enumeration is longest-syllable-first so the most likely
  reading comes first.

`SRCH-041` `[MUST]` **Dictionary-initialism fallback.** If strict parsing fails, a leading run of
letters that are not themselves valid syllables (`T`, `K`, `B`, `G`, `X`, ...) may be consumed as
literal initials, provided the remainder parses and its first non-partial syllable contains a vowel.
`Txu`, `T xu`, `t xu4` all parse as `["t", "xu"]`. This exists because CC-CEDICT readings such as
`T xu4` (T恤 / T-shirt) contain literal Latin initials.

`SRCH-042` `[MUST]` Reading match: compare the parse's syllables against the entry's syllable array
positionally. All but the last query syllable must equal the entry syllable exactly; the last may be a
prefix of it. Where a query syllable carries a tone, the entry's tone at that position must equal it.
An entry with **fewer** syllables than the query cannot match.

`SRCH-043` `[MUST]` Class assignment for reading matches: exact full-reading equality with all tones
specified is `EXACT`; exact full-reading equality without tones is `EXACT_TONELESS`; anything longer or
partial is `PREFIX`.

`SRCH-044` `[MUST]` The Chinese side for a Latin query **also** runs a headword-prefix search on the
raw compacted query (spaces removed), so Latin-containing headwords (`AA制`, `K金`, `X光`) are
reachable by typing `aa`, `k`, `x`. Exact headword equality there is `EXACT`, otherwise `PREFIX`.

### 4.5 English search

`SRCH-045` `[MUST]` All query tokens except the last must match a token in the English index exactly.
The last token matches by **prefix** (so `stud` finds `student`, `study`). If any non-final token has
no postings, the English result set is empty.

`SRCH-046` `[MUST]` Candidates are the intersection of all token posting lists.

`SRCH-047` `[MUST]` Class refinement per candidate:
- `EN_PHRASE` if the query's token sequence appears **contiguously** in the token stream of a single
  sense;
- else `EN_SENSE` if all non-final tokens and a prefix-match of the final token occur within a single
  sense;
- else `EN_ENTRY`.

`SRCH-048` `[MUST]` Single-token English queries: an exact token hit is `EN_PHRASE`; a prefix-only hit
is `EN_SENSE`. Multi-character abbreviations present as whole tokens (`HSK`, `CD`) are exact tokens.

`SRCH-049` `[MUST]` The English side never applies sub-span expansion or segmentation; it is a flat
ranked list.

---

## 5. Ranking

`SRCH-070` `[MUST]` The **canonical comparator**, applied to every candidate set after all candidates
are collected:

1. match-class weight ascending (`SRCH-030`);
2. frequency rank ascending (lower is more common; entries with no frequency data use a sentinel of
   1000000);
3. headword length ascending (in code points);
4. proper-noun reading last (uppercase-initial romanisation sorts after);
5. entry id ascending (final, stable tiebreak).

`SRCH-071` `[MUST]` **Rank before limiting.** Any result cap is applied strictly *after* the full
candidate set has been ranked. Applying a storage-level `LIMIT` before ranking is forbidden: measured
against the reference dataset, an earlier revision of the reference app that limited first silently
dropped 1029 of the correctly-ranked top 1200 for the query `x` — including entries as common as 想
(true rank 29), 现在 (50) and 先生 (82). The reference app was later corrected; the invariant must be
regression-tested (`PIPE-140`).

`SRCH-072` `[MUST]` When the same entry is produced by several match paths, it keeps its **best**
(lowest) match class and appears once.

`SRCH-073` `[SHOULD]` Result cap per side: 1200 ranked ids. Cards are materialised lazily as the list
scrolls; only the visible window plus a small prefetch margin is hydrated.

`SRCH-074` `[MUST]` The exact/segmented group ordering is **text order**, not rank order — a
segmented sentence's words appear in the order they occur in the text. Ranking applies within a group
(multiple readings of the same word) and to the Pattern/Starts/Contains/Related sections.

`SRCH-075` `[MUST]` Within a group of multiple readings for one headword, common readings precede
proper-noun readings, then entry id ascending.

---

## 6. Variant merging (same displayed headword, same reading)

`SRCH-080` `[MUST]` Two or more entries **merge into one card** when, under the active script mode,
they share the same displayed headword **and** the same displayed pronunciation, **and** their
*alternate*-script forms differ from one another.

Example, Simplified mode: 發/发 `fā` and 髮/发 `fā` both display as 发 `fā`, so they merge into one
card whose alternate-form line reads `發 · 髮`.

`SRCH-081` `[MUST]` In Traditional mode the same pair does **not** merge (they display as 發 and 髮),
and two separate cards appear.

`SRCH-082` `[MUST]` A merged card:
- shows the shared headword and reading once;
- shows all distinct alternate forms joined by ` · ` in place of the single alternate form;
- concatenates the members' definitions into one numbered list, ordered by member (members with more
  direct definitions first, original order breaking ties), **de-duplicating** identical definition
  text across members;
- suppresses a member's redirect header when the redirect target is another member of the same group
  (avoiding "variant of 发" inside the 发 card);
- strips a parenthetical "(variant of X)" clause from a definition when X is another member of the
  group, keeping the semantic remainder;
- shows structured notes only when the group has no definitions at all.

`SRCH-083` `[MUST]` The merge group is computed over the **global** set of entries sharing that
headword and reading, not merely over the entries present in the current result set. Search results
and entry detail must agree on group membership.

`SRCH-084` `[MUST]` A merged card's hidden members must still be hydrated so their definitions render;
they must not appear as duplicate rows elsewhere in the same result list.

`SRCH-085` `[MUST]` Merging applies uniformly to: search result cards, word-candidate sheet rows,
entry-detail related-word rows, and the entry-detail header/definition area. It does **not** apply to
HSK grid cards (`HSK-021`).

`SRCH-086` `[MUST]` Merge grouping must be **precomputed** in the dataset as a stable group id per
(script, headword, reading) rather than derived at query time by re-looking-up headwords and
comparing — the reference implementation's runtime grouping issues extra lookups per candidate and is
a documented hot spot (finding `F-021`). See `DATA-080`.

---

## 7. Result presentation

### 7.1 Search control

`SRCH-090` `[MUST]` A floating rounded surface (16 dp radius, hairline border, soft shadow) pinned
below the status bar, above the scrolling results, containing:
- the single-line text field, placeholder `English · Pinyin · 汉字` (Cantonese mode:
  `English · Jyutping · 漢字`), IME action Search;
- a trailing action: **paste** (when the query is empty *and* the clipboard holds usable text) or
  **clear** (when the query is non-empty); never both;
- below a hairline divider, only when the query is non-blank: the two-segment side toggle showing
  `CHINESE (n)` / `ENGLISH (n)`, where the counts appear only when the displayed response corresponds
  to the current query and no search is in flight (otherwise the bare labels);
- below another hairline, only when there is **more than one** result category: a horizontally
  scrollable row of category chips (`Exact`/`Words`, `Pattern`, `Starts`, `Contains`, `Related`), each
  showing its label and count, tapping which scrolls the list to that section with the floating header
  height offset.

`SRCH-091` `[MUST]` The active side segment is filled with `accent` and `onAccent` text; the inactive
segment is `panel` with `muted` text.

`SRCH-092` `[MUST]` The toggle's appearance/disappearance and the category row's are animated
(fade plus vertical expand/shrink from the top, about 150-190 ms).

`SRCH-093` `[MUST]` Changing the query, pasting, or clearing scrolls the result list back to the top.

### 7.2 Result cards

`SRCH-094` `[MUST]` A result card contains, in order:
- a row with the tone-coloured headword (title size), the muted alternate form (or merged variants)
  baseline-aligned beside it, wrapping to the next line if needed; and, right-aligned, the
  pronunciation play button;
- the tone-coloured pronunciation (accent colour), single line, ellipsised;
- any redirect header notes (muted);
- **all** definitions as one paragraph, each numbered `1. `, `2. ` and so on in bold `fg`, separated
  by two spaces, in `muted` text;
- structured notes joined by ` • ` (only when there are no definitions);
- HSK level badges (outlined pills in the level colour) when the entry has HSK levels.

`SRCH-095` `[MUST]` The definition paragraph is clamped and, when clamped, shows a `+N` accent badge
at the bottom-right counting the definitions that are entirely hidden. In result lists and compact
rows the paragraph is clamped to **3 lines**; in the entry-detail body it is unclamped.

`SRCH-096` `[MUST]` The card border is `accent` when the card is the currently open entry (two-pane
layouts), else `border`.

`SRCH-097` `[MUST]` A **compact** variant reduces the headword to title-medium and tightens padding;
it is used for sub-span rows, entry-detail related rows, and sheet rows.

`SRCH-098` `[MUST]` **Inert rows** (unmatched text runs) render as a `bgSoft` card showing the run
text in `muted` plus a small uppercase `NO MATCH` label. They are not tappable.

`SRCH-099` `[MUST]` **Sentence mode grouping:** when `isSentence` is true and a segment has more than
one reading, those readings render as one card with internal hairline dividers rather than as separate
cards, so the sentence's word sequence stays visually one-card-per-word.

### 7.3 Segmented passage card (sentence mode)

`SRCH-100` `[MUST]` When `isSentence` is true, the Chinese side begins with a passage card containing:
- the caption `Tap a Hanzi to see words at that position`;
- the display text rendered as one wrapping text layout, headline-small size, with
  matched spans in `fg` (or tone colours when enabled and alignable) and unmatched in `muted`;
  per-word underlines in alternating shades (`GLB-061`), inset at word start and end so word
  boundaries read as separate strokes, drawn only when the word-boundary setting is on; and the
  selected character highlighted with the `highlight` background and semibold weight;
- the sentence action row: play (TTS), translate, copy (`MED-030`).

`SRCH-101` `[MUST]` Tapping any character in the passage resolves the tap offset via the text layout,
normalises it to a visible code-point start, and opens the word-candidate sheet for that position
(`TXT-040`).

### 7.4 Sections and sub-span expanders

`SRCH-102` `[MUST]` Chinese-side sections, in order, each preceded by a small-caps section label with
a count chip:
- the groups themselves (unlabelled — the first category chip points at them);
- `Hanzi + Pinyin pattern matches` (Cantonese: `Hanzi + Jyutping pattern matches`);
- `Starts with 「X」`;
- `Contains 「X」 elsewhere`;
- `Related matches` — everything ranked into the side that no earlier section claimed.

`SRCH-103` `[MUST]` Each matched group with sub-spans is followed by a collapsed expander pill reading
`+ N more within 「word」`. Expanding reveals the sub-span rows as compact cards behind a 2 dp left
indent bar, animated. The expanded/collapsed state is remembered per (word, row count) while the
result list lives.

`SRCH-104` `[MUST]` **Parent-reading promotion in sub-spans.** When a parent word has an unambiguous
reading, the sub-span candidate whose reading matches the corresponding slice of the parent's reading
is promoted to the top of its span group. Capitalisation is significant (`Fǎ` matches `Fǎ` before
`fǎ`). If the parent's reading cannot be aligned (syllable count not equal to character count), engine
order is kept.

### 7.5 Empty state

`SRCH-110` `[MUST]` With a blank query the Chinese and English sides both show **popular words** and
the screen shows, top to bottom:
- the hint `Type English, Pinyin (nihao or nǐ hǎo), Hanzi, or paste a sentence.` (Cantonese mode
  substitutes `Jyutping (neihou or nei5 hou2)`);
- a `Recognize Chinese text` heading with two equal-width access cards — `Scan with camera` and
  `Choose from gallery` — each an icon over a centred label (omitted entirely on Web, `PROD-023`);
- a `Popular words` heading followed by the popular result cards.

`SRCH-111` `[MUST]` Popular words are the most frequent **multi-character** words in the dataset,
supplied as a precomputed ordered list (`DATA-090`), 50 items. They must not require a
frequency-sorted scan at runtime.

`SRCH-112` `[MUST]` The empty state and the results state use separate scroll positions and
cross-fade into each other (about 180 ms) so returning to a blank query does not inherit result
scroll.

### 7.6 No-result state

`SRCH-113` `[MUST]` A non-blank query with zero rendered entries on the active side shows a single
centred `titleMedium` `muted` message: the current error text if any, otherwise `No matches`.

`SRCH-114` `[SHOULD]` When the active side is empty but the other side has results, the message
should say so and offer the switch, e.g. `No Chinese matches — 12 English matches`. *(New behaviour —
see `Q-012`.)*

---

## 8. Keyboard and input behaviour

`SRCH-120` `[MUST]` The field is single-line with IME action `Search`. Pressing the IME search action
dismisses the keyboard and does not otherwise change results (results are already live).

`SRCH-121` `[MUST]` The clear action empties the query, keeps focus, and re-shows the keyboard.

`SRCH-122` `[MUST]` Selecting a result drops field focus before pushing the detail route, so the
keyboard does not reappear when the list pane returns (`GLB-059`).

`SRCH-123` `[MUST]` The keyboard hides the primary navigation while visible (`GLB-073`).

`SRCH-124` `[MUST]` Paste-into-search sets the query to the clipboard text (line-break-normalised per
`SRCH-011`) and scrolls to top. Clipboard availability is refreshed whenever the app window gains
focus and whenever the primary clip changes while focused; a paste attempt with no usable text does
not clear the query and does not show an error, it only updates availability so the button
disappears.

---

## 9. Navigation from results

`SRCH-130` `[MUST]` Tapping a result card pushes an entry-detail route for that entry id
(`ENT-010`). Tapping the same entry that is already the top route is a no-op.

`SRCH-131` `[MUST]` Tapping a sub-span row opens the sub-span's entry, not the parent.

`SRCH-132` `[MUST]` Tapping a row inside the word-candidate sheet opens that entry; sheet dismissal
follows `TXT-045`.

`SRCH-133` `[MUST]` Changing the query clears the entire entry-detail stack.

---

## 10. Persistence and history

`SRCH-140` `[MUST]` The current query is persisted (debounced about 750 ms, and flushed immediately
when the app stops) and restored on next launch, then executed immediately.

`SRCH-141` `[MUST]` A restored query is line-break-normalised before use.

`SRCH-142` `[MUST]` If the user edits the field before persisted settings have loaded, the user's
input wins — a late settings emission must not overwrite typing.

`SRCH-143` `[MUST]` There is **no** search history list in v1. The single restored query is the whole
history feature. *(Adding one is `Q-013`.)*

---

## 11. Performance expectations

`SRCH-150` `[MUST]` Targets and measurement: `PERF-020`. Summary — keystroke to rendered results:
p50 at most 60 ms, p95 at most 120 ms, hard ceiling 250 ms, on a mid-range 2022 Android phone with a
warm dataset.

`SRCH-151` `[MUST]` `CONTAINS` matching must **not** be implemented as a full index scan. The
reference implementation used a substring predicate over the whole headword index, costing about 6 ms
per call on a fast desktop and far more on a phone, twice per Hanzi query. The dataset must provide a
substring-capable index (`DATA-062`).

`SRCH-152` `[MUST]` Entry hydration is **batched** (never one row per id) and only for ids that will
be rendered. Reference measurements: 1200 ids one-at-a-time about 7.3 ms versus batched about 2.1 ms
on desktop, before per-row JSON parsing.

`SRCH-153` `[MUST]` No per-row JSON parsing on the search path. Definitions must be readable in a form
that does not require parsing a JSON document per entry (`DATA-143`).

---

## 12. Edge cases (all `[MUST]`)

`SRCH-160` A query of only punctuation/emoji normalises to empty, so it behaves exactly like a blank
query (popular words, no side toggle).

`SRCH-161` A query of only spaces is blank.

`SRCH-162` Mixed query with unparseable Latin (`我qwrt`): pattern search is skipped; the Chinese side
falls back to Hanzi handling of 我; the ignored token is not surfaced to the user.

`SRCH-163` A headword that is entirely non-Hanzi (`110`, `2019冠状病毒病`, `PO`) must be findable:
digits and Latin participate in headword exact/prefix matching.

`SRCH-164` Entries whose headword length differs from their syllable count (numerals, Latin
initialisms) are excluded from **pattern** search only; they remain fully searchable by every other
path.

`SRCH-165` Tone-only differences: `hao3` must not return `hao4` entries; `hao` returns both, ranked by
frequency.

`SRCH-166` Two diacritics in one syllable: that segmentation is rejected; if no segmentation survives,
the query falls through to English search.

`SRCH-167` A query longer than the dataset's maximum headword length cannot match exact/prefix; the
sentence path handles it by segmentation instead.

`SRCH-168` Very long pasted passages: segmentation cost is bounded by `TXT-014`; the passage card must
remain scrollable and must not be truncated silently — if a cap is applied it must be visible to the
user.

`SRCH-169` Duplicate ids arriving from multiple match paths must never yield duplicate cards; card
keys are section-prefix plus entry id.

`SRCH-170` An entry with zero definitions and zero notes still renders (headword, reading, HSK badge).
It is never filtered out.

`SRCH-171` Switching side while a search is in flight must reuse the in-flight response when the
query, script, mode, and data generation are unchanged, rather than re-querying.

`SRCH-172` A search that throws leaves the previous results visible and shows the error text in place
of the empty state; the next keystroke must recover.
