# 03 — Search

Search is the app's home surface and its most important feature. Read
[02 — Global Conventions](02-global-conventions.md) first.

---

## 1. Scope and structure

`SRCH-001` `[MUST]` A single text field accepts **all** input kinds — English, romanised
pronunciation (full, abbreviated or mixed), Chinese characters, mixed Chinese+romanisation, and whole
pasted sentences. There is no query-type selector: the query is classified automatically (`SRCH-020`).
The input-method button in the field (keyboard ↔ handwriting, `HW-003`) only changes how characters
are entered; it never changes how the query is interpreted.

`SRCH-002` `[MUST]` Results are organised into two **sides**: `CHINESE` and `ENGLISH`, presented as two
horizontally swipeable **pages** synced with a two-segment section indicator joined to the search field
(`SRCH-180`…`SRCH-184`). Both sides are computed for every non-blank query so each segment can display
its own result count and either page can be shown at once.

`SRCH-003` `[MUST]` The Chinese side answers "what Chinese words match what I typed". The English
side answers "which entries have definitions matching my words".

`SRCH-004` `[SHOULD]` Search result presentation and full entry presentation are specified separately
([04 — Entry and Media](04-entry-and-media.md)) because they differ in structure: a result is a compact
card with clamped definitions, an entry is a paged surface. They **share one card component** for
compact/related rows (`ENT-070`).

---

## 2. Query normalisation

`SRCH-010` `[MUST]` **Matching key.** Every query — English, romanisation, Hanzi or mixed — is
normalised once, deterministically, into a *matching key* before classification (`SRCH-020`). The key
is used only for matching: classification, romanisation parsing, Hanzi search and English tokenisation
(`SRCH-013`) all start from it. What the user sees is never changed — the field keeps the typed text,
and the Chinese side's display text uses the gentler `SRCH-012`. The dataset's search keys are built
with the same steps (`DATA-071`), so query and index agree. Steps, in order:

1. **Unicode NFKC.** Unicode normalisation rewrites equivalent character sequences into one standard
   form. Its composition step merges a base letter followed by a combining mark into the single
   precomposed character: `nǐ` may arrive as `i` + U+030C COMBINING CARON (some macOS/iOS keyboards,
   text copied from PDFs) instead of `ǐ` (U+01D0); without this step the loose mark would be turned
   into a space by step 6 and the tone lost. NFKC (rather than plain NFC) also folds compatibility
   characters to their ordinary forms, exactly as the dataset folds headwords (`GLB-006`): full-width
   Latin letters and digits (`Ｔ恤` → `T恤`, `ｎｉ３` → `ni3`), Kangxi radicals and compatibility
   ideographs to unified ideographs (`GLB-001`), superscript digits, and the ideographic space
   (U+3000) to a space.
2. Lowercase.
3. **Apostrophes.** `’` (U+2019 RIGHT SINGLE QUOTATION MARK), `‘` (U+2018), `ʼ` (U+02BC MODIFIER
   LETTER APOSTROPHE) and `＇` (U+FF07, already folded by step 1) become the ASCII apostrophe `'`
   (U+0027). Smart-punctuation keyboards and text copied from web pages produce `’` where the user
   meant `'`. In romanisation the apostrophe is an explicit syllable boundary (`SRCH-040`): `xi'an` is
   西安 (two syllables) whereas `xian` reads first as 先, so the boundary must survive whichever
   apostrophe the keyboard produced.
4. `u:` becomes `ü`.
5. **Middle dots.** The variants listed in `GLB-005` become `·` (U+00B7), which is kept: it is part of
   headwords such as 马丁·路德·金. In romanisation it acts as a syllable boundary, like a space.
6. **Separators.** Every character that is **not** Hanzi (`GLB-001`), `a-z`, `0-9`, `'`, `·`,
   whitespace, `ü`, or a tone-marked vowel (the 24 combinations of `a e i o u ü` with the four Mandarin
   tone marks) is replaced by a single space. Goal: drop everything that cannot take part in a match —
   punctuation, symbols, emoji, other scripts — while keeping its role as a separator, so the text on
   either side is not glued together: `ni,hao` → `ni hao` (two syllables), `graduate-student` →
   `graduate student` (two English tokens), `你好！` → `你好`, `🙂 hello` → `hello`.
7. Runs of whitespace collapse to one space; the result is trimmed.

Examples: `  NI3   HAO3!! ` becomes `ni3 hao3`; `Ｔ恤` becomes `t恤`; `xi’an` becomes `xi'an`;
`马丁・路德•金` becomes `马丁·路德·金`; a string of only CJK punctuation becomes empty (`SRCH-160`).

`SRCH-011` `[MUST]` Line breaks in pasted input — LF, CRLF, CR, vertical tab (U+000B), form feed
(U+000C), NEL (U+0085), LS (U+2028), PS (U+2029) — each become a single space before the field value
is stored, so the field stays single-line and no characters are lost.

`SRCH-012` `[MUST]` For the Chinese side's **display text** (the segmented passage card and the
"starts with"/"contains" labels), a *separate*, gentler normalisation is used: NFC + whitespace
collapse + trim only, so full-width characters and pasted punctuation are preserved for display and
for passage segmentation. Only matching uses `SRCH-010`.

`SRCH-013` `[MUST]` English tokenisation: lowercase, split on any run of characters outside
`[a-z0-9]`, drop empty tokens, then apply a single naive plural stem — drop a trailing `s` when the
token is longer than 3 characters and does not end in `ss`. `Graduate students` becomes
`["graduate", "student"]`. This rule is the contract; the dataset's English index must be built with
the identical rule (`DATA-076`).

