# 07 — Data Contract and Pipeline

## Data Contract (processed dictionary dataset)

This part defines **exactly what the application receives**. The contract is the module boundary
between the dataset pipeline (`pipeline/`) and the app (`app/`), which live in the same repository
(`PIPE-160`): the pipeline satisfies this contract, the app assumes only this contract, and neither
depends on the other's code. Shared schema and test vectors live in `contract/`.

Section 2 (logical model) is `[MUST]`. Section 9 (physical encoding) is `[REC]` — a concrete,
evidence-based recommendation that may be replaced if the logical contract and the performance budgets
in [08 — Engineering, performance part](08-engineering.md) are still met.

Sizing figures quoted throughout are measured from the reference Mandarin dataset (before the Unihan
entries of `DATA-190` were added): 124760 entries,
197616 distinct headwords, 48800 distinct English tokens, 709027 unique (token, entry) postings, 14178
distinct characters, 427396 character postings, 11105 HSK rows (the earlier word list; the current
syllabus list consumed from the HSK parser repository has 11,000 words, `PIPE-012`), 41319 frequency
rows, and 5.4 MB of definition text.

---

### 1. Packaging and identity

`DATA-001` `[MUST]` A **dataset** is a self-contained, read-only, immutable bundle serving exactly one
dictionary mode. There are two datasets, `mandarin` and `cantonese`. **Both use the identical
schema.** The app selects a dataset by mode and needs no mode-specific code paths. On mobile both
datasets are installed with the app; on Web each is fetched when first needed, kept offline and
updated through changesets (`DATA-215`, `DATA-216`).

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
| `decomposition_hashes` | object | SHA-256 of the `decomposition` pack, the `decomposition-matches` file and the `strokes` pack this dataset was built with (`DATA-191`, `DATA-197`); the Web client re-fetches its stored copies only when a hash changes (`DATA-215`) |
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

`DATA-005` `[MUST]` The dataset is opened read-only and is never mutated by the app. On Web the update
process builds the next version by applying a changeset to a **copy** (`DATA-216`); the open file is
never modified. Any per-user state (settings, widget masks) lives in separate app-owned storage.

`DATA-006` `[MUST]` `content_hash` covers the content, not the file bytes, so the same logical dataset
built twice validates identically. The language-model companion file and every asset pack
(`DATA-170`) reference the `dataset_version` they were built against and are rejected on mismatch.
Non-dataset assets (voice, handwriting and OCR models, OpenCC dictionaries, fallback font —
`DATA-170`) are **not** part of the dataset, are not covered by `content_hash`, and are versioned independently of
`dataset_version`. The one exception to the rejection on mismatch is the Web copy of the decomposition
files, which is re-fetched only when its hash in the dataset metadata changes (`DATA-002`, `DATA-215`).

---

### 2. Logical model

#### 2.1 Entry

`DATA-010` `[MUST]` An **entry** is one (traditional form, simplified form, reading) triple with its
senses — the CC-CEDICT record granularity. Fields:

| Field | Type | Notes |
|-------|------|-------|
| `id` | int32 | **stable within a dataset version**; from 0, carried over between versions where possible (`DATA-013`) |
| `traditional` | string | required |
| `simplified` | string | required; may equal `traditional` |
| `reading_display` | string | what the UI shows; empty only when `reading_source` is `none` |
| `reading_canonical` | string | space-separated numbered syllables, lowercase, umlaut normalised |
| `reading_source` | enum | `cedict` / `cccanto` / `cccedict_canto` / `hsk_patch` (`PIPE-044`) / `unihan` (`DATA-190`) / `none` — where the reading comes from; `none` in the Cantonese dataset (`DATA-182`) and, in either dataset, on a Unihan-only entry without a reading for the dataset's language (`DATA-190`) |
| `syllables` | string[] | toneless, lowercase, one per position |
| `tones` | int8[] | parallel to `syllables`; `0` means unspecified |
| `is_proper_noun` | bool | precomputed from the source's capitalisation (`GLB-013`) |
| `is_unihan` | bool | the entry exists only through the Unihan fallback (`DATA-190`); ranks after dictionary entries (`SRCH-079`) |
| `frequency_rank` | int32 | 1 is most common; `1000000` is the unranked sentinel |
| `char_count` | int16 | code points in `simplified` |
| `senses` | sense[] | ordered; may be empty |
| `sense_blocks` | block[] | provenance blocks over `senses` (`DATA-022`); one block in the Mandarin dataset |
| `notes` | note[] | ordered; may be empty |
| `redirect` | redirect (nullable) | resolved (`DATA-055`) |
| `merge_group_simplified` | int32 (nullable) | (`DATA-080`) |
| `merge_group_traditional` | int32 (nullable) | (`DATA-080`) |
| `hsk_levels` | int8[] | ascending; empty when none; syllabus main levels only (`DATA-125`) |
| `hsk_levels_via_variant` | int8[] | levels received through variant relations (`DATA-193`); empty when none or when `hsk_levels` is non-empty |
| `segmentable` | bool | false for headwords excluded from the segmentation lexicon (`DATA-189`); still searchable and openable |
| `audio_word_key` | string (nullable) | canonical audio key (`MED-005`) |
| `lm_word_id` | int32 | language-model word id of the headword form (`DATA-152`) |
| `lm_unigram` | uint8 | quantised smoothed unigram log-probability (`DATA-152`, `DATA-154`) |

`DATA-011` `[MUST]` `syllables` and `tones` have equal length. When `char_count` equals the syllable
count the entry is tone-colourable (`GLB-066`); the app must not assume it is. An entry with
`reading_source` `none` has empty `syllables` and `tones` and is never tone-coloured.

`DATA-012` `[MUST]` Every entry has both script forms populated. Where the source gives one form only,
both fields carry it.

`DATA-013` `[MUST]` `id` is stable within a `dataset_version` and is used as the final ranking
tiebreak. The pipeline **carries ids over** from the previous version by natural key (`DATA-014`) so
that Web changesets stay small (`DATA-216`): a surviving entry keeps its id, a new entry takes the next
unused id above the previous maximum, and a removed entry's id is left as a gap, not reused. Gaps are
compacted (ids reassigned densely from 0) only in a **rebase** release, which the Web changeset
manifest marks as full-download-only. Ids are therefore **not guaranteed** stable across dataset
versions; anything persisted or linked across versions (widget deep links, routes, future bookmarks)
must key on the natural key instead (`DATA-014`, `DATA-212`).

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
| `source` | enum | `cedict` / `cccanto` / `cccanto_adapted` (a CC-Canto line tagged `# adapted from cc-cedict`, `DATA-182`) / `hsk_patch` (`PIPE-044`) / `unihan` (`DATA-190`); matches the sense's block (`DATA-022`) |
| `tags` | string[] | allow-listed domain, register and part-of-speech tags lifted out of the text (`DATA-060`); may be empty |
| `tokens` | string[] | English tokens per `SRCH-013`, precomputed |

The CC-CEDICT Cantonese readings file (`PIPE-014`) supplies readings only, never senses, so it is not
a sense source; HSK syllabus glosses are not part of the dataset (decided: `Q-032`). The HSK parser
patch (`PIPE-044`) and Unihan (`DATA-190`) are sense sources only for headwords that CC-CEDICT and
CC-Canto lack.

`DATA-021` `[MUST]` Sense ordering within an entry is the pipeline's decision and is final; the app
renders in the given order and numbers from 1.

`DATA-022` `[MUST]` **Provenance is labelled per block, never per sense** (decided: `Q-080`). An
entry's `senses` are partitioned into contiguous **source blocks**, each a record of: `kind`
(`cantonese` / `cantonese_adapted` / `mandarin` / `hsk_patch` / `unihan`), `label_key` (into the label table, `DATA-060`),
`first_sense` and `sense_count`, and, for a `mandarin` block, one or more **reading groups** — the
Pinyin `reading_display` and `reading_canonical` of each CC-CEDICT entry whose senses are carried, with
its sense range — in frequency order. In the Cantonese dataset blocks are stored in this order, and
the app shows the block label whenever the entry is displayed in Cantonese mode, **even when only one
block exists** (`ENT-110`):
1. `cantonese` — original CC-Canto senses for this entry's Jyutping; label `block_cantonese`:
   "Cantonese (CC-Canto)" / 粤语释义（CC-Canto） / 粵語釋義（CC-Canto）;
2. `cantonese_adapted` — senses from CC-Canto lines tagged `# adapted from cc-cedict` (`DATA-182`);
   label `block_cantonese_adapted`: "Cantonese (CC-Canto, adapted from CC-CEDICT)" /
   粤语释义（CC-Canto，改编自 CC-CEDICT） / 粵語釋義（CC-Canto，改編自 CC-CEDICT）;
3. `mandarin` — the carried-over CC-CEDICT senses (`PROD-045`); label `block_mandarin`: "Mandarin
   senses (CC-CEDICT)" / 普通话释义（CC-CEDICT） / 普通話釋義（CC-CEDICT）, shown with the Pinyin of
   each reading group; flagged `collapsed` when a Cantonese block (1 or 2) exists, expanded otherwise
   (`ENT-111`).

Two further block kinds exist in **both** datasets, each the **only** block of its entry and each
**always labelled**:
- `hsk_patch` — senses of an HSK parser patch entry (`PIPE-044`), for an HSK word CC-CEDICT lacks;
  label `block_hsk_patch`: "HSK parser patch" (zh-Hans and zh-Hant in the label data, `PIPE-079`).
  In the Cantonese dataset it takes the place the `mandarin` block would have;
- `unihan` — the Unihan `kDefinition` of a Unihan-only character (`DATA-190`); label `block_unihan`:
  "Unicode (Unihan)" / Unicode（Unihan） / Unicode（Unihan）. It is a **fallback**: it exists only on
  entries with no CC-CEDICT, CC-Canto or HSK parser patch definition, so it never appears beside another
  block (`ENT-126`).

A block is never empty; an entry with no Cantonese-sourced sense has only the `mandarin` block. A
Mandarin-dataset entry has a single block — `mandarin` (label not shown), `hsk_patch` or `unihan`. `[SHOULD]` The pipeline omits
the `mandarin` block from a Cantonese entry when at least 90 % of its normalised sense text already
appears in the entry's Cantonese blocks (`ENT-115`), and reports the count.

Rationale (analysis of the pinned sources, 2026-10-10): of the 128,460 distinct traditional
headwords that CC-Canto or the readings file give a Jyutping for, 16 % have CC-Canto definitions only,
7 % have both sources and 73 % have CC-CEDICT definitions only, so most Cantonese-mode entries are
Mandarin-sourced and a per-sense label would repeat on almost every line.

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
with a bracketed reading, a single form with a bracketed reading, a bare Hanzi run with no reading,
Latin/digit forms with a reading, and CC-Canto's Pleco-style `H+pīnyīn [H+]` form (resolution rule:
`PIPE-079`, decided: `Q-108`).

`DATA-052` `[MUST]` A bare Hanzi run (with interior middle dots normalised per `GLB-005`) is one `REF`
covering the whole run, with the traditional and simplified fields both set to the run and no reading.
The app renders one tap range for the whole run (`ENT-055`). In CC-Canto's own (non-adapted) senses a
bare Hanzi run becomes a `REF` only when it exactly equals a headword; otherwise it stays `TEXT`
(`PIPE-079`).

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

`DATA-060` `[MUST]` A **note** is structured, never free text to be re-parsed: `kind` (one of the note
kinds in the table below), `references` (same shape as `REF`, with resolved ids where unambiguous),
`reading` (nullable display form), `extra` (nullable free text), and `label_key`. These snake_case
identifiers are the **only** spelling of note kinds and sense tags — in the dataset, the pipeline, app
code and every spec document.

**Label table** (decided: `Q-020`). Every note kind, sense tag and provenance block (`DATA-022`) has a
row in a data-driven **label table** shipped in the dataset — key, English, zh-Hans and zh-Hant label
— so labels follow the UI language (`GLB-100`) and change without an app release. The app never
derives a label from the key string. The catalogue below comes from a full pass over CC-CEDICT
(2026-10-09, "C") and CC-CEDICT-derived CC-Canto (2017-02-02, "K"); counts are occurrences anywhere in
the definitions. The parser that produces them is `PIPE-079`.

*Structural conventions — parsed into notes (with references) or, where the gloss itself is the
meaning, kept as a sense with a structural tag:*

| Key | Form | Source convention | C | K | en | zh-Hans | zh-Hant |
|-----|------|-------------------|---|---|----|---------|---------|
| `see` | note | `see X` | 4,386 | free text | See | 见 | 見 |
| `see_also` | note | `see also X` | 110 | 23 | See also | 参见 | 參見 |
| `variant_of` | note | `variant of X` | 2,775 | 119 | Variant of | 异体 | 異體 |
| `old_variant_of` | note | `old variant of X` | 733 | 38 | Old variant of | 旧异体 | 舊異體 |
| `erhua_variant_of` | note | `erhua variant of X` | 258 | 0 | Erhua form of | 儿化 | 兒化 |
| `contracted_form` | note | `contracted variant of X` | 24 | 0 | Contracted form | 合文 | 合文 |
| `japanese_variant_of` | note | `Japanese variant of X` | 1 | 0 | Japanese variant of | 日本异体 | 日本異體 |
| `classifier` | note | C `CL:` (136 distinct classifiers); K Pleco-style `M: ` | 1,468 | 266 | Classifier | 量词 | 量詞 |
| `abbr_for` | note | `abbr. for X`, `(abbr.)` | 1,275 + 14 | 63 | Abbreviation for | 简称 | 簡稱 |
| `also_pr` | note | `also pr. [..]` | 185 | 42 | Also pronounced | 又读 | 又讀 |
| `taiwan_pr` | note | `Taiwan pr. [..]` | 553 | 212 | Taiwan pronunciation | 台湾读音 | 台灣讀音 |
| `also_written` | note | `also written X` | 228 | 12 | Also written | 也作 | 也作 |
| `used_in` | note | `used in X` | 910 | free text | Used in | 用于 | 用於 |
| `measure_word` | sense tag | `classifier for …`; K `[measure word]` | 249 | 240 + 7 | Classifier | 量词 | 量詞 |
| `surname` | sense tag | `surname X` | 761 | 52 | Surname | 姓 | 姓 |
| `radical` | sense tag | `Kangxi radical N` (number kept in `extra`) | 71 | 43 | Radical | 部首 | 部首 |

*Domain, register and part-of-speech tags — an **allow-list of about 60** leading parenthesised tags
(full list with labels in `contract/` as data). An allow-listed tag is lifted out of the text into the
sense's `tags` and rendered as a small label; every other parenthetical — the long tail of 2,714
distinct leading tags in C, and scope notes such as `(of sb/sth)` — stays inline as `TEXT`. The most
frequent allow-listed tags:*

