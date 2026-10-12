# 06 — HSK, Settings and Platform Integration

## HSK

Read [02 — Global Conventions](02-global-conventions.md) first. The reference app has **known data
bugs** in this area; do not reproduce them (see `HSK-026`, `HSK-027`, `HSK-056` and `HSK-067`).

---

### 1. Scope

`HSK-001` `[MUST]` The HSK feature browses the official HSK vocabulary syllabus in its **HSK 3.0,
2026 update** edition (`HSK-003`), grouped by level, and links **every** syllabus word to its canonical
CC-CEDICT entry (`HSK-050`) — or, for the rare word CC-CEDICT lacks, to an **HSK parser patch** entry
(`HSK-051`) — in Cantonese mode, to the Cantonese dataset's copy of that entry (`HSK-090`). It also
lists the syllabus **recognition characters** by character level (`HSK-098`). The syllabus data comes
from the HSK syllabus parser (`HSK-003`, `PROD-095`); the app's pipeline does not re-parse the syllabus or
re-implement the word-to-CC-CEDICT join.

`HSK-002` `[MUST]` HSK is available in **both dictionary modes**. The HSK destination (`PROD-050`)
and HSK badges (`HSK-040`) appear in Mandarin and in Cantonese mode alike; the syllabus, its levels
and its per-level row counts are the same in both. Cantonese-mode specifics (readings, definitions,
ordering, linking): `HSK-090`…`HSK-094`, `HSK-096`. *(Decided: `Q-030`.)*