---

## 3. Query classification

`SRCH-020` `[MUST]` The normalised query is classified into exactly one kind:

| Kind | Condition | Effective search |
|------|-----------|------------------|
| `EMPTY` | normalised query is empty | empty state (`SRCH-110`) |
| `HANZI` | contains Hanzi and every other character is a space or `·` | Chinese text search |
| `MIXED` | contains Hanzi **and** Latin/digits | Chinese pattern search |
| `ROMANISATION` | no Hanzi; has at least one token path (`SRCH-040`, `SRCH-050`, `SRCH-051`) — full, abbreviated, partial or literal syllables in any permitted mix | pronunciation search (+ combinations, `SRCH-060`) |
| `LATIN` | no Hanzi; no token path | English search |

Abbreviated input such as `nh`, `nhao` or `zhg` is `ROMANISATION`, not `LATIN`.

`SRCH-021` `[MUST]` A `ROMANISATION` query is **also** run as an English search, and a `LATIN` query
also runs the Latin-headword prefix search of `SRCH-044` on the Chinese side (both sides are always
computed). Classification decides which side is *auto-selected* (`SRCH-024`), not which searches run.

`SRCH-022` `[MUST]` `MIXED` classification only proceeds to pattern search when every non-Hanzi
character is a plausible romanisation character (`a-z`, tone digits, `'`, `·`, whitespace, `ü`,
tone-marked vowel). Otherwise the query is treated as `HANZI` for the Chinese side (the Latin noise is
ignored) and as English for the English side.

`SRCH-023` `[MUST]` In `MIXED` classification, each maximal Latin run is parsed as romanisation with the
token rules of `SRCH-040`…`SRCH-051`, so full, abbreviated and literal syllables may all appear
(`我xh你`, `da y`). Runs that have no token path are recorded as **ignored tokens**; if any token is
ignored, pattern search is skipped for that query (the pattern would be meaningless). Only the final
Latin run may end in a partial (incomplete) syllable.

`SRCH-024` `[MUST]` **Automatic side selection.** On every query *change* (not on manual side
toggle or swipe), the side is chosen automatically when the response for the new query is applied:
- if the query contains Hanzi **or** the romanisation parse carries at least one explicit tone, choose
  `CHINESE`;
- else, if the query is `ROMANISATION` — including any abbreviated or mixed-abbreviation reading
  (`nh`, `nhao`, `zhg`) — **and** the English side has no match for it (`SRCH-045`), choose
  `CHINESE`: the input only makes sense as pronunciation. Input that also reads as English (`hen`,
  `fan`, or a prefix of an English token such as `ca` while typing `cat`) does not qualify, so typing
  English never flips to the Chinese page midway;
- otherwise choose the user's last remembered side.

When the chosen side differs from the visible page, the pager animates to it (`SRCH-181`).

`SRCH-025` `[MUST]` A manual side change — tapping or clicking a segment of the indicator, a swipe that
settles on the other page, or the arrow keys on Web (`SRCH-184`) — is authoritative for the current
query and does not get overridden by the automatic rule until the query changes.

`SRCH-026` `[MUST]` The remembered side is persisted **only** when the current query was not forced
to Chinese by `SRCH-024` — i.e. only ambiguous Latin queries teach the preference. Typing Hanzi, tones
or abbreviated pronunciation must not permanently flip the user's default to Chinese.

`SRCH-027` `[MUST]` Default remembered side on a fresh install: `ENGLISH`. Abbreviated or otherwise
non-English romanisation still lands on the Chinese page through `SRCH-024`, so a new user typing `nh`
sees 你好 without touching the indicator.

---

## 4. Matching rules

### 4.1 Match classes and their weights

`SRCH-030` `[MUST]` Every candidate is assigned the **best** (lowest-weight) match class it qualifies
for:

| Weight | Class | Meaning |
|--------|-------|---------|
| 1 | `EXACT` | headword equals the query (either script), or fully-toned romanisation equals the entry's reading |
| 2 | `EXACT_TONELESS` | romanisation covers the entry's full reading, without all tones (abbreviated syllables allowed, `SRCH-052`) |
| 3 | `PREFIX` | headword or reading starts with the query but is longer, or the query ends in a partial syllable |
| 4 | `CONTAINS` | query occurs inside the headword at position greater than 0 |
| 5 | `EN_PHRASE` | the query's token sequence occurs contiguously in one sense |
| 6 | `EN_SENSE` | all query tokens occur in one sense (last token may prefix-match) |
| 7 | `EN_ENTRY` | all query tokens occur somewhere in the entry, across senses |

There is **no** separate class or group for abbreviated input: abbreviated matches fall into these
classes and are ordered within them by token cost (`SRCH-052`, `SRCH-070`).

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
Hanzi or a syllable constraint (one token of `SRCH-050` — full with optional tone, abbreviated,
partial or literal). When a Latin run has several kept token paths (`SRCH-051`), at most 8 pattern
alternatives are explored.

`SRCH-037` `[MUST]` A candidate entry matches a pattern when:
- its character count is at least the pattern length;
- its character count equals its syllable count (one syllable per character — entries with
  digits/Latin in the headword are excluded);
- for each position: a Hanzi item equals the simplified **or** traditional character at that position;
  a syllable item matches the syllable at that position by the per-kind rule of `SRCH-042`.

