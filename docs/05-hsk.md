# 05 — HSK

Read [02 — Global Conventions](02-global-conventions.md) first. The reference app has **known data
bugs** in this area; do not reproduce them (see `HSK-070` and findings `F-050` to `F-054`).

---

## 1. Scope

`HSK-001` `[MUST]` The HSK feature browses the official **HSK 3.0** vocabulary syllabus, grouped by
level, and links each syllabus word to its canonical dictionary entry.

`HSK-002` `[MUST]` HSK is **Mandarin-mode only**. In Cantonese mode the HSK destination is hidden and
HSK badges do not appear anywhere. *(Whether a Cantonese-specific level system should replace it is
`Q-030`.)*

`HSK-003` `[MUST]` Levels are `1..7`, where level `7` represents the combined band **7-9** and is
always labelled `HSK 7-9`. Reference row counts (HSK 3.0, 2026 edition): L1 300, L2 204, L3 507,
L4 1019, L5 1638, L6 1815, L7-9 5622 — 11105 rows total, covering about 11090 distinct words.

---

## 2. Level navigation

`HSK-010` `[MUST]` A single continuously scrolling grid holds **all** levels in ascending order, each
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

---

## 3. Grid and cards

`HSK-020` `[MUST]` A staggered (masonry) vertical grid: 1 column below 600 dp, 2 columns at 600-839
dp, 3 columns at 840 dp and above. Level headers span the full line; word cards occupy a single lane.
Horizontal spacing 10 dp; cards carry their own 4 dp vertical padding.

`HSK-021` `[MUST]` A level header renders the level badge with its row count: `HSK 3 (507)`.

`HSK-022` `[MUST]` Each word card is the shared entry card (`ENT-070`) with:
- the canonical dictionary entry's content when the row is linked;
- container colour = the level's paper tint (`GLB-065`);
- merge grouping **not** applied (each syllabus row is its own card);
- an HSK badge footer showing the levels the entry belongs to.

`HSK-023` `[MUST]` An **unlinked** syllabus row (no canonical dictionary entry) renders a fallback
card built from the syllabus data alone: word, traditional form, syllabus reading, syllabus
definitions, and the level badge. The card is **disabled** — not tappable and with no play button.

`HSK-024` `[MUST]` Tapping a linked card opens that entry's detail (`ENT-010`).

`HSK-025` `[MUST]` Each linked card's play button reflects the real availability of pronunciation
audio for the canonical entry.

`HSK-026` `[MUST]` Grid item keys must be unique and stable across scrolling. **The tuple
(level, word, reading) is not unique** in the source data — the reference HSK data contains 14
collisions (for example 局 and 料 each appear twice within level 6, and level 7-9 has six more)
because the syllabus lists a word once per part of speech. Keys must come from a per-row stable
identifier supplied by the dataset (`DATA-120`). Duplicate keys crashed the reference app on level 6
before it was patched.

`HSK-027` `[MUST]` Beyond keying, the successor must **de-duplicate the rows themselves**: one card
per (level, word, reading), with the parts of speech for that level merged into the card's badge. The
reference app still renders two identical cards for 局 in level 6 (finding `F-050`).

---

## 4. Ordering

`HSK-030` `[MUST]` Within a level, rows are ordered **alphabetically by pronunciation**, using this
comparator:
1. plain (toneless) reading of the **canonical dictionary entry** when linked, else of the syllabus
   row;
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

## 5. HSK metadata elsewhere in the app

`HSK-040` `[MUST]` An entry's HSK levels appear as outlined badge pills in: search result cards,
compact rows, sheet rows, HSK cards, and the entry-detail header. Pill text is `HSK n` (or `HSK 7-9`).

`HSK-041` `[MUST]` In the **entry-detail header only**, each badge also carries the part of speech for
that level: `HSK 1 • Measure word`.

`HSK-042` `[MUST]` Part of speech is **per level**, not per entry: 点 is 量 (measure word) at level 1
and 动 (verb) at level 2. Aggregating across levels is a bug.

`HSK-043` `[MUST]` Where a level has several rows for one entry (per-part-of-speech rows), the parts
of speech for that level are de-duplicated and joined, then translated for display.

`HSK-044` `[MUST]` Part-of-speech translation table (source token to English label):
名 Noun · 动 Verb · 形 Adjective · 副 Adverb · 量 Measure word · 数量 Quantifier · 数 Numeral ·
连 Conjunction · 代 Pronoun · 介 Preposition · 助 Particle · 叹 Interjection · 前缀 Prefix ·
后缀 Suffix · 拟声 Onomatopoeia. Combined values arrive joined by `、` and render joined by ` & `
(`动、副` becomes `Verb & Adverb`). Unknown tokens pass through verbatim; blank input yields no label.

`HSK-045` `[SHOULD]` The translation table belongs in the dataset (`DATA-122`) so a new part-of-speech
token does not require an app release.

---

## 6. Relationship between HSK data and dictionary records

`HSK-050` `[MUST]` Each syllabus row is joined **during preprocessing** to exactly one canonical
dictionary entry, or explicitly marked unlinked. The app performs **no** joining at runtime.

