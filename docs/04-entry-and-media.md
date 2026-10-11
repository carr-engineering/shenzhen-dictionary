# 04 — Entry Detail and Media

## Entry Detail (word display)

Covers everything shown when the user opens a word. Read
[02 — Global Conventions](02-global-conventions.md) first. Search-result card presentation lives in
[03 — Search](03-search.md); this document owns the full word page and the shared card component's
data model.

---

### 1. Route model

`ENT-010` `[MUST]` Entry detail is a **stack of routes**. Each route has: an entry id, the
**dictionary mode** it renders in, a unique per-visit route key, the data-generation it was created
in, a request generation, and a content state of `Loading | Ready | Error`. A route's mode decides
which dataset its entry id, reads and nested lookups belong to (`GLB-044`). A route opened from the
app's own UI takes the user's persisted mode (`SET-010`); a route opened from a link takes the link's
mode (`GLB-110`, `ENT-093`); a route pushed from another route inherits that route's mode.

`ENT-011` `[MUST]` Selecting an entry pushes a route **immediately** in `Loading` state so the
transition starts on the same frame as the tap. When the data arrives, the route's content is
*replaced in place* — the route key, and therefore its saved state, does not change.

`ENT-012` `[MUST]` If the top route is still `Loading` when a new selection happens, the new route
**replaces** it rather than stacking on top of it (so a burst of taps cannot build a stack of
skeletons).

`ENT-013` `[MUST]` Selecting the entry that is already the top route is a no-op.

`ENT-014` `[MUST]` Back pops exactly one route, and only if the top route is still the one the back
gesture was issued for (guarded pop). A route is removed from state only **after** its exit animation
completes, so an exiting detail never degrades into an empty placeholder mid-animation.

`ENT-015` `[MUST]` Layout follows the list-detail rule (`GLB-125`):
- Single pane: the detail fills the screen; the list is not visible.
- Two-pane: the list stays visible; the detail pane shows the top route; a **close** action appears in
  the detail's app bar and clears the entire stack.

`ENT-016` `[MUST]` Route transitions:
- forward: incoming slides in from the trailing edge with alpha 0.72 to 1.0; outgoing shifts 12% in
  the opposite direction and fades to 0.88 — about 220 ms;
- back: mirrored;
- when the single-pane detail itself is closing, the pane transition owns the motion and the route
  adds only a small 8% slide;
- content-state change within a route (Loading to Ready/Error) is a cross-fade (180 ms in / 120 ms
  out), never a slide.

`ENT-017` `[MUST]` Loading and error states keep the app bar with a working back (and close, when
two-pane) affordance. The loading state is a skeleton (`ENT-018`), the error state is a centred
message from `GLB-034`.

`ENT-018` `[MUST]` The loading skeleton approximates the real header: a 126x42 dp block (headword), a
152x18 dp block (reading), a row of three equal 30 dp blocks (tabs), then three text bars at 92%, 78%
and 64% width. When pronunciation is hidden (`GLB-120`) the reading block keeps its space but is not
drawn, matching the real header.

`ENT-019` `[MUST]` Empty-detail placeholder (two-pane layout per `GLB-125`, nothing selected): centred
`titleMedium` muted text `Select an entry to see its detail`.

---

### 2. Header

`ENT-020` `[MUST]` The header, above the tab strip, contains:
1. **Headword** in `displaySmall` semibold, tone-coloured per character when tone colouring is on and
   the character count matches the syllable count. It steps down to `headlineMedium` when the headword
   exceeds 8 code points, and wraps across lines.
2. **Alternate form(s)** in `titleMedium` muted, baseline-aligned, wrapping to its own line if needed:
   the merged variant list joined by ` · ` when the entry is part of a merge group, otherwise the
   other script form when it differs, otherwise nothing.
3. A row with the **play button** (40 dp, outlined, accent), the **pronunciation** in
   `titleMedium` medium-weight accent, tone-coloured, allowed to wrap, and the **pronunciation
   toggle** button (`GLB-120`) at the trailing end. When pronunciation is hidden, the reading is not
   shown but its space is reserved (the row is measured as if it were shown) so the header does not
   reflow; the play button stays available (`MED-001`) and the headword keeps its tone colours. A
   Cantonese entry with no Jyutping shows `No Cantonese reading` here instead (`ENT-116`).
   **Alternative readings.** When the entry has alternative readings in the active romanisation —
   its `also_pr` / `taiwan_pr` notes that are full readings of the headword (`DATA-192` eligibility:
   same syllable count, fully parsed; the same readings search matches, `SRCH-078`) — a wrapping
   line directly below this row lists each one as its label from the label table (`ENT-057`) followed
   by the reading, tone marks and tone colours as for the main reading, in note order and joined by
   ` • `: `Taiwan pronunciation lè sè`. The line is in `bodyMedium` muted; the label stays visible and
   only the readings follow pronunciation visibility (`GLB-120`: not drawn, width kept). Those notes
   are not repeated in the notes row (`ENT-057`); partial alternative readings (one syllable of a
   longer word) and the Pinyin alternatives inside a Cantonese entry's Mandarin block stay notes. The
   line is part of the header's measured height, so collapse (`ENT-022`) is unaffected by visibility.
4. A wrapping row of **HSK badges**, one per level, each reading `HSK n` — or `HSK 7-9` for level 7 —
   and, when a part of speech is known for that level, `HSK n • Noun` (`HSK-040`). The levels are the
   entry's direct syllabus levels, its extra bracketed levels (`HSK-003`) and the levels it receives
   through variant relations (`HSK-097`, `DATA-193`); all render **identically** — a propagated badge
   carries the same pill and part of speech and has no marker distinguishing it.
5. Optional inline status text (e.g. a failed word-candidate lookup message).

`ENT-021` `[MUST]` Headword, alternate form and pronunciation are inside a text-selection container so
they can be long-pressed and copied. The play button and the pronunciation toggle are outside it so a
drag does not start a selection. A hidden reading (`GLB-120`) is not part of the selectable text.

`ENT-022` `[MUST]` The header **collapses** on upward scroll and restores on downward scroll at list
top, shared by every tab. Implementation must be placement-only (the pager keeps a constant measured
height and is translated) so collapsing does not re-measure per frame. When collapse exceeds 95%, the
app bar shows the headword as its title with a fade. Because a hidden reading keeps its reserved space
(`GLB-120`), the header height and collapse distance are the same whether readings are shown or
hidden; toggling visibility never changes the collapse fraction.

`ENT-023` `[MUST]` The collapse fraction is part of route-scoped saved state (`GLB-050`).

`ENT-024` `[SHOULD]` While optional assets are still loading, a small 58x12 dp placeholder block sits
in the badge row instead of any text. It disappears when assets resolve, whether or not any were
found.

---

### 3. Tabs

`ENT-030` `[MUST]` Tabs, in fixed order: `DICT`, `STROKES`, `WORDS`. `DICT` and `WORDS` are always
present; `STROKES` appears **only** when at least one character of the *displayed* headword has valid
stroke data with a stroke count greater than zero.

`ENT-031` `[MUST]` Tabs are an underline strip: full-width 2 dp `border` baseline, equal-weight
segments, uppercase letter-spaced labels, active label and its 2 dp underline in `accent`.

