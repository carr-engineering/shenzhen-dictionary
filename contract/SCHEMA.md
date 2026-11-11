# Dataset physical contract (schema_version 1)

Binding boundary between `pipeline/` (writer) and `app/core` (reader). The logical model is
`docs/07-data.md` §2–§8, §12, §15; this file fixes the physical encoding (`DATA-140`… is `[REC]`,
this is what we ship). Where this file and `docs/07-data.md` disagree on *encoding*, this file wins;
on *meaning*, the spec wins. Change both sides in one commit and bump `schema_version`.

## Files (pipeline output directory `pipeline/out/`)

| File | Contents |
|------|----------|
| `mandarin.db`, `cantonese.db` | main dataset, one per mode, identical schema |
| `mandarin-lm.db`, `cantonese-lm.db` | language-model companion (`DATA-151`…`DATA-154`) |
| `mandarin-widget.json`, `cantonese-widget.json` | widget / Word-of-the-Day projection (`DATA-130`) |
| `fixture/…` | the same files built from the small fixture sources (`ARCH-151`) |
| `packs/sentences.db`, `packs/sentences-yue.db` | example sentences |
| `packs/decomposition.db` | IDS decomposition + Make Me a Hanzi matches |
| `packs/audio/…` + `packs/audio/catalog.json` | recorded audio, flat files |
| `build-report.json` | counts, gates, unresolved references |

SQLite: page size 4096, `journal_mode=DELETE`, `VACUUM`+`ANALYZE`, `PRAGMA user_version = schema_version`.
All tables with a natural primary key are `WITHOUT ROWID`.

## Primitive encodings

- **varint**: unsigned LEB128.
- **id list (delta)**: ascending ids, first as varint, then each delta as varint.
- **id list (plain)**: varints in the stored order (used where order is meaningful, not ascending).
- **JSON**: UTF-8, compact, keys exactly as below; absent optional key == null/empty.
- **zjson**: raw DEFLATE (zlib *without* header, wbits −15) of JSON. (Kotlin: `Inflater(true)` on
  JVM/Android.)
- Matching keys use `SRCH-010` normalisation (NFKC + apostrophe/middle-dot folding), `DATA-071`.

## Main dataset tables

