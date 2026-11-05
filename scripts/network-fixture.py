#!/usr/bin/env python3
"""The test app's server for network checks (docs/network.md "Checks"): HTTPS on 127.0.0.1 with a throwaway
certificate for api.apksim.test, which `apksim --network record` reaches with
`--network-host api.apksim.test=127.0.0.1:PORT --network-trust DIR/cert.pem`.

  scripts/network-fixture.py DIR [PORT]   writes DIR/cert.pem and DIR/key.pem (if missing), prints "ready PORT"
  scripts/network-fixture.py DIR --cert-only   only writes them (with openssl, which the Linux image lacks)

POST /v1/greeting {"name": N} -> {"greeting": "Hello, N!", ...} (gzip when asked); GET /v1/plain -> a plain greeting;
anything else 404. Every request must carry an Authorization header (401 without), so a recording has one to redact.
"""
import gzip
import http.server
import json
import os
import ssl
import subprocess
import sys

DIR = sys.argv[1]
CERT_ONLY = sys.argv[2:3] == ["--cert-only"]
PORT = int(sys.argv[2]) if len(sys.argv) > 2 and not CERT_ONLY else 0
CERT, KEY = os.path.join(DIR, "cert.pem"), os.path.join(DIR, "key.pem")
os.makedirs(DIR, exist_ok=True)
if not os.path.exists(CERT):
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", KEY, "-out", CERT,
                    "-days", "3650", "-subj", "/CN=api.apksim.test",
                    "-addext", "subjectAltName=DNS:api.apksim.test"], check=True, capture_output=True)
if CERT_ONLY:
    sys.exit(0)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def send(self, code, obj, compress=False):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Set-Cookie", "session=fixture-secret; Path=/; HttpOnly")
        if compress and "gzip" in self.headers.get("Accept-Encoding", ""):
            body = gzip.compress(body)
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorized(self):
        if self.headers.get("Authorization", "").startswith("Bearer "):
            return True
        self.send(401, {"error": "no Authorization header"})
        return False

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        data = json.loads(self.rfile.read(n) or b"{}")
        if not self.authorized():
            return
        if self.path == "/v1/greeting":
            self.send(200, {"greeting": f"Hello, {data.get('name', 'you')}!", "served": "fixture"}, compress=True)
        else:
            self.send(404, {"error": "not found", "path": self.path})

    def do_GET(self):
        if not self.authorized():
            return
        if self.path == "/v1/plain":
            self.send(200, {"greeting": "Plain hello", "served": "fixture"})
        else:
            self.send(404, {"error": "not found", "path": self.path})

    def log_message(self, fmt, *args):
        sys.stderr.write("[fixture] " + fmt % args + "\n")


server = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(CERT, KEY)
server.socket = ctx.wrap_socket(server.socket, server_side=True)
print(f"ready {server.server_address[1]}", flush=True)
server.serve_forever()