`ENT-032` `[MUST]` Tabs are horizontally swipeable (a pager). Tapping a tab animates to it.

`ENT-033` `[MUST]` Each tab keeps an **independent** scroll position; all three positions plus the
selected tab are route-scoped saved state.

`ENT-034` `[MUST]` The selected tab is authoritative on restoration: if a restored tab is no longer
available (stroke data changed), fall back to `DICT`; a pager's own internal saved page must not
override the restored route tab.

`ENT-035` `[MUST]` Changing the tab set while the pager is alive must not crash or read a stale item
provider. Key the pager subtree on the immutable tab list.

---

### 4. DICT tab

#### 4.1 Single-entry case

`ENT-040` `[MUST]` Content order:
1. optional **redirect header** (`ENT-045`), muted, with tappable Chinese references;
2. the **numbered sense list** — one paragraph per sense, numbered from 1, bold `fg` numbers,
   `bodyLarge` body, each sense selectable and with tappable Chinese references; domain and register
   tags lead the sense (`ENT-058`). In Cantonese mode the list is split into source blocks
   (`ENT-110`);
   An entry whose only source is Unihan (or the project supplement) shows its single labelled block
   (`ENT-126`);
3. **structured notes** joined by ` • `, muted, also with tappable references (`ENT-057`);
4. **Examples** section (`MED-040`; Cantonese mode `MED-048`);
5. the collapsible **Characters** section (`ENT-120`), always last.

`ENT-041` `[MUST]` Senses are never truncated in the DICT tab.

#### 4.2 Merged group case

`ENT-042` `[MUST]` When the entry belongs to a merge group with more than one member, the DICT tab
renders one **section per member that has definitions**, each introduced by a small-caps label naming
the member's alternate-script form: `Traditional: 髮` (or `Simplified: 发` in Traditional mode).

`ENT-043` `[MUST]` Sense numbering is **continuous across sections** (member A senses 1-3, member B
senses 4-5).

`ENT-044` `[MUST]` Each member's structured notes stay with that member's section, not collected at
the bottom. Members are ordered by direct-definition count descending, original order breaking ties.
De-duplication and redirect suppression rules are `SRCH-082`. In Cantonese mode each member section
contains that member's own source blocks (`ENT-110`).

#### 4.3 Redirects and variant expansion

`ENT-045` `[MUST]` Many entries have no definition of their own and instead point at another entry.
Three redirect kinds must be recognised: `variant_of`, `old_variant_of`, `erhua` (from prose
`erhua form of` / `erhua variant of` / `variant of`).

`ENT-046` `[MUST]` A redirect is resolved to a **single** target only when exactly one candidate entry
qualifies at the best matching tier. Tiers, best first:
1. traditional **and** simplified both equal the reference, and the reading matches;
2. both forms equal the reference;
3. one form matches and the reading matches;
4. one form matches.
Candidates must have non-empty definitions and must not be the source entry itself. If two candidates
tie at the best tier, the redirect stays **unresolved**.

`ENT-047` `[MUST]` When resolved, the redirect text becomes a muted header line and the **target's**
definitions become the entry's displayed senses. When unresolved, the redirect text is shown as the
entry's only content (the user still sees where to go, and the reference is tappable).

`ENT-048` `[MUST]` A structured redirect note that is consumed by the expansion is removed from the
notes row (so `variant of: 逼格[bi1 ge2]` does not appear twice).

`ENT-049` `[MUST]` A definition of the form `semantic text (variant of X[reading])` where the whole
parenthetical is the trailing content is treated as an **embedded** variant: within a merge group whose
other member is X, the parenthetical is stripped and only the semantic text is shown; standalone it is
left intact.

`ENT-050` `[MUST]` Redirect resolution must be **precomputed** in the dataset (`DATA-055`). The
reference implementation resolves redirects at read time with extra lookups per entry batch; the
successor must read a resolved `redirect_target_id` (nullable) and the target's sense ids directly.

#### 4.4 Definition prose parsing and internal links

`ENT-051` `[MUST]` Definition text contains structured markup that must be rendered as segments:
- **Structured reference**: `TRAD|SIMP[reading]`, `TRAD[reading]`, or a Latin/digit form with a
  reading, e.g. `一點|一点[yi1 dian3]`, `逼格[bi1 ge2]`, `CD[C D]`. Renders as the form for the active
  script, followed by the reading in parentheses with tone marks, and is a **tap target** covering the
  form.
- **Bare Hanzi run**: a maximal run of Hanzi (and interior middle dots) with no bracketed reading.
  Renders as-is and is a tap target.
- **Pronunciation-only bracket**: `[yi1 dian3]` with no preceding form. Rendered with tone marks
  inside square brackets and is **not** a tap target.
- **Plain text**: everything else, verbatim. Ordinary parentheses, brackets and punctuation must not
  be turned into markup.

`ENT-052` `[MUST]` A bracketed reading is only rendered as Mandarin tone marks when the dataset says
it is Mandarin. Non-Mandarin romanisations (Jyutping, Tâi-lô, POJ, Teochew, Wade-Giles) are shown
verbatim. Provenance is resolved in the pipeline (`DATA-053`), not by scanning surrounding prose at
runtime. In Cantonese mode the rules of `GLB-014` apply per source block (`ENT-110`): readings in the
Cantonese block are Jyutping; readings in the Mandarin block follow `GLB-014` items 2 and 3.

Every reading rendered inside definitions and notes — the parenthesised reading after a structured
reference, a pronunciation-only bracket, a verbatim non-Mandarin romanisation, and a note's own reading
(`ENT-057`) — follows pronunciation visibility (`GLB-120`). When hidden, the reading and its enclosing
parentheses or brackets are not drawn but keep their width, so lines wrap identically in both states.
Tap targets (`ENT-053`) are unaffected.

`ENT-053` `[MUST]` Tap targets are rendered in `accent` with an underline. A **long press** enters
text selection and must not activate the link (`GLB-056`).

`ENT-054` `[MUST]` Tapping a reference opens the word-candidate sheet (`TXT-040`) whose contents are:
1. every entry exactly matching the reference, ordered by the reference-ranking tiers of `ENT-046`;
   within a tier, ties are broken by the shared order of `GLB-013` (including where proper-noun
   readings fall), ending with id; the first is marked as the default;
2. followed by **every dictionary word contained inside** the referenced text, in the standard
   sub-span order (`SRCH-034`).

This is what makes `variant of 一點|一点[yi1 dian3]` offer both 一点 and 点.

`ENT-055` `[MUST]` A **multi-code-point** reference is one single tap range, not one per character.
Surrogate-pair characters keep their full range.

`ENT-056` `[MUST]` Selecting an entry from that sheet pushes a **nested** route, so Back returns to
the referring word with its tab and scroll intact.

#### 4.5 Notes, labels and tags

