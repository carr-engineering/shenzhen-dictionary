#!/bin/sh
# Restore only the WASM backend of ONNX Runtime Web 1.31.1 (MIT), about 15 MB.
# Used by the browser's PP-OCR pipeline; handwriting keeps its TFLite runtime.
# ORT_WEB_SOURCE=<directory of the three runtime files> avoids downloading the npm archive.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
DST="$HERE/../web-ort-runtime"
VERSION=1.31.1
ARCHIVE_SHA512=e46787f9b590f87c4cd74cfb7ab1a5b16aabcabc0c6d2be6ff5b6422c2934f8f20d1f235f555d5542e37e6de2ded5b0f75241e4bb366b993d7ad0ef793cd9447
JS_SHA=b8a78c408c40da65fdc8ba0846f7b2948e92a4c6d6b23cc8e4165e428ad95443
MJS_SHA=30ed0b5cd1182ede931fea448afb567d4dc705ec957af09f108a2be5f770dac3
WASM_SHA=4b884c203b0fa48d057be3332c65513ff85aa3c5bddd6946acc0fab7ecda001d
LICENSE_SHA=2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c
sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
is() { [ -f "$1" ] && [ "$(sha "$1")" = "$2" ]; }
if is "$DST/ort.wasm.min.js" "$JS_SHA" && is "$DST/ort-wasm-simd-threaded.mjs" "$MJS_SHA" && \
   is "$DST/ort-wasm-simd-threaded.wasm" "$WASM_SHA" && is "$DST/LICENSE" "$LICENSE_SHA"; then
    echo "ONNX Runtime Web $VERSION already in place"
    exit 0
fi
runtime_tmp=$(mktemp -d "${TMPDIR:-/tmp}/szdict-ort-web.XXXXXX")
trap 'rm -rf "$runtime_tmp"' EXIT HUP INT TERM
if [ -n "${ORT_WEB_SOURCE:-}" ]; then
    runtime_src=$ORT_WEB_SOURCE
else
    curl -fL --retry 3 "https://registry.npmjs.org/onnxruntime-web/-/onnxruntime-web-$VERSION.tgz" -o "$runtime_tmp/package.tgz"
    actual=$(shasum -a 512 "$runtime_tmp/package.tgz" | cut -d' ' -f1)
    [ "$actual" = "$ARCHIVE_SHA512" ] || { echo "ONNX Runtime archive SHA-512 mismatch" >&2; exit 1; }
    mkdir "$runtime_tmp/unpacked"
    tar -xzf "$runtime_tmp/package.tgz" -C "$runtime_tmp/unpacked" \
        package/dist/ort.wasm.min.js package/dist/ort-wasm-simd-threaded.mjs package/dist/ort-wasm-simd-threaded.wasm
    runtime_src="$runtime_tmp/unpacked/package/dist"
fi
is "$runtime_src/ort.wasm.min.js" "$JS_SHA" && is "$runtime_src/ort-wasm-simd-threaded.mjs" "$MJS_SHA" && \
is "$runtime_src/ort-wasm-simd-threaded.wasm" "$WASM_SHA" || { echo "ONNX Runtime file SHA-256 mismatch" >&2; exit 1; }
# This npm archive omits the license, and npm's version need not match an upstream Git tag.
license="$HERE/ONNXRUNTIME-LICENSE"
is "$license" "$LICENSE_SHA" || { echo "ONNX Runtime license SHA-256 mismatch" >&2; exit 1; }
mkdir -p "$DST"
for name in ort.wasm.min.js ort-wasm-simd-threaded.mjs ort-wasm-simd-threaded.wasm; do cp "$runtime_src/$name" "$DST/$name"; done
cp "$license" "$DST/LICENSE"
echo "ONNX Runtime Web $VERSION restored"
