# 07 — Data Contract and Pipeline

## Data Contract (processed dictionary dataset)

This part defines **exactly what the application receives**. The contract is the module boundary
between the dataset pipeline (`pipeline/`) and the app (`app/`), which live in the same repository
(`PIPE-160`): the pipeline satisfies this contract, the app assumes only this contract, and neither
depends on the other's code. Shared schema and test vectors live in `contract/`.

Section 2 (logical model) is `[MUST]`. Section 9 (physical encoding) is `[REC]` — a concrete,
evidence-based recommendation that may be replaced if the logical contract and the performance budgets
in [08 — Engineering, performance part](08-engineering.md) are still met.

Sizing figures quoted throughout are measured from the reference Mandarin dataset: 124760 entries,
197616 distinct headwords, 48800 distinct English tokens, 709027 unique (token, entry) postings, 14178
distinct characters, 427396 character postings, 11105 HSK rows, 41319 frequency rows, and 5.4 MB of
definition text.

---

### 1. Packaging and identity

`DATA-001` `[MUST]` A **dataset** is a self-contained, read-only, immutable bundle serving exactly one
dictionary mode. There are two datasets, `mandarin` and `cantonese`. **Both use the identical
schema.** The app selects a dataset by mode and needs no mode-specific code paths. On mobile both
datasets are installed with the app; on Web each is fetched when first needed (`DATA-215`).

`DATA-002` `[MUST]` A dataset declares, in a metadata table/section:

| Field | Type | Example |
|-------|------|---------|
| `schema_version` | int | `1` |
| `dataset_id` | string | `mandarin` / `cantonese` |
| `dataset_version` | string | `2026.07.04+3f2a91c` (`PIPE-166`) |
| `pipeline_commit` | hex | repository commit that produced the inputs (`PIPE-166`) |
| `input_hash` | hex | hash of the pipeline inputs (`PIPE-163`) |
| `built_at` | ISO-8601 UTC | `2026-08-24T09:12:03Z` (informational; not part of `content_hash`) |
| `content_hash` | hex | sha256 of the canonical content |
| `language` | ISO 639-3 | `cmn` / `yue` (script is not part of the language; both scripts are present) |
| `romanization` | enum | `pinyin` / `jyutping` |
| `tone_count` | int | `5` / `6` |
| `entry_count` | int | `124760` |
| `max_headword_len` | int | longest headword in code points, e.g. `19` (`DATA-004`) |
| `max_word_len` | int | segmentation window in code points, e.g. `12` (`DATA-004`) |
| `total_frequency` | int64 | corpus total for log-probabilities |
| `has_frequency_data` | bool | selects DAG vs greedy segmentation (`TXT-012`) |
| `has_hsk` | bool | `true` for both datasets (`DATA-187`) |
| `sources` | list | per-source name, version/commit, date, licence, URL (`SET-042`) |
| `asset_packs` | list | companion files and packs with their versions and SHA-256 (`DATA-170`) |
| language-model fields | — | `has_bigram_model`, `lm_source`, `abbrev_penalty`, `partial_penalty`, … (`DATA-155`) |

`DATA-003` `[MUST]` The app verifies `schema_version` against the range it supports **before** any
query. A mismatch is a hard, user-visible error naming both versions. It must never attempt a partial
read. On mobile the dataset ships with the app, so a mismatch is a build error caught in CI
(`PIPE-163`); the runtime check remains as a safeguard and matters for a Web cache.

`DATA-004` `[MUST]` `max_word_len` is the **single source** for the segmentation window and for
sub-span enumeration (`TXT-012`, `TXT-013`, `TXT-020`, `TXT-077`, `DATA-102`): the longest headword, in
code points, that the segmenter considers as a candidate word. The pipeline chooses it (reference value
12) and validates it (`DATA-200` gate 7). Headwords longer than `max_word_len` remain searchable and
openable but are never produced by segmentation. `max_headword_len` (reference 19) is used only to
short-circuit queries that cannot match (`SRCH-167`). The app reads both values and never assumes
either.

`DATA-005` `[MUST]` The dataset is opened read-only and is never mutated by the app. Any per-user state
(settings, widget masks) lives in separate app-owned storage.

`DATA-006` `[MUST]` `content_hash` covers the content, not the file bytes, so the same logical dataset
built twice validates identically. The language-model companion file and every asset pack
(`DATA-170`) reference the `dataset_version` they were built against and are rejected on mismatch.
Non-dataset assets (voice, handwriting and OCR models — `DATA-170`) are **not** part of the dataset,
are not covered by `content_hash`, and are versioned independently of `dataset_version`.

---

### 2. Logical model

#### 2.1 Entry

`DATA-010` `[MUST]` An **entry** is one (traditional form, simplified form, reading) triple with its
senses — the CC-CEDICT record granularity. Fields:

| Field | Type | Notes |
|-------|------|-------|
| `id` | int32 | **stable within a dataset version**; dense from 0 |
| `traditional` | string | required |
| `simplified` | string | required; may equal `traditional` |
| `reading_display` | string | what the UI shows |
| `reading_canonical` | string | space-separated numbered syllables, lowercase, umlaut normalised |
| `syllables` | string[] | toneless, lowercase, one per position |
| `tones` | int8[] | parallel to `syllables`; `0` means unspecified |
| `is_proper_noun` | bool | precomputed from the source's capitalisation (`GLB-013`) |
| `frequency_rank` | int32 | 1 is most common; `1000000` is the unranked sentinel |
| `char_count` | int16 | code points in `simplified` |
| `senses` | sense[] | ordered; may be empty |
| `notes` | note[] | ordered; may be empty |
| `redirect` | redirect (nullable) | resolved (`DATA-055`) |
| `merge_group_simplified` | int32 (nullable) | (`DATA-080`) |
| `merge_group_traditional` | int32 (nullable) | (`DATA-080`) |
| `hsk_levels` | int8[] | ascending; empty when none |
| `audio_word_key` | string (nullable) | canonical audio key (`MED-005`) |
| `lm_word_id` | int32 | language-model word id of the headword form (`DATA-152`) |
| `lm_unigram` | uint8 | quantised smoothed unigram log-probability (`DATA-152`, `DATA-154`) |

`DATA-011` `[MUST]` `syllables` and `tones` have equal length. When `char_count` equals the syllable
count the entry is tone-colourable (`GLB-066`); the app must not assume it is.

`DATA-012` `[MUST]` Every entry has both script forms populated. Where the source gives one form only,
both fields carry it.

`DATA-013` `[MUST]` `id` is stable within a `dataset_version` and is used as the final ranking
tiebreak. It is **not** stable across dataset versions; anything persisted or linked across versions
(widget deep links, routes, future bookmarks) must key on the natural key instead (`DATA-014`,
`DATA-212`).

`DATA-014` `[MUST]` The dataset also exposes a **stable natural key** per entry: a truncated hash of
`traditional + " " + simplified + " " + reading_canonical`, so external references (widgets, future
bookmarks, deep links) survive a dataset upgrade. Lookup by natural key must be indexed.

#### 2.2 Sense

`DATA-020` `[MUST]` A **sense** is one displayable definition:

| Field | Type | Notes |
|-------|------|-------|
| `index` | int16 | 0-based position within the entry; drives the displayed number |
| `text` | string | display text |
| `segments` | segment[] | the parsed structure (`DATA-050`) |
| `source` | enum | `cedict` / `cccanto` / `cccedict_canto` / `hsk_syllabus` |
| `tokens` | string[] | English tokens per `SRCH-013`, precomputed |

`DATA-021` `[MUST]` Sense ordering within an entry is the pipeline's decision and is final; the app
renders in the given order and numbers from 1.

`DATA-022` `[MUST]` In Cantonese mode, senses from Cantonese sources come first, then Mandarin-sourced
senses. The app displays `source` provenance only when an entry mixes sources. *(Whether provenance
should always be labelled is `Q-080`.)*

`DATA-023` `[MUST]` Senses are never empty strings. A source record with no gloss produces zero senses,
not one empty one.

#### 2.3 Parsed sense segments (internal links)

`DATA-050` `[MUST]` Every sense's markup is parsed **during preprocessing** into an ordered segment
list. The app never parses definition prose at runtime. Segment kinds:

- `TEXT { text }` — literal prose, verbatim.
- `READING { display, romanization }` — a bracketed reading; **not** a link.
- `REF { traditional, simplified, reading_display?, reading_canonical?, target_entry_id?, romanization }`
  — a link.

