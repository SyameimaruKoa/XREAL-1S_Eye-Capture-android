#!/usr/bin/env python3
"""Determine whether the right 32 columns copy the same row or an adjacent row."""
import sys

W, H = 2048, 1512
STRIP = 32


def load(path):
    with open(path, "rb") as f:
        d = f.read()
    if len(d) != W * H * 3:
        raise SystemExit(f"{path}: {len(d)}")
    return d


def luma(d, x, y):
    i = (y * W + x) * 3
    return 0.299 * d[i] + 0.587 * d[i + 1] + 0.114 * d[i + 2]


def strip_diff(d, y, src_y, src_x=0, n=STRIP):
    s = 0.0
    for x in range(n):
        s += abs(luma(d, 2016 + x, y) - luma(d, src_x + x, src_y))
    return s / n


def analyze(path):
    d = load(path)
    print(f"\n===== {path} =====")
    # Average per-row difference for candidate sources.
    candidates = {
        "same row left": lambda y: strip_diff(d, y, y, 0),
        "next row left": lambda y: strip_diff(d, y, (y + 1) % H, 0),
        "prev row left": lambda y: strip_diff(d, y, (y - 1) % H, 0),
        "same row x=32": lambda y: strip_diff(d, y, y, 32),
        "same row x=64": lambda y: strip_diff(d, y, y, 64),
        "same row x=1984": lambda y: strip_diff(d, y, y, 1984),
    }
    for name, fn in candidates.items():
        vals = [fn(y) for y in range(0, H - 1, 8)]
        print(f"  {name:16s}: mean={sum(vals)/len(vals):6.3f}")

    # Per-row winner between same/next/prev.
    wins = {"same": 0, "next": 0, "prev": 0}
    for y in range(0, H - 1, 8):
        ds = strip_diff(d, y, y, 0)
        dn = strip_diff(d, y, (y + 1) % H, 0)
        dp = strip_diff(d, y, (y - 1) % H, 0)
        m = min(ds, dn, dp)
        if m == ds:
            wins["same"] += 1
        elif m == dn:
            wins["next"] += 1
        else:
            wins["prev"] += 1
    print("  per-row winner:", wins)

    # Horizontal periodicity: compare right strip to left at offsets 0..40.
    print("  right strip best horizontal offset (same row):")
    out = []
    for off in range(0, 41):
        vals = [strip_diff(d, y, y, off) for y in range(0, H - 1, 8)]
        out.append((sum(vals) / len(vals), off))
    for v, off in sorted(out)[:5]:
        print(f"    off={off:2d} diff={v:.3f}")

    # Is the left strip a copy of something else?
    print("  left strip vs same row x=2016:", end=" ")
    vals = []
    for y in range(0, H - 1, 8):
        s = 0
        for x in range(STRIP):
            s += abs(luma(d, x, y) - luma(d, 2016 + x, y))
        vals.append(s / STRIP)
    print(f"{sum(vals)/len(vals):.3f}")


for p in sys.argv[1:]:
    analyze(p)