| Key | Source tag | Count | en | zh-Hans | zh-Hant |
|-----|-----------|-------|----|---------|---------|
| `idiom` | `(idiom)` | 5,726 | Idiom | 成语 | 成語 |
| `bird_species` | `(bird species of China)` | 1,443 | Bird species | 鸟类 | 鳥類 |
| `figurative` | `(fig.)` | 1,439 | Figurative | 比喻 | 比喻 |
| `literary` | `(literary)` | 1,388 | Literary | 书面 | 書面 |
| `taiwan` | `(Tw)` | 1,293 | Taiwan | 台湾 | 台灣 |
| `colloquial` | `(coll.)`; K trailing `[colloquial]` | 976; K 2,406 | Colloquial | 口语 | 口語 |
| `loanword` | `(loanword)` | 846 | Loanword | 外来词 | 外來詞 |
| `bound_form` | `(bound form)` | 819 | Bound form | 构词成分 | 構詞成分 |
| `old` | `(old)` | 656 | Old usage | 旧时 | 舊時 |
| `dialect` | `(dialect)` | 588 | Dialect | 方言 | 方言 |
| `slang` | `(slang)` | 556; K 631 | Slang | 俚语 | 俚語 |
| `math` | `(math.)` | 537 | Mathematics | 数学 | 數學 |
| `literal` | `(lit.)` | 270 | Literally | 字面 | 字面 |
| `onomatopoeia` | `(onom.)` | 229 | Onomatopoeia | 拟声 | 擬聲 |
| `archaic` | `(archaic)` | 190 | Archaic | 古语 | 古語 |
| `tcm` | `(TCM)` | 179 | Chinese medicine | 中医 | 中醫 |
| `buddhism` | `(Buddhism)` | 156 | Buddhism | 佛教 | 佛教 |
| `internet_slang` | `(Internet slang)` | 139 | Internet slang | 网络用语 | 網絡用語 |
| `derogatory` | `(derog.)` | 97 | Derogatory | 贬义 | 貶義 |
| `cantonese` | `(Cantonese)`, `(Cant.)` | 69; K 289 | Cantonese | 粤语 | 粵語 |
| `prc` | `(PRC)` | 50 | Mainland China | 中国大陆 | 中國大陸 |
| `vulgar` | `(vulgar)` | 38 | Vulgar | 粗俗 | 粗俗 |
| `hong_kong` | `(HK)` | 36 | Hong Kong | 香港 | 香港 |
| `polite`, `honorific`, `courteous` | `(polite)`, `(honorific)`, `(courteous)` | 151 with `humble` | Polite / Honorific / Courteous | 敬辞 | 敬辭 |
| `humble` | `(humble)` | (above) | Humble | 谦辞 | 謙辭 |
| `written_colloquial` | K trailing `[written and colloquial]` | K 703 | Written and colloquial | 书面及口语 | 書面及口語 |
| `written` | K trailing `[written]` | K 76 | Written | 书面语 | 書面語 |
| `pos_noun`, `pos_verb`, `pos_adjective`, `pos_adverb` | K prefixes `(noun)`, `(verb)`, `(adjective)`, `(adverb)`, `n.`, `v.`, `adj.` | K 2,968 | Noun / Verb / Adjective / Adverb | 名词 / 动词 / 形容词 / 副词 | 名詞 / 動詞 / 形容詞 / 副詞 |

Provenance block labels (`DATA-022`: `block_cantonese`, `block_cantonese_adapted`, `block_mandarin`,
`block_hsk_patch`, `block_unihan`), the decomposition labels (`DATA-191`: `unencoded_component`
"Unencoded component", `unknown_component` "Unknown part") and `no_cantonese_reading` (`DATA-182`, `ENT-116`: "No Cantonese reading" / 无粤语读音 / 無粵語讀音)
are rows of the same table. A convention becomes an entry note (notes row) or stays inside its sense
as a tag per the "Form" column; either way the label replaces the source phrase (`ENT-057`).

`DATA-061` `[MUST]` A note consumed by a resolved redirect is **removed** by the pipeline, not filtered
at runtime (`ENT-048`).

`DATA-062` `[MUST]` Reference note counts in the Mandarin source (whole-gloss notes in the 2026-07-04
reference build), for validation: `see` 4222,
`variant_of` 2553, `classifier` 1578, `old_variant_of` 728, `taiwan_pr` 545, `abbr_for` 318,
`also_written` 224, `also_pr` 178, `see_also` 109 — 10219 entries carry at least one note. The kinds
and tags added by `Q-020` are validated against the occurrence counts of `DATA-060` within a declared
tolerance, and every count is diffed against the previous build (`DATA-201`).

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
A **CJK Radicals Supplement** or **CJK Strokes** code point that has an equivalent unified ideograph
(UCD `EquivalentUnifiedIdeograph.txt`, `DATA-190`) is an additional key (kind `radical_form`) for the
entries of that ideograph, so pasting ⻊ or ⺡ finds 足 or 水. (Kangxi Radicals block code points need
no extra key: NFKC already maps them to unified ideographs, `SRCH-010`.)
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
Besides each entry's main reading it holds **alternative-reading postings** (`DATA-192`).

`DATA-192` `[MUST]` **Alternative-reading postings** (`SRCH-078`). The readings given by an entry's
`also_pr` and `taiwan_pr` notes (`DATA-060`; CC-CEDICT `also pr. [..]` 185 and `Taiwan pr. [..]` 553
occurrences, CC-Canto 42 and 212; in the Cantonese dataset CC-Canto's `also pr.` Jyutping) are indexed
as reading keys of that entry, extracted by `PIPE-079`:
- **keys**: the full toneless syllable key in the reading index (`DATA-073`), with the alternative
  reading's own tone array, and a first-letter key in `abbr_idx` (`DATA-151`), so toned, toneless,
  prefix, abbreviated and combined input (`SRCH-050`, `SRCH-060`) all match it;
- **flag**: every such posting is flagged `alt` and records its kind (`also_pr` / `taiwan_pr`) and the
  ordinal of the note it comes from, so the result card can show the matched reading with its label
  ("Also pronounced …" / "Taiwan pronunciation …") without parsing, and ranking can place an entry
  matched **only** through an alternative reading after entries matched by their main reading within
  the same match class (`SRCH-078`);
- **eligibility**: an alternative reading is indexed only when it parses fully against the syllable
  inventory and has as many syllables as the main reading; partial readings (one syllable of a longer
  word) stay notes only and are counted in the build report;
- **no duplicates**: no alternative posting is emitted when its (toneless key, tone array) equals the
  entry's main reading; an alternative reading that differs only in tones gets a posting under the
  same toneless key with its own tones, so a toned query (`qi2`) matches it; the app keeps one match
  per entry, preferring the main reading (`SRCH-078`).

Unihan-only entries (`DATA-190`) feed the reading-index postings (not `abbr_idx`, which excludes
them): their second `kMandarin` value becomes a `taiwan_pr` note and further readings become `also_pr`
notes.

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

In the **Cantonese dataset** (decided: `Q-119`, following Pleco):
- senses of the `cantonese` and `cantonese_adapted` blocks (`DATA-022`) are indexed per entry as above,
  and each such posting carries a **CC-Canto flag**, so search can list CC-Canto-sourced entries before
  CC-CEDICT ones (`SRCH-047`, `SRCH-070`, `SRCH-077`) without reading the entries;
- senses of a `mandarin` block are indexed **once per CC-CEDICT entry** (headword plus Pinyin), never
  once per Cantonese entry that carries them: the posting points to the headword's **primary
  Cantonese entry** (`DATA-182`) and to the sense index within it, which is where a result navigates.
  "walk" therefore yields one 行 posting for 行 `xíng` from the Mandarin block, not one per Jyutping
  entry of 行. Mandarin-block postings carry no CC-Canto flag.

The Mandarin dataset has only `mandarin` blocks, indexed per entry, without the flag.

In **both** datasets, senses of an `hsk_patch` block are indexed like `mandarin` senses, and senses of
a `unihan` block (`DATA-190`) are indexed per entry with a **Unihan flag** on each posting, so search
lists Unihan-only entries after dictionary entries (`SRCH-079`) without reading the entries.

`DATA-077` `[MUST]` Each sense's token list is also stored **per sense** so contiguous-phrase detection
(`EN_PHRASE`) is a local check on the candidate, not a re-tokenisation of its text.

`DATA-078` `[MUST]` **Ranking projection.** For every entry, the ranking inputs — match class
(computed at query time), frequency rank, character count, proper-noun flag, Unihan-only flag
(`DATA-190`), id, and the LM unigram (`DATA-152`) — must be readable **without** loading definitions. Ranking a 30000-candidate set must not
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
(dense rank, 1-based), with the sentinel `1000000` where absent. The app never computes it. In the
Cantonese dataset the frequency table is the custom Cantonese table of `DATA-189`, never the Mandarin
one. The
pipeline `[SHOULD]` rank entries that the frequency table leaves unranked by their smoothed LM unigram
(`DATA-152`), recording the choice in metadata.

`DATA-112` `[MUST]` For segmentation the app needs, per candidate substring, "is this a dictionary
word" and "what is its frequency". Both must be answerable in one batched lookup per run
(`TXT-014`), so the frequency value should be co-located with the headword index or reachable by the
same key.

`DATA-113` `[SHOULD]` Precompute the natural log-probability per headword so the segmenter performs no
logarithms at runtime.

`DATA-114` `[MUST]` The Cantonese dataset uses a **custom Cantonese frequency table** (`DATA-189`,
built by `PIPE-077`; decided: `Q-081`) and sets `has_frequency_data` true. Mandarin frequencies are not
reused for Cantonese. Should the Cantonese table fail its build gate, the build fails; it does not fall
back silently to Mandarin frequencies or to greedy longest-match (`TXT-013`).

`DATA-189` `[MUST]` **Cantonese frequency table.** Same shape as `DATA-110` (surface form in
traditional script → integer count, corpus total in metadata), built by `PIPE-077`:
- base: the rime-cantonese `essay-cantonese.txt` word weights (CC BY 4.0, about 267k word → weight
  lines), rescaled to counts;
- counts added from segmented colloquial corpora: HKCanCor (CC BY 4.0, about 230k pre-segmented
  spoken words), Common Voice `yue` sentences (CC0, about 28.7k), Tatoeba `yue` (CC BY 2.0 FR, about
  20.9k sentences), UD_Cantonese-HK (CC BY-SA 4.0, about 1k sentences) and the zh-yue Wikipedia dump
  (CC BY-SA 4.0).

Corpus-only evidence is thin (the Tatoeba file alone is about 904k tokens, enough for a rough top-5k
ranking), which is why the essay weights are the base. Headwords absent from the table are ranked by
their smoothed LM unigram (`DATA-111`). The source mix and weights are recorded in `sources` and the
build report. **Pronoun + 嘅 headwords** (佢嘅, 我嘅, 你嘅, 我哋嘅, …) are removed from the
segmentation lexicon — both for corpus segmentation in the pipeline and in the dataset, where they carry
`segmentable` false (`DATA-010`) so the app's segmenter never proposes them (`TXT-012`); they remain
searchable and openable. The derived frequency table (and the Cantonese LM, `DATA-152`…) is published
under **CC BY-SA 4.0** with attribution to every input (`SET-040`).

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
the inventory (Latin-initial readings and Unihan-only entries, `DATA-190`, are excluded). Postings per key, in descending unigram order
(`DATA-079` exception), each carrying: entry id, LM word id (`DATA-152`), quantised unigram
(`DATA-154`), and the syllable inventory indices of the reading, so the decoder can filter postings
per token (for example `zh` versus `z`) without reading the entry. Caps: single-letter keys keep the
300 most frequent entries; longer keys are capped by a pipeline-configured limit reported in the build
report (`PIPE-076`). An entry's alternative readings (`DATA-192`) add postings under their own keys,
flagged `alt`.

`DATA-152` `[MUST]` **LM word ids and unigrams.** Every distinct headword form used by the dataset's
language model (simplified form for Mandarin, traditional form for Cantonese) has a dense LM word id
from 0, assigned in descending unigram probability so frequent words have small ids. **Every** headword
except a Unihan-only entry (which is never a language-model edge, `DATA-190`) has a finite, smoothed unigram log-probability (natural log), including headwords absent from the
source model (`PIPE-072`). **Licence boundary:** values derived from the libime model (LGPL-2.1+) —
word ids, unigrams, bigrams and codebooks of the Mandarin language model — live **only** in the
separate, replaceable language-model companion file, never in the CC BY-SA 4.0 main dataset
(`PIPE-018`). The main dataset keeps its own frequency rank (`DATA-110`…`DATA-114`) for ranking and
segmentation; the decoder and the optional bigram segmentation (`TXT-012`) read word ids and unigrams
from the companion file in the same batched read as the bigram blocks. The Cantonese language model
(CC BY-SA 4.0, `PIPE-077`) follows the same layout for uniformity.

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
| `level` | int8 | 1 to 7; 7 means the 7-9 band; the word's **main** level |
| `simplified` | string | |
| `traditional` | string | |
| `homograph` | int8 | syllabus homograph index (`本1`/`本2` → 1/2); 0 when none (`HSK-003`) |
| `parts_of_speech` | string[] | source tokens for this level, de-duplicated; empty for the 495 words without a part of speech |
| `extra_levels` | (level, parts_of_speech[])[] | the bracketed extra levels of the word (半 `1（4）`, POS `数、（副）` → level 4, `副`); empty for all but 102 words (`HSK-003`, `HSK-080`) |
| `entry_id` | int32 | the **primary** linked dictionary entry; never null (`DATA-124`) |
| `also_entry_ids` | int32[] | further linked entries when one simplified word stands for several CC-CEDICT entries (后/後, 回/迴, 干/乾/幹; `HSK-050`); usually empty |
| `sort_key` | string | precomputed pronunciation sort key (`HSK-030`) |

A syllabus word is keyed by **(simplified word, syllabus pinyin, homograph index)** (`HSK-003`): 82
rows carry a homograph index and 61 polyphones appear twice with different pinyin (还 hái / huán), so
neither the word alone nor (word, pinyin) is unique. The key is used by the pipeline's de-duplication
and build report (`PIPE-043`); the syllabus pinyin itself is not stored (below).

**Every row links to a dictionary entry** (decided: `Q-118`): in the Mandarin dataset to the CC-CEDICT
entry — or, for a word CC-CEDICT lacks, the **HSK parser patch** entry (`PIPE-044`) — that the HSK
parser repository resolved the row to (`PIPE-012`, `PIPE-041`), in the Cantonese dataset to the
Cantonese dataset's copy of that same entry (`DATA-187`). Where a row links several entries
(`also_entry_ids`), `entry_id` is the primary one: the card, ordering, widget and Word of the Day use
it, and every linked entry carries the level in `hsk_levels` (`HSK-050`). There are no unlinked rows,
no fallback fields and no syllabus-sourced entries. The syllabus reading is used only by the parser's
join; it is not stored in the dataset, never displayed and not offered as a secondary "HSK reading"
(decided: `Q-031`). The table carries **no syllabus glosses** (decided: `Q-032`): CC-CEDICT and
CC-Canto (plus the HSK parser patch for words CC-CEDICT lacks) are the only definition sources for HSK
words.

`DATA-121` `[MUST]` `sort_key` is the normalised canonical reading of the linked entry (`HSK-031`). In
the Mandarin dataset rows are stored in (level, sort_key, simplified, row_id) order. In the Cantonese
dataset the linked entry's reading is **Jyutping**, so rows sort by Jyutping; a row linked to a
`reading_source` `none` entry (`DATA-182`) has no Jyutping, takes the canonical Pinyin of its
CC-CEDICT entry as `sort_key`, and sorts **after** the Jyutping rows of its level: storage order is
(level, no-Jyutping flag, sort_key, simplified, row_id).

`DATA-122` `[MUST]` A **part-of-speech label table** mapping each source token to a label in every UI
language — English, zh-Hans and zh-Hant (`HSK-044`, `GLB-100`) — so new tokens do not need an app
release.

`DATA-123` `[MUST]` The rows are already **de-duplicated per (level, syllabus key)** — the key of
`DATA-120` — with parts of speech merged (`HSK-027`); there is **one row per syllabus word per level**.
The earlier reference list contained 14 collisions; the count for the current list is reported per build.
Homograph rows of one level that link to the same primary entry stay separate rows (the syllabus
count needs them); the app merges them into one card (`HSK-027`).

`DATA-124` `[MUST]` **Zero unlinked rows** (decided: `Q-118`; `HSK-056`). Every row arrives resolved
from the HSK parser repository (`PIPE-012`) and the pipeline verifies that it resolves to an entry of
each dataset (`PIPE-041`, `PIPE-044`); a row that does not fails the build (`DATA-200` gate 8). No
syllabus-sourced dictionary entries are created.

`DATA-125` `[MUST]` The entry's `hsk_levels` is derived from the main `level` of the rows that link
it (`entry_id` or `also_entry_ids`), ascending and de-duplicated. Extra levels (`extra_levels`) are not
part of `hsk_levels`: they are read with the per-level parts of speech (`DATA-126`) and shown as an
additional badge (`HSK-080`); they never add a grid card.

`DATA-126` `[MUST]` Per-entry, per-level parts of speech — every level of the word (main and extra
levels, and levels received through variants, `DATA-193`) each with **its own** parts of speech, never
merged across levels — must be readable **with the entry** (one read), not by a second query at
entry-open time. Only the entry detail screen shows them, each level with its grammatical class
(`HSK-080`); cards (search results, HSK grid, related rows, sheets, Word of the Day, widget) show the
level badges only and never read parts of speech; the widget projection (`DATA-130`) carries none.

`DATA-193` `[MUST]` **HSK levels through variants** (`HSK-097`; built by `PIPE-082`). An entry related
to an HSK-linked entry by a **plain**, **old** or **erhua** variant relation (`variant_of`,
`old_variant_of`, `erhua_variant_of` notes or redirects with a resolved target, `DATA-054`,
`DATA-055`), in **either direction** — the entry is a variant of the HSK word, or the HSK word is a
variant of the entry — receives that HSK word's levels and per-level parts of speech in
`hsk_levels_via_variant` (`DATA-010`):
- propagation follows at most **two hops**; other variant kinds (`contracted_form`,
  `japanese_variant_of`) and unresolved references do not propagate;
- an entry with its own `hsk_levels` **never** receives propagated levels;
- when an entry is reached from several HSK words with different levels, it takes the levels of the
  source with the **lowest** level (ties: the fewest hops, then the lowest source entry id);
- the app shows the badge exactly as for `hsk_levels` (`HSK-040`, `HSK-097`), but propagated entries
  have **no HSK row**: they never appear in the HSK grid, the widget or Word of the Day (`DATA-130`);
- in the Cantonese dataset the propagation is computed on the CC-CEDICT entries as in Mandarin, and
  every Cantonese entry carrying a receiving CC-CEDICT entry in its `mandarin` block carries the same
  `hsk_levels_via_variant` (`HSK-094`).

Current data (2026-10 analysis): 3,716 CC-CEDICT entries carry 3,767 variant relations (plain 2,709,
old 723, erhua 258, other); propagation gives **630** extra entries a level — 622 at hop 1, 8 at hop 2,
none deeper — of which 370 are single characters (mostly obscure old forms, 宍 → 肉) and 260 words
(抵銷 → 抵消, 骨董 → 古董, 39 erhua forms); 2 entries receive conflicting levels.

`DATA-194` `[MUST]` **HSK character list** (`HSK-098`; decided: `Q-120`). The syllabus's recognition
and writing character lists (汉字) come from the HSK parser repository (`PIPE-012`) and are stored as
data:
- a table `hsk_char`, present in both datasets and **ordered** by (level, `sort_key`, character) so the
  app reads it sequentially, of (character, recognition level 1–7, `writing` flag, writing level band,
  `entry_id`, `sort_key`) — 3,088 **recognition characters** (认读字: L1 246, L2 125, L3 284, L4 441,
  L5 431, L6 413, L7-9 1,148) and the **1,200 writing characters** (书写字, a subset: L1-2 100, L3-6 150
  each, L7-9 500). `entry_id` is the character's **primary single-character entry** in that dataset —
  the most frequent entry whose simplified form is the character (in the Cantonese dataset: whose
  simplified field is), ties broken by the lowest id; a Unihan-only entry counts (`DATA-190`) — and
  `sort_key` is that entry's canonical reading as in `DATA-121` (in the Cantonese dataset an entry
  without Jyutping sorts after the Jyutping characters of its level).

