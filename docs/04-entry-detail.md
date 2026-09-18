# 04 — Entry Detail (word display)

Covers everything shown when the user opens a word. Read
[02 — Global Conventions](02-global-conventions.md) first. Search-result card presentation lives in
[03 — Search](03-search.md); this document owns the full word page and the shared card component's
data model.

---

## 1. Route model

`ENT-010` `[MUST]` Entry detail is a **stack of routes**. Each route has: an entry id, a unique
per-visit route key, the data-generation it was created in, a request generation, and a content state
of `Loading | Ready | Error`.

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

`ENT-015` `[MUST]` Layout:
- Compact width: the detail fills the screen; the list is not visible.
- Medium and expanded widths: a list-detail two-pane layout keeps the list visible; the detail pane
  shows the top route; a **close** action appears in the detail's app bar and clears the entire stack.

`ENT-016` `[MUST]` Route transitions:
- forward: incoming slides in from the trailing edge with alpha 0.72 to 1.0; outgoing shifts 12% in
  the opposite direction and fades to 0.88 — about 220 ms;
- back: mirrored;
- when the compact pane itself is closing, the pane transition owns the motion and the route adds only
  a small 8% slide;
- content-state change within a route (Loading to Ready/Error) is a cross-fade (180 ms in / 120 ms
  out), never a slide.

`ENT-017` `[MUST]` Loading and error states keep the app bar with a working back (and close, when
two-pane) affordance. The loading state is a skeleton (`ENT-018`), the error state is a centred
message from `GLB-034`.

`ENT-018` `[MUST]` The loading skeleton approximates the real header: a 126x42 dp block (headword), a
152x18 dp block (reading), a row of three equal 30 dp blocks (tabs), then three text bars at 92%, 78%
and 64% width.

`ENT-019` `[MUST]` Empty-detail placeholder (two-pane, nothing selected): centred `titleMedium` muted
text `Select an entry to see its detail`.

---

## 2. Header

`ENT-020` `[MUST]` The header, above the tab strip, contains:
1. **Headword** in `displaySmall` semibold, tone-coloured per character when tone colouring is on and
   the character count matches the syllable count. It steps down to `headlineMedium` when the headword
   exceeds 8 code points, and wraps across lines.
2. **Alternate form(s)** in `titleMedium` muted, baseline-aligned, wrapping to its own line if needed:
   the merged variant list joined by ` · ` when the entry is part of a merge group, otherwise the
   other script form when it differs, otherwise nothing.
3. A row with the **play button** (40 dp, outlined, accent) and the **pronunciation** in
   `titleMedium` medium-weight accent, tone-coloured, allowed to wrap.
4. A wrapping row of **HSK badges**, one per level, each reading `HSK n` — or `HSK 7-9` for level 7 —
   and, when a part of speech is known for that level, `HSK n • Noun` (`HSK-040`).
5. Optional inline status text (e.g. a failed word-candidate lookup message).

`ENT-021` `[MUST]` Headword, alternate form and pronunciation are inside a text-selection container so
they can be long-pressed and copied. The play button is outside it so a drag does not start a
selection.

`ENT-022` `[MUST]` The header **collapses** on upward scroll and restores on downward scroll at list
top, shared by every tab. Implementation must be placement-only (the pager keeps a constant measured
height and is translated) so collapsing does not re-measure per frame. When collapse exceeds 95%, the
app bar shows the headword as its title with a fade.

`ENT-023` `[MUST]` The collapse fraction is part of route-scoped saved state (`GLB-050`).

`ENT-024` `[SHOULD]` While optional assets are still loading, a small 58x12 dp placeholder block sits
in the badge row instead of any text. It disappears when assets resolve, whether or not any were
found.

---

## 3. Tabs

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

## 4. DICT tab

### 4.1 Single-entry case

`ENT-040` `[MUST]` Content order:
1. optional **redirect header** (`ENT-045`), muted, with tappable Chinese references;
2. the **numbered sense list** — one paragraph per sense, numbered from 1, bold `fg` numbers,
   `bodyLarge` body, each sense selectable and with tappable Chinese references;
3. **structured notes** joined by ` • `, muted, also with tappable references;
4. **Examples** section (`MED-020`);
5. **Character origin** section, one card per distinct character of the displayed headword that has
   origin data (`MED-025`).

`ENT-041` `[MUST]` Senses are never truncated in the DICT tab.

### 4.2 Merged group case

`ENT-042` `[MUST]` When the entry belongs to a merge group with more than one member, the DICT tab
renders one **section per member that has definitions**, each introduced by a small-caps label naming
the member's alternate-script form: `Traditional: 髮` (or `Simplified: 发` in Traditional mode).

`ENT-043` `[MUST]` Sense numbering is **continuous across sections** (member A senses 1-3, member B
senses 4-5).

`ENT-044` `[MUST]` Each member's structured notes stay with that member's section, not collected at
the bottom. Members are ordered by direct-definition count descending, original order breaking ties.
De-duplication and redirect suppression rules are `SRCH-082`.

### 4.3 Redirects and variant expansion