`HSK-003` `[MUST]` Levels are `1..7`, where level `7` represents the combined band **7-9** and is
always labelled `HSK 7-9`. The word list follows the **HSK 3.0, 2026 update** syllabus (syllabus
published 2025-11, in force 2026-07: 新版HSK考试大纲（词汇、汉字、语法）, Chinese Testing International
(CTI)). Vocabulary per level: L1 300, L2 200, L3 500, L4 1,000, L5 1,600, L6 1,800, L7-9
5,600 — cumulative 300 / 500 / 1,000 / 2,000 / 3,600 / 5,400 / 11,000 words. A syllabus word is
identified by **word + syllabus pinyin + homograph index**: 82 rows carry a homograph index
(`本1`/`本2` style, kept as a suffix of the parser's `word` column) and 61 polyphones appear twice with
different pinyin (还 hái / huán), so neither the word alone nor (word, pinyin) is a key; the parser's
`word_index` (the word's position in the syllabus) together with `level` identifies a row. After de-duplication (`HSK-027`) each level's
distinct-word count must match these figures; the pipeline's validation gate checks them (gate 8 of
`DATA-200`). A word can have **several HSK levels**, each with its own part of speech (grammatical
class, e.g. 动、名) from the syllabus: 102 words carry an **extra bracketed level** for one part of
speech or sense (半 is level `1（4）` with POS `数、（副）`: numeral at level 1, adverb at level 4),
which the parser emits as one row per level sharing the `word_index`; the lowest level is the word's
main level. The extra level is stored with its part of speech (`extra_levels`, `DATA-120`) and shown
as an additional badge (`HSK-080`), but the word is listed and counted in the grid only at its main
level. 495 words (phrases, chengyu) have no part of speech (`HSK-082`). Only facts are stored — word
→ level and per-level part-of-speech tags, and the character levels (`HSK-098`); the official
syllabus document is not redistributed, and CTI and the parser repository are cited as the sources
(`SET-040`). *(Decided: `Q-110`, `Q-102`.)*

**Source** (`PROD-095`). The syllabus data comes from the project owner's parser repository,
[`Punpuf/hsk-syllabus-vocabulary-parser`](https://github.com/Punpuf/hsk-syllabus-vocabulary-parser)
(code MIT; CC-CEDICT-derived data CC BY-SA 4.0), which parses the official syllabus PDF and links each
word to CC-CEDICT. The pipeline consumes its outputs at a **pinned version** (lock file or submodule;
the pinned commit is recorded in the dataset `sources`, `SET-042`): `hsk_word_list.tsv` — columns
`word_index`, `level`, `word`, `pinyin`, `part_of_speech`, `pinyin_numbered`, `pinyin_cc-cedict`,
`traditional_cc-cedict`, `definition_cc-cedict` — and `report.md`, plus its `data/disambiguation.tsv`
and `data/cedict_patch.u8` (`HSK-051`). The same repository provides (as it is extended) the
**recognition** and **writing character lists** (`HSK-098`), the **grammar rules per level** and the
integration of external **grammar-lesson sources per level** (`HSK-099`). Upgrading the syllabus data
means moving the pin; corrections are made in the parser repository, never patched locally.

---

### 2. Level navigation

`HSK-010` `[MUST]` In the **Words** view (`HSK-016`), a single continuously scrolling grid holds **all** levels in ascending order, each
introduced by a full-width level header. There is no per-level page or tab.

`HSK-011` `[MUST]` A floating, horizontally scrolling **level selector** is pinned below the status
bar: one rounded chip per level, labelled `HSK 1` ... `HSK 7-9`, coloured in the level colour
(`GLB-065`). The chip for the level currently at the top of the viewport is shown selected (2 dp
border in the level colour, level-tinted fill); others are `panel` with a hairline border.

`HSK-012` `[MUST]` Tapping a chip animates the grid to that level's header. While the animation runs,
that chip stays selected regardless of what scrolls past; when it finishes, selection reverts to
"whatever is at the top". A newer tap supersedes an in-flight one.

`HSK-013` `[MUST]` The selector surface is translucent (`GLB-074`) with a bottom gradient shadow that
fades in only when the grid can scroll up.

`HSK-014` `[MUST]` The grid's top content padding equals the selector height plus 4 dp; bottom padding
is the bottom safe inset plus 16 dp.

`HSK-015` `[MUST]` The selector surface carries the **pronunciation-visibility toggle button**
(`GLB-120`) at its trailing end, outside the scrolling chip row. Toggling it shows or hides the
readings on every HSK card at once without reloading the grid, without changing card heights (space
stays reserved, `GLB-120`) and without moving the scroll position.

`HSK-016` `[MUST]` The HSK destination has two views, **Words** (the syllabus word grid, `HSK-010`…)
and **Characters** (the recognition characters, `HSK-098`), chosen with a `Words` / `Characters`
segmented switch on the selector surface, leading the level chips. Words is the default on a cold
start. The level selector (`HSK-011`, `HSK-012`) and the pronunciation toggle (`HSK-015`) act on the
view shown. Each view keeps its own scroll position; the chosen view survives configuration change
within a session (`GLB-050`) but is not persisted across launches (`HSK-070`).

---

### 3. Grid and cards

`HSK-020` `[MUST]` A staggered (masonry) vertical grid: 1 column below 600 dp, 2 columns at 600-839
dp, 3 columns at 840 dp and above. Level headers span the full line; word cards occupy a single lane.
Horizontal spacing 10 dp; cards carry their own 4 dp vertical padding.

`HSK-021` `[MUST]` A level header renders the level badge with its row count: `HSK 3 (500)`.

`HSK-022` `[MUST]` Each word card is the shared entry card (`ENT-070`) with:
- the linked canonical dictionary entry's content, its reading shown or hidden per the
  pronunciation-visibility setting (`GLB-120`);
- container colour = the level's paper tint (`GLB-065`);
- merge grouping **not** applied (each syllabus row is its own card);
- an HSK badge footer showing the levels the entry belongs to — level badges only, never the
  grammatical class (`HSK-041`).

`HSK-024` `[MUST]` Tapping a card opens its linked entry's detail (`ENT-010`).

`HSK-025` `[MUST]` Each card's play button reflects the real availability of pronunciation
audio for the canonical entry. Hiding readings (`GLB-120`) does not affect the play button.

`HSK-026` `[MUST]` Grid item keys must be unique and stable across scrolling. **The tuple
(level, word, reading) is not unique** in the source data — the reference HSK data contains 14
collisions (for example 局 and 料 each appear twice within level 6, and level 7-9 has six more)
because the syllabus lists a word once per part of speech. Keys must come from a per-row stable
identifier supplied by the dataset (`DATA-120`). Duplicate keys crashed the reference app on level 6
before it was patched.

`HSK-027` `[MUST]` Beyond keying, the successor must **de-duplicate the rows themselves**: one card
per syllabus word (word + pinyin + homograph index, `HSK-003`) per level, with the parts of speech
for that level merged into that level's entry-detail badge (`HSK-041`, `HSK-043`). The reference app
still renders two identical cards for 局 in level 6. Homograph rows of one level that link to the same
dictionary entry render as **one** card (their parts of speech merge in the entry-detail badge); the
level header (`HSK-021`) still reports the
syllabus word count.

---

### 4. Ordering

`HSK-030` `[MUST]` Within a level, rows are ordered **alphabetically by pronunciation** (Pinyin in
Mandarin mode; Jyutping in Cantonese mode, `HSK-093`), using this comparator:
1. plain (toneless) reading of the linked **canonical dictionary entry**;
2. numbered reading;
3. display (marked) reading;
4. the word itself;
5. the row's stable key.

`HSK-031` `[MUST]` Pronunciation strings are normalised for sorting: trimmed, lowercased, `u:` and
`ü` folded to `v`, whitespace runs collapsed.

`HSK-032` `[MUST]` The ordering key must be **precomputed** in the dataset (`DATA-121`) so the client
performs a plain sort — or, better, reads rows already in order — rather than deriving readings for
11000 rows at load time.

---

### 5. HSK metadata elsewhere in the app

`HSK-040` `[MUST]` An entry's HSK **word** levels appear as outlined badge pills in: search result
cards, compact rows (related rows), sheet rows, HSK cards, the Word of the Day card (`SRCH-115`) and
the entry-detail header, in both dictionary modes. Pill text is `HSK n` (or `HSK 7-9`); on every card
and row the pill shows the level only, never the grammatical class (`HSK-041`). An entry's levels include its direct syllabus levels, its extra bracketed
levels (`HSK-003`) and levels received through variants (`HSK-097`); all render identically.

`HSK-041` `[MUST]` In the **entry-detail header only**, every level the entry has is shown, each badge
carrying the grammatical class (part of speech) for that level: `HSK 1 • Measure word`, `HSK 4 •
Adverb`. A level with no part of speech (`HSK-082`) shows the level alone. HSK character levels are
never shown there (`HSK-098`).

`HSK-042` `[MUST]` Part of speech is **per level**, not per entry: 点 is 量 (measure word) at level 1
and 动 (verb) at level 2. Aggregating across levels is a bug.

`HSK-043` `[MUST]` Where a level has several rows for one entry (per-part-of-speech rows), the parts
of speech for that level are de-duplicated and joined, then translated for display.

`HSK-044` `[MUST]` Part-of-speech (grammatical class) translation table, from the syllabus tokens in
the parser's `part_of_speech` column to English labels, used only by the entry-detail badge
(`HSK-041`):
名 Noun · 动 Verb · 形 Adjective · 副 Adverb · 量 Measure word · 数量 Quantifier · 数 Numeral ·
连 Conjunction · 代 Pronoun · 介 Preposition · 助 Particle · 叹 Interjection · 前缀 Prefix ·
后缀 Suffix · 拟声 Onomatopoeia. Combined values arrive joined by `、` and render joined by ` & `
(`动、副` becomes `Verb & Adverb`). Unknown tokens pass through verbatim; blank input yields no label.
Labels are shown in the UI language (`GLB-100`), so the table carries Chinese labels as well as
English ones.

`HSK-045` `[SHOULD]` The translation table belongs in the dataset (`DATA-122`) so a new part-of-speech
token does not require an app release.

`HSK-097` `[MUST]` **HSK levels propagate through variants.** When a syllabus word's linked entry is
related to another entry by a **plain variant** (`variant of`), **old variant** (`old variant of`) or
**erhua variant** (`erhua variant of`) relation, in either direction — the other entry is a variant
of the HSK word, or the HSK word is itself a variant of the other entry — the other entry receives
the HSK word's levels and per-level parts of speech. Propagation follows up to **two hops** (this
covers every chain in the current data); other variant kinds do not propagate. An entry with its own
direct syllabus level keeps only its direct levels; when an entry receives different levels through
several HSK words, it takes the **lowest**. The badge is **identical** to a direct HSK word's badge
(`HSK-040`, `HSK-041`) everywhere badges appear. The **HSK grid lists only syllabus words** (and the
widget and Word of the Day draw only from them, `HSK-096`): propagated entries never appear as grid
cards. Computed in the pipeline (`DATA-193`, `PIPE-082`), per dataset — in Cantonese mode the
Cantonese copies of those entries carry the same badges (`HSK-094`). Current data: 3,716 CC-CEDICT
entries carry 3,767 variant relations (plain 2,709, old 723, erhua 258, other); propagation gives 630
extra entries a level (622 at hop 1, 8 at hop 2) — 370 single characters, mostly obscure old forms
(宍 → 肉), and 260 words (抵銷 → 抵消, 骨董 → 古董, 39 erhua forms); 2 receive conflicting levels.

---

### 6. Relationship between HSK data and dictionary records

`HSK-050` `[MUST]` **Every** syllabus row is linked **during preprocessing** to exactly one
**primary** canonical dictionary entry — a CC-CEDICT entry, or an HSK parser patch entry for a word
CC-CEDICT lacks (`HSK-051`). The link is the one the HSK syllabus parser resolved (`HSK-003`): its
`pinyin_cc-cedict` and `traditional_cc-cedict` columns, with the row's simplified word, identify the
entry; the pipeline does not re-match words itself. Where one simplified word stands for several
traditional words that CC-CEDICT lists as separate entries with the same reading (后/後, 回/迴,
干/乾/幹), the row also links the others; the parser's entry is primary (the card, ordering and widget
use it), and every linked entry carries the HSK badge. There are no unlinked rows, no fallback cards
and no syllabus-sourced entries. The app performs **no** joining at runtime.

