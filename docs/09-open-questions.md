# 09 — Open Questions and Decisions

Section 1 lists the questions that are still open. Section 2 is the decision log: every question that has
been answered, with where the answer is applied. Closed questions keep their ID so references stay
meaningful; their full discussion is in the git history of this file.

---

## 1. Still open

None. Every question raised in the 2026-10 review has been answered; see the decision log. New questions
should be added here with the next free `Q-` number.

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
| `Q-110` HSK edition | 2025 HSK syllabus (CTI, effective 2025-11-18): cumulative 300 / 500 / 1,000 / 2,000 / 3,600 / 5,400 / 11,000 words | `HSK-003`, `DATA-200` |
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
| `Q-118` Choices | Shared text (Android) replaces the Reading draft. Links to `/zh/…` or `/yue/…` show that content in the link's mode **for that route only** — the saved mode preference never changes. **Every HSK word links to a CC-CEDICT entry**: normalisation plus a committed manual mapping file; the build fails on any unlinked row (no fallback cards). Cantonese mode shows Cantonese example sentences only. Chinese block headings are used when the UI language is Chinese. Translate hand-off text is capped at 1,500 characters. CC-Canto lines adapted from CC-CEDICT are never hidden for characters with several Cantonese readings | `TXT-080`, `GLB-110`, `HSK-056`, `PIPE-044`, `MED-048`, `ENT-110`, `MED-030`, `ENT-114` |
| `Q-119` Cantonese search order | Like Pleco: CC-Canto entries first, then CC-CEDICT entries; a CC-CEDICT match is listed once per CC-CEDICT entry, not once per Cantonese reading | `SRCH-076`, `SRCH-077`, `SRCH-033`, `SRCH-049`, `DATA-076` |
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
