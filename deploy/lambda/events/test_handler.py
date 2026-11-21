"""Tests of the events Lambda. Run:  cd deploy/lambda/events && python3 -m unittest -v test_handler"""
import base64
import json
import os
import unittest
from unittest import mock

import handler

SECRET = "the-shared-secret"


class FakeSns:
    def __init__(self, fail=False):
        self.calls = []
        self.fail = fail

    def publish(self, **kwargs):
        if self.fail:
            raise RuntimeError("boom")
        self.calls.append(kwargs)


def call(body, secret=SECRET, method="POST", base64_body=False, sns=None):
    raw = body if isinstance(body, str) else json.dumps(body)
    event = {"requestContext": {"http": {"method": method}}, "headers": {}, "body": raw, "isBase64Encoded": False}
    if secret is not None:
        event["headers"]["X-Event-Secret"] = secret       # a Function URL lowercases header names; the handler copes with both
    if base64_body:
        event["body"] = base64.b64encode(raw.encode()).decode()
        event["isBase64Encoded"] = True
    return handler.lambda_handler(event, None, sns=sns)


USER = {"type": "USER_REGISTERED", "source": "user-service", "occurredAt": "2026-11-21T10:00:00Z",
        "data": {"userId": 42, "email": "ann@example.com", "role": "USER"}}
ORDER = {"type": "ORDER_PLACED", "source": "order-service", "data": {"orderId": 7, "userId": 42, "email": "ann@example.com",
                                                                      "total": 22.5, "itemCount": 2, "status": "CONFIRMED"}}
STOCK = {"type": "STOCK_EMPTY", "source": "inventory-service", "data": {"sku": "APPLE-IP15P-128", "reason": "reserved", "orderId": 7}}


class HandlerTest(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.dict(os.environ, {"EVENT_SECRET": SECRET, "TOPIC_ARN": "arn:aws:sns:us-west-2:111122223333:microshop-events"}, clear=False)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_a_user_registered_event_is_published_with_a_readable_message(self):
        sns = FakeSns()
        result = call(USER, sns=sns)
        self.assertEqual(result["statusCode"], 200)
        self.assertEqual(len(sns.calls), 1)
        self.assertEqual(sns.calls[0]["TopicArn"], "arn:aws:sns:us-west-2:111122223333:microshop-events")
        self.assertIn("new user registered", sns.calls[0]["Subject"])
        self.assertIn("ann@example.com", sns.calls[0]["Message"])
        self.assertIn("42", sns.calls[0]["Message"])

    def test_an_order_placed_event_says_the_order_the_buyer_and_the_total(self):
        sns = FakeSns()
        self.assertEqual(call(ORDER, sns=sns)["statusCode"], 200)
        self.assertIn("order #7 placed", sns.calls[0]["Subject"])
        self.assertIn("ann@example.com", sns.calls[0]["Message"])
        self.assertIn("22.5", sns.calls[0]["Message"])

    def test_a_stock_empty_event_says_which_sku(self):
        sns = FakeSns()
        self.assertEqual(call(STOCK, sns=sns)["statusCode"], 200)
        self.assertIn("APPLE-IP15P-128", sns.calls[0]["Subject"])
        self.assertIn("ran out of stock", sns.calls[0]["Message"])

    def test_a_wrong_secret_is_refused_and_nothing_is_published(self):
        sns = FakeSns()
        self.assertEqual(call(USER, secret="not-it", sns=sns)["statusCode"], 401)
        self.assertEqual(sns.calls, [])

    def test_a_missing_secret_is_refused(self):
        sns = FakeSns()
        self.assertEqual(call(USER, secret=None, sns=sns)["statusCode"], 401)
        self.assertEqual(sns.calls, [])

    def test_a_function_without_a_secret_refuses_everything(self):
        with mock.patch.dict(os.environ, {"EVENT_SECRET": ""}):
            sns = FakeSns()
            self.assertEqual(call(USER, secret="", sns=sns)["statusCode"], 500)
            self.assertEqual(sns.calls, [])

    def test_only_post_is_accepted(self):
        self.assertEqual(call(USER, method="GET", sns=FakeSns())["statusCode"], 405)

    def test_unknown_types_and_bad_bodies_are_400(self):
        sns = FakeSns()
        self.assertEqual(call({"type": "SOMETHING_ELSE", "data": {}}, sns=sns)["statusCode"], 400)
        self.assertEqual(call("this is not json", sns=sns)["statusCode"], 400)
        self.assertEqual(call("[1,2,3]", sns=sns)["statusCode"], 400)
        self.assertEqual(call({"type": "USER_REGISTERED", "data": "text"}, sns=sns)["statusCode"], 400)
        self.assertEqual(sns.calls, [])

    def test_a_body_that_is_too_large_is_413(self):
        big = {"type": "USER_REGISTERED", "data": {"email": "x" * (70 * 1024)}}
        self.assertEqual(call(big, sns=FakeSns())["statusCode"], 413)

    def test_a_base64_body_is_understood(self):
        sns = FakeSns()
        self.assertEqual(call(USER, base64_body=True, sns=sns)["statusCode"], 200)
        self.assertEqual(len(sns.calls), 1)

    def test_without_a_topic_the_event_is_only_logged_and_still_ok(self):
        with mock.patch.dict(os.environ, {"TOPIC_ARN": ""}):
            sns = FakeSns()
            with self.assertLogs(level="INFO") as logs:
                result = call(USER, sns=sns)
            self.assertEqual(result["statusCode"], 200)
            self.assertEqual(sns.calls, [])
            self.assertTrue(any("EVENT USER_REGISTERED" in line for line in logs.output))

    def test_when_sns_fails_the_answer_is_502_and_the_secret_is_not_logged(self):
        with self.assertLogs(level="INFO") as logs:
            result = call(USER, sns=FakeSns(fail=True))
        self.assertEqual(result["statusCode"], 502)
        self.assertFalse(any(SECRET in line for line in logs.output))
        self.assertNotIn(SECRET, json.dumps(result))

    def test_line_breaks_in_a_value_cannot_forge_extra_lines(self):
        sns = FakeSns()
        evil = dict(USER, data={"userId": 1, "email": "a@b.c\nSubject: forged\r\nBcc: x@y.z"})
        self.assertEqual(call(evil, sns=sns)["statusCode"], 200)
        self.assertNotIn("\r", sns.calls[0]["Message"])
        self.assertEqual(sns.calls[0]["Message"].count("\n"), 2)    # only the 3 lines the handler writes itself


if __name__ == "__main__":
    unittest.main()
