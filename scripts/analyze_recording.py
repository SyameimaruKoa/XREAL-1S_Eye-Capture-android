#!/usr/bin/env python3
"""Analyze the 32-bit stereo WAV sidecar and the HEVC frame types."""
import array
import json
import math
import os
import subprocess
import sys
import wave


def analyze_wav(path):
    with wave.open(path, "rb") as w:
        channels = w.getnchannels()
        width = w.getsampwidth()
        rate = w.getframerate()
        nframes = w.getnframes()
        raw = w.readframes(nframes)
    if channels != 2 or width != 4:
        raise SystemExit(f"unexpected WAV format: ch={channels} width={width}")
    samples = array.array("i")
    samples.frombytes(raw)
    if sys.byteorder != "little":
        samples.byteswap()

    frames = len(samples) // 2
    max_diff = 0
    diff_frames = 0
    peak_l = peak_r = 0
    sum_l = sum_r = sum_ll = sum_rr = sum_lr = 0
    for i in range(frames):
        l = samples[2 * i]
        r = samples[2 * i + 1]
        d = l - r
        if d < 0:
            d = -d
        if d > max_diff:
            max_diff = d
        if d:
            diff_frames += 1
        al = l if l >= 0 else -l
        ar = r if r >= 0 else -r
        if al > peak_l:
            peak_l = al
        if ar > peak_r:
            peak_r = ar
        sum_l += l
        sum_r += r
        sum_ll += l * l
        sum_rr += r * r
        sum_lr += l * r

    def rms(sq):
        return math.sqrt(sq / frames) if frames else 0.0

    def corr():
        n = frames
        num = n * sum_lr - sum_l * sum_r
        den_l = n * sum_ll - sum_l * sum_l
        den_r = n * sum_rr - sum_r * sum_r
        den = math.sqrt(max(0, den_l) * max(0, den_r))
        return num / den if den else 0.0

    return {
        "file": os.path.basename(path),
        "channels": channels,
        "bits": width * 8,
        "sample_rate": rate,
        "frames": frames,
        "duration_s": frames / rate,
        "max_abs_lr_diff": max_diff,
        "diff_frames": diff_frames,
        "diff_fraction": diff_frames / frames if frames else 0,
        "peak_l": peak_l,
        "peak_r": peak_r,
        "rms_l": rms(sum_ll),
        "rms_r": rms(sum_rr),
        "correlation_lr": corr(),
    }


def analyze_pict_types(path):
    counts = {}
    total = 0
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            t = line.strip()
            if not t:
                continue
            counts[t] = counts.get(t, 0) + 1
            total += 1
    return {"total": total, "counts": counts}


def main():
    if len(sys.argv) < 3:
        raise SystemExit("usage: analyze_recording.py <wav> <pict_types.txt> [ffprobe.json]")
    result = {
        "wav": analyze_wav(sys.argv[1]),
        "pict_types": analyze_pict_types(sys.argv[2]),
    }
    if len(sys.argv) >= 4:
        with open(sys.argv[3], "r", encoding="utf-8") as f:
            probe = json.load(f)
        video = next(s for s in probe["streams"] if s["codec_type"] == "video")
        audio = next(s for s in probe["streams"] if s["codec_type"] == "audio")
        result["video"] = {
            "codec": video.get("codec_name"),
            "width": video.get("width"),
            "height": video.get("height"),
            "has_b_frames": video.get("has_b_frames"),
            "r_frame_rate": video.get("r_frame_rate"),
            "avg_frame_rate": video.get("avg_frame_rate"),
            "nb_frames": video.get("nb_frames"),
            "duration_s": video.get("duration"),
            "start_time": video.get("start_time"),
        }
        result["audio"] = {
            "codec": audio.get("codec_name"),
            "channels": audio.get("channels"),
            "channel_layout": audio.get("channel_layout"),
            "sample_rate": audio.get("sample_rate"),
            "duration_s": audio.get("duration"),
            "bit_rate": audio.get("bit_rate"),
        }
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
