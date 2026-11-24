# Lab 0: Your first distribution (about 45 minutes, most of it waiting)

**Goal:** put a small website in a private S3 bucket, put CloudFront in front of it, and see a **Miss** turn into a **Hit**.

**You will learn:** what a distribution and an origin are, what X-Cache and Age mean, why `/` and `/index.html` are different cache entries, and why a missing file gives 403.

Region for S3 and CloudShell: **us-west-2**. CloudFront itself is global.

---

## Step 1: Create the bucket (Console, 3 min)

S3 → **Create bucket**.
- Bucket name: `cf-lab-<yourname>-<4 digits>` (must be unique in the world). Write it down: this guide calls it `BUCKET`.
- Region: **US West (Oregon) us-west-2**.
- **Block all public access: leave it ticked.** The bucket stays private.
- Everything else: default. **Create bucket.**

## Step 2: Upload the website (CloudShell, 2 min)

In CloudShell, inside the `cloudfront-lab` folder (see the README, "Start"):

```bash
export AWS_DEFAULT_REGION=us-west-2
BUCKET=cf-lab-yourname-1234            # <- your bucket name
aws s3 sync site/ s3://$BUCKET/
aws s3 ls s3://$BUCKET/                # must show: assets/ data/ images/ about.html index.html
```

The listing must show `index.html` at the top level and **not** a folder called `site/`. (Prefer the console? Upload the files **inside** `site/`, not the folder `site` itself.)

## Step 3: Create the distribution (Console, 5 min, then wait)

CloudFront → **Create distribution**. The wizard has changed over time. Whatever its layout, you must end with these settings:

| Setting | Value |
|---|---|
| Origin | your bucket, picked from the list (`BUCKET.s3.us-west-2.amazonaws.com`). **Not** the "website endpoint". |
| Origin access | **Origin access control (OAC)**: create a new one, signing "Sign requests". |
| Viewer protocol policy | **Redirect HTTP to HTTPS** |
| Allowed HTTP methods | GET, HEAD |
| Cache policy | **CachingOptimized** (a managed policy) |
| Compress objects automatically | Yes |
| Web Application Firewall | **Do not enable** (a later lab) |
| Price class | Use only North America and Europe |
| Default root object | `index.html` (if the wizard does not ask, set it afterwards: the distribution → **General** → Settings → Edit) |
| Custom domain, certificate | none |

Create it. The distribution starts as **Deploying**.

## Step 4: Allow CloudFront to read the bucket (Console, 2 min)

A banner on the distribution says the bucket policy must be updated, with a **Copy policy** button. Do it:
S3 → your bucket → **Permissions** → **Bucket policy** → Edit → paste → Save.
(Lab 1 explains every line of this policy. For now just paste it.)

## Step 5: Wait until it is deployed (5 to 10 minutes)

CloudFront → Distributions: **Last modified** changes from "Deploying" to a date. Meanwhile read [the mental model](00-mental-model.md) again, or the cheat sheet.

Copy the **Distribution domain name**, for example `d1234abcd5678.cloudfront.net`:

```bash
DIST=d1234abcd5678.cloudfront.net      # <- yours, without https://
```

Open `https://<that name>` in the browser. You should see **EdgeLab**, with three photos.

---

## Exercises

Use the check script (CloudShell or your Mac, any terminal with `curl`):
`bash scripts/check.sh <URL> <how many times>`

### Exercise 1: Miss, then Hit

**Predict:** if you ask for `/index.html` four times, which requests are Miss and which are Hit? What happens to Age?

```bash
bash scripts/check.sh https://$DIST/index.html 4
```

| | Your prediction | What happened |
|---|---|---|
| request 1 | | |
| request 2 to 4 | | |
| Age | | |

**You should see:** the first request is `Miss from cloudfront` (or a Hit if someone already asked: opening the page in the browser may have), the next ones are `Hit from cloudfront`, and Age grows by about the seconds you waited. The Hit is also a bit faster.

### Exercise 2: `/` and `/index.html` are different entries

**Predict:** you already opened `/` in the browser. Is `/index.html` a Hit or a Miss at the first request?

```bash
bash scripts/check.sh https://$DIST/ 2
bash scripts/check.sh https://$DIST/about.html 2
```

`about.html` is a page nobody asked for yet, so its first request is a **Miss**. For `/` versus `/index.html`: the cache key is the path the viewer wrote. The default root object turns `/` into `index.html` only when CloudFront talks to S3, **after** the cache lookup. Two entries, two Misses.

### Exercise 3: Why 403 and not 404?

```bash
bash scripts/check.sh https://$DIST/nope.html 3
```

**You should see:** status **403**, and `X-Cache: Error from cloudfront`. The file does not exist, so why 403? S3 only says "not found" (404) to a caller who is allowed to list the bucket. CloudFront, through OAC, may only **read objects**, so for S3 "no such file" and "not allowed" look the same: 403. Lab 1 shows how to get a real 404.

Also notice: the error itself is cached for a short time (by default **10 seconds**), so the second and third request may say `Error from cloudfront` without reaching S3.

### Exercise 4: The inspector on the page

Open the website, scroll to **Cache inspector**, type `/images/photo-1.jpg` and press **Inspect**. Press it again. The table shows the same headers you saw in the terminal. This is the same check, without a terminal.

### Exercise 5: Look at it from S3's side

```bash
curl -sI https://$BUCKET.s3.us-west-2.amazonaws.com/index.html | head -3
```

**You should see:** `403 Forbidden`. The bucket answers nobody directly. CloudFront is the only way in. Lab 1 is about why.

---

## Questions (answer without looking back)

1. What does `X-Cache: Hit from cloudfront` tell you about your S3 bucket during that request?
2. Why can the first request to `/about.html` never be a Hit (unless someone else asked first)?
3. You open `/` and then `/index.html`. How many times does CloudFront go to S3, and why?
4. Why do you get 403 instead of 404 for a file that does not exist?
5. The `Age` header says 37. What does that mean?

<details><summary>Answers</summary>

1. S3 was not contacted: the copy came from the edge cache.
2. The cache starts empty for every path and every edge location, so the first request has nothing to serve.
3. Twice: `/` and `/index.html` are different cache keys.
4. The bucket policy lets CloudFront read objects only (`s3:GetObject`). Without permission to list the bucket, S3 does not reveal whether a key exists.
5. The copy was stored 37 seconds ago.
</details>

## Remember

- Hit = served by CloudFront. Miss = fetched from the origin.
- The cache key is the viewer's path.
- A new or changed distribution needs minutes to deploy.
- Keep this distribution and bucket for Labs 1 to 3.
