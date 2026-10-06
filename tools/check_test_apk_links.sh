#!/usr/bin/env bash
# Fails when the instrumented test APK references classes that R8 removed from the minified app.
# The test runner runs inside the app process, so a missing class crashes it on start
# (NoClassDefFoundError) before any test runs. Needs build-tools' dexdump (ANDROID_HOME or PATH).
#
#   tools/check_test_apk_links.sh [app.apk] [test.apk]
set -euo pipefail
export LC_ALL=C

APP=${1:-app/build/outputs/apk/benchmark/app-benchmark.apk}
TEST=${2:-$(find app/build/outputs/apk/androidTest/benchmark -name "*.apk" | head -1)}
DEXDUMP=$(command -v dexdump || find "${ANDROID_HOME:-$HOME/android-sdk}/build-tools" -name dexdump | sort -V | tail -1)

work=$(mktemp -d)
trap 'gomi "$work" >/dev/null 2>&1 || true' EXIT
mkdir -p "$work/app" "$work/test"
unzip -o -q "$APP" 'classes*.dex' -d "$work/app"
unzip -o -q "$TEST" 'classes*.dex' -d "$work/test"

defined() { for f in "$1"/classes*.dex; do "$DEXDUMP" -d "$f" 2>/dev/null | grep "Class descriptor" | sed "s/.*'\(L[^']*;\)'.*/\1/"; done | sort -u; }
referenced() { for f in "$1"/classes*.dex; do "$DEXDUMP" -d "$f" 2>/dev/null | grep -oE "L(kotlin|kotlinx|androidx|org/jetbrains|com/google)/[A-Za-z0-9_/\$]+;"; done | sort -u; }

defined "$work/app" > "$work/app.defs"
defined "$work/test" > "$work/test.defs"
referenced "$work/test" > "$work/test.refs"

missing=$(comm -23 "$work/test.refs" "$work/app.defs" | comm -23 - "$work/test.defs")
if [ -n "$missing" ]; then
  echo "::error title=R8::the test APK needs classes that were stripped from the app (add keep rules to app/benchmark-rules.pro):"
  echo "$missing"
  exit 1
fi
echo "test APK links resolve: every referenced class survives in the app"
