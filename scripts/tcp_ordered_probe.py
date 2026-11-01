#!/usr/bin/env python3
"""Send XREAL control requests sequentially through an existing TCP proxy."""

import argparse
import contextlib
import socket
import struct
import time


def read_exact(sock, count):
    data = bytearray()
    while len(data) < count:
        part = sock.recv(count - len(data))
        if not part:
            raise ConnectionError("connection closed while reading a frame")
        data.extend(part)
    return bytes(data)


def transact(sock, command, sequence, body, timeout):
    payload = struct.pack(">HH", 0x8000, sequence) + body
    sock.sendall(struct.pack(">HI", command, len(payload)) + payload)
    deadline = time.monotonic() + timeout
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("response deadline exceeded")
        sock.settimeout(remaining)
        response_command, length = struct.unpack(">HI", read_exact(sock, 6))
        if length > 1024 * 1024:
            raise ValueError("response exceeds 1 MiB")
        response = read_exact(sock, length)
        if len(response) >= 4:
            flags, response_sequence = struct.unpack(">HH", response[:4])
            if response_command == command and flags == 0 and response_sequence == sequence:
                return response[4:]
        print("event command=%04x payload=%s" % (response_command, response.hex()), flush=True)


def receive_video(sock, output_path, seconds):
    deadline = time.monotonic() + seconds
    frames = 0
    video_bytes = 0
    first = None
    last = None
    candidate_first = None
    candidate_last = None
    candidate_invalid = 0
    candidate_max_step = 0
    candidate_long_steps = 0
    arrival_max_step = 0.0
    with open(output_path, "xb") as output:
        while time.monotonic() < deadline:
            sock.settimeout(max(0.1, deadline - time.monotonic()))
            try:
                command, length = struct.unpack(">HI", read_exact(sock, 6))
                if length > 8 * 1024 * 1024:
                    raise ValueError("video message exceeds 8 MiB")
                body = read_exact(sock, length)
            except socket.timeout:
                break
            if command != 0x2785 or len(body) < 102 or body[97:101] != b"\x00\x00\x00\x01":
                raise ValueError("unexpected video message")
            unit = body[97:]
            output.write(unit)
            video_bytes += len(unit)
            if (unit[4] >> 1) & 0x3f < 32:
                now = time.monotonic()
                first = now if first is None else first
                if last is not None:
                    arrival_max_step = max(arrival_max_step, now - last)
                last = now
                candidate = struct.unpack_from("<q", body, 24)[0]
                if candidate_last is not None:
                    step = candidate - candidate_last
                    if step <= 0:
                        candidate_invalid += 1
                    else:
                        candidate_max_step = max(candidate_max_step, step)
                        if step > 50000000:
                            candidate_long_steps += 1
                candidate_first = candidate if candidate_first is None else candidate_first
                candidate_last = candidate
                frames += 1
    span = 0 if first is None or last is None else last - first
    print("video frames=%d bytes=%d arrival_span=%.6f fps=%.3f Mbps=%.3f" %
          (frames, video_bytes, span, (frames - 1) / span if span else 0,
           video_bytes * 8 / span / 1000000 if span else 0), flush=True)
    candidate_span = 0 if candidate_first is None else candidate_last - candidate_first
    print("camera_timestamp_candidate_span_ns=%d non_increasing=%d candidate_fps=%.3f" %
          (candidate_span, candidate_invalid,
           (frames - 1) * 1000000000 / candidate_span if candidate_span > 0 and not candidate_invalid else 0),
          flush=True)
    print("candidate_max_step_ns=%d candidate_steps_over_50ms=%d arrival_max_step_ms=%.3f" %
          (candidate_max_step, candidate_long_steps, arrival_max_step * 1000), flush=True)


def main():
    parser = argparse.ArgumentParser(
        description="Send control requests one at a time, waiting for each matching reply. "
        "Connect through a separately managed proxy bound to the glasses network. "
        "The tool does not start a proxy or restore camera state.")
    parser.add_argument("--host", required=True, help="address of an existing TCP proxy")
    parser.add_argument("--port", required=True, type=int, help="proxy TCP port")
    parser.add_argument("--step", action="append", required=True, metavar="COMMAND:BODY",
                        help="hex command and hex body; repeat in desired order")
    parser.add_argument("--cleanup", action="append", default=[], metavar="COMMAND:BODY",
                        help="request to attempt even after an error; repeat in desired order")
    parser.add_argument("--timeout", type=float, default=10.0, help="response timeout in seconds")
    parser.add_argument("--video-port", type=int, help="existing proxy port for video; connects before control")
    parser.add_argument("--video-output", help="new path for unmodified HEVC access units")
    parser.add_argument("--video-seconds", type=float, default=10.0, help="duration to receive video after requests")
    parser.add_argument("--send", action="store_true", help="transmit requests; otherwise show a dry run")
    args = parser.parse_args()
    def parse_steps(values):
        result = []
        for value in values:
            command, separator, body = value.partition(":")
            if not separator:
                parser.error("each request must have COMMAND:BODY format")
            try:
                parsed = (int(command, 16), bytes.fromhex(body))
            except ValueError:
                parser.error("commands and bodies must be hexadecimal")
            if not 0 <= parsed[0] <= 65535:
                parser.error("command must fit in 16 bits")
            result.append(parsed)
        return result

    steps = parse_steps(args.step)
    cleanup = parse_steps(args.cleanup)
    if len(steps) + len(cleanup) > 65535:
        parser.error("too many requests")
    if args.timeout <= 0:
        parser.error("timeout must be positive")
    if (args.video_port is None) != (args.video_output is None):
        parser.error("--video-port and --video-output must be specified together")
    if args.video_seconds <= 0:
        parser.error("video duration must be positive")
    for index, (command, body) in enumerate(steps, 1):
        print("step=%d command=%04x body=%s" % (index, command, body.hex()), flush=True)
    for index, (command, body) in enumerate(cleanup, len(steps) + 1):
        print("cleanup=%d command=%04x body=%s" % (index, command, body.hex()), flush=True)
    if not args.send:
        print("dry run: no connection made")
        return
    video = None
    if args.video_port is not None:
        video = socket.create_connection((args.host, args.video_port), timeout=args.timeout)
    with video if video is not None else contextlib.nullcontext(), \
            socket.create_connection((args.host, args.port), timeout=args.timeout) as sock:
        try:
            for index, (command, body) in enumerate(steps, 1):
                response = transact(sock, command, index, body, args.timeout)
                print("reply step=%d command=%04x body=%s" % (index, command, response.hex()), flush=True)
                if response != b"\x22\x00":
                    raise RuntimeError("non-success reply; remaining requests were not sent")
            if video is not None:
                receive_video(video, args.video_output, args.video_seconds)
        finally:
            cleanup_failed = False
            for index, (command, body) in enumerate(cleanup, len(steps) + 1):
                try:
                    response = transact(sock, command, index, body, args.timeout)
                    print("cleanup reply=%d command=%04x body=%s" % (index, command, response.hex()), flush=True)
                    if response != b"\x22\x00":
                        cleanup_failed = True
                except (OSError, ValueError) as exc:
                    print("cleanup failed command=%04x error=%s" % (command, exc), flush=True)
                    cleanup_failed = True
            if cleanup_failed:
                raise RuntimeError("one or more cleanup requests failed")


if __name__ == "__main__":
    main()
