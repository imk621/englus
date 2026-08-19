#!/usr/bin/env bash
# Runs the instrumented pipeline test on a connected device or emulator.
#
#   ./tools/run-tests.sh                  # emulator (x86_64), tiny.en
#   ABI=arm64-v8a MODEL=base.en ./tools/run-tests.sh    # real phone
#
# On an emulator this verifies CORRECTNESS only. Any timing it produces
# describes this PC, not a Galaxy S25 — speed is measured by the app on the phone.
set -euo pipefail

# Git Bash rewrites /sdcard/... into a Windows path before adb sees it.
export MSYS_NO_PATHCONV=1

cd "$(dirname "$0")/.."

ABI="${ABI:-x86_64}"
MODEL="${MODEL:-tiny.en}"
PKG="com.englus.spike"

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -Pabis="$ABI" --console=plain

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Deliberately NOT `gradlew connectedAndroidTest`: that task uninstalls the app,
# which deletes /sdcard/Android/data/$PKG and takes the model with it. The
# transcription test then silently SKIPs (an assumption failure counts as a pass,
# so the run still reports OK). Installing by hand keeps the model in place.
. "$(dirname "$0")/push-model.sh"
if [ -f "models/ggml-$MODEL.bin" ]; then
  push_model "models/ggml-$MODEL.bin"
else
  echo "warning: models/ggml-$MODEL.bin not found — transcription test will SKIP"
  echo "         run ./tools/fetch-model.sh $MODEL first"
fi

# -r (raw) rather than the pretty stream: a skipped test is reported as OK by the
# summary line, and a silently skipped transcription test is exactly the outcome
# that would make this whole run meaningless.
OUT=$(adb shell am instrument -r -w \
  -e class com.englus.spike.WhisperPipelineTest \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" 2>&1)

echo "$OUT" | sed -n 's/^INSTRUMENTATION_STATUS: test=/  test: /p'
echo "$OUT" | grep -E "^(OK|FAILURES|Tests run)" || true

if echo "$OUT" | grep -q "AssumptionViolatedException"; then
  echo
  echo "FAIL: a test was SKIPPED —"
  echo "$OUT" | grep -A1 "AssumptionViolatedException" | head -4
  exit 1
fi
if echo "$OUT" | grep -q "FAILURES!!!"; then
  echo "$OUT" | grep -B2 -A12 "^INSTRUMENTATION_STATUS: stack=" | head -40
  exit 1
fi
if echo "$OUT" | grep -qE "^OK \("; then
  exit 0
fi
echo "could not determine test outcome"
exit 1
