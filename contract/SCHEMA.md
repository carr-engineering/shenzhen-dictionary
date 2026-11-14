# Dataset physical contract (schema_version 2)

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
| `packs/decomposition.db` | IDS decomposition + Make Me a Hanzi matches + reverse component index (`part_of`) |
| `packs/audio/…` + `packs/audio/catalog.json` | recorded audio, flat files |
| `build-report.json` | counts, gates, unresolved references |

SQLite: page size 4096, `journal_mode=DELETE`, `VACUUM`+`ANALYZE`, `PRAGMA user_version = schema_version`.
All tables with a natural primary key are `WITHOUT ROWID`.

## Primitive encodings

- **varint**: unsigned LEB128.
- **id list (delta)**: ascending ids, first as varint, then each delta as varint.
- **id list (plain)**: varints in the stored order (used where order is meaningful, not ascending).
- **JSON**: UTF-8, compact, keys exactly as below; absent optional key == null/empty.
- **zjson**: raw DEFLATE (zlib *without* header, wbits −15) of JSON, compressed against the dataset's
  **preset dictionary** when the file has a `body_dict` row (Kotlin: `Inflater(true)` +
  `setDictionary` on JVM/Android; Python: `zlib.decompressobj(-15, zdict)`).
- Matching keys use `SRCH-010` normalisation (NFKC + apostrophe/middle-dot folding), `DATA-071`.

## Main dataset tables