`DATA-051` `[MUST]` `REF` covers all reference shapes present in the sources: traditional-bar-simplified
with a bracketed reading, a single form with a bracketed reading, a bare Hanzi run with no reading, and
Latin/digit forms with a reading.

`DATA-052` `[MUST]` A bare Hanzi run (with interior middle dots normalised per `GLB-005`) is one `REF`
covering the whole run, with the traditional and simplified fields both set to the run and no reading.
The app renders one tap range for the whole run (`ENT-055`).

`DATA-053` `[MUST]` `romanization` on `READING` and `REF` records **which system the reading is in**
(`pinyin`, `jyutping`, `tailo`, `poj`, `teochew`, `wade_giles`, `unknown`). The pipeline resolves this
from the surrounding prose cues; the app renders tone marks only for `pinyin` and displays anything
else verbatim (`GLB-014`, `ENT-052`). Runtime cue-scanning is forbidden.

`DATA-054` `[MUST]` `target_entry_id` is populated when the reference resolves to exactly one entry by
the tiering rules of `ENT-046`; otherwise it is null and the app opens the disambiguation sheet
(`ENT-054`). The pipeline must report the count of unresolved references.

#### 2.4 Redirect (variant expansion)

`DATA-055` `[MUST]` When an entry's only content is a pointer at another entry, the dataset carries a
redirect record: `kind` (`variant` / `old_variant` / `erhua`), `header_text` with its own parsed
segments, `target_entry_id` (nullable when ambiguous), and a `resolved` flag.

`DATA-056` `[MUST]` When resolved, the app renders `header_text` plus the **target's** senses
(`ENT-047`). The target's senses are reachable by id in one read; they are **not** duplicated into the
source entry.

`DATA-057` `[MUST]` An **embedded** variant (a trailing parenthetical "variant of X" inside an
otherwise semantic definition) is marked per sense with an `embedded_variant_ref` so the merge logic of
`SRCH-082` can strip it without re-parsing.

#### 2.5 Note

`DATA-060` `[MUST]` A **note** is structured, never free text to be re-parsed: `kind` (one of `see`,
`variant_of`, `old_variant_of`, `classifier`, `taiwan_pr`, `abbr_for`, `also_written`, `also_pr`,
`see_also`), `references` (same shape as `REF`, with resolved ids where unambiguous), `reading`
(nullable display form), `extra` (nullable free text), and a `display_label` for the kind. These
snake_case identifiers are the **only** spelling of note kinds — in the dataset, the pipeline, app code
and every spec document.

`DATA-061` `[MUST]` A note consumed by a resolved redirect is **removed** by the pipeline, not filtered
at runtime (`ENT-048`).

`DATA-062` `[MUST]` Reference note counts in the Mandarin source, for validation: `see` 4222,
`variant_of` 2553, `classifier` 1578, `old_variant_of` 728, `taiwan_pr` 545, `abbr_for` 318,
`also_written` 224, `also_pr` 178, `see_also` 109 — 10219 entries carry at least one note.

---

### 3. Search indexes

`DATA-070` `[MUST]` **Syllable inventory.** The dataset carries the complete toneless syllable
inventory for its romanisation, its alias map, its abbreviation table (`DATA-150`), and the maximum
syllable length. The app's romanisation parser is driven entirely by these tables and contains no
hard-coded language knowledge (`GLB-010`). Mandarin: about 400 syllables. Jyutping: about 620.

`DATA-071` `[MUST]` **Headword index.** For each entry, keys for: the simplified form, the traditional
form (when different), the NFKC-plus-middle-dot **folded** form of each (when different), and the
ASCII-lowercased form of each (when different). One index answering exact match and prefix range over
all of them, keyed by (key, entry id).
Reference size: 202396 (key, entry) pairs over 197616 distinct keys.
**Key normalisation:** every matching key in the dataset (headword, reading, substring, character,
English token and abbreviation keys) is produced with exactly the normalisation the app applies to the
query matching key in `SRCH-010` — Unicode NFKC plus the apostrophe and middle-dot folding defined
there. Display strings keep their source form. Pipeline and app share the normalisation test vectors
in `contract/` (`PIPE-141`).

`DATA-072` `[MUST]` **Case handling is baked in.** The app must not enumerate ASCII case permutations
at query time (the reference app generated up to 256 variants per query). A single case-folded key
column plus the original key is sufficient.

`DATA-073` `[MUST]` **Reading index.** Keyed by the space-joined toneless syllable string, supporting
exact match and prefix range, with the tone array available for tone filtering without a second read.

`DATA-074` `[MUST]` **Substring (CONTAINS) index.** An index that answers "headwords containing X at
position 1 or later" **without scanning**. Contract: given a query of one or more code points, return
candidate entry ids in bounded time.
Recommended construction (`[REC]`): index every substring of length 1 to 4 starting at position 1 or
later of every headword; for longer queries, filter by the query's first 4 code points and verify the
full match against the candidates' headwords. Measured on the reference data: 589211 (substring, entry)
pairs over 164307 distinct keys for length at most 4 — small enough to ship, and it removes a
full-index scan that cost about 6 ms per call on a desktop and much more on a phone.

`DATA-075` `[MUST]` **Character index.** For each distinct Hanzi in either script form of an entry, a
posting list of entry ids ordered by (frequency rank, id). Reference: 14178 keys, 427396 postings. Used
by mixed-pattern search (`SRCH-039`) and by the "appears in" fallback (`TXT-022`).

`DATA-076` `[MUST]` **English token index.** Keyed by token (per `SRCH-013`), with postings of
(entry id, sense index) so single-sense matching (`SRCH-047`) needs no extra read. Must support exact
key lookup and prefix range (for last-token prefix matching). Reference: 48800 distinct tokens, 709027
unique (token, entry) pairs, 752642 (token, entry, sense) rows.

`DATA-077` `[MUST]` Each sense's token list is also stored **per sense** so contiguous-phrase detection
(`EN_PHRASE`) is a local check on the candidate, not a re-tokenisation of its text.

`DATA-078` `[MUST]` **Ranking projection.** For every entry, the ranking inputs — match class
(computed at query time), frequency rank, character count, proper-noun flag, id, and the LM unigram
(`DATA-152`) — must be readable **without** loading definitions. Ranking a 30000-candidate set must not
hydrate 30000 full entries.

`DATA-079` `[MUST]` Every posting list is stored in ascending entry-id order so intersections are
linear merges. The only exception is the first-letter index (`DATA-151`), whose postings are in
frequency order so that a capped read keeps the most frequent words.

---

### 4. Variant merge groups

`DATA-080` `[MUST]` The pipeline computes merge groups (`SRCH-080`) **twice**, once per script: the
group key is (displayed headword for that script, displayed reading). A group is materialised only when
it has two or more members **and** at least two distinct alternate-script forms.

`DATA-081` `[MUST]` Each entry stores a nullable merge-group id per script. A group table gives, per
group id: the ordered member entry ids (primary first, chosen by direct-sense count descending then id
ascending) and the ordered distinct alternate forms for the display line.

`DATA-082` `[MUST]` "Primary" is the member the app shows as the group's representative; the app must
not recompute it.

`DATA-083` `[MUST]` Groups are global (`SRCH-083`), so search, sheet, and entry detail agree.

---

### 5. Relationships (compounds, sub-spans, related words)

`DATA-100` `[MUST]` Three relationship families must be answerable by indexed lookup, never by runtime
enumeration plus per-substring queries:

1. **starts_with(word)** — entries whose headword has `word` as a strict prefix. Served by the headword
   prefix range (`DATA-071`).
2. **contains(word)** — entries whose headword contains `word` at position 1 or later. Served by
   `DATA-074`.
3. **within(word)** — dictionary words contained *inside* `word`, excluding `word` itself, with each
   occurrence's start, length and entry id.

`DATA-101` `[MUST]` `within` is **precomputed per entry** for multi-character entries of **at most 8
code points**: an ordered list of (start, length, entry id) sorted longest-first, then start ascending,
then target frequency rank, then target id. Longer entries follow `DATA-102`. This removes the single
largest runtime cost of the reference implementation, which enumerates every substring of the word and
issues a lookup per substring, on every entry open and every search group.

`DATA-102` `[MUST]` For entries longer than 8 code points, `within` is **computed on demand** by the
app with one batched headword-index lookup over the entry's substrings (bounded by `max_word_len`,
`DATA-004`), never one query per substring, and ordered as in `DATA-101`. The bound is supported by the
measured distribution: about 95% of the 197616 distinct headwords are at most 4 code points, and only
1221 exceed 8.

