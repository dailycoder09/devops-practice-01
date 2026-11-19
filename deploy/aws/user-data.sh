#!/bin/bash
# EC2 user data for Amazon Linux 2023. Paste this into "Advanced details > User data" when launching
# each web instance. It installs Docker, builds the three apps from GitHub and starts them.
#
# Containers use host networking, so each app listens on its own port on the instance and the
# services reach each other on localhost (product-service -> localhost:8082 and the other way round).
# No service URLs need to be configured.
#
#   ui-app             8080   (target group health check: /healthz)
#   product-service    8081   (health check: /actuator/health)
#   inventory-service  8082   (health check: /actuator/health)
#
# Follow the build with:  sudo tail -f /var/log/user-data.log
set -euo pipefail
exec > /var/log/user-data.log 2>&1

# Where the product photos are served from. "/photos" means the ui-app container itself (the photos are
# baked into its image, so no S3 bucket is needed). Use a full address such as
#   https://my-photos-bucket.s3.us-east-1.amazonaws.com
# to serve them from S3 instead, or leave it empty to keep the placeholder images.
IMAGE_BASE_URL="/photos"

echo "===== USER DATA STARTED ====="

# Maven and npm builds need more memory than a small instance has: add 2 GB of swap.
if ! swapon --show | grep -q /swapfile; then
  dd if=/dev/zero of=/swapfile bs=1M count=2048
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo "Swap enabled"
fi

dnf update -y
dnf install -y git docker

systemctl enable docker
systemctl start docker
echo "Docker started"

usermod -aG docker ec2-user

cd /home/ec2-user
git clone https://github.com/dailycoder09/devops-practice-01.git
cd devops-practice-01
echo "Repository cloned"

build_and_run() {
  local name="$1" dir="$2"
  shift 2
  echo "===== $name ====="
  docker build -t "$name:1.0" "$dir"
  docker rm -f "$name-container" >/dev/null 2>&1 || true
  docker run -d \
    --name "$name-container" \
    --restart unless-stopped \
    --network host \
    "$@" \
    "$name:1.0"
  echo "$name started"
}

build_and_run product-service   product-service -e "IMAGE_BASE_URL=$IMAGE_BASE_URL"
build_and_run inventory-service inventory-service
build_and_run ui-app            ui-app

echo "===== DOCKER IMAGES ====="
docker images
echo "===== DOCKER CONTAINERS ====="
docker ps -a
echo "===== USER DATA COMPLETED ====="
