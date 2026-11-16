# Dataset pipeline

Builds the Mandarin and Cantonese datasets, their language-model companion files and the widget
projections in the physical format of `contract/SCHEMA.md` (logical spec: `docs/07-data.md`).

```sh
uv run pipeline/fetch.py                 # download the pinned large sources into pipeline/sources/ (SHA-256 checked)
uv run pipeline/build.py                 # full build -> pipeline/out/
uv run pipeline/build.py --only mandarin # one dataset
uv run pipeline/build.py --fixture       # small fixture -> pipeline/out/fixture/ (committed copy: contract/fixture/)
uv run --with pytest --with numpy pytest pipeline/tests
```

Python 3.12+ with numpy (declared in the scripts' PEP 723 headers, so `uv run` installs it); the rest is the
standard library. Reading the libime model needs Python 3.14 (`compression.zstd`) or the `zstd` command.

- `sources.lock.json` — URL + SHA-256 of every downloaded input; `sources-pinned/` — small committed
  inputs (HSK parser outputs, the Mandarin frequency list, `freq/en_common.tsv`: the 10,000 most
  frequent English word forms used for search side selection); `refs/` (repository root) —
  CC-CEDICT, CC-Canto, the Cantonese readings file, Tatoeba `yue`.
- `data/` — syllable inventories, aliases, fixture selection.
- `szdict/` — the code: `norm` (SRCH-010/013), `roman`, `senses` (note-convention parser), `mandarin`,
  `cantonese`, `hsk`, `unihan`, `lm`, `core` (indexes + SQLite writer), `validate` (DATA-200 gates).
- `out/build-report.json` — counts, gate results, substitutions.
- Language model: the Mandarin companion (`out/mandarin-lm.db`, about 14 MB) holds the segmentation
  model (libime unigrams and bigrams) and the combination model (`szdict/lm_ngram.py`: libime folded
  onto headwords and merged with OpenSubtitles 2018 zh_cn n-gram counts, `szdict/subs.py`, digits read
  out by `szdict/textnorm.py`; trigrams; pruned to `Config.lm_ngram_budget_bytes`). The subtitle
  corpus is optional in the lock file; its counts and the ARPA arrays are cached under
  `sources/cache/` (about 25 s to rebuild). Its quality gate runs in the app's JVM tests
  (`CombinationQualityTest`, thresholds in `contract/lm-quality.json`).