`SRCH-038` `[MUST]` Pattern match class is `EXACT` when the entry length equals the pattern length,
`PREFIX` otherwise. Abbreviated and partial items add their token cost (`SRCH-052`).

`SRCH-039` `[MUST]` Candidates for pattern search are drawn from the character posting list of the
pattern's **first Hanzi item**. For `我xiang qu` the candidate set is entries containing 我.

### 4.4 Pronunciation search

`SRCH-040` `[MUST]` **Romanisation parsing produces a token DAG.**
- Input is normalised to a letter stream plus two sparse maps: tone-by-diacritic (at the vowel's
  index) and tone-by-digit (at the syllable's end index), plus a set of **hard boundaries** at every
  explicit separator (space, apostrophe, `·`) and after every tone digit.
- Aliases are applied (Mandarin: `lue` to `lüe`, `nue` to `nüe`; `v` to `ü`).
- Over the letter stream, every way of reading a stretch of letters as one token of `SRCH-050` (full,
  abbreviated, partial or literal) becomes an edge between two offsets. Tokens never cross a hard
  boundary. A path through the DAG from the start to the end of the stream is one reading of the
  query; paths are enumerated and limited per `SRCH-051`.
- A full syllable's tone is its digit tone if present, else its single diacritic tone; **two diacritics
  inside one syllable invalidate that token**. Abbreviated, partial and literal tokens carry no tone:
  a tone digit or diacritic on one invalidates that token.
- A partial token is allowed only at the very end of the stream, only when it is a **proper prefix**
  of some inventory syllable (and is not itself a syllable or alias), and only without a trailing tone
  digit; it has tone 0.

`SRCH-041` `[MUST]` **Literal Latin initials (`LITERAL` token).** CC-CEDICT readings such as `T xu4`
(T恤 / T-shirt) and `A A zhi4` (AA制) contain literal Latin letters as syllables. A leading run of
single letters may be read as `LITERAL` tokens, provided the remainder of the path consists of full or
partial tokens and begins with a full syllable. `Txu`, `T xu`, `t xu4` all parse as
`[LITERAL t, FULL xu]`. A `LITERAL` token matches only an entry syllable that is that letter
(case-insensitive). Paths with `LITERAL` tokens never contain abbreviated tokens (Latin-initial
readings are not in the first-letter index, `DATA-151`).

`SRCH-042` `[MUST]` **Reading match, per position and per token kind.** Compare a path's tokens
against the entry's syllable array positionally:
- `FULL` — the entry syllable equals the token's syllable; where the token carries a tone, the entry's
  tone at that position must equal it;
- `ABBR` — the token is one of the entry syllable's abbreviation spellings (`DATA-150`): `z` and `zh`
  both match `zhang`;
- `PARTIAL` (final token only) — the token is a proper prefix of the entry syllable;
- `LITERAL` — the entry syllable is that Latin letter.

Every token must match its position. An entry with **more** syllables than tokens matches as a prefix
(`PREFIX`). An entry with **fewer** syllables than tokens cannot match as a single word; such input is
served by multi-word combinations (`SRCH-060`).

`SRCH-043` `[MUST]` Class assignment for reading matches: the entry's syllable count equals the token
count, no token is `ABBR` or `PARTIAL`, and every `FULL` token carries a tone → `EXACT`; the syllable
count equals the token count and no token is `PARTIAL` → `EXACT_TONELESS` (abbreviated syllables
allowed); anything longer or ending in a partial token → `PREFIX`. An entry reached by several paths
keeps its best (class, token cost) pair (`SRCH-072`).

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

### 4.6 Abbreviated and mixed pronunciation

`SRCH-050` `[MUST]` **Token kinds.** The spelling stage reads romanisation as tokens of four kinds:

| Kind | What it is | Token cost |
|------|------------|------------|
| `FULL` | one inventory syllable (`DATA-070`), after aliases; tone optional | 0 |
| `ABBR` | an abbreviation spelling standing for one syllable (`DATA-150`): Mandarin `n` for `ni`, `zh` or `z` for `zhong`; Jyutping `ng` or `n` for `ngo`, `gw` or `g` for `gwong` | `abbrev_penalty` per token (`DATA-155`) |
| `PARTIAL` | a proper prefix of a syllable, final token only (`SRCH-040`) | `partial_penalty` (`DATA-155`) |
| `LITERAL` | a literal Latin letter in a leading run (`SRCH-041`) | `abbrev_penalty` per token |

Any mix is allowed: from one syllable to all of them may be abbreviated (`nh`, `nih`, `nhao`, `nihao`,
`ni h`, `n hao` all reach 你好). Mandarin `i`, `u` and `v` are never abbreviations; single-letter
syllables such as `a`, `e`, `o` (Mandarin) or `m`, `ng` (Jyutping) are `FULL` tokens.

`SRCH-051` `[MUST]` **Paths and limits.**
- A path is a sequence of tokens covering the whole letter stream. A single-token path is never
  `ABBR`: abbreviation needs at least two tokens, so a one-token reading of `x` or `sh` is a full or
  partial syllable exactly as before abbreviation support existed (`SRCH-176`, `SRCH-177`).
- Paths containing `ABBR` or `LITERAL` tokens have at most **12** tokens; longer such paths are
  discarded. Paths of only `FULL` and `PARTIAL` tokens are not length-limited.
- At most the **8** best paths are kept, ordered by total token cost ascending, then fewest tokens,
  then longest-syllable-first enumeration order (so the most likely reading comes first). The kept
  paths together form the query's reading; their union is the lattice for combinations
  (`SRCH-061`).

`SRCH-052` `[MUST]` **Cost, not a separate tier.** A path's **token cost** is the sum of the absolute
penalties of its tokens (`SRCH-050`); a candidate's token cost is that of the cheapest kept path that
matched it. Within each match class, lower token cost ranks first (`SRCH-070`), so fuller spellings
outrank abbreviated ones: for `zhg`, two-syllable `[zh, g]` matches (中国) rank before
three-syllable `[z, h, g]` matches of the same class, and for Jyutping `ngh` a full `ng` syllable
ranks before `ng` abbreviating `ngo` (`SRCH-179`). Tones on `FULL` tokens still constrain: `ni3h`
only matches entries whose first syllable is `ni3`. There is no "initials" match class, section or
category chip.

`SRCH-053` `[MUST]` **Candidate retrieval.**
- Paths of `FULL`, `PARTIAL` and `LITERAL` tokens use the reading index (`DATA-073`) as before; they are
  not capped before ranking (`SRCH-071`).
- Paths containing `ABBR` tokens use the first-letter index (`DATA-151`): the key is the first letter
  of each token (`zhg` → `zg`); an exact key serves entries with as many syllables as tokens, a key
  prefix range serves longer entries. Postings are filtered per position by `SRCH-042` using the
  stored syllable indices, without reading entries. The index's per-key caps (single-letter keys keep
  their 300 most frequent entries, `DATA-151`) bound only this path.
