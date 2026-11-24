#!/usr/bin/env python3
"""
Mockup server for the vitals app.

Serves the static web client on the LAN and proxies API calls to a real vitals
agent, enforcing the certificate pin from the QR payload on every request.

Why a proxy: a browser cannot pin a certificate, cannot use a self-signed cert
without warnings, and the agent sends no CORS headers. The phone talks plain
HTTP to this process; this process talks HTTPS with a verified pin upstream.

The proxy is not authenticated: it exposes the host metrics to anyone who can
reach this port. Run it on your LAN, only while you are testing, and stop it
afterwards. For camera access (getUserMedia) serve it over HTTPS:

    tailscale serve --bg http://127.0.0.1:8080

Then open https://<host>.<tailnet>.ts.net/ on the phone.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import http.client
import ipaddress
import json
import mimetypes
import os
import socket
import ssl
import sys
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PIN_BYTES = 16
API_PREFIX = "/api/"

TLS_CONTEXT = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
TLS_CONTEXT.check_hostname = False
TLS_CONTEXT.verify_mode = ssl.CERT_NONE

WEB_DIR = Path(__file__).resolve().parent / "web"


def pin_of(der: bytes) -> str:
    digest = hashlib.sha256(der).digest()[:PIN_BYTES]
    return base64.urlsafe_b64encode(digest).rstrip(b"=").decode()


def allowed_host(host: str) -> bool:
    """Only loopback, RFC1918, CGNAT and tailnet names reachable."""
    if not host:
        return False
    if host in ("localhost",) or host.endswith(".ts.net") or host.endswith(".local"):
        return True
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror:
        return False
    for info in infos:
        addr = info[4][0]
        try:
            ip = ipaddress.ip_address(addr)
        except ValueError:
            return False
        if ip.is_loopback or ip.is_private:
            continue
        if ip.version == 4 and ipaddress.ip_address("100.64.0.0") <= ip <= ipaddress.ip_address("100.127.255.255"):
            continue
        return False
    return True


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "vitals-mockup"

    # ----------------------------- rutas -----------------------------
    def do_GET(self) -> None:
        if self.path.startswith(API_PREFIX):
            self.proxy("GET")
        else:
            self.static()

    def do_POST(self) -> None:
        if self.path.startswith(API_PREFIX):
            self.proxy("POST")
        else:
            self.send_json({"error": "not found"}, 404)

    # ----------------------------- estático --------------------------
    def static(self) -> None:
        path = urllib.parse.urlsplit(self.path).path
        if path in ("", "/"):
            path = "/index.html"
        target = (WEB_DIR / path.lstrip("/")).resolve()
        if not str(target).startswith(str(WEB_DIR)) or not target.is_file():
            self.send_json({"error": "not found"}, 404)
            return
        data = target.read_bytes()
        ctype = mimetypes.guess_type(str(target))[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype in ("application/javascript", "application/json"):
            ctype += "; charset=utf-8"
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    # ------------------------------ proxy ----------------------------
    def proxy(self, method: str) -> None:
        upstream = self.headers.get("X-Vitals-Upstream", "")
        pin = self.headers.get("X-Vitals-Pin", "")
        if not upstream or not pin:
            self.send_json({"error": "missing X-Vitals-Upstream or X-Vitals-Pin"}, 400)
            return

        parts = urllib.parse.urlsplit(upstream)
        if parts.scheme != "https" or not parts.hostname:
            self.send_json({"error": "upstream must be an https URL"}, 400)
            return
        if not allowed_host(parts.hostname):
            self.send_json({"error": f"upstream host not allowed: {parts.hostname}"}, 403)
            return

        body = None
        if method == "POST":
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else b""

        conn = None
        try:
            conn = http.client.HTTPSConnection(
                parts.hostname, parts.port or 443, context=TLS_CONTEXT, timeout=30
            )
            conn.connect()
            der = conn.sock.getpeercert(binary_form=True)
            actual = pin_of(der)
            if not hmac.compare_digest(actual, pin):
                self.send_json(
                    {
                        "error": "certificate pin mismatch",
                        "expected": pin,
                        "actual": actual,
                    },
                    403,
                )
                return

            headers = {}
            auth = self.headers.get("Authorization")
            if auth:
                headers["Authorization"] = auth
            if body is not None:
                headers["Content-Type"] = "application/json"

            conn.request(method, self.path, body=body, headers=headers)
            resp = conn.getresponse()
            ctype = resp.getheader("Content-Type", "application/json")

            if "text/event-stream" in ctype:
                self.stream(resp)
                return

            data = resp.read()
            self.send_response(resp.status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)
        except (OSError, http.client.HTTPException) as exc:
            self.send_json({"error": f"upstream unreachable: {exc}"}, 502)
        finally:
            if conn is not None:
                conn.close()

    def stream(self, resp) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("X-Accel-Buffering", "no")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        try:
            while True:
                chunk = resp.read(4096)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass

    # ------------------------------ utils ----------------------------
    def send_json(self, payload: dict, status: int = 200) -> None:
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt: str, *args) -> None:
        if self.command in ("GET", "POST") and self.path.startswith(API_PREFIX):
            return  # keep API noise out of the terminal
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


def local_urls(port: int) -> list[str]:
    urls = []
    try:
        infos = socket.getaddrinfo(socket.gethostname(), port, socket.AF_INET)
        for info in infos:
            addr = info[4][0]
            if addr.startswith("127.") or addr.startswith("169.254."):
                continue
            url = f"http://{addr}:{port}/"
            if url not in urls:
                urls.append(url)
    except socket.gaierror:
        pass
    return urls


def main() -> None:
    parser = argparse.ArgumentParser(description="vitals mockup server")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--bind", default="0.0.0.0")
    args = parser.parse_args()

    if not (WEB_DIR / "index.html").is_file():
        sys.exit(f"missing {WEB_DIR / 'index.html'}")

    httpd = ThreadingHTTPServer((args.bind, args.port), Handler)
    httpd.daemon_threads = True

    print(f"vitals mockup — sirviendo {WEB_DIR}")
    for url in local_urls(args.port):
        print(f"  en la LAN:  {url}")
    print(f"  local:      http://127.0.0.1:{args.port}/")
    print()
    print("La cámara del móvil exige HTTPS. Para escanear el QR desde la web:")
    print(f"  tailscale serve --bg http://127.0.0.1:{args.port}")
    print("  y abre https://<host>.<tu-tailnet>.ts.net/ en el móvil")
    print()
    sys.stdout.flush()

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\ncerrando")
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
