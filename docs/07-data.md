# 07 — Data Contract and Pipeline

## Data Contract (processed dictionary dataset)

This document defines **exactly what the application receives**. It is written so that the data
pipeline and the app can be implemented independently: the pipeline satisfies this contract, the app
assumes only this contract.

Section 2 (logical model) is `[MUST]`. Section 8 (physical encoding) is `[REC]` — a concrete,
evidence-based recommendation that may be replaced if the logical contract and the performance budgets
in [13 — Performance](08-engineering.md) are still met.

Sizing figures quoted throughout are measured from the reference Mandarin dataset: 124760 entries,
197616 distinct headwords, 48800 distinct English tokens, 709027 unique (token, entry) postings, 14178
distinct characters, 427396 character postings, 11105 HSK rows, 41319 frequency rows, and 5.4 MB of
definition text.

---

### 1. Packaging and identity

`DATA-001` `[MUST]` A **dataset** is a self-contained, read-only, immutable bundle serving exactly one
dictionary mode. Two datasets ship or download: `mandarin` and `cantonese`. **Both use the identical
schema.** The app selects a dataset by mode and needs no mode-specific code paths.

`DATA-002` `[MUST]` A dataset declares, in a metadata table/section:

| Field | Type | Example |
|-------|------|---------|
| `schema_version` | int | `1` |
| `dataset_id` | string | `mandarin` / `cantonese` |
| `dataset_version` | string | `2026.08.24+1` |
| `built_at` | ISO-8601 UTC | `2026-08-24T09:12:03Z` |
| `content_hash` | hex | sha256 of the canonical content |
| `language` | BCP-47 | `zh-Hans` / `yue-Hant` |
| `romanization` | enum | `pinyin` / `jyutping` |
| `tone_count` | int | `5` / `6` |
| `entry_count` | int | `124760` |
| `max_headword_len` | int | code points, e.g. `19` |
| `max_word_len` | int | longest word considered by the segmenter, e.g. `12` |
| `total_frequency` | int64 | corpus total for log-probabilities |
| `has_frequency_data` | bool | selects DAG vs greedy segmentation (`TXT-012`) |
| `has_hsk` | bool | `true` for Mandarin, `false` for Cantonese |
| `sources` | list | per-source name, version/commit, date, licence, URL (`SET-042`) |
| `optional_packs` | list | declared companion packs and their versions (`DATA-170`) |

`DATA-003` `[MUST]` The app verifies `schema_version` against the range it supports **before** any
query. A mismatch is a hard, user-visible error naming both versions. It must never attempt a partial
read.

`DATA-004` `[MUST]` `max_word_len` is the **contract** for segmentation and sub-span enumeration
window sizes. The app must read it, never assume it.

`DATA-005` `[MUST]` The dataset is opened read-only and is never mutated by the app. Any per-user state
(settings, widget masks) lives in separate app-owned storage.

`DATA-006` `[MUST]` `content_hash` covers the content, not the file bytes, so the same logical dataset
built twice validates identically. Companion packs reference the `dataset_version` they were built
against and are rejected on mismatch.

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

`DATA-011` `[MUST]` `syllables` and `tones` have equal length. When `char_count` equals the syllable
count the entry is tone-colourable (`GLB-066`); the app must not assume it is.

`DATA-012` `[MUST]` Every entry has both script forms populated. Where the source gives one form only,
both fields carry it.

`DATA-013` `[MUST]` `id` is stable within a `dataset_version` and is used as the widget's deep-link
key and as the final ranking tiebreak. It is **not** stable across dataset versions; anything persisted
across versions must key on the natural key instead (`DATA-014`).

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
(nullable display form), `extra` (nullable free text), and a `display_label` for the kind.

`DATA-061` `[MUST]` A note consumed by a resolved redirect is **removed** by the pipeline, not filtered
at runtime (`ENT-048`).

`DATA-062` `[MUST]` Reference note counts in the Mandarin source, for validation: `see` 4222,
`variant-of` 2553, `classifier` 1578, `old-variant-of` 728, `taiwan_pr` 545, `abbr_for` 318,
`also_written` 224, `also_pr` 178, `see_also` 109 — 10219 entries carry at least one note.

---

### 3. Search indexes

`DATA-070` `[MUST]` **Syllable inventory.** The dataset carries the complete toneless syllable
inventory for its romanisation, its alias map, and the maximum syllable length. The app's romanisation
parser is driven entirely by this table and contains no hard-coded language knowledge (`GLB-010`).
Mandarin: about 400 syllables. Jyutping: about 620.

