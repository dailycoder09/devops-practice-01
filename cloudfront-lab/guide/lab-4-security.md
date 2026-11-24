# Lab 4: Security at the edge (about 75 minutes)

**Goal:** add four layers of protection in front of your site: security headers, a firewall (WAF), a country rule, and private files that only a signed link can open.

**You will learn:** response headers policies, AWS WAF rules (a string match and a rate limit), geo restriction, signed URLs with key groups, and that signatures are checked **before** the cache.

Needs the distribution from Lab 0, and the `/api/*` behavior from Lab 2 for Part 2. Variables: `BUCKET`, `DIST` (see Lab 3, top).

Many changes below take minutes to deploy. Start the next change while you wait, or read.

---

## Part 1: Security headers (response headers policy)

Browsers have protections that a server must switch on with headers. CloudFront can add them to every answer, without touching the origin.

**Predict first:** which of these headers does your site send today?

```bash
curl -sI https://$DIST/index.html | grep -iE "strict-transport|x-content-type|x-frame|referrer-policy|content-security|x-lab"
```

You should see **nothing** (S3 sends none of them).

**Create the policy.** CloudFront → **Policies** → **Response headers** → Create:
- Name `cf-lab-security`.
- Strict-Transport-Security: max age `31536000`, include subdomains, override origin.
- X-Content-Type-Options: on (`nosniff`).
- X-Frame-Options: `DENY`.
- Referrer-Policy: `strict-origin-when-cross-origin`.
- Content-Security-Policy: `default-src 'self'; img-src 'self' data:; frame-ancestors 'none'`.
- Custom header: `X-Lab-Policy` = `cf-lab-security`.

**Attach it.** Distribution → Behaviors → **Default (\*)** → Edit → Response headers policy: `cf-lab-security`. Save and wait for **Deployed**.

```bash
curl -sI https://$DIST/index.html | grep -iE "strict-transport|x-content-type|x-frame|referrer-policy|content-security|x-lab"
bash scripts/check.sh https://$DIST/index.html 2          # a Hit that has the new headers
```

**You should see:** all six headers, **also on a Hit**. The headers are added when the answer leaves the edge, so no invalidation is needed (check this with the Hit above).

What each one does:

| Header | Protects against |
|---|---|
| Strict-Transport-Security | a browser ever using plain HTTP for your site again |
| X-Content-Type-Options: nosniff | a browser guessing a file is a script when it is not |
| X-Frame-Options / `frame-ancestors` | your site being shown inside another site's frame (clickjacking) |
| Referrer-Policy | leaking full addresses to other sites |
| Content-Security-Policy | running scripts or loading files from places you did not allow |

**Break it:** set the Content-Security-Policy to `default-src 'none'`, wait for Deployed, open the site and press F12 → Console. The page loses its style and script, and the console says the policy blocked them. This is the most common reason a "secured" site looks broken. Put the policy back.

## Part 2: A firewall (AWS WAF)

WAF looks at each request **before** CloudFront serves it. It costs money outside a sandbox (a monthly charge for the web ACL and each rule, plus a small charge per request): delete it at the end.

1. Console → **WAF & Shield** → Web ACLs → **Create web ACL**. Scope **CloudFront distributions (Global)**. Name `cf-lab-acl`. Add your distribution under "Associated AWS resources".
2. **Rule 1, a plain string match:** Add rules → Add my own rules → Rule builder.
   - Name `block-bad-bot`. Inspect: **Single header**, header name `user-agent`. Match type: **Contains string**, value `BadBot`. Action **Block**.
3. **Rule 2, a rate limit:** another rule, type **Rate-based rule**.
   - Name `rate-limit-api`. Rate limit **10**, evaluation window **1 minute**, aggregate by **IP address**.
   - Scope-down: URI path **starts with** `/api/`. Action **Block**.
4. Default action: **Allow**. Leave the CloudWatch metrics and sampled requests on. Create.
5. Wait until the distribution is **Deployed**.

**Test rule 1 (instant):**

```bash
curl -s -o /dev/null -w "normal: %{http_code}\n" https://$DIST/index.html
curl -s -o /dev/null -w "BadBot: %{http_code}\n" -A "BadBot/1.0" https://$DIST/index.html
```

**You should see:** `200` and `403`.

**Test rule 2 (takes a minute):** one call a second for a minute against the API:

```bash
for i in $(seq 1 60); do curl -s -o /dev/null -w "%{http_code} " "https://$DIST/api/time?n=$i"; sleep 1; done; echo
```

**You should see:** `200` for the first calls, then `403` once you go over about 10 requests in the minute. WAF counts a little behind real time, so the first block appears some seconds after the 10th request. Stop sending and the blocks end after the window.

**Look at the evidence.** WAF → your web ACL → **Sampled requests**: you can see the blocked requests and which rule stopped them.

**Break it:** edit rule 1 and set the action to **Count** instead of Block. Repeat the BadBot call: it now gets `200`, but the rule counts it. **Count mode** is how you try a new rule on real traffic before you let it block.

## Part 3 (optional, 10 min): a country rule

