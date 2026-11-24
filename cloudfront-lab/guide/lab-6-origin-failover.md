# Lab 6: Origin failover and error pages (about 50 minutes)

**Goal:** make CloudFront switch to a backup origin when the main one fails, and show a friendly page when something is really missing.

**You will learn:** origin groups, failover status codes, why failover only helps for requests that are not cached, custom error responses, and how this compares with the Route 53 failover you built for DropLink.

Needs the distribution and bucket from Lab 0 (`BUCKET`, `DIST`). Work in CloudShell inside `cloudfront-lab`.

```
viewer -> CloudFront -> origin group
                         ├─ primary:   bucket A  (your Lab 0 bucket)
                         └─ secondary: bucket B  (a backup copy, says "BACKUP" on the page)
   if A answers 403, 404, 500, 502, 503 or 504 -> CloudFront asks B for the same file
```

---

## Part 1: A backup bucket

```bash
BACKUP=${BUCKET}-backup
aws s3 mb s3://$BACKUP --region us-west-2
cp -r site /tmp/site-backup
sed -i 's#<h1>EdgeLab</h1>#<h1>EdgeLab (BACKUP)</h1>#' /tmp/site-backup/index.html
echo "<h1>I exist only in the backup bucket</h1>" > /tmp/site-backup/only-in-backup.html
aws s3 sync /tmp/site-backup/ s3://$BACKUP/
aws s3 ls s3://$BACKUP/ | head
```

Same files, with two differences you can recognise: the heading says **BACKUP**, and `only-in-backup.html` exists only here. The bucket is private like the first one.

## Part 2: Add it as an origin and build the group