The table serves **only** the HSK destination's **Characters section** (`HSK-098`), which lists the recognition
characters grouped by character level, each card rendered from `entry_id` like any card and carrying
**no** HSK level badge. Entries carry no character-level field: the character level is **never
displayed** anywhere else — no character-level badge on cards or on the entry detail screen — and it
is **not** a word level: it never feeds
`hsk_levels`, `hsk_levels_via_variant`, word badges, the word grid, propagation, the widget or Word of
the Day. The writing list (`writing` flag and band) is data only, with no v1 presentation. Analysis
figures, for the build report: a character's level equals the first word level containing it for 3,085
of 3,088 (exceptions 店, 饺, 留); 1,503 characters are also single-character syllabus words, 414 of them
with a word level different from the character level (白: character L1, word L5); 1,585 characters
occur only inside multi-character words. Every listed character must have at least one
single-character entry in each dataset, else the build fails (`DATA-200` gate 8).

`DATA-130` `[MUST]` A separate, tiny **widget projection** (`SET-066`) carrying only what the
word-of-the-day widget renders: entry id, natural key, levels, both scripts, both readings, header
notes, senses, notes, for every HSK row (all rows are linked, `DATA-124`). One is emitted per dataset,
Mandarin and Cantonese, since the widget follows the dictionary mode (`SET-062`) and the Word of the
Day card (`SRCH-115`) uses the same deterministic pick (`SET-063`) over it. Reference size about 0.55 MB compressed for 11k
words. The widget must never open the main dataset. Entries that hold a level only through variants
(`DATA-193`) are not in the projection.

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
  reading_source INTEGER, syllables BLOB, tones BLOB, flags INTEGER, freq_rank INTEGER,
  char_count INTEGER, natural_key BLOB, merge_simp INTEGER, merge_trad INTEGER, hsk_levels BLOB,
  hsk_via_variant BLOB, audio_key TEXT, lm_word INTEGER, lm_unigram INTEGER,
  senses BLOB, blocks BLOB, notes BLOB, redirect BLOB)` — `flags`: proper noun, `segmentable`,
  Unihan-only (`DATA-190`); `blocks`: `DATA-022`; `hsk_levels`: levels plus per-level parts of speech
  (`DATA-126`); `hsk_via_variant`: levels plus per-level parts of speech (`DATA-193`);
  `lm_word`/`lm_unigram`: `DATA-152`
- `entry_natural(natural_key BLOB PRIMARY KEY, entry_id INTEGER)`
- `headword(key TEXT, entry_id INTEGER, kind INTEGER)` — kind: exact / folded / lowercased /
  radical_form (`DATA-071`)
- `reading(key TEXT, entry_id INTEGER, alt INTEGER, alt_note INTEGER, tones BLOB)` — `alt`: 0 main,
  1 `also_pr`, 2 `taiwan_pr`; `alt_note` and `tones` only on alternative postings (`DATA-192`)
- `substr_idx(key TEXT PRIMARY KEY, postings BLOB)` — `DATA-074`
- `char_idx(ch TEXT PRIMARY KEY, postings BLOB)` — `DATA-075`
- `en_token(token TEXT PRIMARY KEY, postings BLOB)` — `DATA-076`, entry plus sense pairs (plus the
  CC-Canto flag in the Cantonese dataset, and the Unihan flag in both)
- `merge_group(id INTEGER PRIMARY KEY, members BLOB, variants TEXT)`
- `within(entry_id INTEGER PRIMARY KEY, rows BLOB)` — `DATA-101`
- `word_freq(key TEXT PRIMARY KEY, freq INTEGER, log_prob REAL)`
- `hsk_row(row_id INTEGER PRIMARY KEY, level INTEGER, ...)` — `DATA-120` (including `homograph`,
  `extra_levels`, `also_entry_ids`), stored in sort order
- `hsk_char(ch TEXT PRIMARY KEY, level INTEGER, writing INTEGER, writing_band INTEGER, entry_id INTEGER,
  sort_key TEXT)` — `DATA-194`, stored in sort order
- `hsk_pos_label(token TEXT PRIMARY KEY, label TEXT)`
- `label(key TEXT PRIMARY KEY, en TEXT, zh_hans TEXT, zh_hant TEXT)` — note kinds, sense tags, block
  labels (`DATA-060`, `DATA-022`)
- `syllable(value TEXT PRIMARY KEY)` and `syllable_alias(src TEXT PRIMARY KEY, dst TEXT)` — `DATA-070`
- `syllable_abbrev(abbrev TEXT, syllable TEXT, PRIMARY KEY(abbrev, syllable))` — `DATA-150`
- `lm_codebook(kind INTEGER, code INTEGER, value REAL, PRIMARY KEY(kind, code))` — `DATA-154`
- `popular(rank INTEGER PRIMARY KEY, entry_id INTEGER)` — `DATA-090`

Language-model companion file:

- `meta(key TEXT PRIMARY KEY, value TEXT)` — `dataset_id`, `dataset_version` it targets
- `abbr_idx(key TEXT PRIMARY KEY, postings BLOB)` — `DATA-151`; postings from alternative readings
  carry the `alt` kind (`DATA-192`)
- `bigram(left INTEGER PRIMARY KEY, block BLOB)` — `DATA-153`

`DATA-142` `[REC]` Indexes: exactly the ones the queries need, and **no reverse indexes**. Required:
`headword(key, entry_id)`, `reading(key, entry_id)`, `hsk_row(level, sort_key, row_id)`,
`hsk_char(level, sort_key, ch)`,
`entry(freq_rank, id)`; `abbr_idx`, `bigram`, `syllable_abbrev` and `lm_codebook` need only their
primary keys. The reference schema carried a headword-by-entry index (8 MB) and an
english-token-by-entry index (13.5 MB) that no query used — 21.5 MB of pure waste.

`DATA-143` `[REC]` `senses`, `notes`, `redirect`, `within`, and all posting lists are **length-prefixed
binary blobs**, not JSON:
- posting lists: delta-encoded ascending entry ids as unsigned varints (ids reach 124760, so deltas
  average one to two bytes); `abbr_idx` postings (frequency order) use plain varints;
- `within` rows: varint triples of start, length, and entry-id delta;
- senses: a varint count, then per sense a varint source enum, a varint tag count with tag keys, a
  varint segment count, and per segment a tag plus length-prefixed UTF-8 fields;
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
frequent **multi-character** words (`SRCH-111`), by the dataset's own frequency table (Cantonese:
`DATA-189`). It must not require a frequency-sorted scan at runtime,
and it must be resolved to ids at build time rather than to surface forms resolved at open time.

---

### 11. Companion files, asset packs and non-dataset assets

`DATA-170` `[MUST]` Besides the main dataset file, each dataset has a language-model companion file
(`DATA-151`, `DATA-153`), and asset families are separate packs. Each companion file and pack declares
the `dataset_version` it targets (`DATA-006`). On mobile **everything is installed with the app except
the voice model**, the only separately delivered asset (`DATA-215`, `VOICE-095`; decided: `Q-090`).

| Pack / file | Contents | Reference size | Access key |
|-------------|----------|----------------|------------|
| `lm` (per dataset) | first-letter index, bigram blocks | about 2.5 to 3.5 MB | key / left word id |
| `audio-syllables` | per-syllable recordings | about 13 MB, 1707 clips | canonical syllable (`MED-007`) |
| `audio-words` | full-word recordings | about 52 MB, 6657 clips | canonical word key (`MED-005`) |
| `strokes` | outlines, medians, radical indices | about 30 MB | code point |
| `sentences` | Mandarin example pairs plus a word index | about 5.5 MB | simplified headword |
| `sentences-yue` | Cantonese example sentences with English translations plus a word index (`DATA-188`) | about 0.5 MB (estimate; measure) | traditional headword |
| `decomposition` (shared by both datasets) | per-character IDS per region, radical and stroke data, synthetic-component SVG glyphs, curated radical names (`DATA-191`) | about 1 to 2 MB (estimate; measure) | code point / synthetic id |
| `decomposition-matches` (shared by both datasets; LGPL-3.0, separate file) | per-character stroke-to-part mapping from Make Me a Hanzi `matches`, aligned to the `decomposition` trees (`DATA-197`) | about 0.5 to 1 MB for about 9k characters (estimate; measure) | code point |

**Non-dataset assets.** These are app assets, not part of any dataset: they are not covered by
`content_hash`, are versioned independently of `dataset_version` (each pinned by version and SHA-256 in
the repository lock file, `PIPE-162`), and are credited in `SET-040`.

| Asset | Approx. size | Licence | Platforms and delivery |
|-------|--------------|---------|------------------------|
| Voice model: sherpa-onnx streaming Paraformer, trilingual zh/yue/en, int8 (`VOICE-001`…) | about 240 MB | Apache-2.0 (per the model's ModelScope page; decided: `Q-101`) | **The only separate download on mobile** (`VOICE-095`): Google Play — on-demand Play Asset Delivery pack, requested when Voice is first opened and downloaded by Play (no app networking); iOS — Apple-hosted Background Assets pack (`Q-116`); direct APK — bundled. Not on Web |
| OpenCC dictionaries (`DATA-195`) — shipped by the OpenCC Kotlin Multiplatform library (`ARCH-196`), used at runtime for script conversion on Reading, OCR text and Voice transcript (`TXT-081`…, `CAM-130`…, `VOICE-096`) | about 1.35 MB raw text for all configurations used (under about 0.5 MB gzipped, estimate) | Apache-2.0 | Android, iOS: installed with the app (bundled with the library); Web: fetched lazily per configuration when conversion is first used, browser-cached, not guaranteed offline |
| Handwriting model: ML Kit Digital Ink, simplified / traditional / Hong Kong (`HW-001`…) | about 20 MB per script model (measure) | ML Kit terms (`SET-040`) | Android, iOS: installed with the app; Web: repackaged, fetched on demand |
| OCR model: ML Kit Chinese text recognition (`CAM-001`…, `DATA-196`) | Android about 15 to 20 MB; Web about 2.61 MB | ML Kit terms (`SET-040`) | Android: installed with the app. iOS: none (Apple Vision). Web: the model repackaged for the browser (`ARCH-197`), downloaded on first use of OCR, **stored offline** and updated only when its manifest version changes (`DATA-196`) |
| CJK fallback font for characters missing from platform fonts (decided: `Q-109`) — an OFL-1.1 CJK font, subset by the pipeline to dataset code points the platform fonts lack | measure; budget line in `PERF-070` | SIL OFL 1.1 | Android, iOS: installed with the app. Not on Web: no CJK font is bundled there; Compose Multiplatform's web font fallback fetches its font from a third-party font server when needed, outside the offline set (decided: `Q-117`; `GLB-090`); it is not a pipeline artefact |

`DATA-195` `[MUST]` **OpenCC dictionaries.** Runtime script conversion (`TXT-081`…`TXT-084`,
`CAM-130`…, `VOICE-096`) uses the OpenCC dictionaries packaged by the project's OpenCC Kotlin
Multiplatform library (`opencc-kmp`, `ARCH-196`): the dictionaries of a **pinned OpenCC release**
(Apache-2.0), including the generated ones (`STPhrases` from regional phrases, `TSCharactersExt`, the
reversed tables), in the library's compact format. They are a non-dataset asset: pinned by the
library's version (and its SHA-256 through Gradle dependency verification), not by the lock file;
not a pipeline artefact; not covered by `content_hash`. Size: about 1.35 MB raw text for every
configuration the app uses (`t2s`, `tw2s`, `hk2s`, `tw2sp`, `s2t`, `tw2t`, `hk2t`, `s2hk`, `t2hk`,
`s2tw`, `t2tw`, `s2twp`), under about 0.5 MB gzipped (estimate; measure, `PERF-059`). They are
**loaded lazily per configuration** — for example the about 1 MB `STPhrases` only when a Simplified →
Traditional conversion is first used. Mobile: bundled with the app. Web: each configuration's
dictionaries are fetched from `szdict.com` when that conversion is first used, verified against the
build manifest (`PIPE-150`), browser-cached and **not guaranteed offline** (`GLB-035`). This replaces
the earlier Voice-only "OpenCC conversion table". (Build-time conversions — example sentences,
`PIPE-065`; Cantonese corpus normalisation, `PIPE-074` — use the
same pinned OpenCC release inside the pipeline.)

`DATA-196` `[MUST]` **Web OCR model and manifest** (`ARCH-197`, `CAM-130`…). The Web OCR engine's
model — the ML Kit text-recognition model repackaged for the browser, about 2.61 MB — is published
with the Web deployment together with a small **OCR model manifest**:

| Manifest field | Notes |
|----------------|-------|
| `model_version` | version of the repackaged model, independent of `dataset_version` |
| `url` | location of the model file(s) on `szdict.com` |
| `size` | bytes |
| `sha256` | of each model file |
| `min_app_version` | oldest Web app build that can run this model |

Behaviour:
- the model is downloaded on the **first use of OCR**, verified against the manifest's SHA-256 and
  stored for offline use (OPFS or Cache Storage, with the persistent storage already requested for the
  dataset, `DATA-215`);
- when online, opening OCR fetches the manifest and downloads the model again **only when
  `model_version` changed**; an up-to-date model triggers no download; offline, the stored model is
  used without any check;
- a new version is downloaded and verified completely before it replaces the stored one (`DATA-210`);
  a failed download or a hash mismatch leaves the stored model in use;
- Android bundles its ML Kit model and iOS uses Apple Vision, so neither has a manifest or a download.

`DATA-171` `[MUST]` Each pack declares an index that maps its access key to a record location, so
reading one record costs one seek plus one decode. The reference packs are 256 whole-JSON shards that
must be fully parsed to read one character.

`DATA-172` `[MUST]` An **audio catalogue** lists the available word keys and syllable keys so
availability can be answered without touching the audio files. It is built in the same pipeline run
as the datasets (`PIPE-061`).

`DATA-173` `[MUST]` On mobile every pack, companion file and non-dataset asset **except the voice
model** is present after install; a missing or unreadable one is a defect, but the app still degrades
gracefully (`GLB-035`). A missing voice model (Play and iOS before its download completes) is a normal state shown by the Voice destination's download state (`VOICE-095`). On Web,
everything except the offline set (`DATA-215`: app shell, dataset and, once downloaded, the OCR model
and, once first used, the decomposition files) is fetched on demand, and a not-yet-fetched, evicted
or failed file is a normal state handled by `GLB-035`; a not-yet-downloaded OCR model is the OCR
destination's download state (`DATA-196`).

---

### 12. Cantonese specifics

`DATA-180` `[MUST]` The Cantonese dataset is produced by joining Cantonese sources onto the Mandarin
headword space; it is a **complete, standalone dataset** in the same schema, not a delta.

`DATA-181` `[MUST]` Readings are Jyutping; tones are 1 to 6; `romanization` is `jyutping`;
`tone_count` is 6; the syllable inventory is Jyutping.

`DATA-182` `[MUST]` **Readings and sense provenance** (decided: `Q-080`, `Q-082`; algorithm
`PIPE-053`). Sources: CC-Canto ("K", Jyutping plus Cantonese definitions), the CC-CEDICT Cantonese
readings file ("R", Jyutping only, no definitions) and CC-CEDICT ("C").
- An original CC-Canto definition for the headword and Jyutping becomes a sense with source `cccanto`
  in the `cantonese` block (`DATA-022`).
- A CC-Canto line tagged `# adapted from cc-cedict` (8,882 lines, all single characters) is a
  **Cantonese reading split** of old CC-CEDICT senses; its senses get source `cccanto_adapted` in the
  `cantonese_adapted` block. For a **single-character polyphone** (several Jyutping) adapted senses
  are **never suppressed**: they are the only per-Jyutping split of meaning (行 `hong4` "row;
  profession" vs `hang4` "to walk"; decided: `Q-118`). Only for a **monophone** (a headword with one
  Jyutping) is an adapted sense whose normalised text duplicates a sense of the current CC-CEDICT
  release for the headword suppressed (`ENT-114`).
