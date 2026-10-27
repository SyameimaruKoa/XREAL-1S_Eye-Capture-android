#!/usr/bin/env python3
"""Detailed border/row/column analysis for a 2048x1512 RGB24 frame."""
import sys

W, H = 2048, 1512


def load(path):
    with open(path, "rb") as f:
        data = f.read()
    if len(data) != W * H * 3:
        raise SystemExit(f"{path}: expected {W*H*3} bytes, got {len(data)}")
    return data


def y_at(d, x, y):
    i = (y * W + x) * 3
    return (299 * d[i] + 587 * d[i + 1] + 114 * d[i + 2]) // 1000


def analyze(path):
    d = load(path)
    print(f"\n===== {path} =====")

    # Horizontal gradient per column (average over sampled rows).
    hg = []
    for x in range(1, W):
        s = 0
        n = 0
        for y in range(0, H, 8):
            s += abs(y_at(d, x, y) - y_at(d, x - 1, y))
            n += 1
        hg.append((s / n, x))
    print("top horizontal-gradient columns:")
    for v, x in sorted(hg, reverse=True)[:12]:
        print(f"  x={x:4d} grad={v:.2f}")

    # Vertical gradient per row.
    vg = []
    for y in range(1, H):
        s = 0
        n = 0
        for x in range(0, W, 8):
            s += abs(y_at(d, x, y) - y_at(d, x, y - 1))
            n += 1
        vg.append((s / n, y))
    print("top vertical-gradient rows:")
    for v, y in sorted(vg, reverse=True)[:12]:
        print(f"  y={y:4d} grad={v:.2f}")

    # Row mean/activity for selected ranges.
    print("row profile every 32 rows (last 512):")
    for y in range(H - 512, H, 32):
        s = 0
        a = 0
        for x in range(W):
            v = y_at(d, x, y)
            s += v
            if x:
                a += abs(v - prev)
            prev = v
        print(f"  y={y:4d} mean={s/W:7.2f} act={a/(W-1):6.2f}")

    # Column profile for edges.
    col_mean = []
    for x in range(W):
        s = 0
        for y in range(0, H, 8):
            s += y_at(d, x, y)
        col_mean.append(s / (H // 8))
    print("left columns mean every 16 (0..255):")
    print("  " + " ".join(f"{x}:{col_mean[x]:.0f}" for x in range(0, 256, 16)))
    print("right columns mean every 16 (1792..2047):")
    print("  " + " ".join(f"{x}:{col_mean[x]:.0f}" for x in range(1792, 2048, 16)))

    # Are the bottom rows constant across x?
    for y in [H - 1, H - 20, H - 100, H - 200, H - 300, H - 400, H - 500]:
        vals = [y_at(d, x, y) for x in range(W)]
        print(f"  row {y}: min={min(vals)} max={max(vals)} mean={sum(vals)/W:.1f} "
              f"unique={len(set(vals))}")

    # Look for a sharp content boundary in the bottom 400 rows: compare each row
    # with the row 8 above, and report biggest changes.
    changes = []
    for y in range(H - 400, H, 8):
        s = 0
        for x in range(0, W, 4):
            s += abs(y_at(d, x, y) - y_at(d, x, y - 8))
        changes.append((s / (W // 4), y))
    print("largest row changes in bottom 400 (8-row delta):")
    for v, y in sorted(changes, reverse=True)[:10]:
        print(f"  y={y:4d} delta={v:.2f}")


for p in sys.argv[1:]:
    analyze(p)