Distribution → **Security** (or "Geographic restrictions") → Edit → **Allow list**, add only a country you are **not** in. Save, wait for Deployed.

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://$DIST/index.html        # 403: blocked for your country
```

**Remove the restriction again.** Remember the limits: it uses a country database of IP addresses (wrong sometimes), and a VPN gets around it.

## Part 4: Private files with signed URLs

A file that only people with a time-limited link may open. Everyone else gets 403. No S3 permission is involved: CloudFront checks a **signature**.

**1. Make a key pair (CloudShell).** The private key never leaves CloudShell and is never committed:

```bash
openssl genrsa -out private_key.pem 2048
openssl rsa -pubout -in private_key.pem -out public_key.pem
cat public_key.pem
```

**2. Tell CloudFront the public key.** CloudFront → **Public keys** → Create: name `cf-lab-key`, paste the content of `public_key.pem`. Copy the **ID** (it starts with `K`).
CloudFront → **Key groups** → Create: name `cf-lab-keys`, add `cf-lab-key`.

**3. A behavior for private files.** Distribution → Behaviors → Create:
- Path pattern `/private/*`, origin: your S3 bucket, viewer protocol: Redirect HTTP to HTTPS, cache policy CachingOptimized.
- **Restrict viewer access: Yes**, trusted authorization: **Trusted key groups**, key group `cf-lab-keys`.

Save and wait for Deployed.

**4. A private file.**

```bash
echo "top secret" > /tmp/secret.txt
aws s3 cp /tmp/secret.txt s3://$BUCKET/private/secret.txt
```

**Predict:** you open it without a signature. What do you get?

```bash
curl -s -i https://$DIST/private/secret.txt | head -12
```

**You should see:** `403` and a message that the **Key-Pair-Id query string parameter or cookie value is missing**. The file is in your bucket and CloudFront can read it, but it refuses to serve it without a valid signature.

**5. Sign a link that works for two minutes.**

```bash
KEYID=K2ABCDEFGHIJKL                      # <- the ID of your public key
URL=$(aws cloudfront sign --url "https://$DIST/private/secret.txt" --key-pair-id "$KEYID" \
      --private-key file://private_key.pem --date-less-than "$(date -u -d '+2 minutes' +%Y-%m-%dT%H:%M:%SZ)")
echo "$URL"
curl -s "$URL"                            # top secret
```

**You should see:** `top secret`. The link contains `Expires`, `Signature` and `Key-Pair-Id`.

**6. Break it three ways.**

```bash
curl -s -o /dev/null -w "tampered: %{http_code}\n" "${URL/secret.txt/secret2.txt}"          # the signature does not fit another path: 403
curl -s -o /dev/null -w "changed key id: %{http_code}\n" "${URL%??}ab"                       # the last part of the link is Key-Pair-Id: 403
sleep 130; curl -s -o /dev/null -w "after 2 minutes: %{http_code}\n" "$URL"                  # expired: 403
```

**7. The cache does not bypass the check.** Make a fresh link, call it twice and see a Hit:

```bash
URL=$(aws cloudfront sign --url "https://$DIST/private/secret.txt" --key-pair-id "$KEYID" --private-key file://private_key.pem --date-less-than "$(date -u -d '+5 minutes' +%Y-%m-%dT%H:%M:%SZ)")
bash scripts/check.sh "$URL" 2                         # Miss, then Hit
curl -s -o /dev/null -w "no signature, cached file: %{http_code}\n" https://$DIST/private/secret.txt     # still 403
```

The copy is in the cache, yet the unsigned request is refused: **the signature is checked at the edge before the cache lookup**.

**Signed cookies** do the same for many files at once (for example a whole `/private/*` area for a logged-in user): the cookies carry the signature instead of the URL. You would use them for a video library or a paid download area.

**Clean up:** `rm private_key.pem public_key.pem`. A private key must never be pushed to GitHub.

---

## Questions

1. Why are security headers added by CloudFront and not by S3?
2. Name one reason to start a new WAF rule in Count mode.
3. A user has a valid signed URL and shares it. What stops the sharing, and what does not?
4. Why does the unsigned request still get 403 when the file is in the cache?
5. What is the difference between OAC (Lab 1) and signed URLs?

<details><summary>Answers</summary>

1. S3 cannot add them (it has no such setting), and one policy on CloudFront covers every origin.
2. To see what the rule would block on real traffic, without blocking real users by mistake.
3. The expiry time stops it after a while. Nothing stops it before: whoever has the link can use it until it expires (shorten the time, or tie it to an IP address in a custom policy).
4. The signature is verified for each request before the cache is looked at.
5. OAC controls who may read the **bucket** (only CloudFront). Signed URLs control who may read the file **through CloudFront** (only people holding a valid signature).
</details>

## Remember

- CloudFront can add security headers to every answer, in one place.
- WAF first in Count mode, then Block.
- A rate limit is counted a little behind real time.
- Signed URLs/cookies are verified at the edge, before the cache.
- Delete the WAF web ACL and the private key when you finish.
