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

# Git Bash rewrites /sdcard/... into a Windows path before adb sees it, which
# makes every push land in C:/Program Files/Git/sdcard. Harmless elsewhere.
export MSYS_NO_PATHCONV=1

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

. "$(dirname "$0")/push-model.sh"
push_model "$LOCAL"
echo "done. Tap '모델 목록 새로고침' in the app."
