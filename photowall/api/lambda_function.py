"""The PhotoWall API: one Lambda function behind an API Gateway HTTP API (payload format 2.0).

  GET  /api/health                  -> ok
  GET  /api/photos                  -> the photos, newest first         (public, CloudFront may keep it LIST_TTL_SECONDS)
  POST /api/photos/{id}/like        -> adds one like                    (public, never cached)
  POST /api/upload-urls             -> two signed S3 addresses (photo and thumbnail) for one upload
  POST /api/photos                  -> saves the title of an uploaded photo

The last two need the header  X-Upload-Key  with the word you set in the function's settings (UPLOAD_KEY).
Settings (environment variables): TABLE (DynamoDB), BUCKET (S3), UPLOAD_KEY, LIST_TTL_SECONDS (default 30, 0 = never cache).

Every answer says in Cache-Control how long CloudFront may keep it: this is how the origin tells the cache what to do.
Only the list is cacheable. Everything else is "no-store".
"""
import base64
import datetime
import hmac
import json
import os
import re
import uuid
from decimal import Decimal

LIST_LIMIT = 100
MAX_PHOTO_BYTES = 3_000_000
MAX_THUMB_BYTES = 300_000
SIGNED_FOR_SECONDS = 300
MAX_BODY_BYTES = 10_000
IMMUTABLE = "public, max-age=31536000, immutable"   # a photo never changes under its name, so it may be kept for a year

LIKE_ID = re.compile(r"[A-Za-z0-9]{1,32}")
UPLOAD_ID = re.compile(r"[0-9a-f]{12}")


# ---------------------------------------------------------------------------------------------- AWS clients (made when first used)
def _table():
    import boto3
    return boto3.resource("dynamodb").Table(os.environ["TABLE"])


def _s3():
    import boto3
    from botocore.config import Config
    return boto3.client("s3", region_name=os.environ.get("AWS_REGION"),
                        config=Config(signature_version="s3v4", s3={"addressing_style": "virtual"}))


def _code(error):
    """The AWS error code of an exception, if it has one."""
    return getattr(error, "response", {}).get("Error", {}).get("Code", "")


# ---------------------------------------------------------------------------------------------- helpers
def _reply(status, body, cache_control="no-store"):
    return {"statusCode": status, "headers": {"Content-Type": "application/json", "Cache-Control": cache_control},
            "body": json.dumps(body, default=lambda v: int(v) if isinstance(v, Decimal) else str(v))}


def _list_ttl():
    text = os.environ.get("LIST_TTL_SECONDS", "30")
    return min(int(text), 300) if text.isdigit() else 30


def _body(event):
    raw = event.get("body") or ""
    if event.get("isBase64Encoded"):
        raw = base64.b64decode(raw).decode("utf-8", "replace")
    if len(raw) > MAX_BODY_BYTES:
        raise ValueError("The request is too big.")
    if not raw:
        return {}
    data = json.loads(raw)
    if not isinstance(data, dict):
        raise ValueError("The request must be a JSON object.")
    return data


def _check_key(event):
    """None when the upload key is right, otherwise the answer to send back."""
    expected = os.environ.get("UPLOAD_KEY", "")
    if not expected:
        return _reply(503, {"error": "UPLOAD_KEY is not set in the function's settings."})
    sent = (event.get("headers") or {}).get("x-upload-key", "")
    if not hmac.compare_digest(sent.encode("utf-8"), expected.encode("utf-8")):
        return _reply(403, {"error": "Wrong upload key."})
    return None


def _clean_title(text):
    cleaned = re.sub(r"[\x00-\x1f\x7f]", "", str(text or "")).strip()
    return cleaned[:60]


def _public(item):
    return {"id": item["id"], "title": item.get("title", ""), "likes": int(item.get("likes", 0)),
            "created_at": item.get("created_at", ""), "photo": "/photos/%s.jpg" % item["id"], "thumb": "/thumbs/%s.jpg" % item["id"]}


