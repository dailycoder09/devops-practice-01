#!/bin/bash
# MicroShop on a VM (Amazon Linux 2023), the quick way: no git, no Maven, no building on the VM.
# The six images were built on your computer (deploy/local/build-images.sh) and uploaded to your S3 bucket.
# This script downloads that one file, loads the images and runs them.
# Paste this file into: EC2 > Launch instance > Advanced details > User data.   Watch it:  sudo tail -f /var/log/user-data.log
# Fill in the values ONLY in the copy you paste into the console, never in the file on GitHub.
# Use only letters and digits in DB_PASSWORD and ADMIN_PASSWORD (they sit inside double quotes below).
set -eo pipefail
touch /var/log/user-data.log && chmod 600 /var/log/user-data.log   # the log holds the admin password: root only
exec > >(tee -a /var/log/user-data.log) 2>&1

IMAGES_URL=""        # https://<bucket>.s3.us-west-2.amazonaws.com/images/microshop-images-<commit>.tar.gz
PHOTOS_URL=""        # https://<bucket>.s3.us-west-2.amazonaws.com  = product photos from S3 (used on a NEW database); empty = /photos
ADMIN_EMAIL="admin@example.com"
DB_HOST=""           # RDS endpoint. Empty = no RDS: every service keeps its own H2 file (run ONE VM only).
DB_NAME="microshop"
DB_USER="microshop"
DB_PASSWORD=""       # the RDS master password
JWT_SECRET=""        # with RDS: the SAME value on every VM. Make one:  openssl rand -base64 48
ADMIN_PASSWORD=""    # with RDS: the SAME value on every VM

[ -n "$IMAGES_URL" ] || { echo "STOP: IMAGES_URL is empty. Upload the image file to S3 and write its address here."; exit 1; }
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

# Download the image file from S3 and load it (/var/tmp is on the disk; /tmp is memory on this image).
curl -fsS --retry 5 --retry-delay 5 -o /var/tmp/microshop-images.tar.gz "$IMAGES_URL" || {
  echo "STOP: cannot download $IMAGES_URL . Is the file in the bucket, is the address right, and is the folder images/ public (DEPLOY-AWS.md, Step 1c)?"; exit 1; }
docker load -i /var/tmp/microshop-images.tar.gz
rm -f /var/tmp/microshop-images.tar.gz
for img in product-service inventory-service user-service cart-service order-service ui-app; do
  docker image inspect "localhost/microshop/$img:aws" > /dev/null || { echo "STOP: the image file does not contain localhost/microshop/$img:aws"; exit 1; }
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
    -e JAVA_OPTS="-Xms128m -Xmx320m" -e "IMAGE_BASE_URL=${PHOTOS_URL:-/photos}" \
    "localhost/microshop/$s-service:aws"
done

docker run -d --name ui-app --restart unless-stopped --network host "localhost/microshop/ui-app:aws"
echo "DONE: MicroShop is starting on this VM (the Java services need about a minute)."