- All keys of all kept paths are read in **one batched** lookup (`ARCH-021`), never one read per path
  or per token.

`SRCH-054` `[MUST]` **Data-driven and available everywhere.** The spelling stage knows abbreviations
only through the abbreviation table (`DATA-150`) and the syllable inventory (`DATA-070`), so Mandarin
and Cantonese use the same code (`GLB-010`, `GLB-012`). On mobile the data is installed with the app.
On Web the first-letter index is part of the lazily fetched language-model file (`DATA-170`,
`ARCH-143`): until it is present, `ABBR` paths yield no candidates, the rest of the query is served
normally, and when it arrives the visible results refresh in place (`GLB-033`) without a skeleton.

### 4.7 Multi-word combinations

`SRCH-060` `[MUST]` **Trigger.** For a `ROMANISATION` query whose kept paths include one of at least
**2** tokens, the Chinese side also proposes **combinations**: sequences of two or more dictionary
words that together spell the whole query (`wxhn`, `woxihuanni` → 我 喜欢 你). Combinations are never
computed for `HANZI`, `MIXED`, `LATIN` or blank queries.

`SRCH-061` `[MUST]` **Lattice.** Nodes are letter-stream offsets that are token boundaries on a kept
path. An edge `[i, j)` is a dictionary word of 1 to 6 syllables whose syllables match, per `SRCH-042`
and **without** the prefix allowance, the tokens of a kept path spanning exactly `[i, j)`; a `PARTIAL`
token may appear only on an edge that ends at the end of the query; `LITERAL` tokens form no edges.
Edge candidates come from the first-letter index (`DATA-151`, already in unigram order) in the same
batched read as `SRCH-053`; at most **M = 40** words are kept per span.

`SRCH-062` `[MUST]` **Scoring and search** use the shared lattice decoder (`TXT-026`): the score of a
sequence is the sum of `lp(w | previous word)` — the bigram (`DATA-153`, decoded through `DATA-154`)
when present, otherwise backoff plus unigram (`DATA-152`) — plus the log-penalties of the edge's
tokens, `abbrev_penalty` per abbreviated syllable and `partial_penalty` for a partial one
(`DATA-155`). The decoder runs k-best Viterbi with **k = 8**,
recombination on (node, last word) and a beam cutoff (`TXT-026`). When the dataset's
`has_bigram_model` is `false` (`DATA-155`), scoring uses unigrams only and everything else is
unchanged.

`SRCH-063` `[MUST]` **Output.** The top **5** distinct sequences of two or more words that cover the
whole query, in descending score. Sequences are distinct by their displayed headwords under the active
script. A sequence whose concatenated text equals the headword of an entry already matching the whole
query (`EXACT` or `EXACT_TONELESS`) is dropped — it is already in the list (`nh` does not repeat 你好
as 你 + 好). Whole-query single-word matches stay in the normal ranked list; combinations never enter
the ranked list or the side's result count.

`SRCH-064` `[MUST]` **Combinations group.** Combinations appear as a section on the Chinese page
labelled `Combinations` with a count chip, placed after the whole-query single-word matches (`EXACT`
and `EXACT_TONELESS`) and before longer (`PREFIX`) matches, and with its own `Combinations` category
chip (`SRCH-090`). The section is omitted when there are no combinations.

`SRCH-065` `[MUST]` **Combination card.** One card per combination shows its words in order, each with
its tone-coloured headword (`GLB-066`) and its reading, which follows the pronunciation toggle
(`GLB-120`: hidden readings keep their space). Each word is its own tap target: tapping a word opens
that word's entry (`SRCH-130`); there is no target for the combination as a whole. The card's
accessibility label reads the words in order, each with its reading unless hidden (`GLB-122`).

`SRCH-066` `[MUST]` **Progressive.** Single-word results never wait for combinations: they are
rendered as soon as they are ready, and the Combinations group is added when the decoder finishes,
without moving content the user is looking at (the first visible item keeps its position). The
decoder for a superseded query is cancelled (`GLB-041`). A decoder failure omits the group silently.