- CC-CEDICT senses are carried over with source `cedict` into the `mandarin` block, **after** the
  Cantonese blocks, each reading group introduced by its Pinyin:
  - a headword with a single Mandarin reading (almost every multi-character word) gets the senses of
    the C entry joined on (traditional, normalised Pinyin) (`ENT-112`);
  - a **single-character polyphone** gets one headword-level block holding the senses of **all** its C
    entries in frequency order, identical on every Cantonese entry of that character; a Mandarin
    reading or sense is never paired with a particular Jyutping (`ENT-113`).
- A headword present in CC-Canto but absent from CC-CEDICT still becomes an entry.
- A CC-CEDICT headword with a Jyutping from K or R but no Cantonese gloss still becomes an entry,
  carrying only the `mandarin` block (`PROD-045`).
- **No Jyutping** (decided: `Q-115` option (a)): a CC-CEDICT headword with
  no Jyutping in K or R — 19,905 headwords in the current release — is kept as **one entry per
  (traditional, simplified)** with `reading_source` `none`, empty reading fields, only the `mandarin`
  block (expanded) and the `no_cantonese_reading` label (`ENT-116`). No Jyutping is ever guessed or
  generated. It is findable by Hanzi and English, not by Jyutping, and is excluded from `abbr_idx`
  (`DATA-151`).
- A readings-file headword that has no definition in C or K (R predates C by a decade: 4,977 R
  headwords are no longer in CC-CEDICT, 4,966 of them without a CC-Canto definition either) produces
  no entry, since an entry is never empty (`PROD-045`); the count is reported (`PIPE-078`).
- **Primary Cantonese entry.** A headword's primary Cantonese entry is its Cantonese entry (traditional
  plus simplified form; one per Jyutping, `DATA-184`, or the single `reading_source` `none` entry) with
  the highest frequency (lowest frequency rank), ties broken by the lowest id. It normally carries every
  CC-CEDICT entry of the headword in its `mandarin` block; in the rare multi-character case where
  Cantonese entries carry different CC-CEDICT entries (`PIPE-053` step 4), the same rule is applied
  among the entries carrying the CC-CEDICT entry concerned. It is the target of the headword's
  Mandarin-block English postings (`DATA-076`, `SRCH-077`) and of HSK rows (`DATA-187`); the pipeline
  resolves it at build time.

Join evidence (2026-10-10 analysis): R joins C on (traditional, normalised Pinyin) for 100,313 of
125,244 C entries (80 %), and its multi-character readings are correct (銀行 `ngan4 hong4`, 長大
`zoeng2 daai6`). R carries **no reading-specific information for single-character polyphones** — for 0
of 1,261 multi-reading characters does it give different Jyutping per Pinyin (長 `zhang3` → `coeng4` is
wrong) — so R is never used to infer Pinyin → Jyutping for a single character. CC-Canto's Pinyin is
equally unreliable for adapted lines (571 of 576 polyphones use one Pinyin for every Jyutping), so
CC-Canto is keyed on (traditional, Jyutping).

`DATA-183` `[MUST]` Where a Cantonese source supplies **additional** senses for a word that also exists
in Mandarin, those senses are added, not substituted.

`DATA-184` `[MUST]` One Chinese headword may have several Cantonese readings; each distinct reading is
its own entry, exactly as in Mandarin.

`DATA-186` `[MUST]` Cantonese entries carry a null `audio_word_key` unless a Cantonese audio pack
exists. The reference audio corpus is Mandarin-only.

`DATA-187` `[MUST]` **HSK in the Cantonese dataset.** The Cantonese dataset carries its own HSK
row table (`has_hsk` true; display rules `HSK-090`…`HSK-096`). Each row links to the Cantonese
dataset's **copy of the CC-CEDICT entry the row links to in the Mandarin dataset** (`PIPE-041`) — never
by written form or by reading (the syllabus reading is Pinyin). Every CC-CEDICT entry is carried by
the Cantonese dataset (`PIPE-050`), so every row links; where several Cantonese entries carry it (one
per Jyutping reading, `DATA-184`), the row links to its **primary Cantonese entry** — the most
frequent, then the lowest id (`DATA-182`) — and the decision is recorded in the build report.
Jyutping readings come from CC-Canto or the readings file through the linked entry (`DATA-182`); the
row's `sort_key` is that entry's Jyutping canonical reading (`DATA-121`). Levels, row set,
de-duplication and parts of speech are identical to the Mandarin dataset (`HSK-091`); only `entry_id`
and `sort_key` differ. A row whose linked entry is a `reading_source` `none` entry (`DATA-182`) has no
Jyutping and sorts after the Jyutping rows of its level by its CC-CEDICT entry's Pinyin (`DATA-121`,
`HSK-093`).

`DATA-188` `[MUST]` **Cantonese example sentences** (`MED-048`). The `sentences-yue` pack
(`DATA-170`) holds Cantonese examples built by `PIPE-065` from Tatoeba `yue` (`yue_sentences.tsv`,
`id`/`yue`/`text`, about 20,853 sentences, Traditional script, CC BY 2.0 FR):
- only the **short subset** — sentences of at most 20 characters, about 6.6k — is used; the long,
  paragraph-like sentences (about 14.7k, average about 112 characters, encyclopaedic and religious
  skew) are omitted;
- English translations come from Tatoeba's translation links and English sentences exports, pinned
  like every source (`PIPE-162`); a sentence with no linked English translation is excluded (the
  count is reported);
- each record stores the Tatoeba sentence id(s) (for attribution), the Traditional text, a Simplified
  text converted with OpenCC at build time, and the English text;
- the word index is keyed by **traditional headword**, built by segmenting each sentence with the
  Cantonese headword list (pronoun + 嘅 headwords excluded, `DATA-189`), ranked per `MED-047`
  (headword as a standalone segmented word first, then shorter, then sentence id) and capped at 20
  per word (`MED-046`).

---

### 13. Validation requirements

`DATA-200` `[MUST]` A dataset that fails **any** of these gates must not be published:

1. `schema_version`, `dataset_version`, `content_hash`, and every metadata field present and well-formed.
2. `entry_count` matches the actual row count.
3. Every entry: both script forms non-empty; `reading_canonical` parses fully against the declared
   syllable inventory (report and cap exceptions such as Latin-initial readings; `reading_source`
   `none` entries, `DATA-182`, are counted, not parsed); syllable and tone arrays of equal length;
   source blocks non-empty, contiguous and covering every sense (`DATA-022`).
4. Every posting list is ordered as declared (`DATA-079`) and references existing entry ids.
5. Every redirect target, merge-group member, `within` target, and HSK row entry id references an
   existing entry.
6. Every merge group has two or more members and two or more distinct alternate forms.
7. `max_headword_len` and `max_word_len` match the data and `max_word_len` ≤ `max_headword_len`
   (`DATA-004`).
8. HSK: the per-level word counts equal the **HSK 3.0, 2026 update** (新版HSK考试大纲, CTI; syllabus
   published 2025-11, in force 2026-07; decided: `Q-110`) — new words per level 300, 200, 500, 1,000, 1,600, 1,800 and 5,600
   (levels 1–6 and 7-9; cumulative 300 / 500 / 1,000 / 2,000 / 3,600 / 5,400 / 11,000) — with every
   difference caused by de-duplication (`DATA-123`) or multi-reading rows enumerated in the build
   report; the Cantonese row set equals the Mandarin one (`DATA-187`); **every row in both datasets
   links to an entry** (zero allowance, `DATA-124`) — in the Cantonese dataset to the copy of its
   Mandarin row's entry; every resolved CC-CEDICT form of the HSK parser output (`PIPE-041`) names an
   entry of the pinned CC-CEDICT or of the HSK parser patch; no duplicate (level, syllabus key)
   (`DATA-123`); no row carries a gloss (`DATA-120`); every `also_entry_ids` member carries the row's
   level; no HSK parser patch entry has the natural key of a CC-CEDICT entry (`PIPE-044`); no entry
   has both `hsk_levels` and `hsk_levels_via_variant` (`DATA-193`); the HSK character table has 3,088
   recognition characters and 1,200 writing characters with the per-level counts of `DATA-194`, each
   linked to a single-character entry in both datasets.
9. The popular list has 50 ids, all multi-character, all resolvable.
10. Reading round-trip: `reading_canonical` regenerates `reading_display` for every entry, or the entry
    is on an explicit exception list.
11. Golden-query regression: a fixed set of queries produces the expected ordered top-N ids
    (`PIPE-140`).
12. Audio keys: every `audio_word_key` exists in the audio catalogue (`DATA-172`) built in the same
    pipeline run, and every catalogue key resolves to a record in the audio packs.
13. Size budget: the dataset, its language-model companion file and every pack are at or under their
    budgets (`PERF-070`; per-flavour totals `PERF-073`).
14. Language model: every headword except Unihan-only entries (`DATA-190`) has an LM word id and a
    finite unigram; every `abbr_idx` posting and
    bigram successor references valid ids; each codebook has at most 256 entries; `has_bigram_model`
    matches the presence of bigram data; the quality gate passes (`PIPE-075`).
