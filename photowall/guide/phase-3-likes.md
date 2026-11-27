# Phase 3: Likes (about 30 minutes)

**Goal:** use the like button and see what a cache does to data that changes.

**You will learn:** a POST is never cached, why a cached list shows an old number for a short time, how to choose the TTL (a trade-off, not a rule), and what CloudFront does when a method is not allowed.

Nothing new to build: the function already has the like route. Needs Phase 2 (`DIST`).

---

## Part 1: Press the heart

Open the site, click ♥ on a photo. The number goes up at once. Open the browser's developer tools (F12 → **Network**), click again, and look at the request `POST /api/photos/sample01/like`.

**You should see:** status 200, a response `{"id": "sample01", "likes": 2}`, `Cache-Control: no-store`, and `x-cache: Miss from cloudfront`. A POST always goes to the origin.

The number on the page went up because the **page itself** wrote the new number (the answer of the POST contains it). It did not reload the list.

## Part 2: Why the list can be a little old

**Predict:** you like a photo with curl. You immediately ask for the list. Which number do you get?

```bash
# the list is probably cached right now: ask for it once
bash scripts/check.sh https://$DIST/api/photos 1
curl -s -X POST https://$DIST/api/photos/sample03/like               # {"id": "sample03", "likes": 1}
curl -s https://$DIST/api/photos | python3 -c "import sys,json; print([ (p['id'], p['likes']) for p in json.load(sys.stdin)['photos'] if p['id']=='sample03'])"
bash scripts/check.sh https://$DIST/api/photos 1
```

**You should see:** the like itself says `likes: 1`, but the list still says `0` (a Hit with a small `Age`). The list is a copy from the last Miss. After up to 30 seconds the copy expires and the list catches up:

```bash
sleep 30
curl -s https://$DIST/api/photos | python3 -c "import sys,json; print([ (p['id'], p['likes']) for p in json.load(sys.stdin)['photos'] if p['id']=='sample03'])"
```

This is not a bug. It is the price of the cache: the same answer for many visitors without asking DynamoDB each time. For the number of likes, "up to 30 seconds old" is fine. For a bank balance it would not be.

## Part 3: Choose the TTL (a trade-off)

Think of 100 visitors a minute, all opening the page:

| `LIST_TTL_SECONDS` | Lambda calls for the list (per edge location) | A new like shows on the list after | Good for |
|---|---|---|---|
| 0 | about 100 a minute | at once | tiny traffic or exact numbers |
| 30 | about 2 a minute | up to 30 s | this project |
| 300 | about 1 every 5 minutes | up to 5 minutes | rarely changing data |

Try two values yourself and measure with `SLEEP=10 bash scripts/check.sh https://$DIST/api/photos 8`:
- `LIST_TTL_SECONDS=0` → every line is a Miss.
- `LIST_TTL_SECONDS=300` → one Miss and then Hits for five minutes.
Set it back to `30`.

**The ways to make a cached list fresher** (none is free):
1. A shorter TTL (more Lambda calls and DynamoDB reads).
2. The page updates the number itself after a like (what this page does).
3. An **invalidation** of `/api/photos` after each like: it works, but costs one path per like after the free 1,000 a month, and takes a minute or two. Not a good idea for likes.

## Part 4: Break it

### Break 1: a method that is not allowed

Behaviors → `/api/*` → Edit → allowed methods: **GET, HEAD** only. Wait for Deployed.

```bash
curl -s -i -X POST https://$DIST/api/photos/sample03/like | head -12
```

**You should see:** `403` and a message that this distribution is not configured to allow that method. The like never reaches the Lambda. Put the seven methods back and wait for Deployed.

### Break 2: wrong things in the URL

```bash
curl -s -w " -> %{http_code}\n" -X POST https://$DIST/api/photos/nothing/like          # 404 No such photo
curl -s -w " -> %{http_code}\n" -X POST "https://$DIST/api/photos/bad_id/like"          # 404 Not found (only letters and digits are allowed in an id)
curl -s -w " -> %{http_code}\n" https://$DIST/api/photos/sample03/like                  # 404: a GET is not a like
curl -s -w " -> %{http_code}\n" -X POST https://$DIST/api/photos                        # 403: saving a photo needs the upload key
```

Who checked what? The like route and the 404 come from the **Lambda**, the method rule from **CloudFront**. Each layer has its own rules.

---

## Questions

1. Why is a POST never served from the cache?
2. A visitor clicks a heart and the number goes up, but a friend who opens the page a second later sees the old number. Why?
3. You want likes to show within 5 seconds for everybody. What would you change, and what does it cost?
4. Where does the 403 for a POST come from when the behavior only allows GET and HEAD?

<details><summary>Answers</summary>

1. A POST changes data, so CloudFront always sends it to the origin and does not store the answer.
2. The friend gets the cached list (up to 30 seconds old); the first visitor's page updated its own number from the POST answer.
3. Set `LIST_TTL_SECONDS` to 5. The cost is more calls to the Lambda and DynamoDB (about six times more than at 30 seconds).
4. From CloudFront itself: the method is not allowed on that behavior, so the request never reaches API Gateway or the Lambda.
</details>

## Remember

- POST, PUT and DELETE are never cached.
- A cached list is slightly old by design: choose the TTL for the data.
- Each layer (CloudFront, API Gateway, Lambda) answers with its own errors: know which one spoke.
