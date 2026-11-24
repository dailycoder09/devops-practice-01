# Lab 9: The whole lab as Terraform (about 60 minutes)

**Goal:** rebuild what you made by hand in Labs 0 to 5 with one command, and learn which Terraform block is which console setting.

**You will learn:** how a CloudFront setup looks as code, why OAC for Lambda is nicer than a public URL, how to change a cache rule through Terraform, how drift shows up, and how to tear everything down.

You need Labs 0 to 5 done at least once, so that every block below looks familiar. The code is in [`terraform/`](../terraform/). It has passed `terraform validate`, `terraform fmt` and an offline plan test with pretend AWS answers (`terraform test`). **It has not been applied to a real account yet**: if `apply` fails, send me the first red error.

---

## Part 1: Install Terraform in CloudShell (3 minutes, once per session)

CloudShell keeps only 1 GB of storage, and the AWS plugin is big (about 700 MB). Keep Terraform's working files in `/tmp`:

```bash
mkdir -p ~/tools && curl -fsSLo ~/tools/tf.zip https://releases.hashicorp.com/terraform/1.9.8/terraform_1.9.8_linux_amd64.zip \
  && unzip -o ~/tools/tf.zip -d ~/tools && export PATH=$HOME/tools:$PATH
export TF_DATA_DIR=/tmp/tfdata && mkdir -p $TF_DATA_DIR
terraform version
cd ~/devops-practice-01/cloudfront-lab/terraform           # your clone of the repo
```

After a CloudShell restart repeat the `export` lines (and `terraform init`): `/tmp` is wiped, your state file next to the code is not.

## Part 2: Read before you apply (15 minutes)

Open `terraform/main.tf` next to this table:

| Terraform block | The console lab it replaces |
|---|---|
| `aws_s3_bucket` + `aws_s3_bucket_public_access_block` | Lab 0, Step 1: the private bucket |
| `aws_s3_object` (one per file) | Lab 0, Step 2 and Lab 3: upload with `Cache-Control` (HTML 60 s, assets one year, rest a day) |
| `aws_cloudfront_origin_access_control` (s3) + `aws_s3_bucket_policy` | Lab 0, Step 4 and Lab 1: OAC and the bucket policy, with `ListBucket` so a missing file is a 404 |
| `aws_cloudfront_cache_policy.api` | Lab 2, Part 2: min 0, default 0, max 300, all query strings |
| `aws_lambda_function` + `aws_lambda_function_url` | Lab 2, Part 1: the API |
| `aws_cloudfront_origin_access_control` (lambda) + `aws_lambda_permission` | **new:** the Function URL is **not public**: it needs AWS_IAM and only this distribution may call it |
| `origin` blocks + `ordered_cache_behavior "/api/*"` | Lab 2, Parts 2 and 3: two origins and the behavior (no origin request policy) |
| `aws_cloudfront_response_headers_policy` | Lab 4, Part 1: the security headers |
| `aws_cloudfront_function` + `function_association` | Lab 5: the two edge functions |

Questions while you read:
- Which resource points at which? Follow `aws_cloudfront_distribution.site.arn` through the bucket policy and the Lambda permissions. The distribution needs the Lambda URL, and the permission needs the distribution: why is there no loop?
- Where would the console's "Deploying" wait show up? (`apply` waits until the distribution is deployed.)

## Part 3: Build it (15 minutes, mostly waiting)

```bash
terraform init
terraform plan          # about 25 resources to add; read the list
terraform apply         # type yes
```

When it ends, the outputs give the address and commands. It uses the names `cf-lab-tf-...`, so it lives **next to** what you made by hand and clashes with nothing.

```bash
terraform output -raw url
cd .. && bash scripts/check.sh $(terraform -chdir=terraform output -raw url)/index.html 3
```

Check, one by one, that the earlier labs hold:

| Check | You should see |
|---|---|
| `/index.html` three times | Miss, Hit, Hit; `CC: public, max-age=60` |
| `/assets/app.v1.css` | `CC: public, max-age=31536000, immutable` |
| `/about` | 200 (clean URL, Lab 5) |
| `/old-about.html` | 301 to `/about.html` |
| `/admin/x` | 403 `Not allowed here.` |
| `/missing.html` | 404 (not 403) |
| `/api/time?ttl=20` three times (`BODY=1`) | Miss, Hit, Hit with the same `request_id` |
| `curl -sI .../index.html` | the six security headers and `x-served-by-edge-function` |
| the Function URL directly (`curl -s https://<lambda-host>/`) | `403`: it is not public any more |

