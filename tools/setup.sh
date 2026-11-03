#!/usr/bin/env bash
# Restores the vendored whisper.cpp checkout at the pinned commit.
# whisper.cpp is gitignored, so run this once after cloning this repo.
set -euo pipefail

cd "$(dirname "$0")/.."
PIN_FILE="tools/whisper-pin.txt"
DEST="third_party/whisper.cpp"

PIN="$(grep -v '^#' "$PIN_FILE" | tr -d '[:space:]')"

if [ -d "$DEST/.git" ]; then
  echo "whisper.cpp already present; checking out pin $PIN"
  git -C "$DEST" fetch --depth 1 origin "$PIN"
  git -C "$DEST" checkout -q "$PIN"
else
  echo "cloning whisper.cpp @ $PIN"
  mkdir -p third_party
  git clone --filter=blob:none --no-checkout https://github.com/ggml-org/whisper.cpp.git "$DEST"
  git -C "$DEST" checkout -q "$PIN"
fi

echo "ok: $(git -C "$DEST" rev-parse --short HEAD)"
