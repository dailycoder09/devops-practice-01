# CloudFront Lab

Learn Amazon CloudFront by doing it in the AWS Console and **watching the cache work**. It is a separate practice project: it does not touch DropLink or MicroShop.

You build a small website (S3), put CloudFront in front of it, add an API (Lambda), break things on purpose, and fix them. Every step ends with something you can see in a header.

> **Status.** All eleven labs (0 to 10) are written. **Nothing here has been run on AWS yet.** The website, the Lambda code, the edge functions, the small web server of Lab 10 and the check script were tested on a computer, and the Terraform passes `terraform validate`, `terraform fmt` and an offline plan test with pretend AWS answers. Console button names are written from memory and change from time to time: if a name differs, look for the same idea nearby. A real error message is more reliable than this text, and I would like to hear about every place where the guide and the console disagree.

## The idea in one minute

Run this after every change and look at three things:

```bash
bash scripts/check.sh https://dXXXXXXXXXXXX.cloudfront.net/index.html 3
#1  200 | X-Cache: Miss from cloudfront | Age: 0 | ...
#2  200 | X-Cache: Hit from cloudfront  | Age: 4 | ...
```

- **X-Cache** says whether CloudFront answered from its copy (Hit) or went to your origin (Miss).
- **Age** says how many seconds that copy has been kept.
- **X-Amz-Cf-Pop** (the `POP` column) says which edge location answered.

Before you run a command, write down what you expect. If you can predict these headers, you understand the lesson.

## Plan: two sessions

A sandbox lasts about 4 hours and then everything is deleted, so the labs are split. Each session starts from an empty account.

| Session | Lab | Time | What you learn |
|---|---|---|---|
| **1** | [0 First distribution](guide/lab-0-first-distribution.md) | 45 min | distribution, origin, Miss and Hit, `Age`, default root object, why 403 |
| | [1 Private bucket and OAC](guide/lab-1-private-bucket-oac.md) | 30 min (+25) | the bucket policy, how a cache hides an outage, a real 404, optional own domain with HTTPS |
| | [2 Behaviors and an API origin](guide/lab-2-behaviors-and-api.md) | 75 min | behaviors, cache policy, cache key, TTL from the origin, the Host header trap |
| | [3 Stale content, invalidation, versioned names](guide/lab-3-stale-and-versioned.md) | 45 min | invalidation, `Cache-Control`, `RefreshHit`, compression |
| **2** | [4 Security at the edge](guide/lab-4-security.md) | 75 min | response headers, WAF (string match, rate limit), country rule, signed URLs |
| | [5 Code at the edge](guide/lab-5-edge-functions.md) | 45 min | CloudFront Functions, rewrite, redirect, block; when you need Lambda@Edge |
| | [6 Origin failover and error pages](guide/lab-6-origin-failover.md) | 50 min | origin groups, custom error pages, compare with Route 53 failover |
| | [7 Logs and metrics](guide/lab-7-logs-and-metrics.md) | 50 min | CloudWatch metrics and alarm, access logs, your own hit ratio |
| | [8 Troubleshooting drills](guide/lab-8-troubleshooting-drills.md) | 75 min | six real problems that you cause and then find |
| | [9 The whole lab as Terraform](guide/lab-9-terraform.md) | 60 min | the same setup as code, plan, drift, destroy |
| bonus | [10 The basics real projects need](guide/lab-10-bonus-real-world-basics.md) | 105 min | ALB as origin and what the origin sees, locking the origin, S3 REST vs website endpoint, sub-folders, single-page apps, range requests, cost |

Session 1 is about 3 hours, Session 2 about 6 hours: if you have 4 hours, do Labs 4, 5, 6 and 8 and read 7 and 9.

Every lab has: goal, steps, "predict first", what you should see, "break it", questions with answers, and what to remember.

## What you need

- A sandbox or account with **S3, CloudFront and Lambda** allowed (Lab 0 is the test: if CloudFront itself is blocked, stop and tell me). Some labs also need **EC2 and a load balancer** (10), **WAF** (4), **CloudWatch** alarms and SNS (7), **Route 53 and ACM** (1, optional). If one is blocked, skip that part and read it instead.
- **AWS CloudShell** (the `>_` icon in the console, region **us-west-2**). The labs use it to copy files and run `curl`.
- A browser. Optional for Lab 1: your own domain.

## Start (10 minutes)

1. Push this folder to GitHub (you do the commit and push).
2. In CloudShell, fetch only this folder:

```bash
git clone --depth 1 --filter=blob:none --sparse https://github.com/dailycoder09/devops-practice-01.git
cd devops-practice-01 && git sparse-checkout set cloudfront-lab && cd cloudfront-lab
ls            # guide  site  api  functions  scripts  terraform  README.md
```

3. Open [guide/00-mental-model.md](guide/00-mental-model.md) (5 minutes), then [Lab 0](guide/lab-0-first-distribution.md).

## Files

```
cloudfront-lab/
  README.md                         this page
  guide/
    00-mental-model.md              the words and the picture, read first
    lab-0 ... lab-10                the eleven labs (see the table above)
    cheatsheet.md                   tables to come back to (TTL rules, X-Cache values, symptoms)
    policies-explained.md           the three policies (cache, origin request, response headers) as a story with a picture
  site/                             the practice website (uploaded to S3)
  api/                              the tiny Lambda function and its tests
  functions/                        the four CloudFront Functions (Labs 5 and 10)
  origin/                           the small web server behind a load balancer (Lab 10)
  scripts/check.sh                  prints X-Cache, Age, Cache-Control, edge location for any URL
  terraform/                        the whole lab as code (Lab 9), with an offline test
```

## Cost and clean-up

- CloudFront has a monthly free allowance (1 TB out, 10 million requests) and this lab uses a few kilobytes. S3, Lambda and the Function URL cost almost nothing at this size.
- A sandbox deletes everything at the end. In a normal account clean up in this order: **disable** the distribution, wait until it is deployed, **delete** it (this takes minutes), then delete the buckets, the Lambda function, the WAF web ACL, the certificate and hosted zone (if you made them), the CloudWatch alarm and the CloudFront key group and public key. Or run `terraform destroy` for what Lab 9 built.
- WAF costs money outside a sandbox (a monthly charge per web ACL and per rule). Delete it after Lab 4.
- The Lambda Function URL in Lab 2 is **public** (authentication "NONE") so that the lab is simple. The function only returns the time. Delete it when you finish.
