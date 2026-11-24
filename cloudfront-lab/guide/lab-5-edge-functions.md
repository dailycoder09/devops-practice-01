# Lab 5: Code at the edge (CloudFront Functions) (about 45 minutes)

**Goal:** run a few lines of JavaScript at the edge to rewrite a URL, redirect, block a path, and add a header, before the request reaches the cache or the origin.

**You will learn:** viewer-request and viewer-response events, how to test a function in the console, how to publish and associate it, why a function runs even on cache hits, and when you would need Lambda@Edge instead.

Needs the distribution from Lab 0. The code is in [`functions/edge-request.js`](../functions/edge-request.js) and [`functions/edge-response.js`](../functions/edge-response.js) (tested with Node on a laptop).

---

## What the function does

```
viewer ──> [ viewer-request function ] ──> cache ──> origin
viewer <── [ viewer-response function ] <── cache <── origin
```

| Request | Result |
|---|---|
| `/about` | served from `/about.html` (clean URL) |
| `/old-about.html` | redirect `301` to `/about.html` |
| `/admin/anything` | `403` straight from the edge, the origin is never asked |
| every answer | gets the header `x-served-by-edge-function: cf-lab` |

## Part 1: Create and test the request function (10 minutes)

1. CloudFront → **Functions** → **Create function**. Name `cf-lab-edge-request`, runtime **cloudfront-js-2.0**. Create.
2. In the **Build** tab paste the content of `functions/edge-request.js` into the editor. **Save changes**.
3. **Test** tab: event type **Viewer request**, stage **Development**. In the test event set the URL path to `/about` and **Test function**.

**You should see:** the output request has `uri: /about.html`. The panel also shows the **compute utilization** (a number out of 100): this is your time budget. Try `/old-about.html` (a 301 response), `/admin/x` (403), `/images/photo-1.jpg` (unchanged).

## Part 2: Publish and attach it (5 minutes + deploy)

1. **Publish** tab → **Publish function**. (A function has a *Development* and a *Live* version: only Live can be attached.)
2. Same tab → **Associate**: your distribution, event type **Viewer request**, cache behavior **Default (\*)**. Save.
3. Wait for the distribution to be **Deployed**.

```bash
bash scripts/check.sh https://$DIST/about 2                       # 200: the clean URL works
curl -sI https://$DIST/old-about.html | grep -iE "^HTTP|^location"   # 301 and location: /about.html
curl -s -i https://$DIST/admin/x | head -8                        # 403, "Not allowed here."
```

**Predict first:** `/about` is not a file in S3. Will `X-Cache` say Miss or Hit at the first request? What is the cache key: `/about` or `/about.html`?

**You should see:** the first call is a Miss and the second a Hit. The rewrite happens **before** the cache lookup, so the cache key is `/about.html`. Request `/about.html` now: it is a **Hit** from the same entry.

## Part 3: A viewer-response function (10 minutes)

Create a second function `cf-lab-edge-response` with `functions/edge-response.js`, test it with event type **Viewer response**, publish it, and associate it with the **Default (\*)** behavior as **Viewer response**. A behavior can have one function per event type.

```bash
curl -sI https://$DIST/index.html | grep -i "x-served-by"
```

**You should see:** `x-served-by-edge-function: cf-lab`, **also on a Hit**.

## Part 4: The key fact

**Predict:** a viewer-request function and a Hit: does the function still run?

It does. The function runs **for every viewer request, before the cache**, so a change in the function applies at once (no invalidation) and you pay for every invocation (about 0.10 USD per million, and the first 2 million per month are free). Keep it small.

## Part 5: Break it

**Break 1: a function error.** In the editor delete the line `return request;` at the end, save, **publish** again (it is already associated, so Live is replaced), wait for Deployed:

```bash
curl -s -i https://$DIST/index.html | head -8
```

**You should see:** a `5xx` error (CloudFront reports a function validation error), for **every** request, even for files that are cached. A bug in a viewer function takes the whole behavior down. Put the line back and publish again. This is why you test before you publish.

**Break 2: a redirect loop.** Add this at the top of the handler, after `var uri = request.uri;`:

```js
if (uri === '/about.html') {
  return { statusCode: 301, statusDescription: 'Moved Permanently', headers: { location: { value: '/about' } } };
}
```

With the clean-URL rule, `/about` becomes `/about.html`, which redirects to `/about`, and so on. Publish, wait for Deployed, and run `curl -sIL --max-redirs 5 https://$DIST/about | grep -ciE "^HTTP"`. A browser shows "too many redirects". Remove the lines and publish again.

## What a CloudFront Function cannot do, and Lambda@Edge

| | CloudFront Function | Lambda@Edge |
|---|---|---|
| Runs at | the edge location, **viewer request / response only** | a regional edge cache, **viewer and origin** request / response |
| Language | JavaScript only (small subset) | Node.js, Python |
| Time limit | under 1 millisecond | 5 s (viewer) or 30 s (origin) |
| Network access, files | none | yes |
| Read the request body | no | yes (origin events) |
| Code size | 10 KB | 1 MB (viewer) to 50 MB (origin) |
| Price | about 0.10 USD per million | about 0.60 USD per million plus run time |
| Deploy | in the CloudFront console | the function must be created in us-east-1; its replicas stay for hours after you remove it |

Use a **CloudFront Function** for simple, fast things: rewrites, redirects, headers, simple checks of a header or cookie. Use **Lambda@Edge** when you need to call another service, read the body, resize an image, or run more than a moment. This lab only uses Functions, so you do not leave Lambda@Edge replicas behind.

---

## Questions

1. Why is the cache key `/about.html` and not `/about` after the rewrite?
2. You edit and publish the function. Do you need an invalidation?
3. Name two things that need Lambda@Edge, not a CloudFront Function.
4. Why must you publish a function before you can attach it?

<details><summary>Answers</summary>

1. The function runs before the cache lookup and changes the request, so the cache sees `/about.html`.
2. No. The function runs for every request, so the new code is used as soon as it is deployed.
3. For example: calling another service or database, reading the request body, resizing images, an origin-request rule, or work longer than a millisecond.
4. Only the Live version can be associated; Development is for editing and testing.
</details>

## Remember

- A viewer function runs on every request, before the cache, so it is always up to date.
- Test in the console, publish, associate, then wait for Deployed.
- A bug in a viewer function breaks everything behind it.
- Functions for the simple jobs, Lambda@Edge for the rest.