```sql
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID;
-- keys: every DATA-002 / DATA-155 field as text; lists/objects as JSON text
-- (sources, asset_packs, decomposition_hashes, lm_source, lm_file). Booleans "0"/"1".

CREATE TABLE entry(
  id INTEGER PRIMARY KEY,
  trad TEXT NOT NULL, simp TEXT NOT NULL,
  reading_display TEXT NOT NULL,      -- '' when reading_source = none
  reading_canonical TEXT NOT NULL,    -- 'ni3 hao3'
  reading_source INTEGER NOT NULL,    -- 0 cedict 1 cccanto 2 cccedict_canto 3 hsk_patch 4 unihan 5 none
  syllables TEXT NOT NULL,            -- toneless, space-joined: 'ni hao'
  tones BLOB NOT NULL,                -- one byte per syllable, 0 = unspecified
  flags INTEGER NOT NULL,             -- bit0 proper noun, bit1 segmentable, bit2 unihan-only,
                                      -- bit3 has CC-Canto (cantonese/cantonese_adapted) block
  freq_rank INTEGER NOT NULL,         -- 1 = most common, 1000000 = unranked
  char_count INTEGER NOT NULL,        -- code points of simp
  natural_key TEXT NOT NULL,          -- 16 lowercase hex chars: first 8 bytes of
                                      -- sha256(trad + ' ' + simp + ' ' + R), R = reading_canonical with the
                                      -- source capitalisation kept ('Wang2' vs 'wang2'; see Clarifications)
  merge_simp INTEGER, merge_trad INTEGER,
  hsk TEXT NOT NULL,                  -- JSON [{"l":1,"pos":["动"],"x":0|1}] main levels (x=0) then
                                      -- extra bracketed levels (x=1), ascending; '[]' when none
  hsk_via TEXT NOT NULL,              -- same shape, levels via variant (DATA-193); '[]'
  audio_key TEXT,                     -- canonical word audio key or NULL (MED-005)
  gloss TEXT NOT NULL,                -- card summary: the entry's display senses joined with '; '
                                      -- (redirects: header text + target senses), max ~240 chars
  body BLOB NOT NULL                  -- zjson, see below
);
CREATE INDEX entry_freq ON entry(freq_rank, id);
CREATE UNIQUE INDEX entry_natural ON entry(natural_key);

CREATE TABLE headword(key TEXT, entry_id INTEGER, kind INTEGER,   -- kind: 0 exact (simp/trad),
  PRIMARY KEY(key, entry_id, kind)) WITHOUT ROWID;                -- 1 folded, 2 lowercased, 3 radical_form

CREATE TABLE reading(key TEXT, entry_id INTEGER, alt INTEGER,     -- key: space-joined toneless syllables
  alt_note INTEGER, tones BLOB,                                   -- alt: 0 main, 1 also_pr, 2 taiwan_pr
  PRIMARY KEY(key, entry_id, alt, alt_note)) WITHOUT ROWID;       -- alt_note: ordinal into body.alt; tones always set
-- A second form with the syllables joined WITHOUT spaces is NOT stored; the app derives
-- prefix ranges from parsed syllables.

CREATE TABLE substr_idx(key TEXT PRIMARY KEY, postings BLOB) WITHOUT ROWID;  -- id list (delta), DATA-074
CREATE TABLE char_idx(ch TEXT PRIMARY KEY, postings BLOB) WITHOUT ROWID;     -- id list (plain), (freq_rank,id) order
CREATE TABLE en_token(token TEXT PRIMARY KEY, postings BLOB) WITHOUT ROWID;
-- en_token postings: sorted by (entry_id, sense): repeated [varint entry_id delta (0 allowed for
-- further senses of the same entry), varint (sense_index << 2 | flags)]; flags bit0 CC-Canto, bit1 Unihan.

CREATE TABLE merge_group(id INTEGER PRIMARY KEY, script INTEGER,  -- 0 simplified, 1 traditional
  members BLOB,                                                   -- id list (plain), primary first
  variants TEXT);                                                 -- JSON array of alternate-script forms
CREATE TABLE within(entry_id INTEGER PRIMARY KEY, rows BLOB);     -- repeated varint (start, length, entry_id), DATA-101 order
CREATE TABLE word_freq(key TEXT PRIMARY KEY, freq INTEGER, log_prob REAL) WITHOUT ROWID;

CREATE TABLE hsk_row(row_id INTEGER PRIMARY KEY, ord INTEGER, level INTEGER, simp TEXT, trad TEXT,
  homograph INTEGER, pos TEXT,        -- JSON ["动","名"]
  extra_levels TEXT,                  -- JSON [{"l":4,"pos":["副"]}]
  entry_id INTEGER NOT NULL, also_entry_ids TEXT,   -- JSON [int]
  sort_key TEXT);
CREATE INDEX hsk_row_level ON hsk_row(level, ord);  -- ord = storage order of DATA-121 (global, dense)
CREATE TABLE hsk_char(ch TEXT PRIMARY KEY, ord INTEGER, level INTEGER, writing INTEGER,
  writing_band TEXT, entry_id INTEGER, sort_key TEXT) WITHOUT ROWID;
CREATE INDEX hsk_char_level ON hsk_char(level, ord);
CREATE TABLE hsk_pos_label(token TEXT PRIMARY KEY, en TEXT, zh_hans TEXT, zh_hant TEXT) WITHOUT ROWID;
CREATE TABLE label(key TEXT PRIMARY KEY, en TEXT, zh_hans TEXT, zh_hant TEXT) WITHOUT ROWID;

CREATE TABLE syllable(value TEXT PRIMARY KEY, idx INTEGER) WITHOUT ROWID;  -- idx dense from 0
CREATE TABLE syllable_alias(src TEXT PRIMARY KEY, dst TEXT) WITHOUT ROWID;
CREATE TABLE syllable_abbrev(abbrev TEXT, syllable TEXT, PRIMARY KEY(abbrev, syllable)) WITHOUT ROWID;
CREATE TABLE popular(rank INTEGER PRIMARY KEY, entry_id INTEGER);
```

### `entry.body` (zjson)

```jsonc
{
  "senses": [ { "t": "to love", "src": "cedict",        // DATA-020 source enum as string
                "tags": ["idiom"], "tok": ["to","love"], // DATA-077 tokens
                "seg": [SEG, …],
                "evr": true } ],                          // only when the sense carries an embedded_variant_ref
  "blocks": [ { "kind": "mandarin", "label": "block_mandarin", "first": 0, "count": 3,
                "collapsed": false,
                "readings": [ { "d": "xíng", "c": "xing2", "first": 0, "count": 3 } ] } ],
  "notes":  [ { "kind": "classifier", "label": "classifier", "refs": [REF, …],
                "reading": null, "extra": null } ],
  "redirect": { "kind": "variant", "text": "variant of …", "seg": [SEG, …],
                "target": 123, "resolved": true },        // or absent
  "alt": [ { "kind": "taiwan_pr", "d": "xīngqí", "c": "xing1 qi2" } ]   // alternative readings, in note order
}
```

**Omitted defaults (the reader MUST apply them; they cut the body size by about 60 %):**

- sense `src` absent ⇒ `"cedict"`; `tags` absent ⇒ `[]`;
- sense `seg` absent ⇒ `[{"k":"t","t": <the sense's t>}]` (the sense is plain text);
- sense `tok` absent ⇒ the `SRCH-013` tokenisation of the sense's `t` (lowercase, split on
  non-`[a-z0-9]`, plural stem). It is present only when it differs (senses whose text contains
  references or readings, or whose structural tag replaced a source word such as `surname`);