`DATA-071` `[MUST]` **Headword index.** For each entry, keys for: the simplified form, the traditional
form (when different), the NFKC-plus-middle-dot **folded** form of each (when different), and the
ASCII-lowercased form of each (when different). One index answering exact match and prefix range over
all of them, keyed by (key, entry id).
Reference size: 202396 (key, entry) pairs over 197616 distinct keys.

`DATA-072` `[MUST]` **Case handling is baked in.** The app must not enumerate ASCII case permutations
at query time (the reference app generated up to 256 variants per query — finding `F-020`). A single
case-folded key column plus the original key is sufficient.

`DATA-073` `[MUST]` **Reading index.** Keyed by the space-joined toneless syllable string, supporting
exact match and prefix range, with the tone array available for tone filtering without a second read.

`DATA-074` `[MUST]` **Substring (CONTAINS) index.** An index that answers "headwords containing X at
position 1 or later" **without scanning**. Contract: given a query of one or more code points, return
candidate entry ids in bounded time.
Recommended construction (`[REC]`): index every substring of length 1 to 4 starting at position 1 or
later of every headword; for longer queries, filter by the query's first 4 code points and verify the
full match against the candidates' headwords. Measured on the reference data: 589211 (substring, entry)
pairs over 164307 distinct keys for length at most 4 — small enough to ship, and it removes a
full-index scan that cost about 6 ms per call on a desktop and much more on a phone (finding `F-022`).

`DATA-075` `[MUST]` **Character index.** For each distinct Hanzi in either script form of an entry, a
posting list of entry ids ordered by (frequency rank, id). Reference: 14178 keys, 427396 postings. Used
by mixed-pattern search (`SRCH-039`) and by the "appears in" fallback (`TXT-022`).

`DATA-076` `[MUST]` **English token index.** Keyed by token (per `SRCH-013`), with postings of
(entry id, sense index) so single-sense matching (`SRCH-047`) needs no extra read. Must support exact
key lookup and prefix range (for last-token prefix matching). Reference: 48800 distinct tokens, 709027
unique (token, entry) pairs, 752642 (token, entry, sense) rows.

`DATA-077` `[MUST]` Each sense's token list is also stored **per sense** so contiguous-phrase detection
(`EN_PHRASE`) is a local check on the candidate, not a re-tokenisation of its text.

`DATA-078` `[MUST]` **Ranking projection.** For every entry, the five ranking inputs — match class
(computed at query time), frequency rank, character count, proper-noun flag, and id — must be readable
**without** loading definitions. Ranking a 30000-candidate set must not hydrate 30000 full entries.

`DATA-079` `[MUST]` Every posting list is stored in ascending entry-id order so intersections are
linear merges.

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

`DATA-101` `[MUST]` `within` must be **precomputed per entry** for multi-character entries: an ordered
list of (start, length, entry id) sorted longest-first, then start ascending, then target frequency
rank, then target id. This is the single largest runtime cost removed: the reference implementation
enumerates every substring of the word and issues a lookup per substring, on every entry open and every
search group.

`DATA-102` `[SHOULD]` Bound `within` to entries of at most 8 code points; for longer idioms compute it
on demand from the batched headword index. The measured distribution supports this: about 95% of the
197616 distinct headwords are at most 4 code points, and only 1221 exceed 8.

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
(dense rank, 1-based), with the sentinel `1000000` where absent. The app never computes it.

`DATA-112` `[MUST]` For segmentation the app needs, per candidate substring, "is this a dictionary
word" and "what is its frequency". Both must be answerable in one batched lookup per run
(`TXT-014`), so the frequency value should be co-located with the headword index or reachable by the
same key.

`DATA-113` `[SHOULD]` Precompute the natural log-probability per headword so the segmenter performs no
logarithms at runtime.

`DATA-114` `[MUST]` For Cantonese, if no Cantonese frequency corpus is available, the pipeline must
either (a) reuse Mandarin frequencies for shared surface forms and set `has_frequency_data` true, or
(b) omit frequencies and set it false, in which case the app uses greedy longest-match (`TXT-013`). The
choice must be recorded in metadata. *(Decision needed — `Q-081`.)*

---

### 7. HSK data

`DATA-120` `[MUST]` An **HSK row table**, present only when `has_hsk`, already **ordered** by
(level, pronunciation sort key) so the app reads it sequentially:

| Field | Type | Notes |
|-------|------|-------|
| `row_id` | int32 | stable unique key for list identity (`HSK-026`) |
| `level` | int8 | 1 to 7; 7 means the 7-9 band |
| `simplified` | string | |
| `traditional` | string | |
| `syllabus_reading_display` | string | the syllabus's own (sandhi) form |
| `syllabus_reading_canonical` | string | |
| `parts_of_speech` | string[] | source tokens for this level, de-duplicated |
| `syllabus_senses` | string[] | the syllabus gloss |
| `entry_id` | int32 (nullable) | resolved canonical entry; null when unlinked |
| `sort_key` | string | precomputed pronunciation sort key (`HSK-030`) |

`DATA-121` `[MUST]` `sort_key` is the normalised canonical reading of the linked entry when linked,
else of the syllabus row (`HSK-031`). Rows are stored in (level, sort_key, simplified, row_id) order.

`DATA-122` `[MUST]` A **part-of-speech label table** mapping source token to English label
(`HSK-044`), so new tokens do not need an app release.

`DATA-123` `[MUST]` The rows are already **de-duplicated per (level, word, reading)** with parts of
speech merged (`HSK-027`). The reference data contains 14 such collisions.

`DATA-124` `[MUST]` Unlinked rows must be **reported and gated** by the pipeline (`HSK-056`); the
reference dataset shipped 9.

`DATA-125` `[MUST]` The entry's `hsk_levels` is derived from these rows, ascending and de-duplicated.

`DATA-126` `[MUST]` Per-entry, per-level parts of speech must be readable **with the entry** (one read),
not by a second query at entry-open time.

`DATA-130` `[MUST]` A separate, tiny **widget projection** (`SET-066`) carrying only what the
word-of-the-day widget renders: entry id, natural key, levels, both scripts, both readings, header
notes, senses, notes. Reference size about 0.55 MB compressed for 11k words. The widget must never open
the main dataset.

---

### 8. Physical encoding — recommendation

`DATA-140` `[REC]` **Primary recommendation: one SQLite file per dataset**, opened read-only, with the
following departures from the reference schema. SQLite is chosen because it is native on Android and
iOS, works on the Web via a WASM build over OPFS, gives cancellable queries, needs no full-file load,
and supports incremental page-level access.

`DATA-141` `[REC]` Tables (column lists abbreviated):

- `meta(key TEXT PRIMARY KEY, value TEXT)` — `DATA-002`
- `entry(id INTEGER PRIMARY KEY, trad TEXT, simp TEXT, reading_display TEXT, reading_canonical TEXT,
  syllables BLOB, tones BLOB, flags INTEGER, freq_rank INTEGER, char_count INTEGER, natural_key BLOB,
  merge_simp INTEGER, merge_trad INTEGER, hsk_levels BLOB, audio_key TEXT, senses BLOB, notes BLOB,
  redirect BLOB)`
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
- `popular(rank INTEGER PRIMARY KEY, entry_id INTEGER)` — `DATA-090`

`DATA-142` `[REC]` Indexes: exactly the ones the queries need, and **no reverse indexes**. Required:
`headword(key, entry_id)`, `reading(key, entry_id)`, `hsk_row(level, sort_key, row_id)`,
`entry(freq_rank, id)`. The reference schema carried a headword-by-entry index (8 MB) and an
english-token-by-entry index (13.5 MB) that no query used — 21.5 MB of pure waste (finding `F-023`).

`DATA-143` `[REC]` `senses`, `notes`, `redirect`, `within`, and all posting lists are **length-prefixed
binary blobs**, not JSON:
- posting lists: delta-encoded ascending entry ids as unsigned varints (ids reach 124760, so deltas
  average one to two bytes);
- `within` rows: varint triples of start, length, and entry-id delta;
- senses: a varint count, then per sense a varint source enum, a varint segment count, and per segment
  a tag plus length-prefixed UTF-8 fields;
- the app decodes a blob only for entries it actually renders.

This replaces the per-row JSON parse that the reference app performs on every hydrated entry and every
HSK row.

`DATA-144` `[REC]` Store definition text with **per-blob deflate or zstd** when a blob exceeds about
200 bytes, or use SQLite page compression. The reference definition text is 5.4 MB raw; general-purpose
compression of the whole source bundle reaches 6.3 MB for everything, so per-blob compression should
recover most of that while keeping random access.

`DATA-145` `[REC]` Set the page size to 4096, run `VACUUM` and `ANALYZE` at build time, ship with WAL
disabled (read-only), and set the file's user-version to `schema_version`.