`DATA-103` `[MUST]` `containing_reversed(word)` (`ENT-060` item 4) is served by `contains` applied to
the reversed string; no separate index is needed.

`DATA-104` `[MUST]` Related-word sections must be **pageable**: the storage layer must be able to
return the top N by the canonical comparator with an offset, so the app fetches a first page rather
than up to 500 rows per section (`ENT-064`).

---

### 6. Frequency and segmentation data

`DATA-110` `[MUST]` A **word-frequency table** keyed by surface form with an integer count, plus the
corpus total in metadata. Reference: 41319 forms, total 81775831; only 28283 of 124760 entries carry a
real rank.

`DATA-111` `[MUST]` `frequency_rank` on the entry is derived by the pipeline from the frequency table
(dense rank, 1-based), with the sentinel `1000000` where absent. The app never computes it. The
pipeline `[SHOULD]` rank entries that the frequency table leaves unranked by their smoothed LM unigram
(`DATA-152`), recording the choice in metadata.

`DATA-112` `[MUST]` For segmentation the app needs, per candidate substring, "is this a dictionary
word" and "what is its frequency". Both must be answerable in one batched lookup per run
(`TXT-014`), so the frequency value should be co-located with the headword index or reachable by the
same key.

`DATA-113` `[SHOULD]` Precompute the natural log-probability per headword so the segmenter performs no
logarithms at runtime.

`DATA-114` `[MUST]` For Cantonese, if no Cantonese frequency corpus is available, the pipeline must
either (a) derive frequencies from the Cantonese language-model unigrams (`DATA-152`, `PIPE-074`),
(b) reuse Mandarin frequencies for shared surface forms, in both cases setting `has_frequency_data`
true, or (c) omit frequencies and set it false, in which case the app uses greedy longest-match
(`TXT-013`). The choice must be recorded in metadata. *(Decision needed — `Q-081`.)*

---

### 7. Language-model data (abbreviated pinyin and combinations)

These tables serve abbreviated and mixed pinyin/Jyutping input (`SRCH-050`…`SRCH-054`) and multi-word
combinations (`SRCH-060`…`SRCH-068`) through the shared lattice decoder (`TXT-026`). They are data, so
the decoder stays language-neutral (`GLB-010`). The first-letter index (`DATA-151`) and the bigram
table (`DATA-153`) form the dataset's **language-model companion file**, delivered per `DATA-170`; the
abbreviation table, per-entry word ids and unigrams, codebooks and flags are small and stay in the
main dataset. Expected size of the language-model data: about 2.5 to 3.5 MB per dataset (first-letter
index about 1.1 to 1.4 MB, bigrams about 1.3 to 1.8 MB); budget `PERF-070`.

`DATA-150` `[MUST]` **Syllable abbreviation table.** For every syllable of the inventory (`DATA-070`),
the spellings that may stand for it as an abbreviated token: its first letter, plus its multi-letter
initial where the romanisation has one. Mandarin: `zhang` → `z`, `zh`; `chi` → `c`, `ch`; `shi` → `s`,
`sh`; vowel-initial syllables abbreviate to their first letter (`ai` → `a`); `i`, `u` and `v` are never
abbreviations. Jyutping: `ngo` → `n`, `ng`; `gwong` → `g`, `gw`; `kwai` → `k`, `kw`. The app's
spelling stage (`SRCH-050`) uses only this table to recognise abbreviated tokens.

`DATA-151` `[MUST]` **First-letter index `abbr_idx`.** Key: the first letter of each syllable of an
entry's toneless reading, concatenated in order, lowercase ASCII (你好 `ni hao` → `nh`; 中国人 → `zgr`;
我哋 `ngo dei` → `nd`). Indexed entries: those with 1 to 6 syllables whose reading parses fully against
the inventory (Latin-initial readings are excluded). Postings per key, in descending unigram order
(`DATA-079` exception), each carrying: entry id, LM word id (`DATA-152`), quantised unigram
(`DATA-154`), and the syllable inventory indices of the reading, so the decoder can filter postings
per token (for example `zh` versus `z`) without reading the entry. Caps: single-letter keys keep the
300 most frequent entries; longer keys are capped by a pipeline-configured limit reported in the build
report (`PIPE-076`).

`DATA-152` `[MUST]` **LM word ids and unigrams.** Every distinct headword form used by the dataset's
language model (simplified form for Mandarin, traditional form for Cantonese) has a dense LM word id
from 0, assigned in descending unigram probability so frequent words have small ids. **Every** headword
has a finite, smoothed unigram log-probability (natural log), including headwords absent from the
source model (`PIPE-072`). Each entry carries its form's word id and quantised unigram (`DATA-010`) so
ranking, segmentation and the decoder need no extra read.

`DATA-153` `[MUST]` **Bigram block format.** One block per left word that has surviving bigrams,
keyed by left word id: the left word's backoff weight (codebook index), a successor count, the
successor word ids ascending and delta-encoded as unsigned varints, and one codebook index per
successor for the conditional log-probability. A left word without a block has backoff 0. Only pairs
of dictionary headwords are stored; trigrams are not stored. Scoring: `lp(w | v)` is the stored bigram
when present, otherwise `backoff(v) + unigram(w)`.

`DATA-154` `[MUST]` **Quantisation codebook.** Unigram log-probabilities, bigram log-probabilities and
backoff weights are each stored as a 1-byte index into a codebook of at most 256 natural-log values,
one codebook per quantity, built by the pipeline (`PIPE-073`) and stored in the main dataset. Decoding
is a table lookup; the app performs no logarithms.

`DATA-155` `[MUST]` **Language-model metadata.** The dataset metadata (`DATA-002`) carries:

| Field | Type | Notes |
|-------|------|-------|
| `has_bigram_model` | bool | false → the decoder scores with unigrams only |
| `lm_source` | list | per source: name, version, licence, URL; flags a Mandarin fallback for Cantonese (`PIPE-074`) |
| `abbrev_penalty` | float | natural-log penalty per abbreviated syllable; default `ln 0.5` |
| `partial_penalty` | float | natural-log penalty for a partial final syllable |
| `lm_word_count` | int | number of LM word ids |
| `lm_file` | object | SHA-256 and size of the language-model companion file (`DATA-170`) |

---

### 8. HSK data

`DATA-120` `[MUST]` An **HSK row table**, present when `has_hsk` (both datasets, `DATA-187`), already
**ordered** by (level, pronunciation sort key) so the app reads it sequentially:

| Field | Type | Notes |
|-------|------|-------|
| `row_id` | int32 | stable unique key for list identity (`HSK-026`) |
| `level` | int8 | 1 to 7; 7 means the 7-9 band |
| `simplified` | string | |
| `traditional` | string | |
| `syllabus_reading_display` | string | the syllabus's own (sandhi) Pinyin form, in both datasets |
| `syllabus_reading_canonical` | string | |
| `parts_of_speech` | string[] | source tokens for this level, de-duplicated |
| `syllabus_senses` | string[] | the syllabus gloss |
| `entry_id` | int32 (nullable) | resolved canonical entry; null when unlinked |
| `sort_key` | string | precomputed pronunciation sort key (`HSK-030`) |

`DATA-121` `[MUST]` `sort_key` is the normalised canonical reading of the linked entry when linked,
else of the syllabus row (`HSK-031`). In the Mandarin dataset rows are stored in (level, sort_key,
simplified, row_id) order. In the Cantonese dataset the linked entry's reading is **Jyutping**, so
linked rows sort by Jyutping, and unlinked rows sort **after** the linked rows of their level, by their
syllabus Pinyin (`HSK-093`): storage order is (level, unlinked flag, sort_key, simplified, row_id).

`DATA-122` `[MUST]` A **part-of-speech label table** mapping each source token to a label in every UI
language — English, zh-Hans and zh-Hant (`HSK-044`, `GLB-100`) — so new tokens do not need an app
release.

`DATA-123` `[MUST]` The rows are already **de-duplicated per (level, word, reading)** with parts of
speech merged (`HSK-027`). The reference Mandarin data contains 14 such collisions.

`DATA-124` `[MUST]` Unlinked rows must be **reported and gated** by the pipeline (`HSK-056`); the
reference dataset shipped 9.

