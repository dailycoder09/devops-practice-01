# DropLink in two regions with DNS failover (Terraform)

One command builds everything on an empty AWS account: the network, the servers, the load balancers, the storage, the data copy between regions and the DNS failover. When the sandbox is gone, run it again in a new one.

```
Route 53   droplink.<your domain> ─ PRIMARY ──→ ALB us-west-2 → Auto Scaling group, 2 x m5.large (private subnets)
                                  └ SECONDARY ─→ ALB us-east-1 → Auto Scaling group, 1 x m5.large (private subnets)  ← warm standby
Shared data:   DynamoDB global table (links)   +   two S3 buckets that copy each other (files)
```

**Not run on AWS yet.** The files pass `terraform validate`, `terraform fmt` and an offline plan test with pretend AWS answers (`terraform test`). Nobody has applied them to a real account. Expect to fix one or two things on the first real run, and send the error message.

## What it creates, per region
VPC with 2 public and 2 private subnets, internet gateway, one NAT gateway, security groups (the servers accept port 8080 only from the load balancer), a private S3 bucket (versioned, CORS, lifecycle), an Application Load Balancer, a launch template (Amazon Linux 2023, the launch script of the project), an Auto Scaling group, and an SNS topic if you give an e-mail.
Global: IAM role and instance profile for the servers (also Session Manager, to read logs), the DynamoDB global table, S3 replication in both directions, the Route 53 zone (optional), two health checks and the two failover records.

## Steps (in AWS CloudShell, region us-west-2)

1. **Push `droplink/` to GitHub** (you commit and push). CloudShell clones what is on GitHub.
2. **Get the files and Terraform:**
```bash
git clone --depth 1 https://github.com/dailycoder09/devops-practice-01.git
cd devops-practice-01/droplink/terraform
mkdir -p ~/tools && curl -fsSLo ~/tools/tf.zip https://releases.hashicorp.com/terraform/1.9.8/terraform_1.9.8_linux_amd64.zip \
  && unzip -o ~/tools/tf.zip -d ~/tools && export PATH=$HOME/tools:$PATH
terraform version        # 1.9.8
```
3. **Build the jar** (a few minutes the first time). Terraform uploads it to both buckets:
```bash
OUT=$PWD/droplink.jar bash ../deploy/cloudshell-build.sh
```
4. **Your settings:**
```bash
cp terraform.tfvars.example terraform.tfvars
nano terraform.tfvars        # domain_name, allowed_cidrs (your IP from https://checkip.amazonaws.com in YOUR browser), notify_email
```
   Do not use CloudShell's own IP for `allowed_cidrs`. `["0.0.0.0/0"]` lets everybody in: anyone could then upload files (up to 100 MB each) into your buckets. The Route 53 health checkers are allowed automatically.
5. **Build it** (10 to 20 minutes: NAT gateways, load balancers and the global table are slow):
```bash
terraform init
terraform apply
```
   Read the plan, type `yes`. With the default `create_hosted_zone = true` the output ends with four **name servers**.
6. **Point your domain to Route 53.** At your registrar (where you bought the domain) set the domain's name servers to the four from the output. A new sandbox makes a new zone with new name servers, so you repeat this each time. DNS needs a few minutes up to a few hours to spread. Until then use the two load balancer addresses from the output (`primary_alb_url`, `secondary_alb_url`); you can also ask the zone directly: `dig @<one name server> droplink.<domain>`.
7. **Open `http://droplink.<your domain>`**. Servers need about 5 minutes after the apply to become healthy. If you gave an e-mail, click the confirmation link in the **two** mails from AWS (one per region).

## Test the failover
```bash
# stop the primary region: its load balancer then has no healthy server
aws autoscaling update-auto-scaling-group --region us-west-2 --auto-scaling-group-name droplink-tf-primary --min-size 0 --desired-capacity 0
```
After about 1 to 2 minutes (two failed health checks of 10 s, then DNS caching) the domain answers from us-east-1. Links made before still work, because the table and the files are copied. Bring it back: `terraform apply` (it sets the capacity again).

## Know before you rely on it
- **Copies are asynchronous.** A file uploaded just before a failover can be missing in the other region for a short time (usually seconds, sometimes minutes). DynamoDB usually copies within a second.
- **Download limits** (one-time links) are counted per region. In normal use all traffic goes to one region, so it holds. During a failover the count can be off by a few.
- **The five-wrong-passwords limit** is counted per server.
- **HTTP only.** No certificate yet (browsers show "not secure"). HTTPS needs an ACM certificate per region and a listener on port 443: the next step once the domain works.
- **One NAT gateway per region.** If its zone fails, the servers in the other zone lose their way out until it is back.
- **The secret** is made by Terraform and sits in the Terraform state and in the launch template. Fine for a lab. For anything real use Secrets Manager.
- **The state file** (`terraform.tfstate`) stays in CloudShell, next to the files. Keep it until `terraform destroy`. Losing it leaves resources you must delete by hand.
- **Sandbox limits are not known**: Route 53, DynamoDB global tables (they need a service-linked role) or S3 replication may be blocked. The error message will say which.
- **Cost** (outside a sandbox): 2 NAT gateways, 2 load balancers and 3 m5.large servers cost roughly 8 to 9 USD per day. Destroy when done.

## New jar, new servers
Build the jar again (step 3) and run `terraform apply`. The jar name holds a hash of the file, so the servers are replaced a few at a time.

## Remove everything
```bash
terraform destroy
```
(Also remove the name servers at your registrar if you do not need the domain pointed here.)

## Check without AWS
```bash
terraform init -backend=false && terraform validate && terraform test
```
