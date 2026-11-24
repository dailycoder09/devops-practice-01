# Lab 2: Behaviors and a second origin (about 75 minutes)

**Goal:** serve the website from S3 and an API from Lambda through **one** CloudFront address, and control caching per path.

**You will learn:** origins and behaviors, path patterns, cache policies, what the **cache key** really is, the Host header trap, and how the origin can control the TTL.

Needs the distribution from Lab 0 (`BUCKET`, `DIST`).

```
https://DIST/            -> Default (*)  -> S3 bucket       (cached for a day)
https://DIST/api/time    -> /api/*       -> Lambda URL      (cache decided by the API itself)
```

---

## Part 1: The API (Lambda, 10 minutes)

1. Lambda → **Create function** → Author from scratch. Name `cf-lab-api`, runtime **Python 3.12** (any current Python is fine), keep the other defaults. Create.
2. In the code editor replace the code with the content of [`api/lambda_function.py`](../api/lambda_function.py). Click **Deploy**.
3. **Configuration** → **Function URL** → Create function URL. Auth type **NONE** (lab only: the URL is public and the function only returns the time). Save.
4. Copy the Function URL, for example `https://abcd1234.lambda-url.us-west-2.on.aws/`. Keep **only the host name**: `abcd1234.lambda-url.us-west-2.on.aws`.

```bash
LAMBDA_HOST=abcd1234.lambda-url.us-west-2.on.aws      # <- yours, no https://, no slash
curl -s "https://$LAMBDA_HOST/api/time?ttl=20"
```

You should see JSON with `served_at`, a new `request_id`, and `"cache_control_sent": "max-age=20"`. If you get `403 Forbidden` straight from the Function URL: Configuration → Permissions → **Resource-based policy** must allow the Function URL invoke (the console adds it when you choose NONE).

What this API does: `/api/time` says `Cache-Control: no-store` (do not keep). `/api/time?ttl=20` says `max-age=20` (keep for 20 seconds). So **the origin decides** how long CloudFront may keep each answer.

> If Lambda is blocked in your sandbox: use a second S3 bucket as the API origin with a file `api/time.json` and practise only the behaviors and cache key parts (Parts 3 and 4). You cannot make it dynamic, so the TTL exercises will look flat.

## Part 2: Add the origin and a cache policy (10 minutes)

**The origin.** Distribution → **Origins** → Create origin:
- Origin domain: `abcd1234.lambda-url.us-west-2.on.aws` (host only).
- Protocol: **HTTPS only**. Name it `api-origin`.
- Origin access: Public (the URL has no authentication).

**The cache policy.** CloudFront → **Policies** → **Cache** → Create:
- Name `cf-lab-api-respect-origin`.
- TTL: minimum **0**, default **0**, maximum **300**.
- Cache key: Headers **None**, Cookies **None**, Query strings **All**.
- Compression: Gzip and Brotli on. Create.

Meaning: nothing is cached unless the origin asks (default 0), the origin may ask for up to 300 seconds, and every query string is part of the cache key.

## Part 3: The behavior (5 minutes, then wait)

Distribution → **Behaviors** → Create behavior:
- Path pattern: `/api/*`
- Origin: `api-origin`
- Viewer protocol policy: Redirect HTTP to HTTPS
- Allowed methods: GET, HEAD
- Cache policy: `cf-lab-api-respect-origin`
- **Origin request policy: none.** (Leave it empty. Part 5 shows why.)

Create it and wait until the distribution is **Deployed**. The default `*` behavior stays as it is: S3, CachingOptimized.

> Order matters: the first matching pattern wins and `Default (*)` is last. `/api/*` is more specific, so it must come before the default (the console puts it there).

## Part 4: Experiments

All use `check.sh`. `BODY=1` also prints the start of the answer, so you can see `request_id`.

### Experiment 1: not cacheable

**Predict:** three calls to `/api/time` (no ttl). Hit or Miss? Same `request_id`?

```bash
BODY=1 bash scripts/check.sh https://$DIST/api/time 3
```

**You should see:** `Miss from cloudfront` every time and a **different** `request_id` each time. The origin said `no-store`.

### Experiment 2: the origin sets the TTL

**Predict:** `?ttl=20`, six calls five seconds apart. What changes after 20 seconds?

```bash
SLEEP=5 BODY=1 bash scripts/check.sh "https://$DIST/api/time?ttl=20" 6
```