`ENT-057` `[MUST]` Structural conventions (note kinds as spelled in `DATA-060`) are rendered from the
dataset's **label table** (`DATA-060`, built by `PIPE-079`; decided: `Q-020`). The app never derives a
label from a source key or from prose. A convention renders as its label in the active UI language
(`GLB-100`), then `: ` and its details: references (form + parenthesised reading, tap targets per
`ENT-053`), then the note's own reading, then any extra text, space-joined. Multiple notes are joined
by ` • `. Whether a convention becomes an entry note (notes row) or stays inside its sense is the
pipeline's decision (`PIPE-079`); either way the label replaces the source phrase. An `also_pr` or
`taiwan_pr` note whose reading the header shows as an alternative reading (`ENT-020` item 3, identified
by its note ordinal in the entry's alternative readings, `ENT-080`) is omitted from the notes row; every
other note of those kinds is rendered here with its label. Label copy:

| Source convention (CC-CEDICT / CC-Canto) | `en` | `zh-Hans` | `zh-Hant` |
|---|---|---|---|
| `CL:`, `M:`, `[measure word]`, `classifier for` | Classifier | 量词 | 量詞 |
| `see` | See | 见 | 見 |
| `see also` | See also | 参见 | 參見 |
| `variant of` | Variant of | 异体 | 異體 |
| `old variant of` | Old variant of | 旧异体 | 舊異體 |
| `erhua variant of` | Erhua form of | 儿化 | 兒化 |
| `contracted variant of` | Contracted form | 合文 | 合文 |
| `abbr. for`, `(abbr.)` | Abbreviation for | 简称 | 簡稱 |
| `also pr.` | Also pronounced | 又读 | 又讀 |
| `Taiwan pr.` | Taiwan pronunciation | 台湾读音 | 台灣讀音 |
| `also written` | Also written | 也作 | 也作 |
| `used in` | Used in | 用于 | 用於 |
| `surname` | Surname | 姓 | 姓 |
| `Kangxi radical N` | Radical | 部首 | 部首 |

`ENT-058` `[MUST]` **Domain and register tags.** A leading parenthesised tag on the pipeline's
allow-list (about 60 tags, `PIPE-079`; e.g. `(idiom)`, `(fig.)`, `(literary)`, `(coll.)`, `(Tw)`,
`(slang)`, `(Cantonese)`, `(TCM)`) and CC-Canto's trailing register brackets (`[colloquial]`,
`[written]`, `[written and colloquial]`) are carried as tags on the sense (`DATA-060`) and rendered
as **small tags** before the sense text: `labelSmall`, muted text inside a 1 dp `border` outline, the
label in the active UI language from the label table (e.g. `idiom` / `成语` / `成語`). Several tags keep
source order. Tags are not tap targets and are not part of the selectable sense text. Parenthesised
text that is not on the allow-list (the long tail of leading tags) and scope notes such as
`(of sb/sth)` stay **inline** as plain text, verbatim.

#### 4.6 Cantonese mode: source blocks

`ENT-110` `[MUST]` In Cantonese mode the DICT tab groups an entry's senses into **source blocks**,
labelled per block, never per sense (decided: `Q-080`, `Q-082`). Order:
1. **Cantonese block** — the CC-Canto senses for this entry's Jyutping reading, labelled
   `Cantonese (CC-Canto)` (`粤语释义（CC-Canto）` / `粵語釋義（CC-Canto）`);
2. **Mandarin block** — the carried-over CC-CEDICT senses (`PROD-045`), labelled
   `Mandarin senses (CC-CEDICT)` (`普通话释义（CC-CEDICT）` / `普通話釋義（CC-CEDICT）`), followed on the
   same row by the Mandarin entry's Pinyin (tone marks, tone-coloured, subject to `GLB-120`).

Block labels use the small-caps style of `ENT-042` and are shown whenever the entry is displayed in
Cantonese mode, even when only one block exists. Sense numbering is continuous across blocks
(`ENT-043`). Which senses go into each block is decided by the pipeline (`DATA-182`, `PIPE-052`,
`PIPE-053`); the app renders the blocks as stored.

`ENT-111` `[MUST]` When a Cantonese block exists, the Mandarin block is **collapsed** by default: only
its label row (label, Pinyin, sense count, expand chevron) is shown. Tapping the row expands or
collapses it (180 ms size animation); collapsed senses are not focusable. The expanded flag is
route-scoped saved state (`GLB-050`). When there is no Cantonese block, the Mandarin block is shown
expanded and is not collapsible.

`ENT-112` `[MUST]` For a headword with one Mandarin reading (almost all multi-character words) the
Mandarin block holds the senses of the CC-CEDICT entry joined on (traditional, normalised Pinyin), and
its label row shows that Pinyin. When several Mandarin entries are carried over (correspondence not
determinable, `PIPE-053`), the block lists them in frequency order, each introduced by its own Pinyin
as a sub-label with its senses beneath.

`ENT-113` `[MUST]` **Single-character polyphones.** The Cantonese senses are split per Jyutping
reading: each reading is its own entry (`DATA-184`) whose Cantonese block holds the CC-Canto senses for
that reading. The Mandarin senses of **all** Mandarin entries for the character are attached to the
headword as **one** Mandarin block, identical on every Cantonese entry of that character, each Mandarin
entry introduced by its Pinyin (`ENT-112`). The app never presents a Mandarin reading or sense as
belonging to a particular Jyutping reading. Example: 行 has entries `hang4`, `haang4`, `hang6`, `hong4`
and `hong2`, each with its own Cantonese block; all five show the same Mandarin block listing `xíng`,
`háng` and the other Mandarin readings.

`ENT-114` `[MUST]` CC-Canto lines marked `adapted from cc-cedict` are treated as Cantonese reading
splits: their senses form a Cantonese block labelled `Cantonese (CC-Canto, adapted from CC-CEDICT)`
(`粤语释义（CC-Canto，改编自 CC-CEDICT）` / `粵語釋義（CC-Canto，改編自 CC-CEDICT）`), placed directly after
an original `Cantonese (CC-Canto)` block when the entry has both.
- **Single-character polyphones** (`ENT-113`): adapted lines are **never** suppressed. They are the
  only per-Jyutping split of the meaning (e.g. 行 `hong4` "row; profession" vs `hang4` "to walk"),
  so every Jyutping entry keeps its adapted Cantonese block even when its text repeats the shared
  Mandarin block.
- **Monophones** (headwords with one Jyutping reading): an adapted sense whose normalised text
  duplicates a sense of the current CC-CEDICT release in the Mandarin block is suppressed by the
  pipeline (`PIPE-052`); when every adapted sense is suppressed and no original CC-Canto sense
  exists, the entry has no Cantonese block and `ENT-111`'s expanded case applies.

`ENT-115` `[SHOULD]` When the Mandarin block nearly duplicates the Cantonese block (at least 90% of
its normalised sense text already present there), the pipeline should omit the Mandarin block.
*(Decided from the source analysis — confirm with real entries.)*

`ENT-116` `[MUST]` **No Cantonese reading** (decided: `Q-115`, option (a)). A CC-CEDICT
headword with no Jyutping in any Cantonese source is shown in Cantonese mode with the muted note
`No Cantonese reading` (`无粤语读音` / `無粵語讀音`) in the header's pronunciation slot (`ENT-020`) and only the Mandarin block, expanded, with its Pinyin. No reading is ever guessed or
generated. The note is not a reading, so it stays visible when readings are hidden (`GLB-120`). Tone
colouring is off for the headword; the play button follows `MED-001` (TTS may still speak it).

