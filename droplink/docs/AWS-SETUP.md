# DropLink on AWS: step by step

You build the bucket, the table, the role and one EC2 instance by hand. The jar is built in CloudShell and kept in your S3 bucket; the instance sets itself up from `deploy/user-data.sh`. Region everywhere: **us-west-2 (Oregon)**. Everything is deleted when the sandbox ends (about 4 hours), so Step 10 is the short checklist to repeat it.

> **Not run on AWS.** There was no AWS account when these scripts and this guide were written. The scripts were checked with `bash -n` and with fake commands (the AWS CLI, `curl`, `dnf`, `systemctl` replaced by stubs), and the JSON files were checked to be valid JSON. **No command here was run against AWS.** Console button names are written from memory and may differ a little. Where something is a guess, the text says **(not verified)**. If a step fails, the error message is the truth, not this page.

```
Browser --http:8080--> EC2 (DropLink jar, systemd) --role--> S3 bucket (files/ and releases/)
   |                                              |--------> DynamoDB table (links, ttl)
   +-- uploads and downloads go straight to S3    +--------> SNS topic (optional e-mail)
       with signed addresses (bucket stays PRIVATE)
```

## Before you start

1. **Commit and push the `droplink/` folder** (including `deploy/`) to GitHub. CloudShell clones what is on GitHub, not your folder. The repo must be public.
2. The values you will make, write them down as you go:

| Name | What it is | Example |
|---|---|---|
| `B` | the S3 bucket name (unique in the world) | `droplink-yourname-12345` |
| `TABLE` | the DynamoDB table name | `droplink` |
| `TABLE_ARN` | its ARN (printed in Step 2) | `arn:aws:dynamodb:us-west-2:123456789012:table/droplink` |
| `TOPIC_ARN` | the SNS topic ARN, only if you do Step 3 | `arn:aws:sns:us-west-2:123456789012:droplink-downloads` |
| `SECRET` | the cookie secret, made once: `openssl rand -hex 32` | 64 letters and digits |

3. Open CloudShell (the `>_` icon at the top of the console) with the region **US West (Oregon)** selected, then paste:
```bash
export AWS_DEFAULT_REGION=us-west-2
git clone --depth 1 https://github.com/dailycoder09/devops-practice-01.git
cd devops-practice-01/droplink
ls deploy        # must list: cloudshell-build.sh  ec2-trust-policy.json  iam-policy.json  s3-cors.json  s3-lifecycle.json  user-data.sh
```
All the commands below run from this folder (`droplink`). If `ls deploy` shows nothing, the folder is not pushed yet.

## Step 0: Check first what the sandbox allows

**Not confirmed:** whether this sandbox lets you create IAM roles. DropLink needs one (the instance uses it to read the jar and to sign the browser addresses). Check **before** doing anything else. Each command creates a throw-away thing and deletes it:

```bash
aws sts get-caller-identity --query Arn --output text       # who you are
aws iam create-role --role-name droplink-probe --query Role.Arn --output text \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
aws iam create-instance-profile --instance-profile-name droplink-probe --query InstanceProfile.Arn --output text
aws iam delete-instance-profile --instance-profile-name droplink-probe
aws iam delete-role --role-name droplink-probe
aws dynamodb create-table --table-name droplink-probe --billing-mode PAY_PER_REQUEST \
  --attribute-definitions AttributeName=pk,AttributeType=S --key-schema AttributeName=pk,KeyType=HASH --query TableDescription.TableStatus --output text
aws dynamodb wait table-exists --table-name droplink-probe
aws dynamodb delete-table --table-name droplink-probe --query TableDescription.TableStatus --output text
```

| Result | Meaning |
|---|---|
| Each command prints an ARN or `CREATING` / `DELETING`, no error | Go on to Step 1. |
| `AccessDenied` / `not authorized to perform: iam:CreateRole` | **Stop.** This guide has no way around the role. Look in the EC2 launch wizard (Advanced details > IAM instance profile) for a ready-made profile you may use, and check what it allows. Putting access keys into the instance would probably work with the AWS SDK, but it is not part of this design and was not tested. |
| `AccessDenied` on DynamoDB only | No table, no DropLink in `aws` mode. Check the sandbox's allowed services. |
| `EntityAlreadyExists` from an earlier try | Delete it (`aws iam delete-role --role-name droplink-probe`) and repeat. |

