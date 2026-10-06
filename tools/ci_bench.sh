#!/usr/bin/env bash
# Runs PreviewDecodeBenchmark on the connected emulator/device and pulls the results into $BENCH_OUT.
# Expects the benchmark APKs to be built and fixtures to exist in $BENCH_FIXTURES.
set -euo pipefail

PKG=com.yashikota.omaigenzo
OUT=${BENCH_OUT:-build/bench}
FIXTURES=${BENCH_FIXTURES:-build/bench-fixtures}
THINK_MS=${THINK_MS:-300}
ROUNDS=${ROUNDS:-5}
REMOTE=/sdcard/Android/data/$PKG/files

mkdir -p "$OUT"
adb install -r -g app/build/outputs/apk/benchmark/app-benchmark.apk
adb install -r app/build/outputs/apk/androidTest/benchmark/app-benchmark-androidTest.apk

adb shell mkdir -p "$REMOTE/bench" "$REMOTE/perf"
# The emulator is created fresh for every run, so there is no stale bench-results.jsonl to clear
# (and a file created here by the shell user might not be appendable by the app).
adb push "$FIXTURES/." "$REMOTE/bench/"

adb shell am instrument -w \
  -e class com.yashikota.omaigenzo.PreviewDecodeBenchmark \
  -e thinkMs "$THINK_MS" -e rounds "$ROUNDS" \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/instrument.txt"

# `am instrument` exits 0 even when tests fail or are skipped, so check its report explicitly.
if ! grep -qE '^OK \([0-9]+ tests?\)' "$OUT/instrument.txt"; then
  echo "::error title=Benchmark::instrumentation did not report OK (failed, crashed or skipped for lack of photos)"
  exit 1
fi

adb pull "$REMOTE/perf/bench-results.jsonl" "$OUT/bench-results.jsonl"
adb pull "$REMOTE/perf/omai-perf.jsonl" "$OUT/omai-perf.jsonl" || true
