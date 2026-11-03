#!/usr/bin/env bash
# Instrumented tests for the learning app (:app).
#
#   ./tools/run-app-tests.sh                             # emulator
#   ABI=arm64-v8a MODEL=base.en ./tools/run-app-tests.sh # real phone
#
# Same shape as run-tests.sh: install by hand rather than via
# connectedAndroidTest, which uninstalls the app and takes the pushed model with
# it, turning the transcription test into a silent SKIP that still reports OK.
set -euo pipefail

export MSYS_NO_PATHCONV=1

cd "$(dirname "$0")/.."

ABI="${ABI:-x86_64}"
MODEL="${MODEL:-tiny.en}"
PKG="com.englus.app"

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -Pabis="$ABI" --console=plain

adb install -r app/build/outputs/apk/debug/*-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/*-androidTest.apk

. "$(dirname "$0")/push-model.sh"
if [ -f "models/ggml-$MODEL.bin" ]; then
  PKG="$PKG" push_model "models/ggml-$MODEL.bin"
else
  echo "warning: models/ggml-$MODEL.bin not found — transcription test will SKIP"
fi

OUT=$(adb shell am instrument -r -w \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" 2>&1)

echo "$OUT" | sed -n 's/^INSTRUMENTATION_STATUS: test=/  test: /p' | sort -u
echo "$OUT" | grep -E "^(OK|FAILURES|Tests run)" || true

if echo "$OUT" | grep -q "AssumptionViolatedException"; then
  echo
  echo "FAIL: a test was SKIPPED —"
  echo "$OUT" | grep -A1 "AssumptionViolatedException" | head -4
  exit 1
fi
if echo "$OUT" | grep -q "FAILURES!!!"; then
  echo "$OUT" | grep -A14 "^INSTRUMENTATION_STATUS: stack=" | head -40
  exit 1
fi
echo "$OUT" | grep -qE "^OK \(" && exit 0
echo "could not determine test outcome"
exit 1
