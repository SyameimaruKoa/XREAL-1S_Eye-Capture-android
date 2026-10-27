#!/usr/bin/env python3
"""Use rows with strong vertical change to decide if the right strip comes from
the same row or the next row."""
import sys

W, H = 2048, 1512
N = 32


def load(path):
    d = open(path, "rb").read()
    assert len(d) == W * H * 3, (path, len(d))
    return d


def luma(d, x, y):
    i = (y * W + x) * 3
    return 0.299 * d[i] + 0.587 * d[i + 1] + 0.114 * d[i + 2]


def strip(d, x0, y):
    return [luma(d, x0 + i, y) for i in range(N)]


def mad(a, b):
    return sum(abs(x - y) for x, y in zip(a, b)) / len(a)


def analyze(path):
    d = load(path)
    print(f"\n===== {path} =====")
    rows = []
    for y in range(1, H - 2):
        right = strip(d, 2016, y)
        left_same = strip(d, 0, y)
        left_next = strip(d, 0, y + 1)
        left_prev = strip(d, 0, y - 1)
        vertical = mad(left_same, left_next)
        rows.append((vertical, y, mad(right, left_same), mad(right, left_next),
                     mad(right, left_prev)))

    # Rows with the strongest vertical change at the left edge.
    rows.sort(reverse=True)
    top = rows[:max(20, len(rows) // 10)]
    print(f"strong-vertical rows n={len(top)}")
    print(f"  mean diff same={sum(r[2] for r in top)/len(top):.3f} "
          f"next={sum(r[3] for r in top)/len(top):.3f} "
          f"prev={sum(r[4] for r in top)/len(top):.3f}")
    for v, y, ds, dn, dp in top[:5]:
        print(f"  y={y} v={v:.2f} same={ds:.2f} next={dn:.2f} prev={dp:.2f}")

    # Best row offset for the right strip vs the left strip, high-pass weighted.
    print("row-offset search (right[y] vs left[y+off]):")
    for off in range(-4, 5):
        vals = []
        weights = []
        for y in range(5, H - 6):
            right = strip(d, 2016, y)
            left = strip(d, 0, y + off)
            w = mad(strip(d, 0, y), strip(d, 0, y + 1)) + 0.01
            vals.append(mad(right, left) * w)
            weights.append(w)
        print(f"  off={off:+d} weighted diff={sum(vals)/sum(weights):.3f}")


for p in sys.argv[1:]:
    analyze(p)