- `blocks` absent ⇒ no block when `senses` is empty, otherwise exactly one block
  `{"kind":"mandarin","label":"block_mandarin","first":0,"count":<n senses>,"collapsed":false,
  "readings":[{"d":<entry.reading_display>,"c":<entry.reading_canonical>,"first":0,"count":<n senses>}]}`;
- `notes` absent ⇒ `[]`; `redirect` absent ⇒ none; `alt` absent ⇒ `[]`.

`readings` is present on `mandarin` and `hsk_patch` blocks only. `t` is the plain display text of the
sense: references are written as their Hanzi only (simplified form in the Mandarin dataset,
traditional in the Cantonese one) and bracketed readings as `[display]`; render from `seg` when present.
`contract/fixture/*.db` contains examples of every shape.

`SEG` is one of
`{"k":"t","t":"text"}`,
`{"k":"r","d":"display","rom":"pinyin"}` (bracketed reading, not a link),
`{"k":"ref","tr":"一點","si":"一点","d":"yī diǎn","c":"yi1 dian3","id":123,"rom":"pinyin"}`
(`d`, `c`, `id` optional). `REF` (in notes) is the `ref` object without `"k"`.

## Language-model companion (`*-lm.db`)

```sql
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID;   -- dataset_id, dataset_version, lm_word_count
CREATE TABLE lm_entry(entry_id INTEGER PRIMARY KEY, lm_word INTEGER, lm_unigram INTEGER); -- unigram = codebook index
CREATE TABLE lm_codebook(kind INTEGER, code INTEGER, value REAL,     -- kind 0 unigram, 1 bigram, 2 backoff
  PRIMARY KEY(kind, code)) WITHOUT ROWID;                            -- natural-log values
CREATE TABLE abbr_idx(key TEXT PRIMARY KEY, postings BLOB) WITHOUT ROWID;
-- abbr_idx postings, descending unigram: varint count, then per posting:
--   varint entry_id, varint lm_word, byte lm_unigram, byte alt (0/1/2), byte n, n × varint syllable idx
CREATE TABLE bigram(left INTEGER PRIMARY KEY, block BLOB);
-- block: byte backoff code, varint count, count × varint successor lm_word delta (ascending),
--        count × byte bigram code
```

## Widget projection (`*-widget.json`)

```jsonc
{ "dataset_version": "…", "rows": [ { "id": 1, "key": "natural_key", "levels": [1],
    "simp": "爱", "trad": "愛", "reading": "ài", "gloss": "to love; …" } ] }
```
Rows are in `hsk_row.ord` order, one per HSK row (`DATA-130`).

## Packs

```sql
-- packs/sentences.db (Mandarin, keyed by simplified headword) and sentences-yue.db (traditional)
CREATE TABLE sentence(id INTEGER PRIMARY KEY, zh TEXT, zh_alt TEXT,  -- zh: simplified (yue: traditional); zh_alt: other script
  en TEXT);
CREATE TABLE word_sentences(word TEXT PRIMARY KEY, ids BLOB) WITHOUT ROWID;  -- id list (plain), ranked per MED-047

-- packs/decomposition.db
CREATE TABLE decomp(ch TEXT PRIMARY KEY,   -- a character or a synthetic component id like '&U-i001;'
  ids TEXT,                                -- IDS string of the immediate decomposition, '' for a root
  parts TEXT,                              -- JSON array of immediate parts (chars / synthetic ids), in IDS order
  radical TEXT, strokes INTEGER,           -- Kangxi radical char and total stroke count (nullable)
  name TEXT,                               -- curated radical/component name (en), nullable
  svg TEXT,                                -- SVG path data for unencoded components (1024 em box), nullable
  matches TEXT)                            -- JSON: per stroke, the path into `parts` ([0], [1,0] …) or null;
  WITHOUT ROWID;                           -- NULL when Make Me a Hanzi has no usable matches (DATA-197)

-- packs/audio/catalog.json
-- { "mandarin": {"syllables": "cmn-syl", "words": "cmn-word", "ext": "opus",
--                "syllable_keys": ["a1",…], "word_keys": ["peng2_you5",…]},
--   "cantonese": {"syllables": "yue-syl", "ext": "opus", "syllable_keys": ["gaa3",…]} }
-- file = packs/audio/<dir>/<key>.<ext>; a key is the entry's reading_canonical syllables, lowercase,
-- 'u:' written as 'v', joined with '_' (words) — e.g. peng2_you5, lv4.
```

## Clarifications and changes made by the pipeline (2026-11)

These are the points where the first pipeline build had to pin something this file left open, or
change it. The app reads what is described here.