**You should see:** request 1 is a Miss; requests 2 to 4 are Hits with the **same** `request_id` and Age 5, 10, 15; after about 20 seconds the copy expires and the next request is a Miss with a **new** `request_id`.

### Experiment 3: the cache key

**Predict:** `?ttl=20&x=1` and `?ttl=20&x=2`. Are they one cache entry or two?

```bash
BODY=1 bash scripts/check.sh "https://$DIST/api/time?ttl=20&x=1" 2
BODY=1 bash scripts/check.sh "https://$DIST/api/time?ttl=20&x=2" 2
```

**You should see:** two different entries (each starts with a Miss) because the policy includes **all** query strings.

Now change the policy: Policies → Cache → `cf-lab-api-respect-origin` → Edit → Query strings: **Include the following query strings**: `ttl`. Save, wait until the distribution shows **Deployed** again (a policy change is deployed like any other change), then repeat both commands (use a new `x`, for example `x=3` and `x=4`, to avoid old entries).

**You should see:** `x=3` is a Miss, `x=4` is a **Hit with the same request_id**: `x` is no longer in the cache key. Look at the `query` field of the answer: it contains only `ttl=20`. CloudFront **does not send** to the origin what is not in the cache key.

> **The danger:** if your API really depended on `x`, users would get each other's answers. Put in the cache key everything the answer depends on, and nothing more (more entries means fewer Hits).

### Experiment 4: the path pattern

**Predict:** `/api/*` matches `/api/time`. Does it match `/api`?

```bash
bash scripts/check.sh https://$DIST/api 1
```

**You should see:** `403` (or `404` if you added the `ListBucket` statement in Lab 1). The pattern needs the slash and something after it, so `/api` falls to the default behavior and goes to S3, which has no such file. A pattern is matched on the path text, not on "folders".

## Part 5: Break it

### Break 1: the Host header trap

**Predict:** you forward the viewer's `Host` header to the Lambda URL. What happens?

Behaviors → `/api/*` → Edit → **Origin request policy: AllViewer** (a managed policy that forwards all viewer headers, including Host). Save, wait for Deployed.

```bash
bash scripts/check.sh https://$DIST/api/time 2
```

**You should see:** `403` (the exact text of the message may differ). The Function URL decides who you are by its **own host name**. If CloudFront forwards `Host: dXXXX.cloudfront.net`, Lambda does not recognise the request.

Fix: set the origin request policy back to **none** (or the managed `AllViewerExceptHostHeader`). Wait for Deployed; the cached 403 lasts about 10 seconds.

### Break 2 (optional): methods

```bash
curl -s -i -X POST https://$DIST/api/time | head -5
```

**You should see:** `403` and a message that the distribution does not allow this method. The behavior allows only GET and HEAD. Edit the behavior → Allowed methods: **GET, HEAD, OPTIONS, PUT, POST, PATCH, DELETE** → Save → wait → the POST now reaches Lambda (POST responses are never cached). Skip this one if you are short on time.

---

## Questions

1. Two requests: `/api/time?ttl=20&x=1` and `?ttl=20&x=2`. When is that one cache entry, when two?
2. The origin sends `Cache-Control: max-age=20`, the policy has default 0 and maximum 300. How long is the copy kept? What if the origin said `max-age=1000`?
3. What is the difference between a cache policy and an origin request policy?
4. Why does forwarding the `Host` header break a Lambda Function URL?
5. Your site has `/api/*` and `/images/*` behaviors. Where does `/apiary.html` go?

<details><summary>Answers</summary>

1. One entry if the query string `x` is not part of the cache key (the policy lists only `ttl`). Two if query strings are all included.
2. 20 seconds. For `max-age=1000` it is limited to the maximum: 300 seconds.
3. The cache policy defines the cache key and the TTLs. The origin request policy defines what else is sent to the origin on a Miss. It does not affect the cache key.
4. Lambda URLs route by their own host name. A forwarded viewer Host (`dXXXX.cloudfront.net`) does not match any function URL.
5. To the default behavior: `/api/*` needs a slash after `api`.
</details>

## Remember

- Cache key = what makes two requests the same. Put in it what the answer depends on.
- What is not in the cache key is not sent to the origin.
- The origin can set the TTL with `Cache-Control`; the policy limits it between min and max.
- Never forward the viewer's Host to a service that routes by its own host name.
