#!/usr/bin/env python3
"""Compare VCL NAL units between raw UVC HEVC and the MP4-extracted HEVC."""
import hashlib
import sys


def split_annex_b(data):
    nals = []
    i = 0
    n = len(data)
    starts = []
    while i + 3 <= n:
        if data[i] == 0 and data[i + 1] == 0 and data[i + 2] == 1:
            starts.append(i + 3)
            i += 3
        elif i + 4 <= n and data[i] == 0 and data[i + 1] == 0 and data[i + 2] == 0 and data[i + 3] == 1:
            starts.append(i + 4)
            i += 4
        else:
            i += 1
    for k, s in enumerate(starts):
        e = starts[k + 1] - 3 if k + 1 < len(starts) else n
        # Trim a 4-byte start code's leading zero from the previous NAL.
        while e > s and data[e - 1] == 0:
            e -= 1
        nals.append(data[s:e])
    return nals


def vcl_hashes(path):
    with open(path, "rb") as f:
        data = f.read()
    out = []
    types = {}
    for nal in split_annex_b(data):
        if not nal:
            continue
        t = (nal[0] >> 1) & 0x3F
        types[t] = types.get(t, 0) + 1
        if t < 32:
            out.append(hashlib.sha256(nal).hexdigest())
    return out, types


def main():
    raw, rt = vcl_hashes(sys.argv[1])
    mp4, mt = vcl_hashes(sys.argv[2])
    print(f"raw VCL={len(raw)} types={dict(sorted(rt.items()))}")
    print(f"mp4 VCL={len(mp4)} types={dict(sorted(mt.items()))}")
    same = raw == mp4
    print(f"VCL sequence identical: {same}")
    if not same:
        for i, (a, b) in enumerate(zip(raw, mp4)):
            if a != b:
                print(f"first mismatch at VCL #{i}")
                break
        else:
            print(f"length differs: raw={len(raw)} mp4={len(mp4)}")
    print(f"raw total NALs={sum(rt.values())} mp4 total NALs={sum(mt.values())}")


if __name__ == "__main__":
    main()
