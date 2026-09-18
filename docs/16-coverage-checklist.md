# 16 — Coverage Checklist

Verification that every significant user-facing behaviour found in the reference app is **documented**,
**intentionally excluded**, or **flagged for review**. Use this as the acceptance gate for the
specification set, and as a build checklist afterwards.

Legend: **D** documented and in scope · **X** intentionally excluded · **R** flagged for human review
(document 15) · **N** new requirement not in the reference app.

---

## Navigation and shell

| Behaviour | Status | Where |
|-----------|--------|-------|
| Four primary destinations, order Search / Reading / HSK / Settings | D | `PROD-050` |
| Bottom bar at compact width, navigation rail at medium and above | D | `PROD-050` |
| HSK destination hidden in Cantonese mode | D / R | `HSK-002`, `Q-030` |
| Camera reached from cards/shortcut/share, not the bar | D | `PROD-052` |
| Re-tapping the current destination scrolls to top | D | `PROD-053` |
| Destination change clears the entry stack and any sheet | D | `PROD-055` |
| Back precedence: sheet, camera, route stack, platform | D | `PROD-056` |
| Adaptive list-detail two-pane layout, close action | D | `ENT-015` |
| Route transitions (forward/back/pane-owned) | D | `ENT-016` |
| Route-scoped tab, collapse and per-tab scroll restoration | D | `GLB-050` |
| Edge-to-edge with status-bar scrim; nav hides with the keyboard | D | `GLB-070` |
| Content max width and centring | D | `GLB-067` |
| URL/history reflection on Web | N | `PROD-057`, `ARCH-053` |

## Search

| Behaviour | Status | Where |
|-----------|--------|-------|
| Single field for English / pronunciation / Hanzi / mixed / sentences | D | `SRCH-001` |
| Chinese and English sides with live counts | D | `SRCH-002`, `SRCH-090` |
| Query normalisation, line-break folding, display-text normalisation | D | `SRCH-010` to `SRCH-012` |
| English tokenisation with naive plural stem | D | `SRCH-013` |
| Query classification (empty/hanzi/mixed/latin) | D | `SRCH-020` |
| Automatic side selection and preference persistence rules | D | `SRCH-024` to `SRCH-027` |
| Seven match classes and their weights | D | `SRCH-030` |
| Simplified/traditional matching regardless of script mode | D | `SRCH-031` |
| Folding and ASCII case-insensitive headword matching | D | `SRCH-032`, `GLB-005` to `GLB-007` |
| Exact group / segmented groups / pattern / starts / contains / related | D | `SRCH-033`, `SRCH-102` |
| Sub-spans per group with parent-reading promotion | D | `SRCH-034`, `SRCH-104` |
| Mixed Hanzi + pronunciation pattern search | D | `SRCH-036` to `SRCH-039` |
| Romanisation parsing (tones, diacritics, boundaries, partials, 8-parse cap) | D | `SRCH-040` |
| Dictionary-initialism fallback (`Txu`) | D | `SRCH-041` |
| Latin headword prefix search on the Chinese side | D | `SRCH-044` |
| English last-token prefix, per-sense phrase detection | D | `SRCH-045` to `SRCH-048` |
| Canonical five-key ranking comparator | D | `SRCH-070` |
| Rank before limiting | D | `SRCH-071` |
| Variant merge cards and their content rules | D | `SRCH-080` to `SRCH-086` |
| Floating search control, paste/clear, category chips | D | `SRCH-090` |
| Result card anatomy, definition clamping with `+N` | D | `SRCH-094`, `SRCH-095` |
| Compact rows, inert `NO MATCH` rows, sentence grouping | D | `SRCH-097` to `SRCH-099` |
| Segmented passage card with underlines and tappable characters | D | `SRCH-100` |
| Sub-span expander pill | D | `SRCH-103` |
| Empty state: hint, OCR cards, popular words | D | `SRCH-110` |
| No-result state | D | `SRCH-113` |
| Cross-side no-result hint | N / R | `SRCH-114`, `Q-012` |
| Keyboard, focus and paste behaviour | D | `SRCH-120` to `SRCH-124` |
| Query persistence and restoration | D | `SRCH-140` to `SRCH-142` |
| Search history list | X / R | `SRCH-143`, `Q-013` |

## Entry detail

