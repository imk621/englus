#!/usr/bin/env bash
# Downloads a ggml whisper model and pushes it onto the phone.
#
#   ./tools/fetch-model.sh base.en
#   ./tools/fetch-model.sh small.en
#
# Phase 0 should compare at least base.en and small.en: base.en is the one likely
# to hit the speed target, small.en the one likely to hit the accuracy target.
# Which side of that trade the app lands on is the decision this spike exists for.
set -euo pipefail

MODEL="${1:-base.en}"
PKG="com.englus.spike"
REMOTE_DIR="/sdcard/Android/data/$PKG/files/models"

cd "$(dirname "$0")/.."
mkdir -p models
LOCAL="models/ggml-$MODEL.bin"

if [ ! -f "$LOCAL" ]; then
  URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$MODEL.bin"
  echo "downloading $URL"
  curl -L --fail --progress-bar -o "$LOCAL" "$URL"
else
  echo "already downloaded: $LOCAL"
fi

echo "pushing to $REMOTE_DIR"
adb shell mkdir -p "$REMOTE_DIR"
adb push "$LOCAL" "$REMOTE_DIR/"
echo "done. Tap '모델 목록 새로고침' in the app."