`HSK-051` `[MUST]` The parser resolves each row in this order: (1) exact match on numbered pinyin;
(2) a **unique** tone-insensitive match (`HSK-052`); (3) a disambiguation override from the parser's
`data/disambiguation.tsv` (e.g. 口哨儿 → 口哨, 嗯 `ǹg` → `en4`, ties between several candidates);
(4) a fallback to the parser's patch file `data/cedict_patch.u8` (CC-CEDICT format) for the few words
CC-CEDICT lacks (e.g. 精彩纷呈); (5) anything still unresolved is a **hard error** in the parser.
Patch entries are merged
into **both** datasets as entries of a labelled source, **"HSK parser patch"** (CC BY-SA 4.0,
`SET-040`), and are used for nothing else. **Variant-only words** (e.g. 一点儿 → 一点, 辞典 → 词典,
where the resolved entry's only sense is a variant cross-reference) link to that variant entry, and
their card shows the definitions of the variant's target entry. *(Decided: `Q-121`.)*

(Cantonese dataset: `HSK-090`.)

`HSK-052` `[MUST]` The syllabus and CC-CEDICT disagree on tones for 246 of the 11,000 words, because
the syllabus writes **tone sandhi and neutral-tone conventions** that CC-CEDICT does not (148 一/不
sandhi rows, about 99 neutral-tone conventions):
`不客气 bú kè qi` vs `bù kè qi`; `一半 yíbàn` vs `yī bàn`; `那边 nàbiān` vs `nà bian`;
`学生 xuéshēng` vs `xué sheng`. Some rows carry two readings separated by `/` (`谁 shéi/shuí`). This
is why the parser's resolution falls back to tone-insensitive matching (`HSK-051`); the pipeline
relies on the parser's resolved `pinyin_cc-cedict`, never on the syllabus pinyin.

`HSK-053` `[MUST]` The app **displays the canonical dictionary reading** for linked rows, not the
syllabus reading, so the same word reads identically everywhere in the app. Like every reading, it is
shown or hidden per `GLB-120`. The syllabus reading is used only by the parser's join (`HSK-051`,
`HSK-052`); it is never shown anywhere, in either mode. *(Decided: `Q-031`; former `HSK-054` and `HSK-095` removed.)*

`HSK-055` `[MUST]` CC-CEDICT (Mandarin) and CC-Canto (Cantonese) — plus the HSK parser patch entries
for the words CC-CEDICT lacks (`HSK-051`) — are the **source of truth for definitions**. Syllabus glosses and the parser's `definition_cc-cedict` column are neither kept in the dataset
nor shown anywhere: every card shows its linked entry's dictionary definitions. *(Decided: `Q-032`.)*

`HSK-056` `[MUST]` The build **fails** on **any** row that does not resolve to an entry of the dataset
being built — zero allowance — in either dataset, and lists every offending row in its report. The
pipeline verifies independently of the parser: every row's resolved entry (or patch entry) exists in
the dataset, the per-level counts (`HSK-003`, gate 8 of `DATA-200`), and the Cantonese-dataset join
(`HSK-090`). The reference dataset silently shipped 9 unlinked rows — 新媒体, 新能源, 不予, 精彩纷呈,
居于, 口哨儿, 两翼, 压轴, 致力于 — which appeared in the grid as dead, untappable cards; with the parser
all resolve (口哨儿 through `disambiguation.tsv`, 精彩纷呈 through `cedict_patch.u8`). A new syllabus
or CC-CEDICT release that leaves a row unresolved is fixed in the parser repository (a disambiguation
override or, only when CC-CEDICT lacks the word, a patch entry) and a new pin, never by shipping the
row unlinked.

---

### 7. Loading behaviour and performance

`HSK-060` `[MUST]` The HSK data loads **on first navigation** to the destination, not at app start.

`HSK-061` `[MUST]` The built grid model is cached for the lifetime of the dataset generation. Leaving
and returning to the destination must not reload or rebuild it.