`DATA-125` `[MUST]` The entry's `hsk_levels` is derived from these rows, ascending and de-duplicated.

`DATA-126` `[MUST]` Per-entry, per-level parts of speech must be readable **with the entry** (one read),
not by a second query at entry-open time.

`DATA-130` `[MUST]` A separate, tiny **widget projection** (`SET-066`) carrying only what the
word-of-the-day widget renders: entry id, natural key, levels, both scripts, both readings, header
notes, senses, notes. One is emitted per dataset, Mandarin and Cantonese, since the widget follows
the dictionary mode (`SET-062`) and the Word of the Day card (`SRCH-115`) uses the same deterministic
pick (`SET-063`) over it. Reference size about 0.55 MB compressed for 11k
words. The widget must never open the main dataset.

---

### 9. Physical encoding — recommendation

`DATA-140` `[REC]` **Primary recommendation: one SQLite file per dataset**, plus one language-model
companion SQLite file per dataset (`DATA-170`), both opened read-only, with the following departures
from the reference schema. SQLite is chosen because it is native on Android and iOS, works on the Web
via a WASM build over OPFS, gives cancellable queries, needs no full-file load, and supports
incremental page-level access.

`DATA-141` `[REC]` Tables (column lists abbreviated):

Main dataset file:

- `meta(key TEXT PRIMARY KEY, value TEXT)` — `DATA-002`, `DATA-155`
- `entry(id INTEGER PRIMARY KEY, trad TEXT, simp TEXT, reading_display TEXT, reading_canonical TEXT,
  syllables BLOB, tones BLOB, flags INTEGER, freq_rank INTEGER, char_count INTEGER, natural_key BLOB,
  merge_simp INTEGER, merge_trad INTEGER, hsk_levels BLOB, audio_key TEXT, lm_word INTEGER,
  lm_unigram INTEGER, senses BLOB, notes BLOB, redirect BLOB)` — `lm_word`/`lm_unigram`: `DATA-152`
- `entry_natural(natural_key BLOB PRIMARY KEY, entry_id INTEGER)`
- `headword(key TEXT, entry_id INTEGER, kind INTEGER)` — kind: exact / folded / lowercased
- `reading(key TEXT, entry_id INTEGER)`
- `substr_idx(key TEXT PRIMARY KEY, postings BLOB)` — `DATA-074`
- `char_idx(ch TEXT PRIMARY KEY, postings BLOB)` — `DATA-075`
- `en_token(token TEXT PRIMARY KEY, postings BLOB)` — `DATA-076`, entry plus sense pairs
- `merge_group(id INTEGER PRIMARY KEY, members BLOB, variants TEXT)`
- `within(entry_id INTEGER PRIMARY KEY, rows BLOB)` — `DATA-101`
- `word_freq(key TEXT PRIMARY KEY, freq INTEGER, log_prob REAL)`
- `hsk_row(row_id INTEGER PRIMARY KEY, level INTEGER, ...)` — `DATA-120`, stored in sort order
- `hsk_pos_label(token TEXT PRIMARY KEY, label TEXT)`
- `syllable(value TEXT PRIMARY KEY)` and `syllable_alias(src TEXT PRIMARY KEY, dst TEXT)` — `DATA-070`
- `syllable_abbrev(abbrev TEXT, syllable TEXT, PRIMARY KEY(abbrev, syllable))` — `DATA-150`
- `lm_codebook(kind INTEGER, code INTEGER, value REAL, PRIMARY KEY(kind, code))` — `DATA-154`
- `popular(rank INTEGER PRIMARY KEY, entry_id INTEGER)` — `DATA-090`

Language-model companion file:

- `meta(key TEXT PRIMARY KEY, value TEXT)` — `dataset_id`, `dataset_version` it targets
- `abbr_idx(key TEXT PRIMARY KEY, postings BLOB)` — `DATA-151`
- `bigram(left INTEGER PRIMARY KEY, block BLOB)` — `DATA-153`

`DATA-142` `[REC]` Indexes: exactly the ones the queries need, and **no reverse indexes**. Required:
`headword(key, entry_id)`, `reading(key, entry_id)`, `hsk_row(level, sort_key, row_id)`,
`entry(freq_rank, id)`; `abbr_idx`, `bigram`, `syllable_abbrev` and `lm_codebook` need only their
primary keys. The reference schema carried a headword-by-entry index (8 MB) and an
english-token-by-entry index (13.5 MB) that no query used — 21.5 MB of pure waste.

`DATA-143` `[REC]` `senses`, `notes`, `redirect`, `within`, and all posting lists are **length-prefixed
binary blobs**, not JSON:
- posting lists: delta-encoded ascending entry ids as unsigned varints (ids reach 124760, so deltas
  average one to two bytes); `abbr_idx` postings (frequency order) use plain varints;
- `within` rows: varint triples of start, length, and entry-id delta;
- senses: a varint count, then per sense a varint source enum, a varint segment count, and per segment
  a tag plus length-prefixed UTF-8 fields;
- bigram blocks: as `DATA-153`;
- the app decodes a blob only for entries it actually renders.

This replaces the per-row JSON parse that the reference app performs on every hydrated entry and every
HSK row.

`DATA-144` `[REC]` Store definition text with **per-blob deflate or zstd** when a blob exceeds about
200 bytes, or use SQLite page compression. The reference definition text is 5.4 MB raw; general-purpose
compression of the whole source bundle reaches 6.3 MB for everything, so per-blob compression should
recover most of that while keeping random access.

`DATA-145` `[REC]` Set the page size to 4096, run `VACUUM` and `ANALYZE` at build time, ship with WAL
disabled (read-only), and set the file's user-version to `schema_version`.

`DATA-146` `[REC]` **Do not copy the dataset out of the app package at first run.** The reference app
ships a 90 MB SQLite asset **and** copies it to the app's database directory, so the installed footprint
is about 180 MB. Read the installed file in place (store it uncompressed in the APK or install-time
asset pack and open it via a platform file descriptor or a read-only VFS; read it directly from the iOS
bundle). On Web, the fetched file lives once in OPFS. The raw 6.3 MB gzipped JSON source was **also**
shipped in the reference package and must not be.

`DATA-147` `[REC]` Expected size for the Mandarin dataset under this encoding: **10 to 16 MB**
installed, versus 90 MB (180 MB installed) today. Budgets: `PERF-070`, per-flavour totals `PERF-073`.

`DATA-148` `[REC]` Acceptable alternative physical designs, if the budgets are met: a custom
memory-mappable binary format with FST or trie key indexes (smaller and faster, more work, harder to
evolve); or SQLite plus FTS5 for the English index (simpler prefix search, larger). A pure in-memory
index is **not** acceptable: the reference app's earlier in-memory build failed with a 33.5 MB
contiguous-allocation error and retained hundreds of MB.

---

### 10. Popular words

`DATA-090` `[MUST]` A precomputed ordered list of 50 entry ids for the blank-query state: the most
frequent **multi-character** words (`SRCH-111`). It must not require a frequency-sorted scan at runtime,
and it must be resolved to ids at build time rather than to surface forms resolved at open time.

---

### 11. Companion files, asset packs and non-dataset assets

`DATA-170` `[MUST]` Besides the main dataset file, each dataset has a language-model companion file
(`DATA-151`, `DATA-153`), and asset families are separate packs. Each companion file and pack declares
the `dataset_version` it targets (`DATA-006`). There are no runtime downloads on mobile: everything
below is installed with the app (`DATA-215`).

| Pack / file | Contents | Reference size | Access key |
|-------------|----------|----------------|------------|
| `lm` (per dataset) | first-letter index, bigram blocks | about 2.5 to 3.5 MB | key / left word id |
| `audio-syllables` | per-syllable recordings | about 13 MB, 1707 clips | canonical syllable (`MED-007`) |
| `audio-words` | full-word recordings | about 52 MB, 6657 clips | canonical word key (`MED-005`) |
| `strokes` | outlines, medians, radical indices | about 30 MB | code point |
| `sentences` | example pairs plus a word index | about 5.5 MB | simplified headword |

**Non-dataset assets.** These are app assets, not part of any dataset: they are not covered by
`content_hash`, are versioned independently of `dataset_version` (each pinned by version and SHA-256 in
the repository lock file, `PIPE-162`), and are credited in `SET-040`.

