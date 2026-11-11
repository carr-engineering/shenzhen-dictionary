#!/bin/sh
# Publishes the Android + JVM + Kotlin Multiplatform root publications of shenzhen-hanzi-animator
# 0.1.0 into app/local-maven/ (gitignored; declared as a repository in app/settings.gradle.kts).
#
# The animator build already supports an extra publishing repository through the Gradle property
# publicationTestRepositoryUrl (repository name "IntegrationSmoke"), so no tracked file of that
# repository is touched. iOS / JS / Wasm publications are not built (no Xcode needed).
#
# Usage: app/tools/publish-animator.sh [path-to-shenzhen_hanzi_animator_kmp]
set -eu
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ANIMATOR_DIR="${1:-$APP_DIR/../../shenzhen_hanzi_animator_kmp}"
REPO="$APP_DIR/local-maven"
mkdir -p "$REPO"

TASKS=""
for module in core codec data bundled-data library material3; do
  for publication in Android Jvm KotlinMultiplatform; do
    TASKS="$TASKS :$module:publish${publication}PublicationToIntegrationSmokeRepository"
  done
done

cd "$ANIMATOR_DIR"
# shellcheck disable=SC2086
./gradlew $TASKS "-PpublicationTestRepositoryUrl=file://$REPO" --no-configuration-cache
echo "Published engineering.carr:shenzhen-hanzi-animator-* 0.1.0 to $REPO"