15. Normalisation: every matching key equals its own `SRCH-010` normalisation (`DATA-071`).
16. Source hygiene: every issue class of `PIPE-078` is at or below its previous-build count (no
    regression) and nothing was dropped without a report line.
17. Labels: every note kind, sense tag and block `label_key` has a row with all three languages in the
    label table (`DATA-060`).
18. Cantonese: the frequency table (`DATA-189`) is present and non-empty; no `segmentable` headword is
    a pronoun + 嘅 form; every `sentences-yue` record has an English translation (`DATA-188`).
19. Unihan (`DATA-190`): every Unihan-only entry has at least one sense and exactly one `unihan` block;
    no Unihan-only entry shares a headword with a CC-CEDICT, CC-Canto or HSK parser patch entry; no other
    entry has a `unihan` block.
20. Decomposition (`DATA-191`): every component id resolves (code point or synthetic id with an SVG
    glyph); expansion is acyclic and terminates at roots; every radical form maps to a unified
    ideograph; every curated primitive has a radical-name row in all three UI languages; in
    `decomposition-matches` (`DATA-197`), every sequence and node reference exists in the `decomposition` pack and
    every stroke index lies within the character's `strokes` record; `decomposition_hashes`
    (`DATA-002`) match the published files.
21. Alternative readings (`DATA-192`): every `alt` posting references an existing `also_pr` or
    `taiwan_pr` note of its entry and parses fully against the syllable inventory.

`DATA-201` `[MUST]` The build emits a machine-readable report: counts per table, index sizes, HSK
parser version and resolution counts per step from its `report.md`, HSK parser patch entries merged
and linked (`PIPE-041`, `PIPE-044`), Cantonese HSK primary-entry decisions (`DATA-187`), unresolved references, ambiguous redirects,
romanisation-parse exceptions, entries with no senses, language-model size and coverage (`PIPE-076`),
source-hygiene counts (`PIPE-078`), note-convention and tag counts (`PIPE-079`), Cantonese join counts
(`PIPE-053`: R matches, polyphones, suppressed adapted senses, dropped Mandarin blocks, `none`
entries, readings-only headwords without an entry), Cantonese frequency and example coverage
(`DATA-188`, `DATA-189`), Web changeset sizes (`PIPE-167`), alternative readings indexed and skipped
(`DATA-192`), Unihan entries, senses and readings per dataset (`DATA-190`, `PIPE-080`), decomposition
coverage, region fallbacks, unencoded and unknown parts and parts without an entry (`DATA-191`,
`PIPE-081`), stroke-match coverage — characters and sequences with stroke indices, split mismatches,
stroke-count mismatches (`DATA-191`, `PIPE-084`) —, HSK propagation counts per hop and conflicts
(`DATA-193`, `PIPE-082`), HSK multi-entry links (`PIPE-041`), HSK character-list counts and
primary-entry choices (`DATA-194`), and the diff in each of these against the previous version. A regression beyond declared thresholds
fails the build.

`DATA-202` `[MUST]` The app **validates cheaply at open**: `schema_version` in range, required tables
present, `entry_count` sane. It must not re-validate the whole dataset at start-up.

---

### 14. Delivery and update

`DATA-210` `[MUST]` A dataset, companion file or pack is replaced **atomically**. On mobile they change
only with an app update (the voice model with its own pack version, `VOICE-095`). On Web a new dataset
— whether fully downloaded or produced by applying a changeset (`DATA-216`) — is verified and written
completely to a separate file before it replaces the cached one; a partially written, partially
downloaded or partially patched file is never opened.

`DATA-211` `[MUST]` Replacing a dataset, or a lazily fetched file becoming available on Web, bumps the
app's data generation (`GLB-044`), invalidating every in-flight request and cached model; replacing a
dataset also invalidates every passage id and entry id.

`DATA-212` `[MUST]` Anything the app persists or links across dataset versions uses the natural key
(`DATA-014`), never `entry_id`. This applies to widget deep links, routes (`GLB-110`) and any future
bookmark feature.

`DATA-213` `[SHOULD]` On mobile, datasets are shipped as whole files inside the app update; no delta
format is used there. Deltas exist only for the Web offline dataset (`DATA-216`), where the transfer
size matters.

`DATA-214` `[SHOULD]` The app supports N and N-1 `schema_version` so a Web deployment can still open
a dataset cached in OPFS by the previous deployment until the new one is fetched. A changeset never
crosses a `schema_version` change; that always takes a full download.

`DATA-215` `[MUST]` **Delivery** (decided: `Q-090`, `Q-091`).
- **Mobile**: both datasets, their language-model companion files, all asset packs and every
  non-dataset asset (`DATA-170`) **except the voice model** are installed with the app — Google Play:
  base module plus install-time asset packs; direct APK: bundled inside the APK; iOS: inside the app
  bundle. The voice model is the only separate delivery: an on-demand Play Asset Delivery pack
  downloaded by Play when Voice is first opened, an Apple-hosted Background Assets pack on iOS, and
  bundled in the direct APK (`VOICE-095`). Those downloads are performed by the store's mechanism; the
  app itself makes no network requests (`PROD-071`).
- **Web**: **the offline set is exactly the app shell, the dataset, the OCR model and the
  decomposition data.** The dataset for a mode is fetched on first use of
  that mode — including when a mode link (`/zh/…`, `/yue/…`) opens a route in a mode whose dataset is
  not yet cached, without changing the saved mode preference (`GLB-110`) — verified against the SHA-256 in the build manifest (`PIPE-150`), stored in OPFS with
  persistent storage requested, and kept current through changesets (`DATA-216`, `ARCH-145`).
  The OCR model is stored offline once downloaded and versioned by its own manifest (`DATA-196`).
  **Decomposition data** (`DATA-191`): the first time the entry screen's decomposition section (Characters,
  `ENT-120`…) loads, the `decomposition` pack and the `decomposition-matches` file (`DATA-197`) are
  fetched whole, verified against the build manifest and stored in OPFS beside the dataset; each `strokes` record the section renders
  (the glyph's stroke graphics) is stored there too as it is fetched. From then on that section works
  offline — for a character whose stroke record was never fetched, offline, the glyph is drawn from
  the font with the operator-region highlighting of `DATA-191`. These stored files are **versioned
  with the dataset**: when the dataset is updated (`DATA-216`), the stored decomposition files and
  stroke records are re-fetched **only when their hash in the new dataset's `decomposition_hashes`
  (`DATA-002`) differs** from the stored copy's, then verified and swapped in atomically (`DATA-210`);
  until then, or when that fetch fails, the previous copy stays in use (it holds only code points,
  synthetic ids and stroke indices, never entry ids, so it remains usable with a newer dataset — the
  one exception to the version check of `DATA-006`). Every other Web file — the language-model
  companion file (fetched on the first query that needs it, `SRCH-050`, `SRCH-060`; single-word
  results never wait for it), the asset packs (audio, `strokes` records not yet stored for
  decomposition, sentences), the handwriting model and the OpenCC dictionaries (`DATA-195`) — is
  fetched on demand, verified against the manifest (or `DATA-155` `lm_file`), discarded on mismatch,
  held in the browser cache, and **not guaranteed offline** (`GLB-035`; media gaps are covered by TTS,
  `MED-011`).

`DATA-216` `[MUST]` **Web dataset changeset format.** For each release the pipeline publishes, per
dataset, changesets that turn a recent previous version into the new one (`PIPE-167`), plus a small
**manifest**:

| Manifest field | Notes |
|----------------|-------|
| `dataset_id` | `mandarin` / `cantonese` |
| `version` | the new `dataset_version` |
| `schema_version` | of the new version |
| `content_hash` | of the new version (`DATA-006`) |
| `full` | URL, size and SHA-256 of the full dataset file |
| `changesets` | one per supported base: `base_version`, URL, size, SHA-256 |
| `rebase` | true when the release compacted ids (`DATA-013`); no changesets are offered |

A changeset is a set of **row-level** operations over every table of the dataset — upsert and delete,
keyed by each table's primary key (entry id for `entry`, the key column for index tables, which are
replaced as whole rows). Encoding `[REC]`: an SQLite session-extension changeset when the Web SQLite
build includes the session extension, otherwise an equivalent own record stream (table, operation,
key, row values) in the dataset's binary conventions (`DATA-143`); the choice is fixed per
`schema_version`.

The client: reads the manifest; when it holds a listed `base_version`, downloads that changeset,
verifies its SHA-256, applies it to a **copy** of the cached file, verifies the result's
`content_hash` against the manifest (computed off the main thread with the canonicalisation shared in
`contract/`), and swaps it in atomically (`DATA-210`); otherwise — its version is not a listed base
(more than N versions behind, N being pipeline configuration; or the changeset was not worth
publishing, `PIPE-167`), a `rebase`, a `schema_version` change, or any verification or apply failure —
it downloads the full file. A failed update never damages the cached dataset, which
stays usable offline.

---

### 15. Character data: Unihan and decomposition

`DATA-190` `[MUST]` **Unihan as a fallback source** (built by `PIPE-080`; `ENT-126`, `SRCH-079`).
The Unicode Character Database's **Unihan** data (Unicode License v3, `Unicode-3.0`; notice kept in
the attributions, `SET-040`) is a dictionary source in both datasets, used **only as a fallback**:
- **Unihan-only entries.** Every CJK unified or compatibility ideograph (Unihan of the pinned Unicode
  version — Unicode 18: 102,999 ideographs, URO, Ext A–J, compatibility) that has a `kDefinition`
  and is **not** a headword (either script) of any CC-CEDICT, CC-Canto or HSK parser patch entry of that
  dataset becomes a **full entry**: searchable (ranked after dictionary entries, `SRCH-079`),
  openable and tappable everywhere, with `is_unihan` true (`DATA-010`) and one `unihan` block
  (`DATA-022`) labelled "Unicode (Unihan)". A character that has any dictionary entry — including a
  redirect-only variant entry — gets **no** Unihan senses: the Unihan definition is never shown beside
  a CC-CEDICT, CC-Canto or HSK parser patch definition. The count is measured per build (estimate 20k+) and
  reported.
- **Senses**: `kDefinition` split into senses at `;`; commas stay inside a sense; `U+XXXX` mentions
  become `REF` segments to that character (`DATA-050`); source `unihan` (`DATA-020`); English tokens
  per `SRCH-013`, indexed with the Unihan flag (`DATA-076`). Since Unihan senses exist only on
  Unihan-only entries, `kDefinition` text of characters that have a dictionary entry is neither
  stored nor indexed.
- **Readings**: Mandarin dataset — the first `kMandarin` value (the zh-Hans reading) is the entry's
  reading; a different second value (the zh-Hant/Taiwan reading) becomes a `taiwan_pr` note; when
  `kMandarin` is absent, the first `kHanyuPinyin` reading is used and further distinct ones become
  `also_pr` notes. Cantonese dataset — the first `kCantonese` value is the reading and further values
  become `also_pr` notes (one entry per character, not one per Jyutping, since Unihan gives one
  definition per character). `reading_source` is `unihan`; a character without a reading for the
  dataset's language has `reading_source` `none` (`DATA-010`, as in `DATA-182`). Alternative readings
  feed `DATA-192`. No reading is ever derived from another language's field.
- **Script forms**: both fields carry the character (`DATA-012`), except that a single
  `kSimplifiedVariant` (resp. `kTraditionalVariant`) value different from the character fills the
  simplified (resp. traditional) field.
- **Ranking and language model**: `frequency_rank` is the unranked sentinel (lowest), never filled
  from an LM unigram (`DATA-111`). Unihan-only entries are **never language-model or combination
  edges**: they have no LM word id (`lm_word_id` is the sentinel `-1`, `lm_unigram` the codebook's
  lowest code), no bigrams and no `abbr_idx` postings, an exception to `DATA-151` and `DATA-152`, so
  abbreviated input and combinations (`SRCH-050`, `SRCH-060`) never produce them; they are found by
  Hanzi, full or toneless reading (including alternative readings, `DATA-192`) and English.
- **Character metadata**: `kRSUnicode` (radical and residual strokes) and `kTotalStrokes` are stored
  for **every** character in the decomposition pack (`DATA-191`), not only for Unihan-only ones.
- **Radicals and strokes**: the Kangxi Radicals, CJK Radicals Supplement and CJK Strokes blocks are
  not in Unihan. They are mapped to their unified ideograph through UCD
  `EquivalentUnifiedIdeograph.txt` and `CJKRadicals.txt` (same Unicode version as Unihan): searching
  the radical form finds that ideograph's entries (`DATA-071`), and decomposition shows the ideograph's
  entry with a curated radical name (`DATA-191`).

`DATA-191` `[MUST]` **Character decomposition table** (built by `PIPE-081`; `ENT-120`…`ENT-125`).
The `decomposition` pack (`DATA-170`), shared by both datasets, holds for each character:
- **IDS per region** from BabelStone `IDS.TXT` (Andrew West; free use for any purpose, no permission
  or attribution required — credited anyway, `SET-040`; file date pinned): the Ideographic
  Description Sequence for the source regions G, T and H plus the source's default sequence. The app
  picks **G** for the Simplified script, **T** for Traditional in Mandarin mode and **H** in Cantonese
  mode, falling back to the default sequence when the region has none. Each sequence is stored parsed
  — operator plus component ids, nested — never as text to be re-parsed.
- **Component ids**: a code point, or a **synthetic id** for an unencoded component (`{nn}` in the
  source), which carries a bundled **SVG glyph** and the label `unencoded_component`
  ("Unencoded component"); it has no reading, no definition and no entry.
- **Root rule**: a component is a **root** (not expanded further) when its own IDS is the character
  itself, when it is a single stroke (CJK Strokes block, U+31C0…), or when it is a **curated
  primitive** — the 214 Kangxi radicals and their positional forms (氵 扌 亻 忄 艹 ⺍ …), listed in a
  committed data file (`PIPE-081`). Expansion to strokes is not offered in v1. A source `？` part is an
  "unknown part" (`unknown_component`), shown and not expanded.
- **Radical forms** (U+2E80–U+2FDF): mapped to the unified ideograph (`DATA-190`); the part shows that
  ideograph's entry plus a **curated radical name** in the UI languages (氵 → 三点水 / 三點水, "water"),
  from a committed, reviewed data file. `〾X` (variation indicator) links to X. Overlay (⿻), subtraction
  (㇯) and mirror operators are stored as such; the app shows their parts without positional claims.
- **Character metadata**: radical number and residual strokes (`kRSUnicode`) and total strokes
  (`kTotalStrokes`, `DATA-190`); for a component that has no entry in a dataset, its Unihan readings
  (`kMandarin`, `kCantonese`).

Coverage: every character of either dataset's headwords, every Unihan-only entry and, recursively,
every component reached from them. Readings and short definitions of parts are **not** duplicated in
the pack: the app looks each encoded part up in the active dataset's headword index (one batched
lookup per expansion level, `ARCH-020`) and shows the part's reading and first sense from its
CC-CEDICT, CC-Canto, HSK parser patch or Unihan entry; a part with no entry (no `kDefinition` and no
dictionary entry) shows its glyph and, when Unihan has one, its reading from the pack, and is not
tappable. Licences: the pack carries the BabelStone terms, the Unicode licence for the UCD-derived
mappings and metadata, and CC BY-SA 4.0 for the project's radical names and SVG glyphs; the
`decomposition-matches` file carries LGPL-3.0 and the Make Me a Hanzi notice (`PIPE-018`). On Web the
pack, the matches file and the stroke records it uses are stored offline after first use (`DATA-215`).
Character origins (etymology, historical forms) remain deferred; the decomposition does not depend on
them.