#### 4.7 Unicode (Unihan) fallback block

`ENT-126` `[MUST]` **Unihan as a fallback source** (`DATA-190`, `DATA-022`). A character with no
CC-CEDICT, CC-Canto or supplement definition — including components and radicals mapped to unified
ideographs — is a **full entry** whose senses come from the Unicode `kDefinition` (`unihan` block). It
opens, renders and links exactly like any other entry, in both modes:
- **Block.** The senses form one source block labelled `Unicode (Unihan)` (`Unicode（Unihan）` in both
  Chinese UI languages; label table, `DATA-060`), in the small-caps style of `ENT-042`, shown in
  **both** modes (unlike the unlabelled Mandarin block of the Mandarin dataset). Senses are numbered
  from 1 and parsed like other senses: a `U+XXXX` mention is a tap target to that character
  (`ENT-051`, `ENT-053`).
- **Fallback only.** The block is the primary (and only) definition of such an entry; it never appears
  beside a CC-CEDICT, CC-Canto or supplement block. A character that has any dictionary entry —
  including a redirect-only variant entry — shows its dictionary content and no Unihan text. The app
  renders blocks as stored; the pipeline guarantees exclusivity (`DATA-022`).
- **Readings.** The header reading comes from `kMandarin` (Mandarin mode) or `kCantonese` (Cantonese
  mode) (`DATA-190`); a second Mandarin value or further values are alternative readings shown per
  `ENT-020` item 3. Without a reading for the active language the pronunciation slot keeps its height
  empty in Mandarin mode and shows `No Cantonese reading` in Cantonese mode (`ENT-116`). No reading is
  ever borrowed from the other language.
- **Rest of the page.** Alternate form per `ENT-020` (the `kSimplifiedVariant` / `kTraditionalVariant`
  form when one was stored); no HSK badges; the WORDS tab works as for any single character
  (`ENT-100`); the `STROKES` tab appears only when stroke data exists (`ENT-030`); the play button
  follows `MED-001`/`MED-011` (TTS only; no recording exists); the Characters section applies
  (`ENT-120`).

The project **supplement** block (`DATA-022`, `PIPE-044`; an HSK word CC-CEDICT lacks, e.g. 精彩纷呈) is
presented the same way: one block labelled `Shenzhen Dictionary supplement`, shown in both modes,
never beside another block.

#### 4.8 Characters (decomposition)

Each Hanzi of the displayed headword can be broken down into its components. This is **not** the
deferred Character origins feature (`MED-050`…`MED-058`: etymology, historical forms): decomposition
ships in v1 from the `decomposition` pack (`DATA-191`, built by `PIPE-081` from BabelStone IDS), and no
rule here depends on origin data. It is separate from the inside-words button (`ENT-073`), which lists
dictionary *words* inside a word.

`ENT-120` `[MUST]` **Section.** A collapsible section titled `Characters` (`汉字构成` / `漢字構成`;
label table, `DATA-060`) with the count of its characters is the **last** section of the DICT tab
(`ENT-040`), after Examples, in both modes and for single- and multi-character headwords alike:
- It lists the **distinct Hanzi** of the displayed headword (active script), in headword order, each
  once; Latin letters, digits and punctuation are skipped. The section is omitted when the headword has
  no Hanzi or when no listed character has a decomposition record.
- It is **collapsed by default**: only its label row (label, count, expand chevron) is shown. Tapping
  the row expands or collapses it with the 180 ms size animation of `ENT-111` (instant with reduce
  motion, `GLB-085`); collapsed content is not focusable. The section's expanded flag and every
  expanded part (keyed by its path of component positions) are route-scoped saved state (`GLB-050`).
- Decomposition data is read **only when the section is expanded** (`ARCH-133`), never as part of the
  entry read (`ENT-080`, `ENT-081`).

`ENT-121` `[MUST]` **Tree per character.** Each listed character is a top-level row (`ENT-122`)
followed by its **structure line** — the Ideographic Description Sequence's operator and its immediate
components, e.g. `⿰ 亻 木` for 休, drawn in `titleMedium` with the operator muted — and then one row per
immediate component, in IDS order. A component that is not a root has an expand chevron; expanding it
shows its own structure line and component rows beneath it, recursively, until **root** components:
- **Root rule** (`DATA-191`): a component is a root when its IDS is the character itself, when it is a
  single stroke (CJK Strokes block), or when it is a curated primitive (the 214 Kangxi radicals and
  their positional forms, e.g. 氵 扌 亻 忄 艹 ⺍). Roots have no chevron and no structure line.
  Expansion down to strokes is not offered in v1.
- A top-level character that is itself a root (木) shows only its row, without chevron.
- Nested levels are indented 16 dp per level behind a 2 dp `border` indent bar (as `SRCH-105`), with
  the visual indent capped at four levels; deeper levels still expand.
- A component that recurs on its own path (a malformed cycle in the data) is shown as a root.
- The tree is read-only: no selection (long press does nothing), no drag.

Example (Simplified): 想 → `⿱ 相 心`; 相 → `⿰ 木 目`; 木, 目 and 心 are roots.

`ENT-122` `[MUST]` **Part row.** Every row — top-level character or component — shows, on one line
that wraps:
1. the **glyph** (`titleLarge` for top-level characters, `titleMedium` for components), tone-coloured
   per `GLB-066` when its reading is shown;
2. its **reading** in the active romanisation (Pinyin in Mandarin mode, Jyutping in Cantonese mode),
   accent, following `GLB-120` (not drawn, width kept);
3. a **short definition** — the first sense of the part's entry — in muted `bodyMedium`, clamped to
   two lines: from the CC-CEDICT entry in Mandarin mode; in Cantonese mode the first sense of its
   first block (CC-Canto first, else the Mandarin block); from the supplement or the Unihan block when
   that is the entry's only source (`ENT-126`), with the muted `Unicode (Unihan)` tag before it.

Reading choice: when a character has several entries (readings), the row shows the entry first in
`GLB-013` order; for a **top-level** character whose position aligns with the headword's reading
(syllable count equals character count), the entry whose reading matches that syllable is used
instead (行 in 银行 shows `háng`), as parent-reading promotion does for sub-spans (`SRCH-104`).

The whole row is one **tap target** (except unencoded, unknown and entry-less parts, `ENT-123`):
with exactly one entry for the part it pushes that entry as a nested route (`ENT-056`); with several,
it opens the word-candidate sheet (`TXT-040`) listing them in `ENT-054` order, the row's shown entry
marked default. The expand chevron is a separate tap target (at least 48 dp, `GLB-080`) and never
opens the entry. Parts are looked up in the active dataset's headword index with **one batched lookup
per expansion level** (`DATA-191`, `ARCH-020`), never one read per part.

`ENT-123` `[MUST]` **Special parts** (`DATA-191`):
- **Radical forms** (Kangxi Radicals / CJK Radicals Supplement, U+2E80–U+2FDF) are shown with their
  own glyph followed by `= ` and the unified ideograph they map to (⻌ `= 辵`); reading, definition and
  tap target are that ideograph's entry. The **curated radical name** in the active UI language
  follows the definition as a muted tag (氵: `water` / `三点水` / `三點水`). Positional forms that are
  themselves unified ideographs (亻, 氵) carry the radical name the same way.