`DATA-146` `[REC]` **Do not copy the dataset out of the app bundle at first run.** The reference app
ships a 90 MB SQLite asset **and** copies it to the app's database directory, so the installed footprint
is about 180 MB (finding `F-010`). Either read the asset in place (via a platform file descriptor or a
read-only VFS) or download the dataset once into app storage and never bundle it twice. The raw 6.3 MB
gzipped JSON source was **also** shipped in the reference package and must not be.

`DATA-147` `[REC]` Expected size for the Mandarin dataset under this encoding: **10 to 16 MB**
installed, versus 90 MB (180 MB installed) today. Budget: `PERF-060`.

`DATA-148` `[REC]` Acceptable alternative physical designs, if the budgets are met: a custom
memory-mappable binary format with FST or trie key indexes (smaller and faster, more work, harder to
evolve); or SQLite plus FTS5 for the English index (simpler prefix search, larger). A pure in-memory
index is **not** acceptable: the reference app's earlier in-memory build failed with a 33.5 MB
contiguous-allocation error and retained hundreds of MB (finding `F-011`).

---

### 9. Popular words

`DATA-090` `[MUST]` A precomputed ordered list of 50 entry ids for the blank-query state: the most
frequent **multi-character** words (`SRCH-111`). It must not require a frequency-sorted scan at runtime,
and it must be resolved to ids at build time rather than to surface forms resolved at open time.

---

### 10. Companion asset packs

`DATA-170` `[MUST]` Optional asset families are separate, independently versioned packs, each declaring
the `dataset_version` it targets:

| Pack | Contents | Reference size | Access key |
|------|----------|----------------|------------|
| `audio-syllables` | per-syllable recordings | about 13 MB, 1707 clips | canonical syllable (`MED-007`) |
| `audio-words` | full-word recordings | about 52 MB, 6657 clips | canonical word key (`MED-005`) |
| `strokes` | outlines, medians, radical indices | about 30 MB | code point |
| `origins` | IDS, components, notes, historical glyphs | about 22 MB | code point |
| `sentences` | example pairs plus a word index | about 5.5 MB | simplified headword |

`DATA-171` `[MUST]` Each pack declares an index that maps its access key to a record location, so
reading one record costs one seek plus one decode. The reference packs are 256 whole-JSON shards that
must be fully parsed to read one character (finding `F-071`).

`DATA-172` `[MUST]` An **audio catalogue** lists the available word keys and syllable keys so
availability can be answered without touching the audio files.

`DATA-173` `[MUST]` A missing pack is a normal state (`GLB-035`).

---

### 11. Cantonese specifics

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

`DATA-185` `[MUST]` `has_hsk` is false and the HSK tables are absent (not empty-but-present).

`DATA-186` `[MUST]` Cantonese entries carry a null `audio_word_key` unless a Cantonese audio pack
exists. The reference audio corpus is Mandarin-only.

---

### 12. Validation requirements

`DATA-200` `[MUST]` A dataset that fails **any** of these gates must not be published:

1. `schema_version`, `dataset_version`, `content_hash`, and every metadata field present and well-formed.
2. `entry_count` matches the actual row count.
3. Every entry: both script forms non-empty; `reading_canonical` parses fully against the declared
   syllable inventory (report and cap exceptions such as Latin-initial readings); syllable and tone
   arrays of equal length.
4. Every posting list is strictly ascending and references existing entry ids.
5. Every redirect target, merge-group member, `within` target, and HSK row entry id references an
   existing entry.
6. Every merge group has two or more members and two or more distinct alternate forms.
7. `max_headword_len` and `max_word_len` match the data.
8. HSK: row count matches the published syllabus totals; unlinked rows at or below the agreed allowance
   (target zero) and enumerated in the build report; no duplicate (level, simplified, reading).
9. The popular list has 50 ids, all multi-character, all resolvable.
10. Reading round-trip: `reading_canonical` regenerates `reading_display` for every entry, or the entry
    is on an explicit exception list.
11. Golden-query regression: a fixed set of queries produces the expected ordered top-N ids
    (`PIPE-140`).
12. No dangling asset-pack keys: every `audio_word_key` exists in the audio catalogue.
13. Size budget: the dataset is at or under its declared budget (`PERF-060`).

`DATA-201` `[MUST]` The build emits a machine-readable report: counts per table, index sizes, unlinked
HSK rows, unresolved references, ambiguous redirects, romanisation-parse exceptions, entries with no
senses, and the diff in each of these against the previous version. A regression beyond declared
thresholds fails the build.

