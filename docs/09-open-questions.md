# 09 — Open Questions and Decisions

Section 1 lists the questions that are still open. Section 2 is the decision log: every question that has
been answered, with where the answer is applied. Closed questions keep their ID so references stay
meaningful; their full discussion is in the git history of this file.

---

## 1. Still open

### `Q-122` — Choices made while applying the 2026-10-12 notes (confirm)
- **Alternative readings** (`also pr.` / `Taiwan pr.`) appear on a muted line under the main reading
  in the entry header and are then left out of the notes row (`ENT-020`, `ENT-057`).
- **Unicode-only entries:** one entry per character; they rank after all dictionary matches; in
  Cantonese mode the English page gets a third section `Unicode (Unihan)` (`SRCH-079`, `DATA-190`). In
  passages they count as covered single characters (tappable, included in stepping) (`TXT-012`).
- **Decomposition highlighting:** exact stroke highlighting uses Make Me a Hanzi's per-stroke component
  `matches` (LGPL-3.0, separate data file, ~9,000 characters); other characters get regions estimated from
  the IDS structure (left/right, top/bottom, enclosure). Unencoded components use SVG glyphs drawn by the
  project (CC BY-SA 4.0) (`ENT-120`…, `DATA-191`).
- **Script conversion:** in Mandarin mode the Traditional target is OpenCC standard; Cantonese mode uses
  Hong Kong (`TXT-082`, `VOICE-096`). On OCR it applies to the Text view only; the Image view and its boxes
  keep the recognised text (`CAM-135`). In Reading the conversion stays on while you keep typing and resets
  on Clear, Paste and shared text (`TXT-084`).
- **OCR pick button** on Android and iOS opens a small menu with `Photos` and `Files`; on Web it opens the
  file chooser directly (`CAM-016`).
- **Paste on touch devices:** without a keyboard, long-pressing the camera area opens the system menu with
  `Paste`, so phone users can paste an image too (`CAM-132`).
- **Save photo on Web** downloads the original capture as a JPEG; there is no auto-save on Web
  (`CAM-073`).
- **HSK:** homograph rows of the same level linking to the same entry show as one card; an entry with its
  own HSK level does not also receive a level through a variant (`HSK-027`, `DATA-193`).
- **Privacy page** on szdict.com shows all three languages on one indexable page (`GLB-114`).
- **Words-inside button:** not shown on `Within` rows in the WORDS tab or on combination cards
  (`ENT-073`, `SRCH-105`).

---

## 2. Decision log

