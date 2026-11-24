import json
import unittest
from unittest import mock

from lambda_function import lambda_handler


def call(query=None, raw_query="", host="abc.lambda-url.us-west-2.on.aws"):
    event = {"rawPath": "/api/time", "rawQueryString": raw_query, "queryStringParameters": query, "headers": {"host": host}}
    return lambda_handler(event, None)


class LambdaTest(unittest.TestCase):
    def test_default_is_not_cacheable(self):
        response = call()
        self.assertEqual(200, response["statusCode"])
        self.assertEqual("no-store", response["headers"]["Cache-Control"])

    def test_ttl_sets_max_age(self):
        self.assertEqual("max-age=20", call({"ttl": "20"}, "ttl=20")["headers"]["Cache-Control"])

    def test_ttl_is_limited_to_300(self):
        self.assertEqual("max-age=300", call({"ttl": "99999"})["headers"]["Cache-Control"])

    def test_bad_ttl_is_ignored(self):
        for bad in ("abc", "-5", "1.5", ""):
            self.assertEqual("no-store", call({"ttl": bad})["headers"]["Cache-Control"], bad)

    def test_every_answer_has_a_new_id_and_shows_the_host(self):
        first, second = json.loads(call()["body"]), json.loads(call()["body"])
        self.assertNotEqual(first["request_id"], second["request_id"])
        self.assertEqual("abc.lambda-url.us-west-2.on.aws", first["host_header_seen_by_function"])

    def test_sleep_waits_and_is_limited_to_10_seconds(self):
        with mock.patch("lambda_function.time.sleep") as sleeper:
            body = json.loads(call({"sleep": "3"})["body"])
            self.assertEqual(3, body["slept_seconds"])
            sleeper.assert_called_once_with(3)
        with mock.patch("lambda_function.time.sleep") as sleeper:
            json.loads(call({"sleep": "500"})["body"])
            sleeper.assert_called_once_with(10)

    def test_no_sleep_by_default_or_with_bad_value(self):
        with mock.patch("lambda_function.time.sleep") as sleeper:
            call()
            call({"sleep": "abc"})
            sleeper.assert_not_called()


if __name__ == "__main__":
    unittest.main()