`SRCH-067` `[MUST]` **Delivery.** On mobile the language-model data is installed with the app
(`DATA-170`). On Web it is fetched lazily on the first multi-syllable pinyin or Jyutping query
(`ARCH-143`); that query's single-word results do not wait for the fetch, and the group appears for it
once the data is present. If the fetch fails, the group is omitted and the fetch is retried on a later
qualifying query.

`SRCH-068` `[MUST]` **Budget and tracing.** Abbreviated input and combinations are held to `PERF-025`
(single-word results stay within `PERF-020`). The decoder stage is traced as `dict.lattice`
(`PERF-100`). It performs at most two batched store reads per query — first-letter postings and bigram
blocks — and no per-word reads.

---

## 5. Ranking

`SRCH-070` `[MUST]` The **canonical comparator**, applied to every candidate set after all candidates
are collected:

1. match-class weight ascending (`SRCH-030`);
2. token cost ascending (`SRCH-052`; 0 for candidates not found through romanisation);
3. frequency rank ascending (lower is more common; entries with no frequency data use a sentinel of
   1000000);
4. headword length ascending (in code points);
5. common reading before proper-noun reading, only when all earlier keys are equal (`GLB-013`);
6. entry id ascending (final, stable tiebreak).

`SRCH-071` `[MUST]` **Rank before limiting.** Any result cap is applied strictly *after* the full
candidate set has been ranked. Applying a storage-level `LIMIT` before ranking is forbidden: measured
against the reference dataset, an earlier revision of the reference app that limited first silently
dropped 1029 of the correctly-ranked top 1200 for the query `x` — including entries as common as 想
(true rank 29), 现在 (50) and 先生 (82). The reference app was later corrected; the invariant must be
regression-tested (`PIPE-140`). The precomputed per-key caps of the first-letter index (`SRCH-053`)
are a dataset bound in frequency order, not a query-time limit.

`SRCH-072` `[MUST]` When the same entry is produced by several match paths, it keeps its **best**
(lowest) match class — and, within it, its lowest token cost — and appears once.

`SRCH-073` `[SHOULD]` Result cap per side: 1200 ranked ids. Cards are materialised lazily as the list
scrolls; only the visible window plus a small prefetch margin is hydrated.

`SRCH-074` `[MUST]` The exact/segmented group ordering is **text order**, not rank order — a
segmented sentence's words appear in the order they occur in the text. Ranking applies within a group
(multiple readings of the same word) and to the Pattern/Starts/Contains/Related sections.

`SRCH-075` `[MUST]` Within a group of multiple readings for one headword, readings are ordered by the
canonical comparator (`SRCH-070`). Because frequency is per written form and length is equal within one
headword, this places common readings before proper-noun readings (`GLB-013`), then entry id
ascending.

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
HSK grid cards (`HSK-022`).

`SRCH-086` `[MUST]` Merge grouping must be **precomputed** in the dataset as a stable group id per
(script, headword, reading) rather than derived at query time by re-looking-up headwords and
comparing — runtime grouping issues extra lookups per candidate and was a measured hot spot in the
reference implementation. See `DATA-080`.

---

## 7. Result presentation

### 7.1 Search control

`SRCH-090` `[MUST]` A floating rounded surface (16 dp radius, hairline border, soft shadow) pinned
below the status bar, above the result pages, containing:
- a header row with the single-line text field and, at the row's trailing end outside the field, the
  **pronunciation toggle** (`GLB-120`). The toggle sits in the search header, is always visible
  (including for a blank query, where it governs the Word of the Day reading), and is labelled
  `Hide pronunciation` / `Show pronunciation` (`GLB-122`);
- the field: placeholder `English · Pinyin · 汉字` (Cantonese mode: `English · Jyutping · 漢字`), IME
  action Search, and these trailing actions inside the field, in order towards the trailing edge:
  1. **paste** (when the query is empty *and* the clipboard holds usable text) or **clear** (when the
     query is non-empty); never both;
  2. the **input-method button** (keyboard ↔ handwriting, `HW-003`), always present;
- below a hairline divider, only when the query is non-blank: the two-segment **section indicator**
  for the result pages (`SRCH-180`) showing `CHINESE (n)` / `ENGLISH (n)`, where the counts appear only
  when the displayed response corresponds to the current query and no search is in flight (otherwise
  the bare labels); combinations are not counted (`SRCH-063`);
- below another hairline, only when the **visible page** has more than one result category: a
  horizontally scrollable row of that page's category chips (`Exact`/`Words`, `Combinations`,
  `Pattern`, `Starts`, `Contains`, `Related`), each showing its label and count, tapping which scrolls
  that page to the section with the floating header height offset. The row is replaced by the other
  page's chips when the pager settles on it (`SRCH-182`).

`SRCH-091` `[MUST]` The active segment is filled with `accent` and `onAccent` text; the inactive
segment is `panel` with `muted` text. While the pages are being dragged, the fill follows the pager's
offset and settles on the page the pager settles on.

`SRCH-092` `[MUST]` The indicator's appearance/disappearance and the category row's are animated
(fade plus vertical expand/shrink from the top, about 150-190 ms).

`SRCH-093` `[MUST]` Changing the query, pasting, or clearing scrolls **each** result page back to the
top, including the page that is not visible.

### 7.2 Result pages (swipeable sides)