1. **`entry.body` omits defaults** — see "Omitted defaults" above (schema change).
2. **`natural_key`** hashes the reading *with the source capitalisation* (CC-CEDICT `Wang2` / `wang2`),
   because `reading_canonical` is lowercase and 王 `Wang2` / 王 `wang2` would otherwise collide in the
   unique index. Cantonese: the Jyutping canonical reading (`''` for `reading_source` none). The app
   never computes a natural key; it only looks stored keys up.
3. **`entry.syllables` / `tones`** keep one token per source reading token, including punctuation
   tokens (`,`, `·`) and Latin/digit tokens (lowercased, tone 0), so they stay aligned with the
   headword's code points. **`reading.key`** (and `reading.tones`, `abbr_idx` syllable lists) drop
   every token that is not `[a-z0-9ü]+`, so the key equals its own `SRCH-010` normalisation.
4. **`headword.kind`**: 0 = the raw simplified / traditional form; 1 = NFKC + middle-dot folded form
   when different from the raw form; 2 = the full `SRCH-010` matching key of the form (lowercased,
   separators → space) when different from both; 3 = radical form (a CJK Radicals Supplement / CJK
   Strokes code point whose equivalent unified ideograph is the headword). A normalised query is
   looked up with `key = ?` over all kinds. Kind-3 keys are *not* `SRCH-010`-normal (step 6 would
   delete them): look a single radical code point up before normalising.
5. **`word_freq`** holds every segmentable headword form of at most `max_word_len` code points, in
   both scripts — not only the forms of the source frequency list — so one batched lookup answers
   "is a word" and "log-probability" (`DATA-112`). `freq` is the observed count (0 = unobserved);
   `log_prob` is `ln(freq / total_frequency)` for observed forms and a smoothed value (below every
   observed one, integer-valued) otherwise.
6. **`entry.freq_rank`**: position of the form in the frequency list (1-based, ties broken by form);
   entries the list lacks are ranked after it by their observed LM unigram (meta `freq_rank_lm_fill`
   = 1); everything else, and every Unihan-only entry, has 1000000.
7. **`hsk_row.row_id`** is the syllabus `word_index` (1…11000, identical in both datasets);
   `sort_key` / `hsk_char.sort_key` = toneless reading + TAB + numbered reading (`ü` → `v`), compared
   bytewise. In the Cantonese dataset rows without Jyutping sort after the others of their level
   (already reflected in `ord`).
8. **`gloss`**: for an entry without senses and without a redirect (notes only, e.g. `see X`) it is the
   source text of the notes joined with `; `.
9. **LM file**: `lm_entry` has a row for every entry; Unihan-only entries carry `lm_word = -1`,
   `lm_unigram = 0`. Codebooks are ascending: code 0 is the lowest (most negative) value.
   `abbr_idx` postings carry no note ordinal; `alt` is 0 main, 1 `also_pr`, 2 `taiwan_pr`.
10. **`content_hash`** = sha256 over every table in name order; per table `T` + name + NUL, then each
    row in primary-key (all-column) order, each value as a type tag plus payload — `N` (null),
    `I` + u32be length + decimal text, `F` + 8-byte big-endian double, `S` / `B` + u32be length +
    UTF-8 / raw bytes — followed by `\n`. The meta rows `content_hash` and `built_at` are skipped.
11. Extra `meta` keys: `max_syllable_len`, `freq_rank_lm_fill`.
12. `label` rows: `contract/labels.json` is the reviewed source (`PIPE-079`); `hsk_pos_label` likewise.
13. **`merge_group.variants`** lists the members' alternate-script forms without the group's own
    displayed headword (里 → `["裡","裏"]`); a group still needs two or more members and two or more
    distinct alternate forms before that exclusion (`DATA-080`).
14. **Radical forms** (`headword.kind` 3): a CJK Radicals Supplement / CJK Strokes code point keys the
    entries of its `EquivalentUnifiedIdeograph.txt` ideograph and, when that ideograph is itself a
    radical variant (`kRSUnicode` residual 0), of the radical's main ideograph in `CJKRadicals.txt`
    (⻊ → 𧾷 and 足).
15. **Unihan-only entries** are not created for a compatibility ideograph whose NFKC form is a unified
    ideograph that has an entry (its folded key would duplicate that character's card).
16. `audio_key`, `asset_packs` and `decomposition_hashes` are filled from `pipeline/out/packs/` when the
    packs exist at dataset build time (build packs first, then datasets); otherwise NULL / `[]` / `{}`.
17. Shared data files in `contract/`: `labels.json`, `normalisation-vectors.json`, `golden-queries.json`
    (data-level lookups with expected entries for the fixture and the full datasets; types are described
    in the file), `lm-quality.json` (`PIPE-075`), `fixture/` (output of `build.py --fixture`).
