#!/bin/sh
# Xcode build phase "Build Kotlin framework": builds the shared Compose framework and stages the
# bundle assets with Gradle, then lists the static xcframeworks the app must link (see Config.xcconfig).
set -eu

if [ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-NO}" != "YES" ]; then
  cd "$SRCROOT/.."
  ./gradlew :composeApp:embedAndSignAppleFrameworkForXcode --console=plain
fi

case "$PLATFORM_NAME" in
  iphonesimulator) pattern="ios-*-simulator" ;;
  *) pattern="ios-arm64" ;;
esac
mkdir -p "$(dirname "$SZDICT_LINK_ARGS")"
: > "$SZDICT_LINK_ARGS.tmp"
for dir in $SZDICT_XCFRAMEWORK_DIRS; do
  for xcframework in "$dir"/*.xcframework; do
    [ -d "$xcframework" ] || continue
    for slice in "$xcframework"/$pattern; do
      [ -d "$slice" ] || continue
      for framework in "$slice"/*.framework; do
        [ -d "$framework" ] || continue
        name="$(basename "$framework" .framework)"
        printf '"-F%s" -framework %s\n' "$slice" "$name" >> "$SZDICT_LINK_ARGS.tmp"
      done
    done
  done
done
# Rewritten only when it changed, so an unchanged list does not relink the app.
if ! cmp -s "$SZDICT_LINK_ARGS.tmp" "$SZDICT_LINK_ARGS" 2>/dev/null; then mv "$SZDICT_LINK_ARGS.tmp" "$SZDICT_LINK_ARGS"; else rm "$SZDICT_LINK_ARGS.tmp"; fi
