# Dataset pipeline

Builds the Mandarin and Cantonese datasets, their language-model companion files and the widget
projections in the physical format of `contract/SCHEMA.md` (logical spec: `docs/07-data.md`).

```sh
uv run pipeline/fetch.py                 # download the pinned large sources into pipeline/sources/ (SHA-256 checked)
uv run pipeline/build.py                 # full build -> pipeline/out/
uv run pipeline/build.py --only mandarin # one dataset
uv run pipeline/build.py --fixture       # small fixture -> pipeline/out/fixture/ (committed copy: contract/fixture/)
uv run --with pytest pytest pipeline/tests
```

Python 3.12+, standard library only. Reading the libime model needs Python 3.14 (`compression.zstd`)
or the `zstd` command.

- `sources.lock.json` — URL + SHA-256 of every downloaded input; `sources-pinned/` — small committed
  inputs (HSK parser outputs, frequency list); `refs/` (repository root) — CC-CEDICT, CC-Canto, the
  Cantonese readings file, Tatoeba `yue`.
- `data/` — syllable inventories, aliases, fixture selection.
- `szdict/` — the code: `norm` (SRCH-010/013), `roman`, `senses` (note-convention parser), `mandarin`,
  `cantonese`, `hsk`, `unihan`, `lm`, `core` (indexes + SQLite writer), `validate` (DATA-200 gates).
- `out/build-report.json` — counts, gate results, substitutions.