| Item | Decision | Applied in |
|------|----------|-----------|
| `Q-012` No-result copy | Keep current behaviour: each page shows its own `No matches`; no offer-to-switch message | `SRCH-114` |
| `Q-013` Search history | Only the single restored query. Early iterations store **no user-curated data** (no history, favourites, saved words, notes), so the app carries no responsibility for keeping user data safe; data features come once the app is consolidated | `SRCH-143`, `PROD-030` |
| `Q-020` Note labels | Data-driven label table (en / zh-Hans / zh-Hant) for structural conventions (classifier, see, see also, variant / old / erhua / contracted variant, abbreviation, also pronounced, Taiwan pronunciation, also written, used in, surname, radical) plus about 60 allow-listed domain / register tags; catalogue from a full pass over CC-CEDICT and CC-Canto | `DATA-060`, `PIPE-079`, `ENT-057`, `ENT-058` |
| `Q-030` Cantonese HSK | HSK shown in both modes, joined to the Cantonese dataset, ordered by Jyutping | `HSK-002`, `HSK-090`…, `DATA-187` |
| `Q-031` HSK syllabus reading | Not shown anywhere (it would add inconsistency) | `HSK-054` removed |
| `Q-032` HSK syllabus glosses | Not kept or shown; CC-CEDICT / CC-Canto are the source of truth | `HSK-055`, `DATA-12x` |
| `Q-040` Flash persistence | Session only | `CAM-038` |
| `Q-041` Saved photo resolution | Save the original capture; analysis uses the capped working image | `CAM-073` |
| `Q-050` OCR on Reading | No OCR entry point on the Reading screen. OCR is reached only through its own destination (plus the launcher shortcut and share intent) | `CAM-006`…`CAM-009` |
| `Q-060` TTS fallback | TTS speaks words without a recording, with no visual distinction | `MED-011` |
| `Q-061` Translate hand-off | Android: Google Translate (web URL if not installed). iOS: Apple Translation (always available on iOS 26), with the Google Translate web URL only when the language pair is unsupported. Web: new tab to Google Translate. Other translators post-v1 | `MED-030`…`MED-033` |
| `Q-062` Example ordering | Ranked in the pipeline (headword as a standalone word first, then shorter) | `MED-047` |
| `Q-070` Licence review | Character origins deferred (removes CJKVI IDS, chinese-lexicon and the glyph sources); the remaining question is the app licence (`Q-100`) | `SET-040`, `SET-041` |
| `Q-071` iOS widget | Android widget in v1; daily pick and projection live in shared code so an iOS widget is cheap later | `SET-068`, `SET-063`, `DATA-130` |
| `Q-072` Screen assistant | **Removed** (not deferred). Instead the app receives shared **images → OCR** and shared **text → Reading** | `SET-054`, `SET-056` (Android) |
| `Q-080` Provenance labels | Decided from the dictionary analysis (73 % of Cantonese-mode headwords have only CC-CEDICT definitions): label per **block** — "Cantonese (CC-Canto)" first, then "Mandarin senses (CC-CEDICT) · pinyin", collapsed when a Cantonese block exists | `DATA-022`, `ENT-052`, `PROD-045` |
| `Q-081` Cantonese frequency | Custom Cantonese table: rime-cantonese essay (CC BY 4.0) as the base, counts from HKCanCor, Common Voice yue, Tatoeba yue, UD_Cantonese-HK, zh-yue Wikipedia; words.hk and Leipzig excluded | `DATA-114`, `DATA-189`, `PIPE-077` |
| `Q-082` Mandarin senses on Cantonese entries | (a) carry all Mandarin senses, refined by the analysis: join on (traditional, pinyin) for single-reading words; single-character polyphones use CC-Canto's per-Jyutping split, with the Mandarin senses as one headword-level block | `DATA-022`, `PIPE-053` |
| `Q-090` Delivery | Everything installed with the app **except the voice model**: Play on-demand asset pack, iOS Background Assets pack, bundled in the direct APK. Web keeps only the dataset offline | `PROD-070`, `DATA-170`…, `ARCH-140`…, `VOICE-095` |
| `Q-091` Web | PWA keeps only the dataset offline (OPFS) and self-updates through per-release row-level changesets; other Web assets fetched when needed. Per-word SEO pages rendered at the edge at the same URLs, linking into the app | `ARCH-145`, `ARCH-146`, `DATA-216`, `PIPE-167`, `GLB-113` |
| `Q-093` Stroke quiz | Kept | `MED-060`…`MED-071` |
| `Q-094` Pronunciation scoring | Completely out of v1, including any placeholder interface | `PROD-030` |
| `Q-101` Voice model licence | Apache-2.0 ([ModelScope](https://modelscope.cn/models/dengcunqin/speech_paraformer-large_asr_nat-zh-cantonese-en-16k-vocab8501-online/files)) | `SET-040` |
| `Q-102` HSK word list | Including the file in the repository is fine; only word → level / part-of-speech tags are used | `PIPE-161`, `SET-040` |
| `Q-103` Mandarin language model | libime `lm_sc.arpa` (LGPL-2.1+) accepted | `PIPE-070` |
| `Q-104` Cantonese bigrams | Same corpora as `Q-081`; derived tables published CC BY-SA 4.0; quality gate with Mandarin fallback | `PIPE-074`, `PIPE-075`, `PIPE-077` |
| `Q-107` Routes | Dictionary mode is a path prefix: `/zh/…` and `/yue/…`. Simplified / Traditional is a viewer preference, not in the path. Passages never go in URLs | `GLB-110`…`GLB-113` |
| `Q-108` CC-Canto references | CC-Canto uses Pleco-style references (`同tóng [同]`) only in lines adapted from CC-CEDICT; resolved on hanzi + pinyin. Bare hanzi linked only on an exact headword match | `GLB-014`, `PIPE-079` |
| `Q-109` CJK font | Text tagged by script (zh-Hans / zh-Hant / zh-HK); bundled OFL fallback font for rare characters | `GLB-*`, `PERF-070` |
| `Q-110` HSK edition | 2025 HSK syllabus (CTI, published 2025-11, in force 2026-07): cumulative 300 / 500 / 1,000 / 2,000 / 3,600 / 5,400 / 11,000 words | `HSK-003`, `DATA-200` |
| `Q-111` Web media | Fetched when needed, not part of the offline set; TTS stands in while a clip loads | `MED-084` |
| `Q-112` iOS photo saving | Kept (`NSPhotoLibraryAddUsageDescription`) | `ARCH-106`, `CAM-017` |
| `Q-113` Signing keys | Same key for Play and the direct APK, listed in `assetlinks.json` | `REL-041`, `GLB-111` |
| `Q-114` Design choices | Accepted. Handwriting has Delete and Clear buttons; ink is never auto-committed (the app never inserts a candidate on its own, after a pause or when a new character starts — the user always taps one) | `HW-*` and the rules named in the git history |
| `Q-042` iOS share extension | No iOS share target in v1 (a save-then-open flow would feel janky). Sharing images → OCR and text → Reading is **Android only** | `SET-054`…`SET-056`, `TXT-080` |
| `Q-092` Android minimum | API 26; legacy photo saving uses `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="28"` | `PROD-025`, `CAM-017` |
| `Q-100` App licence | **Apache-2.0** for the app code, so others can build on it easily. Datasets and models stay separate files under their own licences (CC BY-SA 4.0 dictionaries and derived data, LGPL-2.1+ language-model table, Arphic stroke data) | `REL-050`, `SET-041` |
| `Q-105` Fuzzy pinyin | Not in v1 | — |
| `Q-106` Combinations | Confirmed: in pinyin search, after whole-query matches and before PREFIX matches, for every query of 2+ tokens | `SRCH-060`…`SRCH-068` |
| `Q-115` No-Jyutping headwords | (a) shown in Cantonese mode with their Mandarin senses and a "No Cantonese reading" note; no guessed readings | `ENT-116`, `DATA-022` |
| `Q-116` iOS voice | iOS minimum raised to **26**; the voice model is an Apple-hosted Background Assets pack; Voice available on every supported iOS version | `PROD-025`, `VOICE-095`, `ARCH-142` |
| `Q-117` Web CJK font | Web uses Compose Multiplatform's automatic font fallback, downloaded from a third-party font host when needed; no bundled Web font. Mobile keeps the bundled OFL rare-character font | `GLB-008`, `GLB-090`, `ARCH-005` |
| `Q-118` Choices | Shared text (Android) replaces the Reading draft. Links to `/zh/…` or `/yue/…` show that content in the link's mode **for that route only** — the saved mode preference never changes. **Every HSK word links to a CC-CEDICT entry**: linking comes from the project's HSK parser (`Q-121`); the build fails on any unresolved row (no fallback cards). Cantonese mode shows Cantonese example sentences only. Chinese block headings are used when the UI language is Chinese. Translate hand-off text is capped at 1,500 characters. CC-Canto lines adapted from CC-CEDICT are never hidden for characters with several Cantonese readings | `TXT-080`, `GLB-110`, `HSK-056`, `PIPE-044`, `MED-048`, `ENT-110`, `MED-030`, `ENT-114` |
| `Q-119` Cantonese search order | Like Pleco: CC-Canto entries first, then CC-CEDICT entries; a CC-CEDICT match is listed once per CC-CEDICT entry, not once per Cantonese reading | `SRCH-076`, `SRCH-077`, `SRCH-033`, `SRCH-049`, `DATA-076` |
| HSK edition (update) | The 2025 syllabus was published 2025-11 and is in force from 2026-07; the word key is word + pinyin + homograph index | `HSK-003`, `HSK-051` |
| Alternative pronunciations | `also pr.` and `Taiwan pr.` readings are search keys; matches rank after main-reading matches and show the matched reading with its label | `SRCH-078`, `DATA-192` |
| Web OCR | OCR is available on Web with the ML Kit text-recognition model repackaged for the browser (~2.6 MB), stored offline and versioned (re-downloaded only when a new version is published). iOS keeps Apple Vision | `CAM-130`…, `ARCH-197`, `DATA-196` |
| Script conversion | M3 split button on Reading, OCR text and Voice: main action from the detected script, then "Show original"; options dialog with source and target (Simplified, Traditional, Hong Kong, Taiwan, Taiwan with phrases — only from Simplified). Voice converts by default when the script setting is Traditional. Built on a separate OpenCC Kotlin Multiplatform library | `TXT-081`…, `CAM-130`…, `VOICE-096`, `ARCH-196` |
| Reading display | Word boundaries and tone colours stay, on all passage views | `GLB-066`, `SET-010` |
| Privacy policy | One `/privacy` page, identical in the app (bundled text) and on szdict.com | `SET-090`, `GLB-110` |
| Character decomposition | Collapsible "Characters" section at the bottom of the DICT tab: each hanzi's parts down to root components, each with reading and definition (BabelStone IDS). Separate from the card button below. Origins stay deferred | `ENT-120`…`ENT-125`, `DATA-191` |
| Unicode source | Unihan is a fallback definition source (shown only without CC-CEDICT / CC-Canto); Unicode-only characters are full entries | `ENT-126`, `SRCH-079`, `DATA-190` |
| HSK via variants | Variants (plain, old, erhua; up to two hops) of an HSK word, and words an HSK word is a variant of, show the same HSK badge; the grid lists only syllabus words | `HSK-097`, `DATA-193` |
| Inside-words button | Every word card with 2+ hanzi has the same button showing the sub-words and characters inside it, in all result types | `SRCH-105`, `ENT-073` |
| Grammar lessons | v2, not v1. HSK grammar list (593 items) plus imported lessons (AllSet and others) as separately licensed data files; own explanations and examples reviewed before shipping; linked to word ids; discovery in HSK and entry detail. Flashcards later (need user-data storage) | `ARCH-198`, `PIPE-083`, `HSK-099`, `ENT-127` |
| `Q-120` HSK characters | The HSK destination gets a **Characters** section listing recognition characters by HSK character level; those cards show no level badge, and no screen shows a character level — entries show only word levels. The writing list is kept as data | `HSK-098`, `DATA-194` |
| `Q-121` HSK data source | HSK data comes from the project's own parser, [hsk-syllabus-vocabulary-parser](https://github.com/Punpuf/hsk-syllabus-vocabulary-parser) (pinned): it links every word to CC-CEDICT (disambiguation file + CEDICT-format patch file for missing words such as 精彩纷呈) and will also provide the character lists, grammar rules and grammar-lesson sources per level | `HSK-051`, `PIPE-040`…, `PIPE-160`… |
| HSK levels on screens | A word can have several HSK levels; the entry page shows each level with its grammatical class from the syllabus, cards show only the level badges | `ENT-020`, `HSK-040` |
| OCR inputs (correction) | On every screen the camera feed is the main element, with a button to pick an image from storage. Drag and drop (the whole OCR screen is a drop target) and paste work without any dedicated UI element | `CAM-130`… |
| Decomposition (correction) | Always visible (not collapsible); shows which region of the character each part is, by highlighting it on a large glyph; on Web it stays available offline after its first load | `ENT-120`…, `DATA-191` |
| Taiwan phrases | "Traditional (Taiwan, with phrases)" is offered only from Simplified text; never chained through Simplified | `TXT-083` |
| Voice | No "Open in Reading" action; the transcript is already tappable | `VOICE-*` |
| Cantonese example sentences | Tatoeba Cantonese (`yue`) sentences, short subset, with English through Tatoeba links | `MED-048`, `DATA-188` |
| Normalisation | Matching key uses NFKC; dataset keys use the same form | `SRCH-010`, `DATA-071` |
| Navigation | Search · OCR · Voice · Reading · HSK (Web: Search · Reading · HSK); Settings is a card on the empty Search screen | `PROD-050`, `SRCH-115`, `SRCH-116` |
| Pronunciation visibility | One global toggle; hidden readings keep their space; tone colours unaffected | `GLB-120`…`GLB-122` |
| Handwriting | Bottom-sheet pad on Search and Reading, all platforms; ML Kit Digital Ink model bundled on mobile, lazily loaded on Web | `HW-*`, `ARCH-093` |
| Voice | Mobile-only destination; streaming Paraformer trilingual model | `VOICE-*`, `ARCH-094`, `ARCH-095` |
| Abbreviated pinyin and combinations | Any mix of full and abbreviated syllables, scored by penalty; bigram-ranked combinations | `SRCH-050`…`SRCH-068`, `TXT-026` |
| Repository and releases | Single repository with pipeline and sources; `YYYY.MM.DD-X`; trunk-based `main` | `PIPE-160`…, `REL-*` |
| UI languages | en, zh-Hans, zh-Hant | `GLB-100`…`GLB-103` |
| Deferred | Character origins | `PROD-030` |

---

## 3. Flagged by the review, not changed

Raised by the complexity and coherence reviews; not approved for change, listed for later.

**Complexity:** custom binary encodings inside SQLite (`DATA-141`…`DATA-144`, `DATA-171`); substring
index and `within` table alongside cheaper runtime lookups (`DATA-074`, `DATA-075`,
`DATA-100`…`DATA-104`); variant merge-group machinery (`SRCH-080`…`SRCH-086`); generation counters and
storage-level cancellation (`GLB-040`…`GLB-045`, `ARCH-036`); about 40 CI-enforced performance budgets;
pixel and animation values written as MUSTs; dataset N/N-1 versioning; pipeline diff gates; several
ranking comparators; fully ranking both search sides on every keystroke; result-list chips and
expanders; a custom entry-detail stack; HSK browser chrome; the "Read clipboard" launcher shortcut;
audio-plan rules.

**Accepted for now, revisit later:**
- Google Translate is the translate target on Android and Web (`MED-031`); a generic chooser later.
- Compose-Wasm renders Web to a canvas (weaker screen-reader support, text selection, find-in-page,
  browser translation); consider a more web-friendly front end after v1.

**Data licences to verify:** hanzi-writer-data stroke data is under the Arphic Public License (not
MIT); Make Me a Hanzi's LGPL/Arphic split per file; audio-cmn's CC BY-SA version.

**Readings still missing:** example sentences have no reading line (`MED-041`); the reading in result
cards may be ellipsised (`SRCH-094`).

**Cantonese gaps:** no Cantonese recorded audio (`DATA-186`); the proper-noun flag comes from Pinyin
capitalisation.
