# Deploy MicroShop on AWS VMs behind an Application Load Balancer

You create the security groups, the database, the photo bucket, the VM, the target groups and the load balancer by hand in the console. The VM sets itself up from `deploy/aws/user-data.sh`: it installs git and Docker, clones this project from GitHub, builds the six images and runs them. The product photos come from an S3 bucket (Step 1c). Two other ways to get the images onto the VM are described at the end (optional, later).

```
Internet -> Application Load Balancer (port 80) -> path rule -> target group -> port on the VM
   /api/products*   microshop-product-tg    8081
   /api/inventory*  microshop-inventory-tg  8082
   /api/users*      microshop-user-tg       8083
   /api/cart*       microshop-cart-tg       8084
   /api/orders*     microshop-order-tg      8085
   everything else  microshop-ui-tg         8080  (the web pages)
Each VM runs 6 containers: product, inventory, user, cart, order, ui.
All VMs share ONE database: Amazon RDS PostgreSQL ("microshop"), one schema per service.
Product photos: S3 bucket, folder products/ (or, without S3, inside the ui image).
```

## Before you start

1. **Commit and push everything** (the PostgreSQL work and the scripts). The VM clones what is on GitHub, not what is in your folder: old code on GitHub means old images without PostgreSQL support. The repo must be **public**.
2. Check the sandbox allows EC2, security groups, RDS, S3, target groups and load balancers. Use **one region** for everything (yours is `us-west-2`).
3. Create the database first (Step 1b). Without it every VM keeps its own data, and you must run only one VM.

## Step 1: Security groups (EC2 > Security Groups)

| Name | Inbound rules |
|---|---|
| `microshop-alb-sg` | HTTP 80 from **My IP** (or `0.0.0.0/0`) |
| `microshop-vm-sg` | TCP **8080-8085** from `microshop-alb-sg`; SSH 22 from the prefix list `com.amazonaws.us-west-2.ec2-instance-connect` (Source: Custom, type `com.amazonaws` and pick it) |
| `microshop-db-sg` | PostgreSQL **5432** from `microshop-vm-sg` (choose the group, not an IP) |

The SSH rule is for the browser button **Connect > EC2 Instance Connect** (Step 3): that button connects from AWS's own addresses, so "My IP" does not work for it (from the AWS docs; not tried here). Create `microshop-alb-sg` first, then `microshop-vm-sg`, then `microshop-db-sg`. Leave outbound as it is (the VM needs the internet). All three must be in the same VPC as the VMs.

## Step 1b: The database (RDS > Create database)

| Field | Value |
|---|---|
| Engine | **PostgreSQL 16.x**, Standard create, single instance (no Multi-AZ) |
| Template and size | Free tier or Dev/Test; the smallest class allowed (for example `db.t4g.micro`) |
| Identifier | `microshopdb1` |
| Master user and password | `microshop` and a password you choose (self managed): **letters and digits only** (the VM script would change a `$` or a backtick). **Save it.** |
| Storage | gp3, 20 GiB, no storage autoscaling |
| Connectivity | The **same VPC as the VMs**. Public access **No**. Security group: `microshop-db-sg` only (remove `default`). |
| Additional configuration > **Initial database name** | **`microshop`** |

It takes about 10 minutes. Then copy the **Endpoint** (`microshopdb1.xxxx.us-west-2.rds.amazonaws.com`) from the database's Connectivity & security tab. You create no tables: each service creates its own schema (`product_svc`, `inventory_svc`, `user_svc`, `cart_svc`, `order_svc`) when it first starts.

If you forgot the initial database name, connect once from a VM and create it (`sudo dnf install -y postgresql15` gives you `psql`):
```bash
PGPASSWORD='<password>' psql "host=<endpoint> dbname=postgres user=microshop sslmode=require" -c 'CREATE DATABASE microshop;'
```

## Step 1c: Product photos in S3

The 732 product photos (55 MB) come from one S3 bucket instead of from the ui image. No code change is needed: the product service returns the photo addresses stored in the database, and the UI shows any `https://` address.

