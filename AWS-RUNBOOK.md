# AWS runbook: bring MicroShop up in a new lab session

Use this **every time the lab is new**: the sandbox deletes everything (VMs, RDS, S3) after about 4 hours. It takes about 40 to 60 minutes, mostly waiting. Region everywhere: **us-west-2 (Oregon)**. The explanations and troubleshooting are in [DEPLOY-AWS.md](DEPLOY-AWS.md). Tick the boxes as you go.

```
1 Security groups -> 2 Database (start it, 10 min) -> 3 Photo bucket (while you wait) -> 4 Launch the VM
-> 5 Target groups, load balancer, rules (while the VM builds, 20 min) -> 6 Check the VM -> 7 Test
```

## 0. Before the session, only when the code changed

- [ ] Commit and push **everything** to GitHub (the repo is public). The VM clones what is there.
- [ ] Check the push in CloudShell (both files must be listed, the number must be 1):
  ```bash
  git clone --depth 1 https://github.com/dailycoder09/devops-practice-01.git
  ls devops-practice-01/product-service/src/main/resources/application-postgres.yml devops-practice-01/deploy/aws/user-data.sh
  grep -c WITH_PHOTOS devops-practice-01/ui-app/Dockerfile
  rm -rf devops-practice-01
  ```
- [ ] Keep your filled-in copy of the user data on your computer as `deploy/aws/my-user-data.local.sh` (git ignores `*.local.sh`). Never put passwords in a file that is pushed.

## 1. Security groups (EC2 > Security Groups), same VPC for all three

| Name | Inbound rule |
|---|---|
| `microshop-alb-sg` | HTTP 80 from My IP (or `0.0.0.0/0`) |
| `microshop-vm-sg` | TCP 8080-8085 from `microshop-alb-sg`; SSH 22 from the prefix list `com.amazonaws.us-west-2.ec2-instance-connect` |
| `microshop-db-sg` | PostgreSQL 5432 from `microshop-vm-sg` (pick the group) |

- [ ] The three groups exist, created in this order.

## 2. Database (RDS > Create database): start it now, it takes about 10 minutes

| Field | Value |
|---|---|
| Engine | PostgreSQL 16.x, Standard create, single instance |
| Size | Free tier / Dev-Test, smallest class (for example `db.t4g.micro`) |
| Identifier | `microshopdb1` |
| Master user / password | `microshop` / your DB password: **letters and digits only** |
| Storage | gp3, 20 GiB, no autoscaling |
| Connectivity | Same VPC as the VM, Public access **No**, security group `microshop-db-sg` only |
| Additional configuration > **Initial database name** | **`microshop`** |

- [ ] Creating. Later copy the **Endpoint** (Connectivity & security tab) for step 4.

## 3. Photo bucket: while the database is creating (optional; skip it by leaving `PHOTOS_URL` empty in step 4)

Paste in CloudShell. Change only `B` to a name nobody else uses. The bucket is public for `products/*` only.
```bash
B=your-unique-bucket-name
R=us-west-2
aws s3api create-bucket --bucket $B --region $R --create-bucket-configuration LocationConstraint=$R
aws s3api put-public-access-block --bucket $B --public-access-block-configuration "BlockPublicAcls=false,IgnorePublicAcls=false,BlockPublicPolicy=false,RestrictPublicBuckets=false"
cat > /tmp/policy.json <<EOF
{"Version":"2012-10-17","Statement":[{"Sid":"PublicReadProductPhotos","Effect":"Allow","Principal":"*","Action":"s3:GetObject","Resource":"arn:aws:s3:::$B/products/*"}]}
EOF
aws s3api put-bucket-policy --bucket $B --policy file:///tmp/policy.json
git clone --depth 1 https://github.com/dailycoder09/devops-practice-01.git
aws s3 sync devops-practice-01/ui-app/public/photos/ s3://$B/ --exclude ATTRIBUTION.csv --cache-control "public,max-age=604800"
rm -rf devops-practice-01
aws s3 ls s3://$B/products/ --recursive | grep -c '\.jpg$'
curl -sI https://$B.s3.$R.amazonaws.com/products/handwritten/iphone-15-pro/1.jpg | head -1
```
- [ ] The count says **732** and the last line says **HTTP/1.1 200 OK** (`403` = the policy or "Block public access" did not apply; do it in the console: bucket > Permissions).
- [ ] `PHOTOS_URL` for step 4 is `https://<your bucket>.s3.us-west-2.amazonaws.com` (no slash at the end).

## 4. Launch the VM when the database shows an Endpoint (EC2 > Launch instances)

| Field | Value |
|---|---|
| Name / image | `microshop-vm-1`, Amazon Linux 2023 |
| Type | `m5.large` (or `t3.medium`) |
| Network | same VPC as the database, public subnet, **Auto-assign public IP: Enable** |
| Security group | `microshop-vm-sg` |
| Storage | 20 GiB gp3 |
| Advanced details > User data | the whole file `deploy/aws/user-data.sh`, filled in as below |

Fill in your pasted copy only (start at the first character `#!`):

| Variable | Value |
|---|---|
| `PHOTOS_URL` | the bucket address from step 3, or empty |
| `DB_HOST` | the RDS endpoint |
| `DB_PASSWORD` | your DB password (letters and digits only) |
| `JWT_SECRET` | from CloudShell: `openssl rand -base64 48` (the same on every VM) |
| `ADMIN_PASSWORD` | letters and digits only |
| `EVENTS_URL` and `EVENTS_SECRET` | the Lambda function URL and its secret (step 8), or leave both empty for no events |

