# Phase 4: Upload (about 50 minutes)

**Goal:** add a photo from the page. The browser makes the thumbnail and sends both files **straight to S3** with signed addresses.

**You will learn:** presigned S3 URLs, why the Lambda only hands out "permission slips", S3 **CORS**, how a custom header must be forwarded to the origin, and why every upload gets a new name.

Needs Phase 2 (`BUCKET`, `DIST`, and the function with `UPLOAD_KEY` set).

```
 1. browser -> POST /api/upload-urls  (header X-Upload-Key)  -> CloudFront -> Lambda
                                                                 <- two signed addresses (photo, thumbnail), valid 5 minutes
 2. browser -> PUT photo and thumbnail straight to S3 (not through CloudFront, not through the Lambda)
 3. browser -> POST /api/photos {id, title}  -> Lambda checks both files are there, then saves the title in DynamoDB
```

Why not send the photo to the Lambda? A function has size and time limits, and you would pay for it to carry bytes. S3 is built for that. The Lambda only decides **who may upload what**.

---

## Step 1: Let the page upload to S3 (CORS) (5 minutes)

The page lives at `https://dxxxx.cloudfront.net`, S3 at `https://BUCKET.s3.us-west-2.amazonaws.com`: two different addresses. A browser only allows a page to send a PUT to another address if that address says **yes**. S3 says yes through **CORS**.

S3 → bucket → Permissions → **Cross-origin resource sharing (CORS) → Edit**, paste only this list (the console wants the part between `[` and `]`):

```json
[
  {
    "AllowedOrigins": ["*"],
    "AllowedMethods": ["PUT"],
    "AllowedHeaders": ["*"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3000
  }
]
```

`"*"` means any site may ask. It is fine for a lab and you tighten it in Phase 5. Only a PUT is allowed, and only to a signed address, so a stranger still cannot write anything without a slip from your Lambda.

## Step 2: Upload a photo (10 minutes)

Open the site, choose a picture from your computer (a JPEG or PNG; very large ones are fine, the page shrinks them), type a title and **your upload key** (the `UPLOAD_KEY` word), press **Upload**.

You should see the status change: *Preparing the pictures → Asking for upload addresses → Uploading to S3 → Saving → Done*, and the new photo appears first on the wall.

Now look at what happened. F12 → **Network**, upload another one:

| Request | To | Notes |
|---|---|---|
| `POST /api/upload-urls` | your site (CloudFront) | the header `X-Upload-Key` is sent; the answer has an `id` and two signed addresses |
| `PUT photos/<id>.jpg` | `BUCKET.s3.us-west-2.amazonaws.com` | the browser sends the file straight to S3; the address has `X-Amz-Signature` and an expiry |
| `PUT thumbs/<id>.jpg` | the same | the thumbnail (400 px, made by the page itself with a canvas) |
| `POST /api/photos` | your site | `{id, title}`: the Lambda checks both files, then saves |

Check the files in S3: `aws s3 ls s3://$BUCKET/photos/` and `thumbs/`, and the **object properties** of the new photo: `Cache-Control: public, max-age=31536000, immutable`, `Content-Type: image/jpeg`. The signed address contained those headers, so the browser had to send exactly them.

## Step 3: Caching of the new photo (5 minutes)

```bash
ID=0123456789ab                      # <- the id you saw in the Network tab (12 characters)
bash scripts/check.sh https://$DIST/thumbs/$ID.jpg 3
```

**You should see:** a Miss, then Hits, with `CC: ... immutable`. Other visitors see the new photo in the **list** when the 30 seconds of the cached list are over (the page that uploaded it showed it at once).

## Step 4: Break it

### Break 1: the header that does not arrive

Behaviors → `/api/*` → Edit → **origin request policy: none**. Wait for Deployed. Try to upload again.

**You should see:** *The upload failed: Wrong upload key.* — although the key is right. With no origin request policy CloudFront sends the origin only what is in the cache key, so **`X-Upload-Key` is dropped**. Check without the page:

```bash
curl -s -w " -> %{http_code}\n" -X POST https://$DIST/api/upload-urls -H "X-Upload-Key: YOUR-WORD"          # 403 through CloudFront
curl -s -w " -> %{http_code}\n" -X POST https://$APIHOST/api/upload-urls -H "X-Upload-Key: YOUR-WORD" | head -c 120   # 200 straight to the API
```

Same request, two answers. This is how you find out whether CloudFront or the API is the problem. Put **AllViewerExceptHostHeader** back and wait.

### Break 2: CORS removed

Delete the CORS configuration of the bucket. Try to upload.

**You should see:** the status stops at *Uploading to S3* with an error, and the console (F12 → Console) says the request was blocked by **CORS policy**. The two PUT requests show in Network as failed. Put the CORS back.

### Break 3: wrong key, wrong body

```bash
curl -s -w " -> %{http_code}\n" -X POST https://$DIST/api/upload-urls -H "X-Upload-Key: wrong"             # 403 Wrong upload key
curl -s -w " -> %{http_code}\n" -X POST https://$DIST/api/photos -H "X-Upload-Key: YOUR-WORD" -H "Content-Type: application/json" -d '{"id":"0123456789ab","title":"x"}'   # 400: the upload is not complete
```

The second one shows the check in `POST /api/photos`: the Lambda only saves a photo whose **both files exist in S3**, are JPEGs, and are small enough (3 MB and 300 KB; the Lambda deletes files that are too big).

### Break 4: a signed address expires

Ask for addresses, wait 6 minutes, and try the PUT:

```bash
curl -s -X POST https://$DIST/api/upload-urls -H "X-Upload-Key: YOUR-WORD" -o /tmp/u.json
URL=$(python3 -c "import json;print(json.load(open('/tmp/u.json'))['photo']['url'])")
sleep 370
curl -s -X PUT "$URL" -H "Content-Type: image/jpeg" -H "Cache-Control: public, max-age=31536000, immutable" --data-binary @site/photos/sample01.jpg | head -c 300
```

**You should see:** an XML error `Request has expired`. A signed address is a **time-limited permission slip**: that is its whole security.

---

## Questions

1. Why does the page send the photo to S3 and not to the Lambda?
2. What does CORS protect, and who checks it, the server or the browser?
3. Why did the upload fail with "Wrong upload key" when the origin request policy was removed?
4. Why does every uploaded photo get a new id, and what does that give the cache?
5. Anyone can call `/api/upload-urls`. What stops a stranger from filling your bucket?

<details><summary>Answers</summary>

1. A function has size and time limits and you pay for its time; S3 receives files directly. The Lambda only signs the permission.
2. CORS stops a page on one site from sending requests to another site without that site's permission. The **browser** enforces it, using headers the **server** (S3) sends.
3. Without the policy CloudFront does not forward `X-Upload-Key`, so the Lambda sees no key.
4. The id is new every time, so a photo never changes under its name: it can be cached for a year, with no invalidation.
5. The upload key (without it there are no addresses), the short life of the signed addresses, the size checks, and in Phase 5 a rate limit.
</details>

## Remember

- Big files go straight to S3 with a signed address. The function only signs.
- A header the origin needs must be forwarded: origin request policy.
- CORS is the browser asking the other server "may I?".
- New content gets a new name: that is what makes a one-year cache safe.
