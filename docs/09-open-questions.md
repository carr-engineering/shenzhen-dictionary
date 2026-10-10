# 09 — Open Questions and Decisions

Section 1 lists the questions that are still open. Section 2 is the decision log: every question that has
been answered, with where the answer is applied. Closed questions keep their ID so references stay
meaningful; their full discussion is in the git history of this file.

---

## 1. Still open

### `Q-042` — iOS share extension (confirm the v1 approach)
**Background:** Android apps can receive shared images and text through intent filters, which open the
app directly. On iOS the equivalent is a separate **share extension** target, with three limits:
- it runs in its own process with a small memory budget (developers report about 120 MB), so a large
  photo must be downsampled before anything else;
- it cannot officially open its own containing app (the old workaround was blocked in iOS 18 and risks
  App Review rejection);
- it cannot use the app's in-memory state; data is handed over through a shared **App Group**
  container.
  

**Current spec (`SET-057`, `ARCH-147`):** one share extension accepting one image or text. It
downsamples the image (≤ 2048 px) or keeps the text, saves it to the App Group inbox and shows
"Saved — open Shenzhen Dictionary to continue". When the app next becomes active it consumes the inbox
once: image → OCR review, text → Reading. No lookup inside the extension in v1.
**Alternative:** run lookup / OCR inside the extension (more code, tighter memory).
**Needs:** confirmation that "save, then open the app" is acceptable for v1.

Sounds janky, let's not have this on ios for now.

### `Q-092` — Minimum Android API level (advice)
**Facts (2026):** AndroidX libraries default to minSdk 23; Compose 1.13 and CameraX 1.7 move to 24;
`onnxruntime-android` 1.31 (voice) already requires 24. Device share (Dec 2025): API 24–25 1.6 %,
26–27 2.3 %, 28 4.5 %.
- **API 24** is feasible: it is the real floor set by the libraries. Costs: core-library desugaring for
  `java.time`, no native adaptive icons / XML fonts, and the legacy photo-saving path
  (`WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="28"`, as you suggested — that covers API 24–28).
- **API 26** (current spec) keeps about 97.6 % of devices and gives native `java.time`, adaptive icons
  and XML fonts.
- **API 29** would add permission-free MediaStore saving, `Typeface.CustomFallbackBuilder` (rare-character
  fallback fonts), system dark theme and gesture-navigation insets as a baseline, and TLS 1.3 — at about
  9 % reach.

**Recommendation:** 26. Choose 24 only if the extra ~1.6 % matters; nothing in the spec breaks.
**Blocks:** `PROD-025`, `CAM-075`.

Keep 26

### `Q-100` — App licence and store compatibility
**Unclear:** GPL-family licences conflict with Apple App Store terms; LGPL adds a relinking requirement
that iOS apps cannot practically meet. Bundled data keeps its own licences (CC BY-SA 4.0 dictionaries,
LGPL-2.1+ language model, Arphic stroke data, CC BY-SA Cantonese corpora).
**Options:** (a) GPL-3.0-or-later with an App Store exception (needs a CLA so the exception covers all
contributions); (b) MPL-2.0 (file-level copyleft, App Store friendly); (c) Apache-2.0.
**Recommendation:** (b), with datasets and models as separate files under their own licences, listed
in the attribution source (`REL-052`).
**Blocks:** `REL-050`, `SET-041`.

Let's actually have Apache-2.0. Thus allowing for other people to build upon this work more easily.

### `Q-105` — Fuzzy pinyin (post-v1 candidate)
**What it means:** many speakers do not distinguish some sounds — `z`/`zh`, `c`/`ch`, `s`/`sh`, `n`/`l`,
`an`/`ang`, `en`/`eng`, `in`/`ing` (and `n`/`l` in Cantonese). Fuzzy pinyin lets a query typed with one
of a pair also match the other, so `zongguo` still finds 中国 (zhōngguó) and `lihao` finds 你好. It is
cheap to add through the syllable alias data, but it makes many more queries ambiguous.
**Recommendation:** not in v1; consider an opt-in setting later.

Not for now.

### `Q-106` — Combinations: position and activation
**Current spec:** the Combinations group sits after whole-query matches (EXACT / EXACT_TONELESS) and
before PREFIX matches, and runs for every query of 2+ tokens.
**Open:** whether that position and trigger feel right in real listening use.
**Blocks:** `SRCH-060`…`SRCH-068`.
This is for the pinyin search right? If so, yes, i think this order makes sense.

### `Q-115` — Cantonese mode: CC-CEDICT words with no Jyutping
**Facts:** 19,905 current CC-CEDICT headwords have no Jyutping in CC-Canto or the 2015 readings file
(which also lacks 24,931 newer CC-CEDICT entries).
**Options:** (a) show them in Cantonese mode with their Mandarin senses and a "no Cantonese reading"
note; (b) generate Jyutping per character from single-reading characters and mark it as generated;
(c) hide them in Cantonese mode.
**Recommendation:** (a) for v1 — never show a guessed reading; (b) later, clearly marked.
**Blocks:** `DATA-022`, `PIPE-053`, `PIPE-078`.
option a is okay.

