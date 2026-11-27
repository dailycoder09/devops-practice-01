import json
import os
import unittest
from decimal import Decimal
from unittest import mock

import lambda_function as api


class AwsError(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.response = {"Error": {"Code": code}}


class FakeTable:
    def __init__(self, items=None):
        self.items = {item["id"]: dict(item) for item in (items or [])}

    def scan(self, Limit):
        return {"Items": [dict(item) for item in list(self.items.values())[:Limit]]}

    def update_item(self, Key, ConditionExpression, **kwargs):
        if Key["id"] not in self.items:
            raise AwsError("ConditionalCheckFailedException")
        item = self.items[Key["id"]]
        item["likes"] = Decimal(item.get("likes", 0)) + 1
        return {"Attributes": {"likes": item["likes"]}}

    def put_item(self, Item, ConditionExpression):
        if Item["id"] in self.items:
            raise AwsError("ConditionalCheckFailedException")
        self.items[Item["id"]] = dict(Item)


class FakeS3:
    def __init__(self, objects=None):
        self.objects = dict(objects or {})
        self.deleted = []
        self.signed = []

    def generate_presigned_url(self, op, Params, ExpiresIn, HttpMethod):
        self.signed.append((op, Params, ExpiresIn, HttpMethod))
        return "https://%s.s3.example/%s?signature=x" % (Params["Bucket"], Params["Key"])

    def head_object(self, Bucket, Key):
        if Key not in self.objects:
            raise AwsError("404")
        return dict(self.objects[Key])

    def delete_object(self, Bucket, Key):
        self.deleted.append(Key)
        self.objects.pop(Key, None)


def event(method, path, body=None, key=None):
    headers = {"x-upload-key": key} if key is not None else {}
    return {"requestContext": {"http": {"method": method}}, "rawPath": path, "headers": headers,
            "body": json.dumps(body) if body is not None else None}


def jpeg(size=1000):
    return {"ContentType": "image/jpeg", "ContentLength": size}


class ApiTest(unittest.TestCase):
    def setUp(self):
        self.table = FakeTable([
            {"id": "old", "title": "Old", "likes": Decimal(2), "created_at": "2026-01-01T00:00:00Z"},
            {"id": "new", "title": "New", "created_at": "2026-02-01T00:00:00Z"},
        ])
        self.s3 = FakeS3()
        env = {"TABLE": "t", "BUCKET": "b", "UPLOAD_KEY": "secret-word", "LIST_TTL_SECONDS": "30"}
        patches = [mock.patch.dict(os.environ, env), mock.patch.object(api, "_table", lambda: self.table), mock.patch.object(api, "_s3", lambda: self.s3)]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)

    def call(self, *args, **kwargs):
        response = api.lambda_handler(event(*args, **kwargs), None)
        return response["statusCode"], json.loads(response["body"]), response["headers"]

    # ---- read
    def test_health(self):
        self.assertEqual(200, self.call("GET", "/api/health")[0])

    def test_list_is_newest_first_and_cacheable_for_the_ttl(self):
        status, body, headers = self.call("GET", "/api/photos")
        self.assertEqual(200, status)
        self.assertEqual(["new", "old"], [p["id"] for p in body["photos"]])
        self.assertEqual([0, 2], [p["likes"] for p in body["photos"]])
        self.assertEqual("/thumbs/new.jpg", body["photos"][0]["thumb"])
        self.assertEqual("public, max-age=30", headers["Cache-Control"])

    def test_list_ttl_is_a_setting_and_zero_means_no_cache(self):
        with mock.patch.dict(os.environ, {"LIST_TTL_SECONDS": "0"}):
            self.assertEqual("no-store", self.call("GET", "/api/photos")[2]["Cache-Control"])
        with mock.patch.dict(os.environ, {"LIST_TTL_SECONDS": "9999"}):
            self.assertEqual("public, max-age=300", self.call("GET", "/api/photos")[2]["Cache-Control"])
        with mock.patch.dict(os.environ, {"LIST_TTL_SECONDS": "abc"}):
            self.assertEqual("public, max-age=30", self.call("GET", "/api/photos")[2]["Cache-Control"])

    # ---- likes
    def test_like_adds_one_and_is_never_cached(self):
        status, body, headers = self.call("POST", "/api/photos/old/like")
        self.assertEqual((200, 3), (status, body["likes"]))
        self.assertEqual("no-store", headers["Cache-Control"])
        self.assertEqual(1, self.call("POST", "/api/photos/new/like")[1]["likes"])

    def test_like_of_a_missing_photo_is_404(self):
        self.assertEqual(404, self.call("POST", "/api/photos/nothing/like")[0])

    def test_like_with_a_bad_id_or_method_is_404(self):
        self.assertEqual(404, self.call("POST", "/api/photos/a-b/like")[0])
        self.assertEqual(404, self.call("GET", "/api/photos/old/like")[0])

    # ---- upload
    def test_upload_needs_the_right_key(self):
        self.assertEqual(403, self.call("POST", "/api/upload-urls")[0])
        self.assertEqual(403, self.call("POST", "/api/upload-urls", key="wrong")[0])
        self.assertEqual(403, self.call("POST", "/api/photos", {"id": "a" * 12, "title": "x"}, key="")[0])

    def test_upload_is_off_when_no_key_is_set(self):
        with mock.patch.dict(os.environ, {"UPLOAD_KEY": ""}):
            self.assertEqual(503, self.call("POST", "/api/upload-urls", key="")[0])

    def test_upload_urls_are_two_signed_puts_with_the_cache_header(self):
        status, body, headers = self.call("POST", "/api/upload-urls", key="secret-word")
        self.assertEqual(200, status)
        self.assertEqual("no-store", headers["Cache-Control"])
        self.assertRegex(body["id"], r"^[0-9a-f]{12}$")
        self.assertIn("photos/%s.jpg" % body["id"], body["photo"]["url"])
        self.assertIn("thumbs/%s.jpg" % body["id"], body["thumb"]["url"])
        self.assertEqual("image/jpeg", body["photo"]["headers"]["Content-Type"])
        self.assertIn("immutable", body["photo"]["headers"]["Cache-Control"])
        self.assertEqual(2, len(self.s3.signed))
        self.assertTrue(all(op == "put_object" and valid == 300 and method == "PUT" for op, _, valid, method in self.s3.signed))

    def test_save_a_photo_after_both_files_arrived(self):
        pid = "0123456789ab"
        self.s3.objects = {"photos/%s.jpg" % pid: jpeg(200_000), "thumbs/%s.jpg" % pid: jpeg(20_000)}
        status, body, _ = self.call("POST", "/api/photos", {"id": pid, "title": "  My \x07 photo  "}, key="secret-word")
        self.assertEqual(201, status)
        self.assertEqual("My  photo", body["title"])
        self.assertIn(pid, self.table.items)
        self.assertEqual(409, self.call("POST", "/api/photos", {"id": pid, "title": "again"}, key="secret-word")[0])

    def test_save_fails_when_a_file_is_missing(self):
        pid = "0123456789ab"
        self.s3.objects = {"photos/%s.jpg" % pid: jpeg()}
        status, body, _ = self.call("POST", "/api/photos", {"id": pid, "title": "x"}, key="secret-word")
        self.assertEqual(400, status)
        self.assertIn("thumbs", body["error"])
        self.assertNotIn(pid, self.table.items)

    def test_save_deletes_files_that_are_too_big_or_not_jpeg(self):
        pid = "0123456789ab"
        self.s3.objects = {"photos/%s.jpg" % pid: jpeg(5_000_000), "thumbs/%s.jpg" % pid: jpeg(1000)}
        self.assertEqual(400, self.call("POST", "/api/photos", {"id": pid, "title": "x"}, key="secret-word")[0])
        self.assertCountEqual(["photos/%s.jpg" % pid, "thumbs/%s.jpg" % pid], self.s3.deleted)

    def test_save_checks_id_and_title(self):
        for body in ({"id": "short", "title": "x"}, {"id": "0123456789ab", "title": "   "}, {"title": "x"}):
            self.assertEqual(400, self.call("POST", "/api/photos", body, key="secret-word")[0], body)

    def test_bad_json_is_400_and_unknown_routes_404(self):
        bad = {"requestContext": {"http": {"method": "POST"}}, "rawPath": "/api/photos", "headers": {"x-upload-key": "secret-word"}, "body": "{not json"}
        self.assertEqual(400, api.lambda_handler(bad, None)["statusCode"])
        self.assertEqual(404, self.call("GET", "/api/nothing")[0])
        self.assertEqual(404, self.call("DELETE", "/api/photos")[0])

    def test_unexpected_errors_show_nothing_internal(self):
        with mock.patch.object(api, "_table", side_effect=RuntimeError("password=hunter2")):
            status, body, _ = self.call("GET", "/api/photos")
        self.assertEqual(500, status)
        self.assertNotIn("hunter2", json.dumps(body))


if __name__ == "__main__":
    unittest.main()
