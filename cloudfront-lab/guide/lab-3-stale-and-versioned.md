# Lab 3: Stale content, invalidations and versioned names (about 45 minutes)

**Goal:** see the classic CloudFront problem (you changed the file, visitors still get the old one), fix it two ways, and learn which way to prefer.

**You will learn:** why old content stays, invalidations and their cost, `Cache-Control` set from S3, versioned file names, `RefreshHit`, and compression.

Needs the distribution from Lab 0 (`BUCKET`, `DIST`). Work in CloudShell inside the `cloudfront-lab` folder. If it has been restarted, set the variables again:

```bash
export AWS_DEFAULT_REGION=us-west-2
BUCKET=cf-lab-yourname-1234
DIST=d1234abcd5678.cloudfront.net
```

---

## Part 1: The problem

**Predict:** you change `index.html` in S3. Do visitors see the new version at once?

```bash
bash scripts/check.sh https://$DIST/index.html 1          # make sure it is cached
sed -i 's/Version 1/Version 2/' site/index.html
aws s3 cp site/index.html s3://$BUCKET/index.html
aws s3 cp s3://$BUCKET/index.html - | grep -o "Version [0-9]"        # S3 has: Version 2
curl -s https://$DIST/index.html | grep -o "Version [0-9]"           # CloudFront still has: Version 1
```

**You should see:** `Version 2` from S3 and **`Version 1`** from CloudFront. The file had no `Cache-Control`, so the policy's default TTL applied (CachingOptimized: **one day**).

Try a hard refresh in the browser (Cmd/Ctrl + Shift + R) on the page: still `Version 1`. CloudFront does not obey the browser's refresh request; it only follows the rules of the cache policy and the origin.

## Part 2: Fix 1, an invalidation

Console → your distribution → **Invalidations** → **Create invalidation**. Object paths, one per line:

```
/index.html
/
```

(Both, because they are two cache entries: Lab 0, Exercise 2.) Create it. Its status is **In progress**, then **Completed** after a minute or two.

```bash
curl -s https://$DIST/index.html | grep -o "Version [0-9]"           # Version 2
curl -s https://$DIST/ | grep -o "Version [0-9]"                      # Version 2
```

**Cost:** the first **1,000 paths per month** are free, each further path costs about half a US cent. A wildcard such as `/*` counts as **one** path. Invalidations are fine for emergencies. They are a bad **routine**: you must remember them, they take time, and a Miss follows for every path.

## Part 3: Fix 2, versioned names and short HTML

The better way, used by almost every real site:

- Files that never change under their name (CSS, JS, images, **named with a version**) are cached for a **year**.
- The HTML pages that point to them are cached for a **short time** (for example 60 seconds).
- To release: upload new files with **new names**, and a new HTML that points to them. Visitors get the change within a minute, with no invalidation.

**Step 1: upload with cache headers**

```bash
aws s3 cp site/assets/ s3://$BUCKET/assets/ --recursive --cache-control "public, max-age=31536000, immutable"
aws s3 cp site/index.html s3://$BUCKET/index.html --cache-control "public, max-age=60"
aws s3 cp site/about.html s3://$BUCKET/about.html --cache-control "public, max-age=60"
```

**Step 2: one clean-up invalidation** (the old copies have a one-day TTL). Create an invalidation for `/*`, wait for Completed. Then:

```bash
bash scripts/check.sh https://$DIST/index.html 2
bash scripts/check.sh https://$DIST/assets/app.v1.css 2
```

**You should see:** `CC: public, max-age=60` for the page and `CC: public, max-age=31536000, immutable` for the stylesheet. CloudFront passes the origin's header on to the browser and also uses it for its own TTL.

**Step 3: release "Version 3" without invalidating**

Make a new stylesheet under a new name, point the page at it, change the text, and upload:

```bash
sed 's/--accent: #1f6feb/--accent: #d6336c/; s/--accent: #5b9bff/--accent: #ff8fb1/' site/assets/app.v1.css > site/assets/app.v2.css
sed -i 's#/assets/app.v1.css#/assets/app.v2.css#; s/Version 2/Version 3/' site/index.html
aws s3 cp site/assets/app.v2.css s3://$BUCKET/assets/app.v2.css --cache-control "public, max-age=31536000, immutable"
aws s3 cp site/index.html s3://$BUCKET/index.html --cache-control "public, max-age=60"
```

