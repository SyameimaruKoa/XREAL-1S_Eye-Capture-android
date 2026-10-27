#!/usr/bin/env python3
"""Test horizontal/vertical wrap hypotheses in 2048x1512 RGB24 frames."""
import sys

W, H = 2048, 1512


def load(path):
    with open(path, "rb") as f:
        data = f.read()
    if len(data) != W * H * 3:
        raise SystemExit(f"{path}: {len(data)} bytes")
    return data


def px(d, x, y):
    i = (y * W + x) * 3
    return d[i], d[i + 1], d[i + 2]


def diff(a, b):
    return (abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])) / 3.0


def analyze(path):
    d = load(path)
    print(f"\n===== {path} =====")

    # 1) Right 32 columns vs left 32 columns of the same row.
    s = n = 0
    for y in range(0, H, 2):
        for x in range(0, 32):
            s += diff(px(d, 2016 + x, y), px(d, x, y))
            n += 1
    print(f"same-row right32 vs left32: mean diff={s/n:.3f}")

    # 2) Right 32 columns vs left 32 columns of the next row.
    s = n = 0
    for y in range(0, H - 1, 2):
        for x in range(0, 32):
            s += diff(px(d, 2016 + x, y), px(d, x, y + 1))
            n += 1
    print(f"next-row right32 vs left32: mean diff={s/n:.3f}")

    # 3) Bottom 40 rows vs top 40 rows of the same frame.
    s = n = 0
    for y in range(0, 40):
        for x in range(0, W, 4):
            s += diff(px(d, x, 1472 + y), px(d, x, y))
            n += 1
    print(f"bottom40 vs top40: mean diff={s/n:.3f}")

    # 4) Best horizontal shift for right 32 vs left, using luma only, small search.
    def luma(p):
        return (299 * p[0] + 587 * p[1] + 114 * p[2]) / 1000.0

    print("right-edge vs left-edge shift search (right x=2016..2047):")
    for shift in range(0, 65, 8):
        s = n = 0
        for y in range(0, H, 4):
            for x in range(0, 32):
                # compare right edge at 2016+x with left edge at x+shift (wrap)
                s += abs(luma(px(d, 2016 + x, y)) - luma(px(d, (x + shift) % W, y)))
                n += 1
        print(f"  shift={shift:2d} diff={s/n:.2f}")

    # 5) Left edge vs right edge using a larger sample and correlation by row.
    print("edge brightness (mean luma):")
    for x0, x1 in [(0, 32), (32, 64), (1984, 2016), (2016, 2048)]:
        s = n = 0
        for y in range(0, H, 4):
            for x in range(x0, x1):
                s += luma(px(d, x, y))
                n += 1
        print(f"  x={x0:4d}..{x1-1:4d}: {s/n:.2f}")

    # 6) Check for a 32-pixel periodicity in the rightmost region.
    print("right-edge columns mean luma:")
    for x in range(1984, 2048, 4):
        s = 0
        for y in range(0, H, 8):
            s += luma(px(d, x, y))
        print(f"  x={x}: {s/(H//8):.1f}")


for p in sys.argv[1:]:
    analyze(p)
