# Shenzhen Dictionary — app

Kotlin Multiplatform + Compose Multiplatform. Targets: Android, iOS (iPhone and iPad), and Web (Kotlin/Wasm, Compose on a canvas).

| Module | Contents |
|--------|----------|
| `core` | shared engine: store, search, segmentation, lattice decoder, domain facade (`com.szdict.core.api`) |
| `composeApp` | UI, state holders, Android platform layer (widget, shortcuts, share targets), iOS platform layer (`iosMain`), Web platform layer (`wasmJsMain`) |
| `iosApp` | Xcode project hosting the `composeApp` framework: Swift entry point, Info.plist, icon, entitlements |
| `speech` | iOS only: sherpa-onnx through its C API |
| `recognition` | OCR with per-character alternatives and handwriting. OCR on Android and iOS: PP-OCRv4 detector, orientation classifier and PP-OCRv6 Small recogniser on ONNX Runtime (`ocr2`); OCR on Web: the extracted graphs on the TFLite Web runtime in a worker (`ocr`). Handwriting: LiteRT on Android, TensorFlow Lite C on iOS, the TFLite Web runtime on Web |
| `voice` | sherpa-onnx streaming Paraformer wrapper |

## One-time setup (large files are not in git)

```bash
uv run pipeline/fetch.py && uv run pipeline/packs/build_packs.py && uv run pipeline/build.py   # datasets + packs -> pipeline/out
app/tools/publish-animator.sh        # stroke-order library (Android, JVM, Wasm, iOS) -> app/local-maven (needs ../shenzhen_hanzi_animator_kmp; ANIMATOR_SKIP_IOS=1 without Xcode)
(cd app && ./gradlew :recognition:fetchModels)   # handwriting model (Google ML Kit's download) and the TFLite Web runtime
(cd app && ./gradlew :recognition:fetchOcr2Models)   # OCR models of the Android and iOS apps (16 MB; downloads 31 MB from ModelScope, needs uv)
app/tools/fetch-voice-model.sh       # sherpa-onnx library + voice model (~280 MB)
(cd app && uv run --no-project --with fonttools --with cffsubr tools/stage-app-fonts.py)   # iOS/Android interface font with rare characters (below)
```

`opencc-kmp` is resolved from `../opencc-kmp/.cache/maven`.

## Build and test

```bash
cd app
./gradlew :core:jvmTest :composeApp:testDebugUnitTest :recognition:testDebugUnitTest
./gradlew :composeApp:assembleDebug      # or assembleRelease (R8, debug-signed)
```

- **OCR on Android and iOS.** `recognition/tools/fetch_ocr2_models.sh` restores `det.onnx`,
  `cls.onnx`, `rec.onnx` and `rec-labels.txt` into `recognition/src/androidMain/assets/models/ocr2/`
  (pinned URLs and SHA-256; the recogniser is repacked with float16 weight storage, byte-identical
  to the evaluated file). Android packages them as assets and runs them with
  `com.microsoft.onnxruntime:onnxruntime-android` 1.23.2 (19 MB of native code, arm64); iOS gets them
  through `stageIosAssets` and runs them on the onnxruntime xcframework that `speech` links. The
  TensorFlow Lite OCR graphs in `assets/models/ocr/` are the Web build's and are left out of the
  Android package and the iOS bundle. Without the models the apps build with a warning and OCR fails
  with an error. `RECOGNITION_OCR2_IMAGES=<dir of png> RECOGNITION_OCR2_OUT=<dir> ./gradlew
  :recognition:testDebugUnitTest --tests '*Ocr2BenchmarkRun*'` runs the pipeline on the host and
  writes results that `ocr-baseline/tools/score.py` scores.
