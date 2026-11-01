#!/usr/bin/env python3
"""Send XREAL control requests sequentially through an existing TCP proxy."""

import argparse
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


def main():
    parser = argparse.ArgumentParser(
        description="Send control requests one at a time, waiting for each matching reply. "
        "Connect through a separately managed proxy bound to the glasses network. "
        "The tool does not start a proxy or restore camera state.")
    parser.add_argument("--host", required=True, help="address of an existing TCP proxy")
    parser.add_argument("--port", required=True, type=int, help="proxy TCP port")
    parser.add_argument("--step", action="append", required=True, metavar="COMMAND:BODY",
                        help="hex command and hex body; repeat in desired order")
    parser.add_argument("--timeout", type=float, default=10.0, help="response timeout in seconds")
    parser.add_argument("--send", action="store_true", help="transmit requests; otherwise show a dry run")
    args = parser.parse_args()
    steps = []
    for value in args.step:
        command, separator, body = value.partition(":")
        if not separator:
            parser.error("each --step must have COMMAND:BODY format")
        try:
            parsed = (int(command, 16), bytes.fromhex(body))
        except ValueError:
            parser.error("commands and bodies must be hexadecimal")
        if not 0 <= parsed[0] <= 65535:
            parser.error("command must fit in 16 bits")
        steps.append(parsed)
    for index, (command, body) in enumerate(steps, 1):
        print("step=%d command=%04x body=%s" % (index, command, body.hex()), flush=True)
    if not args.send:
        print("dry run: no connection made")
        return
    with socket.create_connection((args.host, args.port), timeout=args.timeout) as sock:
        for index, (command, body) in enumerate(steps, 1):
            response = transact(sock, command, index, body, args.timeout)
            print("reply step=%d command=%04x body=%s" % (index, command, response.hex()), flush=True)
            if response != b"\x22\x00":
                raise RuntimeError("non-success reply; remaining requests were not sent")


if __name__ == "__main__":
    main()
