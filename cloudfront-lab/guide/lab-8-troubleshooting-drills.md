# Lab 8: Troubleshooting drills (about 75 minutes)

**Goal:** learn to find the cause of a CloudFront problem from the symptoms. In each drill **you** break something, then you act as the person who has to find out why, and fix it.

**You will learn:** a fixed way to investigate, and the six problems that make up most real CloudFront tickets.

Needs the setup of the earlier labs: the distribution with the S3 default behavior and the `/api/*` Lambda behavior (Lab 2), `BUCKET`, `DIST`, `LAMBDA_HOST`. If you did the optional parts of Lab 4 to 6 (WAF, country rule, functions, origin group), remove them first or the symptoms will be mixed up. A clean distribution gives clean drills.

Do the drills in any order. Cover the **Solution** and try to find the cause from the symptom.

---

## The investigation order (use it every time)

1. **Is it CloudFront or the origin?** Ask the origin directly (the S3 URL, the Lambda URL). If the origin is also broken, CloudFront is not the problem.
2. **Read the answer:** status code, `X-Cache`, `Age`, `Cache-Control`, and note the `X-Amz-Cf-Id`. `bash scripts/check.sh URL 3`
3. **Which behavior** took this path? Compare the path with the patterns, top to bottom.
4. **Is the distribution Deployed?** A change in progress explains "I changed it and nothing happened".
5. **Cache key and TTL:** what is in the key, how long is a copy kept?
6. **Logs and metrics** (Lab 7) when the above does not explain it.

---

## Drill 1: "Everything is 403"

**Break it:** S3 → your bucket → Bucket policy → change the distribution id in `AWS:SourceArn` to a wrong one (for example change the last two characters). Save. Then invalidate `/*` (so nothing is cached) and wait for Completed.

**Symptom:** the site shows an error page. `bash scripts/check.sh https://$DIST/index.html 2` says 403.

<details><summary>Solution</summary>

1. Origin directly: `curl -sI https://$BUCKET.s3.us-west-2.amazonaws.com/index.html` is also 403, but that is normal (private bucket), so this does not tell you much here.
2. 403 plus `Error from cloudfront`, for **every** path, also new ones.
3. Look at the bucket policy: the `Condition` names a distribution that is not yours. Compare with the id on the distribution page.
4. **Fix:** put the right distribution ARN. Wait 10 to 15 seconds (the error is cached for 10 s), then it works.

Rule: 403 for everything, including a brand new file = permission between CloudFront and S3 (policy, condition, OAC). 403 for one path = that file or that behavior.
</details>

## Drill 2: "I changed the logo but still see the old one"

**Break it:** upload a different picture over `images/photo-1.jpg` (for example copy `photo-2.jpg` over it):

```bash
aws s3 cp site/images/photo-2.jpg s3://$BUCKET/images/photo-1.jpg
```

**Symptom:** S3 has the new picture, the browser and `curl` show the old one.

<details><summary>Solution</summary>

1. The origin has the new file (`aws s3api head-object --bucket $BUCKET --key images/photo-1.jpg` shows a new `LastModified`).
2. `bash scripts/check.sh https://$DIST/images/photo-1.jpg 2`: `Hit from cloudfront` and a big `Age`. `CC` is empty, so the default TTL (a day) applies.
3. Same behavior, same cache key.
4. **Fix:** invalidate `/images/photo-1.jpg` (quick), and in future give changed files a new name (Lab 3).

Rule: Hit with a big Age and the origin is newer = the TTL has not ended.
</details>

## Drill 3: "It is never fast: never a Hit"

**Break it:** create a cache policy with **Cookies: All** (Policies → Cache → Create `drill-cookies`: TTL min 0, default 86400, max 31536000; cookies **All**; the rest none). Attach it to the **Default (\*)** behavior. Wait for Deployed. Then ask with a different cookie every time, the way a site with a session cookie would:

```bash
for i in 1 2 3 4; do bash scripts/check.sh https://$DIST/index.html 1 -H "Cookie: session=$RANDOM"; done
```

**Symptom:** every request is a `Miss`, the origin is busy, the page is slow.

<details><summary>Solution</summary>

1. The origin is fine.
2. `Miss` every time with the same path and the same `Cache-Control`. Run it without the cookie: it becomes a Hit. So something that **changes between requests** is in the cache key.
3. Behavior → its cache policy: cookies are in the key. Every user has a different cookie, so every user has their own copy: the cache is useless.
4. **Fix:** a cache policy with cookies **None** for static files (CachingOptimized). Only the behavior that really depends on a cookie needs it.

Rule: Miss, Miss, Miss for the same URL = look at the cache key (cookies, headers, query strings) and at `Cache-Control: no-store` from the origin.
</details>

## Drill 4: "The API sometimes returns 504"

**Break it:** update the Lambda code with the version in `api/lambda_function.py` (it has a `sleep` option), **Deploy**, and in Configuration → General configuration set the function **Timeout** to 15 seconds. Then set the API origin's **Response timeout** to **1** second (Distribution → Origins → `api-origin` → Edit → Response timeout). Wait for Deployed.

