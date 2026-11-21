#!/bin/bash
# Raw version, no database settings: installs git + docker, clones the repo, builds and runs all six containers.
# ONE VM only: every service keeps its own small H2 file inside its container.
# Paste into: EC2 > Launch instance > Advanced details > User data.   Watch it:  sudo tail -f /var/log/user-data.log
set -eo pipefail
touch /var/log/user-data.log && chmod 600 /var/log/user-data.log
exec > >(tee -a /var/log/user-data.log) 2>&1

REPO_URL="https://github.com/dailycoder09/devops-practice-01.git"
ADMIN_EMAIL="admin@example.com"
JWT_SECRET=$(head -c 48 /dev/urandom | base64 | tr -d '\n')
ADMIN_PASSWORD=$(head -c 24 /dev/urandom | base64 | tr -d '+/=\n' | cut -c1-20)
echo "ADMIN LOGIN: $ADMIN_EMAIL / $ADMIN_PASSWORD"

dnf install -y git docker
systemctl enable --now docker
usermod -aG docker ec2-user
git clone --depth 1 "$REPO_URL" /opt/microshop
cd /opt/microshop

for s in product inventory user cart order; do
  docker build -t "$s-service" "$s-service"
  docker run -d --name "$s-service" --restart unless-stopped --network host \
    --log-opt max-size=10m --log-opt max-file=3 \
    -e SPRING_PROFILES_ACTIVE=prod -e JWT_SECRET="$JWT_SECRET" \
    -e ADMIN_EMAIL="$ADMIN_EMAIL" -e ADMIN_PASSWORD="$ADMIN_PASSWORD" \
    -e IMAGE_BASE_URL=/photos -e JAVA_OPTS="-Xms128m -Xmx320m" \
    -e DB_URL="jdbc:h2:file:/app/data/${s}db;AUTO_SERVER=TRUE" \
    "$s-service"
done

docker build -t ui-app ui-app
docker run -d --name ui-app --restart unless-stopped --network host ui-app
echo "DONE: MicroShop is starting (the Java services need about a minute)."
