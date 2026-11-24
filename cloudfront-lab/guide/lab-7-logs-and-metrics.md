# Lab 7: Logs and metrics (about 50 minutes)

**Goal:** see what CloudFront knows about your traffic: charts, an alarm that sends you an e-mail, and the access log of every request, with your own cache hit ratio worked out from it.

**You will learn:** the built-in CloudFront metrics, a CloudWatch alarm on error rate, standard access logs in S3, the log fields that matter, and how to find one request in the logs from its `X-Amz-Cf-Id` header.

Needs the distribution from Lab 0 (`BUCKET`, `DIST`). **CloudFront metrics live in N. Virginia (us-east-1)**: CloudWatch must be opened in that region for Parts 1 and 2, whatever region your bucket is in.

---

## Part 1: Look at the metrics (10 minutes)

First make some traffic, with a mix of good and bad requests:

```bash
for i in $(seq 1 40); do curl -s -o /dev/null https://$DIST/index.html; curl -s -o /dev/null https://$DIST/missing-$i.html; done
for i in $(seq 1 10); do curl -s -o /dev/null "https://$DIST/api/time?ttl=30"; done
```

Console → CloudFront → your distribution → **Monitoring** tab. (Or CloudWatch → Metrics → **CloudFront** → Per-Distribution Metrics.) Metrics arrive after one to a few minutes.

| Metric (free) | What it counts |
|---|---|
| Requests | all viewer requests |
| BytesDownloaded / BytesUploaded | traffic out / in |
| 4xxErrorRate | percent of requests that got a 4xx |
| 5xxErrorRate | percent of requests that got a 5xx |
| TotalErrorRate | 4xx + 5xx |

"Additional metrics" (cache hit rate, origin latency, error rate per status code) are a paid switch on the distribution: Monitoring tab → **Additional metrics**. Switch it on for the lab if you can, and switch it off afterwards.

**Predict:** with the traffic above, roughly what is the 4xxErrorRate? (Look at how many of the 90 requests were missing files.) Then check the chart.

## Part 2: An alarm that e-mails you (15 minutes)

1. CloudWatch (**N. Virginia**) → Alarms → **Create alarm** → Select metric → CloudFront → **Per-Distribution Metrics** → your distribution → **TotalErrorRate** → Select.
2. Statistic **Average**, period **1 minute**. Condition: **Greater than 20**.
3. Notification: create a new SNS topic `cf-lab-alerts` and give your e-mail. Name the alarm `cf-lab-errors`. Create. **Confirm the subscription** in the e-mail AWS sends you.
4. Cause the alarm: send mostly errors for about three minutes.

```bash
for i in $(seq 1 180); do curl -s -o /dev/null "https://$DIST/nope-$i.html"; sleep 1; done
```

**You should see:** the alarm goes from **OK** to **In alarm** a few minutes later and an e-mail arrives. Then it goes back to OK when the errors stop.

Questions to think about: Why is a **rate** a better alarm than a count? Which error would you want a pager for (4xx or 5xx)? (Usually 5xx: your side is broken. A burst of 4xx can be a bad link or a scan.)

## Part 3: Standard logs to S3 (15 minutes)

Metrics tell you **how much**. Logs tell you **which request**.

1. Make a bucket for the logs: S3 → Create bucket `cf-lab-logs-<yourname>-<4 digits>` (keep Block public access on). Note it as `LOGBUCKET`.
2. Distribution → **Logging** tab → **Add** → **Standard logs** (the newer "v2" kind is configured here and in CloudWatch) → destination **Amazon S3**, your log bucket, suffix prefix `cf/`, format **W3C**. Save. (The console adds the permission the log service needs on the bucket. If it does not, it says what to add: follow its message.)
   - *If your console only offers the older setting:* distribution → **General** → Settings → Edit → **Standard logging: On**, S3 bucket, prefix `cf/`. The older kind needs the bucket to allow ACLs (Object Ownership: "Bucket owner preferred").
3. Send traffic again (the loops from Part 1). **Logs arrive in files, several minutes after the requests** (often 5 to 20, sometimes up to an hour). Meanwhile read the field table below.

