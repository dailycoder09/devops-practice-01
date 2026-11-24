# CloudFront cheat sheet

Tables to come back to. The labs show each of them in action.

## How long is a copy kept? (TTL)

The cache policy has three numbers: **minimum**, **default**, **maximum** TTL (seconds). The origin may send `Cache-Control` (`max-age` or `s-maxage`) or `Expires`.

| Origin sends | CloudFront keeps the copy for |
|---|---|
| nothing | the **default** TTL |
| `max-age=N` | N, but never less than the **minimum** and never more than the **maximum** |
| `no-store` / `private` / `no-cache` | with a minimum of 0 CloudFront follows the origin and does not keep it for reuse; a minimum above 0 overrides the header (it keeps the copy at least that long). Check the current AWS table before you rely on the details. |

Managed policies you will meet:

| Policy | What it does |
|---|---|
| **CachingOptimized** | minimum 1 s, default 1 day, maximum 1 year; gzip + brotli; cache key = path only |
| **CachingDisabled** | all TTLs 0; nothing is kept |
| **AllViewer** (origin request) | sends every viewer header, cookie and query string to the origin, **including Host** |
| **AllViewerExceptHostHeader** (origin request) | the same without Host: use it for origins that route by their own host name (Lambda URL, API Gateway) |

## The cache key

Always the **path**. You may add query strings, headers and cookies in the cache policy. Rules of thumb:

| Do | Why |
|---|---|
| Add only what the answer depends on | every extra value multiplies the number of copies and lowers the Hit ratio |
| Remember: what is not in the key is not sent to the origin | the origin cannot vary its answer on it |
| Use an **origin request policy** for things the origin needs but the answer does not depend on | for example an extra header: sent on a Miss, not part of the key |

## X-Cache values

| Value | Meaning |
|---|---|
| `Hit from cloudfront` | served from the edge cache |
| `RefreshHit from cloudfront` | the copy had expired; the origin said "not modified"; the copy was kept |
| `Miss from cloudfront` | fetched from the origin |
| `Error from cloudfront` | an error (4xx or 5xx) came from CloudFront or the origin |

`Age` = seconds the copy has been stored. `X-Amz-Cf-Pop` = the edge location. `X-Amz-Cf-Id` = the request id to quote to AWS support.

## Invalidation

| | |
|---|---|
| Free | the first 1,000 paths per month per account |
| After that | about 0.005 USD per path |
| Wildcard | `/*` or `/images/*` counts as **one** path |
| Time | a minute or two |
| Better for routine releases | versioned file names + short-lived HTML (Lab 3) |

## Symptom → first thing to check

| Symptom | Likely cause |
|---|---|
| 403 `AccessDenied` for every file | bucket policy missing or for another distribution; OAC not set on the origin |
| 403 for a file that does not exist (not 404) | CloudFront may not list the bucket (add `s3:ListBucket`) |
| 403 from a Lambda URL or API Gateway | the viewer's `Host` header is forwarded (use `AllViewerExceptHostHeader` or no origin request policy) |
| 403 "does not allow the HTTP request method" | the behavior allows only GET and HEAD |
| Old content after a deploy | TTL has not ended: invalidate, or version the file names |
| Never a Hit | `no-store`/`private` from the origin, a policy with TTL 0, or a cookie/header/query string in the cache key that is different every time |
| Everyone sees the same answer but it should differ | what the answer depends on is not in the cache key |
| Change to a behavior has no effect | the distribution is still **Deploying** |
| 504 | the origin is too slow or unreachable (check the origin response timeout) |
| Browser CORS error through CloudFront | the CORS headers are missing or the `Origin` header is not in the cache key / origin request policy |
| Certificate error on your own domain | the certificate must be in us-east-1, issued, attached to the distribution, and match the alternate domain name |

## Commands

```bash
bash scripts/check.sh URL [COUNT]                     # X-Cache, Age, Cache-Control, Content-Encoding, edge location
SLEEP=5 BODY=1 bash scripts/check.sh URL 6            # six calls, five seconds apart, with the start of the answer
curl -sI -H "Accept-Encoding: br" URL                 # all response headers, asking for brotli
dig NAME +short                                       # where a name points
```
