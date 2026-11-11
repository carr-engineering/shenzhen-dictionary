#!/bin/sh
# Restores the recognition model assets from the local reverse-engineering workspace.
#
#   tools/fetch_models.sh            copy the ink model (too large for git) into assets
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
