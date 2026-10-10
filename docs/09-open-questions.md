# 15 — Open Questions (Ambiguous / needs review)

**These must be reviewed by a human before implementation.** Each item states what is unclear, why it
cannot be inferred confidently, the options, and a recommended resolution. The recommendation may be
adopted as-is, but it is a recommendation, not a decision.

Items are ordered by how much downstream work they block.

---

## Blocking: affects data pipeline design

### `Q-081` — Cantonese frequency data
**Unclear:** Segmentation quality and result ranking both depend on word frequencies. The reference app
has Mandarin frequencies from an OpenSubtitles-derived list; no Cantonese equivalent was found in the
repository, and licensing/quality of Cantonese corpora is not established.
**Options:** (a) reuse Mandarin frequencies for shared surface forms; (b) source a Cantonese corpus;
(c) ship without frequencies and use greedy longest-match segmentation.
**Recommendation:** (a) for v1 — Cantonese and Mandarin share most written vocabulary, and greedy
segmentation is noticeably worse (`我们研究生命科学` mis-segments as `我们/研究生/命/科学`). Mark it in
metadata so the choice is visible, and revisit with (b).
**Blocks:** `DATA-114`, `TXT-012`.

### `Q-082` — Which Mandarin senses to carry onto a Cantonese entry
**Unclear:** When a headword has several Mandarin entries (different readings, different meanings) and
one Cantonese reading, which Mandarin senses belong to the Cantonese entry? Meaning-to-reading
correspondence between the two languages is not mechanically derivable.
**Options:** (a) carry the senses of all Mandarin entries for that headword, in frequency order;
(b) carry only the most frequent Mandarin entry's senses; (c) attempt a reading-correspondence mapping
(Mandarin-to-Cantonese initial/tone correspondences).
**Recommendation:** (a) — over-inclusive is better than wrong for a dictionary, and the provenance
label tells the user these are Mandarin glosses. Report the count so the scale of the problem is known.
**Blocks:** `PIPE-053`, `DATA-182`.

### `Q-080` — Labelling sense provenance in Cantonese mode
**Unclear:** Should every sense show its source, or only when an entry mixes sources? Always labelling
is noisy; never labelling misleads (a Mandarin gloss may not be idiomatic Cantonese).
**Options:** (a) label only when sources are mixed; (b) always label; (c) group under sub-headings
(`Cantonese` / `Also in Mandarin`).
**Recommendation:** (c) for entries that mix sources, (a)'s silence for single-source entries. It is
clearer than an inline tag and costs no extra data.
**Blocks:** `DATA-022`, `ENT-052`.

### `Q-030` — Is there a Cantonese equivalent of the HSK feature?
**Unclear:** HSK is Mandarin-specific. Cantonese mode would simply lose a whole primary destination.
**Options:** (a) hide the destination (three destinations in Cantonese mode); (b) find a Cantonese
graded vocabulary list and offer the same browser; (c) offer a generic frequency-band browser in both
modes.
**Recommendation:** (a) for v1 — a fabricated level system would be worse than none. (c) is an
attractive follow-up because it also gives Mandarin users a non-HSK browse path.
**Blocks:** `HSK-002`, `PROD-051`.

### `Q-070` — Licence review of GPL/LGPL data sources
**Unclear:** The reference build includes CJKVI IDS (GPLv2) and Make Me a Hanzi (LGPL-3.0,
Arphic-derived) data, and chinese-lexicon which has **no licence file** in the pinned repository. It
also ships CC BY-SA dictionary data, whose share-alike clause reaches derived datasets.
**This is a legal question, not a technical one.** It must be answered before the successor ships.
**Options:** (a) legal review per source and drop what cannot be cleared; (b) drop the origins pack
entirely; (c) obtain explicit permission for chinese-lexicon.
**Recommendation:** (a), and treat the character-origins pack as the highest-risk item since three of
its four sources are the problematic ones. Ship v1 without origins if the review is not complete.
**Blocks:** `SET-041`, `PIPE-018`, whether the origins pack exists at all.

---

## Blocking: affects product scope

