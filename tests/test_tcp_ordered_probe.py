import socket
import importlib.util
import contextlib
import io
import struct
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "tcp_ordered_probe.py"


def read_exact(sock, size):
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise EOFError("client disconnected")
        data.extend(chunk)
    return bytes(data)


class OrderedProbeTest(unittest.TestCase):
    def test_video_access_unit_capture(self):
        spec = importlib.util.spec_from_file_location("ordered_probe", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        unit = b"\x00\x00\x00\x01\x02\x01\x7f"
        body = bytes(97) + unit
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "capture.hevc"
            reader, writer = socket.socketpair()
            with reader, writer:
                writer.sendall(struct.pack(">HI", 0x2785, len(body)) + body)
                module.receive_video(reader, output, 0.1)
            self.assertEqual(output.read_bytes(), unit)

    def test_video_reports_camera_timestamp_candidates(self):
        spec = importlib.util.spec_from_file_location("ordered_probe", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        unit = b"\x00\x00\x00\x01\x02\x01\x7f"
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "capture.hevc"
            reader, writer = socket.socketpair()
            with reader, writer, contextlib.redirect_stdout(io.StringIO()) as captured:
                for timestamp in (1000000000, 1033333333, 1066666666):
                    header = bytearray(97)
                    struct.pack_into("<q", header, 24, timestamp)
                    body = header + unit
                    writer.sendall(struct.pack(">HI", 0x2785, len(body)) + body)
                module.receive_video(reader, output, 0.1)
            self.assertIn("non_increasing=0 candidate_fps=30.000", captured.getvalue())
            self.assertEqual(output.read_bytes(), unit * 3)

    def test_cleanup_after_error(self):
        received = []
        with socket.socket() as server:
            server.bind(("127.0.0.1", 0))
            server.listen(1)
            port = server.getsockname()[1]

            def reply():
                with server.accept()[0] as conn:
                    for body in (b"\x22\x03\x08\x89\x27", b"\x22\x00"):
                        command, length = struct.unpack(">HI", read_exact(conn, 6))
                        request = read_exact(conn, length)
                        received.append(command)
                        payload = b"\x00\x00" + request[2:4] + body
                        conn.sendall(struct.pack(">HI", command, len(payload)) + payload)

            thread = threading.Thread(target=reply)
            thread.start()
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--host", "127.0.0.1", "--port", str(port),
                 "--step", "2780:1a00", "--step", "2781:1a00",
                 "--cleanup", "2783:1a00", "--send"],
                capture_output=True, text=True, timeout=5, check=False)
            thread.join(timeout=2)
        self.assertFalse(thread.is_alive())
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(received, [0x2780, 0x2783])
        self.assertIn("cleanup reply", result.stdout)


if __name__ == "__main__":
    unittest.main()