Also look at the EC2 side: the sandbox must allow `t3.small` (Step 6). If it does not, use the biggest `t3`/`t2` it allows with at least 2 GiB of memory.

## Step 1: The S3 bucket (private)

One bucket holds two folders: `files/` (what people upload) and `releases/` (the jar). **Block all public access stays ON.** Nobody reads the bucket directly: the browser uses signed addresses (10 minutes to upload, 60 seconds to download).

**Console:** S3 > **Create bucket** > name `droplink-yourname-12345` (yours must be unique), region **US West (Oregon) us-west-2**, ACLs **disabled**, **Block all public access: ticked (all four)**, leave the rest, **Create bucket**. Create no folders. Then open the bucket:
- **Permissions > Cross-origin resource sharing (CORS) > Edit**: paste the content of `deploy/s3-cors.json`. The console wants only the list, so paste from `[` to `]` (without the `{ "CORSRules":` part). **(not verified)** Save.
- **Management > Create lifecycle rule**: name `expire-files-after-8-days`; scope **Limit the scope using filters**, prefix `files/`; tick **Expire current versions of objects** = **8** days and **Delete expired object delete markers or incomplete multipart uploads** > **Delete incomplete multipart uploads** after **1** day; create.

**CloudShell** (does the same, from the JSON files):
```bash
B=droplink-yourname-12345        # your own unique name
aws s3api create-bucket --bucket $B --region us-west-2 --create-bucket-configuration LocationConstraint=us-west-2
aws s3api put-public-access-block --bucket $B --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-cors --bucket $B --cors-configuration file://deploy/s3-cors.json
aws s3api put-bucket-lifecycle-configuration --bucket $B --lifecycle-configuration file://deploy/s3-lifecycle.json
```
Check:
```bash
aws s3api get-public-access-block --bucket $B --query PublicAccessBlockConfiguration     # four times true
aws s3api get-bucket-cors --bucket $B --query 'CORSRules[0].AllowedMethods'              # PUT, GET, HEAD
aws s3api get-bucket-lifecycle-configuration --bucket $B --query 'Rules[0].[Expiration.Days,AbortIncompleteMultipartUpload.DaysAfterInitiation]'    # 8, 1
```

| File | What it does |
|---|---|
| `s3-cors.json` | lets the browser `PUT` (upload), `GET` and `HEAD` to the bucket from the page; exposes the `ETag` header; remembers the answer 3000 s. `AllowedOrigins` is `"*"` for this proof of concept: **narrow it to the app address** (`http://PUBLIC-IP:8080`) later. |
| `s3-lifecycle.json` | deletes objects under `files/` after 8 days, and removes unfinished multipart uploads after 1 day (they cost space and nobody sees them). `releases/` is not touched. |

## Step 2: The DynamoDB table and its time-to-live

On-demand billing, partition key `pk` (String), sort key `sk` (String), time-to-live attribute **`ttl`** (DynamoDB removes expired items in the background; this is not instant).

**Console:** DynamoDB > **Create table** > name `droplink`, partition key `pk` **String**, sort key `sk` **String**, **Customize settings** > capacity mode **On-demand**, **Create table**. When it is Active: open it > **Additional settings** (tab) > **Time to Live (TTL) > Turn on**, attribute name `ttl`, confirm. Copy the table **ARN** (Overview > Additional info).

**CloudShell:**
```bash
aws dynamodb create-table --table-name droplink --billing-mode PAY_PER_REQUEST \
  --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
  --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE --query TableDescription.TableStatus --output text
aws dynamodb wait table-exists --table-name droplink
aws dynamodb update-time-to-live --table-name droplink --time-to-live-specification Enabled=true,AttributeName=ttl
TABLE_ARN=$(aws dynamodb describe-table --table-name droplink --query Table.TableArn --output text); echo $TABLE_ARN
aws dynamodb describe-time-to-live --table-name droplink --query TimeToLiveDescription     # ENABLED, ttl
```
The status may say `ENABLING` for a few minutes: that is fine.

