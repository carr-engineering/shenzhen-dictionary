#!/bin/sh
# Checks a deployed szdict.com against the distribution that was just uploaded:
#
#   ci/verify-site.sh <base-url> <dist-dir>
#
# The live version.json and sw.js must be the uploaded ones, and the hosting rules of app/README
# ("Web") must hold: app routes serve index.html, /privacy serves privacy.html, WebAssembly is
# served as application/wasm with Brotli, the service worker and dataset manifest are never cached.
set -eu
BASE="$1"
DIST="$2"
fail() { echo "::error::$*"; exit 1; }
sha() { shasum -a 256 | cut -d' ' -f1; }
header() { # url header-name [accept-encoding]
  curl -fsS -o /dev/null -D - -H "Accept-Encoding: ${3:-identity}" "$1" | tr -d '\r' |
    awk -v h="$(echo "$2" | tr 'A-Z' 'a-z')" -F': ' 'tolower($1) == h { print $2 }'
}

# The new release is live (the switch is atomic, but allow for a slow edge of a few seconds).
for _ in 1 2 3 4 5 6; do
  [ "$(curl -fsS "$BASE/version.json" | sha)" = "$(sha < "$DIST/version.json")" ] && break
  sleep 5
done
[ "$(curl -fsS "$BASE/version.json" | sha)" = "$(sha < "$DIST/version.json")" ] || fail "version.json is not the deployed one"
[ "$(curl -fsS "$BASE/sw.js" | sha)" = "$(sha < "$DIST/sw.js")" ] || fail "sw.js is not the deployed one"

index="$(sha < "$DIST/index.html")"
for route in / /zh/search /yue /reading /settings; do
  [ "$(curl -fsS "$BASE$route" | sha)" = "$index" ] || fail "$route does not serve index.html"
done
[ "$(curl -fsS "$BASE/privacy" | sha)" = "$(sha < "$DIST/privacy.html")" ] || fail "/privacy does not serve privacy.html"
code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/missing-file-$$.js")"
[ "$code" = 404 ] || fail "a missing asset returned $code, expected 404"

wasm="$(cd "$DIST" && ls ./*.wasm | head -n 1 | sed 's|^\./||')"
[ "$(header "$BASE/$wasm" content-type br)" = "application/wasm" ] || fail "$wasm is not served as application/wasm"
[ "$(header "$BASE/$wasm" content-encoding br)" = "br" ] || fail "$wasm is not served with Brotli"
for path in /sw.js /index.html /data/manifest.json; do
  [ "$(header "$BASE$path" cache-control)" = "no-cache" ] || fail "$path is not served with Cache-Control: no-cache"
done
[ "$(curl -fsS "$BASE/data/manifest.json" | sha)" = "$(sha < "$DIST/data/manifest.json")" ] || fail "data/manifest.json differs"
echo "szdict.com serves $(cat "$DIST/version.json")"
