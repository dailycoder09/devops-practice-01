"""Runs the events Lambda on your own computer, to try the services without AWS.

    EVENT_SECRET=localsecret python3 local_server.py 9099

then start the services with  EVENTS_URL=http://localhost:9099/  EVENTS_SECRET=localsecret . Every event shows up in
this window as a log line. Without TOPIC_ARN nothing is published (log only), exactly like the function in AWS.
It is a test tool, not part of the deployment.
"""
import json
import logging
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

import handler

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stdout)


class AsFunctionUrl(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        event = {
            "requestContext": {"http": {"method": "POST"}},
            "headers": {k.lower(): v for k, v in self.headers.items()},
            "body": self.rfile.read(length).decode("utf-8"),
            "isBase64Encoded": False,
        }
        result = handler.lambda_handler(event, None)
        body = result["body"].encode("utf-8")
        self.send_response(result["statusCode"])
        for name, value in result["headers"].items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):    # the handler logs what matters
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9099
    print("events Lambda (local) on http://localhost:%d/" % port, flush=True)
    HTTPServer(("127.0.0.1", port), AsFunctionUrl).serve_forever()