- **Rare characters on Android.** Android's system CJK font (AOSP's Noto Sans CJK) has no glyph for
  2,435 of the 25,451 characters in the datasets, packs and interface text (the same set as the Web
  rare tier; `gen3` → 𫖱), and the system fallback never reaches a font the app brings, so — as on
  iOS — the glyphs are added to the interface font: `tools/stage-app-fonts.py` also writes
  `composeApp/build/android/fonts/sans-<weight>.otf` (Source Sans 3 + those characters from
  Plangothic, renamed "SZDict Sans", 1.07 MB each). `stageAndroidFonts` (on `preBuild`) packages
  them as the assets `fonts/` of every variant, stored uncompressed (`noCompress` "otf": Compose maps
  a weight in under a millisecond instead of inflating it); `AndroidAppFonts.kt` sets them as the
  interface family in `MainActivity` and the widget configuration. Without them the build warns and
  the app uses the Source Sans resources (rare characters as boxes). Release APK +4.8 MB. The
  recording `tools/android-system-cjk.txt` is Noto Sans CJK's coverage
  (`tools/stage-app-fonts.py --record-android NotoSansCJK-Regular.ttc`, pulled from an emulator's
  `/system/fonts`): only characters Noto lacks are added, so every other hanzi still comes from the
  system font, and phones with other system fonts get the same conservative set.

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
  The language model (`<mode>-lm.db`: 14 MB for Mandarin with the combination model, 4.9 MB for
  Cantonese) is fetched when a query first needs it; the search runs
  again when it arrives.
- **Service worker** (`sw.js`, distribution only). The distribution task writes the build's
  version and shell file list into it. It keeps the shell (start page, code, WebAssembly, workers,
  SQLite, the privacy page) for offline starts, answers every route with the start page, and
  keeps lazily fetched audio, OpenCC dictionaries, fonts and stroke graphics as they pass through.
  A new build installs beside the running one and takes over on the next load. The development
  server runs without it.
- **Latin font.** English, Pinyin and Jyutping are set in Source Sans 3 (SIL OFL 1.1) on every
  platform: the unmodified static OTFs of release 3.052R for weights 400/500/600/700 are Compose
  resources (`composeApp/src/commonMain/composeResources/font/`, 1.35 MB, with the licence under
  `files/`; `ui/theme/AppTypography.kt`). Chinese falls back per glyph to the platform's CJK font
  (system font on Android, PingFang on iOS — on both with rare characters added to the font, see Build and test and iOS below — the tiers below on Web). On Web they are part of the
  offline shell; `index.html` preloads them beside the code and the first frame waits for them,
  so text never appears in another face first (1.35 MB, 0.62 MB with Brotli).