- A `〾X` (variation indicator) part is shown and linked as X.
- **Unencoded components** (synthetic ids) are drawn from their bundled SVG glyph at the glyph size
  of the row, in `fg` (tinted like text in both themes), with the muted label `Unencoded component`
  (`未编码部件` / `未編碼部件`). They have no reading, definition, tap target or chevron.
- **Unknown parts** (`？` in the source) show `？` muted with the label `Unknown part` (`未知部件` /
  `未知部件`); they are never expanded.
- An encoded part with **no entry** in the active dataset (no dictionary entry and no `kDefinition`)
  shows its glyph and, when the pack has one, its Unihan reading for the active language; it has no
  definition and is not tappable, but expands when it is not a root.
- **Overlay, subtraction and mirror** operators (⿻, ㇯, mirror forms) are shown in the structure line
  as stored; their parts are listed without any claim about position.

`ENT-124` `[MUST]` **Regional IDS.** The sequence used depends on the script and mode (`DATA-191`):
the **G** (mainland) sequence for the Simplified script, **T** (Taiwan) for Traditional in Mandarin
mode, **H** (Hong Kong) in Cantonese mode, each falling back to the source's default sequence when the
region has none. The listed characters follow the displayed headword in the active script, so
changing script refreshes the section (`ENT-092`) and may change both characters and structures;
expanded paths that no longer exist are dropped.

`ENT-125` `[MUST]` **States, accessibility and absence.**
- While an expansion's data is loading, the expanded area shows a skeleton of one row per expected
  part (or one row when unknown); failures and an absent `decomposition` pack (Web before or after a
  failed fetch, `MED-084`) follow `GLB-035`: expanding the section (or a part) shows the muted text
  `Not available` in place of the tree, and the rest of the entry is unaffected (`ENT-108`). On Web
  the pack is fetched on the first expansion and the tree replaces the skeleton when it arrives.
- Accessibility (`GLB-080`): each row's label reads the glyph, its reading (unless hidden,
  `GLB-122`), its short definition, the radical name or special-part label, and the expanded state;
  the structure line is announced as the operator's spoken name followed by its parts (⿰ "left to
  right", ⿱ "top to bottom", ⿴ "surrounding"…; ⿻ "overlaid"; the full operator list with names in the
  three UI languages comes from the label table).
- Expanding or collapsing keeps the tapped row where it is on screen (content below it moves) and does
  not change the header collapse fraction (`ENT-022`).
- Performance: reading and rendering one expansion level is the trace section `entry.decompose`
  (`PERF-100`) and never blocks the UI thread (`MED-082`).

#### 4.9 Grammar (v2, not in v1)

`ENT-127` `[SHOULD]` *v2 note, not part of v1.* In v2 an entry whose word is linked to grammar points
(`linked_word_ids` of the grammar data, `ARCH-198`, `PIPE-083`) shows a **Grammar** section in the DICT
tab, between Examples and Characters, listing those points (title in the UI language, HSK level,
pattern) and opening the point or its lesson; discovery per level lives in the HSK destination
(`HSK-099`). Only reviewed content is shown. v1 builds nothing for it beyond keeping the DICT tab's
section list open to one more section and the entry read (`ENT-080`) open to an optional linked-points
field.

---

### 5. WORDS tab

`ENT-060` `[MUST]` Sections, in order, each with a small-caps label and count, omitted when empty:
1. `Within 「word」` — dictionary words contained inside this word (sub-spans), longest-first;
   parent-reading promotion applies (`SRCH-104`).
2. `Starting with 「word」` — longer headwords beginning with it.
3. `Containing 「word」` — headwords containing it at position 1 or later.
4. `Containing reversed` — headwords containing the reversed headword (e.g. from 上海 find 海上).
   Omitted when the reversed string equals the original.

`ENT-061` `[MUST]` All rows are compact cards (`SRCH-097`) with the same content rules as search
result cards, including merge variants, clamped definitions and pronunciation visibility (`GLB-120`:
a hidden reading keeps its space, the play button stays). Rows of the `Starting with`, `Containing`
and `Containing reversed` sections whose headword has two or more Hanzi carry the inside-words button
(`ENT-073`); `Within` rows carry none, since the section already lists every word inside the entry
(as inside-words rows, `SRCH-105`).

`ENT-062` `[MUST]` `Within` rows are keyed by (start, length, entry id) and de-duplicated by entry id
after merge grouping, so one entry never appears twice in that section.

`ENT-063` `[MUST]` When every section is empty, the tab shows `No related words.`

`ENT-064` `[SHOULD]` Related-word sections are **paged**, not fully hydrated. The reference
implementation eagerly fetched up to 500 entries per section (up to about 1500 hydrated entries per
entry opened, each with JSON parsing) — a primary cause of slow entry opening. The successor must
fetch a first page (25 per section is recommended) and load more on scroll.

`ENT-065` `[MUST]` Each row's play button reflects the **actual** availability of pronunciation audio
for that entry (recording or TTS, `MED-001`). The reference implementation hard-coded the WORDS-tab
buttons to enabled.

---

### 6. STROKES tab

`ENT-066` `[MUST]` One practice pane per distinct character of the displayed headword that has valid
stroke data, laid out 1 / 2 / 3 per row at compact / medium / expanded widths. Behaviour: `MED-060`.

---

### 7. Shared card component

`ENT-070` `[MUST]` One card component serves search results, sub-span rows, sheet rows, HSK cards and
WORDS rows. Its inputs: the entry, the merge-group member entries, script mode, the merged variant
list, selected flag, tone-colour flag, pronunciation-visible flag (`GLB-120`), compact flag,
container colour override, audio availability (tri-state: unknown / unavailable /
available), playing flag, the HSK levels to badge (direct, extra and via variants, `ENT-020` item 4),
the source block whose definition is shown (for the `Unicode (Unihan)` / supplement provenance tag,
`ENT-126`, `SRCH-079`), the matched alternative reading when the card was reached through one
(`SRCH-078`), the inside-words input (`ENT-073`), and callbacks for tap and play.

`ENT-071` `[MUST]` Its content derivation (definition concatenation, redirect header suppression,
embedded-variant stripping, note visibility) is the merge logic of `SRCH-082` and must be implemented
once and shared. Every card is backed by a dictionary entry — HSK cards included, since every HSK
word links to an entry (`HSK-050`, `HSK-056`) — so there is no disabled or entry-less card variant.

`ENT-073` `[MUST]` **Inside-words button** (`SRCH-105`, which defines its contents, look and
behaviour). The button and its expander are **part of the shared card component**, implemented once,
so they look and behave the same on every surface. Every card whose headword has **two or more
Hanzi** (`GLB-001`) carries it, whatever the surface: search results of every query kind, compact and
related rows (WORDS tab, `ENT-061`), HSK cards (`HSK-022`) and word-candidate sheet rows (`TXT-040`),
the latter two where their layout allows as those surfaces specify. Exceptions: rows *inside* an
inside-words expander and `Within` rows (`ENT-061`) have none, a combination card (`SRCH-065`) has
none, and the button is omitted when the entry's `within` list is empty. Inputs: the entry's `within`
count (read with the card's batched hydration, `DATA-100`…`DATA-102`), the expanded flag (owned by the
surface: per (word, row count) while its list lives) and a toggle callback; row entries are hydrated
only when the expander opens. Tapping the button never opens the card's entry; tapping a row opens
that row's entry (`SRCH-131`; on the entry page as a nested route, `ENT-056`).

