# 06 — HSK, Settings and Platform Integration

## HSK

Read [02 — Global Conventions](02-global-conventions.md) first. The reference app has **known data
bugs** in this area; do not reproduce them (see `HSK-026`, `HSK-027`, `HSK-056` and `HSK-067`).

---

### 1. Scope

`HSK-001` `[MUST]` The HSK feature browses the official **HSK 3.0** vocabulary syllabus, grouped by
level, and links each syllabus word to its canonical dictionary entry in the active dataset.

`HSK-002` `[MUST]` HSK is available in **both dictionary modes**. The HSK destination (`PROD-050`)
and HSK badges (`HSK-040`) appear in Mandarin and in Cantonese mode alike; the syllabus, its levels
and its per-level row counts are the same in both. Cantonese-mode specifics (readings, definitions,
ordering, linking): `HSK-090`…`HSK-096`. *(Decided: `Q-030`.)*

`HSK-003` `[MUST]` Levels are `1..7`, where level `7` represents the combined band **7-9** and is
always labelled `HSK 7-9`. Reference row counts (HSK 3.0, 2026 edition): L1 300, L2 204, L3 507,
L4 1019, L5 1638, L6 1815, L7-9 5622 — 11105 rows total, covering about 11090 distinct words.
*(Whether these counts match the published standard is an open question in
[09 — Open Questions](09-open-questions.md).)*

---

### 2. Level navigation

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

`HSK-015` `[MUST]` The selector surface carries the **pronunciation-visibility toggle button**
(`GLB-120`) at its trailing end, outside the scrolling chip row. Toggling it shows or hides the
readings on every HSK card at once without reloading the grid, without changing card heights (space
stays reserved, `GLB-120`) and without moving the scroll position.

---

### 3. Grid and cards

`HSK-020` `[MUST]` A staggered (masonry) vertical grid: 1 column below 600 dp, 2 columns at 600-839
dp, 3 columns at 840 dp and above. Level headers span the full line; word cards occupy a single lane.
Horizontal spacing 10 dp; cards carry their own 4 dp vertical padding.

`HSK-021` `[MUST]` A level header renders the level badge with its row count: `HSK 3 (507)`.

`HSK-022` `[MUST]` Each word card is the shared entry card (`ENT-070`) with:
- the canonical dictionary entry's content when the row is linked, its reading shown or hidden per
  the pronunciation-visibility setting (`GLB-120`);
- container colour = the level's paper tint (`GLB-065`);
- merge grouping **not** applied (each syllabus row is its own card);
- an HSK badge footer showing the levels the entry belongs to.

`HSK-023` `[MUST]` An **unlinked** syllabus row (no canonical dictionary entry) renders a fallback
card built from the syllabus data alone: word, traditional form, syllabus reading (visibility per
`GLB-120`), syllabus definitions, and the level badge. The card is **disabled** — not tappable and
with no play button.

`HSK-024` `[MUST]` Tapping a linked card opens that entry's detail (`ENT-010`).

`HSK-025` `[MUST]` Each linked card's play button reflects the real availability of pronunciation
audio for the canonical entry. Hiding readings (`GLB-120`) does not affect the play button.

`HSK-026` `[MUST]` Grid item keys must be unique and stable across scrolling. **The tuple
(level, word, reading) is not unique** in the source data — the reference HSK data contains 14
collisions (for example 局 and 料 each appear twice within level 6, and level 7-9 has six more)
because the syllabus lists a word once per part of speech. Keys must come from a per-row stable
identifier supplied by the dataset (`DATA-120`). Duplicate keys crashed the reference app on level 6
before it was patched.

`HSK-027` `[MUST]` Beyond keying, the successor must **de-duplicate the rows themselves**: one card
per (level, word, reading), with the parts of speech for that level merged into the card's badge. The
reference app still renders two identical cards for 局 in level 6.

---

### 4. Ordering

