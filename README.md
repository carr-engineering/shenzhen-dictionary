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
| 04 | [Entry Detail and Media](docs/04-entry-and-media.md) | Word page, tabs, internal links, related words, audio, TTS, examples, strokes |
| 05 | [Reading and Input](docs/05-reading-and-input.md) | Segmentation and passages, Reading, Camera/OCR, handwriting input, voice transcription |
| 06 | [HSK and Settings](docs/06-hsk-and-settings.md) | HSK browser, settings, persistence, shortcuts, widget, share targets |
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

Desktop-native targets, user accounts, cloud sync, SRS/flashcards, a system-wide input method,
pronunciation scoring, the Android screen-assistant integration and character origins are out of
scope for v1. See `PROD-030`.
