# 09 — Settings and Platform Integration

---

## 1. Settings screen

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

## 2. Persisted settings

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

## 3. Theme

`SET-020` `[MUST]` `Light` / `Dark` / `Auto` (follow system). The resolved theme drives the palette
(`GLB-060`) and the system bar icon contrast (`GLB-070`).

`SET-021` `[MUST]` The theme applies to the widget as well (`SET-062`).

`SET-022` `[SHOULD]` The design is a fixed brand palette; platform dynamic colour is deliberately not
adopted.

---

## 4. Attribution

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

## 5. Launcher shortcuts / quick actions

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

## 6. Share-image target

`SET-054` `[MUST]` The app registers as a share target for `image/*`. Receiving a shared image opens
the camera surface in gallery-review mode and runs OCR on it (`CAM-040`).

`SET-055` `[MUST]` The shared URI is read once; the intent is consumed; only the URI's scheme and
authority may be logged.

---

## 7. Android home-screen widget (HSK word of the day)

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

## 8. Android screen-assistant integration

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

## 9. Edge cases (all `[MUST]`)

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