## Step 3 (optional): An SNS topic for the e-mail on download

Skip it to have no e-mail: leave `TOPIC_ARN` empty in the user data later (and see the note in Step 4).

**Console:** SNS > Topics > **Create topic** > **Standard**, name `droplink-downloads` > Create. Open it > **Create subscription**: protocol **Email**, your address. Open the e-mail from AWS and click **Confirm subscription**. Copy the topic **ARN**.

**CloudShell:**
```bash
TOPIC_ARN=$(aws sns create-topic --name droplink-downloads --query TopicArn --output text); echo $TOPIC_ARN
aws sns subscribe --topic-arn $TOPIC_ARN --protocol email --notification-endpoint YOUR-EMAIL-ADDRESS
```
Then click **Confirm subscription** in the e-mail. Until you do, nothing is sent.

## Step 4: IAM policy, role and instance profile

The instance gets its permissions from a **role** (no keys anywhere). `deploy/iam-policy.json` has **three words to replace** before you use it:

| Placeholder | Replace with |
|---|---|
| `BUCKET` (every place) | the bucket name only, no `s3://` (example `droplink-yourname-12345`) |
| `TABLE_ARN` | the full table ARN from Step 2 |
| `TOPIC_ARN` | the full topic ARN from Step 3 |

What the policy allows (and nothing more):

| Statement | Allows | On |
|---|---|---|
| `UploadDownloadDeleteFiles` | `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject` | `arn:aws:s3:::BUCKET/files/*` |
| `ListSoMissingFilesAnswer404` | `s3:ListBucket` | `arn:aws:s3:::BUCKET` (without it S3 answers 403 instead of 404 for a missing file, and the app would show an error page instead of "upload has not arrived") |
| `ReadTheJarRelease` | `s3:GetObject` | `arn:aws:s3:::BUCKET/releases/*` (the instance downloads the jar) |
| `LinkTable` | `dynamodb:GetItem`, `PutItem`, `UpdateItem`, `DeleteItem`, `Query`, `BatchWriteItem` | the table ARN |
| `DownloadMail` | `sns:Publish` | the topic ARN |

The signed addresses are signed with the role's temporary credentials, which is why the upload address lives only 10 minutes and the download address 60 seconds.
**No SNS topic?** Delete the whole `DownloadMail` statement (console: in the JSON editor; CloudShell: the command below does it).

**Console:**
1. IAM > Policies > **Create policy** > **JSON** tab: paste `iam-policy.json` with the three words replaced > Next > name `droplink-app` > Create policy.
2. IAM > Roles > **Create role** > **AWS service** > use case **EC2** > Next > tick `droplink-app` > Next > name **`droplink-ec2-role`** > Create role. (From the console, EC2 roles get an instance profile with the same name automatically.) The trust policy it makes is the same as `deploy/ec2-trust-policy.json`: only `ec2.amazonaws.com` may use the role.

**CloudShell:**
```bash
# fill in the three placeholders (B, TABLE_ARN and TOPIC_ARN must be set in this shell)
sed -e "s|BUCKET|$B|g" -e "s|TABLE_ARN|$TABLE_ARN|g" -e "s|TOPIC_ARN|$TOPIC_ARN|g" deploy/iam-policy.json > /tmp/droplink-policy.json
# no topic? drop the SNS statement:
# python3 -c 'import json,sys; p=json.load(sys.stdin); p["Statement"]=[s for s in p["Statement"] if s["Sid"]!="DownloadMail"]; print(json.dumps(p,indent=2))' < /tmp/droplink-policy.json > /tmp/p2.json && mv /tmp/p2.json /tmp/droplink-policy.json
grep -c 'BUCKET\|TABLE_ARN\|TOPIC_ARN' /tmp/droplink-policy.json      # must print 0 (no placeholder left)

POLICY_ARN=$(aws iam create-policy --policy-name droplink-app --policy-document file:///tmp/droplink-policy.json --query Policy.Arn --output text); echo $POLICY_ARN
aws iam create-role --role-name droplink-ec2-role --assume-role-policy-document file://deploy/ec2-trust-policy.json --query Role.Arn --output text
aws iam attach-role-policy --role-name droplink-ec2-role --policy-arn $POLICY_ARN
aws iam create-instance-profile --instance-profile-name droplink-ec2-role
aws iam add-role-to-instance-profile --instance-profile-name droplink-ec2-role --role-name droplink-ec2-role
rm /tmp/droplink-policy.json
```
Wait about 10 seconds before Step 6 (a new instance profile needs a moment to be usable; **not verified**).