# ---------------------------------------------------------------------------------------------- the routes
def list_photos():
    items = _table().scan(Limit=LIST_LIMIT).get("Items", [])
    items.sort(key=lambda item: item.get("created_at", ""), reverse=True)
    ttl = _list_ttl()
    return _reply(200, {"photos": [_public(item) for item in items]}, "public, max-age=%d" % ttl if ttl else "no-store")


def like(photo_id):
    try:
        result = _table().update_item(Key={"id": photo_id}, UpdateExpression="ADD likes :one",
                                      ConditionExpression="attribute_exists(id)",
                                      ExpressionAttributeValues={":one": 1}, ReturnValues="UPDATED_NEW")
    except Exception as error:
        if _code(error) == "ConditionalCheckFailedException":
            return _reply(404, {"error": "No such photo."})
        raise
    return _reply(200, {"id": photo_id, "likes": int(result["Attributes"]["likes"])})


def upload_urls():
    bucket = os.environ["BUCKET"]
    photo_id = uuid.uuid4().hex[:12]
    headers = {"Content-Type": "image/jpeg", "Cache-Control": IMMUTABLE}

    def target(key):
        url = _s3().generate_presigned_url(
            "put_object", HttpMethod="PUT", ExpiresIn=SIGNED_FOR_SECONDS,
            Params={"Bucket": bucket, "Key": key, "ContentType": headers["Content-Type"], "CacheControl": headers["Cache-Control"]})
        return {"url": url, "headers": headers}

    return _reply(200, {"id": photo_id, "photo": target("photos/%s.jpg" % photo_id), "thumb": target("thumbs/%s.jpg" % photo_id)})


def save_photo(data):
    photo_id = str(data.get("id", ""))
    title = _clean_title(data.get("title"))
    if not UPLOAD_ID.fullmatch(photo_id):
        return _reply(400, {"error": "Bad photo id."})
    if not title:
        return _reply(400, {"error": "The title is empty."})

    bucket = os.environ["BUCKET"]
    s3 = _s3()
    keys = [("photos/%s.jpg" % photo_id, MAX_PHOTO_BYTES), ("thumbs/%s.jpg" % photo_id, MAX_THUMB_BYTES)]
    for key, limit in keys:
        try:
            head = s3.head_object(Bucket=bucket, Key=key)
        except Exception as error:
            if _code(error) in ("404", "NoSuchKey", "NotFound"):
                return _reply(400, {"error": "The upload is not complete: %s is missing." % key.split("/")[0]})
            raise
        if head.get("ContentType") != "image/jpeg" or head.get("ContentLength", 0) < 1 or head.get("ContentLength", 0) > limit:
            for bad_key, _ in keys:
                s3.delete_object(Bucket=bucket, Key=bad_key)
            return _reply(400, {"error": "The file is too big or not a JPEG."})

    item = {"id": photo_id, "title": title, "likes": 0,
            "created_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")}
    try:
        _table().put_item(Item=item, ConditionExpression="attribute_not_exists(id)")
    except Exception as error:
        if _code(error) == "ConditionalCheckFailedException":
            return _reply(409, {"error": "This photo is already saved."})
        raise
    return _reply(201, _public(item))


# ---------------------------------------------------------------------------------------------- the entry point
def lambda_handler(event, context):
    method = event.get("requestContext", {}).get("http", {}).get("method", "")
    path = event.get("rawPath", "")
    try:
        if method == "GET" and path == "/api/health":
            return _reply(200, {"status": "ok"})
        if method == "GET" and path == "/api/photos":
            return list_photos()
        match = re.fullmatch(r"/api/photos/([^/]+)/like", path)
        if method == "POST" and match and LIKE_ID.fullmatch(match.group(1)):
            return like(match.group(1))
        if method == "POST" and path in ("/api/upload-urls", "/api/photos"):
            denied = _check_key(event)
            if denied:
                return denied
            return upload_urls() if path == "/api/upload-urls" else save_photo(_body(event))
        return _reply(404, {"error": "Not found."})
    except ValueError as error:                       # a bad request body
        return _reply(400, {"error": str(error)})
    except Exception as error:                        # never show internals to the caller; the details go to the log
        print(json.dumps({"level": "ERROR", "method": method, "path": path, "error": type(error).__name__}))
        return _reply(500, {"error": "Something went wrong."})