**a) The bucket.** S3 > Create bucket: a unique name, region **us-west-2**, ACLs disabled. Then, on the bucket, **Permissions > Block public access > Edit**, untick all, save and confirm (the photos are meant to be public).

**b) Public read for the photos only.** Bucket > Permissions > Bucket policy > Edit, paste this with your bucket name in place of `BUCKET`, and save:
```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "PublicReadProductPhotos",
    "Effect": "Allow",
    "Principal": "*",
    "Action": "s3:GetObject",
    "Resource": "arn:aws:s3:::BUCKET/products/*"
  }]
}
```

**c) Upload the photos.** The fast way is CloudShell (it sits inside AWS; uploading from your own computer can be very slow, for example 100 KB/s). In CloudShell, same region, with your bucket name:
```bash
B=BUCKET
git clone --depth 1 https://github.com/dailycoder09/devops-practice-01.git
ls devops-practice-01/ui-app/public/photos | head -3      # must show: products (and ATTRIBUTION.csv)
aws s3 sync devops-practice-01/ui-app/public/photos/ s3://$B/ --exclude ATTRIBUTION.csv --cache-control "public,max-age=604800"
rm -rf devops-practice-01
aws s3 ls s3://$B/products/ --recursive | grep -c '\.jpg$'      # must say 732
```
If the `ls` shows no `photos` folder, the photos are not on GitHub yet: push first. The other way: in the console, at the **top level** of the bucket (not inside another folder), Upload > Add folder > `ui-app/public/photos/products`.

*If the photos ended up in the wrong folder* (for example under `images/photos/products/`), move them inside the bucket (this does not use your upload speed). In CloudShell, with `WRONG` set to the wrong prefix:
```bash
WRONG=images/photos/products
aws s3 ls s3://$B/$WRONG/ --recursive | grep -c '\.jpg$'                          # must say 732
aws s3 mv s3://$B/$WRONG/ s3://$B/products/ --recursive --dryrun | head -3        # rehearsal: only prints
aws s3 mv s3://$B/$WRONG/ s3://$B/products/ --recursive --quiet                   # the real move
aws s3 ls s3://$B/products/ --recursive | grep -c '\.jpg$'                        # must say 732 again
```

**d) Check that it is public.** Open `https://BUCKET.s3.us-west-2.amazonaws.com/products/handwritten/iphone-15-pro/1.jpg` in a browser, or run `curl -sI` on it: `HTTP/1.1 200 OK` is good, `403` means the policy or Block public access is not saved.

**e) Point the shop at the bucket.** Set `PHOTOS_URL="https://BUCKET.s3.us-west-2.amazonaws.com"` in the user data (Step 2; no slash at the end, no folder). It is used when the product service first starts on a **new** database, and the ui image is then built without its own photos. For a database that **already exists**, run this once instead, after the services have started (on a VM; `sudo dnf install -y postgresql15` gives you `psql`):
```bash
B=https://BUCKET.s3.us-west-2.amazonaws.com
PGPASSWORD='<password>' psql "host=<endpoint> dbname=microshop user=microshop sslmode=require" <<EOF
UPDATE product_svc.product_images SET url = '$B' || substring(url from 8) WHERE url LIKE '/photos/%';
UPDATE product_svc.products SET image_url = '$B' || substring(image_url from 8) WHERE image_url LIKE '/photos/%';
EOF
```
It changes only addresses that start with `/photos/` (checked on PostgreSQL 16), so running it twice is safe, and no restart is needed. To go back to local photos, run it the other way round: `... SET url = '/photos' || substring(url from LENGTH('$B') + 1) WHERE url LIKE '$B/%';` and the same for `product_svc.products` with `image_url`.

## Step 2: Launch the VM (EC2 > Launch instances)

