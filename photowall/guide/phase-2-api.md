# Phase 2: The API (about 60 minutes)

**Goal:** a real API (API Gateway → Lambda → DynamoDB) behind the **same CloudFront address**, with the list of photos cached for 30 seconds and everything else never cached.

**You will learn:** two origins and a `/api/*` behavior, a cache policy that **lets the API decide** the cache time, why the `Host` header must not be forwarded, and how much work the cache saves your Lambda.

Needs Phase 1 (`BUCKET`, `DIST`). At the end the page says *"The list comes from the API (/api/photos)"*.

```
https://DIST/api/photos  ->  CloudFront  ->  API Gateway (HTTP API)  ->  Lambda  ->  DynamoDB
https://DIST/anything else  ->  CloudFront  ->  S3
```

---

## Step 1: The table and the sample data (8 minutes)

DynamoDB → Create table: name `photowall`, partition key **`id`** (String), **On-demand**. Create.

Put the eight sample photos into it (CloudShell, inside the `photowall` folder):

```bash
python3 scripts/seed_table.py photowall --dry-run | tail -2        # look first
python3 scripts/seed_table.py photowall                           # 8 items written to photowall
```

## Step 2: The role of the function (8 minutes)

IAM → Roles → Create role → **AWS service → Lambda** → attach **AWSLambdaBasicExecutionRole** (it lets the function write logs) → name `photowall-api-role` → Create. Then open the role → **Add permissions → Create inline policy → JSON** (use your bucket name and your region/account id; the table ARN is on the table's page), name it `photowall-data`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": ["dynamodb:Scan", "dynamodb:UpdateItem", "dynamodb:PutItem"],
      "Resource": "arn:aws:dynamodb:us-west-2:ACCOUNT-ID:table/photowall" },
    { "Effect": "Allow", "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
      "Resource": ["arn:aws:s3:::BUCKET/photos/*", "arn:aws:s3:::BUCKET/thumbs/*"] },
    { "Effect": "Allow", "Action": "s3:ListBucket", "Resource": "arn:aws:s3:::BUCKET" }
  ]
}
```

The S3 lines are for Phase 4 (uploads); adding them now saves a trip back. Nothing else is allowed: the function cannot read other tables or other buckets.

## Step 3: The function (10 minutes)

Lambda → Create function → Author from scratch: name `photowall-api`, runtime **Python 3.12**, **use the existing role** `photowall-api-role`. In the editor replace the code with the content of [`api/lambda_function.py`](../api/lambda_function.py) → **Deploy**.

**Configuration → Environment variables** (add all four):

| Key | Value |
|---|---|
| `TABLE` | `photowall` |
| `BUCKET` | your bucket name |
| `UPLOAD_KEY` | a word of your choice, at least 8 characters (it is the "password" for uploads; do not write it in the repo) |
| `LIST_TTL_SECONDS` | `30` |

**Configuration → General configuration:** timeout **10 seconds**.

**Test it in the console** (Test tab → create an event, replace the whole JSON):

```json
{
  "requestContext": { "http": { "method": "GET" } },
  "rawPath": "/api/photos",
  "headers": {}
}
```

You should see status 200, a body with the eight photos, and the header `"Cache-Control": "public, max-age=30"`.

## Step 4: API Gateway (8 minutes)

API Gateway → **Create API** → **HTTP API** → Build. Integration: **Lambda**, function `photowall-api`. Name `photowall-http-api`. Routes: change the route to **`ANY`** `/api/{proxy+}` (not the default `ANY /photowall-api`). Stage: `$default`, **auto-deploy** on. Create.

Copy the **Invoke URL**, for example `https://abc123def4.execute-api.us-west-2.amazonaws.com`:

```bash
APIHOST=abc123def4.execute-api.us-west-2.amazonaws.com        # <- yours, no https://
curl -s https://$APIHOST/api/health                           # {"status": "ok"}
curl -s https://$APIHOST/api/photos | head -c 300
```

This is the API **without** CloudFront. Keep these two commands: you will compare them with the cached address.

## Step 5: Put it behind CloudFront (15 minutes)