### `Q-072` — Keep the Android screen-assistant integration?
**Unclear:** Its value is real (OCR any screen) but it costs two services, a stub recognition service,
an extra process, an always-running component, and it degrades unpredictably across OEMs and Android
versions (screenshots return null under `FLAG_SECURE`, disabled context sharing, enterprise policy, or
OEM gaps). No usage data exists.
**Options:** (a) drop it from v1; (b) keep it as-is; (c) replace it with the much cheaper share-image
path plus documentation ("share a screenshot to the app").
**Recommendation:** (c) — the share target already covers screenshots with a fraction of the surface
area. Revisit (b) if users ask.
**Blocks:** `SET-070` to `SET-077`.

### `Q-071` — iOS home-screen widget in v1?
**Unclear:** The Android widget is a complete, polished feature (per-instance level masks, deterministic
daily word, audio without opening the database). Reproducing it on iOS is a meaningful chunk of work
that duplicates none of the shared code.
**Options:** (a) Android only in v1; (b) both; (c) neither in v1.
**Recommendation:** (a). The widget projection dataset (`DATA-130`) is platform-neutral, so adding iOS
later is cheap.
**Blocks:** `SET-068`.

### `Q-013` — Search history
**Unclear:** The reference app restores exactly one last query and has no history list. It is unclear
whether that is a deliberate minimalism or simply unbuilt.
**Options:** (a) keep one restored query; (b) add a recent-queries list on the empty screen; (c) add
recents plus saved/starred words.
**Recommendation:** (b) as a small, high-value addition to the empty state (recents above Popular
words), with a clear action. (c) is a separate feature needing its own spec.
**Blocks:** `SRCH-143`, the empty-state layout.

### `Q-050` — OCR entry points on the Reading destination
**Unclear:** "Read this text" and "read this photo" are the same user intent, but OCR access cards exist
only on the empty Search screen.
**Recommendation:** add them to the Reading destination's empty state. Low risk, clearly aligned with
the product's philosophy.
**Blocks:** `TXT-058`.

---

## Non-blocking: behaviour details

### `Q-012` — No-result copy when the other side has results
**Unclear:** Today both sides show `No matches` independently; a user on the English side of a Hanzi
query sees an empty screen while Chinese results exist behind the toggle.
**Recommendation:** show `No Chinese matches — 12 English matches` with the count acting as the switch.
Needs copy review.
**Blocks:** `SRCH-114`.

### `Q-020` — Note label wording
**Unclear:** Notes currently render their raw source keys (`also-pr`, `tw-pr`, `cl`, `abbr-for`).
**Recommendation:** a data-driven label table (`DATA-060`) with human copy: `classifier`,
`also pronounced`, `Taiwan pronunciation`, `abbreviation for`, `also written`, `see`, `see also`,
`variant of`, `old variant of`. Needs a copy pass by someone who knows the dictionary conventions.
**Blocks:** `ENT-058`.

### `Q-031` — Show the HSK syllabus reading when it differs
**Unclear:** For about 257 rows the syllabus teaches a tone-sandhi form (`bú kè qi`, `yíbàn`,
`xuéshēng`) that differs from the dictionary reading. The app discards it. Learners are examined on the
syllabus form.
**Options:** (a) keep discarding it; (b) show it as a secondary `HSK reading` line on the entry page;
(c) show it on HSK cards only.
**Recommendation:** (b) — it is genuinely useful information and the data is already there. Needs a
learner/teacher sanity check that the syllabus forms are not simply errors.
**Blocks:** `HSK-054`.

### `Q-032` — Show HSK syllabus glosses for linked entries
**Unclear:** The syllabus has its own glosses, sometimes clearer and level-appropriate, sometimes just a
subset. Linked cards show only dictionary definitions.
**Recommendation:** do not show them by default (duplication and noise), but keep them in the dataset so
this can be revisited with real examples.
**Blocks:** `HSK-055`.

### `Q-040` — Does the flash setting persist across launches?
**Recommendation:** no — match platform camera apps, which reset to the default each session. Persist
only for the session (`CAM-038`).