```bash
bash scripts/check.sh "https://$DIST/api/time" 1                 # fine
bash scripts/check.sh "https://$DIST/api/time?sleep=3" 1         # the function waits 3 seconds
```

**Symptom:** the plain call works; the slow call fails.

<details><summary>Solution</summary>

1. Call the Lambda URL directly: `curl -s "https://$LAMBDA_HOST/api/time?sleep=3"` works after 3 seconds. The origin is fine, just slow.
2. Through CloudFront: **504** and `Error from cloudfront`, with a page that talks about an origin that did not answer in time.
3. Only requests with `sleep` fail, so it depends on the request, not on the path or the behavior.
4. The origin's **response timeout** (1 s) is shorter than the answer time (3 s). **Fix:** raise the response timeout (default 30 s) for slow origins, or make the origin faster. Set it back to 30.

Also try a **wrong origin name** (edit the origin domain to a typo): you get **502** ("could not resolve the origin domain name"). Put it back.

Rule: 502 = CloudFront could not talk to the origin properly (DNS, TLS, connection). 504 = it could, but the origin was too slow.
</details>

## Drill 5: "The browser says CORS error"

**Setup:** another website's JavaScript wants to read your file. Test with `curl` the way a browser does, by sending an `Origin` header:

```bash
curl -sI -H "Origin: https://example.org" https://$DIST/data/big.json | grep -iE "^HTTP|access-control|vary"
```

**Symptom:** no `Access-Control-Allow-Origin` header in the answer. (In a browser console on another site, `fetch('https://DIST/data/big.json')` fails with a CORS error.)

<details><summary>Solution</summary>

1. S3 sends CORS headers only if the **bucket** has a CORS configuration, and only if CloudFront passes the `Origin` header to S3, which the default cache policy does not.
2. There are two ways to fix it:
   - **At CloudFront (simple):** attach a response headers policy that adds `Access-Control-Allow-Origin`. The managed policy **SimpleCORS** adds `Access-Control-Allow-Origin: *` for GET and HEAD. Attach it to the **Default (\*)** behavior (if you built `cf-lab-security` in Lab 4, add the CORS setting to that policy instead: a behavior has only one response headers policy).
   - **At the origin:** a CORS configuration on the bucket, **and** a cache policy / origin request policy that forwards the `Origin` header (managed: origin request policy **CORS-S3Origin**), and the `Origin` header in the cache key.
3. After the fix the same `curl` shows `access-control-allow-origin: *`.

Rule: CORS error through CloudFront = either the header is not added, or the `Origin` header never reached the origin, or the answer was cached without it.
</details>

## Drill 6: "I fixed it, but the error stays"

**Break it:** Error pages → create a custom error response for **403**: error caching minimum TTL **300** seconds (no custom page needed). Wait for Deployed. Make the file missing, then fix it:

```bash
bash scripts/check.sh https://$DIST/later.html 2          # 403 or 404, an Error
echo "<h1>now it exists</h1>" > /tmp/later.html
aws s3 cp /tmp/later.html s3://$BUCKET/later.html
bash scripts/check.sh https://$DIST/later.html 2          # still the error, although the file is there
```

**Symptom:** the file exists but the error continues for minutes.

<details><summary>Solution</summary>

1. The origin has the file (`aws s3 ls s3://$BUCKET/later.html`).
2. `Error from cloudfront` with a growing `Age`: CloudFront serves a **cached error**.
3. Distribution → **Error pages**: the error caching TTL is 300 s. Default is 10 s.
4. **Fix:** invalidate `/later.html` (immediate), and lower the error caching TTL to 10 to 30 seconds so that fixes are visible quickly. Remove the custom response if you do not need it.

Rule: a fix that does not show = a cached error. Invalidate the path, and keep the error TTL short.
</details>

---

## Your own checklist

Copy this into your notes. It is the Lab 8 list in one place:

| Symptom | First thing to look at |
|---|---|
| 403 everywhere | bucket policy and its `SourceArn` condition, OAC on the origin |
| 403 on one path | does the file exist? which behavior matches? allowed methods? signed URL needed? |
| 502 | origin name, TLS, connection, a function error |
| 504 | origin too slow: response timeout vs the real time |
| Old content | `Age`, `Cache-Control`, TTL: invalidate or rename |
| Always Miss | cache key (cookies, headers, query strings), `no-store` |
| CORS error | response headers policy, or bucket CORS + `Origin` forwarded |
| Fixed but still failing | cached error: invalidate, shorten the error TTL |
| Change without effect | still Deploying? wrong behavior? |

## Clean up after the drills

- Put the Lambda timeout and the origin response timeout back (3 s / 30 s).
- Delete the drill cache policy `drill-cookies` and the custom error responses.
- Make sure the bucket policy has the right `SourceArn` again.