| Asset | Approx. size | Licence | Platforms |
|-------|--------------|---------|-----------|
| Voice model: sherpa-onnx streaming Paraformer, trilingual zh/yue/en, int8 (`VOICE-001`…) | about 240 MB | per the model's published licence (attribution wording: `09-open-questions.md`) | Android, iOS (not Web) |
| OpenCC conversion table for voice output (`VOICE-001`…) | about 1 MB | Apache-2.0 | Android, iOS |
| Handwriting model: ML Kit Digital Ink, simplified / traditional / Hong Kong (`HW-001`…) | about 20 MB per script model (measure) | ML Kit terms (`SET-040`) | Android, iOS bundled; Web repackaged, fetched on first use |
| OCR model: ML Kit Chinese text recognition (`CAM-001`…) | about 15 to 20 MB | ML Kit terms (`SET-040`) | Android, iOS (not Web) |

`DATA-171` `[MUST]` Each pack declares an index that maps its access key to a record location, so
reading one record costs one seek plus one decode. The reference packs are 256 whole-JSON shards that
must be fully parsed to read one character.

`DATA-172` `[MUST]` An **audio catalogue** lists the available word keys and syllable keys so
availability can be answered without touching the audio files. It is built in the same pipeline run
as the datasets (`PIPE-061`).

`DATA-173` `[MUST]` On mobile every pack and companion file is present after install; a missing or
unreadable one is a defect, but the app still degrades gracefully (`GLB-035`). On Web a not-yet-fetched
or failed pack is a normal state handled by `GLB-035`.

---

### 12. Cantonese specifics

`DATA-180` `[MUST]` The Cantonese dataset is produced by joining Cantonese sources onto the Mandarin
headword space; it is a **complete, standalone dataset** in the same schema, not a delta.

`DATA-181` `[MUST]` Readings are Jyutping; tones are 1 to 6; `romanization` is `jyutping`;
`tone_count` is 6; the syllable inventory is Jyutping.

`DATA-182` `[MUST]` Sense provenance rules:
- a Cantonese-source gloss for the headword and reading becomes a sense with source `cccanto`;
- a Cantonese-annotated CEDICT gloss becomes `cccedict_canto`;
- the Mandarin entry's glosses for the same headword are carried over with source `cedict`, **after**
  the Cantonese ones;
- a headword present in Cantonese sources but absent from CC-CEDICT still becomes an entry;
- a headword present in CC-CEDICT with a known Cantonese reading but no Cantonese gloss still becomes an
  entry, carrying the Mandarin glosses (`PROD-045`).

`DATA-183` `[MUST]` Where a Cantonese source supplies **additional** senses for a word that also exists
in Mandarin, those senses are added, not substituted.

`DATA-184` `[MUST]` One Chinese headword may have several Cantonese readings; each distinct reading is
its own entry, exactly as in Mandarin.

`DATA-186` `[MUST]` Cantonese entries carry a null `audio_word_key` unless a Cantonese audio pack
exists. The reference audio corpus is Mandarin-only.

