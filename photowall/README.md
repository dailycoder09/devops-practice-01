# PhotoWall

A small photo wall to learn how a **UI with images** and a **simple API** work together behind **CloudFront**, with **S3, API Gateway, Lambda, DynamoDB** and your own domain. The code is tiny on purpose (one HTML page, one JavaScript file, one Python function): the learning is in the AWS setup and in the question *"what do I cache, and for how long?"*.

> **Status.** Phases 1 to 6 are written. **Nothing here has been run on AWS yet.** The page script, the Lambda function (15 tests), the seeding script and the file layout were tested on a computer. **The Terraform of Phase 6 was only checked for balanced brackets, it has not been through `terraform validate`**: the first step of Phase 6 is to validate it in CloudShell. Console button names are written from memory: if a name differs, look for the same idea nearby, and tell me where the guide and the console disagree.

## What it does

- Shows a wall of photos (thumbnails). Click one to see it big.
- Each photo has a heart: click it to add a like.
- Add a photo: the **browser** makes a thumbnail, asks the API for two signed S3 addresses, and sends both files **straight to S3**. A short key (a word you choose) protects uploads: there is no login.

## The picture

```
 visitor
    |   https://photowall.shaikhirfan.com   (Route 53 alias -> CloudFront, HTTPS)
    v
 +--------------------------------------------------------------------------+
 |  CLOUDFRONT                                                              |
 |    /api/*   -> API Gateway -> Lambda -> DynamoDB      (the list: cached 30 s, the rest: never)
 |    all else -> S3 bucket (private, OAC)                                  |
 |                 index.html, data/      cached 60 s                       |
 |                 assets/, photos/, thumbs/   cached 1 year (never change under their name)
 +--------------------------------------------------------------------------+
    ^
    |   upload: the browser PUTs the photo and the thumbnail straight to S3 (signed addresses from the Lambda)
    +-- S3 (CORS allows the PUT)
```

## What is cached (the heart of the project)

| Path | Where it comes from | Cached for | Why |
|---|---|---|---|
| `/` , `/index.html`, `/data/photos.json` | S3 | **60 s** | a new release shows within a minute |
| `/assets/*` (versioned file names) | S3 | **1 year** | the name changes with every release |
| `/photos/*`, `/thumbs/*` | S3 | **1 year** | a photo never changes under its id |
| `GET /api/photos` (the list) | Lambda | **30 s** (a setting) | the same for everybody, read all the time |
| `POST /api/photos/{id}/like` | Lambda | **never** | it changes data |
| `POST /api/upload-urls`, `POST /api/photos` | Lambda | **never** | they change data and need the key |

## The phases

Do them in order. Each ends with a working app, and each can be rebuilt in one sandbox (about 4 hours; Phases 1 to 5 take about 4 hours together).

| Phase | You build | You learn | Time |
|---|---|---|---|
| [1 The page and the photos](guide/phase-1-ui-and-photos.md) | a private S3 bucket and CloudFront (OAC) | cache headers per path, TTL from S3, bucket policy for exact paths | 50 min |
| [2 The API](guide/phase-2-api.md) | DynamoDB, Lambda, API Gateway, a `/api/*` behavior | two origins, a cache policy that respects the origin, the Host header | 60 min |
| [3 Likes](guide/phase-3-likes.md) | nothing new: you use it | a POST is never cached, why a cached list is slightly old, the TTL trade-off | 30 min |
| [4 Upload](guide/phase-4-upload.md) | S3 CORS, the upload key | signed S3 addresses, forwarding a header to the origin, CORS | 50 min |
| [5 Your domain, headers, a rate limit](guide/phase-5-domain-and-security.md) | Route 53 + ACM, a response headers policy, a WAF rule | HTTPS on your own name, CSP, rate limiting | 60 min |
| [6 Watch it, then rebuild it with Terraform](guide/phase-6-observe-and-terraform.md) | CloudWatch checks, Terraform | how much CloudFront saves your API, the whole thing as code | 60 min |

## Files

```
photowall/
  README.md                this page
  guide/                   the six phases
  site/                    what goes to S3
    index.html  assets/app.v1.js  assets/style.v1.css
    data/photos.json       the sample list (Phase 1 shows it, the API replaces it in Phase 2)
    photos/  thumbs/       8 sample photos and their 400 px thumbnails (credits in photos/CREDITS.md)
  api/                     the Lambda function and its tests
  scripts/                 check.sh (what CloudFront says), seed_table.py (sample data into DynamoDB)
  terraform/               the whole project as code (Phase 6)
```

## Try the page on your computer (no AWS)

```bash
cd site && python3 -m http.server 8000        # then open http://localhost:8000
```

You see the wall from the static list. The API does not exist there, so likes and uploads show an error: that is expected. Press Ctrl+C to stop.

## Run the tests (no AWS)

```bash
cd api && python3 -m unittest -q test_lambda_function        # 15 tests
cd ../scripts && python3 -m unittest -q test_seed_table      # 3 tests
```

## Know before you start

- **No login.** Anyone who opens the site can like photos. Only people who know the **upload key** can add photos. Do not share the address widely, and delete everything when you finish: this is a learning project.
- **The list shows at most 100 photos** and is read with a scan: fine for learning, not for a big site.
- **A sandbox is wiped** after about 4 hours (with it the DNS zone, so Phase 5 needs new name servers at your registrar each time).
- **Cost** outside a sandbox is small, but WAF (Phase 5) and CloudFront extras are not free: see the cleanup list at the end of Phase 6.
- Sample photos: CC0 / public domain (see `site/photos/CREDITS.md`).