| Field | Value |
|---|---|
| Name | `microshop-vm-1` |
| Image | Amazon Linux 2023 |
| Instance type | `t3.medium` (4 GiB; five Java services use about 2 GB) or bigger; `m5.large` builds faster |
| Network | default VPC, a public subnet, **Auto-assign public IP: Enable** |
| Security group | `microshop-vm-sg` |
| Storage | 20 GiB gp3 |
| Advanced details > User data | paste the whole file `deploy/aws/user-data.sh` |

In the copy you paste (start at the very first character, `#!`; never put the values in the file on GitHub) fill in `PHOTOS_URL` (Step 1c e, or leave empty), `DB_HOST` (the endpoint), `DB_PASSWORD`, `JWT_SECRET` and `ADMIN_PASSWORD`. Make the secret once with `openssl rand -base64 48`. Use only letters and digits in `DB_PASSWORD` and `ADMIN_PASSWORD` (the script puts the values inside double quotes). Put the **same values on every VM**: the script stops with a message in the log if one is missing, and different secrets give the "session has ended" error. Leave `DB_HOST` empty only for one VM without RDS (each service then keeps its own small H2 file). Click **Launch instance**.

## Step 3: Wait for it, then read the admin login

The first boot installs, clones and builds 6 images, which can take 20 minutes or more. To watch it, connect to the VM (Connect > EC2 Instance Connect) and run:

```bash
sudo tail -f /var/log/user-data.log        # Ctrl+C to stop watching; ends with: DONE: MicroShop is starting on this VM
sudo grep "ADMIN LOGIN" /var/log/user-data.log     # the admin email and password (with RDS: the ADMIN_PASSWORD you wrote)
sudo docker ps                              # 6 containers: Up
```

If the log file is not there yet, wait a minute; if it never appears, read `/var/log/cloud-init-output.log`.

## Step 4: Target groups (EC2 > Target Groups > Create), six times

Same for all: Target type **Instances**, protocol **HTTP**, default VPC, HTTP1. Under **Advanced health check settings**: Healthy 2, Unhealthy 2, Timeout 5, Interval 15, Success codes 200. Register `microshop-vm-1` with the port below and create.

| Name | Port | Health check path |
|---|---|---|
| `microshop-ui-tg` | 8080 | `/healthz` |
| `microshop-product-tg` | 8081 | `/actuator/health` |
| `microshop-inventory-tg` | 8082 | `/actuator/health` |
| `microshop-user-tg` | 8083 | `/actuator/health` |
| `microshop-cart-tg` | 8084 | `/actuator/health` |
| `microshop-order-tg` | 8085 | `/actuator/health` |

More on health checks: [HEALTH-CHECKS.md](HEALTH-CHECKS.md).

## Step 5: The load balancer (EC2 > Load Balancers > Create > Application Load Balancer)

Name `microshop-alb`, **Internet-facing**, IPv4, default VPC, **two Availability Zones** (an ALB needs two), security group `microshop-alb-sg` only. Listener **HTTP : 80**, default action **Forward to** `microshop-ui-tg`. Create it.

## Step 6: Path rules (the load balancer > Listeners and rules > HTTP:80 > Add rule)

Condition **Path**, action **Forward to target group**:

| Priority | Path | Forward to |
|---|---|---|
| 10 | `/api/products*` | `microshop-product-tg` |
| 20 | `/api/inventory*` | `microshop-inventory-tg` |
| 30 | `/api/users*` | `microshop-user-tg` |
| 40 | `/api/cart*` | `microshop-cart-tg` |
| 50 | `/api/orders*` | `microshop-order-tg` |

## Step 7: Check and test

Each target group > **Targets** tab: all six must become **healthy** (about a minute). Then take the **DNS name** from the load balancer page:

```bash
ALB=http://<the DNS name>
curl -s $ALB/healthz                     # ok
curl -s "$ALB/api/products?size=1"       # a product, with its photo address under your bucket
curl -s -X POST $ALB/api/users/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"<the admin password>"}'      # a token
```

Open `http://<the DNS name>` in a browser: browse, register, order, and log in as admin.

