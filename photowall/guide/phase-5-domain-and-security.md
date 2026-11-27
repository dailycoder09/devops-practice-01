# Phase 5: Your domain, security headers and a rate limit (about 60 minutes)

**Goal:** the site opens at `https://photowall.shaikhirfan.com`, with security headers, an upload address limited to your site, and a WAF rule that slows down someone who clicks the heart ten thousand times.

**You will learn:** a sub-domain with Route 53 and ACM, a response headers policy with a **CSP** that fits this app, a tighter S3 CORS, and a WAF **rate-based rule**.

Needs Phases 1 to 4 (`BUCKET`, `DIST`). The DNS part is the slow one: do **Part 1** first and let the name spread while you do the rest.

---

## Part 1: Your own address with HTTPS (about 25 minutes, plus waiting)

You did this for DropLink and in the CloudFront lab. Same steps, with a **sub-domain** so that nothing else of `shaikhirfan.com` is touched.

1. **Hosted zone.** Route 53 → Hosted zones → Create: name `photowall.shaikhirfan.com`, Public. Copy its four name servers.
2. **At your registrar** add **four NS records** named `photowall` with those four values (a new sandbox gives new values: repeat each time).
3. **Check it spread** (Mac terminal or CloudShell): `dig NS photowall.shaikhirfan.com +short` shows the four `awsdns` names. This can take minutes to hours.
4. **Certificate.** Switch the console to **N. Virginia (us-east-1)** → Certificate Manager → Request a public certificate for `photowall.shaikhirfan.com`, DNS validation → **Create records in Route 53** → wait for **Issued** (it only works once step 3 shows the servers).
5. **Attach it.** CloudFront → distribution → General → Edit: Alternate domain name `photowall.shaikhirfan.com`, Custom SSL certificate: that one. Wait for Deployed.
6. **Point the name.** Route 53 → the zone → Create record: name empty (the zone itself), type **A**, Alias → CloudFront distribution → yours. Add the same as **AAAA** if you like.

```bash
curl -sI http://photowall.shaikhirfan.com | head -3          # 301 to https
bash scripts/check.sh https://photowall.shaikhirfan.com/api/photos 2
```

**Predict:** the cache lives in CloudFront, not in the name. Is `https://photowall.shaikhirfan.com/api/photos` a Hit if you just asked for the same path on the `cloudfront.net` name?

**You should see:** a **Hit**. The cache key is the path, not the host name: two names for the same distribution share one cache.

## Part 2: Let only your site upload (5 minutes)

Tighten the S3 CORS from Phase 4: replace `"AllowedOrigins": ["*"]` by the addresses of your site:

```json
"AllowedOrigins": ["https://photowall.shaikhirfan.com", "https://dxxxxxxxxxxxx.cloudfront.net"]
```

(Use your real `cloudfront.net` name.) Upload a photo from the page: it still works. Then try from a place that is not allowed, to see the refusal that the browser enforces:

```bash
curl -s -i -X OPTIONS "https://$BUCKET.s3.us-west-2.amazonaws.com/photos/x.jpg" \
  -H "Origin: https://evil.example" -H "Access-Control-Request-Method: PUT" | head -5
```

**You should see:** `403 CORSResponse: This CORS request is not allowed`. With a listed origin the answer is 200 with `access-control-allow-origin`.

## Part 3: Security headers (response headers policy) (15 minutes)

CloudFront → Policies → Response headers → Create: name `photowall-security`:
- Strict-Transport-Security: max age `31536000`, include subdomains, override.
- X-Content-Type-Options: on. X-Frame-Options: `DENY`. Referrer-Policy: `strict-origin-when-cross-origin`.
- **Content-Security-Policy:** `default-src 'self'; img-src 'self'; connect-src 'self' https://BUCKET.s3.us-west-2.amazonaws.com; frame-ancestors 'none'` (use your bucket name).

Attach it to the **Default (\*)** behavior. Wait for Deployed.

```bash
curl -sI https://$DIST/index.html | grep -iE "strict-transport|x-content-type|x-frame|referrer|content-security"
```

Why this CSP? Read it as sentences: *everything (`default-src`) only from my own site; pictures only from my own site; scripts and styles only from my own site (that is why the page has no inline script or style); calls only to my own site and to my S3 bucket (the upload); nobody may frame me.*

### Break it: the missing S3 address

Edit the policy: remove `https://BUCKET.s3.us-west-2.amazonaws.com` from `connect-src`. Wait for Deployed (or use a private window, the headers are applied by CloudFront, not cached). Try to upload.

**You should see:** the upload fails at *Uploading to S3*, and the console says it violated the **Content Security Policy** (`connect-src`). A strict CSP protects against injected scripts but also blocks **your own** calls if you forget one. Put the address back.

## Part 4: A rate limit on the likes (WAF) (15 minutes)

WAF costs money outside a sandbox and may not be allowed: if it is blocked, read this part and skip it.

1. WAF & Shield → Web ACLs → Create: scope **CloudFront distributions (Global)**, name `photowall-acl`, add your distribution as the resource.
2. Add rule → **Rate-based rule**: name `rate-limit-likes`, limit **20**, window **1 minute**, aggregate by **IP address**. **Scope-down:** URI path **ends with** `/like`. Action **Block**.
3. Default action Allow. Create. Wait for the distribution to be Deployed.

```bash
for i in $(seq 1 45); do curl -s -o /dev/null -w "%{http_code} " -X POST https://$DIST/api/photos/sample02/like; sleep 1; done; echo
```

**You should see:** `200` for the first calls and then `403` after you pass about 20 in the minute (WAF counts a little behind real time, so the first 403 may come a few seconds late). A normal visitor clicking a few hearts never notices. The list and the photos are not affected: the rule only watches paths ending with `/like`.

Check **WAF → your ACL → Sampled requests** to see the blocked requests.

---

## Questions

1. Two names point to the same distribution. Do they share the cache? Why?
2. What does the CSP `connect-src` have to contain for the upload to work, and why?
3. Who enforces CORS: the browser or S3?
4. A rate-based rule has a limit of 20 per minute. Does a visitor who sends 21 requests get blocked at exactly the 21st?

<details><summary>Answers</summary>

1. Yes. The cache key is the path (and what the cache policy adds), not the host name.
2. Your own site and the S3 bucket address: the browser sends the PUT to S3 from the page, and `connect-src` lists the places the page may call.
3. The browser decides and enforces it, using the CORS headers that S3 sends. S3 only answers the question "is this origin allowed?".
4. Not exactly. WAF counts with a small delay, so blocking can start a few requests or seconds later.
</details>

## Remember

- A sub-domain zone touches nothing else of your domain; a new sandbox needs new name servers at the registrar.
- A CSP must list every place your own page calls. Test the upload after you change it.
- CORS: tighten to your own addresses once the app works.
- A WAF rate rule slows abuse, it does not stop it completely.
