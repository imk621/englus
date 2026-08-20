#!/usr/bin/env bash
# Downloads the reference episode used for the decisive Phase 0 measurement.
#
# NPR Planet Money, ~40 min, 44.1 kHz joint stereo, 128 kbps mp3.
# Chosen deliberately:
#   - podcast RSS enclosure, i.e. the exact fetch path Phase 1 will use
#   - spontaneous conversational speech, not read prose (read audiobooks are the
#     easiest case for ASR and would flatter the accuracy numbers)
#   - 44.1 kHz stereo, so the resampler and downmix are actually exercised
#   - long enough that the SoC leaves boost clocks and thermal throttling shows up
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p audio
DEST="audio/reference.mp3"

if [ -f "$DEST" ]; then
  echo "already present: $DEST ($(wc -c < "$DEST") bytes)"
  exit 0
fi

FEED="https://feeds.npr.org/510289/podcast.xml"
echo "resolving newest episode from $FEED"

URL="$(curl -sL --max-time 60 "$FEED" \
  | grep -oE 'url="https://[^"]*\.mp3[^"]*"' \
  | head -1 | sed 's/^url="//; s/"$//' | sed 's/&amp;/\&/g')"

if [ -z "$URL" ]; then
  echo "error: could not find an mp3 enclosure in the feed" >&2
  exit 1
fi

echo "downloading $URL"
curl -L --fail --progress-bar --max-time 900 -o "$DEST" "$URL"
echo "saved $DEST ($(wc -c < "$DEST") bytes)"
