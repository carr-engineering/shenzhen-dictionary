# 06 — HSK, Settings and Platform Integration

## HSK

Read [02 — Global Conventions](02-global-conventions.md) first. The reference app has **known data
bugs** in this area; do not reproduce them (see `HSK-070` and findings `F-050` to `F-054`).

---

### 1. Scope

`HSK-001` `[MUST]` The HSK feature browses the official **HSK 3.0** vocabulary syllabus, grouped by
level, and links each syllabus word to its canonical dictionary entry.

`HSK-002` `[MUST]` HSK is **Mandarin-mode only**. In Cantonese mode the HSK destination is hidden and
HSK badges do not appear anywhere. *(Whether a Cantonese-specific level system should replace it is
`Q-030`.)*

`HSK-003` `[MUST]` Levels are `1..7`, where level `7` represents the combined band **7-9** and is
always labelled `HSK 7-9`. Reference row counts (HSK 3.0, 2026 edition): L1 300, L2 204, L3 507,
L4 1019, L5 1638, L6 1815, L7-9 5622 — 11105 rows total, covering about 11090 distinct words.

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

---

### 3. Grid and cards

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

### 4. Ordering

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

### 5. HSK metadata elsewhere in the app

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

### 6. Relationship between HSK data and dictionary records

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

### 7. Loading behaviour and performance

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

### 8. Persistence

`HSK-070` `[MUST]` The grid scroll position survives configuration change within a session
(`GLB-050`) but is not persisted across launches.

`HSK-071` `[MUST]` No per-level "last viewed" preference is persisted. The destination always opens at
level 1 on a cold start.

`HSK-072` `[MUST]` The home-screen widget keeps its own persisted level mask per widget instance
(`SET-060`).

---

### 9. Edge cases (all `[MUST]`)

`HSK-080` A word belonging to several levels shows one badge per level, ascending.

`HSK-081` A level with zero rows after de-duplication is omitted from both the grid and the selector.

`HSK-082` A syllabus row with an empty part of speech contributes no part-of-speech label.

`HSK-083` A syllabus row whose two `/`-separated readings resolve to different dictionary entries must
resolve to one card in the pipeline (preferring the more frequent reading) and record the alternative.

`HSK-084` Tapping a card while the entry-detail stack already has routes pushes onto it as usual.

`HSK-085` Switching to Cantonese mode while the HSK destination is active moves the user to Search.

## Settings and Platform Integration

---

### 1. Settings screen

`SET-001` `[MUST]` Settings is a primary destination (`PROD-050`), a scrolling list with a `Settings`
heading, sections separated by 18 dp, 20 dp horizontal padding, and status/nav-bar insets applied.

`SET-002` `[MUST]` Controls, in order:

| Control | Type | Options / default |
|---------|------|-------------------|
| Dictionary | segmented | `中文 Mandarin` / `粵語 Cantonese` — default Mandarin |
| Default script | segmented | `简体` / `繁體` — default Simplified (Mandarin), Traditional (Cantonese) |
| Theme | segmented | `Light` / `Dark` / `Auto` — default Auto |
| Tone coloring | switch | default on |
| Reading boundaries | switch | default on |
| Save photos to gallery | switch | default off (`CAM-080`) — Android/iOS only |
| Screen assistant | status + button | Android only, only when the assistant role exists |

`SET-003` `[MUST]` Changing any setting takes effect immediately and is persisted immediately; there is
no Save button.

`SET-004` `[MUST]` Below a divider: an `About & Attribution` block with the app version and build
number, an explanatory paragraph, a button opening an **open-source library licences** dialog, a
`Data sources` heading, and one card per bundled dataset.

---

### 2. Persisted settings

`SET-010` `[MUST]` Persisted keys and semantics:

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `dictionary_mode` | enum | `mandarin` | `PROD-040` |
| `script` | enum | `simplified` | per mode (`SET-013`) |
| `theme_mode` | enum | `system` | |
| `tone_colors` | bool | `true` | |
| `show_boundaries` | bool | `true` | word-boundary underlines |
| `search_side` | enum | `english` | only written per `SRCH-026` |
| `last_search_query` | string | `""` | debounced 750 ms, flushed on stop |
| `camera_auto_save` | bool | `false` | `CAM-080` |