| Behaviour | Status | Where |
|-----------|--------|-------|
| Route stack, loading replacement, guarded pop, deferred removal | D | `ENT-010` to `ENT-014` |
| Skeleton and error panes with working navigation | D | `ENT-017` to `ENT-019` |
| Header: headword, alternates, reading, play, HSK badges, status | D | `ENT-020` |
| Text selection in the header | D | `ENT-021` |
| Collapsing header (placement-only) with app-bar title fade | D | `ENT-022` |
| Tabs DICT / STROKES / WORDS, conditional strokes, pager, per-tab scroll | D | `ENT-030` to `ENT-035` |
| Single-entry sense list, notes, examples, origins | D | `ENT-040` |
| Merged-group sections with continuous numbering | D | `ENT-042` to `ENT-044` |
| Redirect kinds, tiered resolution, expansion, note consumption | D | `ENT-045` to `ENT-050` |
| Definition prose segments and internal links | D | `ENT-051` to `ENT-056` |
| Structured note kinds and rendering | D | `ENT-057` |
| Human note labels | R | `ENT-058`, `Q-020` |
| WORDS sections: within / starts / contains / contains-reversed | D | `ENT-060` |
| Paged related words | D | `ENT-064` |
| Correct per-row audio availability | D | `ENT-065` |
| Shared card component | D | `ENT-070` to `ENT-072` |
| Per-character entry list (`chars`) | X | `ENT-081` (dead work, removed) |
| Script change re-fetches the whole stack | D | `ENT-092` |

## HSK

| Behaviour | Status | Where |
|-----------|--------|-------|
| Levels 1-7 with the 7-9 band label | D | `HSK-003` |
| Single continuous grid with level headers | D | `HSK-010` |
| Floating level selector with current-level tracking | D | `HSK-011`, `HSK-012` |
| Responsive masonry grid, level-tinted cards | D | `HSK-020` to `HSK-022` |
| Unlinked rows as disabled fallback cards | D | `HSK-023` |
| Stable unique row keys | D | `HSK-026` |
| Row de-duplication within a level | N | `HSK-027` (reference bug `F-050`) |
| Pronunciation ordering with canonical readings | D | `HSK-030` to `HSK-032` |
| HSK badges app-wide; per-level part of speech in the header | D | `HSK-040` to `HSK-044` |
| Data-driven part-of-speech labels | N | `HSK-045` |
| Pipeline-side join with tolerant reading matching | D | `HSK-050` to `HSK-053` |
| Show the syllabus reading when it differs | R | `HSK-054`, `Q-031` |
| Show syllabus glosses for linked rows | R | `HSK-055`, `Q-032` |
| Gate on unlinked rows | N | `HSK-056` (reference bug `F-051`) |
| Deferred load, cached model, cross-fade placeholder | D | `HSK-060` to `HSK-064` |
| First frame without hydrating the whole level set | N | `HSK-066` (reference bug `F-052`) |
| One bad row does not fail the screen | N | `HSK-067` (reference bug `F-053`) |

## Camera / OCR

| Behaviour | Status | Where |
|-----------|--------|-------|
| Full-screen surface, slide transition, nav hidden | D | `CAM-001` |
| Five entry points | D | `CAM-002` |
| Lazy permission request, three-state prompt, resume re-check | D | `CAM-010` to `CAM-014` |
| Camera-unavailable fallback | D | `CAM-015` |
| Photo-picker path with no media permission | D | `CAM-016` |
| Minimal permission set | D | `CAM-017` |
| 4:3 fit-centre preview, rear camera, 72 dp shutter | D | `CAM-020` to `CAM-024` |
| Lifecycle binding and post-disposal capture discard | D | `CAM-025` |
| Upright, EXIF-corrected, 2560 px-capped images | D | `CAM-026` |
| **Tap to focus** | N | `CAM-030` to `CAM-034` |
| **Flash / torch control** | N | `CAM-035` to `CAM-039` |
| Gallery/share decode, error and consume-once behaviour | D | `CAM-040` to `CAM-044` |
| Offline Chinese recognition with a 20 s timeout | D | `CAM-050`, `CAM-051` |
| Symbol-based line text, quads, per-char offsets | D | `CAM-052`, `CAM-053` |
| No-Hanzi and failure messages | D | `CAM-054`, `CAM-056` |
| Per-line segmentation for tappability | D | `CAM-055` |
| Re-analysis on dataset change | D | `CAM-057` |
| Result header: retake / pick new, save, Image-Text toggle | D | `CAM-060` |
| Overlay drawing, pinch-zoom, double-tap, hit-testing | D | `CAM-062` to `CAM-066` |
| Pan-to-centre on character stepping | D | `CAM-067` |
| Text view with All-text card and per-line cards | D | `CAM-069`, `CAM-070` |
| **Save the original photo to the photo library** | N | `CAM-071` to `CAM-076` |
| Saved-image resolution choice | R | `CAM-074`, `Q-041` |
| **Auto-save setting** | N | `CAM-080` to `CAM-084` |
| Lifecycle: release, retain, no screenshot on disk | D | `CAM-090` to `CAM-094` |
| Back precedence inside the surface | D | `CAM-100` |
| Web exclusion | X | `PROD-022`, `PROD-023` |

