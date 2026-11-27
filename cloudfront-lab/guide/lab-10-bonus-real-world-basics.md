# Lab 10 (bonus): The basics real projects need (about 105 minutes)

**Goal:** fill the gaps that the first labs left: an **ALB / EC2 as origin**, **S3 REST vs website endpoint**, a **single-page app** setup, **range requests**, and **what CloudFront costs**.

**You will learn:** what CloudFront sends to a dynamic origin and how to control it, how to lock an origin so only CloudFront can reach it, why `/docs/` fails on an S3 origin and how to fix it, why a custom error page can break an API, how video-style range requests look, and how to estimate a bill.

Needs the distribution and bucket from Lab 0, and the cache policy `cf-lab-api-respect-origin` from Lab 2 (`BUCKET`, `DIST`). Parts 1 and 2 are independent of each other; Part 3 comes after Part 2 (it uses its `/dyn/` origin).

New files used here: [`origin/user-data.sh`](../origin/user-data.sh) (the web server), [`functions/edge-directory-index.js`](../functions/edge-directory-index.js) and [`functions/edge-spa.js`](../functions/edge-spa.js). The web server and both functions were tested on a computer; the AWS steps were not run (see the README note about console names).

---

## Part 1: S3 REST endpoint, website endpoint, and sub-folders (20 minutes)

An S3 bucket has **two** kinds of address, and the CloudFront console shows both:

| | REST endpoint | Website endpoint |
|---|---|---|
| Looks like | `BUCKET.s3.us-west-2.amazonaws.com` | `BUCKET.s3-website-us-west-2.amazonaws.com` |
| In the origin list | yes (it is the one Lab 0 used) | no: you must type it, and CloudFront then treats it as an ordinary web server |
| OAC (private bucket) | **yes** | **no**: the bucket must be public |
| HTTPS from CloudFront to S3 | yes | **no**, HTTP only |
| Index document for sub-folders (`/docs/`) | **no** | yes |
| Redirect rules | no | yes |

**Prefer the REST endpoint with OAC** (private bucket). Use the website endpoint only if you truly need its index documents or redirects, and accept that the bucket is public.

**Try the website endpoint (it stays private, so it fails):** S3 → your bucket → Properties → **Static website hosting** → Enable, index document `index.html` → Save. Then:

```bash
curl -sI http://$BUCKET.s3-website-us-west-2.amazonaws.com/ | head -3          # 403 Forbidden: the bucket is private
```

Switch static website hosting **off** again afterwards.

**The sub-folder problem.** Make a page in a folder:

```bash
mkdir -p /tmp/docs && echo "<h1>The docs</h1>" > /tmp/docs/index.html
aws s3 cp /tmp/docs/index.html s3://$BUCKET/docs/index.html --cache-control "public, max-age=60"
```

**Predict:** `https://DIST/` works because of the default root object. Does `https://DIST/docs/` work?

```bash
bash scripts/check.sh https://$DIST/docs/ 1               # 404 (or 403): the object "docs/" does not exist
bash scripts/check.sh https://$DIST/docs/index.html 1     # 200
```

The default root object applies **only to the root**. For sub-folders, a CloudFront Function can add the missing `index.html`:

1. CloudFront → Functions → Create `cf-lab-directory-index` (runtime **cloudfront-js-2.0**), paste `functions/edge-directory-index.js`, Save, test with `/docs/` (the output URI must be `/docs/index.html`), **Publish**.
2. **Associate** it with your distribution, **Default (\*)**, event **Viewer request**. If a viewer-request function from Lab 5 is already there, **replace** it: a behavior has one function per event.
3. Wait for Deployed.

```bash
bash scripts/check.sh https://$DIST/docs/ 2               # 200 and <h1>The docs</h1>
```

## Part 2: A load balancer (or a server) as origin (45 minutes)

Most real origins are not S3: they are an ALB in front of servers. The tiny server in `origin/user-data.sh` answers any path with the **headers it received**, so you can see exactly what CloudFront sends. It also supports `?ttl=N` (like the Lambda of Lab 2), `/health`, and `/.../missing` (a 404).

**Cost note:** one small server and one ALB for about an hour. Delete everything at the end of the lab.

### 2a. Build the origin (20 minutes)

Use any VPC with two public subnets (your default VPC, or the one from DropLink).

1. **Security groups** (EC2 → Security Groups). Name them clearly:
   - `lab-origin-alb-sg`: inbound **HTTP 80 from anywhere (0.0.0.0/0)** for now. You lock it down in 2e.
   - `lab-origin-vm-sg`: inbound **Custom TCP 8080** from the source `lab-origin-alb-sg`.