```sql
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID;
-- keys: every DATA-002 / DATA-155 field as text; lists/objects as JSON text
-- (sources, asset_packs, decomposition_hashes, lm_source, lm_file). Booleans "0"/"1".

CREATE TABLE entry(
  id INTEGER PRIMARY KEY,
  trad TEXT NOT NULL, simp TEXT NOT NULL,
  reading_display TEXT,               -- NULL = reading_canonical rendered for the dataset: Jyutping
                                      -- verbatim; Pinyin with tone marks per syllable (GLB-015), a
                                      -- comma attached (note 21). Stored where that differs: capitalised
                                      -- readings, tokens outside the inventory.
  reading_canonical TEXT NOT NULL,    -- 'ni3 hao3'
  reading_source INTEGER NOT NULL,    -- 0 cedict 1 cccanto 2 cccedict_canto 3 hsk_patch 4 unihan 5 none
  syllables TEXT,                     -- toneless, space-joined: 'ni hao'. NULL (with tones) = derived from
  tones BLOB,                         -- reading_canonical: split at spaces, a token's trailing digit is its
                                      -- tone (0 without one), the rest lowercased ('u:' -> 'ü') its syllable.
                                      -- Stored only where that differs (tokens outside the inventory).
  flags INTEGER NOT NULL,             -- bit0 proper noun, bit1 segmentable, bit2 unihan-only,
                                      -- bit3 has CC-Canto (cantonese/cantonese_adapted) block,
                                      -- bits 4-7 primary-reading rank (note 18)
  freq_rank INTEGER NOT NULL,         -- 1 = most common, 1000000 = unranked
  char_count INTEGER NOT NULL,        -- code points of simp
  natural_key BLOB NOT NULL,          -- 8 bytes: the first 8 bytes of
                                      -- sha256(trad + ' ' + simp + ' ' + R), R = reading_canonical with the
                                      -- source capitalisation kept ('Wang2' vs 'wang2'; see Clarifications).
                                      -- The app's key string is its 16 lowercase hex characters.
  merge_simp INTEGER, merge_trad INTEGER,
  hsk TEXT NOT NULL,                  -- JSON [{"l":1,"pos":["动"],"x":0|1}] main levels (x=0) then
                                      -- extra bracketed levels (x=1), ascending; '' when none
  hsk_via TEXT NOT NULL,              -- same shape, levels via variant (DATA-193); '' when none
  audio_key TEXT,                     -- canonical word audio key or NULL (MED-005)
  ulp INTEGER NOT NULL,               -- unigram log-probability of the entry's word x 100 (natural log,
                                      -- note 5); -6000 for a non-segmentable entry
  body BLOB NOT NULL                  -- zjson, see below
);
CREATE UNIQUE INDEX entry_natural ON entry(natural_key);
CREATE TABLE body_dict(id INTEGER PRIMARY KEY, dict BLOB NOT NULL);  -- one row (id 1): the preset DEFLATE
                                      -- dictionary of every entry.body; no row = plain DEFLATE

CREATE TABLE headword(key TEXT, entry_id INTEGER, kind INTEGER,   -- kind: 0 exact (simp/trad),
  PRIMARY KEY(key, entry_id, kind)) WITHOUT ROWID;                -- 1 folded, 2 lowercased, 3 radical_form

CREATE TABLE reading(key TEXT, entry_id INTEGER, alt INTEGER,     -- key: space-joined toneless syllables
  alt_note INTEGER, tones BLOB,                                   -- alt: 0 main, 1 also_pr, 2 taiwan_pr
  PRIMARY KEY(key, entry_id, alt, alt_note)) WITHOUT ROWID;       -- alt_note: ordinal into body.alt; tones always set
-- A second form with the syllables joined WITHOUT spaces is NOT stored; the app derives
-- prefix ranges from parsed syllables.

CREATE TABLE substr_idx(key TEXT PRIMARY KEY, postings BLOB) WITHOUT ROWID;  -- id list (delta), DATA-074
CREATE TABLE char_idx(ch TEXT PRIMARY KEY, postings BLOB) WITHOUT ROWID;     -- id list (plain), (freq_rank,id) order
                                      -- (the app re-ranks a list it shows: SRCH-070 caps freq_rank by HSK level)
CREATE TABLE en_token(token TEXT PRIMARY KEY, postings BLOB) WITHOUT ROWID;
-- en_token postings: sorted by (entry_id, sense): repeated [varint entry_id delta (0 allowed for
-- further senses of the same entry), varint (sense_index << 2 | flags)]; flags bit0 CC-Canto, bit1 Unihan.

CREATE TABLE merge_group(id INTEGER PRIMARY KEY, script INTEGER,  -- 0 simplified, 1 traditional
  members BLOB,                                                   -- id list (plain), primary first
  variants TEXT);                                                 -- JSON array of alternate-script forms

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
CREATE TABLE english_word(word TEXT PRIMARY KEY, freq INTEGER) WITHOUT ROWID;  -- note 19 (optional table)
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
  ids TEXT,                                -- IDS string of the immediate decomposition, '' for a root;
                                           -- one sequence per character, chosen per note 24
  parts TEXT,                              -- JSON array of immediate parts (chars / synthetic ids), in IDS order
  radical TEXT, strokes INTEGER,           -- Kangxi radical char and total stroke count (nullable)
  name TEXT,                               -- curated radical/component name (en), nullable
  svg TEXT,                                -- SVG path data for unencoded components (1024 em box), nullable
  matches TEXT)                            -- JSON: per stroke, the path into `parts` ([0], [1,0] …) or null;
  WITHOUT ROWID;                           -- NULL when Make Me a Hanzi has no usable matches (DATA-197)
CREATE TABLE part_of(part TEXT PRIMARY KEY,  -- a component: a character or a synthetic id ('&U-i001;')
  chars TEXT NOT NULL)                     -- the rows built directly from it: every `decomp.ch` whose own
  WITHOUT ROWID;                           -- `ids` names `part` as a leaf (operators skipped, inline nested
                                           -- sub-sequences flattened, '？' never listed), as one string of
                                           -- code points in ascending order. Optional (added 2026-11, see
                                           -- note 23): a pack without it has no reverse index.
CREATE TABLE radical_form(form TEXT NOT NULL,  -- a row glyph written for a Kangxi radical other than
  radical TEXT NOT NULL,                   -- itself: 亻→人, 扌→手, 耂→老, 月→肉, 阝→阜 and 阝→邑
  PRIMARY KEY(form, radical)) WITHOUT ROWID;  -- (radical = the unified ideograph, as `decomp.radical`).
                                           -- Optional (added 2026-11, note 24).

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
5. **Word probability** (`DATA-110`…`DATA-113`): there is no `word_freq` table. A headword form is a
   word when the headword index has a segmentable entry for it, and its log-probability is
   `max(entry.ulp) / 100` over those entries — returned by the same batched headword lookup. `ulp` is
   the **language model's unigram of the entry's word** (natural log × 100, the unquantised value
   behind `lm_entry.lm_unigram`): for Mandarin the blend of the libime unigram and the frequency
   list, for Cantonese the frequency table. A word no corpus observed gets the smoothed value — a
   flat one below every observed word for multi-character words, a lower one for a single
   character, the lowest for a Unihan-only character. The app's unigram DAG (`TXT-012`, used when the
   language-model file is absent) and its bigram lattice therefore score on one scale. The Mandarin
   frequency list repeats a word's count under its traditional forms; such a copy is not evidence
   for another entry whose *simplified* form it is (龢 / 龢, 妳 / 妳) and is dropped before ranking.
6. **`entry.freq_rank`**: position of the form in the frequency list (1-based, ties broken by form);
   entries the list lacks are ranked after it by their observed LM unigram (meta `freq_rank_lm_fill`
   = 1); everything else, and every Unihan-only entry, has 1000000.
7. **`hsk_row.row_id`** is the syllabus `word_index` (1…11000, identical in both datasets);
   `sort_key` / `hsk_char.sort_key` = toneless reading + TAB + numbered reading (`ü` → `v`), compared
   bytewise. In the Cantonese dataset rows without Jyutping sort after the others of their level
   (already reflected in `ord`).
8. **Card summary**: there is no `gloss` column; a card's definitions come from the body (the entry's
   display senses; for a redirect its header text and the target's senses; for a notes-only entry the
   text of its notes). The widget projection keeps its own `gloss` string.
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
18. **Primary-reading rank** — `entry.flags` bits 4–7 (`(flags >> 4) & 15`, capped at 15): the entry's
    position among the entries that share its headword (simplified form in the Mandarin dataset,
    traditional in the Cantonese one); 0 = the primary reading. Order: own HSK level, lowest first
    (行 xíng L3 before háng L5) → for a single character the Unihan reading order (`kHanyuPinlu`
    corpus counts, then `kMandarin`; Cantonese: `kCantonese`) → common before proper noun → entries
    with senses before pure variants → more senses → id. The app uses it (a) to pick the **one**
    entry standing for a bare headword — a passage segment's default reading, a decomposition row —
    as (Unihan-only last, rank, common before proper, frequency rank, id), and (b) as the last key
    before the id in every ranked list (`SRCH-070`: …, common before proper, rank, id), so 石 lists
    shí before dàn. `hsk_char.entry_id` is the rank-0 entry unless an HSK row names the character.
    The `abbr_idx` postings of one word list its primary reading first (ties of the unigram order).
19. **`english_word`** (optional table; `meta.english_word_total` = token count of the source corpus):
    the 10,000 most frequent English word forms with their corpus counts
    (`pipeline/sources-pinned/freq/en_common.tsv`), plus — with `freq` 0 — words the definitions use
    in lowercase in at least five senses and that are a complete reading in the dataset's syllable
    inventory. It answers "does this romanised query read as English?" for side selection
    (`SRCH-024`, `SRCH-027`): a `ROMANISATION` query forces the Chinese side when the English side
    has no match, **or** when the Chinese side has a whole-query match (a dictionary entry in the
    `MATCHES` section) and the query (i) is not made of words of this table only and (ii) is not
    likely the start of one — the share `sum(freq of the words starting with the last word) /
    english_word_total` is less than a tenth of the probability (language-model unigram less token
    cost) of the best whole-query Chinese match. `nh`, `dian`, `xuexi`, `ren`, `hao` force Chinese;
    `hen`, `fan`, `long`, `can`, `sang` (English words) and `ca` (start of `can`, `call`, …) keep the
    remembered side. A query made of words of this table is combined (`SRCH-060`) only through its
    full-syllable readings. Without the table the app applies the plain "no English match" rule.
    Whatever the rules above select, a side with no result at all never wins over a side that has
    some: the app then forces the side that answers the query (`love` → English, also when Chinese
    is the remembered side); like every forced side it is not persisted (`SRCH-026`).
20. **Passage segmentation** (`TXT-012`, `TXT-026`): with `has_frequency_data`, `has_bigram_model` and
    the language-model file present, the app decodes a character lattice whose edges are the
    segmentable headwords of the run — language-model word and unigram from `lm_entry`, bigram blocks
    of the distinct left words, `-20` for an unknown character or a Unihan-only entry — with `k = 1`
    and a per-word penalty of 1 nat (a near tie goes to the longer dictionary word). Two batched
    reads are added to the headword lookup: `lm_entry` and `bigram`.
21. **`reading_display`** attaches a comma token to the syllable before it (`yī bù zuò, èr bù xiū`);
    `syllables`, `tones` and `reading_canonical` keep one token per source token (note 3), so a
    display string may have fewer space-separated parts than `tones` has elements.
22. **Combinations** (`SRCH-063`): besides the top-5 rule, a sequence is offered only when its score is
    within 2.5 nats of the best sequence and of the best single word spelling the whole query, and
    is at least −6.5 nats per syllable. When the query reads as syllables — boundaries typed (space,
    apostrophe, tone digit) or, unspaced, a single shortest full-syllable reading — the best sequence
    whose word boundaries fall on those syllable boundaries is offered without the per-syllable floor
    (still within 2.5 nats of the best whole-query word); with typed boundaries, further such sequences
    within 2.5 nats of it need only −11 nats per syllable (`ruan xing tiao zheng` → 软 行 调整, 软 性 调整…).
    A letter typed as a word of its own counts as spelled in a query of English words (`wo shi yi g`).
23. **`part_of`** (decomposition pack, 2026-11): the reverse component index of the entry page's PARTS
    tab ("Appears in"). Additive: the pack's `user_version` stays 1 and `decomp` is unchanged; a reader
    treats a pack without the table as "no reverse index" and never scans `decomp` to build one
    (`ARCH-023`). Keys are exact components (亻 and 人 are separate rows); a character that reaches a
    component only through another encoded character is not listed under it (孬 = ⿱不好 is listed
    under 好, not under 女). The pack's sha256 changes with the table, so `asset_packs` /
    `decomposition_hashes` of a dataset built before the pack are stale until the next dataset build
    (the app reads neither; the Web manifest hashes the pack when it is staged).

24. **Sequence choice and `radical_form`** (decomposition pack, 2026-11). A character's `ids` is
    BabelStone IDS.TXT's sequence for the preferred region (G for characters of simplified
    headwords and what they reach, T then H for traditional-only ones) unless that sequence falls
    short: a part that is unencoded (`{97}` → `&U-i097;`) or unknown (`？`), or the character's
    radical (`kRSUnicode`, first value) neither a part nor inside one. Then the candidates — the
    preferred sequence, the character's other IDS.TXT sequences in file order, Make Me a Hanzi's
    `decomposition`, and the IDS.TXT sequences with unencoded components spelt by the file's own
    replacement fragments — are ranked: every part encoded and the radical among or inside the parts,
    then every part encoded, then fewest unencoded parts and fewest unknown parts; ties go to fewer
    inline nested groups, then to the order above. 我 = ⿰手戈 (IDS.TXT's alternative ⿰⿱㇒扌戈, the
    nested ⿱㇒扌 being 手 as written in 我), 会 = ⿱𠆢云, 街 = ⿻行圭. `part_of` is built from the
    stored sequences. `radical_form` lists the forms a reader should take as a radical when marking
    a character's radical among its parts (都 = ⿰者阝 with radical 邑); it comes from CJKRadicals.txt,
    the CJK Radicals Supplement (by name), Unihan forms with no residual strokes (`kRSUnicode` x.0,
    x.-n) and the shared forms 月 (肉) and 阝 (邑). Additive like note 23: `user_version` stays 1 and a
    reader treats a pack without the table as "no extra forms".

## Schema version 2 (2026-11) — what changed from version 1

Size pass (`PERF-070`): `mandarin.db` 61.1 → 34.6 MB, `cantonese.db` 87.5 → 43.0 MB, same query shapes.

- `entry.body` is compressed against a 32 KiB **preset dictionary** stored in `body_dict` (trained by
  `pipeline/make_body_dict.py`, committed as `pipeline/data/body-dict-<id>.bin`). The app sets it on
  its raw inflater; the pipeline's `enc.unzjson(blob, zdict)` is the reference.
- `entry.gloss` is gone (cards are built from the bodies they hydrate anyway).
- `word_freq` is gone; `entry.ulp` carries the value (note 5). `entry_freq` (an index no query used) is gone.
- `entry.natural_key` is an 8-byte BLOB instead of 16 hex characters; `hsk` / `hsk_via` are `''` when empty.
- `entry.reading_display`, `syllables`, `tones` are NULL where the app derives them from `reading_canonical`
  (`Romanisation.display` / `parseCanonical`; the pipeline stores the value whenever its own rendering of
  the canonical reading differs).
- `within` is gone: inside-words rows are computed on demand (`DATA-102`) — for all cards of a page
  with one batched headword lookup of their sub-spans (both scripts), ordered longest span first,
  then start, then the canonical projection order (Unihan-only last, HSK-capped frequency rank (`SRCH-070`), common before
  proper, reading rank, id).
- `meta.schema_version` / `PRAGMA user_version` = 2. The app (`DictionaryFactory.SUPPORTED_SCHEMA_VERSION`
  = 2) still opens a version-1 file; `com.szdict.core.api` is unchanged.
