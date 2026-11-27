# The three policies, as a story with a picture

Read this after [the mental model](00-mental-model.md). It explains the three policies of a behavior in the simplest way I know.

## The characters

- **The customer** is the browser.
- **The local shop** is a CloudFront edge location, near the customer.
- **The fridge** in the shop is the cache.
- **The main kitchen**, far away, is your origin (S3, a load balancer, a Lambda URL).

## The whole path, with the three policies marked

```
  CUSTOMER (browser)
  asks:  GET /menu/tea?sugar=no     Cookie: name=Ravi     Accept-Language: hi
            |
            v
  +--------------------------------------------------------------+
  |  THE LOCAL SHOP  (CloudFront edge)                           |
  |                                                              |
  |  1. CACHE POLICY writes the "order label" (the cache key)    |
  |       label = /menu/tea + sugar=no                           |
  |       (the cookie and the language are not part of it)       |
  |                                                              |
  |  2. Look in the fridge for that label                        |
  |       found and still fresh (TTL) -----> HIT ---------+      |
  |       not found, or too old                           |      |
  +----------------|--------------------------------------|------+
                   | MISS                                 |
                   v                                      |
    ORIGIN REQUEST POLICY adds notes to the phone call:   |
    the label values + extras (cookie, country, ...)      |
                   |                                      |
                   v                                      |
  +------------------------------+                        |
  |  MAIN KITCHEN  (origin)      |                        |
  |  makes the tea and adds its  |                        |
  |  own labels (Cache-Control)  |                        |
  +----------------|-------------+                        |
                   | answer                               |
                   v                                      |
    the shop keeps a copy in the fridge (as long as       |
    the TTL says: cache policy + the origin's labels)     |
                   |                                      |
                   v                                      v
  +--------------------------------------------------------------+
  |  3. RESPONSE HEADERS POLICY sticks labels on the cup         |
  |     for EVERY answer, Hit or Miss                            |
  |     (security rules, CORS, your own labels)                  |
  +-------------------------------|------------------------------+
                                  v
                    CUSTOMER gets the tea (with the labels)
```

## The three rule books

| | Cache policy | Origin request policy | Response headers policy |
|---|---|---|---|
| In the story | what counts as "the same order", and how long the tea stays in the fridge | notes added to the phone call to the kitchen | labels stuck on the cup on the way out |
| Direction | request coming **in** (finding the copy) | request going **out** to the origin | answer going **out** to the customer |
| Runs on a Hit? | yes (it builds the key) | **no** (no call to the kitchen) | **yes** |
| Changes the fridge (the cache)? | **yes** | no | no |
| Typical use | static files: path only, 1 day. API: query strings in the key | send the `Origin` header for CORS, the visitor's country, a cookie the origin needs | HSTS, CSP, `X-Frame-Options`, CORS headers, your own header |
| Required on a behavior | **yes** | no | no |

## The third rule book: the response headers policy

When the shop hands over the tea, it can stick **labels on the cup**: "Hot, handle with care", "Contains milk", "Please drink within the hour". The shop adds them **itself**, to every cup, **without asking the kitchen**. A cup from the fridge gets the same labels as a cup fresh from the kitchen.

In CloudFront these labels are headers:
- **Security labels** (HSTS, `X-Content-Type-Options`, `X-Frame-Options`, CSP, Referrer-Policy): "the browser must only talk to me over HTTPS", "do not guess the file type", "do not show me inside another site's frame".
- **CORS labels**: "websites other than mine are allowed to use this cup".
- **Your own labels**: for example `X-Lab-Policy`, or `Server-Timing` to show how fast the shop was.

They do **not** change what is in the fridge and they do not change what the kitchen is asked. A change to this policy shows up on the next answer, even for a copy that was cached long ago (you do not need an invalidation).

## Three customers, one afternoon

The shop has the cache policy "label = path + `sugar`, keep for 1 hour", and an origin request policy that also tells the kitchen the visitor's cookie.

| # | Customer asks | Label (cache key) | Fridge | What happens |
|---|---|---|---|---|
| 1 | `/menu/tea?sugar=no`, cookie Ravi | `/menu/tea` + `sugar=no` | empty | **Miss**: call the kitchen, notes include "cookie: Ravi". Keep a copy. Stick the response labels on. |
| 2 | `/menu/tea?sugar=no`, cookie Asha | same label (the cookie is not in the key) | has it | **Hit**: Asha gets the cup that was made for Ravi's call. No call to the kitchen. The response labels are stuck on again. |
| 3 | `/menu/tea?sugar=yes`, cookie Asha | `/menu/tea` + `sugar=yes` | no such label | **Miss**: a new call, a new copy. |

Look at customer 2: the kitchen was told Ravi's cookie, but Asha got the same cup. This is fine only if the tea does not depend on the cookie. If it did depend on it, the cookie belongs in the **cache policy**, or you would hand one person's private order to another.

## The one rule

- The tea **changes** with it (sugar, language, country, a login): put it in the **cache policy**.
- The kitchen only **needs to know** it, and the tea does not change: put it in the **origin request policy**.
- A label to stick on the cup for the browser: put it in the **response headers policy**.

## Where you do each one in the labs

| Policy | Lab |
|---|---|
| Cache policy: TTL, cache key, query strings | Lab 2 (and Lab 8, Drill 3: a cookie in the key) |
| Origin request policy: the Host header, what the origin sees | Lab 2 (Break 1) and Lab 10 (Part 2) |
| Response headers policy: security headers | Lab 4 (Part 1) and Lab 8 (Drill 5: CORS) |

## Quick questions

1. A cup comes out of the fridge. Which of the three rule books are used for this answer?
2. You add a cookie to the origin request policy only. Does the number of cups in the fridge change?
3. You want every answer to carry `X-Frame-Options: DENY`. Which policy?
4. Tea depends on the visitor's country. Which policy, and what happens if you forget?

<details><summary>Answers</summary>

1. The cache policy (it built the key to find the cup) and the response headers policy (the labels). The origin request policy is not used: nobody called the kitchen.
2. No. The origin request policy does not change the cache key.
3. The response headers policy.
4. The cache policy (country in the key). If you forget, the first visitor's version is served to everybody.
</details>