`DATA-197` `[MUST]` **Stroke-to-part matches** (built by `PIPE-084`; `ENT-124`, `ARCH-200`). So that
the app can highlight which strokes of the glyph each part occupies, a separate file, `decomposition-matches` (`DATA-170`), holds data derived
from Make Me a Hanzi `dictionary.txt` (about 9k characters; **LGPL-3.0**, kept as its own replaceable
file and never merged into the `decomposition` pack or the dataset). For each covered character it
stores, per IDS sequence of the `decomposition` pack (G, T, H, default) **whose component split equals
Make Me a Hanzi's `decomposition`** for that character (compared after radical-form mapping,
`DATA-190`, recursively for the parts Make Me a Hanzi decomposes further), the **stroke indices of each
part** of that sequence's tree, obtained from the `matches` field (stroke → component path). Stroke
indices refer to the character's stroke list in the `strokes` pack (`DATA-170`), which is the stroke
graphics drawn for the glyph; the pipeline keeps a character only when Make Me a Hanzi's stroke count
equals that record's. A sequence that does not equal Make Me a Hanzi's split, a character without
`matches`, and a part that no stroke's match path leads to carry no stroke indices: for them the app
derives an **approximate region** from the IDS operators on the character's bounding box (⿰ left /
right halves, ⿱ top / bottom halves, ⿲ / ⿳ thirds, ⿴ ⿵ ⿶ ⿷ ⿸ ⿹ ⿺ outer enclosure and inner
rectangle; any other operator the whole box), computed in shared app code
(`ARCH-200`), not stored. The dataset metadata records the hashes of the `decomposition` pack, this file
and the `strokes` pack (`DATA-002` `decomposition_hashes`).

### Deferred (post-v1) and withdrawn — Data Contract

- `origins` pack (former `DATA-170` row: etymology notes, historical glyphs) — deferred with Character
  origins. Decomposition (IDS and components) is no longer part of it: it ships in v1 as the
  `decomposition` pack (`DATA-191`).
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
something it may legitimately leave open (a redirect target), it records the ambiguity explicitly in
the dataset (a null target) and the app has a defined behaviour for it; what must resolve (every HSK
link, `DATA-124`) fails the build instead. It must never leave the app to
guess.

---

### 2. Sources

#### 2.1 Mandarin

`PIPE-010` `[MUST]` **CC-CEDICT** (MDBG distribution) is the primary Mandarin source: traditional form,
simplified form, numbered Pinyin, and a `/`-separated list of glosses. Reference build: the 2026-07-04
release, 124760 usable records after filtering, CC BY-SA 4.0.

`PIPE-011` `[MUST]` **FrequencyWords** `zh_cn` (OpenSubtitles 2018 via OPUS) supplies word frequencies.
Reference: 41319 forms, corpus total 81775831, CC BY-SA 4.0.

`PIPE-012` `[MUST]` **HSK data: the HSK parser repository** (`PROD-095`; decided: `Q-102`, `Q-121`).
HSK data is not parsed from the syllabus or joined to CC-CEDICT by this pipeline. It comes from the
user's repository `https://github.com/Punpuf/hsk-syllabus-vocabulary-parser` (code MIT, not shipped;
CC-CEDICT-derived data CC BY-SA 4.0), consumed at a **pinned version** — its commit or tag and the
SHA-256 of every consumed output, in the lock file entry of `REL-042` (`PIPE-162`). That repository
parses the official syllabus PDF of the **HSK 3.0, 2026 update** (新版HSK考试大纲, CTI; syllabus
published 2025-11, in force 2026-07; decided: `Q-110`): 11,000 words, new per level 300 / 200 / 500 /
1,000 / 1,600 / 1,800 / 5,600 for levels 1–6 and 7-9. Consumed outputs:
- `hsk_word_list.tsv` — one row per syllabus word and level: `word_index`, `level`, `word`, `pinyin`,
  `part_of_speech`, `pinyin_numbered`, `pinyin_cc-cedict`, `traditional_cc-cedict`,
  `definition_cc-cedict` — levels, per-level parts of speech, the syllabus key (word, syllabus
  pinyin, homograph index; `DATA-120`) and the **resolved CC-CEDICT entry** (simplified `word`,
  `traditional_cc-cedict`, `pinyin_cc-cedict`). Its resolution order is exact numbered Pinyin →
  unique tone-insensitive match → the overrides of `data/disambiguation.tsv` → the patch fallback
  `data/cedict_patch.u8` (CC-CEDICT format); an unresolved word is a hard error there. The
  `definition_cc-cedict` column is ignored — **no glosses** (decided: `Q-032`); definitions come
  from the dataset entries;
- `report.md` — the parser's resolution report, copied into the build report (`DATA-201`);
- `data/disambiguation.tsv` — the reviewed overrides, recorded for the build report only;
- `data/cedict_patch.u8` — the patch entries merged into both datasets (`PIPE-044`);
- the **recognition and writing character lists** with their levels (3,088 recognition characters,
  1,200 writing characters; `DATA-194`);
- the **grammar rules per level** and per-level external grammar-lesson sources, consumed only by the
  v2 grammar build (`PIPE-083`).
Only facts are used; the official syllabus document is not redistributed, and CTI and the parser
repository are cited (`SET-040`). A parser release is adopted by a reviewed pull request that bumps
the lock entry and passes every HSK gate (`DATA-200` gate 8).

#### 2.2 Cantonese

`PIPE-013` `[MUST]` **CC-Canto** (`cccanto-webdist.txt`, release 2017-02-02, 34,334 lines, CC BY-SA)
supplies Cantonese (Jyutping) readings and Cantonese-specific definitions. It is keyed on
(traditional, Jyutping): its Pinyin field is not a reliable join key (empty on 59 lines; in the 8,882
lines tagged `# adapted from cc-cedict`, 571 of 576 polyphones use one Pinyin for every Jyutping) and
is used only to resolve its Pleco-style references (`PIPE-079`).

`PIPE-014` `[MUST]` **CC-CEDICT Cantonese readings** (`cccedict-canto-readings-150923.txt`, release
2015-09-23, 105,862 lines, Jyutping only, no definitions) supplies Jyutping for CC-CEDICT entries,
keyed (traditional, simplified, Pinyin) and joined on (traditional, normalised Pinyin) for
single-reading headwords only (`DATA-182`, `PIPE-053`). The file is **stale**: 4,977 of its headwords
have since left CC-CEDICT, and 24,931 current CC-CEDICT entries find no reading in it, so together
with CC-Canto 19,905 current headwords have no Jyutping at all (`Q-115`). Its gaps are reported, never
filled by inference (`PIPE-078`).

`PIPE-015` `[MUST]` CC-CEDICT itself is a Cantonese source too: its English glosses remain useful for
Cantonese entries (`DATA-182`).

`PIPE-016` `[MUST]` The Cantonese frequency table is built from licensed corpora by `PIPE-077`
(`DATA-189`; decided: `Q-081`).

#### 2.3 Asset and language-model sources

`PIPE-017` `[MUST]` Example sentences: Mandarin — Tatoeba eng-cmn pairs, converted between scripts
with OpenCC at build time; Cantonese — Tatoeba `yue` sentences (`yue_sentences.tsv`) with English
from Tatoeba's links and English sentences exports, all pinned (`DATA-188`, CC BY 2.0 FR). Recorded
audio (audio-cmn / Shtooka), stroke data (hanzi-writer-data), the OFL CJK fallback font (`DATA-170`).
Language-model and Cantonese corpus sources: `PIPE-070`, `PIPE-077`. Character sources: Unihan and
the UCD radical mappings (`PIPE-080`), BabelStone IDS (`PIPE-081`), Make Me a Hanzi `dictionary.txt`
for stroke-to-part matches (`PIPE-084`). The HSK parser patch (`PIPE-012`, `PIPE-044`). Full
attribution table: `SET-040`.

`PIPE-018` `[MUST]` **Licences** (decided: `Q-100`, `Q-103`). The app code is **Apache-2.0**; datasets,
models and other data ship as **separate files under their own licences** and are never compiled into
app code:
- each **derived dataset** (main file, Mandarin and Cantonese) is published under **CC BY-SA 4.0**,
  the licence of CC-CEDICT and CC-CEDICT-derived inputs, with attribution to every source;
- the **Mandarin language-model companion file** (`DATA-170`), derived from the libime model
  (`PIPE-070`), is a separate, replaceable file under **LGPL-2.1-or-later**; the Cantonese one is
  CC BY-SA 4.0 (`PIPE-074`, `PIPE-077`);
- asset packs and non-dataset assets keep their sources' licences (for example Tatoeba CC BY 2.0 FR,
  the Arphic-licensed stroke data, CC BY-SA Cantonese tables, the OFL fallback font, the Apache-2.0
  OpenCC dictionaries; `DATA-170`);
- the **Unihan** data and UCD radical mappings (`PIPE-080`) are under the Unicode License v3
  (`Unicode-3.0`), compatible with the CC BY-SA dataset; the notice is kept in the attributions;
- the `decomposition` pack (`DATA-191`) combines BabelStone IDS (free use, no attribution required;
  credited anyway), Unicode-3.0 mappings and the project's own radical names and component glyphs
  (CC BY-SA 4.0); the copyleft decomposition sources CHISE and CJKVI IDS (GPL) are **not** used;
- the `decomposition-matches` file (`DATA-197`, `PIPE-084`), derived from Make Me a Hanzi
  `dictionary.txt`, is a **separate, replaceable file under LGPL-3.0**, never merged into another file;
- the **HSK parser patch** entries (`data/cedict_patch.u8`, `PIPE-044`) are CC-CEDICT-format data
  under **CC BY-SA 4.0**, merged into both datasets as a labelled source credited as "HSK parser
  patch"; the parser's MIT code is not shipped.

Licence compatibility is checked at build time and the resolved licence text and source metadata are
emitted into each file (`DATA-002`, `SET-042`). Any further copyleft source needs an explicit
allow-list decision; the libime model and the Make Me a Hanzi `matches` data are on that allow-list
(decided: `Q-103`; user decision 2026-10-12). Non-commercial or
unclear-rights sources are never used in v1 (`PIPE-077`). The only planned exception is v2 grammar
content (`PIPE-083`): non-commercial-licensed lessons may be imported there, but only as separately
licensed data files, flagged per item and excludable by a build flag.

#### 2.4 Source hygiene and note conventions

`PIPE-078` `[MUST]` **Source data hygiene: normalise, repair, report.** Every dictionary source passes
a hygiene stage before any join. Each issue class is **normalised** when the fix is mechanical,
**repaired** from a reviewed, hash-bound override file when it is not, and otherwise the record is
quarantined — never silently dropped. Every class is counted per source in the build report
(`DATA-201`), and a count above the previous build's fails the build (`DATA-200` gate 16) unless the
pull request raises the baseline explicitly. Classes and counts in the pinned releases (C = CC-CEDICT
2026-10-09, K = CC-Canto 2017-02-02, R = readings file 2015-09-23):

| Issue | Count | Treatment |
|-------|-------|-----------|
| Pinyin written without syllable spaces | R 1,917 · K 606 | normalise: split against the Pinyin inventory, then join |
| Non-standard Jyutping tokens (full-width letters; embedded markup such as `{jat1 檔:dong2}`) | R 443 · K 213 | normalise full-width to ASCII; repair or quarantine the rest |
| Syllable count ≠ character count (where both are Hanzi) | R 7 · K 21 | repair via override, else quarantine the reading |
| Empty Pinyin field | K 59 | accept (CC-Canto is keyed on Jyutping, `PIPE-013`); report |
| Simplified characters in the traditional field | K 89 | repair: map via CC-CEDICT / OpenCC `s2hk`, report each |
| Stray, doubled or trailing spaces in fields | 3,819 | normalise |
| Duplicate lines | count per source | collapse to one; report |
| Malformed line (one in K, `陳慧琳`) | 1 | repair via override or quarantine |
| R headwords no longer in CC-CEDICT | 4,977 | produce no entry unless K defines them (`DATA-182`); report |
| C headwords without any Jyutping | 19,905 | `reading_source` `none` per `Q-115` (`DATA-182`); report |

Readings are never inferred to fill a gap: in particular, no Pinyin → Jyutping mapping is derived from
R for single characters (`DATA-182`).

`PIPE-079` `[MUST]` **Note-convention parser and label table** (decided: `Q-020`, `Q-108`). One parser
turns the conventions catalogued in `DATA-060` into notes, sense tags, references and senses, for both
CC-CEDICT and CC-Canto, and emits the label table (`DATA-060`) from a reviewed data file in
`contract/` (en / zh-Hans / zh-Hant per key). Rules:
1. **Structural conventions** (`see`, `variant of`, `CL:`, `M: `, `abbr. for`, `also pr.`, `Taiwan
   pr.`, `also written`, `used in`, …) become notes or structural sense tags per `DATA-060`, with their
   references resolved (`PIPE-026`).
2. **Allow-listed tags:** a leading parenthesised tag on the allow-list (about 60) is moved into the
   sense's `tags`; anything else stays inline. The allow-list and its labels are data; adding a tag
   needs no app release.
3. **CC-Canto specifics:** trailing register brackets (`[colloquial]` 2,406, `[written and
   colloquial]` 703, `[written]` 76) are stripped into tags **before** reference scanning; POS
   prefixes (`(noun)`, `(verb)`, `(adjective)`, `(adverb)`, `n.`, `v.`, `adj.`; 2,968) become
   `pos_*` tags; numbered senses inside one definition (591) are split into separate senses; Pleco-style
   classifiers `M: 个gè [个]` become `classifier` notes.
4. **CC-Canto references** (`GLB-014`): the only structured shape is Pleco-style `H+pīnyīn [H+]` (769
   occurrences in 586 entries, all in adapted lines), matched by
   `(H+)([a-züāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜ']+) \[(H+)\]`. The Hanzi are simplified even under traditional
   headwords, so they are mapped to traditional through CC-CEDICT and resolved on (Hanzi, toneless
   Pinyin) to the Cantonese entry carrying that Mandarin entry's senses (768 of 769 resolve); the
   result is a `REF` with `romanization` `pinyin`. In original CC-Canto senses, bare Hanzi runs
   (quoted or not) become a `REF` only on an exact headword match in either script (107 of 158 runs);
   otherwise they stay `TEXT`, and a parenthesised Jyutping after a run is kept verbatim. No pipe,
   CEDICT `[pin1]` or `{jyutping}` reference form exists in CC-Canto.
5. Counts of every note kind and tag are reported and diffed (`DATA-062`, `DATA-201`); keys without a
   complete label row fail the build (`DATA-200` gate 17).