```bash
LOGBUCKET=cf-lab-logs-yourname-1234
aws s3 ls s3://$LOGBUCKET/cf/ --recursive | tail -5
```

When files appear:

```bash
mkdir -p /tmp/logs && aws s3 sync s3://$LOGBUCKET/cf/ /tmp/logs/ --only-show-errors
zcat /tmp/logs/*.gz | head -4 | cut -c1-300       # a header with #Fields: and then one line per request
```

The fields worth knowing:

| Field | Meaning |
|---|---|
| `x-edge-location` | which edge location answered (like the POP column) |
| `cs-uri-stem` | the path |
| `sc-status` | the status code |
| `x-edge-result-type` | Hit, RefreshHit, Miss, Error, and more: how CloudFront got the answer |
| `x-edge-response-result-type` | what CloudFront sent to the viewer |
| `time-taken` | seconds from the request to the last byte |
| `x-edge-request-id` | the same value as the `X-Amz-Cf-Id` response header: your key to one request |
| `c-ip`, `cs(User-Agent)` | who asked (personal data: handle logs with care) |

## Part 4: Work out your own cache hit ratio

```bash
zcat /tmp/logs/*.gz | awk -F'\t' '
  /^#Fields:/ { n = split($0, f, " "); for (i = 2; i <= n; i++) if (f[i] == "x-edge-result-type") col = i - 1; next }
  /^#/ { next }
  col { c[$col]++; total++ }
  END { for (k in c) printf "%-12s %6d  %5.1f%%\n", k, c[k], 100 * c[k] / total }'
```

(The awk reads the `#Fields:` line, so it finds the right column by itself.)

**You should see:** a table with Hit, Miss, Error and maybe RefreshHit. **Hit ratio = (Hit + RefreshHit) / all requests.** Your loops asked for 40 missing files (errors) and the same file again and again (hits).

Now **the top paths and the slowest requests:**

```bash
zcat /tmp/logs/*.gz | awk -F'\t' '/^#Fields:/ { n = split($0, f, " "); for (i = 2; i <= n; i++) { if (f[i]=="cs-uri-stem") p=i-1; if (f[i]=="time-taken") t=i-1 } next } /^#/ {next} { s[$p]++; if ($t > m[$p]) m[$p]=$t } END { for (k in s) printf "%5d  %.3fs  %s\n", s[k], m[k], k }' | sort -rn | head -8
```

## Part 5: Find one request

```bash
curl -sI https://$DIST/about.html | grep -i x-amz-cf-id        # copy the value
CFID=paste-the-value-here
zcat /tmp/logs/*.gz | grep -F "$CFID" | cut -c1-300           # your request, once the log file has arrived
```

This is how a support ticket ("a user got a 502 at 14:03") turns into one log line: ask the user for the `X-Amz-Cf-Id`, or look up the time and path.

**Real-time logs (read only):** CloudFront can also send each request within seconds to a Kinesis data stream, for dashboards and alerts. It costs more and needs a stream, so it is not part of the lab.

---

## Questions

1. Where are the CloudFront metrics, and in which region must you create the alarm?
2. Why can the access log of a request that happened one minute ago still be missing?
3. How do you calculate the cache hit ratio from the logs? Which field do you use?
4. A user says "I got an error at 14:03". What do you ask for, and how do you find the request?
5. Why alarm on a rate instead of a count?

<details><summary>Answers</summary>

1. In us-east-1 (CloudWatch, N. Virginia); alarms on CloudFront metrics are created there too.
2. Standard logs are delivered in batches, minutes (sometimes up to an hour) later.
3. Count `x-edge-result-type`: (Hit + RefreshHit) / all requests.
4. The path, the time, and best the `X-Amz-Cf-Id` header; look it up as `x-edge-request-id`.
5. A count grows with traffic, so a busy day looks bad when it is not. A rate says how much of your traffic fails.
</details>

## Remember

- Metrics = how much, in us-east-1. Logs = which request, in S3, later.
- `x-edge-result-type` is the log version of X-Cache.
- `X-Amz-Cf-Id` (header) = `x-edge-request-id` (log): one key for one request.
- Switch off additional metrics and delete logs when you finish (logs can contain IP addresses).