## If something is wrong

| Symptom | Check |
|---|---|
| Nothing happens after launch | `sudo tail -100 /var/log/user-data.log` shows the failing step. |
| `git clone` fails | The repo is private or not pushed. |
| The services ignore the database settings, or log H2 files | The VM cloned an old commit: push everything and launch a new VM (Before you start, 1). |
| Downloads fail | The VM has no internet: public subnet with a public IP. |
| A target is unhealthy | `sudo docker ps` (is it Up?), `sudo docker logs order-service`; the security group must allow 8080-8085 from `microshop-alb-sg`. |
| ALB answers 503 for a path | That path's target group has no healthy target. |
| A service cannot reach the database (it hangs, or restarts) | `microshop-db-sg` needs an inbound rule 5432 from `microshop-vm-sg`, and the database must be in the same VPC as the VM. Test: `timeout 5 bash -c "</dev/tcp/<endpoint>/5432" && echo open`. |
| `password authentication failed` in a service log | `DB_PASSWORD` in the user data is wrong. |
| "Your session has ended" right after login | The VMs do not all have the same `JWT_SECRET`, or `DB_HOST` is empty so each VM has its own data. |
| A container keeps restarting / out of memory | Use `t3.medium` or bigger. `free -m`. |
| Photos do not show | Open one photo address in a browser. **403**: the bucket policy or "Block public access" (Step 1c a and b). **404**: the files are not at `products/<folder>/<n>.jpg` in the bucket (Step 1c c). It shows a local `/photos/...` address, or the ui has no photos: `PHOTOS_URL` was empty or the database already existed (Step 1c e). |

## Tear down

Delete the load balancer, the six target groups, terminate the VMs, delete the database `microshopdb1` (no final snapshot), empty and delete the photo bucket, then delete `microshop-db-sg`, `microshop-vm-sg` and `microshop-alb-sg`.

## Good to know

- **Not tested on AWS from here.** There is no AWS account in this project's tools. What was checked on the Mac: all six images build with the real Dockerfiles in Podman and run against a PostgreSQL container; the ui builds without its photos when asked (`WITH_PHOTOS=no`); the VM script was run with fake commands in each mode (no RDS, RDS, with and without `PHOTOS_URL`); and the photo SQL ran correctly on PostgreSQL 16. The S3 photos and the public policy were done by you in the sandbox and checked with `curl` (`200`). The VM boot and the load balancer have not been run yet.
- **Several VMs:** with the shared database and the **same** `DB_PASSWORD`, `JWT_SECRET` and `ADMIN_PASSWORD` on every VM, any number of VMs can be in the target groups. Register every VM in all six target groups, because each VM runs all six containers and the services call each other on `localhost`. The admin account is created by whichever VM starts first.
- With RDS the data lives in the database and survives replacing a VM. Without `DB_HOST` it lives inside the containers and is lost with them.
- Keep the connection count in mind: each service opens up to 5 database connections (`DB_POOL_MAX`), so 5 services times the number of VMs.
- A sandbox session ends after about 4 hours and deletes everything; launch again from Step 2.

## Optional, later, A: carry the images through S3 (needs a fast upload)

`deploy/local/build-images.sh` builds the six images on your Mac with Podman (the ui without photos) and saves them as one file, `deploy/local/out/microshop-images-<commit>.tar.gz` (about 860 MB, first run about 10 to 35 minutes, depending on the network). You upload that file to the bucket folder `images/`, add `images/*` to the public-read policy (the `Resource` of the policy in Step 1c b becomes a list of both), and paste `deploy/aws/user-data-s3.sh` instead of `user-data.sh`: the VM only downloads the file, loads the images and runs them (a few minutes, no git, no building). Fill in `IMAGES_URL` (the address of the file) and `PHOTOS_URL`. **The upload is the catch:** at 100 KB/s the file takes about 2.4 hours, so use it only on a fast connection. The file is public, so it must contain no secrets (it holds only the application code).