---

### 8. Data required per entry

`ENT-080` `[MUST]` To render an entry page the app needs, in **one** read:
entry id; traditional and simplified forms; display reading and canonical numbered reading; syllable
and tone arrays; ordered senses with their tags (`ENT-058`) and, in Cantonese mode, their source
blocks with each Mandarin block's Pinyin (`ENT-110`…`ENT-116`); structured notes with labels from
the label table (`ENT-057`); resolved
redirect (kind, header text, target id, target senses); frequency rank; HSK levels with per-level part
of speech, including extra levels and levels via variants (`DATA-193`); the entry's alternative
readings for the header (`ENT-020` item 3: kind, note ordinal, display reading and tones of each
eligible `also_pr` / `taiwan_pr` reading, `DATA-192`); the source-block kinds including `unihan` and
`supplement` (`ENT-126`); merge-group id and member ids; and the *first page* of each related-word
section. Decomposition is **not** part of this read (`ENT-120`).

`ENT-081` `[MUST]` The reference `EntryDetailData` also carried a per-character entry list
(`chars`) that no UI ever rendered. It cost one exact lookup, one merge grouping and one hydration per
character of every opened headword. It must not exist in the successor. The Characters section
(`ENT-120`…`ENT-125`) does not revive it: its parts are looked up only when the user expands it, one
batched lookup per expansion level.

`ENT-082` `[MUST]` Related-word sections and sub-spans are answered by indexed lookup (`DATA-100`),
never by per-character posting-list intersections at runtime. `Within` is precomputed for entries of
up to 8 code points and computed on demand for longer headwords (`DATA-101`, `DATA-102`).

---

### 9. Interaction, scrolling and lifecycle

`ENT-090` `[MUST]` Vertical scroll drives header collapse; horizontal drag belongs to the pager. The
collapse handler must ignore horizontal deltas.

`ENT-091` `[MUST]` Content scrolls beneath the transparent system navigation bar; each tab page pads
its own bottom inset (`GLB-072`).

`ENT-092` `[MUST]` Changing script mode re-fetches **every** route in the stack (they may render
different headwords, merge groups and related words) and keeps the stack shape and route keys. While
refetching, each route keeps showing its previous content and is refreshed in place when its new data
arrives (`GLB-030`, progress per `GLB-033`); only a route that had no content yet shows its skeleton. A
failure marks the affected routes with the error state.

`ENT-093` `[MUST]` A real change of the user's dictionary mode (`PROD-043`) **clears** the stack
(entry ids are dataset-scoped — `GLB-044`). Opening a link whose mode differs from the user's
(App Links / Universal Links, Web URLs, the widget — `GLB-110`) is **not** a mode change: the
persisted mode is untouched, the link's entry route records the link's mode (`ENT-010`) and routes
pushed from it carry the same mode, and once the stack is left (popped past its root or closed) the
app is back in the user's own mode. Each route's Web URL uses its own route's mode segment.

`ENT-094` `[MUST]` Any pending word-candidate lookup is cancelled and cleared when: the detail is
closed, a route is popped, the script changes, the dataset changes, or the destination changes.

`ENT-095` `[MUST]` Rapid taps on definition references keep only the latest lookup.

---

### 10. Edge cases (all `[MUST]`)

`ENT-100` A single-character entry has no `Within` section.

`ENT-101` An entry whose reversed form equals itself (single character, palindrome) has no
`Containing reversed` section.

`ENT-102` An entry with no definitions, no notes and an unresolved redirect still renders header,
tabs, and the WORDS tab.

`ENT-103` An entry id that no longer exists in the current dataset produces the error state, never a
crash.

`ENT-104` A 19-character idiom headword must wrap and remain fully visible with no clipping at the
largest supported font scale.

`ENT-105` Headwords containing digits or Latin (`110`, `2019冠状病毒病`, `K金`) render normally;
tone colouring is skipped because the counts do not match (`GLB-066`).

`ENT-106` A reference in a definition pointing at a form with several readings opens the sheet with
all of them, the best-tier one marked default — it must not silently pick one.

`ENT-107` Opening a nested route for a word already deeper in the stack is allowed (cycles are
permitted); each visit has its own route key and scroll state.

`ENT-108` If asset loading throws, the entry keeps its dictionary content and simply has no examples,
strokes or recorded audio (`GLB-035`).

## Pronunciation, Audio, Sentences and Strokes

Asset families beyond the core dictionary: recorded audio, example sentences, stroke data and the
decomposition pack (`DATA-191`, rendered by `ENT-120`…). Delivery
is `MED-084`: on mobile they are installed with the app; on Web they are fetched when needed and are
not part of the offline set.
Any family can still be absent or incomplete, so all are subject to `GLB-035` (graceful absence).
Character origins are deferred (see the Deferred list at the end of this part).

---

### 1. Recorded pronunciation audio

`MED-001` `[MUST]` A pronunciation **play button** appears on every entry card and in the entry-detail
header. It is enabled when a recording plan exists for that entry **or** TTS can speak it
(`MED-011`), and disabled only when neither is available. It shows a breathing pulse on the speaker
glyph while its own audio plays, and is otherwise a static outlined accent button. Pronunciation
visibility (`GLB-120`) never affects it.

`MED-002` `[MUST]` Only one playback is active at a time. Starting a new one cancels the previous one
and clears its playing state. Playback state is a single "currently playing entry id" so exactly one
button animates. A Voice recording session (`VOICE-063`)
owns the audio session: starting it stops any playback or TTS, and play controls are disabled while
it records, so playback cannot start during recording.

`MED-003` `[MUST]` An **audio plan** is an ordered list of asset paths plus a `gaps` flag. Playing a
plan plays its clips in sequence.

`MED-004` `[MUST]` Audio is addressed by **canonical numbered pronunciation**, never by characters, so
homophones share one verified recording and so entries with two readings (便宜 `pian2 yi5` vs
`bian4 yi2`) cannot pick the wrong clip.

`MED-005` `[MUST]` The canonical key is built by lowercasing each syllable, folding `u:` and `ü` to
`v`, requiring every syllable to carry an explicit tone digit, and joining with `_`
(`za2 zhi4` becomes `za2_zhi4`). A reading with any untoned syllable has no word key.

`MED-006` `[MUST]` Plan selection:
- **Multi-character entries** prefer a matching full-word recording; otherwise they fall back to the
  per-syllable sequence.
- **Single-character entries** prefer a complete per-syllable plan; a full-word recording is used only
  when the required syllable recording is missing.

`MED-007` `[MUST]` Per-syllable resolution folds `u:`/`ü` to `v`, requires an explicit tone digit, and
allows one fallback: a neutral-tone syllable (`5`) may use the tone-1 recording. Any unresolved
syllable sets `gaps = true` while the resolvable clips are still played.