1. Distribution → **Origins** → Create origin: the backup bucket, Origin access: **OAC** (reuse the one from Lab 0). Name it `backup`. Create.
2. CloudFront shows a bucket policy to copy. Put it on the **backup bucket**: S3 → backup bucket → Permissions → Bucket policy. (It is the same policy as in Lab 1, with the backup bucket's name and the same distribution ARN. Add the `ListBucket` statement from Lab 1 Part 3 as well, so a missing file is a 404 and not a 403.)
3. **Origins** → **Create origin group**: name `cf-lab-group`, primary: your first origin, secondary: `backup`, failover criteria: tick **403, 404, 500, 502, 503, 504**. Create.
4. **Behaviors** → **Default (\*)** → Edit → Origin and origin groups: `cf-lab-group`. Save. Wait for **Deployed**.

## Part 3: Experiments

### Experiment 1: normal times

```bash
curl -s https://$DIST/ | grep -o "<h1>[^<]*</h1>"
```

**You should see:** `<h1>EdgeLab</h1>`. The primary answers, the backup is not used. (If you see `(BACKUP)`, an earlier error was cached: invalidate `/*` and try again.)

### Experiment 2: a file only the backup has

**Predict:** `only-in-backup.html` is not in bucket A. What does the viewer get?

```bash
bash scripts/check.sh https://$DIST/only-in-backup.html 2
curl -s https://$DIST/only-in-backup.html
```

**You should see:** status `200` and the backup's text. CloudFront asked A (404 or 403), saw a status in the failover list, and asked B. The viewer never saw the error. The second request is a Hit: the answer from the backup is cached under the same path.

### Experiment 3: the primary breaks

Simulate an outage of bucket A: delete its bucket policy (copy the text first). Then ask for something that is **not cached**:

```bash
echo "<h1>Brand new page</h1>" > /tmp/new.html
aws s3 cp /tmp/new.html s3://$BACKUP/new.html          # exists only in the backup
bash scripts/check.sh https://$DIST/new.html 2         # works: the backup answers
bash scripts/check.sh https://$DIST/index.html 2       # still the primary's cached copy: the heading without (BACKUP)
```

Now invalidate `/index.html` and `/`, wait for Completed, and ask again:

```bash
curl -s https://$DIST/ | grep -o "<h1>[^<]*</h1>"
```

**You should see:** `EdgeLab (BACKUP)`: with the primary broken and nothing cached, the whole site comes from the backup. **Restore the bucket A policy** (both statements) and invalidate again to go back.

**The lessons:**
- Failover helps for what is **not in the cache**. Cached copies are served first, which is also why an outage stays invisible for a while.
- It works per request: no DNS change, no waiting for a health check. The first request that hits the failing origin pays the extra time.
- It works only for **GET, HEAD and OPTIONS**. A POST is never retried on another origin.

### Experiment 4: speed of the failover

Failing over costs the time CloudFront spends on the primary first. Origin settings control it: Origin → Edit → **Connection attempts** (default 3), **Connection timeout** (default 10 s), **Response timeout** (default 30 s). A bucket answers at once with an error, so here you do not notice. With an origin that is **down** (nothing listening) the viewer waits for the attempts and timeouts before the backup is asked. Lower them if you use failover, for example 1 attempt and 2 seconds. Read these three fields on your origin and write down the defaults.

## Part 4: A friendly error page

Visitors should not see the raw S3 error text. Distribution → **Error pages** → Create custom error response:
- HTTP error code **404**, customize: Yes, response page path `/errors/404.html`, HTTP response code **404**, error caching minimum TTL **30** seconds.
- Do the same for **403** (response code 404 is fine for a bucket: the page "does not exist" for the visitor).

Create the page, in **both** buckets (the group can answer from either):

```bash
mkdir -p /tmp/err && cat > /tmp/err/404.html <<'EOF'
<!doctype html><meta charset="utf-8"><title>Not found</title>
<body style="font-family:sans-serif;max-width:30rem;margin:3rem auto"><h1>This page does not exist</h1>
<p><a href="/">Back to the start</a></p></body>
EOF
aws s3 cp /tmp/err/404.html s3://$BUCKET/errors/404.html --cache-control "public, max-age=300"
aws s3 cp /tmp/err/404.html s3://$BACKUP/errors/404.html --cache-control "public, max-age=300"
```

Wait for Deployed (and restore the bucket A policy if you have not).

```bash
curl -s -i https://$DIST/definitely-missing.html | head -12
```

**You should see:** status `404` and your own page text. Note that the **status stays 404** (search engines and tools still know it is missing) and the **page is yours**.

**Break it (the stuck error):** the setting "error caching minimum TTL" keeps an error for that long. Set it to **300** for 404, request a missing file, upload it to S3, and request it again: you still get the error for up to five minutes, although the file exists. Set the TTL back to something small (10 to 30 seconds), and note how an error stays.

## Part 5: Two kinds of failover, side by side

You have now built both:

| | Route 53 failover (DropLink) | CloudFront origin group (this lab) |
|---|---|---|
| Works at | DNS: the name points to another address | each request, inside CloudFront |
| Switch time | health check (10 s × failures) plus the DNS cache time | the first failed request, at once |
| What it protects | a whole site, any protocol | GET/HEAD/OPTIONS to origins behind one distribution |
| Needs | a domain and health checks | nothing extra |
| Sticky? | clients keep the old address until their DNS cache expires | no: next request may go to the primary again |
| Used together | yes: DNS failover between two CloudFront distributions or two load balancers, and an origin group inside |  |

---

## Questions

1. Which three things must be true for a request to be retried on the secondary origin?
2. Why can an outage stay invisible with failover and a cache?
3. Why is it a good idea to lower the connection attempts and timeouts of the primary origin?
4. A custom error response for 404 returns your page with status 404. Why keep the status?
5. Which failover would you pick for a POST-heavy API, and why?

<details><summary>Answers</summary>

1. The request is GET, HEAD or OPTIONS; the primary answers with a status in the failover list (or does not answer); and the object is not already in the cache.
2. Cached copies keep being served, and the secondary answers the rest, so viewers see no errors. Watch the metrics, not only the page.
3. Otherwise a viewer waits for the full attempts and timeouts of a dead origin before the secondary is tried.
4. Browsers, search engines and monitoring still know the page does not exist; only the look of the page changed.
5. DNS failover (Route 53) or a load balancer with health checks: an origin group never retries a POST.
</details>

## Remember

- Origin group = primary + secondary + the status codes that trigger the switch.
- It only helps for uncached GET, HEAD and OPTIONS requests.
- Lower the attempts and timeouts, or a dead primary makes every first request slow.
- A custom error page keeps the status; an error is cached for the error TTL, so keep that small.