`SRCH-180` `[MUST]` **Two pages, one indicator.** For a non-blank query the Chinese and English sides
are two horizontally swipeable pages of one pager, Chinese first, in the indicator's segment order.
The indicator (`SRCH-090`) and the pager are always in sync: tapping a segment animates the pager to
that page, and the visible page is always the active segment. For a blank query the pager is
**inert**: the indicator is hidden, a single empty-state surface is shown (`SRCH-110`), and horizontal
drags do nothing.

`SRCH-181` `[MUST]` **Swipe and auto-switch.** A swipe that settles on the other page is a manual side
change (`SRCH-025`, `SRCH-026`). An automatic side change (`SRCH-024`) animates the pager to the chosen
page with the same motion as a tap on the indicator (instant when reduce-motion is on, `GLB-085`). If
the user is dragging when an automatic change would apply, the drag wins: the automatic change is
skipped for that query and the page where the drag settles is a manual change. Showing the other page
reuses the computed response and is held to the side-toggle row of `PERF-020`.

`SRCH-182` `[MUST]` **Per-page state.** Each page keeps its own:
- scroll position, retained while the query is unchanged (swiping away and back returns to the same
  place; `GLB-050`), and reset to the top on query change, paste or clear (`SRCH-093`);
- category chips (`SRCH-090`) and section anchors;
- floating-header shadow, driven by that page's scroll offset (`GLB-074`), updated as the visible page
  changes;
- no-result state (`SRCH-113`, `SRCH-114`): a page with no matches shows its own message while the
  other page shows its results; the `SRCH-114` offer to switch animates the pager;
- error state (`SRCH-172`).

`SRCH-183` `[MUST]` **Gesture conflicts.** The pager claims a drag only when its direction is
predominantly horizontal after the platform touch slop; vertical drags scroll the page. In addition:
- the category chip row lives in the floating header, outside the pager: horizontal drags that start
  on it scroll the chip row and never move the pager, even at the row's end;
- on the segmented passage card (`SRCH-100`), a long press starts text selection (`GLB-056`) and drags
  after it extend the selection, never page; a plain horizontal drag on the card pages;
- on Android, drags that start inside the system gesture insets belong to the system back gesture,
  which always wins; on iOS the edge-swipe back gesture wins wherever a route can be popped;
- the handwriting sheet (`HW-010`) and the word-candidate sheet (`TXT-040`) keep their own gestures;
  drags on them never page;
- in the two-pane layout (`GLB-125`) the pager is confined to the list pane, and the page-change
  threshold is relative to that pane's width; horizontal drags in the detail pane belong to the entry
  pager (`ENT-032`, `ENT-090`).

`SRCH-184` `[MUST]` **Pointer, keyboard and screen readers.** Every page is reachable without swiping.
On Web (and with a mouse on any platform) clicking a segment switches pages; with the indicator
focused, the Left/Right arrow keys move to the previous/next page (arrow keys in the text field keep
moving the caret); a horizontal trackpad gesture pages like a swipe, and a mouse drag does not page.
The indicator is exposed as a tab list and each page as its tab panel, so screen readers announce
`Chinese, 12 results, tab 1 of 2` (`GLB-080`).

### 7.3 Result cards

`SRCH-094` `[MUST]` A result card contains, in order:
- a row with the tone-coloured headword (title size), the muted alternate form (or merged variants)
  baseline-aligned beside it, wrapping to the next line if needed; and, right-aligned, the
  pronunciation play button;
- the tone-coloured pronunciation (accent colour), single line, ellipsised. It follows the
  pronunciation toggle (`GLB-120`): when hidden, the line is not shown but keeps its height, so cards
  do not change size; the headword's tone colours and the play button are unaffected;
- any redirect header notes (muted);
- **all** definitions as one paragraph, each numbered `1. `, `2. ` and so on in bold `fg`, separated
  by two spaces, in `muted` text; readings inside definitions follow `GLB-120`;
- structured notes joined by ` • ` (only when there are no definitions);
- HSK level badges (outlined pills in the level colour) when the entry has HSK levels.

`SRCH-095` `[MUST]` The definition paragraph is clamped and, when clamped, shows a `+N` accent badge
at the bottom-right counting the definitions that are entirely hidden. In result lists and compact
rows the paragraph is clamped to **3 lines**; in the entry-detail body it is unclamped.

`SRCH-096` `[MUST]` The card border is `accent` when the card is the currently open entry (two-pane
layouts, `GLB-125`), else `border`.

`SRCH-097` `[MUST]` A **compact** variant reduces the headword to title-medium and tightens padding;
it is used for sub-span rows, entry-detail related rows, and sheet rows. Its reading follows `GLB-120`
in the same way as `SRCH-094` (space reserved when hidden).

`SRCH-098` `[MUST]` **Inert rows** (unmatched text runs) render as a `bgSoft` card showing the run
text in `muted` plus a small uppercase `NO MATCH` label. They are not tappable.

`SRCH-099` `[MUST]` **Sentence mode grouping:** when `isSentence` is true and a segment has more than
one reading, those readings render as one card with internal hairline dividers rather than as separate
cards, so the sentence's word sequence stays visually one-card-per-word. Each reading line follows
`GLB-120` (space reserved when hidden).

### 7.4 Segmented passage card (sentence mode)

