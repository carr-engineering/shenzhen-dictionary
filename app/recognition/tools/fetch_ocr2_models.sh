#!/bin/sh
# Restores the OCR models of the Android and iOS apps (too large for git) into
# src/androidMain/assets/models/ocr2/:
#
#   det.onnx         PP-OCRv4 mobile text detector                       4.7 MB
#   cls.onnx         PP-OCR mobile v2.0 text-line orientation classifier 0.6 MB
#   rec.onnx         PP-OCRv6 Small recogniser, weights stored as FP16  10.7 MB
#   rec-labels.txt   the recogniser's symbols, one per line
#
# The ONNX files come from the RapidOCR model repository on ModelScope (tag v3.10.0; PaddleOCR
# models, Apache-2.0), pinned by SHA-256. The recogniser is repacked from the pinned FP32 source by
# pack_ocr2_recogniser.py (needs `uv`; pinned package versions make the result byte-identical).
#
#   tools/fetch_ocr2_models.sh       (or ./gradlew :recognition:fetchOcr2Models)
#
# OCR2_MODEL_SOURCE=<dir> takes the three source files from a local directory instead of downloading.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
DST="$HERE/../src/androidMain/assets/models/ocr2"
CACHE="$HERE/../build/ocr2-models"
BASE="https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx"

DET_SHA=d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9
CLS_SHA=e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c
REC_SOURCE_SHA=6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884
REC_SHA=a2683eeed08e747d46011a46b830b1528591b6257a61d66a722a441e62924666
LABELS_SHA=b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d

sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
is() { [ -f "$1" ] && [ "$(sha "$1")" = "$2" ]; }

if is "$DST/det.onnx" $DET_SHA && is "$DST/cls.onnx" $CLS_SHA && is "$DST/rec.onnx" $REC_SHA && is "$DST/rec-labels.txt" $LABELS_SHA; then
  echo "OCR models already in place"
  exit 0
fi

mkdir -p "$DST" "$CACHE"
fetch() { # url-path file sha256
  name="$(basename "$1")"
  if ! is "$CACHE/$name" "$3"; then
    if [ -n "${OCR2_MODEL_SOURCE:-}" ] && [ -f "$OCR2_MODEL_SOURCE/$name" ]; then
      cp "$OCR2_MODEL_SOURCE/$name" "$CACHE/$name"
    else
      curl -fL --retry 3 -o "$CACHE/$name" "$BASE/$1"
    fi
  fi
  is "$CACHE/$name" "$3" || { echo "SHA-256 mismatch for $name: $(sha "$CACHE/$name")" >&2; exit 1; }
}

fetch PP-OCRv4/det/ch_PP-OCRv4_det_mobile.onnx det $DET_SHA
fetch PP-OCRv4/cls/ch_ppocr_mobile_v2.0_cls_mobile.onnx cls $CLS_SHA
fetch PP-OCRv6/rec/PP-OCRv6_rec_small.onnx rec $REC_SOURCE_SHA

cp "$CACHE/ch_PP-OCRv4_det_mobile.onnx" "$DST/det.onnx"
cp "$CACHE/ch_ppocr_mobile_v2.0_cls_mobile.onnx" "$DST/cls.onnx"
command -v uv > /dev/null || { echo "uv is needed to repack the recogniser (https://docs.astral.sh/uv/)" >&2; exit 1; }
uv run --no-project --python 3.12 --with onnx==1.23.2 --with numpy==2.5.4 --with protobuf==7.36.2 \
  "$HERE/pack_ocr2_recogniser.py" "$CACHE/PP-OCRv6_rec_small.onnx" "$DST/rec.onnx" "$DST/rec-labels.txt"
is "$DST/rec.onnx" $REC_SHA || { echo "The repacked recogniser differs from the evaluated one: $(sha "$DST/rec.onnx")" >&2; exit 1; }
is "$DST/rec-labels.txt" $LABELS_SHA || { echo "Unexpected label table: $(sha "$DST/rec-labels.txt")" >&2; exit 1; }
echo "OCR models restored"
