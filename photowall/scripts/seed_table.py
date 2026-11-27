#!/usr/bin/env python3
"""Puts the sample photos of site/data/photos.json into the DynamoDB table (Phase 2).

    python3 scripts/seed_table.py TABLE_NAME            # in CloudShell, inside the photowall folder
    python3 scripts/seed_table.py TABLE_NAME --dry-run  # only print what would be written

Safe to run twice: the same ids are written again with the same values (it also resets their likes to 0).
"""
import json
import os
import sys

PHOTOS_JSON = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "site", "data", "photos.json")


def items_from_file(path=PHOTOS_JSON):
    """The table items for the sample photos: only what the table stores (the picture addresses are built from the id)."""
    with open(path, encoding="utf-8") as handle:
        photos = json.load(handle)["photos"]
    return [{"id": p["id"], "title": p["title"], "likes": 0, "created_at": p["created_at"]} for p in photos]


def main(argv):
    if len(argv) < 2 or argv[1].startswith("-"):
        print(__doc__)
        return 1
    items = items_from_file()
    if "--dry-run" in argv:
        for item in items:
            print(item)
        print("%d items (dry run, nothing written)" % len(items))
        return 0
    import boto3
    table = boto3.resource("dynamodb").Table(argv[1])
    with table.batch_writer() as batch:
        for item in items:
            batch.put_item(Item=item)
    print("%d items written to %s" % (len(items), argv[1]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
