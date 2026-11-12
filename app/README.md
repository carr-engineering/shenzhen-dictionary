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
(never committed): `data/` (datasets, packs, audio and a `manifest.json` of sizes and content tags
from `pipeline/out`), `opencc/`, `strokes/` and `sqlite3/` (sqlite-wasm). The dataset of the active
mode is downloaded on the first visit by the store worker (`wasmJsMain/resources/sqlite-worker.js`)
and kept in OPFS; a file whose tag in the manifest changed is downloaded again.

- The CJK font comes from the font host; add `-Pszdict.web.cjkFont=/path/to/NotoSansCJKsc-Regular.otf`
  to serve it from the app's own origin instead (development without the network).
- `http://localhost:8081/?selftest=1` runs the shared engine in the browser (search, entry, examples,
  decomposition, analysis and HSK in both modes) and logs `[selftest] …` lines to the console.
- The distribution is static files; the host must answer every route of the route table
  (`/zh/…`, `/yue/…`, `/reading`, `/ocr`, `/settings`, `/privacy`) with `index.html`.
- OCR and handwriting run the Android graphs in `recognition-worker.js` (TFLite Web runtime). The
  model files are staged under `models/` with a `manifest.json` (sizes, SHA-256, files per set); the
  worker downloads a set on first use (OCR 5.6 MB, handwriting 7.9 MB plus the shared 3.8 MB
  runtime, before compression), verifies it and keeps it in Cache Storage.
  `/?selftest=recognition&base=testdata/&ocr=a.png,b.png&ink=ink-fixtures.txt` runs both on files
  served under `base` (the module's `androidInstrumentedTest/assets/testdata`) and logs the results.