2. **The server.** Launch instance: Amazon Linux 2023, `t3.micro`, **no key pair**, security group `lab-origin-vm-sg`, **Advanced details → User data: paste the whole `origin/user-data.sh`**. It needs no internet access and no IAM role.
3. **Target group** (EC2 → Target Groups): type Instances, name `lab-origin-tg`, HTTP **8080**, health check path `/health`. Register the instance.
4. **Load balancer** (Application, internet-facing): name `lab-origin-alb`, two public subnets, security group `lab-origin-alb-sg`, listener **HTTP 80** forward to `lab-origin-tg`.
5. Wait until the target is **healthy** (2 to 4 minutes). Then:

```bash
ALB=lab-origin-alb-1234567890.us-west-2.elb.amazonaws.com        # <- the DNS name of your ALB
curl -s "http://$ALB/anything?ttl=20" | head -20
```

You should see JSON with `served_at`, `request_id`, `server` and a `headers` section: this is what the origin gets from **you** directly.

### 2b. Put CloudFront in front (10 minutes)

1. Distribution → **Origins** → Create origin: origin domain = the ALB DNS name, protocol **HTTP only**, port 80, name `alb-origin`. (HTTPS to the ALB would need a certificate on the ALB that matches the name CloudFront asks for: that means your own domain. This lab keeps the hop CloudFront → ALB on HTTP, **which you should not do for real data**.)
2. **Behaviors** → Create: path pattern `/dyn/*`, origin `alb-origin`, viewer protocol Redirect HTTP to HTTPS, cache policy **`cf-lab-api-respect-origin`** (Lab 2), origin request policy **none**.
3. Wait for Deployed.

### 2c. What does the origin see? (10 minutes)

**Predict:** which of these headers will the server receive through CloudFront: `host`, `cookie`, `x-forwarded-for`, `user-agent`, `via`, `x-amz-cf-id`? What is the `host`?

```bash
curl -s "https://$DIST/dyn/headers?a=1" -H "Cookie: session=abc" | python3 -c "import sys,json; d=json.load(sys.stdin); [print(k,'=',v) for k,v in d['headers'].items()]; print('query seen:', repr(d['query']))"
```

**You should see** (write down what you actually get): `host` is the **ALB's own name**, not `dXXXX.cloudfront.net` (CloudFront sets it to the origin domain); the `cookie` is **missing**; the query string `a=1` is also **missing** (`query seen: ''`) because the cache policy does not include `a`; and CloudFront adds its own headers such as `via`, `x-amz-cf-id` and a `user-agent` of Amazon CloudFront.

Now change what is sent. Behavior `/dyn/*` → Edit → **Origin request policy: AllViewer** (managed). Wait for Deployed and repeat the command:

**You should see:** `host` is now `dXXXX.cloudfront.net` (the viewer's Host), the `cookie` arrives, and the query string arrives. This works with an ALB, which does not care about the Host header. With the Lambda URL of Lab 2 the same setting gave **403**: the origin decides which settings are safe.

Try the managed policy **AllViewerAndCloudFrontHeaders-2022-06**: the origin now also receives headers such as `cloudfront-viewer-country`. This is how an app knows the visitor's country without any lookup.

**Remember the rule from Lab 2:** a header sent to the origin but not in the **cache key** does not split the cache. Check it: with `AllViewer` and `?ttl=20`, send two different cookies and compare `request_id`:

```bash
BODY=1 bash scripts/check.sh "https://$DIST/dyn/headers?ttl=20" 1 -H "Cookie: u=1"
BODY=1 bash scripts/check.sh "https://$DIST/dyn/headers?ttl=20" 1 -H "Cookie: u=2"
```

**You should see:** the second is a **Hit with the same request_id** although the cookie differs (the cookie is not in the cache key). The origin never saw `u=2`: **never forward personal data to an origin and then cache the answer**. Put into the cache key everything the answer depends on.

### 2d. Dynamic content and the cache (5 minutes)

```bash
SLEEP=5 BODY=1 bash scripts/check.sh "https://$DIST/dyn/headers?ttl=20" 6
```

Same story as Lab 2, now with a load balancer: Miss, Hits with the same `request_id`, a new one after 20 seconds.

### 2e. Lock the origin: only CloudFront may reach the ALB (10 minutes)

Right now anyone can call the ALB directly and skip CloudFront (and any WAF on it).

1. Edit `lab-origin-alb-sg`: **delete** the `0.0.0.0/0` rule and add inbound HTTP 80 with the source being the **managed prefix list `com.amazonaws.global.cloudfront.origin-facing`** (it is in the list of sources). This list holds CloudFront's address ranges. (It counts as about 55 rules of the default 60 per security group: that is normal, but you cannot add many other rules to the same group.)
2. Test:

```bash
curl -s -m 8 -o /dev/null -w "direct to the ALB: %{http_code}\n" "http://$ALB/anything"      # no answer: connection timed out (curl exit code 28)
bash scripts/check.sh "https://$DIST/dyn/headers?x=$RANDOM&ttl=1" 1                          # still works through CloudFront
```

**A second layer (optional, 10 minutes).** The prefix list lets in **every** CloudFront distribution in the world, also somebody else's. To allow only yours, add a secret header:
- CloudFront → Origins → `alb-origin` → Edit → **Add custom header**: name `X-Origin-Verify`, value a long random word (not a real secret in a lab: you will delete it).
- ALB → listener → **Edit rules**: add a rule, condition **HTTP header** `X-Origin-Verify` equals that value, action forward; change the **default action** to **Return fixed response 403**.
- A request through your distribution works; the same request through another distribution (or with the header missing) gets 403.

## Part 3: A single-page app (15 minutes)

A React or Vue site has one real file, `index.html`. A link such as `/products/42` is not a file: the browser's JavaScript draws that screen. If a visitor opens it directly, S3 says "no such file". Two ways to fix it.

**Predict first:** `https://DIST/products/42` today:

```bash
bash scripts/check.sh https://$DIST/products/42 1       # 404 (or 403)
```

### Way 1: custom error responses (the common way, with a catch)

Distribution → **Error pages** → Create custom error response, **twice**: HTTP error code **403** and **404**, customize: Yes, response page path `/index.html`, HTTP response code **200**, error caching minimum TTL **10**. (If you made the Lab 6 error pages, replace them for now.) Wait for Deployed.

```bash
bash scripts/check.sh https://$DIST/products/42 1              # 200: the index page
curl -s -i "https://$DIST/dyn/missing" | head -12              # the ALB says 404 with JSON ...
```

**You should see:** the second call is **200 with the HTML of the site**, although the API said 404. Custom error responses apply to the **whole distribution**, not to one behavior, so a real API error is replaced by the web page. A client that expects JSON breaks, and monitoring never sees a 404.

### Way 2: a function on the default behavior only

1. **Remove** the two custom error responses.
2. Create a function `cf-lab-spa` with `functions/edge-spa.js`, test with `/products/42` (the output URI is `/index.html`), Publish, and associate it as **Viewer request** on the **Default (\*)** behavior only (replace the directory-index function; for the lab that is fine).

```bash
bash scripts/check.sh https://$DIST/products/42 1              # 200, the index page
curl -s -i "https://$DIST/dyn/missing" | head -12              # 404 with the JSON body: the API keeps its real errors
```

The function runs only for the default behavior, so `/dyn/*` and `/api/*` are untouched. The price: you maintain a small piece of code.

## Part 4: Range requests (10 minutes)

Video players and download managers ask for **part** of a file: `Range: bytes=0-99`.

```bash
curl -s -D - -o /dev/null -H "Range: bytes=0-99" -H "Accept-Encoding: identity" https://$DIST/data/big.json | grep -iE "^HTTP|content-range|content-length|accept-ranges|x-cache"
```

**You should see:** `206 Partial Content`, `Content-Range: bytes 0-99/401312`, `Content-Length: 100`, `Accept-Ranges: bytes`. Run it again with another range, and then once without `Range`:

```bash
for r in "0-99" "100-199" ""; do
  args=(); [ -n "$r" ] && args=(-H "Range: bytes=$r")
  echo "range [$r]"; curl -s -D - -o /dev/null "${args[@]}" -H "Accept-Encoding: identity" https://$DIST/data/big.json | grep -iE "^HTTP|x-cache|content-length"
done
```

Write down which of these three were Hit or Miss. CloudFront can answer a range from a copy it already holds and can ask the origin for just the range. Streaming video works this way (and HLS uses many small files, which are even easier to cache). Why does it matter? A viewer who skips ahead in a long video does not download the whole file, and your origin is not asked for the whole file again.

## Part 5: What does CloudFront cost? (15 minutes)

You pay for a few things. Look the prices up in the console (the **plan** page) and the AWS Pricing Calculator (calculator.aws), because they change:

| Item | You pay for |
|---|---|
| **Data transfer out** | gigabytes sent to viewers, cheaper in the regions of your **price class** (you set `PriceClass_100` in Lab 0: the cheapest regions only) |
| **Requests** | per 10,000 HTTP/HTTPS requests |
| **Invalidations** | the first 1,000 paths a month are free |
| **CloudFront Functions** | per million invocations (first 2 million a month are free) |
| **Lambda@Edge** | per million requests plus the run time |
| **WAF** | a monthly price per web ACL and per rule, plus a price per million requests |
| **Real-time logs, Origin Shield, extra metrics, dedicated IPs** | each has its own price |
| **Free** | data from AWS origins (S3, ALB, EC2) **to** CloudFront; standard logs delivery (you pay for the S3 storage) |

CloudFront also has a monthly **free allowance** (the always-free tier covers 1 TB of data out and 10 million requests), and your console showed a **plan** step with "Free ($0/month)": AWS now also sells flat monthly plans with included usage. I have not checked what each plan includes: read the plan page before you pick one for real use.

**Exercise: three estimates.** Open the Pricing Calculator, add CloudFront, and fill the table with the monthly result for each case (note your assumptions: region mix, average object size, % cache hit):

| Case | Data out | Requests | Your estimate |
|---|---|---|---|
| A: a small blog | 50 GB | 1 million | |
| B: an image site | 2 TB | 100 million | |
| C: a video site | 20 TB | 10 million | |

**Exercise: your lab's real numbers.** Distribution → Monitoring: the **Requests** and **BytesDownloaded** of the last day. If this traffic went on for a month, what would it cost?

**Ways to lower the bill:** a higher cache hit ratio (versioned files, longer TTLs, a smaller cache key); compression (less data); a price class that fits where your users are; no routine invalidations; WAF only where you need it; Origin Shield only if your origin is expensive to hit.

## Clean up

- Delete: the ALB, the target group, the instance, and the two security groups.
- Static website hosting: off. Custom error responses: remove or restore your Lab 6 pages.
- Re-associate the functions you want (Lab 5), or leave the behavior without a function.
- Remove the `/dyn/*` behavior and the `alb-origin` if you no longer need them.

---

## Questions

1. Why can't CloudFront use OAC with the S3 website endpoint, and what does that mean for the bucket?
2. `/` works but `/docs/` gives 404 on an S3 REST origin. Why? Name one fix.
3. What `Host` header does an ALB origin receive by default, and what changes with the AllViewer policy?
4. Why is it dangerous to forward a user's cookie to the origin without putting it in the cache key?
5. Why does a custom error response for 404 break an API behind the same distribution?
6. What does the managed prefix list in the ALB security group do, and what does the secret header add?
7. Name three things you can do to lower a CloudFront bill.

<details><summary>Answers</summary>

1. The website endpoint is an ordinary public web server to CloudFront, and OAC works only with the S3 REST endpoint. The bucket must be public for the website endpoint.
2. The default root object applies to the root only, and the REST endpoint has no index document for sub-folders. Fix: a CloudFront Function that adds `index.html` to a path ending with `/` (or the website endpoint with a public bucket).
3. The ALB's own DNS name (CloudFront sets Host to the origin domain). With AllViewer it receives the viewer's Host (`dXXXX.cloudfront.net`).
4. The first user's answer is cached and served to everybody (the second user's cookie never reaches the origin, so the answer cannot depend on it). Anything the answer depends on must be in the cache key.
5. Custom error responses apply to the whole distribution, so an API's real 404 becomes the page `/index.html` with status 200.
6. The prefix list lets in only CloudFront's address ranges (all CloudFront customers). The secret header lets in only requests that carry it, that is, your distribution.
7. For example: raise the hit ratio (versioned files, longer TTL, a smaller cache key), compress text, choose a price class that fits your viewers, stop routine invalidations, use WAF only where needed.
</details>

## Remember

- REST endpoint + OAC for a private bucket; the website endpoint means a public bucket.
- The default root object is for `/` only.
- The origin sees what the **origin request policy** and the cache key allow: check it with an echo server.
- Lock the origin: security group with the CloudFront prefix list, plus a secret header.
- A single-page app: a function on the default behavior beats custom error pages when you also have an API.
- Cost = data out + requests (+ extras). Data from AWS origins to CloudFront is free.