## Text analysis and reading

| Behaviour | Status | Where |
|-----------|--------|-------|
| Run modes (Hanzi-only vs full) | D | `TXT-011` |
| DAG maximum-likelihood segmentation and greedy fallback | D | `TXT-012`, `TXT-013` |
| Batched, chunked segmentation | D | `TXT-014`, `TXT-015` |
| Passage identity and caching | D | `TXT-016`, `ARCH-070` |
| Span candidate enumeration and ordering | D | `TXT-020`, `TXT-021` |
| "Appears in" fallback and the not-in-a-run case | D | `TXT-022`, `TXT-023` |
| One-layout interactive passage renderer, shared | D | `TXT-030` to `TXT-032` |
| Visible-character definition for tapping and stepping | D | `TXT-033` |
| Word-candidate sheet: peek, drag, header, stepper, progress, padding | D | `TXT-040` to `TXT-046` |
| Reading destination: field, paste, clear, auto-analyse, status | D | `TXT-050` to `TXT-056` |
| Focus-gated clipboard access and usable-text definition | D | `TXT-053`, `TXT-054` |
| `Read clipboard` shortcut | D | `TXT-057`, `SET-050` |
| OCR cards on Reading | R | `TXT-058`, `Q-050` |

## Pronunciation and media

| Behaviour | Status | Where |
|-----------|--------|-------|
| Play button states, single active playback | D | `MED-001`, `MED-002` |
| Audio plans keyed by canonical reading | D | `MED-003` to `MED-007` |
| Batched availability computation | D | `MED-008` |
| Silent playback failures | D | `MED-009` |
| Audio as a downloadable pack | N / R | `MED-010`, `Q-090` |
| TTS fallback for words | N / R | `MED-011`, `Q-060` |
| Sentence action row: play/stop, translate, copy | D | `MED-020` to `MED-025` |
| Translate hand-off | D / R | `MED-030` to `MED-033`, `Q-061` |
| Example sentences: highlight, tappable, expander, cap | D | `MED-040` to `MED-046` |
| Example ranking | R | `MED-047`, `Q-062` |
| Character origins: IDS layout, chips, glyph strip, provenance | D | `MED-050` to `MED-057` |
| Origins re-encoding and pack | N | `MED-058` |
| Stroke tab layout and canvas sizing | D | `MED-060` |
| Preview / animation / practice modes | D | `MED-062` to `MED-064` |
| Full stroke acceptance criteria | D | `MED-065`, `MED-066` |
| Mistake counting, hints, reset, outline toggle | D | `MED-067`, `MED-068` |
| Intentional practice boundaries (one difficulty, no history) | X | `MED-069` |
| Whether strokes stay in scope at all | R | `Q-093` |
| Malformed-median tolerance | D | `MED-070` |
| Asset shard access and caching | D | `MED-080` to `MED-083` |

## Settings and platform integration

| Behaviour | Status | Where |
|-----------|--------|-------|
| Settings list, controls, immediate persistence | D | `SET-001` to `SET-003` |
| Dictionary mode control | N | `SET-002`, `PROD-040` |
| Script, theme, tone colours, boundaries | D | `SET-002` |
| Auto-save photos | N | `SET-002`, `CAM-080` |
| Persisted key table, per-mode namespacing, corrupt-value fallback | D | `SET-010` to `SET-016` |
| Attribution cards and licence dialog | D | `SET-040` to `SET-044` |
| Licence review of GPL/LGPL/unlicensed sources | R | `SET-041`, `Q-070` |
| Launcher shortcuts and consume-once | D | `SET-050` to `SET-053` |
| Share-image target | D | `SET-054`, `SET-055` |
| Android word-of-the-day widget (sizes, mask, determinism, deep link, audio) | D | `SET-060` to `SET-067` |
| Widget deep link keyed on a version-stable key | N | `DATA-212` (reference bug `F-122`) |
| iOS widget | R | `SET-068`, `Q-071` |
| Android screen-assistant integration | D / R | `SET-070` to `SET-077`, `Q-072` |
| Pronunciation scoring port | X | `Q-094` |