**The cache policy.** CloudFront → Policies → Cache → Create: name `photowall-api-respect-origin`; TTL: minimum **0**, default **0**, maximum **300**; cache key: headers **None**, cookies **None**, query strings **All**; gzip and brotli on. Meaning: keep nothing unless the API asks, never more than 300 seconds.

**The origin.** Distribution → Origins → Create origin: domain = `abc123def4.execute-api.us-west-2.amazonaws.com` (host only), protocol **HTTPS only**, name `api`.

**The behavior.** Behaviors → Create: path pattern **`/api/*`**, origin `api`, viewer protocol Redirect HTTP to HTTPS, **allowed methods: all seven (GET, HEAD, OPTIONS, PUT, POST, PATCH, DELETE)**, cache policy `photowall-api-respect-origin`, **origin request policy: AllViewerExceptHostHeader** (managed).

Why that origin request policy? Two reasons you will meet again: API Gateway answers **403** if CloudFront forwards the visitor's `Host` header (it routes by its own name), and the custom header `X-Upload-Key` of Phase 4 would be **dropped** if nothing forwards it. This policy forwards all headers except `Host`.

Wait until the distribution is **Deployed**.

## Step 6: Test (10 minutes)

Open the site: the text now says **"The list comes from the API (/api/photos)"**, and the header table shows `x-cache` and `age`.

### Experiment 1: the list is cached for 30 seconds

**Predict:** three calls in a row, then a call after 30 seconds. Hit or Miss? Does the Lambda run each time?

```bash
SLEEP=12 bash scripts/check.sh https://$DIST/api/photos 5
```

**You should see:** `CC: public, max-age=30`; Miss, then Hits with `Age` 12, 24; after 30 seconds a Miss again with `Age` 0. The Lambda ran only on the Misses.

### Experiment 2: the API sets the cache time

Change `LIST_TTL_SECONDS` in the function's settings to `5`, **Save**, and repeat. Then set `0`. Then set it back to `30`.

**You should see:** `max-age=5` (a Hit for only a few seconds), then `no-store` (every call is a Miss). **The origin decides**, within the limits of the cache policy (0 to 300). Check the limit: set `LIST_TTL_SECONDS` to `999`: the function caps it to 300.

### Experiment 3: the Host header trap

Behaviors → `/api/*` → Edit → origin request policy **AllViewer**. Wait for Deployed.

```bash
bash scripts/check.sh "https://$DIST/api/photos?x=$RANDOM" 1
```

**You should see:** `403`. API Gateway does not know the name `dxxxx.cloudfront.net`. Change the policy back to **AllViewerExceptHostHeader** and wait for Deployed.

### Experiment 4: what the Lambda really did

Lambda → Monitor → **View CloudWatch logs**, or the **Invocations** graph. After a minute of testing you see a few invocations, not one per request. In Phase 6 you will count this properly.

---

## Questions

1. The Lambda sends `Cache-Control: public, max-age=30`. The cache policy has maximum 300. How long is the list kept? What if the Lambda said `max-age=1000`?
2. Why does the `/api/*` behavior allow all seven methods if only the list is cached?
3. Why is the origin request policy `AllViewerExceptHostHeader` and not `AllViewer`?
4. Which two things show in Lambda's logs when three visitors open the page within 10 seconds?

<details><summary>Answers</summary>

1. 30 seconds. For `max-age=1000` it would be cut to the maximum, 300 seconds.
2. Likes and uploads are POST requests and must reach the Lambda. POST answers are never cached whatever the policy says.
3. `AllViewer` forwards the visitor's `Host` header and API Gateway then answers 403. The other policy forwards everything else.
4. One invocation for the first visitor (a Miss); the other two are served from CloudFront's cache and never reach the Lambda.
</details>

## Remember

- The API tells CloudFront how long to keep an answer with `Cache-Control`; the cache policy sets the limits.
- Never forward the visitor's `Host` header to API Gateway or a Lambda URL.
- A behavior for `/api/*` needs all methods, or POST fails with 403.
- Your API works without CloudFront too (`$APIHOST`): CloudFront sits in front of it, it does not replace it.