`MED-008` `[MUST]` Availability for a list of visible cards is computed in one batched off-thread pass
per result set, not per card render, and is invalidated when the result set, query, script, mode or
dataset changes.

`MED-009` `[MUST]` Playback errors are silent — the button simply stops animating. No error text.

`MED-010` `[MUST]` Recorded audio (word and syllable clips; sizes in `DATA-170` and `PERF-070`) is
delivered per `MED-084`: installed with the app on mobile, so every recording is playable offline from
first launch. On Web clips are fetched when needed and are not available offline; until a needed clip
has been fetched, the play button uses TTS (`MED-011`).

`MED-011` `[MUST]` When no recording exists for an entry (always the case for Cantonese entries,
`DATA-186`), or on Web while its clip is not yet fetched or the fetch failed, the play button speaks
the headword in the active script via platform TTS (language per `MED-022`) instead of being disabled.
There is **no visual distinction** between recorded and TTS playback: same button, same pulse
(`MED-001`), same single-playback rule (`MED-002`). On Web, a tap that falls back to TTS also starts
fetching the clip so later taps play the recording. *(Decided: `Q-060`.)*

`MED-012` `[MUST]` Ambiguous legacy recordings (a clip that cannot be attributed to exactly one
reading) must be excluded from the catalogue rather than guessed.

---

### 2. Text-to-speech (sentences)

`MED-020` `[MUST]` A **sentence action row** appears under every passage: play/stop (TTS), translate,
copy. Buttons are 40 dp outlined accent icon buttons; disabled when the text is blank. Where the
surface offers script conversion — the Reading passage (`TXT-081`…`TXT-084`), the OCR text view's
`All text` card (`CAM-130`…`CAM-135`) and the Voice transcript header (`VOICE-096`) — the conversion
**split button** (`TXT-081`) sits to the right of that surface's actions; conversion is a view state
(`GLB-009`). Play, translate and copy always act on the **displayed** text: the converted text while a
conversion is shown, the original otherwise.

`MED-021` `[MUST]` The play button becomes a stop button while that exact text is speaking, with a
cross-fade+scale icon transition. Tapping stop cancels; tapping play on a different text switches.

`MED-022` `[MUST]` TTS language follows the script/mode: Simplified Mandarin uses `zh-CN`, Traditional
Mandarin `zh-TW`, Cantonese `yue-HK` (falling back to `zh-HK`).

`MED-023` `[MUST]` TTS availability varies by device. Initialisation failure, a missing voice, or a
speak error must leave the UI consistent (button back to play) with no error dialog.

`MED-024` `[MUST]` TTS is stopped and released when the owning state holder is destroyed and when the
dataset is replaced.

`MED-025` `[MUST]` Sentence action rows appear on: the Search sentence card, the Reading passage
(including text shared into the app from another app on Android, `TXT-080`), the
Camera `All text` card, each Camera line card, and each Voice transcript line (`VOICE-040`). Play/stop on a transcript line follows the
recording rule of `MED-002` (unavailable while recording). The conversion split button (`MED-020`)
appears once per surface — on the Reading passage row, the `All text` card (it converts every line
card too, `CAM-135`) and the Voice header (the whole transcript, `VOICE-096`) — never on the Search
sentence card, individual Camera line cards or individual transcript lines.

---

### 3. Translate hand-off

`MED-030` `[MUST]` The translate action hands the text to an **external** translator; the app never
translates sentences itself and itself makes no network request (`PROD-070`). The translate action is
available on every platform (decided: `Q-061`): the Google Translate app on Android (`MED-031`),
Apple Translation on iOS (`MED-032`), Google Translate in a new tab on Web (`MED-033`). Where a
platform target cannot take the text, the shared fallback is the **Google Translate web URL**:
`https://translate.google.com/?sl={source}&tl=en&text={URL-encoded text}&op=translate`, where
`{source}` is `zh-CN` for Simplified Mandarin, `zh-TW` for Traditional Mandarin and `yue` in
Cantonese mode. The text appears only in this outbound URL, never in the app's own routes
(`GLB-110`).

**Length cap (decided: `Q-118`).** The text is trimmed and capped at **1,500 code points** on every
platform. The cap exists because Google Translate limits how much text a URL (and its input box)
accepts; URL-encoding Chinese triples its byte length, so longer text would be truncated or rejected
by the server. Longer text is cut at the last sentence boundary (`。！？!?` or a line break) before the
cap — or at the cap itself when there is none — and a brief note `Only the first part was sent to
translate.` is shown.

`MED-031` `[MUST]` **Android:** send `ACTION_PROCESS_TEXT` with `EXTRA_PROCESS_TEXT` (and
`EXTRA_PROCESS_TEXT_READONLY = true`) targeted at the package `com.google.android.apps.translate`,
declared in the manifest's `<queries>` block so it is visible. No activity class names are hard-coded.
If Google Translate is not installed or the intent does not resolve, open the web URL of `MED-030` in
the default browser (`ACTION_VIEW`). If that fails too, show a brief toast
`No app available to translate`.

`MED-032` `[MUST]` **iOS:** present the system translation sheet of Apple's Translation framework
(`.translationPresentation`) with the text. The framework (iOS 17.4+) is always present because the
iOS minimum is 26 (decided: `Q-116`). The sheet is used whenever `LanguageAvailability` reports the
source language (`zh-Hans`, `zh-Hant` or, in Cantonese mode, `yue-Hant`) → `en` as supported; only
when the pair is unsupported is the web URL of `MED-030` opened instead (system browser).

`MED-033` `[MUST]` **Web:** open the web URL of `MED-030` in a **new tab** (`noopener`), directly
from the click handler so popup blockers allow it.

*Post-v1 note:* letting the user pick another translator (a generic chooser or a translator setting)
is out of scope for v1; the Google Translate target is hard-coded.

---

### 4. Copy

`MED-035` `[MUST]` The copy action places the trimmed text on the clipboard as plain text. No
confirmation is required on platforms that show their own.

---

### 5. Example sentences

`MED-040` `[MUST]` The entry-detail DICT tab shows an `Examples` section with a count when example
sentences exist for the entry's **simplified** headword (Mandarin mode; Cantonese mode: `MED-048`).

`MED-041` `[MUST]` Each example shows the Chinese sentence (in the active script) at 20 sp with 33 sp
line height, then its English translation in muted body text.

`MED-042` `[MUST]` Occurrences of the headword inside the sentence are highlighted with the
`highlight` background (every occurrence, not just the first).

`MED-043` `[MUST]` Every Hanzi in the sentence is an individual tap target that opens the
word-candidate sheet for that offset within the sentence. The sheet's previous/next controls move to
the nearest character in the sentence that is covered by a dictionary word, skipping characters that
are not, and are disabled when there is none in that direction (`GLB-058`). Tap ranges must be per
code point with correct offsets, including for supplementary characters.

`MED-044` `[MUST]` Long-press selects text without following the tap (`GLB-056`).

`MED-045` `[MUST]` Five examples are shown initially, with an expander `+ N more examples`; once
expanded a `- Show fewer` toggle returns to five. The expanded flag is remembered per (headword,
example count) while the route lives.

`MED-046` `[MUST]` Examples are looked up by exact simplified headword and capped (20) at the data
layer.

