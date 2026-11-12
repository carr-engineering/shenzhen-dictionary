#!/usr/bin/env bash
# Fetches the Voice transcription native dependency and model (ARCH-095, Q-101). Nothing fetched here
# is committed: the files are large binaries, gitignored inside app/voice and app/speech.
#
#   1. sherpa-onnx Android AAR (static onnxruntime) -> app/voice/libs/sherpa-onnx-classes.jar
#                                                    + app/voice/src/main/jniLibs/arm64-v8a/*.so
#   2. streaming Paraformer trilingual (zh / yue / en), int8
#                                                   -> app/voice/src/main/assets/voice-model/
#   3. iOS: sherpa-onnx and onnxruntime static xcframeworks (device + simulator)
#                                                   -> app/speech/ios-frameworks/
#
# VOICE_TARGETS selects the libraries to fetch ("android ios" by default); the model is always fetched.
#
# Downloads resume (curl -C -) and are verified by SHA-256, so the script is safe to re-run.
set -euo pipefail

SHERPA_VERSION="2.0.1"
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-static-link-onnxruntime-${SHERPA_VERSION}.aar"
MODEL_BASE="${VOICE_MODEL_BASE:-https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-trilingual-zh-cantonese-en/resolve/main}"
ABIS="${VOICE_ABIS:-arm64-v8a}"
TARGETS="${VOICE_TARGETS:-android ios}"
# iOS: the same sherpa-onnx release, built against this onnxruntime (sherpa-onnx build-ios.sh).
IOS_SHERPA_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/xcframework/sherpa-onnx-v${SHERPA_VERSION}-ios-static.xcframework.zip"
IOS_SHERPA_SHA256="88aa569f71b7903e0bbdfbe6e2e114b3b95862b1ef0759eea3024f82926d7374"
IOS_ORT_VERSION="1.28.2"
IOS_ORT_URL="https://github.com/csukuangfj/onnxruntime-libs/releases/download/v${IOS_ORT_VERSION}/onnxruntime-ios-static-xcframework-${IOS_ORT_VERSION}.xcframework.zip"
IOS_ORT_SHA256="2c2299acbb461d26d4bac4bc85985d40e7c7177ed6072703ae0846d88b0b4599"

HERE="$(cd "$(dirname "$0")" && pwd)"
VOICE="$HERE/../voice"
CACHE="$VOICE/.cache"
LIBS="$VOICE/libs"
JNI="$VOICE/src/main/jniLibs"
MODEL="$VOICE/src/main/assets/voice-model"
TEST_ASSETS="$VOICE/src/androidTest/assets/voice-test"
IOS_FRAMEWORKS="$HERE/../speech/ios-frameworks"
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

# 1. Android library.
if [[ " $TARGETS " == *" android "* ]]; then
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
fi

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

# 4. iOS libraries: static xcframeworks, linked by the app target and by :speech's cinterop.
# fetch_xcframework <url> <sha256> <name>.xcframework <version stamp>
fetch_xcframework() {
  local url="$1" want="$2" name="$3" stamp="$4"
  if [[ -d "$IOS_FRAMEWORKS/$name" && "$(cat "$IOS_FRAMEWORKS/$name.version" 2>/dev/null)" == "$stamp" ]]; then
    echo "ok       $name"
    return
  fi
  local zip="$CACHE/$name.zip"
  fetch "$url" "$zip" "$want"
  rm -rf "$IOS_FRAMEWORKS/$name"
  unzip -q -o "$zip" -d "$IOS_FRAMEWORKS"
  [[ -d "$IOS_FRAMEWORKS/$name" ]] || { echo "$name not found in $(basename "$zip")" >&2; exit 1; }
  echo "$stamp" > "$IOS_FRAMEWORKS/$name.version"
  rm -f "$zip"
}
if [[ " $TARGETS " == *" ios "* ]]; then
  mkdir -p "$IOS_FRAMEWORKS"
  fetch_xcframework "$IOS_SHERPA_URL" "$IOS_SHERPA_SHA256" sherpa-onnx.xcframework "$IOS_SHERPA_SHA256"
  fetch_xcframework "$IOS_ORT_URL" "$IOS_ORT_SHA256" onnxruntime.xcframework "$IOS_ORT_SHA256"
fi

echo "done: $(du -sh "$MODEL" | cut -f1) model, library ${SHERPA_VERSION} for: $TARGETS ($ABIS)"
