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
| 01 | [Product Overview](docs/01-product-overview.md) | What the product is, journeys, platforms, modes, philosophy |
| 02 | [Global Conventions](docs/02-global-conventions.md) | Shared rules referenced by every feature spec — read before any feature |
| 03 | [Search](docs/03-search.md) | Query handling, matching, ranking, result presentation |
| 04 | [Entry Detail](docs/04-entry-detail.md) | Word/entry display, tabs, internal links, related words |
| 05 | [HSK](docs/05-hsk.md) | Level browser, HSK metadata, ordering |
| 06 | [Camera / OCR](docs/06-camera-ocr.md) | Android + iOS capture, recognition, focus, flash, image saving |
| 07 | [Text Analysis & Reading](docs/07-text-analysis-reading.md) | Segmentation, passage interaction, clipboard reading |
| 08 | [Pronunciation & Media](docs/08-pronunciation-and-media.md) | Audio plans, TTS, examples, strokes, character origins |
| 09 | [Settings & Platform Integration](docs/09-settings-and-platform-integration.md) | Persisted settings, shortcuts, widgets, share targets, assistant |
| 10 | [Data Contract](docs/10-data-contract.md) | **Exact shape of the processed dataset the app consumes** |
| 11 | [Data Pipeline Requirements](docs/11-data-pipeline.md) | What the offline build must produce, from which sources, with what validation |
| 12 | [Technical Architecture](docs/12-architecture.md) | Recommended implementation (explicitly non-binding where marked) |
| 13 | [Performance Targets](docs/13-performance.md) | Measurable budgets and benchmark definitions |
| 14 | [Findings Register](docs/14-findings-register.md) | Preserve / Improve / Bug / Replace / New classification of every notable behavior |
| 15 | [Open Questions](docs/15-open-questions.md) | **Ambiguous items requiring human review before implementation** |
| 16 | [Coverage Checklist](docs/16-coverage-checklist.md) | Proof that every reference-app feature is documented, excluded, or flagged |

## Requirement conventions

Every normative statement carries an ID and a strength:

- `[MUST]` — mandatory product behavior. Changing it changes the product.
- `[SHOULD]` — strongly intended behavior; deviate only with a recorded reason.
- `[REC]` — recommended implementation choice. **Free to change** if there is a compelling
  technical reason. All of [Technical Architecture](docs/12-architecture.md) is `[REC]` unless a
  statement is explicitly marked otherwise.

ID prefixes: `PROD` (overview), `GLB` (global), `SRCH`, `ENT`, `HSK`, `CAM`, `TXT`, `MED`, `SET`,
`DATA`, `PIPE`, `ARCH`, `PERF`, `Q` (open question).

Cross-references use the ID, e.g. "see `GLB-014`".

## Non-goals for the first release

Desktop targets, user accounts, cloud sync, SRS/flashcards, handwriting *input* (as an input method),
speech recognition, and pronunciation scoring are out of scope. See `PROD-030`.