6. **Alternative readings** (`DATA-192`): the bracketed reading of every `also_pr` and `taiwan_pr`
   note (CC-CEDICT Pinyin; CC-Canto Pinyin and, where given, Jyutping) is normalised to canonical
   numbered form with the reading rules of `PIPE-021` / `PIPE-078`, and, when it parses fully and has
   as many syllables as the entry's main reading, emitted as reading-index and `abbr_idx` postings
   flagged `alt` (kind and note ordinal), skipping exact duplicates of the main reading. A note with
   several readings yields one posting per reading. Indexed, partial and unparseable readings are
   counted per kind and source (`DATA-201`). (Mandarin readings are indexed in the Mandarin dataset,
   Jyutping readings in the Cantonese dataset; a Mandarin `also_pr` carried in a Cantonese entry's
   `mandarin` block is not a Jyutping key.)

`PIPE-080` `[MUST]` **Unihan import** (`DATA-190`). Inputs, pinned in the lock file (`PIPE-162`) at one
Unicode version: `Unihan.zip`, `EquivalentUnifiedIdeograph.txt` and `CJKRadicals.txt`. Steps:
1. Read `kDefinition`, `kMandarin`, `kCantonese`, `kHanyuPinyin` (used only when `kMandarin` is
   missing), `kSimplifiedVariant`, `kTraditionalVariant`, `kRSUnicode` and `kTotalStrokes`; ignore
   every other field.
2. Normalise readings: `kMandarin` / `kHanyuPinyin` tone-marked Pinyin → canonical numbered Pinyin
   (`PIPE-021`); `kCantonese` Jyutping as given; a reading that does not parse against the dataset's
   inventory is dropped and counted.
3. After the CC-CEDICT, CC-Canto and HSK parser patch entries of a dataset are built, create a Unihan-only
   entry for every ideograph with a `kDefinition` that is not a headword in either script of that
   dataset (`DATA-190`), with senses, readings, notes and script forms as defined there.
4. Map Kangxi Radicals, CJK Radicals Supplement and CJK Strokes code points to unified ideographs from
   `EquivalentUnifiedIdeograph.txt`, cross-checked with `CJKRadicals.txt`; emit the `radical_form`
   headword keys (`DATA-071`) and pass the mapping to `PIPE-081`.
5. Report: ideographs read, `kDefinition` count, Unihan-only entries per dataset, entries without a
   reading per dataset, alternative readings, unmapped radical forms (`DATA-201`). Gate: `DATA-200`
   gate 19.

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

`PIPE-025` `[MUST]` Parse every sense's markup into segments (`DATA-050`) and its conventions into
notes and tags (`PIPE-079`), classifying each bracketed
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

The HSK join to CC-CEDICT is **not** implemented here: it is done by the pinned HSK parser repository
(`PROD-095`, `PIPE-012`), whose outputs this build consumes and verifies.

`PIPE-040` `[MUST]` Read the parser's `hsk_word_list.tsv` at the pinned version (SHA-256 checked,
`PIPE-162`). For each row take the syllabus key (word, syllabus pinyin, homograph index; `DATA-120`),
the level and its parts of speech, and the resolved CC-CEDICT form (`word`, `traditional_cc-cedict`,
`pinyin_cc-cedict`). A word with several levels — separate syllabus rows, or bracketed extra levels
(`1（4）` with `数、（副）` → main level 1 `数`, extra level 4 `副`) — keeps **each level with its own
parts of speech** (`DATA-120`, `DATA-126`); the part-of-speech field is split on the ideographic comma
and de-duplicated per level. The syllabus pinyin is used only as part of the key; the
`definition_cc-cedict` column is ignored (`Q-032`).

`PIPE-041` `[MUST]` **Resolve every row to dataset entries.** The row's primary Mandarin entry is the
entry whose natural key (`DATA-014`) is the resolved form (`traditional_cc-cedict`, `word`,
`pinyin_cc-cedict` normalised to canonical numbered Pinyin, `PIPE-021`) — a CC-CEDICT entry of the
pinned release (`PIPE-010`) or an HSK parser patch entry (`PIPE-044`). A resolved form that names no
entry — for example because the parser resolved against a different CC-CEDICT release than the one
pinned here — **fails the build** (`DATA-124`, `DATA-200` gate 8); the fix is a parser release or a
CC-CEDICT pin bump, never a local override. The parser's CC-CEDICT release and its `report.md`
resolution counts are copied into the build report (`DATA-201`). The Cantonese link is `DATA-187`.

Multiple links: where the syllabus word stands for several CC-CEDICT entries with **different
traditional forms** (后/後, 回/迴, 干/乾/幹; `HSK-050`), the row also links every other CC-CEDICT entry
with the same simplified form and the same tone-exact reading that is neither capitalised (proper noun
or surname) nor pure-variant (`also_entry_ids`, `DATA-120`); the parser's resolved entry stays the
primary. Every such link is listed in the build report for review. A row resolved to a pure-variant
entry (erhua forms such as 一点儿 → 一点) links to that variant entry, whose resolved redirect shows the
target's senses (`DATA-056`).

`PIPE-043` `[MUST]` De-duplicate rows per (level, syllabus key), merging parts of speech within that
level only (`DATA-123`); polyphones (还 hái / huán) and homograph-indexed words (本1 / 本2) stay separate
rows. The earlier reference list had 14 collisions; the count is reported per build.

`PIPE-044` `[MUST]` **Every row links; zero allowance** (decided: `Q-118`, `Q-121`). Rows that
CC-CEDICT cannot serve are resolved inside the parser repository — by its reviewed overrides
`data/disambiguation.tsv` or by an entry of its patch `data/cedict_patch.u8` — never by an override
file in this repository. **HSK parser patch entries** (for example 精彩纷呈, absent from
CC-CEDICT) are parsed with the CC-CEDICT parser (`PIPE-020`…`PIPE-025`, `PIPE-079`) and merged into
**both** datasets as a labelled source: `reading_source` `hsk_patch`, senses of source `hsk_patch` and
an `hsk_patch` block labelled "HSK parser patch" (`DATA-022`), CC BY-SA 4.0 (`PIPE-018`, `SET-040`).
In the Cantonese build a patch entry is treated like a CC-CEDICT entry for the Jyutping join
(`PIPE-053`), normally ending as a `reading_source` `none` entry with the `hsk_patch` block in place of
the `mandarin` block. A patch entry whose natural key is also a CC-CEDICT entry of the pinned release
fails the build (`DATA-200` gate 8); the scheduled update (`PIPE-165`) flags it so the patch entry is
removed in the parser repository. A patch entry that no row links to is reported. Any row that does not
resolve **fails the build**; the fix is in the parser repository (a disambiguation line or a patch
entry), never an unlinked row, a fallback card or a syllabus-sourced dictionary entry (`DATA-124`).
The reference dataset shipped 9 unlinked rows.

`PIPE-045` `[MUST]` Emit the sort keys and store rows in final order (`DATA-121`).

`PIPE-046` `[MUST]` Emit the widget projection (`DATA-130`) for each dataset, covering every row, and
the HSK character table (`DATA-194`) from the parser's recognition and writing character lists:
validate the per-level counts and resolve each character's primary single-character entry and sort key
in each dataset (`DATA-200` gate 8).

`PIPE-082` `[MUST]` **HSK propagation through variants** (`DATA-193`, `HSK-097`). After the HSK rows
are linked: build an undirected graph over CC-CEDICT and HSK parser patch entries whose edges are resolved
`variant_of`, `old_variant_of` and `erhua_variant_of` relations (notes and redirects, `DATA-055`,
`DATA-060`); from every entry with `hsk_levels`, walk at most **two hops**; assign to each reached
entry without its own `hsk_levels` the levels and per-level parts of speech of the source with the
lowest level (ties: fewer hops, then lower source id) as `hsk_levels_via_variant`. Run once on the
Mandarin build and copy the result onto the Cantonese entries carrying each receiving entry
(`DATA-193`). Report the entries reached per hop, single characters versus words, erhua cases,
conflicts and the hop-3 count (expected 0; a non-zero count is reported, not propagated). Baseline
(2026-10): 630 entries (622 hop 1, 8 hop 2), 2 conflicts.

---

### 5. Cantonese build

`PIPE-050` `[MUST]` Build the Cantonese headword space as the **union** of CC-Canto headwords and all
CC-CEDICT headwords (those without any Jyutping become `reading_source` `none` entries per `Q-115`,
`DATA-182`). Readings-file headwords contribute readings only; one with no definition in either
dictionary produces no entry (`DATA-182`). All sources pass `PIPE-078` first.

`PIPE-051` `[MUST]` The entry key is (traditional, simplified, Jyutping reading). One Chinese word with
several Cantonese readings yields several entries (`DATA-184`).

`PIPE-052` `[MUST]` Sense assembly follows `DATA-182`, `DATA-183` and the block order of `DATA-022`:
original CC-Canto senses, then adapted CC-Canto senses, then the Mandarin block. Within the Cantonese
blocks senses are de-duplicated by normalised text; for a **monophone** (one Jyutping) an adapted
sense whose normalised text equals a sense of the **current** CC-CEDICT release for the headword is
suppressed (`ENT-114`) — adapted senses of a single-character polyphone are never suppressed
(`DATA-182`; decided: `Q-118`) — and the
`[SHOULD]` ≥ 90 % overlap rule may drop the Mandarin block (`DATA-022`, `ENT-115`). Each suppression
and drop is counted. (Among headwords with original CC-Canto entries that also have CC-CEDICT senses,
4,745 in all, a rough heuristic found 15 % duplicate, 17 % partial overlap and 68 % complementary.)

`PIPE-053` `[MUST]` **Cantonese join** (decided: `Q-082` option (a), refined by the source analysis):
1. Normalise C and R Pinyin (lowercase, `u:`/`v` → `ü`, syllable spacing per `PIPE-078`).
2. **Single-reading headwords** (one C entry; almost every multi-character word): join C → R on
   (traditional, normalised Pinyin) to obtain the Jyutping; the C entry's senses form the Mandarin
   block, labelled with its Pinyin. CC-Canto entries with the same (traditional, Jyutping) merge into
   that entry; other CC-Canto readings of the headword become their own entries carrying the same
   Mandarin block.
3. **Single-character polyphones** (several C entries, or several Jyutping): the Cantonese entries are
   CC-Canto's per-Jyutping entries (original and adapted lines, adapted senses kept in full,
   `PIPE-052`); each carries one headword-level
   Mandarin block with the senses of **all** C entries for the character in frequency order, each
   group introduced by its Pinyin. R is **not** used to pair Pinyin with Jyutping. A polyphone that
   CC-Canto does not cover takes its distinct Jyutping readings from R as entries carrying only that
   Mandarin block, and is counted.
4. **Multi-character headwords with several C entries** (rare): join each C entry to R as in 2; when
   two C entries yield the same Jyutping, the entry carries both as reading groups of its Mandarin
   block in frequency order.
5. Headwords with no Jyutping from K or R: `DATA-182` (`Q-115`).
6. Resolve each headword's primary Cantonese entry (`DATA-182`) for the English index (`DATA-076`)
   and the HSK link (`DATA-187`).

Report counts per path (R matches — 100,313 of 125,244 C entries in the 2026-10-10 analysis —,
polyphones, uncovered polyphones, multi-entry merges, `none` entries).

`PIPE-054` `[MUST]` Do **not** assume the reference app's joining logic is correct — it has none
(Cantonese does not exist in the reference app). This join is designed fresh from `DATA-180` to
`DATA-187`.

`PIPE-055` `[MUST]` Emit the Jyutping syllable inventory, alias map and abbreviation table as data
(`DATA-070`, `DATA-150`), validated by parsing every entry's reading against them.

`PIPE-056` `[MUST]` Emit `has_hsk = true` and the HSK tables built by `DATA-187` (each row linked to
the primary Cantonese entry, `DATA-182`, carrying its Mandarin row's CC-CEDICT entry; Jyutping via
that entry),
reusing the read, de-duplicated, resolved Mandarin rows (`PIPE-040`…`PIPE-044`) and applying
`PIPE-045` and `PIPE-046`; any row without a link fails the build (`DATA-200` gate 8).

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

`PIPE-065` `[MUST]` **Sentences.** Build the word index and sentence store per pack; convert scripts
with OpenCC at build time; rank each word's sentences per `MED-047` (decided: `Q-062`) — sentences in
which the headword is a standalone word of the segmented sentence first, then the others, shorter
first within each group, sentence id breaking ties — and keep the top 20. Mandarin (`sentences`): eng-cmn
pairs, indexed by simplified headword. Cantonese (`sentences-yue`, `DATA-188`): select the Tatoeba
`yue` sentences of at most 20 characters, attach English through the pinned Tatoeba links and English
sentences exports, exclude sentences without an English translation, segment with the Cantonese
headword list (pronoun + 嘅 forms excluded, `DATA-189`), and index by traditional headword. Report
selected, excluded-untranslated and per-word coverage counts.

`PIPE-081` `[MUST]` **Decomposition build** (`DATA-191`). Inputs: BabelStone `IDS.TXT` (pinned by
file date and SHA-256 in the lock file; about 97.7k entries, Unicode 16), the radical mappings of
`PIPE-080`, and committed data files (`PIPE-161`): the **curated primitive list** (214 Kangxi radicals
plus positional forms such as 氵 扌 亻 忄 艹 ⺍), the **radical names** (en / zh-Hans / zh-Hant, for
example 氵 三点水 / 三點水 / "water"), and one **SVG glyph** per unencoded component used. Steps:
1. Parse every line into per-region sequences (G, T, H, default); parse each sequence into a tree of
   operators and components; reject malformed sequences into the report.
2. Map radical-form components to unified ideographs (`PIPE-080`); turn `〾X` into a link to X; give
   each distinct `{nn}` an id from a committed synthetic-id table and require its SVG glyph (a missing
   glyph fails the build); mark `？` parts unknown.
3. Apply the root rule (`DATA-191`): the sequence is the character itself, a single stroke, or a
   curated primitive.
4. Select the coverage set — every character of both datasets' headwords and Unihan-only entries,
   closed under components — and attach `kRSUnicode`, `kTotalStrokes` and, for components with no
   entry, Unihan readings.
5. Validate (`DATA-200` gate 20: ids resolve, no cycles, radical forms mapped, names complete) and
   report coverage per region, region fallbacks, unencoded, unknown and entry-less parts and the
   maximum depth (`DATA-201`).
CHISE and CJKVI IDS (GPL) are not used; Make Me a Hanzi etymology (LGPL-3.0) is a possible later,
separate file for origins only (deferred); its stroke `matches` are used now, by `PIPE-084`.

`PIPE-084` `[MUST]` **Stroke-to-part matches** (`DATA-191`, `ARCH-200`). Input: Make Me a Hanzi
`dictionary.txt` (LGPL-3.0; pinned by commit and SHA-256 in the lock file, `PIPE-162`). For every
character of the decomposition coverage set that has a `matches` field:
1. Parse Make Me a Hanzi's `decomposition` (IDS-like, `？` for unknown parts) and map radical forms as
   in `PIPE-081` step 2.
