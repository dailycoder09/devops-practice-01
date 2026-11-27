#!/usr/bin/env python3
"""A tiny web server for the CloudFront lab (Lab 10): the "dynamic application" behind a load balancer.

GET /health               -> "ok" (the load balancer's health check)
GET /anything             -> JSON: the time, a unique id, this server's name and EVERY request header it received
GET /anything?ttl=20      -> the same, with "Cache-Control: max-age=20" (CloudFront may keep it 20 seconds); without ttl: no-store
GET /missing (or */missing) -> 404 with a JSON body (used to show what a custom error page does to an API)

It shows all request headers on purpose: that is how you see what CloudFront sends to an origin.
Only for the lab. Do not run it anywhere that is open to the internet for longer than the lab.
"""
import datetime
import json
import socket
import sys
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

MAX_TTL = 300


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body, content_type="application/json", cache_control="no-store"):
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", cache_control)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/health":
            return self._send(200, "ok", "text/plain")
        if url.path.endswith("/missing"):
            return self._send(404, json.dumps({"error": "not found", "path": url.path}))
        ttl = parse_qs(url.query).get("ttl", [""])[0]
        cache_control = "max-age=%d" % min(int(ttl), MAX_TTL) if ttl.isdigit() else "no-store"
        body = {
            "served_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
            "request_id": str(uuid.uuid4()),
            "server": socket.gethostname(),
            "path": url.path,
            "query": url.query,
            "cache_control_sent": cache_control,
            "headers": {name.lower(): value for name, value in self.headers.items()},
        }
        self._send(200, json.dumps(body, indent=1), cache_control=cache_control)

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.command, urlparse(self.path).path))   # no query, no headers in the log


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
