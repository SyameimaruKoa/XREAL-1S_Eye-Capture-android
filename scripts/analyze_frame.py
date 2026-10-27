#!/usr/bin/env python3
"""Inspect RGB24 frames for borders, bottom corruption, and 16px blockiness."""
import sys

W, H = 2048, 1512


def load(path):
    with open(path, "rb") as f:
        data = f.read()
    if len(data) != W * H * 3:
        raise SystemExit(f"{path}: expected {W*H*3} bytes, got {len(data)}")
    return data


def y_at(data, x, y):
    i = (y * W + x) * 3
    return (299 * data[i] + 587 * data[i + 1] + 114 * data[i + 2]) // 1000


def stats(path):
    data = load(path)
    row_mean = []
    row_act = []
    for y in range(H):
        s = 0
        a = 0
        for x in range(W):
            v = y_at(data, x, y)
            s += v
            if x:
                a += abs(v - prev)
            prev = v
        row_mean.append(s / W)
        row_act.append(a / (W - 1))

    col_mean = []
    for x in range(W):
        s = 0
        for y in range(0, H, 4):
            s += y_at(data, x, y)
        col_mean.append(s / (H // 4))

    def band_mean(vals, a, b):
        return sum(vals[a:b]) / max(1, b - a)

    def band_max(vals, a, b):
        return max(vals[a:b]) if b > a else 0

    print(f"\n== {path} ==")
    print(f"row mean: top={band_mean(row_mean,0,100):.1f} mid={band_mean(row_mean,H//2-50,H//2+50):.1f} "
          f"bottom100={band_mean(row_mean,H-100,H):.1f} last20={band_mean(row_mean,H-20,H):.1f}")
    print(f"row activity: top={band_mean(row_act,0,100):.2f} mid={band_mean(row_act,H//2-50,H//2+50):.2f} "
          f"bottom100={band_mean(row_act,H-100,H):.2f} bottom300={band_mean(row_act,H-300,H):.2f} "
          f"max_bottom={band_max(row_act,H-300,H):.2f} at y={H-300+row_act[H-300:].index(max(row_act[H-300:]))}")
    print("row activity by 100-row band (last 6):", [round(band_mean(row_act, H - 100*(k+1), H - 100*k), 2)
                                                     for k in range(5, -1, -1)])
    print(f"col mean: left64={band_mean(col_mean,0,64):.1f} left128={band_mean(col_mean,0,128):.1f} "
          f"mid={band_mean(col_mean,W//2-64,W//2+64):.1f} right128={band_mean(col_mean,W-128,W):.1f} "
          f"right64={band_mean(col_mean,W-64,W):.1f}")
    dark_cols = [x for x, v in enumerate(col_mean) if v < 8]
    print(f"cols mean<8: {len(dark_cols)} first={dark_cols[:8]} last={dark_cols[-8:]}")
    dark_rows = [y for y, v in enumerate(row_mean) if v < 8]
    print(f"rows mean<8: {len(dark_rows)} first={dark_rows[:8]} last={dark_rows[-8:]}")

    # 16-pixel block-boundary activity vs interior, for the bottom 300 rows.
    def blockiness(y0, y1):
        edge = 0
        inner = 0
        ne = ni = 0
        for y in range(y0, y1, 4):
            for x in range(16, W - 16, 1):
                d = abs(y_at(data, x, y) - y_at(data, x - 1, y))
                if x % 16 == 0:
                    edge += d
                    ne += 1
                else:
                    inner += d
                    ni += 1
        return edge / max(1, ne), inner / max(1, ni)

    for label, y0, y1 in [("top", 0, 300), ("middle", H // 2 - 150, H // 2 + 150),
                          ("bottom", H - 300, H)]:
        e, i = blockiness(y0, y1)
        print(f"blockiness {label}: edge16={e:.3f} inner={i:.3f} ratio={e/max(1e-9,i):.3f}")


if __name__ == "__main__":
    for p in sys.argv[1:]:
        stats(p)