### `Q-041` — Which resolution is saved to the photo library?
**Unclear:** The capture pipeline downscales to 2560 px on the long side for OCR. A user saving a photo
probably expects full camera resolution.
**Options:** (a) retain the full-resolution capture separately and save that; (b) save the 2560 px
working copy.
**Recommendation:** (a) — memory cost is transient and a downscaled photo in someone's camera roll is a
surprise. If (a) proves too memory-hungry on low-end devices, (b) is acceptable but must be documented
in the UI copy.
**Blocks:** `CAM-074`.

### `Q-042` — iOS share extension for images
**Unclear:** Android registers as a share target for `image/*`. The iOS equivalent is a share extension,
which is a separate target with its own bundle and cannot easily share the app's dataset handle.
**Recommendation:** defer to post-v1; the in-app picker covers the same need with less machinery.

### `Q-060` — TTS fallback for words with no recording
**Unclear:** Today the play button is simply disabled, which reads as broken.
**Recommendation:** fall back to TTS for the headword, with no visual distinction. Needs a quality check
that platform Mandarin/Cantonese TTS pronounces isolated words acceptably (tone-3 sandhi and neutral
tones are common failure cases).
**Blocks:** `MED-011`.

### `Q-061` — Translate hand-off mechanism
**Unclear:** The reference implementation targets hard-coded third-party activity class names, which
break when that app changes.
**Recommendation:** implicit intents only; accept a chooser; drop the component names.
**Blocks:** `MED-033`.

### `Q-062` — Example-sentence ordering
**Unclear:** Examples appear in source order. Some are much better illustrations than others.
**Recommendation:** rank by (contains the headword as a standalone segmented word, then shorter first),
computed in the pipeline. Needs a spot check that the ranked top-5 are actually better.
**Blocks:** `MED-047`.

---

## Non-blocking: platform and delivery

### `Q-090` — Which asset packs are bundled versus downloaded?
**Unclear:** The specs require the core dictionary to be bundled and permit packs to be downloaded, but
the split has product consequences (a first run with no audio at all may feel broken).
**Options:** (a) bundle the syllable audio (13 MB, covers every word by concatenation) and download the
rest; (b) download everything; (c) bundle nothing optional and prompt on first use.
**Recommendation:** (a) — syllable audio alone gives every entry a usable pronunciation, and it is the
smallest pack with the widest coverage.
**Blocks:** `ARCH-141`, `PERF-070`.

### `Q-091` — Web dataset delivery and offline expectations
**Unclear:** A 10-16 MB dataset over the network on first visit is a real cost. Is the Web target
expected to work fully offline (PWA, installed) or is it a "try it in the browser" surface?
**Recommendation:** treat Web as a full PWA: service worker, dataset in OPFS, visible one-time install
step. If the product intent is a lightweight preview instead, the architecture changes materially (a
server-side search API), so this needs an explicit answer.
**Blocks:** `ARCH-004`, `PERF-060`.

### `Q-092` — Minimum Android API level
**Unclear:** The reference app targets API 26+. API 26-28 costs a legacy photo-saving path
(`CAM-075`) and misses several conveniences.
**Recommendation:** raise the minimum to API 29 (Android 10) unless install-base data says otherwise;
it removes the legacy storage path entirely.

### `Q-093` — Does the successor keep the stroke-practice quiz?
**Unclear:** It is a substantial, high-quality feature (about 1200 lines of geometry and recognition)
that is arguably a different product from a dictionary, and its data is the largest non-audio pack.
**Options:** (a) keep it as specified; (b) keep animation only, drop the quiz; (c) drop the tab.
**Recommendation:** (a) — it is finished, well-specified, and differentiating. But confirm it is in
scope, because reimplementing it is a meaningful fraction of the total build.
**Blocks:** `MED-060` to `MED-071`, the strokes pack.

### `Q-094` — Pronunciation scoring boundary
**Unclear:** The reference project documents a `PronunciationScoringPort` as a future boundary
(Hanzi plus reference reading plus 16 kHz mono PCM in, a scoring result out) but nothing is wired.
**Recommendation:** leave it out of v1 entirely, including the placeholder interface. Add it when the
feature is actually specified; a port with no implementation is a comment, not architecture.