Now wait up to a minute (the old `index.html` copy lives at most 60 seconds) and run:

```bash
SLEEP=15 bash scripts/check.sh https://$DIST/index.html 6
curl -s https://$DIST/index.html | grep -o "Version [0-9]"
```

**You should see:** after at most about 60 seconds the page says **Version 3** and uses the pink colour: **no invalidation was needed**. The new stylesheet's first request is a Miss, then Hits.

**Break it:** overwrite a versioned file under the **same name** and see that nobody gets it:

```bash
echo "/* changed */ body { background: black; }" > /tmp/oops.css
aws s3 cp /tmp/oops.css s3://$BUCKET/assets/app.v1.css --cache-control "public, max-age=31536000, immutable"
curl -s https://$DIST/assets/app.v1.css | head -2        # the OLD stylesheet, for up to a year
```

That is why versioned files must **never** be changed in place. (Restore: `aws s3 cp site/assets/app.v1.css s3://$BUCKET/assets/app.v1.css --cache-control "public, max-age=31536000, immutable"` and invalidate `/assets/app.v1.css`.)

## Part 4: RefreshHit

What happens when a copy **expires**? CloudFront asks the origin "has it changed?" (with the ETag it stored). S3 answers "no, 304" and CloudFront keeps its copy: it did **not** download the file again.

```bash
aws s3 cp site/about.html s3://$BUCKET/about.html --cache-control "public, max-age=10"
bash scripts/check.sh https://$DIST/about.html 1            # a Miss (or a Hit) with the old copy: see Age
sleep 15
bash scripts/check.sh https://$DIST/about.html 1            # the copy is older than 10 s
```

**You should see:** after the 10 seconds a line with **`RefreshHit from cloudfront`**. A RefreshHit is an expired copy that was re-checked and kept. If the object had changed, you would see `Miss`. (If the first line is a Hit with Age above 10 because of the earlier invalidation, run the sleep and the check once more.)

## Part 5: Compression

`data/big.json` is about 400 KB of text.

```bash
curl -s -o /dev/null -w "plain:  %{size_download} bytes\n" -H "Accept-Encoding: identity" https://$DIST/data/big.json
curl -s -o /dev/null -w "gzip:   %{size_download} bytes\n" -H "Accept-Encoding: gzip" https://$DIST/data/big.json
curl -s -o /dev/null -w "brotli: %{size_download} bytes\n" -H "Accept-Encoding: br" https://$DIST/data/big.json
bash scripts/check.sh https://$DIST/data/big.json 2          # the Enc column: br
```

**You should see:** about 400 KB plain and a few KB with gzip or brotli. Compression is done **by CloudFront**, because the cache policy has it switched on (CachingOptimized does) and the type is text. The browser asks with `Accept-Encoding`, and the policy puts that in the cache key, so each encoding is cached separately.

Why not compress the JPEG photos? They are already compressed: CloudFront only compresses types that benefit, and only between about 1 KB and 10 MB.

---

## Questions

1. You changed a file in S3 and visitors still see the old one. Name three ways to fix it, and say which one you would build into your release process.
2. Why do you invalidate both `/` and `/index.html`?
3. Why can the stylesheet be cached for a year but `index.html` only for 60 seconds?
4. What is a `RefreshHit`, and what did S3 answer to produce it?
5. Why does CloudFront keep a separate copy for `gzip` and `br`?

<details><summary>Answers</summary>

1. Invalidate the path; wait for the TTL to run out; give the file a new name (versioned) and update the page that uses it. The versioned name with a short-lived HTML is the one for a release process.
2. The cache key is the viewer's path, so they are two entries.
3. The stylesheet's name changes with every version, so an old copy can never be wrong. The page keeps the same name, so its copy must expire quickly for visitors to find the new stylesheet name.
4. An expired copy that CloudFront checked with the origin (If-None-Match with the ETag) and kept because S3 answered 304 Not Modified.
5. The encoding is part of the cache key (the policy enables it), because each visitor must get a body their browser can read.
</details>

## Remember

- Cache for a long time **and** never change the file: version the name.
- Cache for a short time **and** keep the name: your HTML.
- Invalidations are for emergencies: the first 1,000 paths a month are free, a wildcard is one path.
- Hit, Miss, RefreshHit, Error are the four X-Cache values you will see most.
