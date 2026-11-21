#!/bin/bash
# MicroShop on a VM (Amazon Linux 2023), the quick way: no git, no Maven, no building on the VM.
# It pulls the six ready-made images from Amazon ECR (built once by CodeBuild, see DEPLOY-AWS.md) and runs them.
# The VM needs an IAM instance profile that lets it pull from ECR (AmazonEC2ContainerRegistryReadOnly).
# Paste this file into: EC2 > Launch instance > Advanced details > User data.   Watch it:  sudo tail -f /var/log/user-data.log
# Fill in the secret values ONLY in the copy you paste into the console, never in the file on GitHub.
# Use only letters and digits in DB_PASSWORD and ADMIN_PASSWORD (they sit inside double quotes below).
set -eo pipefail
touch /var/log/user-data.log && chmod 600 /var/log/user-data.log   # the log holds the admin password: root only
exec > >(tee -a /var/log/user-data.log) 2>&1

IMAGE_TAG="latest"   # or a commit tag from ECR (for example 3f9a1c2e) to pin a version or roll back
ADMIN_EMAIL="admin@example.com"
DB_HOST=""           # RDS endpoint. Empty = no RDS: every service keeps its own H2 file (run ONE VM only).
DB_NAME="microshop"
DB_USER="microshop"
DB_PASSWORD=""       # the RDS master password
JWT_SECRET=""        # with RDS: the SAME value on every VM. Make one:  openssl rand -base64 48
ADMIN_PASSWORD=""    # with RDS: the SAME value on every VM

if [ -n "$DB_HOST" ]; then
  [ -n "$DB_PASSWORD" ] && [ -n "$JWT_SECRET" ] && [ -n "$ADMIN_PASSWORD" ] || {
    echo "STOP: with DB_HOST set, DB_PASSWORD, JWT_SECRET and ADMIN_PASSWORD must be filled in (the same on every VM)."; exit 1; }
fi
[ -n "$JWT_SECRET" ] || JWT_SECRET=$(head -c 48 /dev/urandom | base64 | tr -d '\n')
[ -n "$ADMIN_PASSWORD" ] || ADMIN_PASSWORD=$(head -c 24 /dev/urandom | base64 | tr -d '+/=\n' | cut -c1-20)
echo "ADMIN LOGIN: $ADMIN_EMAIL / $ADMIN_PASSWORD" >> /var/log/user-data.log   # only into the root-only file, not onto the console output

dnf install -y docker
systemctl enable --now docker
usermod -aG docker ec2-user      # ec2-user can run docker without sudo (after the next login)

# Where are the images? In this account and region, in ECR (the VM learns both by itself).
TOKEN=$(curl -sf --max-time 5 --retry 5 -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 300")
REGION=$(curl -sf --max-time 5 --retry 5 -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/placement/region)
[ -n "$REGION" ] || { echo "STOP: cannot read the region from the instance metadata."; exit 1; }
ACCOUNT=$(aws sts get-caller-identity --query Account --output text --region "$REGION")
REGISTRY="$ACCOUNT.dkr.ecr.$REGION.amazonaws.com"
echo "Pulling from $REGISTRY/microshop/*:$IMAGE_TAG"
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"

# Pull all six first; if the CodeBuild build is still running, wait (up to 15 minutes per image) instead of half starting.
for img in product-service inventory-service user-service cart-service order-service ui-app; do
  n=0
  until docker pull "$REGISTRY/microshop/$img:$IMAGE_TAG"; do
    n=$((n+1))
    [ "$n" -lt 30 ] || { echo "STOP: cannot pull microshop/$img:$IMAGE_TAG. Has the CodeBuild build succeeded? Is the tag right?"; exit 1; }
    echo "Waiting for microshop/$img:$IMAGE_TAG ... ($n/30)"; sleep 30
  done
done

for s in product inventory user cart order; do
  if [ -n "$DB_HOST" ]; then      # shared PostgreSQL: one database, one schema per service (created on first start)
    DB_ENV=(-e SPRING_PROFILES_ACTIVE=prod,postgres -e "DB_URL=jdbc:postgresql://$DB_HOST:5432/$DB_NAME?sslmode=require"
            -e "DB_USERNAME=$DB_USER" -e "DB_PASSWORD=$DB_PASSWORD" -e "DB_SCHEMA=${s}_svc")
  else                            # no RDS: a small H2 file inside the container
    DB_ENV=(-e SPRING_PROFILES_ACTIVE=prod -e "DB_URL=jdbc:h2:file:/app/data/${s}db;AUTO_SERVER=TRUE")
  fi
  ADMIN_ENV=()
  [ "$s" = user ] && ADMIN_ENV=(-e "ADMIN_EMAIL=$ADMIN_EMAIL" -e "ADMIN_PASSWORD=$ADMIN_PASSWORD")
  docker run -d --name "$s-service" --restart unless-stopped --network host \
    --log-opt max-size=10m --log-opt max-file=3 \
    "${DB_ENV[@]}" "${ADMIN_ENV[@]}" -e JWT_SECRET="$JWT_SECRET" \
    -e JAVA_OPTS="-Xms128m -Xmx320m" -e IMAGE_BASE_URL=/photos \
    "$REGISTRY/microshop/$s-service:$IMAGE_TAG"
done

docker run -d --name ui-app --restart unless-stopped --network host "$REGISTRY/microshop/ui-app:$IMAGE_TAG"
echo "DONE: MicroShop is starting on this VM (the Java services need about a minute)."
