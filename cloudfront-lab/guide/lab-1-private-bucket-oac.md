# Lab 1: A private bucket and OAC (about 30 minutes, plus 25 optional)

**Goal:** understand why the bucket is private and how CloudFront still reads it. Then break the permission and see what the cache hides.

**You will learn:** OAC and the bucket policy line by line, how a cache hides an outage, and how to get a real 404. Optional: your own domain with HTTPS.

Needs the distribution and bucket from Lab 0 (`BUCKET` and `DIST` set in your shell).

---

## Part 1: Read the bucket policy

S3 → your bucket → Permissions → Bucket policy. It looks like this (names are yours):

```json
{
  "Version": "2008-10-17",
  "Statement": [{
    "Sid": "AllowCloudFrontServicePrincipal",
    "Effect": "Allow",
    "Principal": { "Service": "cloudfront.amazonaws.com" },
    "Action": "s3:GetObject",
    "Resource": "arn:aws:s3:::BUCKET/*",
    "Condition": { "StringEquals": { "AWS:SourceArn": "arn:aws:cloudfront::ACCOUNT-ID:distribution/DISTRIBUTION-ID" } }
  }]
}
```

| Line | Meaning |
|---|---|
| `Principal: cloudfront.amazonaws.com` | The CloudFront **service** may ask S3 for objects. |
| `Action: s3:GetObject` | It may only **read objects**. No listing, no writing. |
| `Resource: BUCKET/*` | Every object in this bucket. |
| `Condition: AWS:SourceArn` | **Only this distribution.** Another distribution, even in your account, is refused. |

With OAC, CloudFront **signs** each request it sends to S3 (like any AWS caller). Before OAC there was OAI (origin access identity), the older method. OAC is the one to use.

**Test the other side:** the bucket still has *Block all public access*, so:

```bash
curl -sI https://$BUCKET.s3.us-west-2.amazonaws.com/index.html | head -1      # 403
curl -sI https://$DIST/index.html | head -1                                  # 200
```

## Part 2: Break it, and watch the cache hide the damage

You will remove the permission and see which requests still work.

**Predict first:** after you delete the bucket policy, will `/index.html` (already cached) still work? Will a **new** file?

1. Make sure `/index.html` is cached, and create a brand-new file that nobody has asked for:
```bash
bash scripts/check.sh https://$DIST/index.html 2
echo "<h1>extra</h1>" > /tmp/extra.html
aws s3 cp /tmp/extra.html s3://$BUCKET/extra.html
```
2. S3 → bucket → Permissions → Bucket policy → **Delete** (copy the policy text somewhere first!).
3. Check both:
```bash
bash scripts/check.sh https://$DIST/index.html 2     # still 200, Hit: the copy is in the cache
bash scripts/check.sh https://$DIST/extra.html 2     # 403: CloudFront cannot read it from S3 any more
```
4. **Restore** the policy (paste it back). Wait 10 to 15 seconds (the cached 403 expires), then:
```bash
bash scripts/check.sh https://$DIST/extra.html 2     # 200 again
```

**The lesson:** the cache kept `/index.html` alive while the origin access was broken. In real life this hides an outage for as long as the TTL, and then everything fails at once. Always test with an object that is **not** cached.

## Part 3: A real 404 instead of 403

Add a second statement so CloudFront may *list* the bucket. Edit the bucket policy and add this statement next to the first one (use your names):

```json
{
  "Sid": "AllowCloudFrontToListSoMissingFilesAre404",
  "Effect": "Allow",
  "Principal": { "Service": "cloudfront.amazonaws.com" },
  "Action": "s3:ListBucket",
  "Resource": "arn:aws:s3:::BUCKET",
  "Condition": { "StringEquals": { "AWS:SourceArn": "arn:aws:cloudfront::ACCOUNT-ID:distribution/DISTRIBUTION-ID" } }
}
```

Note: this resource is the **bucket** (no `/*`). Wait 10 to 15 seconds for the cached error, then:

```bash
bash scripts/check.sh https://$DIST/still-missing.html 2     # 404 now
```

Listing lets S3 tell "does not exist" (404) apart from "not allowed" (403). It exposes no file names to visitors: only CloudFront asks.

## Part 4 (optional, 5 min): switch OAC off

Distribution → **Origins** → edit your origin → Origin access: **Public**. Save and wait for Deployed. Because the bucket is private, **every uncached** request now fails with 403. Switch it back to your OAC. (Each change takes minutes to deploy: skip this part if you are short on time.)

---

## Part 5 (optional, about 25 minutes): your own domain with HTTPS

You need a domain you control. The certificate for CloudFront **must be in us-east-1**. These steps use `cdn.example.com` as the name: use your own.

1. **Hosted zone.** Route 53 → Hosted zones → Create: name `cdn.example.com`, Public. At your domain's registrar add **four NS records** named `cdn` pointing to the four name servers of the zone (the same way you did it for DropLink). A new sandbox means new name servers: this is the slow part.
2. **Certificate.** Switch the console to **N. Virginia (us-east-1)** → Certificate Manager → Request a public certificate for `cdn.example.com`, DNS validation → open it → **Create records in Route 53**. Wait for **Issued**.
3. **Attach it.** CloudFront → your distribution → **General** → Settings → Edit: **Alternate domain name** `cdn.example.com`; **Custom SSL certificate**: pick it. Save. Wait for Deployed.
4. **Point the name.** Route 53 → your zone → Create record: name empty (the zone itself), type **A**, **Alias** → "Alias to CloudFront distribution" → pick yours. Create.
5. **Test.**
```bash
dig cdn.example.com +short                       # several CloudFront IP addresses
curl -sI http://cdn.example.com | head -3        # 301 to https
bash scripts/check.sh https://cdn.example.com/index.html 2
```

If the browser shows a certificate warning, the certificate is not attached or does not match the name. If the name does not resolve, the NS records at the registrar are missing.

---

## Questions

1. What does the `Condition` in the bucket policy protect against?
2. You delete the bucket policy. `/index.html` still loads. Why? Does that mean everything is fine?
3. Why is the `ListBucket` statement on `arn:aws:s3:::BUCKET` and the first one on `BUCKET/*`?
4. Why must the certificate for CloudFront be in us-east-1 even if your bucket is in Oregon?

<details><summary>Answers</summary>

1. Another distribution (or anyone else using the CloudFront service) reading your bucket: only the distribution named in the ARN is allowed.
2. The copy is in the cache. Nothing is fine: any object not in the cache fails, and the cached ones fail when their TTL ends.
3. `GetObject` works on objects (`BUCKET/*`). `ListBucket` works on the bucket itself.
4. CloudFront is a global service that reads its certificates from us-east-1.
</details>

## Remember

- OAC = CloudFront signs requests; the bucket policy says which distribution.
- Cached objects hide origin problems. Test with a new object.
- 403 for a missing file means the caller cannot list the bucket.
