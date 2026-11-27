# Phase 1: The page and the photos (about 50 minutes)

**Goal:** the PhotoWall page and the sample photos come from a **private S3 bucket** through **CloudFront**, with the right cache time for each kind of file.

**You will learn:** why different files get different cache times, how to set `Cache-Control` on S3 objects, and a bucket policy that opens **only the paths you name**.

At the end the page shows the wall from a static list and says *"The list comes from a static file: there is no API yet"*. The API arrives in Phase 2.

Region: **us-west-2**. Work in the console and in AWS CloudShell (the `>_` icon).

---

## Step 1: The bucket (3 minutes)

S3 → Create bucket: name `photowall-<yourname>-<4 digits>` (unique, lowercase), region **us-west-2**, **Block all public access: ON**. Create. Write the name down: this guide calls it `BUCKET`.

## Step 2: Get the files and upload them (5 minutes)

In CloudShell:

```bash
git clone --depth 1 --filter=blob:none --sparse https://github.com/dailycoder09/devops-practice-01.git
cd devops-practice-01 && git sparse-checkout set photowall && cd photowall
export AWS_DEFAULT_REGION=us-west-2
BUCKET=photowall-yourname-1234            # <- your bucket
```

Upload in **three groups**, because each group has its own cache time:

```bash
# 1. the page and the list: one minute
aws s3 cp site/index.html s3://$BUCKET/index.html --cache-control "public, max-age=60"
aws s3 cp site/data/photos.json s3://$BUCKET/data/photos.json --cache-control "public, max-age=60"
# 2. files that never change under their name: one year
aws s3 cp site/assets/ s3://$BUCKET/assets/ --recursive --cache-control "public, max-age=31536000, immutable"
aws s3 cp site/photos/ s3://$BUCKET/photos/ --recursive --cache-control "public, max-age=31536000, immutable"
aws s3 cp site/thumbs/ s3://$BUCKET/thumbs/ --recursive --cache-control "public, max-age=31536000, immutable"

aws s3 ls s3://$BUCKET/ --recursive | wc -l       # 21 files
```

Why these times? The **page** (`index.html`) keeps its name, so it must expire quickly or a new release would not show. The **assets and photos** are different: `app.v1.js` will never be changed, a new release will be `app.v2.js` (Lab 3 of the CloudFront lab). A photo gets a new id when it is uploaded. So they can be kept for a year.

## Step 3: The distribution (10 minutes, then wait)

CloudFront → Create distribution. Name `photowall`. Origin: your bucket (the S3 origin, not the website endpoint), **Grant CloudFront access: Yes** (OAC). Cache settings: the S3 defaults (CachingOptimized). **Security protections:** leave **off** for now if the wizard lets you (WAF is Phase 5). Create. Then set **Default root object** to `index.html` (General → Edit), if the wizard did not ask. Price class: North America and Europe only, if offered.

Wait until it is **Deployed**. Copy the **distribution domain name**:

```bash
DIST=dxxxxxxxxxxxx.cloudfront.net         # <- yours, without https://
```

## Step 4: Open only what the site needs (5 minutes)

The wizard put a bucket policy on the bucket that lets CloudFront read **everything**. Replace it with one that names only what the site serves. S3 → bucket → Permissions → Bucket policy → Edit (use your own bucket name, account id and distribution id; the console's "Copy policy" text shows the exact `SourceArn` value to reuse):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowCloudFrontRead",
      "Effect": "Allow",
      "Principal": { "Service": "cloudfront.amazonaws.com" },
      "Action": "s3:GetObject",
      "Resource": [
        "arn:aws:s3:::BUCKET/index.html",
        "arn:aws:s3:::BUCKET/assets/*",
        "arn:aws:s3:::BUCKET/data/*",
        "arn:aws:s3:::BUCKET/photos/*",
        "arn:aws:s3:::BUCKET/thumbs/*"
      ],
      "Condition": { "StringEquals": { "AWS:SourceArn": "arn:aws:cloudfront::ACCOUNT-ID:distribution/DISTRIBUTION-ID" } }
    },
    {
      "Sid": "AllowCloudFrontList",
      "Effect": "Allow",
      "Principal": { "Service": "cloudfront.amazonaws.com" },
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::BUCKET",
      "Condition": { "StringEquals": { "AWS:SourceArn": "arn:aws:cloudfront::ACCOUNT-ID:distribution/DISTRIBUTION-ID" } }
    }
  ]
}
```

Why list the paths? Later phases add other things to the same bucket. Naming the paths means nothing else can become public by accident. `ListBucket` makes a missing file answer **404** and not 403.

## Step 5: Open the site and test (10 minutes)

Open `https://<DIST>`. You should see the eight photos, the text *"The list comes from a static file (/data/photos.json): there is no API yet"*, and a table of headers for the list request.