`HSK-030` `[MUST]` Within a level, rows are ordered **alphabetically by pronunciation** (Pinyin in
Mandarin mode; Jyutping in Cantonese mode, `HSK-093`), using this comparator:
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

### 5. HSK metadata elsewhere in the app

`HSK-040` `[MUST]` An entry's HSK levels appear as outlined badge pills in: search result cards,
compact rows, sheet rows, HSK cards, and the entry-detail header, in both dictionary modes. Pill text
is `HSK n` (or `HSK 7-9`).

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
Labels are shown in the UI language (`GLB-100`), so the table carries Chinese labels as well as
English ones.

`HSK-045` `[SHOULD]` The translation table belongs in the dataset (`DATA-122`) so a new part-of-speech
token does not require an app release.

---

### 6. Relationship between HSK data and dictionary records

`HSK-050` `[MUST]` Each syllabus row is joined **during preprocessing** to exactly one canonical
dictionary entry, or explicitly marked unlinked. The app performs **no** joining at runtime.

`HSK-051` `[MUST]` The join key is (simplified form, traditional form, reading), with reading matching
tolerant of syllabus-vs-dictionary differences (`HSK-052`). When several dictionary entries qualify,
the pipeline picks the most frequent non-proper-noun reading and records the decision. (Cantonese
dataset: `HSK-090`.)

`HSK-052` `[MUST]` The syllabus and CC-CEDICT disagree on readings for about 257 of 11105 rows,
because the syllabus writes **tone sandhi and neutral-tone conventions** that CC-CEDICT does not:
`不客气 bú kè qi` vs `bù kè qi`; `一半 yíbàn` vs `yī bàn`; `那边 nàbiān` vs `nà bian`;
`学生 xuéshēng` vs `xué sheng`. Some rows carry two readings separated by `/` (`谁 shéi/shuí`). The
reading match must therefore be **tone-insensitive with a tone-sensitive preference**, and must split
on `/`.

`HSK-053` `[MUST]` The app **displays the canonical dictionary reading** for linked rows, not the
syllabus reading, so the same word reads identically everywhere in the app. Like every reading, it is
shown or hidden per `GLB-120`.

`HSK-054` `[SHOULD]` The syllabus reading should nevertheless be preserved in the dataset and shown as
a secondary "HSK reading" on the entry page when it differs, because the sandhi form is pedagogically
what the learner is taught. *(New behaviour — `Q-031`.)*

`HSK-055` `[MUST]` Syllabus definitions are kept as a **separate provenance** from dictionary
definitions. Linked cards show the dictionary definitions; unlinked cards show the syllabus
definitions. *(Whether linked entries should also surface a differing syllabus gloss is `Q-032`.)*

`HSK-056` `[MUST]` The pipeline must **fail its validation gate** on unlinked rows above a small
allowance, and must emit the list of unlinked rows. The reference dataset silently shipped 9 unlinked
rows — 新媒体, 新能源, 不予, 精彩纷呈, 居于, 口哨儿, 两翼, 压轴, 致力于 — which appear in the grid as
dead, untappable cards. Most are joinable after normalising the traditional form or splitting variant
readings; genuinely absent words must be added to the dictionary dataset as syllabus-sourced entries
rather than shipped unlinked.

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

`HSK-067` `[MUST]` The load must not fail wholesale because one row cannot be linked. The reference
implementation threw when any linked id was missing from the dictionary, taking down the whole
screen. Unresolvable rows degrade to the disabled fallback card.

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

`HSK-090` `[MUST]` The Cantonese dataset carries its own HSK row table: the pipeline joins every
syllabus row to the Cantonese dataset by written form — traditional form first, simplified form as a
cross-check — never by reading, since the syllabus reading is Pinyin (`DATA-187`). The app performs
no joining at runtime (`HSK-050`). Where the written form has several Cantonese entries (one per
reading, `DATA-184`), the pipeline picks one deterministically (the entry sharing senses with the
Mandarin-linked entry, then the most frequent) and records the decision. Unlinked rows are gated as
in `HSK-056`.

