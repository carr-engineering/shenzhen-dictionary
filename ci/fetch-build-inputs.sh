#!/bin/sh
# Restores the build inputs that are not in git, for a build on a machine without the local
# pipeline sources:
#
#   pipeline/out      the dataset snapshot pinned in ci/build-inputs.lock (a `build-inputs` release
#                     asset; refresh it with ci/publish-build-inputs.sh)
#   recognition       the handwriting model (from Google ML Kit's download URL) and the TFLite Web
#                     runtime (from npm), via app/recognition/tools/fetch_models.sh
#
# Every download is checked against SHA-256.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=build-inputs.lock
. "$ROOT/ci/build-inputs.lock"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fsSL --retry 3 -o "$TMP/$DATASET_ASSET" "$BUILD_INPUTS_URL/$DATASET_ASSET"
actual="$(shasum -a 256 "$TMP/$DATASET_ASSET" | cut -d' ' -f1)"
[ "$actual" = "$DATASET_SHA256" ] || { echo "SHA-256 mismatch for $DATASET_ASSET: $actual" >&2; exit 1; }
rm -rf "$ROOT/pipeline/out"
mkdir -p "$ROOT/pipeline/out"
tar -xzf "$TMP/$DATASET_ASSET" -C "$ROOT/pipeline/out"
echo "dataset restored: $DATASET_ASSET"

sh "$ROOT/app/recognition/tools/fetch_models.sh"
