#!/bin/sh
# Uploads the local dataset (pipeline/out) as the snapshot CI builds the apps and szdict.com from.
#
# The pipeline reads local sources that CI does not have, so the dataset ships as a pinned snapshot:
# rebuild it (pipeline/README.md), run this script, and commit the rewritten ci/build-inputs.lock —
# the next release on main then carries the new data. Needs the GitHub CLI (`gh auth login`).
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/pipeline/out"
REPO="carr-engineering/shenzhen-dictionary"
TAG="build-inputs"

VERSION="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["mandarin"]["meta"]["dataset_version"])' "$OUT/build-report.json")"
ASSET="dataset-$(printf '%s' "$VERSION" | tr -c 'A-Za-z0-9._-' '_').tar.gz"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
COPYFILE_DISABLE=1 tar -C "$OUT" --exclude '.DS_Store' -czf "$TMP/$ASSET" .
SHA="$(shasum -a 256 "$TMP/$ASSET" | cut -d' ' -f1)"

if ! gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
  gh release create "$TAG" -R "$REPO" --prerelease --title "Build inputs" --notes \
    "Pinned inputs of the release workflow that are not in git (see ci/build-inputs.lock). Not an app release."
fi
gh release upload "$TAG" "$TMP/$ASSET" -R "$REPO" --clobber

cat > "$ROOT/ci/build-inputs.lock" <<LOCK
# The dataset snapshot the release workflow builds from (ci/fetch-build-inputs.sh). Written by
# ci/publish-build-inputs.sh; commit it to ship the new data with the next release.
BUILD_INPUTS_URL=https://github.com/$REPO/releases/download/$TAG
DATASET_ASSET=$ASSET
DATASET_SHA256=$SHA
LOCK
echo "Uploaded $ASSET ($SHA); ci/build-inputs.lock updated: commit it."