`HSK-062` `[MUST]` Replacing the dataset (mode switch, dataset replacement) invalidates the cache and
any in-flight load.

`HSK-063` `[MUST]` Loading state: the level selector renders as three grey chip placeholders and the
grid renders shape-matched placeholder cards of varied heights (138/164/124/178/146/156/132/172 dp)
with `bgSoft` bars, non-scrollable. It cross-fades (200 ms in) into the real content; the placeholder
is not re-shown for a warm return.

`HSK-064` `[MUST]` `No HSK data` (or the error text) is shown centred when the load produced nothing.

`HSK-065` `[MUST]` Cold and warm entry targets: `PERF-040`. The reference implementation reads all
11105 rows, JSON-parses each row's definitions, hydrates about 11000 dictionary entries (each with
its own JSON parsing), builds the sort model, **and** computes audio availability for every entry
before showing anything — tolerable on a fast desktop, multiple seconds on a phone.

`HSK-066` `[MUST]` The successor must render the grid from a **pre-ordered, pre-projected** row table
(`DATA-120`) containing everything the card needs, and must resolve audio availability lazily per
visible card.

`HSK-067` `[MUST]` The load must not fail wholesale because of one bad row. The reference
implementation threw when any linked id was missing from the dictionary, taking down the whole
screen. Linkage is guaranteed at build time (`HSK-056`) and cards render from the pre-projected row
table (`HSK-066`), so the grid never depends on resolving entries at load; a card whose entry
nevertheless fails to open on tap shows the entry error state.

---

### 8. Persistence

`HSK-070` `[MUST]` The grid scroll position survives configuration change within a session
(`GLB-050`) but is not persisted across launches.

`HSK-071` `[MUST]` No per-level "last viewed" preference is persisted. The destination always opens at
level 1 on a cold start.

`HSK-072` `[MUST]` The home-screen widget keeps its own persisted level mask per widget instance
(`SET-061`).

---

### 9. Edge cases (all `[MUST]`)

`HSK-080` A word belonging to several levels shows one badge per level, ascending.

`HSK-081` A level with zero rows after de-duplication is omitted from both the grid and the selector.

`HSK-082` A syllabus row with an empty part of speech contributes no part-of-speech label.

`HSK-083` A syllabus row whose two `/`-separated readings resolve to different dictionary entries must
resolve to one card in the pipeline (preferring the more frequent reading) and record the alternative.

`HSK-084` Tapping a card while the entry-detail stack already has routes pushes onto it as usual.

`HSK-085` Switching dictionary mode while the HSK destination is active keeps the user on HSK. The
grid is rebuilt from the new mode's dataset (`HSK-062`); the previous grid stays visible but inert
until the new one replaces it (`GLB-030`), and the view then shows the level that was at the top
before the switch.

---

### 10. HSK in Cantonese mode

`HSK-090` `[MUST]` The Cantonese dataset carries its own HSK row table: each row links to the
Cantonese dataset's **copy of the same CC-CEDICT entry** the Mandarin row links to (`HSK-050`,
`DATA-187`) — the Cantonese entry whose Mandarin block carries that CC-CEDICT entry. Every CC-CEDICT
headword — and every HSK parser patch entry (`HSK-051`), carried like a CC-CEDICT entry — is present in the
Cantonese dataset (a headword with no Jyutping as a no-reading entry, `DATA-182`), so every row
links. Where several Cantonese entries carry the CC-CEDICT entry (one per
Jyutping reading, `DATA-184`), the pipeline picks one deterministically (the most frequent, then the
lowest id) and records the decision. The app performs no joining at runtime (`HSK-050`); the build
fails on any unlinked row (`HSK-056`).

`HSK-091` `[MUST]` Per-level row counts, levels, de-duplication and parts of speech are identical in
both modes (`HSK-003`, `HSK-027`, `HSK-042`); only the linked entry differs.

`HSK-092` `[MUST]` Cards in Cantonese mode show the linked Cantonese entry's **Jyutping**
(visibility per `GLB-120`) and its definitions **Cantonese-first**, with Mandarin-sourced definitions
as fallback, labelled by provenance (`PROD-045`). A card whose linked entry has no Cantonese reading
shows the `No Cantonese reading` note in place of the Jyutping and the entry's Mandarin senses
(`ENT-116`, `Q-115`).

`HSK-093` `[MUST]` In Cantonese mode rows are ordered by the linked entry's **Jyutping** with the
`HSK-030` comparator; rows whose linked entry has no Cantonese reading (`Q-115`) sort after the
Jyutping rows of their level, by the Pinyin of their linked CC-CEDICT entry. The sort key is
precomputed in the Cantonese dataset (`DATA-121`, `DATA-187`).
*(Decided: `Q-114`.)*

`HSK-094` `[MUST]` Entry badges in Cantonese mode (`HSK-040`, `HSK-041`) derive from the Cantonese
HSK row table, so an entry shows the same levels and per-level parts of speech as its Mandarin
counterpart.

`HSK-096` `[MUST]` The widget (`SET-060`) and the Word of the Day card (`SRCH-115`) draw from the HSK
row table of the active mode's dataset.

---

### 11. HSK characters

`HSK-098` `[MUST]` Besides the words, the HSK 3.0, 2026 update syllabus lists characters (汉字) by
level: **3,088 recognition characters** (认读字: L1 246, L2 125, L3 284, L4 441, L5 431, L6 413, L7-9
1,148) and **1,200 writing characters** (书写字, a subset: L1-2 100, L3-6 150 each, L7-9 500). Both
lists come from the HSK syllabus parser repository at its pinned version (`HSK-003`, `PROD-095`); the
pipeline stores them as data (the `hsk_char` table, `DATA-194`) and validates the per-level counts. A
character's level equals the first word level containing it for 3,085 of the 3,088 (exceptions 店,
饺, 留); 1,503 characters are also single-character syllabus words, 414 of which have a word level
different from the character level (白: character level 1, word level 5), and 1,585 occur only
inside longer words. *(Decided: `Q-120`.)*

