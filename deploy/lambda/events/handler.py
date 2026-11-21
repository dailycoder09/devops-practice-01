"""MicroShop events: receives "a user registered", "an order was placed" and "a SKU ran out" from the services
and turns each into a readable message.

AWS Lambda, runtime Python 3.12, handler  handler.lambda_handler , reached through a Function URL
(auth type NONE: the secret below is the protection).

Settings (Lambda > Configuration > Environment variables):
  EVENT_SECRET  required. The same value as EVENTS_SECRET in the user data of the VM. Calls without it are refused (401).
  TOPIC_ARN     optional. An SNS topic: every event is published to it, and the people subscribed (your e-mail) get
                it. Without it the event is only written to the log (CloudWatch Logs).
"""
import base64
import hmac
import json
import logging
import os
import re

logger = logging.getLogger()
logger.setLevel(logging.INFO)

KNOWN_EVENTS = {"USER_REGISTERED", "ORDER_PLACED", "STOCK_EMPTY"}
MAX_BODY_BYTES = 64 * 1024
_CONTROL = re.compile(r"[\x00-\x1f\x7f]")


def _reply(status, body):
    return {"statusCode": status, "headers": {"Content-Type": "application/json"}, "body": json.dumps(body)}


def _clean(value, limit=200):
    """A value from an event, as short plain text (no line breaks or other control characters)."""
    return _CONTROL.sub(" ", str(value))[:limit]


def build_message(event_type, data):
    """The subject and the text of the notification for one event."""
    if event_type == "USER_REGISTERED":
        return ("MicroShop: new user registered",
                "A new user registered.\nEmail: %s\nUser id: %s" % (_clean(data.get("email", "?")), _clean(data.get("userId", "?"))))
    if event_type == "ORDER_PLACED":
        return ("MicroShop: order #%s placed" % _clean(data.get("orderId", "?"), 20),
                "Order #%s was placed.\nBy: %s (user id %s)\nTotal: %s\nItems: %s"
                % (_clean(data.get("orderId", "?"), 20), _clean(data.get("email", "?")), _clean(data.get("userId", "?")),
                   _clean(data.get("total", "?"), 30), _clean(data.get("itemCount", "?"), 10)))
    return ("MicroShop: out of stock %s" % _clean(data.get("sku", "?"), 40),
            "The SKU %s ran out of stock (%s)." % (_clean(data.get("sku", "?"), 40), _clean(data.get("reason", "?"), 30)))


def lambda_handler(event, context=None, sns=None):
    """Function URL event (payload format 2.0) in, HTTP-style answer out. `sns` is only for tests."""
    method = ((event.get("requestContext") or {}).get("http") or {}).get("method", "POST")
    if method != "POST":
        return _reply(405, {"error": "Use POST"})

    expected = os.environ.get("EVENT_SECRET", "")
    if not expected:
        logger.error("EVENT_SECRET is not set on this function: every call is refused")
        return _reply(500, {"error": "Not configured"})
    headers = {str(k).lower(): v for k, v in (event.get("headers") or {}).items()}
    received = headers.get("x-event-secret", "")
    if not hmac.compare_digest(received.encode("utf-8"), expected.encode("utf-8")):
        logger.warning("Refused a call with a wrong or missing secret")
        return _reply(401, {"error": "Unauthorized"})

    raw = event.get("body") or ""
    if event.get("isBase64Encoded"):
        try:
            raw = base64.b64decode(raw).decode("utf-8")
        except (ValueError, UnicodeDecodeError):
            return _reply(400, {"error": "Bad body"})
    if len(raw.encode("utf-8")) > MAX_BODY_BYTES:
        return _reply(413, {"error": "Body too large"})
    try:
        body = json.loads(raw)
    except ValueError:
        return _reply(400, {"error": "The body is not JSON"})
    if not isinstance(body, dict) or body.get("type") not in KNOWN_EVENTS or not isinstance(body.get("data", {}), dict):
        return _reply(400, {"error": "Unknown event"})

    event_type = body["type"]
    subject, message = build_message(event_type, body.get("data", {}))
    logger.info("EVENT %s | %s | source=%s traceId=%s",
                event_type, message.replace("\n", " | "), _clean(body.get("source", "?"), 40), _clean(body.get("traceId", "-"), 40))

    topic = os.environ.get("TOPIC_ARN", "")
    if topic:
        try:
            if sns is None:
                import boto3  # only needed when a topic is set; present in the Lambda runtime
                sns = boto3.client("sns")
            sns.publish(TopicArn=topic, Subject=subject[:100], Message=message)
        except Exception as error:  # the caller gets 502 and logs a warning; the event is not retried
            logger.error("SNS publish failed: %s", type(error).__name__)
            return _reply(502, {"error": "Could not publish"})
    return _reply(200, {"ok": True})