`SET-011` `[MUST]` Script preference is stored **per dictionary mode**, so switching modes restores that
mode's script rather than carrying one over.

`SET-012` `[MUST]` Unknown or corrupt stored values fall back to the default without an error and
without wiping the rest of the store.

`SET-013` `[MUST]` `last_search_query` is stored per dictionary mode so switching modes restores that
mode's last query.

`SET-014` `[MUST]` Not persisted across launches: the entry-detail stack, camera state, reading draft,
HSK scroll position, sheet state, flash mode.

`SET-015` `[MUST]` A settings change that affects widget appearance (script, tone colours, theme)
triggers a widget refresh (`SET-060`).

`SET-016` `[MUST]` Settings load asynchronously. Until they arrive the app renders defaults; when they
arrive, they apply — **except** that a query the user has already typed is never overwritten
(`SRCH-142`).

---

### 3. Theme

`SET-020` `[MUST]` `Light` / `Dark` / `Auto` (follow system). The resolved theme drives the palette
(`GLB-060`) and the system bar icon contrast (`GLB-070`).

`SET-021` `[MUST]` The theme applies to the widget as well (`SET-062`).

`SET-022` `[SHOULD]` The design is a fixed brand palette; platform dynamic colour is deliberately not
adopted.

---

### 4. Attribution

`SET-040` `[MUST]` Each bundled data source is credited with: name, the role it plays in the app, its
licence, and one or more outbound links. Sources in the reference build, all of which the successor
must continue to credit when it ships the same data:

| Source | Role | Licence |
|--------|------|---------|
| CC-CEDICT (via MDBG) | Mandarin headwords, Pinyin, English definitions, variants, notes | CC BY-SA 4.0 |
| CC-Canto / Cantonese dictionary data | Cantonese readings and definitions | CC BY-SA 3.0 (verify at build time) |
| FrequencyWords (zh_cn, OpenSubtitles 2018 via OPUS) | word frequency for ranking and segmentation | CC BY-SA 4.0 |
| HSK 3.0 syllabus vocabulary parser | HSK levels, parts of speech, syllabus glosses | MIT |
| Tatoeba / Tatoeba Challenge | example sentence pairs | CC BY 2.0 FR |
| OpenCC | build-time simplified/traditional conversion of examples | Apache-2.0 |
| audio-cmn (Shtooka-derived) | recorded syllable and word pronunciations | CC BY-SA |
| hanzi-writer-data (Make Me a Hanzi derived) | stroke outlines, order, medians | MIT / Arphic Public License |
| Make Me a Hanzi | IDS decompositions, etymology hints | LGPL-3.0 (Arphic-derived) |
| CJKVI IDS | fallback decompositions | GPLv2 |
| chinese-lexicon (Dong Chinese) | character definitions, component roles, etymology notes | no licence file in the pinned repo; included with prominent attribution |
| Xiaoxuetang glyph mirror | historical glyph images | CC0 1.0 |
| Wikimedia character images | fallback historical glyphs | public domain |

`SET-041` `[MUST]` Licence obligations that must be honoured in the shipping app: CC BY-SA sources
require attribution **and** that derived dictionary data remain under a compatible licence; GPLv2 data
(CJKVI IDS) and LGPL data (Make Me a Hanzi) must be reviewed before inclusion in a closed-source
binary. *(Legal review item — `Q-070`.)*

`SET-042` `[MUST]` Each source's exact version/commit/build date is recorded in the dataset metadata
and surfaced in the attribution card (`DATA-200`), so a user can tell which build of CC-CEDICT they
have.

`SET-043` `[MUST]` A separate dialog lists direct runtime library dependencies with version, role,
licence and URL.

`SET-044` `[MUST]` Links open in the platform browser.

---

### 5. Launcher shortcuts / quick actions

`SET-050` `[MUST]` Two static shortcuts:
- `Read clipboard` — opens the Reading destination and pastes (`TXT-057`);
- `Open camera` — opens the camera surface (`CAM-002`).

`SET-051` `[MUST]` The clipboard shortcut must defer its clipboard read until the window actually has
focus (`TXT-053`), so it works when launched cold.

