#!/usr/bin/env python3
"""Turns a spike report into an SRT so timestamps can be checked by eye/ear.

    python tools/json2srt.py results/run.json            # segment level
    python tools/json2srt.py results/run.json --words     # one cue per word
    python tools/json2srt.py results/run.json --words --dtw

Load the audio + generated SRT in any player (VLC, mpv). If word cues land on
the words you hear, the ±100 ms bar for karaoke and shadowing is met. This is
the practical check — there is no ground-truth word alignment to diff against.
"""
import json
import sys


def ts(ms: int) -> str:
    ms = max(0, int(ms))
    h, ms = divmod(ms, 3_600_000)
    m, ms = divmod(ms, 60_000)
    s, ms = divmod(ms, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = {a for a in sys.argv[1:] if a.startswith("--")}
    if not args:
        print(__doc__)
        return 2

    data = json.load(open(args[0], encoding="utf-8"))
    segments = data.get("segments", data)
    use_words = "--words" in flags
    use_dtw = "--dtw" in flags

    cues = []
    for seg in segments:
        if not use_words:
            cues.append((seg["t0"], seg["t1"], seg["text"].strip()))
            continue
        for tok in seg.get("tokens", []):
            text = tok["text"].strip()
            if not text:
                continue
            if use_dtw and "tdtw" in tok:
                # DTW gives a single moment, not a span; show a fixed 300 ms cue
                # so the word is readable while it is being spoken.
                start = tok["tdtw"]
                end = start + 300
            else:
                start, end = tok["t0"], tok["t1"]
            if end <= start:
                end = start + 120
            cues.append((start, end, text))

    out = []
    for i, (start, end, text) in enumerate(cues, 1):
        out.append(f"{i}\n{ts(start)} --> {ts(end)}\n{text}\n")

    dest = args[1] if len(args) > 1 else args[0].rsplit(".", 1)[0] + (
        ".words.srt" if use_words else ".srt"
    )
    with open(dest, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    print(f"wrote {len(cues)} cues -> {dest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