### `Q-116` — Voice on iOS requires iOS 26+
**Facts:** On-Demand Resources is deprecated in the iOS 27 SDK. Its successor, Background Assets with
Apple-hosted packs, needs iOS 26+. Self-hosted Background Assets works on iOS 16.1+ but needs our own
download server.
**Current spec:** the voice model is an Apple-hosted Background Assets pack, so Voice is offered on
iOS 26+ and hidden on older iOS (as on Web). The rest of the app keeps iOS 16 as the minimum.
**Options:** keep that; raise the iOS minimum to 26; or self-host the pack on szdict.com for iOS 16–25.
**Blocks:** `VOICE-095`, `PROD-025`.

Raise iOS min to 26 and use the background asset pack.

### `Q-117` — Web CJK font size
**Facts:** Compose-Wasm draws text on a canvas and cannot use the browser's fonts; its automatic
fallback downloads Noto subsets from a third-party server, which breaks offline use and `GLB-090`. The
Web build therefore ships its own OFL CJK font, cut down to the dataset and UI characters (`ARCH-005`).
**Open:** the real size per script (estimated ≤ 8 MB) and whether it can load before the first frame;
measure at first integration. This raised the Web first-visit target (`PERF-060`) to 15 s / 30 s.

For web, downloading a 3rd party font is okay.

### `Q-118` — Choices made while applying the decisions (confirm)
- Shared text **replaces** the Reading draft, like `Open in Reading` (`TXT-080`, `SET-056`). What?
- iOS share inbox: if several items wait, the newest wins (`SET-057`).
- Opening a `/zh/…` or `/yue/…` link switches the app's dictionary mode, and the switch persists
  (`GLB-110`). it should not persist that as the new user prefenrence, only adjust for that word entry.
- HSK words missing from the dictionary are **no longer added as syllabus-sourced entries**; they stay
  unlinked and show a card with headword, pinyin and part of speech but no definition (`HSK-056`,
  `HSK-023`). The Word of the Day never picks them (`SET-063`). this is unacceptable. each HSK word must be mapped to a CC-CEDICT entry as is already done by the current processing.
- Cantonese mode shows only Cantonese example sentences, not Mandarin ones (`MED-048`). OK
- Chinese labels for definition blocks, e.g. 粤语释义（CC-Canto）/ 普通话释义 (`ENT-110`…). What?
- Translate hand-off text is capped at 1,500 characters (`MED-030`). What does this mean?
- CC-Canto lines "adapted from CC-CEDICT" are hidden when they repeat current CC-CEDICT text. What does "adapted from CC-CEDICT" mean?
  (`ENT-114`). Risk: for polyphones this can remove the per-Jyutping meaning split (行 hong4 losing
  "row; profession"); check against real entries before finalising. Explain this risk better? How would that be an issue? Each word is ided by trad + simpl + pronunciaiton, no? thus how is this is an issue?

### `Q-119` — English search on Cantonese polyphones
**Facts:** in Cantonese mode a single-character polyphone such as 行 has one entry per Jyutping, and
each carries the same headword-level Mandarin block (`ENT-113`). English search indexing that block
would return all five 行 entries for "walk".
**Options:** (a) index the Mandarin block once per headword (on the most frequent Jyutping entry);
(b) index it on every entry; (c) index only Cantonese senses for polyphones.
**Recommendation:** (a).
**Blocks:** `SRCH-047`, `DATA-076`.

I'm a bit confused with these options. I think we can follow how pleco does it:
They will first show the CC-Canto entries, and afterwards the CC-CEDICT entries.

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
| `Q-061` Translate hand-off | Android: Google Translate (web URL if not installed). iOS: Apple Translation (iOS 17.4+), else the Google Translate web URL. Web: new tab to Google Translate. Other translators post-v1 | `MED-030`…`MED-033` |
| `Q-062` Example ordering | Ranked in the pipeline (headword as a standalone word first, then shorter) | `MED-047` |
| `Q-070` Licence review | Character origins deferred (removes CJKVI IDS, chinese-lexicon and the glyph sources); the remaining question is the app licence (`Q-100`) | `SET-040`, `SET-041` |
| `Q-071` iOS widget | Android widget in v1; daily pick and projection live in shared code so an iOS widget is cheap later | `SET-068`, `SET-063`, `DATA-130` |
| `Q-072` Screen assistant | **Removed** (not deferred). Instead the app receives shared **images → OCR** and shared **text → Reading** | `SET-054`, `SET-056`, `SET-057` |
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