- **CJK font.** Without preparation the app fetches the complete Noto Sans CJK (16 MB) of the glyph
  variant in use from the font host. `uv run --no-project --with fonttools tools/stage-web-fonts.py`
  cuts the SC, TC and HK variants into frequency tiers under `build/web/static/cjk/fonts` — about
  1.5 MB for the first, which also holds every character of the interface text. The
  distribution includes them and the app fetches them in order; `index.html` requests the first
  tier with the page (beside the code and the dataset), so hanzi appear with the first frames.
  The option
  `--full` stages the complete fonts for a deployment that must not ask the font host. The
  same run cuts the rare tier: the 2,432 characters of the datasets and packs that Noto Sans CJK
  lacks (mostly CJK Extensions B–J), from Plangothic P1 and P2 (SIL OFL 1.1; subsets renamed
  "SZDict Rare Han", as the licence's reserved name requires) into `rare-1.ttf` (0.51 MB) and
  `rare-2.ttf` (0.19 MB). A rare file is fetched only once the dictionary has handed the interface
  one of its characters (`ShownCharacters.kt`). Source fonts are downloaded from pinned URLs into
  `build/web/font-cache` and checked against pinned SHA-256s; `fonts/index.json` lists every staged
  file's SHA-256, which the app verifies before using it.
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

## Release and deploy

Every push to `main` runs `.github/workflows/release.yml`:

1. **Version.** The next `YYYY.MM.DD-X` for today's UTC date (`REL-001`); `versionCode` is
   `YYYYMMDD × 100 + X` (`REL-002`). Gradle reads `-PappVersionName` / `-PappVersionCode`, and falls back
   to the literal in `composeApp/build.gradle.kts` for local builds.
2. **Inputs that are not in git.**
   - `ci/publish-deps.sh` checks out the stroke-order animator and opencc-kmp at the commits in
     `ci/deps.lock` and publishes them. Bump a SHA there after pushing a library change.
   - `ci/fetch-build-inputs.sh` restores `pipeline/out` from the snapshot pinned in
     `ci/build-inputs.lock` and runs `recognition/tools/fetch_models.sh`. The handwriting model comes
     from Google ML Kit's own download URL; the TFLite Web runtime comes from npm.
   - **To ship new data:** rebuild the dataset, run `ci/publish-build-inputs.sh` (needs `gh auth login`),
     and commit the rewritten lock file.
3. **Builds and tests.** The Web distribution, the release APK and the unit tests run in parallel.
   The APK is signed with the Play upload key of `engineering.carr.szdict` (repository secrets
   `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`).
   Without those variables, local release builds stay debug-signed.
4. **Deploy.** The precompressed distribution, plus a `version.json`, is uploaded over SSH
   (`DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS`) to `szdict-deploy@45.115.38.102`:
   - It goes into `/var/www/szdict/releases/<version>-<sha>/`, with unchanged files hard-linked from
     the live release.
   - It is then activated by atomically repointing `/var/www/szdict/current`. The last 5 releases are kept.
   - The key may only run the server's forced command `/usr/local/bin/szdict-deploy`, which allows
     `current`, an rsync upload into `releases/` and `activate <release>`.
   - Caddy (`/etc/caddy/Caddyfile`) serves the hosting rules above, with HTTPS from Let's Encrypt and no
     access log. It speaks HTTP/1.1 and HTTP/2 only (global option `servers { protocols h1 h2 }`): over
     HTTP/3 (QUIC) browsers in mainland China fetched the site at ~15 KB/s against ~8 MB/s over TCP.
   - `ci/verify-site.sh` then checks the live site against the upload.
5. **Release.** Only after a successful deploy: the annotated tag and a GitHub Release with
   `szdict-<version>.apk`, `szdict-web-<version>.tar.gz` and `SHA256SUMS`.

**Roll back** by reactivating an earlier release with the deploy key:
`ssh -i <deploy key> szdict-deploy@45.115.38.102 activate <version>-<sha>`.
Alternatively, as root, run `ln -sfn releases/<name> /var/www/szdict/current.tmp && mv -T /var/www/szdict/current.tmp /var/www/szdict/current` in `/var/www/szdict`.

## iOS

Needs Xcode 26 or later with an iOS simulator runtime (licence accepted: `sudo xcodebuild -license`),
and the one-time setup above — `publish-animator.sh` publishes the iOS variants of the stroke
library, `fetch-voice-model.sh` fetches the voice model and the sherpa-onnx / onnxruntime
xcframeworks into `speech/ios-frameworks/` (OCR runs on that onnxruntime too, so the iOS build
needs it: `VOICE_TARGETS=ios app/tools/fetch-voice-model.sh`), and `./gradlew
:recognition:fetchIosFrameworks` the TensorFlow Lite C runtime into `recognition/ios-frameworks/`.
No CocoaPods, no Swift packages.

```bash
cd app
./gradlew :composeApp:compileKotlinIosSimulatorArm64 :composeApp:compileKotlinIosArm64   # Kotlin only
xcrun simctl list devices available        # pick a simulator; `xcrun simctl boot <udid>`
xcodebuild -project iosApp/SZDict.xcodeproj -scheme SZDict -configuration Debug -sdk iphonesimulator \
  -destination 'id=<udid>' -derivedDataPath iosApp/build CODE_SIGNING_ALLOWED=NO build
xcrun simctl install <udid> iosApp/build/Build/Products/Debug-iphonesimulator/SZDict.app
xcrun simctl launch <udid> com.szdict.app
xcrun simctl openurl <udid> 'szdict://zh/word/…'      # any route of the route table, as under https://szdict.com/
```

Or open `iosApp/SZDict.xcodeproj` in Xcode and run the `SZDict` scheme (Apple-silicon simulators;
a device needs a signing team, and the Associated Domains entitlement for Universal Links).

- **Project.** `iosApp/SZDict.xcodeproj` is written by hand. Its first build phase
  (`iosApp/build-kotlin.sh`) runs `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`, which
  builds the static `ComposeApp` framework and stages the bundle assets. Identity, version and
  linker settings are in `iosApp/Config.xcconfig`; `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`
  mirror `appVersionName` in `composeApp/build.gradle.kts`.
- **Native libraries.** The Kotlin code reaches TensorFlow Lite, sherpa-onnx and onnxruntime through
  cinterop; they are static xcframeworks that the app target links. The build phase lists every
  `*.xcframework` under `recognition/ios-frameworks/` and `speech/ios-frameworks/` into a linker
  response file, so adding one needs no project change (a system framework it depends on goes into
  `OTHER_LDFLAGS` in `Config.xcconfig`).
- **Assets.** `:composeApp:stageIosAssets` copies the datasets, packs and recorded audio, the
  OpenCC dictionaries, the stroke data, the recognition models and — when fetched — the voice model
  into `composeApp/build/ios/assets` (about 440 MB); Xcode copies that folder into the app as
  `assets/`. Everything is opened in place and read-only (`IosBundle.kt`); nothing is copied at
  first launch. AVAudioPlayer does not play the pack's Ogg container, so each clip's Opus packets are rewrapped as CAF in memory when it is played (`OggOpusCaf.kt`; nothing is transcoded).
- **Fonts.** Hanzi come from the system cascade (PingFang), which has no glyph for 2,301 of the
  25,451 characters in the datasets and packs (rare ideographs of CJK Extensions B–J and the like;
  `gen3` → 𫖱). Skia's per-glyph fallback on iOS asks only CoreText, and CoreText's cascade never
  offers fonts an app registers or hands to Compose (it answers with LastResort's "?" box), so the
  glyphs go into the interface font itself:
  `uv run --no-project --with fonttools --with cffsubr tools/stage-app-fonts.py` writes
  `composeApp/build/ios/fonts/sans-{regular,medium,semibold,bold}.otf` — the Source Sans 3
  resources with those characters added from Plangothic P1/P2 (pinned URLs and SHA-256 shared with
  the Web tool, cache in `build/web/font-cache`), renamed "SZDict Sans" as both licences' reserved
  names require; 1.0 MB each, 2.8 MB more than the resources. `stageIosAssets` bundles them as
  `assets/fonts/` and `IosAppFonts.kt` sets them as the interface family (`LocalPlatformFontFamily`);
  only characters the system lacks are added, so every other hanzi still comes from PingFang.
  Without them the build warns and the app uses the Source Sans resources (rare characters as
  boxes). Which characters the system lacks is recorded in `tools/ios-system-cjk.txt` (the code
  points the iOS 26 cascade draws); after an iOS update, re-record it in a simulator with
  `tools/ios-font-probe.swift` (commands in the file). Characters missing from the recording are
  added, so new dataset characters are never left without a glyph.
- **Icon.** `./gradlew :composeApp:generateIosAppIcon` redraws
  `iosApp/SZDict/Assets.xcassets/AppIcon.appiconset/icon-1024.png` from the Android launcher
  vectors; run it when they change (the PNG is committed). Likewise
  `./gradlew :composeApp:generateAppLogo` redraws the shared UI's logo (`ui/kit/AppLogo.kt`, an
  `ImageVector` shown in the expanded rail header).
- **Name.** "SZ Dict" where space is tight (home screen), "Shenzhen Dictionary" where there is room;
  深圳词典 / 深圳詞典 always in Chinese (`AppName.kt`, mirrored in `InfoPlist.strings`, the Android
  `app_name` strings and `index.html`; the PWA manifest reads it from `AppName.kt`).
- **Entry points.** `MainViewController.kt` builds the session and exposes what the Swift host
  (`iosApp/SZDict/SZDictApp.swift`) forwards: incoming URLs (`szdict:` scheme and Universal Links,
  through `IncomingRouter`), Home Screen quick actions, and Apple's translation sheet.