`HSK-091` `[MUST]` Per-level row counts, levels, de-duplication and parts of speech are identical in
both modes (`HSK-003`, `HSK-027`, `HSK-042`); only the linked entry differs.

`HSK-092` `[MUST]` Linked cards in Cantonese mode show the linked Cantonese entry's **Jyutping**
(visibility per `GLB-120`) and its definitions **Cantonese-first**, with Mandarin-sourced definitions
as fallback, labelled by provenance (`PROD-045`). An unlinked row's fallback card (`HSK-023`) shows
the syllabus Pinyin labelled `Mandarin`.

`HSK-093` `[MUST]` In Cantonese mode rows are ordered by the linked entry's **Jyutping** with the
`HSK-030` comparator; unlinked rows sort after the linked rows of their level, by their syllabus
Pinyin. The Jyutping sort key is precomputed in the Cantonese dataset (`DATA-121`, `DATA-187`).

`HSK-094` `[MUST]` Entry badges in Cantonese mode (`HSK-040`, `HSK-041`) derive from the Cantonese
HSK row table, so an entry shows the same levels and per-level parts of speech as its Mandarin
counterpart.

`HSK-095` `[SHOULD]` Where `HSK-054` is adopted, the entry page in Cantonese mode shows the syllabus
reading as a secondary "HSK reading" labelled `Mandarin`.

`HSK-096` `[MUST]` The widget (`SET-060`) and the Word of the Day card (`SRCH-115`) draw from the HSK
row table of the active mode's dataset.

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

`SET-003` `[MUST]` Changing any setting takes effect immediately and is persisted immediately; there is
no Save button.

`SET-004` `[MUST]` Below a divider: an `About & Attribution` block with the app version
(`YYYY.MM.DD-X`, see the release section of [08 — Engineering](08-engineering.md)), the **dataset
version** of the active dataset (source versions + pipeline commit, `PIPE-166`), an explanatory
paragraph, a button opening an **open-source library licences** dialog (`SET-043`), a
`Data sources` heading, and one card per bundled data source or model (`SET-040`). Long-pressing the
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
| `tone_colors` | bool | `true` | |
| `show_pronunciation` | bool | `true` | global reading visibility (`SET-017`, `GLB-120`) |
| `show_boundaries` | bool | `true` | word-boundary underlines |
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
reading draft, voice transcript, HSK scroll position, sheet state (word-candidate and handwriting
sheets), flash mode, and whether Settings was open.

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
| HSK 3.0 word list (committed in the repository; from the HSK 3.0 syllabus vocabulary parser) | HSK levels, parts of speech, syllabus glosses | MIT (parser); redistribution terms of the list itself: open question |
| Tatoeba / Tatoeba Challenge | example sentence pairs | CC BY 2.0 FR |
| OpenCC | simplified/traditional conversion of examples (build time) and of voice transcripts (runtime table) | Apache-2.0 |
| audio-cmn (Shtooka-derived) | recorded syllable and word pronunciations | CC BY-SA |
| hanzi-writer-data (Make Me a Hanzi derived) | stroke outlines, order, medians | MIT / Arphic Public License |
| libime language model (`lm_sc.arpa`, fcitx) | Mandarin word bigrams for multi-word combinations (`SRCH-060`…) | LGPL-2.1-or-later |
| rime-cantonese `essay-cantonese.txt` | Cantonese language-model corpus | CC BY 4.0 |
| HKCanCor | Cantonese language-model corpus | CC BY 4.0 |
| Cantonese Wikipedia (zh-yue) | Cantonese language-model corpus | CC BY-SA 4.0 |

Models and recognition engines (mobile only unless stated):

