import os
import unittest

import seed_table


class SeedTest(unittest.TestCase):
    def test_items_have_only_the_stored_fields(self):
        items = seed_table.items_from_file()
        self.assertEqual(8, len(items))
        self.assertEqual({"id", "title", "likes", "created_at"}, set(items[0]))
        self.assertEqual(0, items[0]["likes"])

    def test_every_sample_photo_has_a_photo_and_a_thumbnail_file(self):
        site = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "site")
        for item in seed_table.items_from_file():
            for folder in ("photos", "thumbs"):
                self.assertTrue(os.path.exists(os.path.join(site, folder, item["id"] + ".jpg")), (folder, item["id"]))

    def test_usage_without_arguments(self):
        self.assertEqual(1, seed_table.main(["seed_table.py"]))


if __name__ == "__main__":
    unittest.main()