- **Characters view.** The HSK destination's **Characters** view (`HSK-016`) lists the recognition
  characters **grouped by character level**, in the layout of the Words grid: level headers with
  their character count (`HSK 3 (284)`, `HSK-021`), the masonry grid of `HSK-020`, the level selector
  and the pronunciation toggle. Each card is the shared entry card (`ENT-070`) of the character's
  single-character entry in the active mode's dataset (the pipeline picks one deterministically when
  there are several, most frequent first, and records it), with its reading and definitions as on any
  card, ordered by the `HSK-030` comparator (Jyutping in Cantonese mode, `HSK-093`), precomputed. The
  card shows **no HSK badge** of any kind. Tapping opens the entry detail (`ENT-010`). The view loads
  on first display and is cached like the word grid (`HSK-060`…`HSK-062`); the build fails if a
  recognition character resolves to no single-character entry.
- **Never a badge.** The character level is **not** an HSK word level: it is never shown on the entry
  detail screen or as a badge anywhere, and never feeds `HSK-040` badges, the word grid, propagation
  (`HSK-097`), the widget or Word of the Day.
- **Writing characters** are kept as data only (the `writing` flag); v1 has no UI for them.

---

### 12. Grammar lessons (v2, not in v1)

`HSK-099` `[SHOULD]` *v2 note, not part of v1.* In v2 the HSK destination offers **grammar lessons per
level**, built on the HSK 3.0, 2026 update syllabus grammar list (593 points: L1 70, L2 78, L3 96, L4 95, L5 70, L6
50, L7-9 134, in the hierarchy 类别 / 类别名称 / 细目 / 语法内容) plus separately licensed imported
lessons and reviewed project-written explanations and examples; the grammar rules per level and the
integration of the external grammar-lesson sources per level come from the HSK syllabus parser
repository (`HSK-003`, `PROD-095`); linked words show related grammar on
the entry detail screen (`ENT-127`). The data model and architecture readiness are specified in
`ARCH-198` and `PIPE-083`. v1 builds nothing for it beyond not blocking it: the HSK destination's
structure (view switch, level selector, per-level sections) must leave room for a per-level grammar entry point.

---

## Settings and Platform Integration

---

### 1. Settings screen

`SET-001` `[MUST]` Settings is **not** a primary destination (`PROD-050`). It is reached from the
`Settings` card on the empty Search screen (`SRCH-116`) and from the route `/settings` (`GLB-110`),
and opens as a pushed route (`SET-005`). It is a scrolling list with a `Settings` heading, sections
separated by 18 dp, 20 dp horizontal padding, and status/nav-bar insets applied.

`SET-002` `[MUST]` Controls, in order:

| Control | Type | Options / default |
|---------|------|-------------------|
| Dictionary | segmented | `中文 Mandarin` / `粵語 Cantonese` — default Mandarin |
| Default script | segmented | `简体` / `繁體` — default Simplified (Mandarin), Traditional (Cantonese) |
| App language | selector | `System` / `English` / `简体中文` / `繁體中文` — default System (`SET-018`) |
| Theme | segmented | `Light` / `Dark` / `Auto` — default Auto |
| Tone coloring | switch | default on |
| Show pronunciation | switch | default on (`SET-017`) |
| Reading boundaries | switch | default on |
| Save photos to gallery | switch | default off (`CAM-080`) — Android/iOS only |
| Privacy policy | link row | opens the privacy policy (`SET-090`) |

`SET-003` `[MUST]` Changing any setting takes effect immediately and is persisted immediately; there is
no Save button.

`SET-004` `[MUST]` Below a divider: an `About & Attribution` block with the app version
(`YYYY.MM.DD-X`, see the release section of [08 — Engineering](08-engineering.md)), the **dataset
version** of the active dataset (source versions + pipeline commit, `PIPE-166`), an explanatory
paragraph, a button opening an **open-source library licences** dialog (`SET-043`), a `Privacy
policy` link (`SET-090`), a `Data sources` heading, and one card per bundled data source or model
(`SET-040`). Long-pressing the
version opens the developer screen (`ARCH-171`); there is no other entry point to it.

`SET-005` `[MUST]` Settings opens as a route pushed over the Search destination: a top bar with the
`Settings` title and a back/up control; the primary navigation stays visible with Search selected.
Back (`PROD-056`) and up pop it, returning to the empty Search screen with its state intact
(`GLB-050`). Switching primary destination dismisses it (`PROD-055`). Changing the dictionary mode or
script from Settings keeps Settings open; Search underneath refreshes (`PROD-043`). On expanded
widths Settings is a single pane constrained to the readable width (`GLB-067`). Settings exists on
every platform, including Web.

---

### 2. Persisted settings

`SET-010` `[MUST]` Persisted keys and semantics:

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `dictionary_mode` | enum | `mandarin` | `PROD-040` |
| `script` | enum | `simplified` | per mode (`SET-011`) |
| `ui_language` | enum | `system` | `system` / `en` / `zh-Hans` / `zh-Hant` (`SET-018`) |
| `theme_mode` | enum | `system` | |
| `tone_colors` | bool | `true` | `GLB-066`; applies to Reading passages, OCR text and the Voice transcript, including converted text (`TXT-081`…) |
| `show_pronunciation` | bool | `true` | global reading visibility (`SET-017`, `GLB-120`) |
| `show_boundaries` | bool | `true` | word-boundary underlines on Reading passages, OCR text and the Voice transcript, including converted text (`TXT-081`…) |
| `input_method` | enum | `keyboard` | `keyboard` / `handwriting` (`SET-019`) |
| `search_side` | enum | `english` | only written per `SRCH-026` |
| `last_search_query` | string | `""` | per mode (`SET-013`); debounced 750 ms, flushed on stop |
| `camera_auto_save` | bool | `false` | `CAM-080` |

`SET-011` `[MUST]` Script preference is stored **per dictionary mode**, so switching modes restores that
mode's script rather than carrying one over.