## Cross-cutting

| Behaviour | Status | Where |
|-----------|--------|-------|
| Hanzi range definition and code-point correctness | D | `GLB-001`, `GLB-002` |
| Script mode, headword and alternate form | D | `GLB-003`, `GLB-004` |
| Romanisation-agnostic engine | D | `GLB-010` to `GLB-012` |
| Proper-noun readings | D | `GLB-013` |
| Non-Mandarin romanisation preservation | D | `GLB-014`, `DATA-053` |
| Never blank populated content | D | `GLB-030` |
| Skeletons, in-place progress, error copy, recoverability | D | `GLB-031` to `GLB-034` |
| Graceful asset absence | D | `GLB-035` |
| Empty vs no-result distinction | D | `GLB-036` |
| Latest-only, conflation, cancellability, data generation | D | `GLB-040` to `GLB-046` |
| State preservation and route-keyed saved state | D | `GLB-050` to `GLB-052` |
| Interaction conventions (selection, long press, sheets, stepping, focus) | D | `GLB-055` to `GLB-059` |
| Palette, radii, tone and HSK colours, boundary shades | D | `GLB-060` to `GLB-066` |
| Six-tone Cantonese palette | N | `GLB-064` |
| Accessibility: labels, targets, scaling, colour independence, screen reader | D | `GLB-080` to `GLB-086` |
| Per-character accessibility for interactive passages | N | `ARCH-160` |
| No behavioural telemetry; no sensitive logging | D | `GLB-090` to `GLB-092` |

## Data and delivery

| Behaviour | Status | Where |
|-----------|--------|-------|
| Dataset identity, versioning, validation at open | D | `DATA-001` to `DATA-006` |
| Full logical model (entry, sense, segments, redirect, note) | D | `DATA-010` to `DATA-062` |
| All search indexes with measured sizes | D | `DATA-070` to `DATA-079` |
| Precomputed merge groups | N | `DATA-080` |
| Precomputed `within` relationships and pageable relations | N | `DATA-100` to `DATA-104` |
| Frequency data and log-probabilities | D | `DATA-110` to `DATA-114` |
| HSK tables, sort keys, label table, widget projection | D | `DATA-120` to `DATA-130` |
| Physical encoding recommendation and rejected alternatives | D | `DATA-140` to `DATA-148` |
| Popular words as data | D | `DATA-090` |
| Companion asset packs and catalogues | D | `DATA-170` to `DATA-173` |
| Cantonese dataset rules | N | `DATA-180` to `DATA-186` |
| Validation gates and build report | D | `DATA-200` to `DATA-202` |
| Update strategy and key stability | D | `DATA-210` to `DATA-215` |
| Pipeline sources, builds, and moved transformations | D | `PIPE-010` to `PIPE-101` |
| Golden-query regression suite | D | `PIPE-140` to `PIPE-142` |

---

## Intentionally excluded from v1 (recap)

- Desktop native targets (`PROD-024`).
- Camera/OCR on Web (`PROD-022`).
- Accounts, sync, SRS, notes, handwriting input, speech recognition, pronunciation scoring
  (`PROD-030`, `Q-094`).
- Search history list, pending review (`Q-013`).
- Stroke-practice difficulty levels, saved scores, component shortcuts (`MED-069`).
- iOS widget and iOS share extension, pending review (`Q-071`, `Q-042`).
- The reference app's dead code and dead state: `dictionaryStatus`, `NavPill`,
  `PronunciationScoringPort`, `EntryDetailData.chars`, unused reverse indexes (`F-023`, `F-032`,
  `F-048`, `F-049`).

## Specification acceptance gate

Before implementation starts, confirm:

1. Every `R` row above has a recorded decision in [15 — Open Questions](15-open-questions.md).
2. The five blocking questions (`Q-081`, `Q-082`, `Q-080`, `Q-030`, `Q-070`) are answered — they change
   the data pipeline or the product's shape.
3. The scope questions (`Q-072`, `Q-071`, `Q-093`, `Q-091`) are answered — they change the estimate
   materially.
4. The performance budgets in [13](13-performance.md) are accepted as CI gates.