`SRCH-100` `[MUST]` When `isSentence` is true, the Chinese side begins with a passage card containing:
- the caption `Tap a Hanzi to see words at that position`;
- the display text rendered as one wrapping text layout, headline-small size, with
  matched spans in `fg` (or tone colours when enabled and alignable) and unmatched in `muted`;
  per-word underlines in alternating shades (`GLB-061`), inset at word start and end so word
  boundaries read as separate strokes, drawn only when the word-boundary setting is on; and the
  selected character highlighted with the `highlight` background and semibold weight;
- the sentence action row: play (TTS), translate, copy (`MED-020`).

`SRCH-101` `[MUST]` Tapping any character in the passage resolves the tap offset via the text layout,
normalises it to a visible code-point start, and opens the word-candidate sheet for that position
(`TXT-040`).

### 7.5 Sections and sub-span expanders

`SRCH-102` `[MUST]` Chinese-side sections, in order, each preceded by a small-caps section label with
a count chip:
- the groups themselves (unlabelled — the first category chip points at them);
- `Hanzi + Pinyin pattern matches` (Cantonese: `Hanzi + Jyutping pattern matches`);
- `Starts with 「X」`;
- `Contains 「X」 elsewhere`;
- `Related matches` — everything ranked into the side that no earlier section claimed.

For a `ROMANISATION` query the Chinese side is the ranked list with the `Combinations` section placed
per `SRCH-064`.

`SRCH-103` `[MUST]` Each matched group with sub-spans is followed by a collapsed expander pill reading
`+ N more within 「word」`. Expanding reveals the sub-span rows as compact cards behind a 2 dp left
indent bar, animated. The expanded/collapsed state is remembered per (word, row count) while the
result list lives.

`SRCH-104` `[MUST]` **Parent-reading promotion in sub-spans.** When a parent word has an unambiguous
reading, the sub-span candidate whose reading matches the corresponding slice of the parent's reading
is promoted to the top of its span group. Capitalisation is significant (`Fǎ` matches `Fǎ` before
`fǎ`). If the parent's reading cannot be aligned (syllable count not equal to character count), engine
order is kept.

### 7.6 Empty state

`SRCH-110` `[MUST]` With a blank query the screen shows one empty-state surface (no result
pages, `SRCH-180`), top to bottom, on every platform:
- the hint `Type English, Pinyin (nihao, nh or nǐ hǎo), Hanzi, or paste a sentence.` (Cantonese mode
  substitutes `Jyutping (neihou, nh or nei5 hou2)`), mentioning abbreviated input;
- the **Word of the Day** card (`SRCH-115`);
- the **Settings** card (`SRCH-116`);
- a `Popular words` heading followed by the popular result cards (`SRCH-111`).

`SRCH-111` `[MUST]` Popular words are the most frequent **multi-character** words in the dataset,
supplied as a precomputed ordered list (`DATA-090`), 50 items. They must not require a
frequency-sorted scan at runtime.

`SRCH-112` `[MUST]` The empty state and the results state use separate scroll positions and
cross-fade into each other (about 180 ms) so returning to a blank query does not inherit result
scroll.

`SRCH-115` `[MUST]` **Word of the Day card.** Shows one HSK word for the device-local date, picked
with the same deterministic function and candidate rule as the widget (`SET-063`) using the full level
mask (all levels), so the card and a widget set to all levels show the same word on the same day. It
works in both dictionary modes, drawing from the active mode's HSK rows (`HSK-090`, `HSK-096`). The card
shows the label `Word of the Day`, the tone-coloured headword with its alternate form, the reading
(following `GLB-120`, space reserved when hidden), the HSK level badge, the first definitions clamped
as on result cards (`SRCH-095`), and a speaker button that plays the word's pronunciation (`MED-001`)
without opening the entry. Tapping the card anywhere else opens the word's entry (`SRCH-130`). The word
changes when the local date changes while the screen is visible.

`SRCH-116` `[MUST]` **Settings card.** Below the Word of the Day card: a settings icon, the label
`Settings` and a chevron. Tapping it pushes the Settings route (`SET-005`); back returns to this
empty state. Because the last query is restored on launch (`SRCH-140`), Settings is reached by clearing
the query.

### 7.7 No-result state

`SRCH-113` `[MUST]` A non-blank query with zero rendered entries on a page shows, on that page, a
single centred `titleMedium` `muted` message: the current error text if any, otherwise `No matches`.

`SRCH-114` `[SHOULD]` When a page is empty but the other side has results, the message
should say so and offer the switch, e.g. `No Chinese matches — 12 English matches`. *(New behaviour —
see `Q-012`.)*

---

## 8. Keyboard and input behaviour

`SRCH-120` `[MUST]` The field is single-line with IME action `Search`. Pressing the IME search action
dismisses the keyboard and does not otherwise change results (results are already live). The
handwriting sheet has no such action; it closes per `HW-024`.

`SRCH-121` `[MUST]` The clear action empties the query, keeps focus, and re-shows the **current input
method**: the soft keyboard when the method is `keyboard`, the handwriting sheet (soft keyboard
suppressed) when it is `handwriting` (`HW-006`).

`SRCH-122` `[MUST]` Selecting a result (including a word of a combination) drops field focus and hides
the current input method — soft keyboard or handwriting sheet — before pushing the detail route, so
neither reappears when the list pane returns (`GLB-059`). Tapping the field again shows the current
method (`HW-004`).

`SRCH-123` `[MUST]` The soft keyboard and the handwriting sheet each hide the primary navigation while
visible (`GLB-073`, `HW-022`).

