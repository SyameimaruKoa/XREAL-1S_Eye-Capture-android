import socket
import struct
import subprocess
import sys
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
