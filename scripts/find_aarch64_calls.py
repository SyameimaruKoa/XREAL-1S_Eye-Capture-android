#!/usr/bin/env python3
"""List direct AArch64 BL call sites for a virtual address in a little-endian ELF64 file."""

import argparse
import struct
from pathlib import Path


def executable_segments(data):
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected a little-endian ELF64 file")
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine != 183:
        raise ValueError("Expected an AArch64 ELF file")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    if phentsize < 56 or phoff + phentsize * phnum > len(data):
        raise ValueError("Invalid program header table")
    for i in range(phnum):
        entry = phoff + i * phentsize
        kind, flags, offset, virtual, _, file_size = struct.unpack_from("<IIQQQQ", data, entry)
        if kind == 1 and flags & 1:
            if offset + file_size > len(data):
                raise ValueError("Executable segment extends beyond the file")
            yield offset, virtual, file_size


def call_sites(data, target):
    for offset, virtual, size in executable_segments(data):
        for relative in range(0, size - 3, 4):
            instruction = struct.unpack_from("<I", data, offset + relative)[0]
            if instruction >> 26 != 0b100101:
                continue
            immediate = instruction & 0x03FFFFFF
            if immediate & 0x02000000:
                immediate -= 0x04000000
            address = virtual + relative
            if address + immediate * 4 == target:
                yield address


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("elf", type=Path, help="AArch64 ELF file to inspect without modifying it")
    parser.add_argument("target", type=lambda value: int(value, 0), help="Target virtual address, e.g. 0x1945e34")
    args = parser.parse_args()
    try:
        for address in call_sites(args.elf.read_bytes(), args.target):
            print(f"0x{address:x}")
    except (OSError, ValueError, struct.error) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
