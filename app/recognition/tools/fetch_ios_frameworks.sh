#!/bin/sh
# Restores the TensorFlow Lite C runtime the iOS build links (ios-frameworks/, not in git):
# the static TensorFlowLiteC.xcframework of the CocoaPods binary release, pinned by URL and SHA-256.
# Run by ./gradlew :recognition:fetchIosFrameworks, which the iOS cinterop tasks depend on.
# Does nothing when the pinned version is already in place.
set -eu
VERSION=2.17.0
URL="https://dl.google.com/tflite-release/ios/prod/tensorflow/lite/release/ios/release/32/20240729-115310/TensorFlowLiteC/2.17.0/0c10b3543e01f547/TensorFlowLiteC-2.17.0.tar.gz"
SHA256=9667b476015f136e5b332ce040e12822c4ac6d5c58947882ddc809cdff0fb99e
HERE="$(cd "$(dirname "$0")" && pwd)"
DST="$HERE/../ios-frameworks"
STAMP="$DST/TensorFlowLiteC.version"

if [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$VERSION $SHA256" ] && [ -d "$DST/TensorFlowLiteC.xcframework" ]; then
  exit 0
fi

mkdir -p "$DST"
TMP="$(mktemp -d "$DST/.fetch.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
curl -fsSL -o "$TMP/TensorFlowLiteC.tar.gz" "$URL"
actual="$(shasum -a 256 "$TMP/TensorFlowLiteC.tar.gz" | cut -d' ' -f1)"
[ "$actual" = "$SHA256" ] || { echo "SHA-256 mismatch for $URL: $actual" >&2; exit 1; }
# Only the core runtime: the CoreML and Metal delegates in the same archive are not used.
tar -xzf "$TMP/TensorFlowLiteC.tar.gz" -C "$TMP" --strip-components=2 "TensorFlowLiteC-$VERSION/Frameworks/TensorFlowLiteC.xcframework"
rm -rf "$DST/TensorFlowLiteC.xcframework"
mv "$TMP/TensorFlowLiteC.xcframework" "$DST/TensorFlowLiteC.xcframework"
echo "$VERSION $SHA256" > "$STAMP"
echo "TensorFlowLiteC $VERSION restored"
