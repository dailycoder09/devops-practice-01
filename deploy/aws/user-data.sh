#!/bin/bash
# MicroShop on a VM (Amazon Linux 2023). Paste this file into: EC2 > Launch instance > Advanced details > User data.
# First boot: installs git + docker, pulls the code from GitHub, builds one container per service and runs them.
# Ports on the VM: 8080 UI, 8081 product, 8082 inventory, 8083 user, 8084 cart, 8085 order.
# Watch it:  sudo tail -f /var/log/user-data.log
# Fill in the secret values ONLY in the copy you paste into the console, never in the file on GitHub.
# Use only letters and digits in DB_PASSWORD and ADMIN_PASSWORD (they sit inside double quotes below).
set -eo pipefail
touch /var/log/user-data.log && chmod 600 /var/log/user-data.log   # the log holds the admin password: root only
exec > >(tee -a /var/log/user-data.log) 2>&1

REPO_URL="https://github.com/dailycoder09/devops-practice-01.git"   # must be a public repo
ADMIN_EMAIL="admin@example.com"
PHOTOS_URL=""        # https://<bucket>.s3.us-west-2.amazonaws.com = product photos from S3 (used on a NEW database); empty = photos inside the ui image
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

dnf install -y git docker
systemctl enable --now docker
usermod -aG docker ec2-user      # ec2-user can run docker without sudo (after the next login)
git clone --depth 1 "$REPO_URL" /opt/microshop
cd /opt/microshop

for s in product inventory user cart order; do
  if [ -n "$DB_HOST" ]; then      # shared PostgreSQL: one database, one schema per service (created on first start)
    DB_ENV=(-e SPRING_PROFILES_ACTIVE=prod,postgres -e "DB_URL=jdbc:postgresql://$DB_HOST:5432/$DB_NAME?sslmode=require"
            -e "DB_USERNAME=$DB_USER" -e "DB_PASSWORD=$DB_PASSWORD" -e "DB_SCHEMA=${s}_svc")
  else                            # no RDS: a small H2 file inside the container
    DB_ENV=(-e SPRING_PROFILES_ACTIVE=prod -e "DB_URL=jdbc:h2:file:/app/data/${s}db;AUTO_SERVER=TRUE")
  fi
  ADMIN_ENV=()
  [ "$s" = user ] && ADMIN_ENV=(-e "ADMIN_EMAIL=$ADMIN_EMAIL" -e "ADMIN_PASSWORD=$ADMIN_PASSWORD")
  docker build -t "$s-service" "$s-service"
  docker run -d --name "$s-service" --restart unless-stopped --network host \
    --log-opt max-size=10m --log-opt max-file=3 \
    "${DB_ENV[@]}" "${ADMIN_ENV[@]}" -e JWT_SECRET="$JWT_SECRET" \
    -e JAVA_OPTS="-Xms128m -Xmx320m" -e "IMAGE_BASE_URL=${PHOTOS_URL:-/photos}" \
    "$s-service"
done

UI_ARGS=()
[ -z "$PHOTOS_URL" ] || UI_ARGS=(--build-arg WITH_PHOTOS=no)   # photos come from S3: the ui image does not need its own copy
docker build "${UI_ARGS[@]}" -t ui-app ui-app
docker run -d --name ui-app --restart unless-stopped --network host ui-app
echo "DONE: MicroShop is starting on this VM (the Java services need about a minute)."