`DATA-202` `[MUST]` The app **validates cheaply at open**: `schema_version` in range, required tables
present, `entry_count` sane. It must not re-validate the whole dataset at start-up.

---

### 13. Update strategy

`DATA-210` `[MUST]` A dataset is replaced **atomically**. A partially written or partially downloaded
dataset is never opened.

`DATA-211` `[MUST]` Replacing a dataset bumps the app's data generation (`GLB-044`), invalidating every
in-flight request, cached model, passage id and entry id.

`DATA-212` `[MUST]` Anything the app persists across dataset versions uses the natural key
(`DATA-014`), never `entry_id`. This applies to widget deep links and to any future bookmark feature.

`DATA-213` `[SHOULD]` Datasets are published as whole files, not diffs. At 10 to 16 MB a full
replacement is cheaper to build, test and reason about than a delta format.

`DATA-214` `[SHOULD]` The app supports N and N-1 `schema_version` so a dataset update and an app update
can roll out independently.

`DATA-215` `[MUST]` The bundled/base dataset for at least one mode must work with no network at all
(`PROD-071`). Downloaded packs are additive and independently droppable to reclaim space.

## Data Pipeline Requirements

**Not implemented as part of this specification work.** This document defines what an independent
offline build must do to produce datasets satisfying [10 — Data Contract](07-data.md).

---

### 1. Position and principles

`PIPE-001` `[MUST]` The pipeline is an **offline, reproducible, out-of-app build**. It runs on CI or a
developer machine, takes pinned source inputs, and emits versioned dataset artefacts plus a build
report. The app is never a party to it.

`PIPE-002` `[MUST]` **Reproducible**: the same pinned inputs produce byte-identical artefacts (stable
iteration order, no timestamps inside the content hash, sorted table writes).

`PIPE-003` `[MUST]` **Pinned inputs**: every source is referenced by version, commit, or a content
hash of the downloaded file. Sources are never fetched implicitly at build time from a moving URL
without recording the resolved hash.

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

`PIPE-012` `[MUST]` **HSK 3.0 syllabus vocabulary** supplies levels, per-level parts of speech, and
syllabus glosses. Reference: 11105 rows across levels 1-7, MIT-licensed extraction.

#### 2.2 Cantonese

`PIPE-013` `[MUST]` **CC-Canto** supplies Cantonese (Jyutping) readings and Cantonese-specific
definitions keyed by (traditional, simplified, Pinyin).

`PIPE-014` `[MUST]` **CC-CEDICT-Canto** (the Cantonese-reading annotation of CC-CEDICT) supplies
Jyutping readings for CC-CEDICT headwords that have no CC-Canto record.

`PIPE-015` `[MUST]` CC-CEDICT itself is a Cantonese source too: its English glosses remain useful for
Cantonese entries (`DATA-182`).

`PIPE-016` `[SHOULD]` A Cantonese frequency list, if one of acceptable quality can be licensed, drives
Cantonese segmentation; otherwise `DATA-114` applies.

#### 2.3 Asset sources

`PIPE-017` `[MUST]` Example sentences (Tatoeba eng-cmn pairs, converted between scripts with OpenCC at
build time), recorded audio (audio-cmn / Shtooka), stroke data (hanzi-writer-data), character origins
(Make Me a Hanzi, CJKVI IDS, chinese-lexicon), historical glyph images (Xiaoxuetang mirror, Wikimedia).
Full attribution table: `SET-040`.

`PIPE-018` `[MUST]` Licence compatibility is checked at build time and the resolved licence text and
source metadata are emitted into the dataset (`DATA-002`, `SET-042`). GPLv2 and LGPL sources are gated
behind an explicit allow-list decision (`Q-070`).

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
pronunciation (finding `F-024`). Toneless syllables must be derived by removing only a **tone digit in
tone position** from a token that is otherwise a valid syllable; non-syllable tokens are preserved
verbatim.

`PIPE-023` `[MUST]` Emit the headword, reading, character, substring and English-token indexes of
`DATA-070` to `DATA-079`, with the folded and case-folded key variants precomputed.

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
Structured note kinds `variant-of` and `old-variant-of` produce a redirect only when the entry has no
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

`PIPE-041` `[MUST]` Join each row to exactly one dictionary entry, preferring, in order:
1. an entry matching (simplified, traditional, reading) exactly;
2. an entry matching (simplified, traditional) with a tone-insensitive reading match;
3. an entry matching simplified only with a tone-insensitive reading match;
4. an entry matching simplified only, preferring the lowest frequency rank and a non-proper-noun
   reading.
