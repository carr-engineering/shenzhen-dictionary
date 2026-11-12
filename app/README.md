# Shenzhen Dictionary — app

Kotlin Multiplatform + Compose Multiplatform. Targets: Android, and Web (Kotlin/Wasm, Compose on a canvas).

| Module | Contents |
|--------|----------|
| `core` | shared engine: store, search, segmentation, lattice decoder, domain facade (`com.szdict.core.api`) |
| `composeApp` | UI, state holders, Android platform layer (widget, shortcuts, share targets), Web platform layer (`wasmJsMain`) |
| `recognition` | OCR with per-character alternatives and handwriting, run from the extracted graphs: LiteRT on Android, the TFLite Web runtime in a worker on Web |
| `voice` | sherpa-onnx streaming Paraformer wrapper |

## One-time setup (large files are not in git)

```bash
uv run pipeline/fetch.py && uv run pipeline/packs/build_packs.py && uv run pipeline/build.py   # datasets + packs -> pipeline/out
app/tools/publish-animator.sh        # stroke-order library (Android, JVM, Wasm) -> app/local-maven (needs ../shenzhen_hanzi_animator_kmp)
(cd app && ./gradlew :recognition:fetchModels)   # handwriting model (needs ../chinese-handwritting) and the TFLite Web runtime
app/tools/fetch-voice-model.sh       # sherpa-onnx library + voice model (~280 MB)
```

`opencc-kmp` is resolved from `../opencc-kmp/.cache/maven`.

## Build and test

```bash
cd app
./gradlew :core:jvmTest :composeApp:testDebugUnitTest :recognition:testDebugUnitTest
./gradlew :composeApp:assembleDebug      # or assembleRelease (R8, debug-signed)
```

## Web

```bash
cd app
./gradlew :composeApp:wasmJsBrowserDevelopmentRun     # http://localhost:8081 (webpack dev server)
./gradlew :composeApp:wasmJsBrowserDistribution       # composeApp/build/dist/wasmJs/productionExecutable
./gradlew :core:jvmTest --tests '*PureInflateTest*'   # the inflater used on Web against java.util.zip
```

Both tasks stage what the page fetches next to the app shell into `composeApp/build/web/static`
(never committed): `data/` (datasets, packs, audio and a `manifest.json` from `pipeline/out`),
`opencc/`, `strokes/`, `sqlite3/` (sqlite-wasm), `models/`, and `pwa/` (web app manifest, the
icon set — a square SVG favicon and PNGs drawn at their own size from the vector drawables of the
Android adaptive icon, with `any` and safe-zone `maskable` variants — and `privacy.html` rendered
from the `PrivacyPolicy*.kt` sources).

- **Datasets.** `data/manifest.json` carries the `dataset_version` and each file's size and
  SHA-256. The store worker (`wasmJsMain/resources/sqlite-worker.js`) downloads the active mode's
  dataset on the first visit, verifies it and keeps it in OPFS; a pointer file names the verified
  files. Later visits open the installed dataset without the network; the manifest is checked in
  the background, a changed file is downloaded and verified beside the one in use, and one write
  of the pointer file installs it for the next load (the app shows "Dictionary updated"; other
  tabs are told through a BroadcastChannel). Updates are full downloads — changesets are not built.
  The language model (`<mode>-lm.db`) is fetched when a query first needs it; the search runs
  again when it arrives.
- **Service worker** (`sw.js`, distribution only). The distribution task writes the build's
  version and shell file list into it. It keeps the shell (start page, code, WebAssembly, workers,
  SQLite, the privacy page) for offline starts, answers every route with the start page, and
  keeps lazily fetched audio, OpenCC dictionaries, fonts and stroke graphics as they pass through.
  A new build installs beside the running one and takes over on the next load. The development
  server runs without it.
- **Font.** Without preparation the app fetches the complete Noto Sans CJK (16 MB) of the glyph
  variant in use from the font host. `python3 tools/stage-web-fonts.py` (needs `fonttools`) cuts
  the SC, TC and HK variants into frequency tiers under `build/web/static/cjk/fonts` — about
  1.5 MB for the first — which the distribution then includes and the app fetches in order;
  `--full` also stages the complete fonts for a deployment that must not ask the font host.
- **Compression.** `node tools/web-precompress.mjs` writes `.br` and `.gz` siblings into the
  distribution for hosts that serve precompressed files; otherwise the host must compress
  (the code is 4.6 MB with Brotli, 17 MB without).
- **Hosting.** Static files. Every route of the route table (`/zh/…`, `/yue/…`, `/reading`, `/ocr`,
  `/settings`) is answered with `index.html`; `/privacy` with `privacy.html` (the running app
  opens its own bundled copy); `sw.js`, `index.html` and `data/manifest.json` must be revalidated
  on every request (`Cache-Control: no-cache`).
- `http://localhost:8081/?selftest=1` runs the shared engine in the browser (search, entry, examples,
  decomposition, analysis and HSK in both modes) and logs `[selftest] …` lines to the console.
- `localStorage["szdict.debug"] = "1"` adds `window.szdictState()` (destination, query, side,
  counts, download state) for browser automation: the app draws on a canvas.
- OCR and handwriting run the Android graphs in `recognition-worker.js` (TFLite Web runtime). The
  model files are staged under `models/` with a `manifest.json` (sizes, SHA-256, files per set); the
  worker downloads a set on first use (OCR graphs 1.9 MB, handwriting 7.9 MB, plus the 3.8 MB
  runtime they share; about 2.5 MB and 6.5 MB over gzip), verifies it and keeps it in Cache Storage.
  `/?selftest=recognition&base=testdata/&ocr=a.png,b.png&ink=ink-fixtures.txt` runs both on files
  served under `base` (the module's `androidInstrumentedTest/assets/testdata`) and logs the results.