`DATA-187` `[MUST]` **HSK in the Cantonese dataset.** The Cantonese dataset carries its own HSK
row table (`has_hsk` true; display rules `HSK-090`…`HSK-096`). The pipeline joins every row of the
committed HSK word list to the Cantonese entries **by written form, never by reading** (the syllabus
reading is Pinyin): the row's simplified word is mapped to candidate traditional forms with OpenCC
`s2t` and `s2hk` at build time (plus the word list's own traditional form, when present); an entry
matches when its traditional form equals a candidate, with the simplified form as a cross-check. Where
the written form has several Cantonese entries (one per Jyutping reading, `DATA-184`), the pipeline
picks one deterministically: the entry sharing senses with the row's Mandarin-linked entry
(`PIPE-041`), then the lowest frequency rank, then the lowest id; the decision is recorded in the build
report. Jyutping readings come from CC-Canto / CC-CEDICT-Canto through the linked entry; the row's
`sort_key` is that entry's Jyutping canonical reading (`DATA-121`). Levels, row set, de-duplication
and parts of speech are identical to the Mandarin dataset (`HSK-091`); only `entry_id` and `sort_key`
differ, and the syllabus reading fields keep the syllabus Pinyin (shown labelled `Mandarin`,
`HSK-092`). A row that matches no Cantonese entry is emitted with a null `entry_id`, and every
unmatched word is enumerated in the build report and gated (`DATA-200` gate 8).

---

### 13. Validation requirements

`DATA-200` `[MUST]` A dataset that fails **any** of these gates must not be published:

1. `schema_version`, `dataset_version`, `content_hash`, and every metadata field present and well-formed.
2. `entry_count` matches the actual row count.
3. Every entry: both script forms non-empty; `reading_canonical` parses fully against the declared
   syllable inventory (report and cap exceptions such as Latin-initial readings); syllable and tone
   arrays of equal length.
4. Every posting list is ordered as declared (`DATA-079`) and references existing entry ids.
5. Every redirect target, merge-group member, `within` target, and HSK row entry id references an
   existing entry.
6. Every merge group has two or more members and two or more distinct alternate forms.
7. `max_headword_len` and `max_word_len` match the data and `max_word_len` ≤ `max_headword_len`
   (`DATA-004`).
8. HSK: Mandarin row count equals the published syllabus total minus the documented de-duplication
   collisions (`DATA-123`; in the reference data 11105 − 14 = 11091); the Cantonese row set equals the
   Mandarin one (`DATA-187`); unlinked (Mandarin) and unmatched (Cantonese) rows at or below the agreed allowance (target zero) and enumerated in the build report;
   no duplicate (level, word, reading).
9. The popular list has 50 ids, all multi-character, all resolvable.
10. Reading round-trip: `reading_canonical` regenerates `reading_display` for every entry, or the entry
    is on an explicit exception list.
11. Golden-query regression: a fixed set of queries produces the expected ordered top-N ids
    (`PIPE-140`).
12. Audio keys: every `audio_word_key` exists in the audio catalogue (`DATA-172`) built in the same
    pipeline run, and every catalogue key resolves to a record in the audio packs.
13. Size budget: the dataset, its language-model companion file and every pack are at or under their
    budgets (`PERF-070`; per-flavour totals `PERF-073`).
14. Language model: every headword has an LM word id and a finite unigram; every `abbr_idx` posting and
    bigram successor references valid ids; each codebook has at most 256 entries; `has_bigram_model`
    matches the presence of bigram data; the quality gate passes (`PIPE-075`).
15. Normalisation: every matching key equals its own `SRCH-010` normalisation (`DATA-071`).

`DATA-201` `[MUST]` The build emits a machine-readable report: counts per table, index sizes, unlinked
HSK rows and Cantonese HSK join decisions, unresolved references, ambiguous redirects,
romanisation-parse exceptions, entries with no senses, language-model size and coverage (`PIPE-076`),
and the diff in each of these against the previous version. A regression beyond declared thresholds
fails the build.

`DATA-202` `[MUST]` The app **validates cheaply at open**: `schema_version` in range, required tables
present, `entry_count` sane. It must not re-validate the whole dataset at start-up.

---

### 14. Delivery and update

`DATA-210` `[MUST]` A dataset, companion file or pack is replaced **atomically**. On mobile they change
only with an app update. On Web a newly fetched file is verified (`DATA-215`) and written completely
before it replaces the cached one; a partially written or partially downloaded file is never opened.

`DATA-211` `[MUST]` Replacing a dataset, or a lazily fetched file becoming available on Web, bumps the
app's data generation (`GLB-044`), invalidating every in-flight request and cached model; replacing a
dataset also invalidates every passage id and entry id.

`DATA-212` `[MUST]` Anything the app persists or links across dataset versions uses the natural key
(`DATA-014`), never `entry_id`. This applies to widget deep links, routes (`GLB-110`) and any future
bookmark feature.

`DATA-213` `[SHOULD]` Datasets are published as whole files, not diffs. At 10 to 16 MB a full
replacement is cheaper to build, test and reason about than a delta format.

`DATA-214` `[SHOULD]` The app supports N and N-1 `schema_version` so a Web deployment can still open
a dataset cached in OPFS by the previous deployment until the new one is fetched.

`DATA-215` `[MUST]` **Delivery.**
- **Mobile**: both datasets, their language-model companion files, all asset packs and the
  non-dataset assets (`DATA-170`) are installed with the app — Google Play: install-time asset packs
  (and the base module); direct APK: bundled inside the APK; iOS: inside the app bundle. There is no
  runtime download of any data on mobile (`PROD-071`).
- **Web**: the dataset for a mode is fetched on first use of that mode; the asset packs (audio, strokes,
  sentences) when first needed; the language-model companion file on the first query that needs it
  (`SRCH-050`, `SRCH-060` — single-word results never wait for it); the handwriting model when
  handwriting is first opened (`HW-001`…). Every fetched file is verified against the SHA-256 in the
  build manifest (`PIPE-150`) or `DATA-155` `lm_file`, discarded on mismatch, and cached (OPFS) for
  later offline use.

---

### Deferred (post-v1) and withdrawn — Data Contract

- `origins` pack (former `DATA-170` row: IDS, components, notes, historical glyphs) — deferred with
  Character origins.
- `DATA-185` — withdrawn: the Cantonese dataset now carries HSK rows (`DATA-187`).

## Data Pipeline Requirements

This part defines what the in-repository dataset pipeline (`pipeline/`, `PIPE-160`) must do to produce
datasets satisfying the Data Contract above. The contract and the shared test vectors in `contract/`
remain the boundary between the pipeline and the app.

---

### 1. Position and principles

`PIPE-001` `[MUST]` The pipeline is an **offline, reproducible build** that lives in the app
repository as its own module (`pipeline/`, JVM) and runs in CI or on a developer machine. It takes
pinned source inputs (`PIPE-161`, `PIPE-162`) and emits versioned dataset artefacts plus a build
report. App code never runs or links pipeline code; the pipeline never depends on app modules. Both
depend only on `contract/`.

`PIPE-002` `[MUST]` **Reproducible**: the same pinned inputs produce byte-identical artefacts (stable
iteration order, no timestamps inside the content hash, sorted table writes).

`PIPE-003` `[MUST]` **Pinned inputs**: every source is either committed with a version note
(`PIPE-161`) or referenced by URL and SHA-256 in the lock file (`PIPE-162`). Sources are never fetched
from a moving URL without a recorded hash.

`PIPE-004` `[MUST]` **Fail loudly**: any validation gate in `DATA-200` failing aborts publication. The
pipeline never emits a "best effort" dataset.

`PIPE-005` `[MUST]` **No app-side fallbacks for pipeline gaps.** If the pipeline cannot resolve
something (an HSK link, a redirect target), it records the ambiguity explicitly in the dataset (a null
target, an unlinked row) and the app has a defined behaviour for it. It must never leave the app to
guess.

---

### 2. Sources

#### 2.1 Mandarin

`PIPE-010` `[MUST]` **CC-CEDICT** (MDBG distribution) is the primary Mandarin source: traditional form,
simplified form, numbered Pinyin, and a `/`-separated list of glosses. Reference build: the 2026-07-04
release, 124760 usable records after filtering, CC BY-SA 4.0.

`PIPE-011` `[MUST]` **FrequencyWords** `zh_cn` (OpenSubtitles 2018 via OPUS) supplies word frequencies.
Reference: 41319 forms, corpus total 81775831, CC BY-SA 4.0.

`PIPE-012` `[MUST]` **HSK 3.0 syllabus vocabulary** (the HSK word list file committed in
`data-sources/`) supplies levels, per-level parts of speech, and syllabus glosses for both datasets.
Reference: 11105 rows across levels 1-7, MIT-licensed extraction.

#### 2.2 Cantonese

`PIPE-013` `[MUST]` **CC-Canto** supplies Cantonese (Jyutping) readings and Cantonese-specific
definitions keyed by (traditional, simplified, Pinyin).

`PIPE-014` `[MUST]` **CC-CEDICT-Canto** (the Cantonese-reading annotation of CC-CEDICT) supplies
Jyutping readings for CC-CEDICT headwords that have no CC-Canto record.

`PIPE-015` `[MUST]` CC-CEDICT itself is a Cantonese source too: its English glosses remain useful for
Cantonese entries (`DATA-182`).

`PIPE-016` `[SHOULD]` A Cantonese frequency list, if one of acceptable quality can be licensed, drives
Cantonese segmentation; otherwise `DATA-114` applies.

#### 2.3 Asset and language-model sources

`PIPE-017` `[MUST]` Example sentences (Tatoeba eng-cmn pairs, converted between scripts with OpenCC at
build time), recorded audio (audio-cmn / Shtooka), stroke data (hanzi-writer-data). Language-model
sources: `PIPE-070`. Full attribution table: `SET-040`.

`PIPE-018` `[MUST]` Licence compatibility is checked at build time and the resolved licence text and
source metadata are emitted into the dataset (`DATA-002`, `SET-042`). GPLv2 and LGPL sources are gated
behind an explicit allow-list decision (`Q-070`); the libime language model (`PIPE-070`) is on that
allow-list.

---

### 3. Mandarin build

`PIPE-020` `[MUST]` Parse each CC-CEDICT record into: traditional, simplified, numbered reading, and
ordered glosses. Records that fail to parse are counted and reported, never silently dropped.

`PIPE-021` `[MUST]` Derive per entry: `reading_display` (tone marks by the placement rule of `GLB-015`,
applied only to syllables that are valid Mandarin, so Latin initials and digits pass through),
`reading_canonical` (lowercased, `u:` and `v` folded to `ü`), `syllables`, `tones`, `is_proper_noun`
(from the source's capitalisation), and `char_count`.

`PIPE-022` `[MUST]` **Fix the toneless-reading bug.** The reference pipeline derived the plain reading
by stripping a trailing digit from every whitespace-separated token, which mangles readings containing
literal digits: entry `11區 / 11 qu1` produced the plain reading `1 qu`, making it unfindable by
pronunciation. Toneless syllables must be derived by removing only a **tone digit in tone position**
from a token that is otherwise a valid syllable; non-syllable tokens are preserved verbatim.

`PIPE-023` `[MUST]` Emit the headword, reading, character, substring and English-token indexes of
`DATA-070` to `DATA-079`, with the folded and case-folded key variants precomputed and every key
normalised as in `DATA-071`, plus the syllable abbreviation table (`DATA-150`).

`PIPE-024` `[MUST]` Tokenise every sense with **exactly** the rule in `SRCH-013` (lowercase, split on
non-alphanumeric, drop trailing `s` when length is greater than 3 and not ending in `ss`). A divergence
between pipeline and app tokenisation silently breaks English search; a shared test vector must pin it
(`PIPE-140`).

`PIPE-025` `[MUST]` Parse every sense's markup into segments (`DATA-050`), classifying each bracketed
reading's romanisation system from prose cues (`DATA-053`). Cue vocabulary observed in the source:
`Tai-lo`, `Jyutping`, `Cantonese`, `Hokkien`, `Teochew`, `Wade-Giles`, `Peh-oe-ji`/`POJ` for
non-Mandarin; `Mandarin`, `Taiwan pr.`, `Beijing pr.`, `also pr.`, `pronounced`, `the reading` for
Mandarin. The **nearest preceding** cue within about 96 characters wins.

`PIPE-026` `[MUST]` Resolve references (`DATA-054`) and redirects (`DATA-055`) using the tiering rules
of `ENT-046`, leaving genuinely ambiguous cases unresolved and reporting them.

`PIPE-027` `[MUST]` Detect redirect prose markers case-insensitively at word boundaries:
`erhua form of`, `erhua variant of`, `variant of` at top level (the whole sense) produce a redirect;
the same markers inside a trailing parenthetical produce an **embedded** variant (`DATA-057`).
Structured note kinds `variant_of` and `old_variant_of` produce a redirect only when the entry has no
senses of its own and carries exactly one such note with exactly one reference.

`PIPE-028` `[MUST]` Compute merge groups per script (`DATA-080`).

`PIPE-029` `[MUST]` Compute `within` relationships (`DATA-101`) for entries within the size bound.

`PIPE-030` `[MUST]` Compute frequency ranks and log-probabilities (`DATA-111`, `DATA-113`), and the
popular-words list (`DATA-090`).

---

### 4. HSK build

`PIPE-040` `[MUST]` Normalise each syllabus row: split `/`-separated alternative readings; normalise
the syllabus reading to canonical numbered form; split the part-of-speech field on the ideographic
comma and de-duplicate.

`PIPE-041` `[MUST]` Join each row to exactly one Mandarin dictionary entry, preferring, in order:
1. an entry matching (simplified, traditional, reading) exactly;
2. an entry matching (simplified, traditional) with a tone-insensitive reading match;
3. an entry matching simplified only with a tone-insensitive reading match;
4. an entry matching simplified only, preferring the lowest frequency rank and a non-proper-noun
   reading.
Record which tier matched, per row, in the build report. The Cantonese join is `DATA-187`.

`PIPE-042` `[MUST]` Tolerate the systematic syllabus-vs-dictionary reading differences: tone sandhi
(`bú` for `bù`, `yí`/`yì` for `yī`), neutral-tone conventions (`nàbiān` vs `nà bian`,
`xuéshēng` vs `xué sheng`), and syllable-joining. About 257 of 11105 rows differ this way (`HSK-052`).

`PIPE-043` `[MUST]` De-duplicate rows per (level, simplified, reading), merging parts of speech
(`DATA-123`). The reference data has 14 collisions.

`PIPE-044` `[MUST]` **Gate on unlinked rows.** The reference dataset shipped 9 unlinked rows; the target
is zero. For each remaining unlinked row, either fix the join or add a `hsk_syllabus`-sourced entry to
the dictionary (`DATA-020`), and report the choice.

`PIPE-045` `[MUST]` Emit the sort keys and store rows in final order (`DATA-121`).

`PIPE-046` `[MUST]` Emit the widget projection (`DATA-130`) for each dataset.

---

### 5. Cantonese build

`PIPE-050` `[MUST]` Build the Cantonese headword space as the **union** of CC-Canto headwords,
CC-CEDICT-Canto annotated headwords, and CC-CEDICT headwords that acquire a Cantonese reading from
either.

`PIPE-051` `[MUST]` The entry key is (traditional, simplified, Jyutping reading). One Chinese word with
several Cantonese readings yields several entries (`DATA-184`).

`PIPE-052` `[MUST]` Sense assembly follows `DATA-182` and `DATA-183`: Cantonese-source senses first,
then CC-CEDICT senses for the same headword, de-duplicated by normalised text.

`PIPE-053` `[MUST]` Where CC-CEDICT has several Mandarin entries for a headword, the Mandarin senses
carried onto a Cantonese entry are those of the Mandarin entry whose meaning best corresponds. When the
correspondence is not determinable, carry the senses of **all** Mandarin entries for that headword, in
frequency order, rather than picking arbitrarily. Record the count of such cases.
*(Semantic-alignment quality is `Q-082`.)*

`PIPE-054` `[MUST]` Do **not** assume the reference app's joining logic is correct — it has none
(Cantonese does not exist in the reference app). This join is designed fresh from `DATA-180` to
`DATA-187`.

`PIPE-055` `[MUST]` Emit the Jyutping syllable inventory, alias map and abbreviation table as data
(`DATA-070`, `DATA-150`), validated by parsing every entry's reading against them.

`PIPE-056` `[MUST]` Emit `has_hsk = true` and the HSK tables built by the join of `DATA-187` (OpenCC
`s2t`/`s2hk` mapping by written form, Jyutping from CC-Canto via the linked entry), reusing the
normalised, de-duplicated Mandarin rows (`PIPE-040`, `PIPE-043`) and applying `PIPE-045` and
`PIPE-046`; report and gate unmatched words (`DATA-200` gate 8).

---

### 6. Asset pack build

`PIPE-060` `[MUST]` Each pack is built independently, declares the `dataset_version` it targets, and
emits a key-to-record index (`DATA-171`).

`PIPE-061` `[MUST]` **Audio catalogue.** Build the word-key and syllable-key catalogues (`DATA-172`)
in the same run as the datasets. Word keys are canonical numbered readings (`MED-005`). A recording
that cannot be attributed to exactly one reading is **excluded** (`MED-012`), and the exclusion list is
emitted for review. A manual override file, hash-bound to the source audio, may resolve reviewed cases.

`PIPE-062` `[MUST]` Audio generation never modifies source recordings.

`PIPE-063` `[MUST]` **Strokes.** Convert the source stroke records into the compact geometry format,
validating that each character has at least one stroke path and reporting characters whose medians are
unusable (they must still ship their outlines — `MED-070`).

`PIPE-065` `[MUST]` **Sentences.** Build the word index and sentence store; convert scripts with OpenCC
at build time; cap per-word sentence lists; apply the ranking of `MED-047` if adopted.

`PIPE-066` `[MUST]` The legacy Hanzi-keyed audio directory (a generator input in the reference build)
must never be packaged. Any build-only input must be excluded from shipping artefacts by an explicit
allow-list, not by a filename-pattern deny-list.

---

### 7. Language-model build

`PIPE-070` `[MUST]` **Sources** (pinned in the lock file, `PIPE-162`; credited in `SET-040`):
- Mandarin: the pre-trained libime model `lm_sc.arpa` (LGPL-2.1-or-later; distributed as a zstd archive
  of about 78 MB; 270714 unigrams and 4789953 bigrams in the reference release).
- Cantonese: built at pipeline time (`PIPE-074`) from the rime-cantonese essay corpus (CC BY 4.0),
  HKCanCor (CC BY 4.0), the zh-yue Wikipedia dump (CC BY-SA 4.0) and Tatoeba `yue` sentences
  (CC BY 2.0 FR). Sources of unclear licence (for example words.hk) are not used.
The resolved source metadata is written to `lm_source` (`DATA-155`).

`PIPE-071` `[MUST]` **ARPA import.** Stream-parse the ARPA file (`\data\`, `\1-grams:`, `\2-grams:`
sections), convert log10 values to natural log, keep unigram log-probabilities and backoff weights and
bigram log-probabilities, and ignore higher orders. Malformed lines fail the build.

`PIPE-072` `[MUST]` **Vocabulary mapping.** Map LM tokens to dictionary headword forms after
`DATA-071` normalisation: Mandarin by simplified form; Cantonese by traditional form, converting
simplified-script corpus text with OpenCC `s2hk` first. Assign dense word ids in descending unigram
order (`DATA-152`). LM tokens that are not headwords contribute only to the unknown-word mass; headwords
absent from the LM receive a smoothed unigram (from their characters' unigrams with a per-word
out-of-vocabulary penalty), never minus infinity. The smoothing method and its parameters are recorded
in the build report.

`PIPE-073` `[MUST]` **Pruning and quantisation.** Keep only bigrams whose both words are dictionary
headwords; drop bigrams whose probability is within a configured δ of the backoff estimate; cap the
successors per left word; target about 300000 to 500000 bigrams per dataset. Build the codebooks
(`DATA-154`) from the surviving values (for example by quantile or k-means) and emit the bigram blocks
(`DATA-153`), the first-letter index (`DATA-151`) and the per-entry word ids and unigram codes
(`DATA-152`). All thresholds are pipeline configuration, recorded in the build report.

`PIPE-074` `[MUST]` **Cantonese LM.** Normalise the Cantonese corpora (NFKC, OpenCC `s2hk` for
simplified text, markup stripped), segment them with the Cantonese headword list (`TXT-012`-style
maximum likelihood over the dictionary), and train a bigram model with KenLM `lmplz` at build time
(build-only tool, never shipped), then import it via `PIPE-071`. If the Cantonese model fails the
quality gate (`PIPE-075`), the pipeline may fall back to Mandarin bigrams for shared written forms
(converted with OpenCC), flagging the fallback in `lm_source`.

`PIPE-075` `[MUST]` **Quality gate.** Each dataset's language model is evaluated against a golden set
in `contract/`, and publication fails if it does not pass. Mandarin required cases: `nh` → 你好,
`zg` → 中国, `wxhn` → 我喜欢你 (as the top combination), each at rank 1. Cantonese cases (for example
`nd` → 我哋, `gwdw` → 廣東話, `ngd` → 我哋). Each set also asserts a top-3 hit rate at or above a declared
threshold over a broader query list.

`PIPE-076` `[MUST]` **Size and coverage report.** For each dataset report: companion-file size and
the sizes of `abbr_idx` and `bigram`; bigram counts before and after pruning; key and posting counts
of the first-letter index and capped keys; the share of headwords with an observed (not smoothed)
unigram; and the diff against the previous build. Sizes are checked against `PERF-070`.

---

### 8. Transformations moved out of the app

`PIPE-100` `[MUST]` The following are performed at runtime by the reference app and **must** move into
the pipeline:

| # | Runtime work in the reference app | Moves to |
|---|-----------------------------------|----------|
| 1 | Deriving toneless syllables and tone arrays from numbered readings, per entry, per query | `DATA-010` |
| 2 | Converting numbered readings to tone marks for display | `DATA-010` |
| 3 | Parsing definition prose into text/reading/reference segments, per render | `DATA-050` |
| 4 | Scanning prose to decide whether a bracketed reading is Mandarin | `DATA-053` |
| 5 | Resolving `variant of` / `erhua` redirects by extra lookups per entry batch | `DATA-055` |
| 6 | Stripping embedded "(variant of X)" clauses by re-parsing definitions | `DATA-057` |
| 7 | Tokenising English definitions (`englishTokens`) for phrase checks | `DATA-020`, `DATA-077` |
| 8 | NFKC/middle-dot folding of headwords | `DATA-071` |
| 9 | Enumerating up to 256 ASCII case permutations per query | `DATA-071`, `DATA-072` |
| 10 | Computing variant merge groups by re-looking-up headwords per candidate | `DATA-080` |
| 11 | Enumerating every substring of a word and querying each to find sub-spans | `DATA-101` |
| 12 | Enumerating every substring of a passage run for segmentation | `DATA-112` batching |
| 13 | Computing HSK part-of-speech aggregation per level, per entry open | `DATA-126` |
| 14 | Deriving HSK pronunciation sort keys for 11105 rows at load | `DATA-121` |
| 15 | Translating HSK part-of-speech tokens with a hard-coded table | `DATA-122` |
| 16 | JSON-parsing definitions and notes per hydrated entry and per HSK row | `DATA-143` |
| 17 | Deriving frequency ranks and popular words | `DATA-090`, `DATA-111` |
| 18 | Computing `ln(freq) - ln(total)` per segmentation candidate | `DATA-113` |
| 19 | Hard-coded Mandarin syllable inventory and alias map in app code | `DATA-070` |
| 20 | Deriving audio keys and availability per entry render | `DATA-010`, `DATA-172` |

`PIPE-101` `[MUST]` What legitimately **stays** in the app: query normalisation and classification (it
depends on live input), romanisation parsing and abbreviation spelling driven by the shipped tables,
candidate ranking (it depends on the query's match classes), lattice decoding over the shipped
language-model data (`TXT-026`), segmentation search over shipped frequencies, and rendering.

---

### 9. Golden regression suite

`PIPE-140` `[MUST]` The pipeline ships a golden-query suite executed against each candidate dataset,
asserting exact ordered top-N entry ids. It must cover at least:

- partial pronunciation (`x`, `shi`, `zhongguore`) — including the invariant that ranking precedes
  limiting (`SRCH-071`): a limit-first implementation dropped 1029 of the correctly-ranked top 1200 for
  `x` and 1159 for `to` on the reference dataset;
- fully-toned and toneless readings (`ni3hao3`, `nihao`);
- abbreviated and mixed pinyin (`SRCH-050`…): `nh`, `nih`, `nhao` and `nihao` all reaching 你好, with
  fuller spellings outranking abbreviations; `zhg` and `zg` reaching 中国; Jyutping `ngd` and `nd`
  reaching 我哋, `gwdw` reaching 廣東話;
- multi-word combinations (`SRCH-060`…): `wxhn` and `woxihuanni` producing 我喜欢你;
- initialism readings (`Txu`, `T xu`, `t xu4`);
- multi-token English (`graduate student`), single-token English (`to`), abbreviation tokens (`HSK`);
- mixed patterns (`我xiang qu`, `da y`) and unparseable mixed (`我qwrt`);
- Hanzi exact with several readings (`了` — 4 exact, 27 starts-with, 148 contains in the reference data);
- sentence segmentation (`我们研究生命科学` under both DAG and greedy strategies);
- variant merges (`fa1` in both scripts; the 无/無 family; the 贊/讚 spelling family);
- redirects (`一点儿` resolving to `一点`; `B格` resolving to `逼格`; an ambiguous redirect staying
  unresolved);
- case-insensitive Latin headwords (`aa`, `k`, `x`);
- folding (`Ｔ恤` matching `t恤`; middle-dot variants of `马丁·路德·金`);
- HSK per-level parts of speech (`点` is a measure word at level 1 and a verb at level 2);
- HSK in the Cantonese dataset (你好 at level 1 with Jyutping `nei5 hou2`);
- every previously unlinked HSK row now resolving.

`PIPE-141` `[MUST]` The suite is shared with the app's test suite as data in `contract/`, so both
sides assert the same expectations against the same dataset.

`PIPE-142` `[MUST]` The suite runs on **every** dataset build, not only on pipeline changes — a source
refresh can change ranking.

---

### 10. Publication

`PIPE-150` `[MUST]` Artefacts per build: one dataset file per mode, one language-model companion file
per mode, one file per asset pack, a machine-readable build report (`DATA-201`), and a manifest listing
every artefact with its size, SHA-256 and target `dataset_version`. The manifest also lists the
non-dataset assets (`DATA-170`) with their own versions and SHA-256 from the lock file.

`PIPE-151` `[MUST]` The manifest is consumed by the app build, which packages every artefact for each
flavour (`DATA-215`), and by the Web loader, which verifies lazily fetched files against it. It
includes the minimum supported `schema_version` for each artefact.

`PIPE-152` `[SHOULD]` Data changes reach a release only through a reviewed pull request that carries
the build-report diff against the previous dataset (`DATA-201`, `PIPE-165`).

---

### 11. Repository layout and CI

`PIPE-160` `[MUST]` **Single repository** (user decision). The app and the dataset pipeline live in
one repository:
- `app/` — the Kotlin Multiplatform app modules;
- `pipeline/` — the dataset builder (JVM), run in CI and locally;
- `data-sources/` — committed, pinned inputs (`PIPE-161`);
- `contract/` — the data-contract schema and the shared test vectors (normalisation, tokenisation,
  golden queries, LM quality set) used by both the pipeline and the app.
The data contract remains the module boundary: `app/` and `pipeline/` each depend on `contract/` and
never on each other.

`PIPE-161` `[MUST]` **Committed inputs.** `data-sources/` holds the small, redistributable inputs:
CC-CEDICT, CC-Canto, CC-CEDICT-Canto, the HSK word list file, the frequency lists, and the syllable,
alias and abbreviation tables. Each source has a note giving its origin URL, version or release date,
retrieval date and licence, and every file is REUSE-annotated.

`PIPE-162` `[MUST]` **Large third-party inputs are never committed.** The libime ARPA archive (about
78 MB zst), the Cantonese corpora, and the voice and handwriting models (`DATA-170`) are pinned in a
lock file (URL plus SHA-256, with version and licence) and fetched and cached by CI. A hash mismatch
fails the build.

`PIPE-163` `[MUST]` **CI regenerates the dataset.** CI computes an input hash over `data-sources/`,
`pipeline/`, `contract/` and the lock file. When the hash changes, CI rebuilds every dataset and pack;
otherwise it reuses the cached artefacts keyed by that hash. In both cases it runs the validation gates
(`DATA-200`), the golden queries (`PIPE-140`), the language-model quality gate (`PIPE-075`) and the
size check (`PERF-070`, `PERF-073`) before the app build consumes the artefacts.

`PIPE-164` `[MUST]` **No dataset files are committed.** Dataset artefacts exist only as CI build
outputs and caches; the app build fails if the artefacts for its input hash are absent rather than
falling back to a stale copy.

`PIPE-165` `[MUST]` **Scheduled source updates.** A scheduled workflow (weekly) fetches the latest
CC-CEDICT, CC-Canto and CC-CEDICT-Canto releases, updates `data-sources/` and their version notes, runs
the pipeline, and opens a pull request whose description carries the build-report diff (entry counts,
changed headwords, golden-query changes, gate results). No pull request is opened when nothing
changed. Merging it is what ships updated data in the next release.

`PIPE-166` `[MUST]` **Dataset version.** `dataset_version` = the release date of the newest pinned
dictionary source (`YYYY.MM.DD`) + the short hash of the last commit that changed the pipeline inputs
(`PIPE-163`), for example `2026.07.04+3f2a91c`. Because sources, lock file and pipeline are all in the
repository, that commit identifies every input; the individual source versions are recorded in
`sources` and `lm_source`, and the commit in `pipeline_commit` (`DATA-002`). The version is shown in
Settings and the attributions (`SET-042`).

---

### Deferred (post-v1) — Data Pipeline

- `PIPE-064` — Origins: re-encoding historical glyph images (deferred with Character origins, as are
  the origin sources Make Me a Hanzi decompositions, CJKVI IDS, chinese-lexicon, the Xiaoxuetang
  mirror and Wikimedia glyph images).