## Step 5: Build the jar in CloudShell and put it in S3

```bash
BUCKET=$B bash deploy/cloudshell-build.sh
```
It installs Maven 3.9.6 into `~/tools` if missing, clones the repo to a temporary folder, runs `mvn -q -DskipTests package`, uploads the jar to `s3://BUCKET/releases/droplink-<commit>.jar` **and** `droplink-latest.jar`, prints both addresses and the jar size, and removes the clone. The first run downloads the dependencies: **a few minutes**. It stops with a message if `BUCKET` is empty, the bucket cannot be reached, the download or build fails.

Check:
```bash
aws s3 ls s3://$B/releases/ --human-readable      # two files, the same size (tens of MB)
```
CloudShell has 1 GB of home space. If a later build says `No space left on device`: `rm -rf ~/.m2` (Maven's cache) and run it again.

## Step 6: Launch the EC2 instance

**First make the user data.** Copy `deploy/user-data.sh` and fill in the top (only in your copy, never in the file on GitHub; save it on your computer as `deploy/my-user-data.local.sh`, git ignores `*.local.sh`):

| Variable | Value | Becomes |
|---|---|---|
| `BUCKET` | the bucket name | `DROPLINK_BUCKET` |
| `TABLE` | `droplink` | `DROPLINK_TABLE` |
| `TOPIC_ARN` | the topic ARN, or empty | `DROPLINK_TOPIC_ARN` |
| `SECRET` | `openssl rand -hex 32` (letters and digits; the **same** on every instance) | `DROPLINK_SECRET` |
| `JAR` | `droplink-latest.jar` | the file read from `releases/` |
| `PUBLIC_URL` | empty for now (the public IP is not known before launch). It is the public address of the app (`http://PUBLIC-IP:8080`). What the app does when it is empty: ask the app's own README **(not verified)**. | `DROPLINK_PUBLIC_URL` |

The script also sets `DROPLINK_MODE=aws`, `DROPLINK_KEY_PREFIX=files/` and `DROPLINK_REGION` (read from the instance metadata). It stops with a message in the log if `BUCKET`, `TABLE` or `SECRET` is empty, or `SECRET` is under 16 characters.

**EC2 > Launch instances:**

| Field | Value |
|---|---|
| Name | `droplink-1` |
| Image | **Amazon Linux 2023** |
| Instance type | **`t3.small`** (2 GiB; the Java heap is capped at 512 MB) |
| Key pair | Proceed without a key pair (you do not need SSH) |
| Network settings > Edit | default VPC, a public subnet, **Auto-assign public IP: Enable** |
| Security group | **Create** `droplink-sg`: inbound **Custom TCP, port 8080, source My IP**. Remove or restrict the default SSH rule. |
| Storage | 8 GiB gp3 (the default) |
| Advanced details > **IAM instance profile** | **`droplink-ec2-role`** |
| Advanced details > **User data** | the whole filled copy, from the first character `#!` |

Click **Launch instance**. The user data runs **only at first boot**: if you forgot the profile or a value, terminate the instance and launch a new one.

**Watch it.** The first boot takes about 2 to 4 minutes. Without SSH: EC2 > the instance > **Actions > Monitor and troubleshoot > Get system log** (shows the end of the boot output; **not verified**). With SSH: add an inbound rule SSH 22 from the prefix list `com.amazonaws.us-west-2.ec2-instance-connect`, then **Connect > EC2 Instance Connect**, and run:
```bash
sudo tail -f /var/log/user-data.log        # ends with: DONE: DropLink is UP on port 8080
sudo journalctl -u droplink --no-pager | tail -50
```

**Open it:** copy the instance's **Public IPv4 address**, then in your browser `http://PUBLIC-IP:8080`. Health: `http://PUBLIC-IP:8080/actuator/health/readiness` shows `{"status":"UP"}`.

## Step 7: What to test

| # | Do | You should see |
|---|---|---|
| 1 | Upload a **small image** (a few hundred KB) | a progress bar, then a link page |
| 2 | Look at the preview | the image shows |
| 3 | **Copy the link**, open it in a private window | the download page |
| 4 | Click **Download** | the file arrives and is the same file |
| 5 | Make a **one-time link**, open it twice | works once; the second time says used or expired |
| 6 | Make a link **with a password** | asks for it; a wrong one is refused; the right one downloads |
| 7 | Open the **activity page** | the downloads you just did are listed (and, with SNS, an e-mail arrives) |
| 8 | **Delete** a link | the link no longer works |

In the AWS console, too:
- **S3** > your bucket > `files/`: the uploaded object is there (and disappears after the delete in test 8, **not verified**).  The bucket must still show as not public.
- **DynamoDB** > Tables > `droplink` > **Explore table items**: the link item (`pk`, `sk`, and a `ttl` number).
- From the instance's IP: `curl -s http://PUBLIC-IP:8080/actuator/health` is `UP`.

## Step 8: If something is wrong

| Symptom | Check |
|---|---|
| The log says `STOP: cannot download s3://.../releases/...` | 1) The instance has **no instance profile**: EC2 > the instance > Actions > Security > **Modify IAM role** > `droplink-ec2-role`, attaching it afterwards does not re-run the boot script, so launch a new instance. 2) The jar is not in S3: `aws s3 ls s3://$B/releases/`. 3) `BUCKET` or `JAR` is misspelled. 4) The policy statement `ReadTheJarRelease` lacks the real bucket name. |
| The log says `STOP: BUCKET, TABLE and SECRET must be filled in` | You pasted the empty file. Launch again with the filled copy. |
| The browser upload fails with `403` | Open the browser tools (F12) > Network/Console. A **CORS** message = the bucket CORS is missing or not saved (`aws s3api get-bucket-cors --bucket $B`). An XML `AccessDenied` from S3 = the role lacks `s3:PutObject` on `files/*`, or the key prefix is not `files/`. `Request has expired` = the 10 minute address ran out: upload again. |
| `Access Denied` or `Unable to load credentials` when the page asks for an upload/download address (in `journalctl -u droplink`) | The app cannot sign: no role on the instance (see the first row), or the instance is not in the same account as the bucket. |
| `AccessDeniedException` from DynamoDB in the log | The policy's `TABLE_ARN` is wrong (region, account or name; `aws dynamodb describe-table --table-name droplink --query Table.TableArn`) or an action is missing. Fix the policy in IAM: it applies at once, no restart needed (**not verified**). `ResourceNotFoundException` = wrong `TABLE` name or region. |
| Health is not UP / the log ends with `FAILED: DropLink did not answer UP` | `sudo journalctl -u droplink --no-pager \| tail -50`. Typical: `DROPLINK_SECRET` empty, wrong bucket or table name, no memory. Check the settings without showing the secret: `sudo sed 's/SECRET=.*/SECRET=hidden/' /etc/droplink.env`. After a fix in `/etc/droplink.env`: `sudo systemctl restart droplink`. |
| The page does not open at all | The security group must allow TCP **8080** from **your current IP** (it changes: edit the rule), the instance needs a public IP, and the address must be `http://` (not https). |
| Upload works, **preview does not** | The browser reads the image through a signed `GET` from S3. Check CORS allows `GET`/`HEAD`, and the F12 > Network tab for the failing address: `403` = expired (60 s) or role; CORS error = CORS. |
| Link works but the downloaded file is empty or wrong | The upload did not finish (check the object size in S3). Upload again. |
| No e-mail on download | You did not click **Confirm subscription**; or `TOPIC_ARN` is empty in the user data; or the policy has no `sns:Publish` on that topic. |
| Password link asks again and again | `SECRET` differs between instances (or changed after a restart). |

