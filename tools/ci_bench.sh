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

# Let the app create its own storage directories. A directory created here by the shell user is not
# writable by the app, and PerfLogger then fails on start (this is how the first working run died).
# Launching the real activity first also smoke-tests the minified app: it must start without crashing.
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
sleep 3
if adb logcat -d -b crash | grep -q "Process: $PKG"; then
  echo "::error title=Benchmark::the minified app crashed on launch"
  adb logcat -d -b crash | grep -A25 "Process: $PKG" | head -60
  exit 1
fi
adb shell am force-stop "$PKG"
# Fixtures are only read by the app, so a shell-owned directory is fine for them.
adb shell mkdir -p "$REMOTE/bench"
# The emulator is created fresh for every run, so there is no stale bench-results.jsonl to clear
# (and a file created here by the shell user might not be appendable by the app).
adb push "$FIXTURES/." "$REMOTE/bench/"

# Whatever happens, keep the device log: it is the only way to see why a run hung or crashed.
trap 'adb logcat -d > "$OUT/logcat.txt" 2>&1 || true' EXIT
adb logcat -c

# A process that dies on start (for example an R8-stripped class) leaves `am instrument` silent until
# the timeout, so watch the device log and fail within seconds, printing the crash itself.
watch_for_crash() {
  local app_pid=$1
  while kill -0 "$app_pid" 2>/dev/null; do
    if adb logcat -d -b crash 2>/dev/null | grep -q "Process: $PKG"; then
      echo "::error title=Benchmark::the app process crashed on start"
      adb logcat -d -b crash | grep -A25 "Process: $PKG" | head -60
      pkill -P "$app_pid" 2>/dev/null || true
      kill "$app_pid" 2>/dev/null || true
      return 0
    fi
    sleep 3
  done
}

# One instrumentation pass. $1 = hardware true|false. Returns non-zero if it failed, hung or was skipped.
run_pass() {
  local hardware=$1 log="$OUT/instrument-hardware-$1.txt"
  # -r streams per-test results as they happen, and timeout bounds the pass.
  adb logcat -c
  timeout "${PASS_TIMEOUT:-12m}" adb shell am instrument -w -r \
    -e class com.yashikota.omaigenzo.PreviewDecodeBenchmark \
    -e thinkMs "$THINK_MS" -e rounds "$ROUNDS" -e hardware "$hardware" \
    "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$log" 2>&1 &
  local instrument_pid=$!
  watch_for_crash "$instrument_pid" &
  local watcher_pid=$!
  wait "$instrument_pid" || echo "::warning title=Benchmark::hardware=$hardware instrumentation exited abnormally (timeout, crash or killed)"
  kill "$watcher_pid" 2>/dev/null || true
  cat "$log"

  # `am instrument` exits 0 even when tests fail, so read the raw status codes:
  # 0 = passed, -1 = error, -2 = failure, -3 = ignored, -4 = assumption failed (e.g. no photos found).
  local passed bad
  passed=$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0' "$log" || true)
  bad=$(grep -cE '^INSTRUMENTATION_STATUS_CODE: -[1-4]' "$log" || true)
  if [ "$passed" -lt 1 ] || [ "$bad" -gt 0 ] || ! grep -q '^INSTRUMENTATION_CODE: -1' "$log"; then
    echo "hardware=$hardware: passed=$passed bad=$bad"
    grep -E '^INSTRUMENTATION_STATUS: (test|class|stack)=|Timed out|shortMsg|longMsg' "$log" | head -60 || true
    return 1
  fi
}

# Software bitmaps are the stable, required measurement. GPU-resident bitmaps are measured too, but
# emulator GL is not a faithful GPU (an earlier run hung there), so that pass may fail without
# failing the job: it exists to compare the two, not to gate.
if ! run_pass false; then
  echo "::error title=Benchmark::software-bitmap pass failed, hung or was skipped (see instrument-hardware-false.txt and logcat.txt)"
  exit 1
fi
run_pass true || echo "::warning title=Benchmark::hardware-bitmap pass did not complete on this emulator; its results are omitted"

adb pull "$REMOTE/perf/bench-results.jsonl" "$OUT/bench-results.jsonl"
adb pull "$REMOTE/perf/omai-perf.jsonl" "$OUT/omai-perf.jsonl" || true
