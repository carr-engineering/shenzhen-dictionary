#!/bin/sh
# Publishes the Android + JVM + Wasm (browser) + Kotlin Multiplatform root publications of
# shenzhen-hanzi-animator 0.1.0 into app/local-maven/ (gitignored; declared as a repository in app/settings.gradle.kts).
#
# The animator build already supports an extra publishing repository through the Gradle property
# publicationTestRepositoryUrl (repository name "IntegrationSmoke"), so no tracked file of that
# repository is touched. iOS / JS publications are not built (no Xcode needed).
#
# Usage: app/tools/publish-animator.sh [path-to-shenzhen_hanzi_animator_kmp]
set -eu
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ANIMATOR_DIR="${1:-$APP_DIR/../../shenzhen_hanzi_animator_kmp}"
REPO="$APP_DIR/local-maven"
mkdir -p "$REPO"

TASKS=""
for module in core codec data bundled-data library material3; do
  for publication in Android Jvm WasmJs KotlinMultiplatform; do
    TASKS="$TASKS :$module:publish${publication}PublicationToIntegrationSmokeRepository"
  done
done

# The browser stroke resources are reachable through two source sets of the Wasm target; an init
# script (not a change to the animator repository) lets the resource copy keep one of each.
INIT="$(mktemp -t publish-animator-init).gradle"
trap 'rm -f "$INIT"' EXIT
cat > "$INIT" <<'GRADLE'
allprojects {
    tasks.withType(org.gradle.language.jvm.tasks.ProcessResources).configureEach {
        duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    }
}
GRADLE

cd "$ANIMATOR_DIR"
# shellcheck disable=SC2086
./gradlew -I "$INIT" $TASKS "-PpublicationTestRepositoryUrl=file://$REPO" --no-configuration-cache
echo "Published engineering.carr:shenzhen-hanzi-animator-* 0.1.0 to $REPO"