## Optional, later, B: build the images once in AWS (CodeBuild + ECR)

Not needed now. This replaces the 20-minute build on every VM by one build in AWS: CodeBuild builds the six images from GitHub and stores them in ECR, and each VM only pulls them (`deploy/aws/user-data-ecr.sh`, a few minutes). Your sandbox allows ECR and CodeBuild (CodeBuild only with small or medium compute). Nothing here was run on a real account.

1. **Push everything first.** CodeBuild builds what is on GitHub, not your folder: if you push only `buildspec.yml`, the build still says Succeeded but makes old images without PostgreSQL support.
2. **Six repositories.** In CloudShell: `for s in product-service inventory-service user-service cart-service order-service ui-app; do aws ecr create-repository --repository-name microshop/$s; done`
3. **The build project** (CodeBuild > Create build project): name `microshop-build`; Source GitHub > **Public repository** `https://github.com/dailycoder09/devops-practice-01` (if the console insists on a GitHub credential, connect your own account once); Environment: Managed image, Amazon Linux, Standard, newest image, type **Linux x86_64** (never ARM); under **Additional configuration** tick **Privileged** ("Enable this flag if you want to build Docker images ..."); Compute **EC2** (never Lambda), size **Medium**; New service role; buildspec: use the file `buildspec.yml` in the repo root. The region selector must show **US West (Oregon)**.
4. **Let the build push.** IAM > Roles > the role CodeBuild created (named like `codebuild-microshop-build-service-role`; CodeBuild > `microshop-build` > Edit > Environment links to it) > Add permissions > Attach policies > **AmazonEC2ContainerRegistryPowerUser**.
5. **Role for the VMs.** IAM > Roles > Create role > AWS service > EC2 > **AmazonEC2ContainerRegistryReadOnly** > name `microshop-vm-role`. Choose it under Advanced details > **IAM instance profile** when you launch the VM, and paste `deploy/aws/user-data-ecr.sh` instead of `user-data.sh` (same values to fill in; `IMAGE_TAG` is `latest` or a commit tag from ECR).
6. **Build.** CodeBuild > `microshop-build` > **Start build** (about 10 to 15 minutes; the Phase details tab shows a failed phase). ECR then shows six repositories with the tags `latest` and the first 8 characters of the commit (check it equals `git rev-parse --short=8 HEAD`). Launch the VM after that; if the build is still running, the VM waits up to 15 minutes for the images.

A running VM is not updated by a new build: images are pulled once, at the VM's first boot. To roll out new code, build again and launch a new VM.

| Symptom | Check |
|---|---|
| `docker login` or a pull fails with `no basic auth credentials`, `AccessDenied` or `Unable to locate credentials` | The VM has no IAM instance profile, or it lacks `AmazonEC2ContainerRegistryReadOnly`. The boot script does not run again: terminate that VM and launch a new one with the profile chosen. |
| The log ends with `STOP: cannot pull ...`, or `repository ... not found` / `manifest unknown` | The build has not succeeded yet, the repository names are not exactly `microshop/<name>`, or `IMAGE_TAG` does not exist. Fix it, then launch a new VM. |
| `exec format error` in `docker logs` | The images were built on an ARM environment: set the project to **Linux x86_64** and build again. |
| The build fails in `DOWNLOAD_SOURCE` or with `YAML_FILE_ERROR` | `buildspec.yml` is not on GitHub: push it. |
| The build fails with `Cannot connect to the Docker daemon` | The **Privileged** box is not ticked. |
| The build fails at `docker push` with `repository ... does not exist` or `denied` | The build role lacks `AmazonEC2ContainerRegistryPowerUser`, or the project and the repositories are in different regions. |
| `429 Too Many Requests` or `toomanyrequests` | Docker Hub limits anonymous pulls per IP over 6 hours: start the build again (a new build often runs on another machine); if it keeps happening, log in to Docker Hub from the build with a free account. |

To delete it all later: the six ECR repositories (with their images), the CodeBuild project and the two IAM roles.