2. Compare it with each of the character's stored sequences (G, T, H, default, `PIPE-081`): a sequence
   is **matched** only when its operator and component split equals Make Me a Hanzi's, level by level,
   for every part Make Me a Hanzi decomposes further; otherwise it gets no stroke data (the app uses
   the IDS-operator regions, `DATA-191`).
3. Check that the character's stroke count in Make Me a Hanzi equals its `strokes` record
   (`PIPE-063`); on a mismatch the character gets no stroke data.
4. For each matched sequence, turn each stroke's match path into the stroke's part in that sequence's
   tree, and store per tree node the ascending stroke indices of the strokes under it; strokes whose
   path is `null` belong to no part.
5. Emit `decomposition-matches` as its own LGPL-3.0 file with the Make Me a Hanzi notice
   (`PIPE-018`), and write the SHA-256 of the `decomposition` pack, this file and the `strokes` pack
   into each dataset's `decomposition_hashes` (`DATA-002`).
6. Validate (`DATA-200` gate 20) and report characters with matches, matched and unmatched sequences
   per region, split mismatches and stroke-count mismatches (`DATA-201`).

`PIPE-066` `[MUST]` The legacy Hanzi-keyed audio directory (a generator input in the reference build)
must never be packaged. Any build-only input must be excluded from shipping artefacts by an explicit
allow-list, not by a filename-pattern deny-list.

---

### 7. Language-model build

`PIPE-070` `[MUST]` **Sources** (pinned in the lock file, `PIPE-162`; credited in `SET-040`):
- Mandarin: the pre-trained libime model `lm_sc.arpa` (LGPL-2.1-or-later; distributed as a zstd archive
  of about 78 MB; 270714 unigrams and 4789953 bigrams in the reference release).
- Cantonese: built at pipeline time (`PIPE-074`) from the corpora of `PIPE-077` (decided: `Q-104`).
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

`PIPE-074` `[MUST]` **Cantonese LM.** Normalise the Cantonese corpora of `PIPE-077` (NFKC, OpenCC
`s2hk` for simplified text, markup stripped), segment the unsegmented ones with the Cantonese headword
list (`TXT-012`-style maximum likelihood over the dictionary with the `DATA-189` frequencies; pronoun +
嘅 headwords excluded; HKCanCor and UD_Cantonese-HK keep their own segmentation), and train a bigram model with KenLM `lmplz` at build time
(build-only tool, never shipped), then import it via `PIPE-071`. If the Cantonese model fails the
quality gate (`PIPE-075`), the pipeline may fall back to Mandarin bigrams for shared written forms
(converted with OpenCC), flagging the fallback in `lm_source`. The derived Cantonese model is
published under CC BY-SA 4.0 with attribution to every corpus (`SET-040`).

`PIPE-075` `[MUST]` **Quality gate.** Each dataset's language model is evaluated against a golden set
in `contract/`, and publication fails if it does not pass. Mandarin required cases: `nh` → 你好,
`zg` → 中国, `wxhn` → 我喜欢你 (as the top combination), each at rank 1. Cantonese cases (for example
`nd` → 我哋, `gwdw` → 廣東話, `ngd` → 我哋). Each set also asserts a top-3 hit rate at or above a declared
threshold over a broader query list.

`PIPE-076` `[MUST]` **Size and coverage report.** For each dataset report: companion-file size and
the sizes of `abbr_idx` and `bigram`; bigram counts before and after pruning; key and posting counts
of the first-letter index and capped keys; the share of headwords with an observed (not smoothed)
unigram; and the diff against the previous build. Sizes are checked against `PERF-070`.

`PIPE-077` `[MUST]` **Cantonese corpus sources and licences** (decided: `Q-081`, `Q-104`). Used for
the Cantonese frequency table (`DATA-189`) and the Cantonese LM (`PIPE-074`), each pinned in the lock
file (`PIPE-162`) and credited in `SET-040`:

| Source | Role | Size (approx.) | Licence |
|--------|------|----------------|---------|
| rime-cantonese `essay-cantonese.txt` | unigram base (word → weight) | 267k lines | CC BY 4.0 |
| HKCanCor (via PyCantonese) | counts and bigrams; pre-segmented spoken Cantonese | 230k words | CC BY 4.0 |
| Common Voice `yue` sentences | counts and bigrams; colloquial | 28.7k sentences | CC0 |
| Tatoeba `yue` | counts and bigrams | 20.9k sentences (904k tokens) | CC BY 2.0 FR |
| UD_Cantonese-HK | counts and bigrams; segmented | 1k sentences | CC BY-SA 4.0 |
| zh-yue Wikipedia dump | counts and bigrams; written register | dump | CC BY-SA 4.0 |

**Excluded:** words.hk (non-commercial open-data licence), the Leipzig corpora (non-commercial terms),
OpenSubtitles and wordfreq (no Cantonese), and LIHKG or Common Crawl dumps (unclear rights). Any new
source needs a recorded licence check before it is added. Because CC BY-SA inputs are mixed in, the
derived tables (frequency table, LM) are published under **CC BY-SA 4.0** with attribution to every
input. Pronoun + 嘅 headwords (佢嘅, 我嘅, …) are removed from the segmentation lexicon used on these
corpora (`DATA-189`).

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
- Cantonese join (`PIPE-053`): 銀行 reaching `ngan4 hong4` with the Mandarin block `yín háng`; 行 with
  its five Jyutping entries sharing one Mandarin block; a `reading_source` `none` headword found by
  Hanzi and English but not by Jyutping; a pronoun + 嘅 form never produced by segmentation;
- every previously unlinked HSK row now resolving through the parser outputs (口哨儿, and 精彩纷呈 → its
  HSK parser patch entry), in both datasets; 后 linking both its 后 and 後 entries, one marked primary; 半 at
  level 1 with extra level 4 (`副`);
- alternative readings (`DATA-192`): 垃圾 found by `lese` and `le4 se4` (Taiwan pronunciation),
  after entries whose main reading matches;
- Unihan-only entries (`DATA-190`): a character with only a `kDefinition` found by Hanzi and English
  and ranked after dictionary entries, never produced by `abbr_idx` or combinations; a radical form
  (⻊) finding its unified ideograph;
- HSK through variants (`DATA-193`): 骨董 carrying 古董's level, absent from the HSK grid;
- decomposition (`DATA-191`): 想 → ⿱相心 → 相 → ⿰木目, stopping at the primitives 木, 目 and 心; 想's
  matched sequence carrying stroke indices for 相 and 心 that together cover all its strokes;
- HSK characters (`DATA-194`): the level-1 recognition characters listed in sort order, each resolving
  to its primary single-character entry in both datasets;
- 行 `hang4` keeping its adapted "to walk" sense (`DATA-182`); in Cantonese mode "walk" lists 行
  `hang4` among the CC-Canto results and adds at most one 行 row for 行 `xíng` from the Mandarin block,
  never one per Jyutping entry (`DATA-076`).

`PIPE-141` `[MUST]` The suite is shared with the app's test suite as data in `contract/`, so both
sides assert the same expectations against the same dataset.

`PIPE-142` `[MUST]` The suite runs on **every** dataset build, not only on pipeline changes — a source
refresh can change ranking.

---

### 10. Publication

`PIPE-150` `[MUST]` Artefacts per build: one dataset file per mode, one language-model companion file
per mode, one file per asset pack, the Web changesets and changeset manifests (`PIPE-167`), the
word-page data for the edge-rendered SEO pages (`PIPE-167`), a machine-readable build report
(`DATA-201`), and a manifest listing every artefact with its size, SHA-256 and target
`dataset_version`. The manifest also lists the non-dataset assets (`DATA-170`) with their own versions
and SHA-256 from the lock file (the OpenCC dictionaries with the library version, `DATA-195`). The Web
OCR model and its manifest (`DATA-196`) are published with the Web deployment, model files first and
the manifest last, and change only when the repackaged model's version changes.

`PIPE-151` `[MUST]` The manifest is consumed by the app build, which packages every artefact for each
flavour (`DATA-215`; the voice model goes into the Play on-demand pack and the iOS Background Assets
pack, `VOICE-095`), and by the Web loader, which verifies fetched files against it. It includes the
minimum supported `schema_version` for each artefact.

`PIPE-152` `[SHOULD]` Data changes reach a release only through a reviewed pull request that carries
the build-report diff against the previous dataset (`DATA-201`, `PIPE-165`).

`PIPE-167` `[MUST]` **Per-release Web outputs** (decided: `Q-091`).
1. **Changesets** (`DATA-216`): for each dataset, the pipeline keeps the published files of the last N
   releases (N is configuration, about 4 to 8) as build cache and generates one changeset **directly**
   from each of them to the new version (one hop, no chains), by diffing the two SQLite files table by
   table on primary keys. Entry ids are carried over by natural key (`DATA-013`) so unchanged entries
   produce no operations. A changeset larger than about half the full file is not published (that base
   gets a full download). The pipeline **tests every changeset** by applying it to its base and
   comparing the result's `content_hash` with the new build; a mismatch fails the build. A release that
   compacts ids is marked `rebase` and publishes no changesets.
2. **Changeset manifest** per dataset with the fields of `DATA-216` (version, base versions, SHA-256
   and size of each changeset and of the full dataset). Upload order: full file and changesets first,
   the manifest **last**, so a client never sees a manifest pointing at missing files.
3. **SEO word-page data** for the edge Worker (`ARCH-146`, `GLB-113`): per mode, a table keyed by the
   natural-key route (`/zh/word/…`, `/yue/word/…`) holding what a word page renders (headword in both
   scripts, reading, source blocks with senses and notes, HSK levels, canonical URL), sharded for the
   edge store chosen in `ARCH-146`, plus the sitemap index and sitemaps (at most 50,000 URLs each). It
   is published with the same release and the same `dataset_version`.

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
CC-CEDICT, CC-Canto, the CC-CEDICT Cantonese readings file, the frequency lists, the hygiene override
files (`PIPE-078`), the note-label data
(`PIPE-079`), the decomposition data files — primitive list, radical names, synthetic-id table and
component SVG glyphs (`PIPE-081`) — and the syllable, alias and abbreviation tables. Each source has a note giving its origin URL, version or release date,
retrieval date and licence, and every file is REUSE-annotated. HSK data is not committed here: it
comes from the pinned HSK parser repository (`PIPE-012`, `PIPE-162`).

`PIPE-162` `[MUST]` **Large third-party inputs are never committed.** The libime ARPA archive (about
78 MB zst), the Cantonese corpora (`PIPE-077`), the Tatoeba exports (sentences, links, `yue` and English
sentences), the previous releases' dataset files used for changesets (`PIPE-167`), the fallback font, Unihan and
the UCD radical files (`PIPE-080`), BabelStone `IDS.TXT` (`PIPE-081`), Make Me a Hanzi
`dictionary.txt` (`PIPE-084`), and the voice, handwriting and OCR models (`DATA-170`, including the Web
OCR model's source, `DATA-196`) are pinned in a lock file (URL plus SHA-256, with version and licence)
and fetched and cached by CI. A hash mismatch fails the build. The same lock file pins the **HSK parser
repository** (`PIPE-012`, `PROD-095`) as an external input — its commit or tag and the SHA-256 of each
consumed output (`hsk_word_list.tsv`, `report.md`, `data/disambiguation.tsv`, `data/cedict_patch.u8`,
the character lists and, for `PIPE-083`, the grammar outputs) — in the entry defined by `REL-042`.

`PIPE-163` `[MUST]` **CI regenerates the dataset.** CI computes an input hash over `data-sources/`,
`pipeline/`, `contract/` and the lock file. When the hash changes, CI rebuilds every dataset and pack;
otherwise it reuses the cached artefacts keyed by that hash. In both cases it runs the validation gates
(`DATA-200`), the golden queries (`PIPE-140`), the language-model quality gate (`PIPE-075`) and the
size check (`PERF-070`, `PERF-073`) before the app build consumes the artefacts.

`PIPE-164` `[MUST]` **No dataset files are committed.** Dataset artefacts exist only as CI build
outputs and caches; the app build fails if the artefacts for its input hash are absent rather than
falling back to a stale copy.

`PIPE-165` `[MUST]` **Scheduled source updates.** A scheduled workflow (weekly) fetches the latest
CC-CEDICT, CC-Canto and CC-CEDICT Cantonese readings releases (the latter two have not changed since
2017 and 2015, `PIPE-013`, `PIPE-014`), updates `data-sources/` and their version notes, checks for a
newer HSK parser repository release and bumps its lock entry (`PIPE-012`, `REL-042`), runs the
pipeline (flagging any HSK parser patch entry that CC-CEDICT now contains and any parser row that no
longer resolves against the new CC-CEDICT, both to be fixed in the parser repository, `PIPE-044`),
and opens a pull request whose description carries the build-report diff (entry counts,
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
  the origin sources Make Me a Hanzi etymology, chinese-lexicon, the Xiaoxuetang mirror and Wikimedia
  glyph images). Decomposition itself is in v1 from BabelStone IDS (`PIPE-081`); CJKVI IDS is not used
  (GPL).
- `PIPE-083` — **Grammar data (v2, not in v1;** `ARCH-198`, `HSK-099`, `ENT-127`**).** v1 builds
  nothing; the design is recorded so v1 does not block it:
  - **Skeleton**: the HSK 3.0 grammar rules per level, consumed from the pinned HSK parser repository
    (`PIPE-012`, `REL-042`) and not re-parsed here (figures from the syllabus analysis): 593
    items (L1 70, L2 78, L3 96, L4 95, L5 70, L6 50, L7-9 134) in the hierarchy 类别 / 类别名称 / 细目 /
    语法内容, with no numbering or examples; 178 items are word lists (词类 / 语素), the rest patterns
    (`A比B+形容词`); grammar sense indices (都1, 了1, 吧1) are distinct from vocabulary homograph
    indices; 17 topics are staged across levels (比较句1–5, 把字句1–4). Model: GrammarPoint (id, HSK
    level, optional CEFR, category / subcategory / detail, topic key and stage, kind
    `pattern` / `word_list` / `concept`, titles and explanations per UI language, typed pattern slots,
    examples, related points, linked words, sources, provenance, review status, version), Example,
    Lesson (ordered points, prerequisites) and SourceRef (name, URL, SPDX licence, attribution).
  - **Imported sources** (for example the AllSet Chinese Grammar Wiki, CC BY-NC-SA 3.0), integrated
    per level by the HSK parser repository and consumed from its pinned outputs, are stored as
    **separately licensed data files**, never merged into the dataset, each item carrying its source
    and licence. Non-commercial content is flagged, and a **build flag** excludes it (forks and
    commercial builds), with the build failing if a flagged item would ship under the flag.
  - **Project content** — explanations (en, zh-Hans, zh-Hant) and generated example sentences —
    carries provenance (imported / authored / generated with model and date) and a **review status**
    (`draft` / `machine_generated` / `reviewed` / `approved`, with reviewer and date); only reviewed or
    approved items ship.
  - **Links**: grammar points link to words by natural key (`DATA-014`), resolved per dataset at build
    time; an unresolved link fails the build. Example tokens link to words the same way.
  - Flashcards and spaced repetition (card ids from point and example ids, FSRS state, review log)
    need user-data storage that v1 excludes (`PROD-017`) and are later still.