(To get the Lambda host: `aws lambda get-function-url-config --function-name cf-lab-tf-api --query FunctionUrl --output text`.)

## Part 4: Change something through code (15 minutes)

Make each change in the file, run `terraform plan`, **read what it says**, then `apply`.

1. **A longer cache for HTML.** In `main.tf` change `max-age=60` for `.html` to `max-age=300`. The plan shows the objects `index.html` and `about.html` changing **in place**, nothing else. After `apply`: `bash scripts/check.sh <url>/index.html 1` shows `max-age=300` (the old copy may stay up to its old 60 s).
2. **A new maximum TTL for the API.** Change `max_ttl = 300` to `max_ttl = 60`. The plan shows an in-place change of the cache policy. After the policy is deployed: `?ttl=200` is now cached for 60 seconds, not 200: confirm with `SLEEP=20 BODY=1 bash scripts/check.sh ".../api/time?ttl=200" 5`.
3. **Switch the edge functions off.** `terraform apply -var enable_edge_functions=false`. The plan **destroys** the two functions and removes the associations. `/about` now gives 404 again (there is no such file). Switch them back on.
4. **Drift.** In the console edit the distribution's **default behavior** and turn **Compress objects automatically** off. Run `terraform plan`: it shows that something changed outside Terraform and **puts it back**. Apply to repair it. This is the main reason to keep the console for learning and the code for the real thing.
5. **Break it in a new way.** Remove the `aws_lambda_permission` blocks from `main.tf` and apply. `/api/time` gives a 403 from the origin (the Lambda says CloudFront may not call it). Put them back. (Compare with the Host-header 403 of Lab 2: the same code, a different cause.)

## Part 5: Tear it down

```bash
terraform destroy
```

It disables and deletes the distribution (several minutes), empties and deletes the bucket (`force_destroy`), and removes the Lambda function and the roles. In a sandbox this also happens by itself at the end.

## If you want more (exercises, not written for you)

Each of these is one new resource in the same file; look it up in the Terraform AWS provider documentation:

- **WAF** (Lab 4): `aws_wafv2_web_acl` with `scope = "CLOUDFRONT"` (this one must be created in us-east-1, so it needs a second provider with that region), then `web_acl_id` on the distribution.
- **Signed URLs** (Lab 4): `aws_cloudfront_public_key`, `aws_cloudfront_key_group`, a `/private/*` behavior with `trusted_key_groups`.
- **Origin failover** (Lab 6): a second bucket and an `origin_group` block.
- **Custom domain** (Lab 1): `aliases` and `viewer_certificate` with an ACM certificate from us-east-1, plus `aws_route53_record` with an alias.
- **Logging** (Lab 7): `logging_config` and a bucket for the logs.

---

## Questions

1. Why is the Function URL safer here than in Lab 2? What two things make it so?
2. What does `terraform plan` tell you that the console never does?
3. You change the HTML `Cache-Control` in the code and apply. Why can visitors still see the old header for a while?
4. Why do the resources have `-tf` in their names?
5. What does `terraform destroy` need `force_destroy = true` for?

<details><summary>Answers</summary>

1. The URL needs AWS_IAM (a signed request), and the Lambda permission lets only this CloudFront distribution call it, through OAC.
2. Exactly what will be created, changed or destroyed, before anything happens, and drift: what someone changed by hand.
3. CloudFront still has copies cached under the old TTL until they expire (or you invalidate).
4. So that the lab can run next to the resources you made by hand without name clashes.
5. A bucket that still contains files cannot be deleted; `force_destroy` lets Terraform empty it first.
</details>

## Remember

- The same setup, two ways: the console to learn what each setting does, code to repeat it and keep it correct.
- Read the plan every time. In-place change, replace and destroy mean very different things.
- Drift is real: a hand edit is undone by the next apply.
- OAC works for S3 and for Lambda URLs.
