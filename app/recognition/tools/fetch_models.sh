#!/bin/sh
# Restores the recognition model assets from the local reverse-engineering workspace.
#
#   tools/fetch_models.sh            copy the ink model (too large for git) into assets, and the
#                                    TFLite Web runtime for the browser build into web-runtime/
#   tools/fetch_models.sh --all      also regenerate the small OCR graphs and label tables that are
#                                    committed (needs Python 3.12 with the workspace's LiteRT deps)
#
# Source workspace: $CHINESE_HANDWRITING_DIR (default /Users/marc/Documents/code/chinese-handwritting).
set -eu
SRC="${CHINESE_HANDWRITING_DIR:-/Users/marc/Documents/code/chinese-handwritting}"
HERE="$(cd "$(dirname "$0")" && pwd)"
DST="$HERE/../src/androidMain/assets/models"
mkdir -p "$DST/ocr" "$DST/ink"

check() { # file sha256
  actual="$(shasum -a 256 "$1" | cut -d' ' -f1)"
  [ "$actual" = "$2" ] || { echo "SHA-256 mismatch for $1: $actual" >&2; exit 1; }
}

cp "$SRC/native-ink/models/chinese_lstm_4x192.tflite" "$DST/ink/chinese_lstm_4x192.tflite"
check "$DST/ink/chinese_lstm_4x192.tflite" b14fd839784b72c4872b0e923815baf1b42734326059145a63ef338a6d866f65
echo "ink model restored"

# The Web build runs the same graphs with the TFLite Web runtime (SIMD build) of
# @tensorflow/tfjs-tflite 0.0.1-alpha.10: from the workspace's vendored copy, or from the npm CDN.
WEB="$HERE/../web-runtime"
mkdir -p "$WEB"
for name in tflite_web_api_cc_simd.js tflite_web_api_cc_simd.wasm; do
  if [ -f "$SRC/web-ocr/vendor/$name" ]; then
    cp "$SRC/web-ocr/vendor/$name" "$WEB/$name"
  else
    curl -fsSL -o "$WEB/$name" "https://cdn.jsdelivr.net/npm/@tensorflow/tfjs-tflite@0.0.1-alpha.10/wasm/$name"
  fi
done
check "$WEB/tflite_web_api_cc_simd.js" d5c71816525140d7a35b59a908d3b07d42741576630e647d246c19c6fa72d63d
check "$WEB/tflite_web_api_cc_simd.wasm" 40f49cc2d458ec09bb43db160e9e55f4b3c790a4c1d1fa280f8a61796296cf8e
echo "web runtime restored"

if [ "${1:-}" = "--all" ]; then
  PY="${PYTHON:-/opt/homebrew/bin/python3.12}"
  for name in detector script-encoder script-decoder hani-encoder latn-encoder; do
    cp "$SRC/web-ocr/models/$name.tflite" "$DST/ocr/$name.tflite"
  done
  # The recogniser decoders are repackaged so their pre-softmax logits are a graph output.
  for name in hani latn; do
    PYTHONPATH="$SRC/web-ocr/research/python" "$PY" "$HERE/repack_decoder.py" "$SRC/web-ocr/models/$name-decoder.tflite" "$DST/ocr/$name-decoder.tflite"
  done
  "$PY" - "$SRC" "$DST" <<'PY'
import json, sys
src, dst = sys.argv[1:3]
for source, target in [("web-ocr/models/hani-labels.json", "ocr/hani-labels.txt"),
                       ("web-ocr/models/latn-labels.json", "ocr/latn-labels.txt"),
                       ("native-ink/models/labels.json", "ink/labels.txt")]:
    labels = json.load(open(f"{src}/{source}", encoding="utf-8"))
    assert not any("\n" in label for label in labels)
    open(f"{dst}/{target}", "w", encoding="utf-8", newline="").write("\n".join(labels) + "\n")
PY
  echo "OCR graphs and label tables regenerated"
fi
