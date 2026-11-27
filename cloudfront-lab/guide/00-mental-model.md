# The mental model (read this first, 5 minutes)

## The picture

```
 You (browser)
     |  1. DNS: dXXXX.cloudfront.net  (or your own name) -> the nearest edge location
     v
 EDGE LOCATION (POP)  ------ has a copy? ---- yes ---> answer at once        X-Cache: Hit from cloudfront
     |                                                                          (Age = seconds since it was stored)
     | no (or the copy is too old)
     v
 REGIONAL EDGE CACHE (a bigger cache in the middle; you do not configure it)
     |
     | still no
     v
 ORIGIN: your S3 bucket, your load balancer, your Lambda URL ...                X-Cache: Miss from cloudfront
     |
     +--> the answer is stored on the way back, for as long as the rules say (TTL)
```

A **Hit** is fast and never touches your origin. A **Miss** is slower and does. All of CloudFront tuning is about getting more Hits that are still correct.

## The words

| Word | Meaning |
|---|---|
| **Distribution** | One CloudFront setup. It has a name like `d1234abcd.cloudfront.net` and a list of origins and behaviors. |
| **Edge location / POP** | A CloudFront site near the viewer. Each one has its own cache. The `POP` column shows which one answered (for example `SEA900-P1`). |
| **Origin** | Where the real content lives: an S3 bucket, a load balancer, a Lambda Function URL, any web server. |
| **Behavior** | A rule: "requests whose path matches this pattern go to that origin with these settings". The last one, `Default (*)`, catches the rest. The first matching pattern wins, so order matters. |
| **Cache policy** | Decides the **cache key** and the **TTLs** of a behavior. |
| **Cache key** | What makes two requests "the same" for the cache. Always the path. Optionally also some query strings, headers or cookies you choose. Same key = the same stored copy. |
| **Origin request policy** | Decides what CloudFront **sends to the origin** on a Miss, beyond the cache key (for example extra headers). It does not change the cache key. |
| **TTL** | How long a copy may be kept: a minimum, a default and a maximum, combined with the origin's `Cache-Control` header (see the cheat sheet). |
| **Invalidation** | "Forget these paths now." It works across all edge locations and takes a minute or two. |
| **OAC** (origin access control) | Lets CloudFront read a **private** S3 bucket by signing its requests. The bucket policy says "only this distribution". |

Next, read [the three policies as a story](policies-explained.md): it makes the cache policy, the origin request policy and the response headers policy easy to tell apart.

## Three facts that explain most surprises

1. **The cache key is the path as the viewer wrote it.** `/` and `/index.html` are two different cache entries, even if S3 returns the same file.
2. **A cached object keeps being served while the origin is broken.** That hides outages for a while, and it also hides your fixes until the copy expires or you invalidate it.
3. **A change to a distribution takes minutes to reach every edge.** After editing a behavior or a policy, wait until the status is **Deployed** (or the policy page says so) before you conclude anything.

## How to study each lab

1. **Predict**: write down the headers you expect.
2. **Do it** and run `scripts/check.sh`.
3. **Compare** with your prediction. If it differs, find out why before you go on.
4. **Break it** on purpose, then fix it.
5. Answer the questions at the end without looking.
