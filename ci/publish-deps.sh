#!/usr/bin/env bash
# Publishes the sibling libraries pinned in ci/deps.lock for a build without the sibling checkouts:
#
#   shenzhen-hanzi-animator-kmp -> app/local-maven            (app/tools/publish-animator.sh)
#   opencc-kmp                  -> .ci/opencc-maven           (pass -Popencc.repository=<that dir>)
#
# Sources are cloned into .ci/src (gitignored). Only the targets the CI builds are published
# (Android, JVM, Wasm); the iOS publications need Xcode and the iOS app is not built by CI.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=deps.lock
. "$ROOT/ci/deps.lock"
SRC="$ROOT/.ci/src"
mkdir -p "$SRC"

checkout() { # url sha dir
  if [ ! -d "$3/.git" ]; then git init -q "$3"; git -C "$3" remote add origin "$1"; fi
  git -C "$3" fetch -q --depth 1 origin "$2"
  git -C "$3" -c advice.detachedHead=false checkout -q --force FETCH_HEAD
  echo "$(basename "$3") at $(git -C "$3" rev-parse HEAD)"
}

checkout "$ANIMATOR_REPO" "$ANIMATOR_SHA" "$SRC/animator"
ANIMATOR_SKIP_IOS=1 "$ROOT/app/tools/publish-animator.sh" "$SRC/animator"

checkout "$OPENCC_REPO" "$OPENCC_SHA" "$SRC/opencc"
(
  cd "$SRC/opencc"
  ./gradlew -Popencc.targets=jvm,android,wasmJs --no-configuration-cache \
    :opencc-core:publishAllPublicationsToConsumerRepository \
    :opencc-detect:publishAllPublicationsToConsumerRepository \
    :opencc-data:publishAllPublicationsToConsumerRepository
)
rm -rf "$ROOT/.ci/opencc-maven"
cp -R "$SRC/opencc/.cache/maven" "$ROOT/.ci/opencc-maven"
echo "Published opencc-kmp to $ROOT/.ci/opencc-maven"
