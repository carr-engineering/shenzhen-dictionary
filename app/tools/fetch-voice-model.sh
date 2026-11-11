#!/usr/bin/env bash
# Fetches the Voice transcription native dependency and model (ARCH-095, Q-101). Nothing fetched here
# is committed: the files are large binaries, gitignored inside app/voice.
#
#   1. sherpa-onnx Android AAR (static onnxruntime) -> app/voice/libs/sherpa-onnx-classes.jar
#                                                    + app/voice/src/main/jniLibs/arm64-v8a/*.so
#   2. streaming Paraformer trilingual (zh / yue / en), int8
#                                                   -> app/voice/src/main/assets/voice-model/
#
# Downloads resume (curl -C -) and are verified by SHA-256, so the script is safe to re-run.
set -euo pipefail

SHERPA_VERSION="2.0.1"
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-static-link-onnxruntime-${SHERPA_VERSION}.aar"
MODEL_BASE="${VOICE_MODEL_BASE:-https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-trilingual-zh-cantonese-en/resolve/main}"
ABIS="${VOICE_ABIS:-arm64-v8a}"

HERE="$(cd "$(dirname "$0")" && pwd)"
VOICE="$HERE/../voice"
CACHE="$VOICE/.cache"
LIBS="$VOICE/libs"
JNI="$VOICE/src/main/jniLibs"
MODEL="$VOICE/src/main/assets/voice-model"
TEST_ASSETS="$VOICE/src/androidTest/assets/voice-test"
mkdir -p "$CACHE" "$LIBS" "$JNI" "$MODEL" "$TEST_ASSETS"

sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }

# fetch <url> <destination> [sha256]
fetch() {
  local url="$1" dest="$2" want="${3:-}"
  if [[ -f "$dest" && -n "$want" && "$(sha256 "$dest")" == "$want" ]]; then
    echo "ok       $(basename "$dest")"
    return
  fi
  if [[ -f "$dest" && -z "$want" ]]; then
    echo "present  $(basename "$dest")"
    return
  fi
  echo "fetching $(basename "$dest")"
  curl -fL --retry 20 --retry-delay 5 --retry-all-errors -C - -o "$dest.part" "$url"
  if [[ -n "$want" && "$(sha256 "$dest.part")" != "$want" ]]; then
    echo "checksum mismatch for $(basename "$dest")" >&2
    rm -f "$dest.part"
    exit 1
  fi
  mv "$dest.part" "$dest"
}

# 1. Library.
AAR="$CACHE/sherpa-onnx-static-link-onnxruntime-${SHERPA_VERSION}.aar"
fetch "$AAR_URL" "$AAR"
UNPACKED="$CACHE/aar-${SHERPA_VERSION}"
rm -rf "$UNPACKED" && mkdir -p "$UNPACKED"
unzip -q -o "$AAR" -d "$UNPACKED"
cp "$UNPACKED/classes.jar" "$LIBS/sherpa-onnx-classes.jar"
for abi in $ABIS; do
  mkdir -p "$JNI/$abi"
  cp "$UNPACKED/jni/$abi/"*.so "$JNI/$abi/"
done
rm -rf "$UNPACKED"

# 2. Model (small files first, so a partial run already has the vocabulary).
fetch "$MODEL_BASE/tokens.txt" "$MODEL/tokens.txt"
fetch "$MODEL_BASE/decoder.int8.onnx" "$MODEL/decoder.int8.onnx" \
  545427acf508452b7d89969be082c8128c681e3432ff43aef09f6159f4b61a7e
fetch "$MODEL_BASE/encoder.int8.onnx" "$MODEL/encoder.int8.onnx" \
  6047a644b41b236d9d8e89e3b94ef39d1b7037daab028131b722ca52e10b0357

# 3. Short 16 kHz test recordings for the instrumented recogniser test (not packaged in the app).
for wav in 1.wav 2.wav 6-zh-en.wav; do
  fetch "$MODEL_BASE/test_wavs/$wav" "$TEST_ASSETS/$wav" || true
done

echo "done: $(du -sh "$MODEL" | cut -f1) model, library ${SHERPA_VERSION} for: $ABIS"
