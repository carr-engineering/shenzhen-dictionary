# 10 — Data Contract (processed dictionary dataset)

This document defines **exactly what the application receives**. It is written so that the data
pipeline and the app can be implemented independently: the pipeline satisfies this contract, the app
assumes only this contract.

Section 2 (logical model) is `[MUST]`. Section 8 (physical encoding) is `[REC]` — a concrete,
evidence-based recommendation that may be replaced if the logical contract and the performance budgets
in [13 — Performance](13-performance.md) are still met.

Sizing figures quoted throughout are measured from the reference Mandarin dataset: 124760 entries,
197616 distinct headwords, 48800 distinct English tokens, 709027 unique (token, entry) postings, 14178
distinct characters, 427396 character postings, 11105 HSK rows, 41319 frequency rows, and 5.4 MB of
definition text.

---

## 1. Packaging and identity

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

## 2. Logical model

### 2.1 Entry

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

### 2.2 Sense

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

### 2.3 Parsed sense segments (internal links)

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

### 2.4 Redirect (variant expansion)

`DATA-055` `[MUST]` When an entry's only content is a pointer at another entry, the dataset carries a
redirect record: `kind` (`variant` / `old_variant` / `erhua`), `header_text` with its own parsed
segments, `target_entry_id` (nullable when ambiguous), and a `resolved` flag.

`DATA-056` `[MUST]` When resolved, the app renders `header_text` plus the **target's** senses
(`ENT-047`). The target's senses are reachable by id in one read; they are **not** duplicated into the
source entry.

`DATA-057` `[MUST]` An **embedded** variant (a trailing parenthetical "variant of X" inside an
otherwise semantic definition) is marked per sense with an `embedded_variant_ref` so the merge logic of
`SRCH-082` can strip it without re-parsing.

### 2.5 Note

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

## 3. Search indexes

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

## 4. Variant merge groups

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

## 5. Relationships (compounds, sub-spans, related words)

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

## 6. Frequency and segmentation data

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

## 7. HSK data

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

## 8. Physical encoding — recommendation

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

## 9. Popular words

`DATA-090` `[MUST]` A precomputed ordered list of 50 entry ids for the blank-query state: the most
frequent **multi-character** words (`SRCH-111`). It must not require a frequency-sorted scan at runtime,
and it must be resolved to ids at build time rather than to surface forms resolved at open time.

---

## 10. Companion asset packs

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

## 11. Cantonese specifics

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

## 12. Validation requirements

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

## 13. Update strategy

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