`SET-012` `[MUST]` Unknown or corrupt stored values fall back to the default without an error and
without wiping the rest of the store.

`SET-013` `[MUST]` `last_search_query` is stored per dictionary mode so switching modes restores that
mode's last query.

`SET-014` `[MUST]` Not persisted across launches: the entry-detail stack, OCR destination state,
reading draft, voice transcript, script-conversion view state (`TXT-081`…, `CAM-130`…,
`VOICE-096`), HSK scroll position and Words / Characters view (`HSK-016`), sheet state (word-candidate and handwriting sheets), flash mode,
and whether Settings or the privacy policy was open.

`SET-015` `[MUST]` A settings change that affects widget appearance (dictionary mode, script, tone
colours, theme) triggers a widget refresh (`SET-060`).

`SET-016` `[MUST]` Settings load asynchronously. Until they arrive the app renders defaults; when they
arrive, they apply — **except** that a query the user has already typed is never overwritten
(`SRCH-142`).

`SET-017` `[MUST]` `show_pronunciation` is one global, persisted value (`GLB-120`). The `Show
pronunciation` switch in Settings and the inline toggle buttons (Search, Reading, Voice, entry
header, HSK — `HSK-015`) all read and write this one key; a change from any of them is reflected
immediately in all the others.

`SET-018` `[MUST]` `ui_language` selects the UI language (`GLB-100`, `GLB-102`). `System` follows the
device locale through the mapping and fallback of `GLB-101`; an explicit choice overrides it. Option
labels are shown in their own language whatever the current UI language. Where the platform offers a
per-app language (Android 13+, iOS), the setting uses that mechanism so the system's per-app
language screen and this control stay in agreement. The UI language is independent of dictionary
mode, script and content; changing it keeps navigation and screen state (`GLB-050`).

`SET-019` `[MUST]` `input_method` records the last input method chosen with the input-method button
(`HW-001`…) for the Search and Reading fields. It has no Settings control.

---

### 3. Theme

`SET-020` `[MUST]` `Light` / `Dark` / `Auto` (follow system). The resolved theme drives the palette
(`GLB-060`) and the system bar icon contrast (`GLB-070`).

`SET-021` `[MUST]` The theme applies to the widget as well (`SET-062`).

`SET-022` `[SHOULD]` The design is a fixed brand palette; platform dynamic colour is deliberately not
adopted.

---

### 4. Attribution

`SET-040` `[MUST]` Each bundled data source and model is credited with: name, the role it plays in the
app, its licence, and one or more outbound links. The credits shown here and in `SET-043` are
generated at build time from **one machine-readable attribution source in the repository** — the
same source the CI licence check uses (release practice, [08 — Engineering](08-engineering.md)) —
never maintained by hand in UI code. Platform-specific items are shown only on the platforms that
ship them.

Data sources:

| Source | Role | Licence |
|--------|------|---------|
| CC-CEDICT (via MDBG) | Mandarin headwords, Pinyin, English definitions, variants, notes | CC BY-SA 4.0 |
| CC-Canto / Cantonese dictionary data | Cantonese readings and definitions | CC BY-SA 3.0 (verify at build time) |
| FrequencyWords (zh_cn, OpenSubtitles 2018 via OPUS) | word frequency for ranking and segmentation | CC BY-SA 4.0 |
| HSK syllabus vocabulary parser ([`Punpuf/hsk-syllabus-vocabulary-parser`](https://github.com/Punpuf/hsk-syllabus-vocabulary-parser), pinned version, `PROD-095`): its outputs from the HSK 3.0, 2026 update syllabus (CTI) — word list with CC-CEDICT links, character lists (`HSK-003`, `HSK-098`) — and its patch entries (`cedict_patch.u8`, labelled "HSK parser patch") for the few HSK words CC-CEDICT lacks (`HSK-051`) | HSK word levels, part-of-speech tags, word-to-entry links, character levels; definitions of the patch entries | parser code MIT; CC-CEDICT-derived data and patch entries CC BY-SA 4.0; syllabus facts only, official PDF not redistributed |
| Unicode Unihan database | fallback definitions and readings for characters with no CC-CEDICT or CC-Canto entry (`ENT-126`, `DATA-190`) | Unicode-3.0 (licence notice reproduced in full on the card) |
| Unicode Character Database (`EquivalentUnifiedIdeograph.txt`, `CJKRadicals.txt`) | mapping radical forms to unified ideographs in character decomposition (`ENT-120`…, `DATA-191`) | Unicode-3.0 (same notice) |
| BabelStone IDS (`IDS.TXT`, Andrew West; file date pinned) | character decomposition (`ENT-120`…, `DATA-191`) | free use for personal or commercial purposes; no attribution required, credited anyway |
| Tatoeba / Tatoeba Challenge | Mandarin–English example sentence pairs | CC BY 2.0 FR |
| Tatoeba Cantonese (`yue`) sentences, with English translations via Tatoeba links | Cantonese example sentences (`MED-048`, `DATA-188`); Cantonese frequency corpus (`DATA-189`) | CC BY 2.0 FR |
| OpenCC data (dictionaries, pinned release) | simplified/traditional conversion: examples at build time; script conversion of Reading passages, OCR text and the Voice transcript at runtime (`TXT-081`…, `CAM-130`…, `VOICE-096`, `DATA-195`) | Apache-2.0 |
| audio-cmn (Shtooka-derived) | recorded syllable and word pronunciations | CC BY-SA |
| hanzi-writer-data (Make Me a Hanzi derived) | stroke outlines, order, medians | MIT / Arphic Public License |
| Make Me a Hanzi `dictionary.txt` (`matches` field only; separate data file) | stroke-to-component mapping that highlights each decomposition part's strokes in the glyph (`ENT-120`…) | LGPL-3.0 |
| libime language model (`lm_sc.arpa`, fcitx) | Mandarin word bigrams for multi-word combinations (`SRCH-060`…) | LGPL-2.1-or-later |
| rime-cantonese `essay-cantonese.txt` | Cantonese frequency table (unigram base) and language model (`DATA-189`, `PIPE-077`) | CC BY 4.0 |
| HKCanCor | Cantonese frequency counts and bigrams (`DATA-189`, `PIPE-077`) | CC BY 4.0 |
| Common Voice Cantonese (`yue`) sentences | Cantonese frequency counts and bigrams (`DATA-189`, `PIPE-077`) | CC0 |
| UD_Cantonese-HK | Cantonese frequency counts and bigrams (`DATA-189`, `PIPE-077`) | CC BY-SA 4.0 |
| Cantonese Wikipedia (zh-yue) | Cantonese frequency counts and bigrams (`DATA-189`, `PIPE-077`) | CC BY-SA 4.0 |
| CJK fallback font (OFL-licensed; exact font recorded in the attribution source; mobile only) | glyphs for rare characters missing from system fonts; text is tagged zh-Hans / zh-Hant / zh-HK (`PERF-070`) | SIL OFL 1.1 |
| Noto CJK subsets (Web only; fetched when needed by Compose Multiplatform's web font fallback, not bundled, `ARCH-005`) | Chinese glyphs on Web | SIL OFL 1.1 |

Models, recognition and conversion engines (mobile only unless stated):

| Component | Role | Licence |
|-----------|------|---------|
| sherpa-onnx | voice recognition runtime (`VOICE-001`…) | Apache-2.0 |
| onnxruntime | inference runtime used by sherpa-onnx | MIT |
| Streaming Paraformer trilingual model (zh / Cantonese / en), [`dengcunqin/speech_paraformer-large_asr_nat-zh-cantonese-en-16k-vocab8501-online`](https://modelscope.cn/models/dengcunqin/speech_paraformer-large_asr_nat-zh-cantonese-en-16k-vocab8501-online) | voice recognition model; the only separately downloaded asset (`VOICE-095`) | Apache-2.0 |
| ML Kit Text Recognition (Chinese) | OCR destination on Android (`CAM-006`…); iOS uses the system's Vision framework | Google ML Kit terms |
| Web OCR model (ML Kit Text Recognition model repackaged for the browser) | OCR destination on Web (`CAM-130`…, `ARCH-197`, `DATA-196`); Web only | Google ML Kit terms |
| OpenCC KMP library (`opencc-kmp`, project-maintained, pinned version, `ARCH-196`) | runtime simplified/traditional conversion engine; all platforms | Apache-2.0 |
| ML Kit Digital Ink Recognition models | handwriting recognition (`HW-001`…); also on Web | Google ML Kit terms |

`SET-041` `[MUST]` The app's own code is licensed **Apache-2.0**, so others can build on it
easily. Datasets and models are **separate files under their own licences**, never relicensed: the
CC BY-SA 4.0 dictionaries and the dictionary dataset derived from them (which stays CC BY-SA); the
LGPL-2.1+ Mandarin language-model table, shipped as a separate, replaceable file with its licence
notice and source availability; the Arphic Public License stroke data; the CC BY-SA Cantonese
tables; the CC BY-SA 4.0 HSK parser data and patch entries; the LGPL-3.0 Make Me a Hanzi `matches`
data, shipped as a separate, replaceable file; the Unicode-3.0 Unihan/UCD data; and the
BabelStone IDS decomposition file. CC BY and CC BY-SA sources require attribution (`SET-040`). *(Decided: `Q-100`.)*

`SET-042` `[MUST]` Each source's exact version/commit/build date is recorded in the dataset metadata
(`sources`, `DATA-002`) and surfaced in its attribution card, so a user can tell which build of
CC-CEDICT they have. The dataset version shown in `SET-004` is derived from the same record plus the
pipeline commit (`PIPE-166`).

`SET-043` `[MUST]` A separate dialog lists direct runtime library dependencies with version, role,
licence and URL, generated from the same attribution source as `SET-040`.

`SET-044` `[MUST]` Links open in the platform browser.

---

### 5. Launcher shortcuts / quick actions

`SET-050` `[MUST]` Static shortcuts:
- `Read clipboard` — opens the Reading destination and pastes (`TXT-057`);
- `Open camera` — opens the OCR destination with its live camera feed (`CAM-006`);
- `[SHOULD]` `Voice` — opens the Voice destination (`VOICE-001`…); Android and iOS.

`SET-051` `[MUST]` The clipboard shortcut must defer its clipboard read until the window actually has
focus (`TXT-053`), so it works when launched cold.

`SET-052` `[MUST]` A shortcut's action is **consumed** on handling (the intent action is reset) so
activity recreation, rotation, or a later Back does not re-trigger it.

`SET-053` `[MUST]` On Android these are static launcher shortcuts and on iOS Home Screen quick
actions, the Voice one included on both (optional per `SET-050`). On Web they are omitted, or — if a
PWA manifest is shipped — `Read clipboard` and `Open camera` are exposed as PWA shortcuts; Web has no
Voice destination, so no Voice shortcut.

---

### 6. Share targets

On **Android only**, the app receives shared **images** (→ OCR destination) and shared **text**
(→ Reading destination). iOS has no share target in v1 (no share extension), and Web registers none.

`SET-054` `[MUST]` On Android the app registers as a share target for `image/*` (`ACTION_SEND`).
Receiving a shared image opens the OCR destination
(`CAM-002`, `CAM-006`) in gallery-review mode and runs OCR on it (`CAM-040`).

`SET-055` `[MUST]` On Android a shared item (image URI or text) is read once; the intent is
consumed so activity recreation, rotation or a later Back does not re-deliver it. Only
an image URI's scheme and authority may be logged; shared text is never logged.

`SET-056` `[MUST]` On Android the app is a share target for **text**: an `ACTION_SEND` filter for
`text/plain`, and an `ACTION_PROCESS_TEXT` activity labelled `Look up in Shenzhen Dictionary` in the
system text-selection menu (read-only: the app never returns replacement text). Received text is handled per `TXT-080`: the Reading destination opens,
**replaces its current draft** with the text and analyses it (`TXT-051`; long text is chunked per
`TXT-015`). Blank text is ignored: Reading opens with its draft unchanged. Consumed once per
`SET-055`.

---

### 7. Android home-screen widget (HSK word of the day)

`SET-060` `[MUST]` An Android app widget shows one HSK word per day. Responsive across three size
buckets (about 180x120, 250x150, 320x210 dp) showing progressively more content (headword and reading,
then definitions, then more definitions/notes).

`SET-061` `[MUST]` Each widget **instance** has its own persisted level mask (a 7-bit set of HSK
levels), chosen in a configuration activity when the widget is placed. With no levels selected the
widget shows `Choose HSK levels`.

`SET-062` `[MUST]` The widget follows the app's dictionary mode (Jyutping and Cantonese-first
definitions in Cantonese mode, `HSK-092`, `HSK-096`), script, tone-colour and theme settings, and
refreshes when any of them changes. It always shows the reading: `show_pronunciation` (`SET-017`)
does not apply to the widget. *(Decided: `Q-114`.)*

`SET-063` `[MUST]` The word is derived **deterministically** from the device-local date and the level
mask: filter to words in the selected levels (every row is linked, `HSK-050`), de-duplicate by
entry id, sort by entry id, then take
index `splitmix64(epochDay XOR (levelMask << 32)) mod n`, where `n` is the candidate count. All
arithmetic is on unsigned 64-bit integers with wrap-around; `epochDay` is the signed day count since
1970-01-01 in 64-bit two's complement; `>>` is a logical shift; the modulo is taken on the unsigned
result. `splitmix64` is exactly:

```
z = x + 0x9E3779B97F4A7C15
z = (z XOR (z >> 30)) * 0xBF58476D1CE4E5B9
z = (z XOR (z >> 27)) * 0x94D049BB133111EB
return z XOR (z >> 31)
```

Every platform, including Web, uses this function (one shared Kotlin implementation, or `BigInt` in
any JavaScript path) so the same date and mask always produce the same word. Test vectors:
`splitmix64(0)` = `0xE220A8397B1DCDAF`; epoch day 20736 (2026-10-10) with mask `0x7F` → input
`0x0000007F00005100` → `0x67C657EB0895804C`; epoch day −1 with mask `0x01` → input
`0xFFFFFFFEFFFFFFFF` → `0x19BA2418AFBBFAE1`. Negative epoch days must work; changing the mask must
change the sequence. The pick is stable within one dataset version. The same function and candidate
rule drive the in-app Word of the Day card (`SRCH-115`), so a widget and the card with the same mask
show the same word on the same day.

`SET-064` `[MUST]` Tapping the widget opens **that exact entry's** detail in the app via the entry
route of the widget's dictionary mode — `/zh/word/{natural-key}` or `/yue/word/{natural-key}`
(`GLB-110`) — carrying the entry's natural key (`DATA-014`, `DATA-212`),
never its `entry_id`. The app switches to Search, clears any existing detail stack, and pushes that
entry; if the dataset is still opening it waits and then opens it. The link's mode applies to that
route — and routes pushed from it — only (`GLB-110`): it does **not** change the persisted
`dictionary_mode`, and leaving the stack returns to the user's own mode.

`SET-065` `[MUST]` The widget has a speaker button that plays the word's pronunciation from the audio
installed with the app (on mobile all audio is installed up front, `MED-010`) **without opening the
dictionary database**.

`SET-066` `[MUST]` The widget reads a small dedicated projection asset (`DATA-130`) — entry id, natural
key, levels, both scripts, readings, header notes, definitions, notes — never the full dictionary. It
must render with a bounded, small memory and time cost inside the widget host's limits.

`SET-067` `[MUST]` The widget refreshes on date change, time set and timezone change.

`SET-068` `[MUST]` v1 ships the **Android** widget only. The daily pick (`SET-063`) and the projection
reader (`DATA-130`) live in shared, platform-neutral code, so an iOS WidgetKit widget can follow
post-v1 without new logic. Web has no equivalent. *(Decided: `Q-071`.)*

---

### 8. Edge cases (all `[MUST]`)

`SET-080` Switching dictionary mode while a detail stack is open clears it and re-runs the search
(`PROD-043`).

`SET-081` Switching script while a detail stack is open keeps the stack and refreshes every route in
place (`ENT-092`, `GLB-030`).

`SET-082` A widget whose selected levels are all removed from a new dataset shows `Word unavailable`
rather than an empty card.

`SET-083` A widget tap whose natural key no longer resolves in the current dataset opens the app and
shows the entry error state.

`SET-084` The auto-save switch is absent on Web.

---

### 9. Privacy policy

`SET-090` `[MUST]` The app has **one privacy policy**, served at the route `/privacy` (`GLB-110`)
in the app and at `szdict.com/privacy` on the website — same route, same content. The app **bundles**
the policy text, so it works offline, in **English, Simplified Chinese and Traditional Chinese**,
shown in the UI language (`GLB-101` fallback); the website serves the same text from the same source
file, published with each web deploy (release practice, [08 — Engineering](08-engineering.md)). It is
reached from the `Privacy policy` row in Settings (`SET-002`) and the `Privacy policy` link in the
About & Attribution block (`SET-004`); it opens as a pushed route over Settings with a back/up control
(like `SET-005`) and is a single readable-width text page (`GLB-067`). The policy states, at least
(spec summary, not the legal text):
- no accounts and no telemetry or analytics;
- Android makes no network requests of its own; the only downloads are those managed by Google Play or
  the OS;
- what the Web app fetches: szdict.com (app, dataset, OCR model and other assets) and the font host
  (`ARCH-005`);
- what is stored on the device: preferences (`SET-010`), the last search query, and cached datasets
  and models;
- camera and microphone are used only on the device for OCR and Voice, and nothing captured is stored
  except photos the user chooses to save (`CAM-080`);
- shared text and images (share targets, `SET-054`…) are processed on the device only;
- the app stores no user-curated data (`PROD-030`).