Record which tier matched, per row, in the build report.

`PIPE-042` `[MUST]` Tolerate the systematic syllabus-vs-dictionary reading differences: tone sandhi
(`bú` for `bù`, `yí`/`yì` for `yī`), neutral-tone conventions (`nàbiān` vs `nà bian`,
`xuéshēng` vs `xué sheng`), and syllable-joining. About 257 of 11105 rows differ this way (`HSK-052`).

`PIPE-043` `[MUST]` De-duplicate rows per (level, simplified, reading), merging parts of speech
(`DATA-123`). The reference data has 14 collisions.

`PIPE-044` `[MUST]` **Gate on unlinked rows.** The reference dataset shipped 9 unlinked rows; the target
is zero. For each remaining unlinked row, either fix the join or add a `hsk_syllabus`-sourced entry to
the dictionary (`DATA-020`), and report the choice.

`PIPE-045` `[MUST]` Emit the sort keys and store rows in final order (`DATA-121`).

`PIPE-046` `[MUST]` Emit the widget projection (`DATA-130`).

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
`DATA-186`.

`PIPE-055` `[MUST]` Emit the Jyutping syllable inventory and alias map as data (`DATA-070`), validated
by parsing every entry's reading against it.

`PIPE-056` `[MUST]` Emit `has_hsk = false` and omit the HSK tables.

---

### 6. Asset pack build

`PIPE-060` `[MUST]` Each pack is built independently, declares the `dataset_version` it targets, and
emits a key-to-record index (`DATA-171`).

`PIPE-061` `[MUST]` **Audio catalogue.** Build the word-key and syllable-key catalogues (`DATA-172`).
Word keys are canonical numbered readings (`MED-005`). A recording that cannot be attributed to exactly
one reading is **excluded** (`MED-012`), and the exclusion list is emitted for review. A manual override
file, hash-bound to the source audio, may resolve reviewed cases.

`PIPE-062` `[MUST]` Audio generation never modifies source recordings.

`PIPE-063` `[MUST]` **Strokes.** Convert the source stroke records into the compact geometry format,
validating that each character has at least one stroke path and reporting characters whose medians are
unusable (they must still ship their outlines — `MED-070`).

`PIPE-064` `[MUST]` **Origins.** Re-encode historical glyph images from base64 data URIs into a compact
image format at a fixed display size, stored as binary records rather than inside JSON (`MED-058`).

`PIPE-065` `[MUST]` **Sentences.** Build the word index and sentence store; convert scripts with OpenCC
at build time; cap per-word sentence lists; apply the ranking of `MED-047` if adopted.

`PIPE-066` `[MUST]` The legacy Hanzi-keyed audio directory (a generator input in the reference build)
must never be packaged. Any build-only input must be excluded from shipping artefacts by an explicit
allow-list, not by a filename-pattern deny-list.

---

### 7. Transformations moved out of the app

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
depends on live input), romanisation parsing driven by the shipped inventory, candidate ranking (it
depends on the query's match classes), segmentation search over shipped frequencies, and rendering.

---

### 8. Golden regression suite

`PIPE-140` `[MUST]` The pipeline ships a golden-query suite executed against each candidate dataset,
asserting exact ordered top-N entry ids. It must cover at least:

- partial pronunciation (`x`, `shi`, `zhongguore`) — including the invariant that ranking precedes
  limiting (`SRCH-071`): a limit-first implementation dropped 1029 of the correctly-ranked top 1200 for
  `x` and 1159 for `to` on the reference dataset;
- fully-toned and toneless readings (`ni3hao3`, `nihao`);
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
- every previously unlinked HSK row now resolving.

`PIPE-141` `[MUST]` The suite is shared with the app's test suite as data, so both sides assert the same
expectations against the same dataset.

`PIPE-142` `[MUST]` The suite runs on **every** dataset build, not only on pipeline changes — a source
refresh can change ranking.

---

### 9. Publication

`PIPE-150` `[MUST]` Artefacts per build: one dataset file per mode, one file per asset pack, a
machine-readable build report (`DATA-201`), and a manifest listing every artefact with its size, hash
and target `dataset_version`.

`PIPE-151` `[MUST]` The manifest is what the app's optional-download logic consumes; it must include the
minimum supported `schema_version` for each artefact.

`PIPE-152` `[SHOULD]` Publication is gated on a human-reviewed diff of the build report against the
previous version (`DATA-201`).

