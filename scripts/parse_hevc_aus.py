#!/usr/bin/env python3
"""Parse Annex-B HEVC into access units and report NAL structure.

An AU starts at an AUD (type 35) or at a VCL NAL whose first_slice_segment_in_pic_flag
is set (when no AUD is present).
"""
import sys
from collections import Counter


def find_start_codes(data):
    nals = []
    i = 0
    n = len(data)
    while i + 3 < n:
        if data[i] == 0 and data[i + 1] == 0:
            if data[i + 2] == 1:
                sc = i
                sc_len = 3
            elif i + 3 < n and data[i + 2] == 0 and data[i + 3] == 1:
                sc = i
                sc_len = 4
            else:
                i += 1
                continue
            start = sc + sc_len
            j = start
            while j + 3 < n:
                if data[j] == 0 and data[j + 1] == 0 and (data[j + 2] == 1 or
                        (j + 3 < n and data[j + 2] == 0 and data[j + 3] == 1)):
                    break
                j += 1
            end = j if j + 3 < n else n
            if start < end:
                nals.append((sc, start, end, (data[start] >> 1) & 0x3f))
            i = end
        else:
            i += 1
    return nals


def parse(path):
    with open(path, "rb") as f:
        data = f.read()
    nals = find_start_codes(data)
    print(f"\n===== {path} ({len(data)} bytes, {len(nals)} NALs) =====")
    types = Counter(t for _, _, _, t in nals)
    print("NAL types:", dict(sorted(types.items())))

    aus = []
    cur = []
    for sc, start, end, t in nals:
        new_au = False
        if t == 35:
            new_au = True
        elif t < 32:
            first_slice = (data[start + 2] >> 7) & 1 if start + 2 < end else 1
            first_slice = (data[start + 2] & 0x80) != 0
            if first_slice:
                new_au = True
        if new_au and cur:
            aus.append(cur)
            cur = []
        cur.append((sc, start, end, t))
    if cur:
        aus.append(cur)

    print(f"AUs: {len(aus)}")
    counts = Counter(len(au) for au in aus)
    print("NALs per AU:", dict(sorted(counts.items())))
    total_sizes = [au[-1][2] - au[0][0] for au in aus]
    print(f"AU size: min={min(total_sizes)} max={max(total_sizes)} "
          f"avg={sum(total_sizes)/len(total_sizes):.1f}")

    # Last NAL of each AU.
    last_types = Counter(au[-1][3] for au in aus)
    print("last NAL types:", dict(sorted(last_types.items())))
    last_sizes = [au[-1][2] - au[-1][1] for au in aus]
    print(f"last NAL payload size: min={min(last_sizes)} max={max(last_sizes)} "
          f"avg={sum(last_sizes)/len(last_sizes):.1f}")

    # VCL NALs per AU and their sizes.
    vcl_counts = Counter(sum(1 for x in au if x[3] < 32) for au in aus)
    print("VCL NALs per AU:", dict(sorted(vcl_counts.items())))

    print("first 8 AUs:")
    for k, au in enumerate(aus[:8]):
        desc = ", ".join(f"t{t}:{end-start}" for _, start, end, t in au)
        print(f"  AU{k}: total={au[-1][2]-au[0][0]} [{desc}]")

    # Verify every AU ends with a VCL NAL.
    bad = [i for i, au in enumerate(aus) if not (au[-1][3] < 32)]
    print(f"AUs not ending in VCL: {len(bad)} {bad[:10]}")

    # First NAL types per AU.
    first_types = Counter(au[0][3] for au in aus)
    print("first NAL types:", dict(sorted(first_types.items())))


for p in sys.argv[1:]:
    parse(p)
