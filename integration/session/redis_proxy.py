"""Loopback RESP2 proxy for deterministic command/response barriers; never logs payloads."""
import collections
import socket
import socketserver
import threading


def frame(stream):
    line = stream.readline()
    if not line:
        raise EOFError()
    if line[:1] == b"*":
        frames = [frame(stream) for _ in range(int(line[1:]))]
        return line + b"".join(raw for raw, _ in frames), [value for _, value in frames]
    if line[:1] == b"$":
        size = int(line[1:])
        if size < 0:
            return line, None
        data = stream.read(size + 2)
        if len(data) != size + 2:
            raise EOFError()
        return line + data, data[:-2]
    return line, line[1:-2]


class RedisProxy:
    def __init__(self, destination):
        self.counts = collections.Counter()
        self.lock = threading.Lock()
        self.hook = None
        owner = self

        class Handler(socketserver.StreamRequestHandler):
            def handle(self):
                try:
                    with socket.create_connection(("127.0.0.1", destination), timeout=5) as upstream:
                        stream = upstream.makefile("rb")
                        while True:
                            raw, parts = frame(self.rfile)
                            command = parts[0].decode().upper()
                            with owner.lock:
                                owner.counts[command] += 1
                                hook = owner.hook if owner.hook and owner.hook[0] == command else None
                                if hook:
                                    owner.hook = None
                            upstream.sendall(raw)
                            response, _ = frame(stream)
                            if hook:
                                hook[1]()
                            self.wfile.write(response)
                            self.wfile.flush()
                except (OSError, EOFError):
                    pass

        class Server(socketserver.ThreadingTCPServer):
            daemon_threads = True
            allow_reuse_address = True
        self.server = Server(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def after(self, command, action):
        with self.lock:
            if self.hook:
                raise AssertionError("unconsumed Redis barrier")
            self.hook = (command, action)

    def close(self):
        self.server.shutdown()
        self.server.server_close()