You will use the check script all the time: `bash scripts/check.sh <URL> <how many times>` prints X-Cache (Hit or Miss), Age, Cache-Control and the edge location for any address.

### Exercise 1: three kinds of file, three cache times

**Predict:** which of these has a short `Cache-Control` and which a long one?

```bash
bash scripts/check.sh https://$DIST/index.html 2
bash scripts/check.sh https://$DIST/data/photos.json 2
bash scripts/check.sh https://$DIST/assets/app.v1.js 2
bash scripts/check.sh https://$DIST/thumbs/sample01.jpg 2
```

**You should see:** `CC: public, max-age=60` for the first two; `public, max-age=31536000, immutable` for the last two. The first request of each is a Miss (or a Hit if the page already loaded it), then Hit. CloudFront takes the TTL from the `Cache-Control` header you set on the S3 object.

### Exercise 2: thumbnails are the point

```bash
curl -s -o /dev/null -w "photo:     %{size_download} bytes\n" https://$DIST/photos/sample05.jpg
curl -s -o /dev/null -w "thumbnail: %{size_download} bytes\n" https://$DIST/thumbs/sample05.jpg
```

The wall loads eight thumbnails, not eight full photos. Only the photo you click is fetched in full size. Smaller files mean faster pages and a smaller bill (you pay for data out).

### Exercise 3: only the named paths are open

```bash
bash scripts/check.sh https://$DIST/index.html 1                       # 200
bash scripts/check.sh https://$DIST/private-note.txt 1                 # 404 or 403: it is not in the bucket, and not in the policy
echo "secret" > /tmp/secret.txt && aws s3 cp /tmp/secret.txt s3://$BUCKET/secret.txt
bash scripts/check.sh https://$DIST/secret.txt 1                       # 403: the file exists, but the policy does not name it
```

(A cached 403 lasts about 10 seconds. Remove the file again: `aws s3 rm s3://$BUCKET/secret.txt`.)

### Exercise 4: a new release of the page

Change the text of the page and see the rule of one minute in action:

```bash
sed -i 's/Version 1/Version 2/' site/index.html
aws s3 cp site/index.html s3://$BUCKET/index.html --cache-control "public, max-age=60"
for i in 1 2 3 4 5 6; do curl -s https://$DIST/index.html | grep -o "Version [0-9]"; sleep 15; done
```

**You should see:** *Version 1* for a while and *Version 2* after the copy has expired (at most about a minute). **Always upload first, then wait or invalidate**: an invalidation before the upload re-caches the old file.

---

## Questions

1. Why does `index.html` get 60 seconds and `app.v1.js` one year?
2. What would happen if you set one year on `index.html`?
3. Why does the bucket policy list five paths instead of `BUCKET/*`?
4. The wall loads thumbnails. What two things does that save?

<details><summary>Answers</summary>

1. `index.html` keeps its name, so a new release only reaches visitors when its copy expires. A versioned file never changes under its name, so an old copy is never wrong.
2. A new release would stay invisible for up to a year unless you invalidate it every time.
3. So that nothing added to the bucket later (uploads, temporary files) becomes public without you deciding it.
4. Time (smaller files load faster) and money (less data out).
</details>

## Remember

- The cache time of an S3 file is the `Cache-Control` you put on the object.
- Same name, short cache. New name for every version, long cache.
- Name the paths that CloudFront may read.
- Keep this bucket and distribution for the next phases.