`MED-047` `[MUST]` Examples are **ranked in the pipeline**, not by source order: sentences in which
the headword is a standalone word of the segmented sentence come first, then the others; within each
group shorter sentences come first, sentence id breaking ties. The cap of `MED-046` keeps the top 20.
The app renders the stored order. *(Decided: `Q-062`.)*

`MED-048` `[MUST]` **Cantonese example sentences.** In Cantonese mode the `Examples` section shows
Cantonese sentences from Tatoeba `yue` (`DATA-188`, sources `PIPE-077`) instead of the Mandarin pairs:
- only the short subset is used (sentences of at most 20 characters); long paragraph-like sentences
  are omitted;
- each sentence is shown with its English translation, taken from Tatoeba's translation links and
  English sentences (pinned like the other sources); a sentence with no linked English translation is
  not included;
- sentences are looked up by exact **traditional** headword, ranked per `MED-047` using Cantonese
  segmentation, and capped at 20;
- the text is Traditional in the source and is converted to Simplified at build time for the
  Simplified script (as `PIPE-017`);
- `MED-041`…`MED-045` apply unchanged; taps open the Cantonese word-candidate sheet.

Attribution (CC BY 2.0 FR) is listed in `SET-040`.

---

### 6. Character origins

Deferred to post-v1 (`MED-050`…`MED-058`, see Deferred below). No v1 rule depends on origin data.
Character **decomposition** (IDS structure and components) is not part of origins: it ships in v1 as
the DICT tab's Characters section (`ENT-120`…`ENT-125`, `DATA-191`).

---

### 7. Stroke order and practice

`MED-060` `[MUST]` The `STROKES` tab shows one practice pane per distinct character of the displayed
headword with valid stroke data (1/2/3 columns by width). The practice canvas is capped so a whole
card fits on short screens (window height minus 260 dp, clamped to 180-420 dp).

`MED-061` `[MUST]` Stroke data per character: an ordered list of stroke outlines (filled paths), an
ordered list of **medians** (polylines through each stroke's centre), and an optional radical stroke
index list. Coordinates arrive in a y-up 1024-unit space with a standard offset; rendering normalises
them into a centred y-down viewport via `y' = 900 - y`. Outlines, medians, touch input, hints and
completed strokes must all share that one transform.

`MED-062` `[MUST]` Three modes per pane: **Preview** (static outline), **Animation**, **Practice**.

`MED-063` `[MUST]` Animation reveals each stroke's real filled outline progressively by masking it with
its median, in stroke order, with a configurable inter-stroke delay (default 120 ms) and a per-stroke
duration derived from the median's length. Replay restarts from the first stroke.

`MED-064` `[MUST]` Practice is a **touch-first guided quiz**: the user traces the expected stroke; the
attempt is accepted or rejected; accepted strokes stay drawn; the expected index advances; completing
the last stroke completes the character with a brief pulse.

`MED-065` `[MUST]` Stroke acceptance criteria (all must hold, with a leniency multiplier):
- start point within tolerance of the median's start (default 250 units);
- end point within tolerance of the median's end (default 250);
- average sampled distance to the median within tolerance (default 350, halved when the outline is
  visible);
- maximum sampled distance within tolerance (default 260);
- coverage: at least 60% of the median's resampled points have an input point within 150 units;
- length ratio between 0.35 and 2.35 of the median length (padded to be forgiving on very short
  strokes);
- direction similarity positive (a reversed trace is rejected);
- normalised shape distance within 0.40;
- the trace must not match a **later** stroke more closely (with an 8-unit advantage margin for the
  expected stroke).
Both forward and backward traces are evaluated so a stroke drawn the wrong way is explicitly rejected
rather than silently matched.

`MED-066` `[MUST]` Sampling uses 30 resampled points (minimum 8); adjacent duplicate input points are
removed first.

`MED-067` `[MUST]` A rejected attempt increments the mistake counters, briefly flashes the failed
trace (about 220 ms), and after **2** mistakes on the current stroke shows an animated hint tracing the
expected median (about 520 ms). Mistake count and hint reset when the stroke is accepted.

`MED-068` `[MUST]` A reset action clears accepted strokes and mistakes and returns to the start of
practice. Outline visibility is independently toggleable per character.

`MED-069` `[MUST]` Intentional boundaries: one difficulty level, no saved history, no scores, no
component shortcuts (the packaged stroke assets carry no component maps).

`MED-070` `[MUST]` Malformed medians must not drop the stroke outlines: a stroke with an unusable
median still renders and is skipped for practice. A record with no stroke paths at all is rejected and
the `STROKES` tab does not appear for that character.

`MED-071` `[SHOULD]` Stroke data (delivered per `MED-084`; size in `DATA-170`) should be stored as a
compact binary geometry format with one record per character rather than JSON shards that must be
fully parsed to read one character (`DATA-171`).

---

### 8. Asset access patterns

`MED-080` `[MUST]` Assets are addressed by content key, never by scanning: strokes by code point,
example sentences by simplified headword (`MED-046`; traditional headword for Cantonese examples,
`MED-048`), recordings by canonical key
(`MED-005`, `MED-007`), decomposition records by code point or synthetic component id (`DATA-191`). Reading one character's or one headword's record must not require reading
or parsing records for other keys. The storage layout is the data contract's concern (`DATA-170`,
`DATA-171`).

`MED-081` `[MUST]` Asset records already read are cached in memory (bounded) so revisiting an entry or
tab does not read them again.

`MED-082` `[MUST]` Reading an asset never blocks the UI thread and never fails the entry.

`MED-083` `[SHOULD]` The successor should replace whole-shard JSON reads with random-access records
(one seek + one decode per character) so reading one character's strokes does not parse a shard
containing hundreds. See `DATA-171`.

`MED-084` `[MUST]` **Delivery.** On Android and iOS, recorded audio, stroke data, example sentences
(Mandarin and Cantonese) and the `decomposition` pack (`DATA-191`) are installed with the app (bundled, or an install-time asset pack on Play):
there is nothing to download and no network access (`PROD-070`). On Web they are **fetched when
needed** and cached by the browser; they are not part of the offline set (only the dataset is,
`ARCH-145`), so offline they may be unavailable. While a fetch is pending or after it fails, the
affected control or section behaves as absent (`GLB-035`), except audio: TTS stands in while a clip
loads or when it cannot be fetched (`MED-010`, `MED-011`).

---

### Deferred (post-v1)

Character origins are deferred in their entirety; these IDs are reserved and not normative in v1.
Where they mention IDS layout, components and component taps, they mean the origin card's own
presentation; v1 decomposition is specified separately (`ENT-120`…`ENT-125`) and does not wait for
them.

- `MED-050` `Character origin` section in the DICT tab.
- `MED-051` Origin card contents (IDS layout, components, definition, etymology, historical glyphs,
  provenance).
- `MED-052` Tappable component chips.
- `MED-053` IDS structural rendering.
- `MED-054` Lazy decoding of historical glyph images.
- `MED-055` Component tap opens a nested route or a search.
- `MED-056` Cancellation of obsolete component lookups.
- `MED-057` Bounded component-origin preloading.
- `MED-058` Origins asset delivery and glyph re-encoding.