`SRCH-124` `[MUST]` Paste-into-search sets the query to the clipboard text (line-break-normalised
per `SRCH-011`) and scrolls to top. Clipboard availability is refreshed whenever the app window gains
focus and whenever the primary clip changes while focused; a paste attempt with no usable text does
not clear the query and does not show an error, it only updates availability so the button
disappears.

---

## 9. Navigation from results

`SRCH-130` `[MUST]` Tapping a result card — or a word inside a combination card (`SRCH-065`) or the Word
of the Day card (`SRCH-115`) — pushes an entry-detail route for that entry (`ENT-010`). Tapping the
same entry that is already the top route is a no-op.

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

All numeric budgets live in [08 — Engineering](08-engineering.md) (performance part).

`SRCH-150` `[MUST]` Keystroke-to-results latency, side change and page swipe, and typing smoothness
are held to `PERF-020`…`PERF-022`, measured as `PERF-021` describes; abbreviated input and
combinations to `PERF-025`.

`SRCH-151` `[MUST]` `CONTAINS` matching must **not** be implemented as a full index scan. The
reference implementation used a substring predicate over the whole headword index, twice per Hanzi
query (cost evidence: `PERF-023`). The dataset must provide a substring-capable index (`DATA-074`);
target `PERF-024`.

`SRCH-152` `[MUST]` Entry hydration is **batched** (never one row per id) and only for ids that will
be rendered (evidence `PERF-023`, target `PERF-024`).

`SRCH-153` `[MUST]` No per-row JSON parsing on the search path. Definitions must be readable in a form
that does not require parsing a JSON document per entry (`DATA-143`).

---

## 12. Edge cases (all `[MUST]`)

`SRCH-160` A query of only punctuation/emoji normalises to empty, so it behaves exactly like a blank
query (empty state, no section indicator, inert pager).

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

`SRCH-166` Two diacritics in one syllable: that token is rejected; if no token path survives, the
query is `LATIN` and is served by English search.

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
query, script, mode, and data generation are unchanged, rather than re-querying. A swipe is a side
switch for this rule.

`SRCH-172` A search that throws leaves the previous results visible and shows the error text in place
of the empty state; the next keystroke must recover.

`SRCH-173` `nh`: `ROMANISATION` through `[ABBR n, ABBR h]`. Two-syllable n·h words such as 你好 are
`EXACT_TONELESS` matches ordered by frequency (你好 is expected first, `PIPE-140`); longer n·h… words
follow as `PREFIX`. The English side has no match, so the Chinese page is selected (`SRCH-024`). Combinations
are computed (2 tokens) but never repeat 你好 as 你 + 好 (`SRCH-063`).

`SRCH-174` `nhao` and `nih`: `nhao` reads as `[ABBR n, FULL hao]` and reaches 你好 as
`EXACT_TONELESS` with the cost of one abbreviation (the fully spelled `nihao` reaches it with cost
0, `SRCH-052`); the full syllable `hao` constrains the second position, so only n·hao words match.
`nih` reads both as `[FULL ni, ABBR h]` (two-syllable ni·h words such as 你好, `EXACT_TONELESS`) and `[FULL ni, PARTIAL h]`
(longer ni·h… words, `PREFIX`). For both, the side follows `SRCH-024`: if the English side has prefix
matches for the final token (`nih` → `nihilism`), the remembered side applies; otherwise `CHINESE`.

`SRCH-175` `zhg`: `[ABBR zh, ABBR g]` reaches 中国 (`EXACT_TONELESS`, cost 2) ahead of three-syllable
`[ABBR z, ABBR h, ABBR g]` matches of the same class (cost 3). `zh` must also match as `z`
(`DATA-150`), so `zg` reaches 中国 too.

`SRCH-176` `x`: a single-token query is read only as `PARTIAL` (`SRCH-051`); results and ranking are
identical to those without abbreviation support, including the `SRCH-071` regression (想, 现在, 先生
at their true ranks). No combinations (one token).

`SRCH-177` `sh`: read as `[PARTIAL sh]` (one-syllable-initial words such as 是, 说, 上 as `PREFIX`)
and as `[ABBR s, ABBR h]` (two-syllable s·h words such as 时候, 上海 as `EXACT_TONELESS`, which
therefore rank first by class). English has prefix matches (`she`, `ship`), so the remembered side
applies.

`SRCH-178` `zzzz`: four `ABBR` tokens; every stage stays bounded (≤12 tokens, 8 paths, the
first-letter index caps, M = 40, k = 8) and within `PERF-025`. The Chinese page shows whatever matches
and at most 5 combinations; if it has no single-word matches and no combinations, the no-result rules
apply per page (`SRCH-113`). The English side has no match, so the Chinese page is selected.

`SRCH-179` Jyutping `ngh` (Cantonese mode): read as `[FULL ng, ABBR h]` (cost 1), `[ABBR ng, ABBR h]`
(`ng` standing for `ngo`, `ngaa`, …; cost 2) and `[ABBR n, ABBR g, ABBR h]` (cost 3). Within
`EXACT_TONELESS`, two-syllable words whose first syllable is the full syllable `ng` rank before those
whose first syllable is merely abbreviated by `ng`, and both before three-syllable n·g·h words.

`SRCH-185` T恤 regression: `Txu`, `T xu`, `t xu4` still reach T恤 through `[LITERAL t, FULL xu]` as a
whole-query match; 体恤 (`ti3 xu4`) also matches through `[ABBR t, FULL xu]`, and both are ordered
by `SRCH-070` (equal token cost). With `t xu4`, the tone on `xu` constrains both.