`ENT-045` `[MUST]` Many entries have no definition of their own and instead point at another entry.
Three redirect kinds must be recognised: `variant-of`, `old-variant-of`, `erhua` (from prose
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

### 4.4 Definition prose parsing and internal links

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
runtime.

`ENT-053` `[MUST]` Tap targets are rendered in `accent` with an underline. A **long press** enters
text selection and must not activate the link (`GLB-056`).

`ENT-054` `[MUST]` Tapping a reference opens the word-candidate sheet (`TXT-040`) whose contents are:
1. every entry exactly matching the reference, ordered by the reference-ranking tiers of `ENT-046`,
   then proper-noun-last, then frequency, then id; the first is marked as the default;
2. followed by **every dictionary word contained inside** the referenced text, in the standard
   sub-span order (`SRCH-034`).

This is what makes `variant of 一點|一点[yi1 dian3]` offer both 一点 and 点.

`ENT-055` `[MUST]` A **multi-code-point** reference is one single tap range, not one per character.
Surrogate-pair characters keep their full range.

`ENT-056` `[MUST]` Selecting an entry from that sheet pushes a **nested** route, so Back returns to
the referring word with its tab and scroll intact.

### 4.5 Notes

`ENT-057` `[MUST]` Structured note kinds present in the source data and their rendering: `see`,
`variant-of`, `old-variant-of`, `cl` (classifier/measure word), `tw-pr` (Taiwan pronunciation),
`abbr-for`, `also-written`, `also-pr`, `see-also`. A note renders as its kind with hyphens replaced by
spaces, lowercased, then `: ` and its details: references (form + parenthesised reading), then the
note's own reading, then any extra text, space-joined. Multiple notes are joined by ` • `.

`ENT-058` `[SHOULD]` Note kind labels should be replaced by human phrasing in the successor
(`classifier:`, `also pronounced:`, `Taiwan pronunciation:`, `abbreviation for:`, `see also:`) rather
than the raw hyphenated source keys. *(Improvement — `Q-020`.)*

---

## 5. WORDS tab

`ENT-060` `[MUST]` Sections, in order, each with a small-caps label and count, omitted when empty:
1. `Within 「word」` — dictionary words contained inside this word (sub-spans), longest-first;
   parent-reading promotion applies (`SRCH-104`).
2. `Starting with 「word」` — longer headwords beginning with it.
3. `Containing 「word」` — headwords containing it at position 1 or later.
4. `Containing reversed` — headwords containing the reversed headword (e.g. from 上海 find 海上).
   Omitted when the reversed string equals the original.

`ENT-061` `[MUST]` All rows are compact cards (`SRCH-097`) with the same content rules as search
result cards, including merge variants and clamped definitions.

`ENT-062` `[MUST]` `Within` rows are keyed by (start, length, entry id) and de-duplicated by entry id
after merge grouping, so one entry never appears twice in that section.

`ENT-063` `[MUST]` When every section is empty, the tab shows `No related words.`

`ENT-064` `[SHOULD]` Related-word sections are **paged**, not fully hydrated. The reference
implementation eagerly fetched up to 500 entries per section (up to about 1500 hydrated entries per
entry opened, each with JSON parsing) — a primary cause of slow entry opening (finding `F-030`). The
successor must fetch a first page (25 per section is recommended) and load more on scroll.

`ENT-065` `[MUST]` Each row's play button reflects the **actual** availability of pronunciation audio
for that entry. The reference implementation hard-coded the WORDS-tab buttons to enabled (finding
`F-031`).

---

## 6. STROKES tab

`ENT-066` `[MUST]` One practice pane per distinct character of the displayed headword that has valid
stroke data, laid out 1 / 2 / 3 per row at compact / medium / expanded widths. Behaviour: `MED-050`.

---

## 7. Shared card component

`ENT-070` `[MUST]` One card component serves search results, sub-span rows, sheet rows, HSK cards and
WORDS rows. Its inputs: the entry, the merge-group member entries, script mode, the merged variant
list, selected flag, tone-colour flag, compact flag, container colour override, enabled flag, audio
availability (tri-state: unknown / unavailable / available), playing flag, and callbacks for tap and
play.

`ENT-071` `[MUST]` Its content derivation (definition concatenation, redirect header suppression,
embedded-variant stripping, note visibility) is the merge logic of `SRCH-082` and must be implemented
once and shared.

`ENT-072` `[MUST]` A disabled card is visually identical but not tappable and has no play affordance
(used for HSK rows that failed to link — `HSK-023`).

---

## 8. Data required per entry

`ENT-080` `[MUST]` To render an entry page the app needs, in **one** read:
entry id; traditional and simplified forms; display reading and canonical numbered reading; syllable
and tone arrays; ordered senses (with provenance in Cantonese mode); structured notes; resolved
redirect (kind, header text, target id, target senses); frequency rank; HSK levels with per-level part
of speech; merge-group id and member ids; and the *first page* of each related-word section.

`ENT-081` `[MUST]` The reference `EntryDetailData` also carried a per-character entry list
(`chars`) that no UI ever rendered. It cost one exact lookup, one merge grouping and one hydration per
character of every opened headword. It must not exist in the successor (finding `F-032`).

`ENT-082` `[MUST]` Related-word sections and sub-spans must be answerable from precomputed
relationships (`DATA-100`), not from per-character posting-list intersections at runtime.

---

## 9. Interaction, scrolling and lifecycle

`ENT-090` `[MUST]` Vertical scroll drives header collapse; horizontal drag belongs to the pager. The
collapse handler must ignore horizontal deltas.

`ENT-091` `[MUST]` Content scrolls beneath the transparent system navigation bar; each tab page pads
its own bottom inset (`GLB-072`).

`ENT-092` `[MUST]` Changing script mode re-fetches **every** route in the stack (they may render
different headwords, merge groups and related words) and keeps the stack shape and route keys. While
refetching, each route shows its skeleton; a failure marks the affected routes with the error state.

`ENT-093` `[MUST]` Changing dictionary mode **clears** the stack (entry ids are dataset-scoped —
`GLB-044`).

`ENT-094` `[MUST]` Any pending word-candidate lookup is cancelled and cleared when: the detail is
closed, a route is popped, the script changes, the dataset changes, or the destination changes.

`ENT-095` `[MUST]` Rapid taps on definition references keep only the latest lookup.

---

## 10. Edge cases (all `[MUST]`)

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
origins, strokes or audio (`GLB-035`).