| Component | Role | Licence |
|-----------|------|---------|
| sherpa-onnx | voice recognition runtime (`VOICE-001`…) | Apache-2.0 |
| onnxruntime | inference runtime used by sherpa-onnx | MIT |
| Streaming Paraformer trilingual model (zh / Cantonese / en) | voice recognition model | per model card (wording: open question) |
| ML Kit Text Recognition (Chinese) | OCR destination (`CAM-006`…) | Google ML Kit terms |
| ML Kit Digital Ink Recognition models | handwriting recognition (`HW-001`…); also on Web | Google ML Kit terms |

`SET-041` `[MUST]` Licence obligations that must be honoured in the shipping app: CC BY and CC BY-SA
sources require attribution; CC BY-SA sources also require that derived dictionary data remain under
a compatible licence; LGPL-2.1+ material (the Mandarin language model, in derived form) keeps its
licence notice and source availability. The app itself is open source (copyleft acceptable); the
choice of the app's own licence must be compatible with every source above — including the Arphic
Public License of the stroke data — and with App Store distribution. *(Open question — `Q-070`.)*

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
- `[SHOULD]` `Voice` — opens the Voice destination (`VOICE-001`…), as an Android static shortcut;
  mobile only.

`SET-051` `[MUST]` The clipboard shortcut must defer its clipboard read until the window actually has
focus (`TXT-053`), so it works when launched cold.

`SET-052` `[MUST]` A shortcut's action is **consumed** on handling (the intent action is reset) so
activity recreation, rotation, or a later Back does not re-trigger it.

`SET-053` `[MUST]` On iOS these become Home Screen quick actions (the Voice one optional); on Web they
are omitted (or `Read clipboard` is exposed as a PWA shortcut if a manifest is shipped), since Web has
no OCR or Voice destination.

---

### 6. Share-image target

`SET-054` `[MUST]` The app registers as a share target for `image/*`. Receiving a shared image opens
the OCR destination (`CAM-006`) in gallery-review mode and runs OCR on it (`CAM-040`).

`SET-055` `[MUST]` The shared URI is read once; the intent is consumed; only the URI's scheme and
authority may be logged.

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
does not apply to the widget.

`SET-063` `[MUST]` The word is derived **deterministically** from the device-local date and the level
mask: filter to words in the selected levels, de-duplicate by entry id, sort by entry id, then take
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
route `/word/{natural-key}` (`GLB-110`), carrying the entry's natural key (`DATA-014`, `DATA-212`),
never its `entry_id`. The app switches to Search, clears any existing detail stack, and pushes that
entry; if the dataset is still opening it waits and then opens it.

`SET-065` `[MUST]` The widget has a speaker button that plays the word's pronunciation from the audio
installed with the app (on mobile all audio is installed up front, `MED-010`) **without opening the
dictionary database**.

`SET-066` `[MUST]` The widget reads a small dedicated projection asset (`DATA-130`) — entry id, natural
key, levels, both scripts, readings, header notes, definitions, notes — never the full dictionary. It
must render with a bounded, small memory and time cost inside the widget host's limits.

`SET-067` `[MUST]` The widget refreshes on date change, time set and timezone change.

`SET-068` `[MUST]` iOS equivalent (WidgetKit) is desirable but **optional** for v1. Web has no
equivalent. *(Scope decision — `Q-071`.)*

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

### Deferred (post-v1)

The Android screen-assistant integration is deferred (`PROD-030`); these IDs are reserved and not
normative in v1. The microphone is used only by the Voice destination (`VOICE-001`…, `ARCH-104`).

- `SET-070` App selectable as the default digital assistant; screenshot run through OCR.
- `SET-071` Assist-structure text analysed in Reading when no screenshot is available.
- `SET-072` Inline message when neither screenshot nor text is available.
- `SET-073` Interaction service in a separate process; screenshot handed over without writing to disk.
- `SET-074` Recognition-service stub required by the voice-interaction metadata (Android 11 and earlier).
- `SET-075` Settings row with assistant status and a deep link to system assistant settings.
- `SET-076` No launch over the lock screen.
- `SET-077` Scope recommendation (now decided: deferred, `Q-072`).
- `SET-085` Assistant section absent when the platform reports no assistant role.
