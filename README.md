# Shenzhen Dictionary — Implementation Specifications

This repository contains the **implementation-ready specification set** for a production-quality
successor to an existing Chinese dictionary application (referred to throughout as *the reference
app*). The reference app is a Kotlin Multiplatform / Compose Android application whose UX is
considered ~90–95% representative of the intended product, but whose architecture, data layout,
performance and application size are **not** to be carried forward.

These documents are the source of truth. A coding agent should be able to build the successor
application from them **without reading the reference app's code**.

## Read in this order

| # | Document | Purpose |
|---|----------|---------|
| 01 | [Product](docs/01-product.md) | What the product is, journeys, platforms, navigation, modes, philosophy |
| 02 | [Global Conventions](docs/02-global-conventions.md) | Shared rules referenced by every feature spec (text, romanisation, states, sheets, theme, accessibility, UI languages, routes) — read before any feature |
| 03 | [Search](docs/03-search.md) | Query handling, matching (incl. abbreviated pinyin and multi-word combinations), ranking, result presentation, home screen |
| 04 | [Entry Detail and Media](docs/04-entry-and-media.md) | Word page, tabs, internal links, related words, character decomposition, audio, TTS, examples, strokes |
| 05 | [Reading and Input](docs/05-reading-and-input.md) | Segmentation and passages, Reading, Camera/OCR (all platforms), script conversion, handwriting input, voice transcription |
| 06 | [HSK and Settings](docs/06-hsk-and-settings.md) | HSK browser, settings, persistence, privacy policy, shortcuts, widget, share targets |
| 07 | [Data](docs/07-data.md) | **Exact shape of the processed dataset** and the in-repo pipeline that builds it |
| 08 | [Engineering](docs/08-engineering.md) | Architecture (non-binding where marked), performance budgets, release and repository practice |
| 09 | [Open Questions](docs/09-open-questions.md) | **Decisions taken and items still needing human review** |

## Requirement conventions

Every normative statement carries an ID and a strength:

- `[MUST]` — mandatory product behavior. Changing it changes the product.
- `[SHOULD]` — strongly intended behavior; deviate only with a recorded reason.
- `[REC]` — recommended implementation choice. **Free to change** if there is a compelling
  technical reason. All of the architecture part of [Engineering](docs/08-engineering.md) is `[REC]` unless a
  statement is explicitly marked otherwise.

ID prefixes: `PROD` (overview), `GLB` (global), `SRCH`, `ENT`, `HSK`, `CAM`, `TXT`, `MED`, `SET`,
`DATA`, `PIPE`, `ARCH`, `PERF`, `HW` (handwriting), `VOICE`, `REL` (release), `Q` (open question).
IDs are stable: they did not change when the original 16 documents were consolidated into 9.

Cross-references use the ID, e.g. "see `GLB-014`".

## Non-goals for the first release

Desktop-native targets, user accounts, cloud sync, user-curated data (search history list,
favourites, saved words, notes), SRS/flashcards, a system-wide input method and pronunciation scoring
are out of scope for v1. The screen-assistant integration has been removed from the product;
character origins are deferred to a later release (character decomposition ships in v1); grammar
lessons are designed for v2. See `PROD-030` and `PROD-017`.

## Licence

The application code is licensed under **Apache-2.0**. Datasets and models are shipped as separate files
under their own licences (CC BY-SA 4.0 dictionary data and derived datasets, LGPL-2.1+ Mandarin
language-model file, Arphic Public License stroke data, Apache-2.0 voice model and OpenCC
dictionaries, Unicode License v3 Unihan data, BabelStone IDS decomposition data, LGPL-3.0 Make Me a
Hanzi stroke-to-component matches, CC BY-SA 4.0 HSK parser patch entries, and others), all listed in
the attribution source (`REL-052`, `SET-040`). HSK word data comes from the
[HSK syllabus vocabulary parser](https://github.com/Punpuf/hsk-syllabus-vocabulary-parser) (code
MIT), consumed at a pinned version (`PROD-095`). Script conversion uses OpenCC through a
separate Kotlin Multiplatform library, imported at a pinned version (`ARCH-196`).