## Step 9: Tear down

The sandbox deletes everything by itself after about 4 hours. To do it by hand (the order matters):
```bash
aws ec2 terminate-instances --instance-ids i-XXXXXXXX          # or the console; then delete droplink-sg when it is terminated
aws s3 rm s3://$B --recursive && aws s3api delete-bucket --bucket $B
aws dynamodb delete-table --table-name droplink
aws sns delete-topic --topic-arn $TOPIC_ARN                    # if you made it
aws iam remove-role-from-instance-profile --instance-profile-name droplink-ec2-role --role-name droplink-ec2-role
aws iam delete-instance-profile --instance-profile-name droplink-ec2-role
aws iam detach-role-policy --role-name droplink-ec2-role --policy-arn $POLICY_ARN
aws iam delete-role --role-name droplink-ec2-role
aws iam delete-policy --policy-arn $POLICY_ARN
rm -rf ~/devops-practice-01 ~/.m2
```

## Step 10: Every new lab session (checklist)

- [ ] `droplink/` is pushed to GitHub (see Before you start).
- [ ] CloudShell: `export AWS_DEFAULT_REGION=us-west-2`, clone the repo, `cd devops-practice-01/droplink`.
- [ ] Step 0: the IAM role and DynamoDB probe works.
- [ ] Step 1: bucket (4 commands). New unique name `B`.
- [ ] Step 2: table + TTL. Keep `TABLE_ARN`.
- [ ] Step 3 (optional): SNS topic, **confirm the e-mail again** (a new account means a new subscription).
- [ ] Step 4: policy, role, instance profile (fill the three placeholders again: the bucket name and ARNs are new).
- [ ] Step 5: `BUCKET=$B bash deploy/cloudshell-build.sh`.
- [ ] Step 6: new `SECRET` (`openssl rand -hex 32`), fill the user data, launch `t3.small` with the profile and `droplink-sg` (8080 from My IP).
- [ ] Open `http://PUBLIC-IP:8080` and run the tests of Step 7.

