# Phase 6: Watch it, then rebuild it with Terraform (about 60 minutes)

**Goal:** prove with numbers how much CloudFront saves your API, then build the **whole project** again with one command.

**You will learn:** how to read the CloudFront and Lambda metrics side by side, and how a full project (S3, DynamoDB, Lambda, API Gateway, CloudFront, optionally domain, HTTPS and WAF) looks as code.

---

## Part 1: How much does the cache save? (20 minutes)

Make 200 requests for the list, then count what reached the Lambda:

```bash
for i in $(seq 1 200); do curl -s -o /dev/null https://$DIST/api/photos; done; echo done
```

Wait two or three minutes, then compare:
- **CloudFront** (N. Virginia console) → your distribution → **Monitoring** → **Requests**: about 200 more than before.
- **Lambda** (us-west-2) → `photowall-api` → Monitor → **Invocations** for the same minutes: only a few (about one per 30 seconds per edge location that answered).

**Your numbers:**

| | Requests |
|---|---|
| Requests that reached CloudFront | |
| Invocations of the Lambda | |
| Share answered by the cache | |

This is the reason for the whole architecture: the cache answers most of the traffic, so Lambda, API Gateway and DynamoDB stay quiet and cheap. Repeat with `LIST_TTL_SECONDS=0` and watch the difference.

Also look at **CloudWatch → Log groups → `/aws/lambda/photowall-api`**: one line per invocation. Open one log stream and find the errors you caused in Phases 3 and 4 (the function logs only the error name, never the key or the body).

**Optional: an alarm** like Lab 7 of the CloudFront lab: CloudWatch (N. Virginia) → alarm on CloudFront **5xxErrorRate** for your distribution, greater than 5, with an e-mail.

## Part 2: Terraform: the whole project as code (40 minutes)

> **Read this first.** The Terraform in `terraform/` has **not** been through `terraform validate` yet (only a check that its brackets balance). Step 2 below runs the validation in CloudShell. If it prints an error, send me the message: that is expected the first time, and a fix takes a minute.

What it builds (the same as Phases 1 to 5):

| Terraform | Console phase |
|---|---|
| `aws_s3_bucket` + public access block + bucket policy with exact paths + CORS | 1 and 4 |
| `aws_s3_object` for every file of `site/`, with the cache headers | 1 |
| `aws_dynamodb_table` + `aws_dynamodb_table_item` (the sample photos) | 2 |
| `aws_lambda_function` + role + `aws_apigatewayv2_*` (HTTP API, route `ANY /api/{proxy+}`) | 2 |
| `aws_cloudfront_distribution` with two origins, `/api/*` behavior, cache policy, response headers policy | 1, 2 and 5 |
| `aws_route53_zone`, `aws_acm_certificate`, records (only if `domain_name` is set) | 5 |
| `aws_wafv2_web_acl` rate limit (only if `enable_waf = true`) | 5 |

### Step 1: Install Terraform in CloudShell (once per session)

```bash
mkdir -p ~/tools && curl -fsSLo ~/tools/tf.zip https://releases.hashicorp.com/terraform/1.9.8/terraform_1.9.8_linux_amd64.zip \
  && unzip -o ~/tools/tf.zip -d ~/tools && export PATH=$HOME/tools:$PATH
export TF_DATA_DIR=/tmp/tfdata && mkdir -p $TF_DATA_DIR        # the AWS plugin is big: keep it outside your 1 GB home
cd ~/devops-practice-01/photowall/terraform                     # your clone of the repo
terraform version
```

### Step 2: Validate before you build

```bash
terraform init -backend=false
terraform validate
```

It must say `Success! The configuration is valid.` If not, paste me the error.

### Step 3: Build it (10 to 15 minutes, mostly CloudFront)

First **without** a domain, so that nothing depends on DNS:

```bash
terraform init
terraform plan                       # about 50 resources; read the list
terraform apply                      # type yes
terraform output url
terraform output -raw upload_key     # the word for the Upload key box
```

The names are `photowall-tf-...`, so it lives **next to** what you built by hand without clashes. Open the `url`. You should see the wall with the sample photos and **"The list comes from the API"**: the API, the likes and the upload work as in the phases. Run the checks of Phase 2 (`check.sh .../api/photos 3`) and Phase 4 (an upload).

**Own address too (Phase 5):** two steps, because the certificate must wait for your name servers:

```bash
terraform apply -var domain_name=photowall.shaikhirfan.com -target=aws_route53_zone.site
terraform output name_servers        # set these as four NS records at the registrar, then wait until dig shows them
terraform apply -var domain_name=photowall.shaikhirfan.com       # now the certificate, the alias, everything else
```

Add `-var enable_waf=true` for the rate limit (it costs money outside a sandbox).

### Step 4: Change something through the code

1. `terraform apply -var list_ttl_seconds=5` : the plan shows one change, the Lambda's setting. Test with `check.sh`.
2. Edit `site/index.html` (change the text), run `terraform apply`: only the `index.html` object changes. Visitors see it within a minute (Phase 1 rule).
3. In the console, change the cache policy `photowall-tf-api-respect-origin` (for example the maximum TTL), then `terraform plan`: it shows the **drift** and puts it back.

### Step 5: Compare, then remove

| | Console | Terraform |
|---|---|---|
| First build | about 4 hours of clicks | 15 minutes |
| Rebuild in a new sandbox | start over | the same command |
| Knowing why each setting exists | yes: you did it | read the code with the phase table above |
| Mistakes | easy to make, hard to see | the plan shows every change first |

```bash
terraform destroy
```

---

## Clean up the console version

Delete, in this order: the WAF web ACL (remove it from the distribution first), the CloudFront distribution (disable → wait → delete), the Route 53 records and zone, the ACM certificate, the API Gateway API, the Lambda function and its role, the DynamoDB table, the S3 buckets (empty them first), CloudWatch alarms and log groups. A sandbox does this for you at the end.

## Questions

1. 200 visitors open the page in one minute and the list TTL is 30 seconds. Roughly how often does the Lambda run?
2. Why is the Terraform bucket CORS built from the distribution's name?
3. Why does the sample-photo item have `ignore_changes`?
4. What do you gain from `terraform plan` that the console never gives you?

<details><summary>Answers</summary>

1. About two to three times (once per 30-second window per edge location that serves the visitors), not 200 times.
2. The page runs at that address, and S3 must allow exactly that origin to send the upload.
3. Visitors change the likes. Without it the next apply would reset the likes to 0.
4. A list of every change before it happens, and a view of what someone changed by hand (drift).
</details>

## Remember

- The numbers show the point of the design: the cache protects the Lambda and the database.
- Validate the Terraform first, then apply without a domain, then add the domain.
- Terraform is for repeating and keeping things correct, the console for learning why.