- [ ] VM launched (it builds for about 20 minutes). Save the filled copy as `deploy/aws/my-user-data.local.sh`.

## 5. While the VM builds: target groups, load balancer, rules

**Six target groups** (EC2 > Target Groups > Create): Instances, HTTP, same VPC, HTTP1; health check Healthy 2, Unhealthy 2, Timeout 5, Interval 15, Success 200; register `microshop-vm-1` with the port shown.

| Name | Port | Health check path |
|---|---|---|
| `microshop-ui-tg` | 8080 | `/healthz` |
| `microshop-product-tg` | 8081 | `/actuator/health` |
| `microshop-inventory-tg` | 8082 | `/actuator/health` |
| `microshop-user-tg` | 8083 | `/actuator/health` |
| `microshop-cart-tg` | 8084 | `/actuator/health` |
| `microshop-order-tg` | 8085 | `/actuator/health` |

**Load balancer** (EC2 > Load Balancers > Create > Application): `microshop-alb`, Internet-facing, IPv4, same VPC, **two Availability Zones**, security group `microshop-alb-sg` only, listener HTTP 80, default action Forward to `microshop-ui-tg`.

**Path rules** (the load balancer > Listeners and rules > HTTP:80 > Add rule; condition Path, action Forward to target group):

| Priority | Path | Forward to |
|---|---|---|
| 10 | `/api/products*` | `microshop-product-tg` |
| 20 | `/api/inventory*` | `microshop-inventory-tg` |
| 30 | `/api/users*` | `microshop-user-tg` |
| 40 | `/api/cart*` | `microshop-cart-tg` |
| 50 | `/api/orders*` | `microshop-order-tg` |

- [ ] Six target groups, the load balancer and five rules exist.

## 6. Check the VM (EC2 > the instance > Connect > EC2 Instance Connect)

```bash
sudo tail -f /var/log/user-data.log        # Ctrl+C to stop; it ends with: DONE: MicroShop is starting on this VM
sudo grep "ADMIN LOGIN" /var/log/user-data.log
sudo docker ps                              # 6 containers: Up
for p in 8081:product 8082:inventory 8083:user 8084:cart 8085:order; do echo "${p#*:}: $(curl -s -m 5 localhost:${p%%:*}/actuator/health | head -c 40)"; done
echo "ui: $(curl -s -m 5 localhost:8080/healthz)"
```
- [ ] Every service prints `{"status":"UP"...` and the ui prints `ok`.
- [ ] Each target group > Targets: all **healthy** (about a minute after the services are up).

## 7. Test

```bash
ALB=http://PASTE-THE-LOAD-BALANCER-DNS-NAME-HERE
curl -s $ALB/healthz
curl -s "$ALB/api/products?size=1"      # a product; with PHOTOS_URL its imageUrl starts with your bucket address
```
- [ ] Open `http://<DNS name>` in a browser: photos show, register, log in (no "session has ended"), place an order, log in as `admin@example.com` with your `ADMIN_PASSWORD`.

## 8. Optional: events with Lambda (new user, order placed, stock empty), about 10 minutes

Do it **before step 4** if you want the events from the first boot; the details are in DEPLOY-AWS.md, Step 8.
```bash
TOPIC=$(aws sns create-topic --name microshop-events --query TopicArn --output text); echo $TOPIC
aws sns subscribe --topic-arn $TOPIC --protocol email --notification-endpoint YOUR-EMAIL-ADDRESS
openssl rand -hex 24          # the EVENT_SECRET: use it in the Lambda AND as EVENTS_SECRET in the user data
```
- [ ] Click **Confirm subscription** in the e-mail from AWS.
- [ ] Lambda > Create function: `microshop-events`, Python 3.12; paste `deploy/lambda/events/handler.py` over `lambda_function.py`; **Deploy**.
- [ ] Configuration > Environment variables: `EVENT_SECRET` (the hex value) and `TOPIC_ARN` (the ARN printed above).
- [ ] Configuration > Permissions > the role > Create inline policy: allow `sns:Publish` on the topic ARN.
- [ ] Configuration > Function URL > Create, auth type **NONE**; copy the URL.
- [ ] In the user data (step 4): `EVENTS_URL` = that URL, `EVENTS_SECRET` = the same hex value.
- [ ] Test: register a user in the shop, then place an order: two e-mails arrive. (On a VM that is already running, the three containers must be recreated, or launch a new VM.)

## If something is wrong (the most common)

| Symptom | Check |
|---|---|
| No log file / nothing happens | `sudo cat /var/log/cloud-init-output.log`; the user data must start with `#!` |
| A service cannot reach the database | `microshop-db-sg` allows 5432 from `microshop-vm-sg`; the VM uses `microshop-vm-sg` and is in the database's VPC |
| Services restart / out of memory | use `m5.large` or `t3.medium`; `free -m` |
| "Your session has ended" right after login | not all VMs have the same `JWT_SECRET` |
| Photos missing | open a photo address in the browser: 403 = bucket policy, 404 = files not at `products/<folder>/<n>.jpg`, local `/photos/...` address = `PHOTOS_URL` was empty (see DEPLOY-AWS.md, Step 1c e) |
| A target group says unhealthy | `sudo docker ps` and `sudo docker logs <name>-service` on the VM |

Everything is deleted at the end of the session. To start again, repeat from step 1 (and step 0 if the code changed).