`HSK-051` `[MUST]` The join key is (simplified form, traditional form, reading), with reading matching
tolerant of syllabus-vs-dictionary differences (`HSK-052`). When several dictionary entries qualify,
the pipeline picks the most frequent non-proper-noun reading and records the decision.

`HSK-052` `[MUST]` The syllabus and CC-CEDICT disagree on readings for about 257 of 11105 rows,
because the syllabus writes **tone sandhi and neutral-tone conventions** that CC-CEDICT does not:
`不客气 bú kè qi` vs `bù kè qi`; `一半 yíbàn` vs `yī bàn`; `那边 nàbiān` vs `nà bian`;
`学生 xuéshēng` vs `xué sheng`. Some rows carry two readings separated by `/` (`谁 shéi/shuí`). The
reading match must therefore be **tone-insensitive with a tone-sensitive preference**, and must split
on `/`.

`HSK-053` `[MUST]` The app **displays the canonical dictionary reading** for linked rows, not the
syllabus reading, so the same word reads identically everywhere in the app.

`HSK-054` `[SHOULD]` The syllabus reading should nevertheless be preserved in the dataset and shown as
a secondary "HSK reading" on the entry page when it differs, because the sandhi form is pedagogically
what the learner is taught. *(New behaviour — `Q-031`.)*

`HSK-055` `[MUST]` Syllabus definitions are kept as a **separate provenance** from dictionary
definitions. Linked cards show the dictionary definitions; unlinked cards show the syllabus
definitions. *(Whether linked entries should also surface a differing syllabus gloss is `Q-032`.)*

`HSK-056` `[MUST]` The pipeline must **fail its validation gate** on unlinked rows above a small
allowance, and must emit the list of unlinked rows. The reference dataset silently shipped 9 unlinked
rows — 新媒体, 新能源, 不予, 精彩纷呈, 居于, 口哨儿, 两翼, 压轴, 致力于 — which appear in the grid as
dead, untappable cards (finding `F-051`). Most are joinable after normalising the traditional form or
splitting variant readings; genuinely absent words must be added to the dictionary dataset as
syllabus-sourced entries rather than shipped unlinked.

---

## 7. Loading behaviour and performance

`HSK-060` `[MUST]` The HSK data loads **on first navigation** to the destination, not at app start.

`HSK-061` `[MUST]` The built grid model is cached for the lifetime of the dataset generation. Leaving
and returning to the destination must not reload or rebuild it.

`HSK-062` `[MUST]` Replacing the dataset (mode switch, asset pack) invalidates the cache and any
in-flight load.

`HSK-063` `[MUST]` Loading state: the level selector renders as three grey chip placeholders and the
grid renders shape-matched placeholder cards of varied heights (138/164/124/178/146/156/132/172 dp)
with `bgSoft` bars, non-scrollable. It cross-fades (200 ms in) into the real content; the placeholder
is not re-shown for a warm return.

`HSK-064` `[MUST]` `No HSK data` (or the error text) is shown centred when the load produced nothing.

`HSK-065` `[MUST]` Targets: `PERF-030`. Cold first frame after tapping the destination: p50 at most
150 ms, p95 at most 300 ms. Warm re-entry: at most 50 ms. The reference implementation reads all
11105 rows, JSON-parses each row's definitions, hydrates about 11000 dictionary entries (each with
its own JSON parsing), builds the sort model, **and** computes audio availability for every entry
before showing anything — measured at about 17 ms for the row read alone on a fast desktop and
multiple seconds on a phone (finding `F-052`).

`HSK-066` `[MUST]` The successor must render the grid from a **pre-ordered, pre-projected** row table
(`DATA-120`) containing everything the card needs, and must resolve audio availability lazily per
visible card.

`HSK-067` `[MUST]` The load must not fail wholesale because one row cannot be linked. The reference
implementation threw when any linked id was missing from the dictionary, taking down the whole screen
(finding `F-053`). Unresolvable rows degrade to the disabled fallback card.

---

## 8. Persistence

`HSK-070` `[MUST]` The grid scroll position survives configuration change within a session
(`GLB-050`) but is not persisted across launches.

`HSK-071` `[MUST]` No per-level "last viewed" preference is persisted. The destination always opens at
level 1 on a cold start.

`HSK-072` `[MUST]` The home-screen widget keeps its own persisted level mask per widget instance
(`SET-060`).

---

## 9. Edge cases (all `[MUST]`)

`HSK-080` A word belonging to several levels shows one badge per level, ascending.

`HSK-081` A level with zero rows after de-duplication is omitted from both the grid and the selector.

`HSK-082` A syllabus row with an empty part of speech contributes no part-of-speech label.

`HSK-083` A syllabus row whose two `/`-separated readings resolve to different dictionary entries must
resolve to one card in the pipeline (preferring the more frequent reading) and record the alternative.

`HSK-084` Tapping a card while the entry-detail stack already has routes pushes onto it as usual.

`HSK-085` Switching to Cantonese mode while the HSK destination is active moves the user to Search.
