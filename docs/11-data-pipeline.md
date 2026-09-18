# 11 — Data Pipeline Requirements

**Not implemented as part of this specification work.** This document defines what an independent
offline build must do to produce datasets satisfying [10 — Data Contract](10-data-contract.md).

---

## 1. Position and principles

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

## 2. Sources

### 2.1 Mandarin

`PIPE-010` `[MUST]` **CC-CEDICT** (MDBG distribution) is the primary Mandarin source: traditional form,
simplified form, numbered Pinyin, and a `/`-separated list of glosses. Reference build: the 2026-07-04
release, 124760 usable records after filtering, CC BY-SA 4.0.

`PIPE-011` `[MUST]` **FrequencyWords** `zh_cn` (OpenSubtitles 2018 via OPUS) supplies word frequencies.
Reference: 41319 forms, corpus total 81775831, CC BY-SA 4.0.

`PIPE-012` `[MUST]` **HSK 3.0 syllabus vocabulary** supplies levels, per-level parts of speech, and
syllabus glosses. Reference: 11105 rows across levels 1-7, MIT-licensed extraction.

### 2.2 Cantonese

`PIPE-013` `[MUST]` **CC-Canto** supplies Cantonese (Jyutping) readings and Cantonese-specific
definitions keyed by (traditional, simplified, Pinyin).

`PIPE-014` `[MUST]` **CC-CEDICT-Canto** (the Cantonese-reading annotation of CC-CEDICT) supplies
Jyutping readings for CC-CEDICT headwords that have no CC-Canto record.

`PIPE-015` `[MUST]` CC-CEDICT itself is a Cantonese source too: its English glosses remain useful for
Cantonese entries (`DATA-182`).

`PIPE-016` `[SHOULD]` A Cantonese frequency list, if one of acceptable quality can be licensed, drives
Cantonese segmentation; otherwise `DATA-114` applies.

### 2.3 Asset sources

`PIPE-017` `[MUST]` Example sentences (Tatoeba eng-cmn pairs, converted between scripts with OpenCC at
build time), recorded audio (audio-cmn / Shtooka), stroke data (hanzi-writer-data), character origins
(Make Me a Hanzi, CJKVI IDS, chinese-lexicon), historical glyph images (Xiaoxuetang mirror, Wikimedia).
Full attribution table: `SET-040`.

`PIPE-018` `[MUST]` Licence compatibility is checked at build time and the resolved licence text and
source metadata are emitted into the dataset (`DATA-002`, `SET-042`). GPLv2 and LGPL sources are gated
behind an explicit allow-list decision (`Q-070`).

---

## 3. Mandarin build

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

## 4. HSK build

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

## 5. Cantonese build

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

## 6. Asset pack build

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

## 7. Transformations moved out of the app

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

## 8. Golden regression suite

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

## 9. Publication

`PIPE-150` `[MUST]` Artefacts per build: one dataset file per mode, one file per asset pack, a
machine-readable build report (`DATA-201`), and a manifest listing every artefact with its size, hash
and target `dataset_version`.

`PIPE-151` `[MUST]` The manifest is what the app's optional-download logic consumes; it must include the
minimum supported `schema_version` for each artefact.

`PIPE-152` `[SHOULD]` Publication is gated on a human-reviewed diff of the build report against the
previous version (`DATA-201`).