## Step 11: Next stage, not built yet

An **Application Load Balancer** with an **Auto Scaling group**. Nothing here is built or tested:
1. Launch template: the same AMI, instance type, instance profile and the **same user data** (same `DROPLINK_SECRET`).
2. Auto Scaling group: **min 2** instances across **two Availability Zones**; health check type **ELB** on `/actuator/health/readiness`.
3. Target tracking scaling on **average CPU 50%**.
4. ALB listener HTTP 80 with path rules `/api/*` and `/d/*` to the target group (default rule to the same group; instance security group allows 8080 from the ALB group only).
5. Optional canary: two target groups with **weighted** forwarding (for example 90/10) for a new jar version.
6. Optional **NLB** in front of the ALB for fixed IP addresses.
7. Narrow the bucket CORS `AllowedOrigins` to the load balancer address and set `DROPLINK_PUBLIC_URL` to it.

The app is stateless (files in S3, links in DynamoDB), so it scales without changes **as long as every instance has the same `DROPLINK_SECRET`**.

## What was and was not tested

| Tested here (on the Mac, no AWS) | NOT tested |
|---|---|
| `bash -n` on both scripts; the four JSON files parse as JSON | Any AWS call: the CLI commands, the console steps, the policy in IAM, CORS and lifecycle accepted by S3 |
| `cloudshell-build.sh` and `user-data.sh` run against stub commands: the normal path, the guards (empty values, short or bad secret), a failed jar download, a service that never gets UP, a missing region in the metadata | The boot of a real Amazon Linux 2023 instance, `dnf install java-17-amazon-corretto-headless`, systemd, the IMDSv2 calls |
| The env file is mode 600 and holds the `DROPLINK_*` names; the secret is not in the log | That IAM roles can be created in this sandbox (Step 0), the Maven build in CloudShell, the 1 GB home limit, the console button names |
| | The app itself with the real S3, DynamoDB and SNS, the browser upload with CORS, and Step 11 |