`SET-052` `[MUST]` A shortcut's action is **consumed** on handling (the intent action is reset) so
activity recreation, rotation, or a later Back does not re-trigger it.

`SET-053` `[MUST]` On iOS these become Home Screen quick actions; on Web they are omitted (or exposed
as PWA shortcuts if a manifest is shipped).

---

### 6. Share-image target

`SET-054` `[MUST]` The app registers as a share target for `image/*`. Receiving a shared image opens
the camera surface in gallery-review mode and runs OCR on it (`CAM-040`).

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

`SET-062` `[MUST]` The widget follows the app's script, tone-colour and theme settings, and refreshes
when any of them changes.

`SET-063` `[MUST]` The word is derived **deterministically** from the device-local date and the level
mask: filter to words in the selected levels, de-duplicate by entry id, sort by entry id, then index by
a 64-bit mix of `epochDay XOR (levelMask << 32)` modulo the candidate count. The same date and mask
must always produce the same word; negative epoch days must work; changing the mask must change the
sequence.

`SET-064` `[MUST]` Tapping the widget opens **that exact entry's** detail in the app, via an explicit
intent carrying the entry id. The app switches to Search, clears any existing detail stack, and pushes
that entry; if the dataset is still opening it waits and then opens it.

`SET-065` `[MUST]` The widget has a speaker button that plays the word's pronunciation from packaged
assets **without opening the dictionary database**.

`SET-066` `[MUST]` The widget reads a small dedicated projection asset (`DATA-130`) — entry id, levels,
both scripts, readings, header notes, definitions, notes — never the full dictionary. It must render
with a bounded, small memory and time cost inside the widget host's limits.

`SET-067` `[MUST]` The widget refreshes on date change, time set and timezone change.

`SET-068` `[MUST]` iOS equivalent (WidgetKit) is desirable but **optional** for v1. Web has no
equivalent. *(Scope decision — `Q-071`.)*

---

### 8. Android screen-assistant integration

`SET-070` `[SHOULD]` The app may be selectable as the device's **default digital assistant**. When the
assistant gesture is invoked, it requests the assist structure and a screenshot, launches the app as an
assistant activity, and runs the screenshot through the same OCR path as gallery images.

`SET-071` `[MUST]` If a screenshot is unavailable (secure window, user disabled context sharing,
enterprise policy, OEM gap) but the foreground app exposed visible Hanzi through the assist structure,
that text is analysed in the Reading destination instead.

`SET-072` `[MUST]` If neither is available, show the inline message
`Screen capture is unavailable. You can still choose an image from the gallery.`

`SET-073` `[MUST]` The always-running interaction service lives in a **separate lightweight process**;
the session service shares the app process so the framework-owned bitmap can be handed to the UI
**without writing the screenshot to disk**.

`SET-074` `[MUST]` The platform requires a recognition-service component in the voice-interaction
metadata on Android 11 and earlier. The app ships a stub that immediately reports unsupported, never
opens the microphone, and the manifest must **not** request microphone permission.

`SET-075` `[MUST]` Settings shows the current status (`Default assistant: On/Off`) and a button
deep-linking to the system assistant settings. Selection is always an explicit user action in system
settings; the app never claims the role itself.

`SET-076` `[MUST]` Launching over the lock screen is disabled; the feature requires an unlocked device.

`SET-077` `[SHOULD]` This is a low-value, high-maintenance, OEM-dependent integration. Recommendation:
**defer it out of v1** and reinstate it only if usage justifies the two extra services and the extra
process. *(Scope decision — `Q-072`.)*

---

### 9. Edge cases (all `[MUST]`)

`SET-080` Switching dictionary mode while a detail stack is open clears it and re-runs the search
(`PROD-043`).

`SET-081` Switching script while a detail stack is open keeps the stack and re-fetches every route
(`ENT-092`).

`SET-082` A widget whose selected levels are all removed from a new dataset shows `Word unavailable`
rather than an empty card.

`SET-083` A widget tap for an entry id that no longer exists opens the app and shows the entry error
state.

`SET-084` The auto-save switch is absent on Web.

`SET-085` The assistant section is absent when the platform reports no assistant role.

