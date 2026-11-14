#!/bin/sh
# Publishes the Android + JVM + Wasm (browser) + iOS (arm64 device and Apple-silicon simulator) +
# Kotlin Multiplatform root publications of
# shenzhen-hanzi-animator 0.1.0 into app/local-maven/ (gitignored; declared as a repository in app/settings.gradle.kts).
#
# The animator build already supports an extra publishing repository through the Gradle property
# publicationTestRepositoryUrl (repository name "IntegrationSmoke"), so no tracked file of that
# repository is touched. The JS publication is not built. The iOS publications need Xcode (klib
# compilation only); pass ANIMATOR_SKIP_IOS=1 to leave them out on a machine without it.
#
# Usage: app/tools/publish-animator.sh [path-to-shenzhen_hanzi_animator_kmp]
set -eu
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ANIMATOR_DIR="${1:-$APP_DIR/../../shenzhen_hanzi_animator_kmp}"
REPO="$APP_DIR/local-maven"
mkdir -p "$REPO"

PUBLICATIONS="Android Jvm WasmJs KotlinMultiplatform"
if [ "${ANIMATOR_SKIP_IOS:-0}" != "1" ]; then PUBLICATIONS="$PUBLICATIONS IosArm64 IosSimulatorArm64"; fi
TASKS=""
for module in core codec data bundled-data library material3; do
  for publication in $PUBLICATIONS; do
    TASKS="$TASKS :$module:publish${publication}PublicationToIntegrationSmokeRepository"
  done
done

# The browser stroke resources are reachable through two source sets of the Wasm target; an init
# script (not a change to the animator repository) lets the resource copy keep one of each.
INIT_DIR="$(mktemp -d)"
INIT="$INIT_DIR/publish-animator-init.gradle"
trap 'rm -rf "$INIT_DIR"' EXIT
cat > "$INIT" <<'GRADLE'
allprojects {
    tasks.withType(org.gradle.language.jvm.tasks.ProcessResources).configureEach {
        duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    }
}
GRADLE

cd "$ANIMATOR_DIR"
# The bundled stroke packs are generated from a pinned corpus, not committed (the animator's
# tools/DATA_PROVENANCE.md); this is a no-op when they are already current.
if [ -x tools/prepare_bundled_data.sh ]; then tools/prepare_bundled_data.sh; fi
# shellcheck disable=SC2086
./gradlew -I "$INIT" $TASKS "-PpublicationTestRepositoryUrl=file://$REPO" --no-configuration-cache
echo "Published engineering.carr:shenzhen-hanzi-animator-* 0.1.0 to $REPO"
