# Shenzhen Dictionary — app

Kotlin Multiplatform + Compose Multiplatform. Android is the only target built and tested so far.

| Module | Contents |
|--------|----------|
| `core` | shared engine: store, search, segmentation, lattice decoder, domain facade (`com.szdict.core.api`) |
| `composeApp` | UI, state holders, Android platform layer, widget, shortcuts, share targets |
| `recognition` | OCR with per-character alternatives and handwriting, run from the extracted graphs with LiteRT |
| `voice` | sherpa-onnx streaming Paraformer wrapper |

## One-time setup (large files are not in git)

```bash
uv run pipeline/fetch.py && uv run pipeline/packs/build_packs.py && uv run pipeline/build.py   # datasets + packs -> pipeline/out
app/tools/publish-animator.sh        # stroke-order library -> app/local-maven (needs ../shenzhen_hanzi_animator_kmp)
(cd app && ./gradlew :recognition:fetchModels)   # handwriting model (needs ../chinese-handwritting)
app/tools/fetch-voice-model.sh       # sherpa-onnx library + voice model (~280 MB)
```

`opencc-kmp` is resolved from `../opencc-kmp/.cache/maven`.

## Build and test

```bash
cd app
./gradlew :core:jvmTest :composeApp:testDebugUnitTest :recognition:testDebugUnitTest
./gradlew :composeApp:assembleDebug      # or assembleRelease (R8, debug-signed)
```
