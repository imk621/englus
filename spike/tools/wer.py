#!/usr/bin/env python3
"""Word error rate between a reference transcript and a spike run.

    python tools/wer.py reference.txt results/ggml-base.en_4t_dtw_1234.txt

VOA Learning English and BBC 6 Minute English publish transcripts, which is why
Phase 0 uses them: they are the only way to get a real WER number rather than a
subjective "looks about right".
"""
import re
import sys


def normalize(text: str) -> list[str]:
    text = text.lower()
    text = re.sub(r"[^a-z0-9'\s]", " ", text)   # keep apostrophes: "don't" != "dont"
    return text.split()


def edit_distance(ref: list[str], hyp: list[str]) -> tuple[int, int, int, int]:
    """Levenshtein with operation counts. Returns (distance, sub, ins, dele)."""
    n, m = len(ref), len(hyp)
    # (cost, sub, ins, del) per cell; only two rows are ever live.
    prev = [(j, 0, j, 0) for j in range(m + 1)]
    for i in range(1, n + 1):
        cur = [(i, 0, 0, i)] + [(0, 0, 0, 0)] * m
        for j in range(1, m + 1):
            if ref[i - 1] == hyp[j - 1]:
                cur[j] = prev[j - 1]
            else:
                c_sub, c_ins, c_del = prev[j - 1][0], cur[j - 1][0], prev[j][0]
                best = min(c_sub, c_ins, c_del)
                if best == c_sub:
                    p = prev[j - 1]; cur[j] = (best + 1, p[1] + 1, p[2], p[3])
                elif best == c_ins:
                    p = cur[j - 1]; cur[j] = (best + 1, p[1], p[2] + 1, p[3])
                else:
                    p = prev[j]; cur[j] = (best + 1, p[1], p[2], p[3] + 1)
        prev = cur
    return prev[m]


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2

    ref = normalize(open(sys.argv[1], encoding="utf-8").read())
    hyp = normalize(open(sys.argv[2], encoding="utf-8").read())
    if not ref:
        print("reference is empty")
        return 1

    dist, sub, ins, dele = edit_distance(ref, hyp)
    wer = dist / len(ref)

    print(f"reference words : {len(ref)}")
    print(f"hypothesis words: {len(hyp)}")
    print(f"substitutions   : {sub}")
    print(f"insertions      : {ins}")
    print(f"deletions       : {dele}")
    print(f"WER             : {wer:.2%}")
    # Rough reading for English learning material: <10% is usable for dictation
    # scoring, >20% means the transcript itself teaches errors.
    verdict = "usable" if wer < 0.10 else ("marginal" if wer < 0.20 else "unusable")
    print(f"verdict         : {verdict}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
