"""The tiny API of the CloudFront lab (an AWS Lambda function with a Function URL).

GET /api/time           -> the time, a unique id, and "Cache-Control: no-store" (CloudFront must not keep it)
GET /api/time?ttl=20    -> the same, but with "Cache-Control: max-age=20" (CloudFront may keep it for 20 seconds)
GET /api/time?sleep=3   -> waits 3 seconds before it answers (at most 10): used in Lab 8 to cause a 504 timeout on purpose

The answer shows which Host header the function received: through CloudFront it must be the Function URL's own host name,
which is why the origin request policy of the /api/* behavior must NOT forward the viewer's Host header (Lab 2).
"""
import datetime
import json
import time
import uuid

MAX_TTL = 300
MAX_SLEEP = 10


def lambda_handler(event, context):
    query = event.get("queryStringParameters") or {}
    ttl_text = str(query.get("ttl", ""))
    if ttl_text.isdigit():
        ttl = min(int(ttl_text), MAX_TTL)
        cache_control = "max-age=%d" % ttl
    else:
        cache_control = "no-store"

    sleep_text = str(query.get("sleep", ""))
    slept = min(int(sleep_text), MAX_SLEEP) if sleep_text.isdigit() else 0
    if slept:
        time.sleep(slept)

    headers = event.get("headers") or {}
    body = {
        "served_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "request_id": str(uuid.uuid4()),
        "path": event.get("rawPath", ""),
        "query": event.get("rawQueryString", ""),
        "host_header_seen_by_function": headers.get("host", ""),
        "cache_control_sent": cache_control,
        "slept_seconds": slept,
    }
    return {
        "statusCode": 200,
        "headers": {"Content-Type": "application/json", "Cache-Control": cache_control},
        "body": json.dumps(body),
    }
