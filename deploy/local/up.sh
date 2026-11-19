#!/usr/bin/env bash
# Runs the whole shop locally in containers, shaped like the AWS setup:
#   two "VMs" (a and b), each with product-service, inventory-service and ui-app,
#   plus ONE user-service, ONE cart-service and ONE order-service container (see README:
#   two copies with separate H2 files would make a login, a cart or an order fail at
#   random), behind a gateway container that applies the same path rules as the ALB.
#
# Usage:   deploy/local/up.sh            (uses podman)
#          CONTAINER_CLI=docker deploy/local/up.sh
#          GATEWAY_PORT=9000 deploy/local/up.sh     (default port is 8000)
#          deploy/local/up.sh --build-only          (build the seven images, start nothing)
set -euo pipefail

CLI="${CONTAINER_CLI:-podman}"
PORT="${GATEWAY_PORT:-8000}"
NET="shop-net"
# Seven JVMs (product x2, inventory x2, user, cart, order) share one container machine, so keep each one small.
JAVA_LOCAL="-Xms128m -Xmx320m -XX:+UseG1GC"

cd "$(dirname "$0")/../.."

if ! command -v "$CLI" >/dev/null 2>&1; then
  echo "$CLI was not found on your PATH."
  exit 1
fi

if ! "$CLI" info >/dev/null 2>&1; then
  echo "$CLI is installed but cannot reach its engine."
  if [ "$CLI" = "podman" ]; then
    echo "Start the Podman machine first:  podman machine start"
    echo "(first time:  podman machine init --cpus 4 --memory 6144 && podman machine start)"
  fi
  exit 1
fi

# A leftover Docker config (~/.docker/config.json) can name credential helpers (for example gcloud)
# that fail without a terminal and stop every image pull. All images here are public, so podman
# gets an empty credentials file instead. Set REGISTRY_AUTH_FILE yourself to use real credentials.
if [ "$CLI" = "podman" ] && [ -z "${REGISTRY_AUTH_FILE:-}" ]; then
  AUTH_FILE="$(mktemp)"
  echo '{}' > "$AUTH_FILE"
  trap 'rm -f "$AUTH_FILE"' EXIT
  export REGISTRY_AUTH_FILE="$AUTH_FILE"
fi

# podman only keeps HEALTHCHECK instructions when the image is built in docker format.
BUILD_FORMAT=""
if [ "$CLI" = "podman" ]; then
  BUILD_FORMAT="--format docker"
fi

# Behind a company proxy (HTTPS_PROXY set), the build steps need it too. It is passed in explicitly;
# the Maven Dockerfiles turn it into a Maven proxy setting, npm and apk read it directly.
PROXY_URL="${HTTPS_PROXY:-${https_proxy:-${HTTP_PROXY:-${http_proxy:-}}}}"

build_image() {
  local tag="$1" context="$2"
  if [ -n "$PROXY_URL" ]; then
    "$CLI" build $BUILD_FORMAT \
      --build-arg "HTTP_PROXY=$PROXY_URL" --build-arg "HTTPS_PROXY=$PROXY_URL" \
      -t "$tag" "$context"
  else
    "$CLI" build $BUILD_FORMAT -t "$tag" "$context"
  fi
}

echo "==> Building images (the first build downloads Maven and npm dependencies and takes a few minutes)"
build_image product-service:local product-service
build_image inventory-service:local inventory-service
build_image user-service:local user-service
build_image cart-service:local cart-service
build_image order-service:local order-service
build_image ui-app:local ui-app
build_image shop-gateway:local deploy/local/gateway

if [ "${1:-}" = "--build-only" ]; then
  echo
  echo "Images built:"
  "$CLI" images --format 'table {{.Repository}}\t{{.Tag}}\t{{.Size}}' | grep -E "REPOSITORY|product-service|inventory-service|user-service|cart-service|order-service|ui-app|shop-gateway"
  echo
  echo "Start them later with:  deploy/local/up.sh"
  exit 0
fi

# The running containers must not inherit the proxy: the health checks call localhost.
RUN_PROXY_FLAG=""
if [ "$CLI" = "podman" ]; then
  RUN_PROXY_FLAG="--http-proxy=false"
fi

echo "==> Preparing network and removing old containers"
"$CLI" network inspect "$NET" >/dev/null 2>&1 || "$CLI" network create "$NET" >/dev/null
for name in gateway product-a product-b inventory-a inventory-b user cart order ui-a ui-b; do
  "$CLI" rm -f "$name" >/dev/null 2>&1 || true
done

echo "==> Starting the two simulated VMs"
for side in a b; do
  # The services call each other through the gateway, like they would through the ALB DNS name.
  "$CLI" run -d $RUN_PROXY_FLAG --name "product-$side" --hostname "vm-$side" --network "$NET" \
    -e JAVA_OPTS="$JAVA_LOCAL" -e INVENTORY_SERVICE_URL=http://gateway:8080 \
    product-service:local >/dev/null
  "$CLI" run -d $RUN_PROXY_FLAG --name "inventory-$side" --hostname "vm-$side" --network "$NET" \
    -e JAVA_OPTS="$JAVA_LOCAL" -e PRODUCT_SERVICE_URL=http://gateway:8080 \
    inventory-service:local >/dev/null
  "$CLI" run -d $RUN_PROXY_FLAG --name "ui-$side" --hostname "vm-$side" --network "$NET" \
    ui-app:local >/dev/null
done

# One user container only (hostname vm-a). Each container has its own H2 file, so a second copy would not know
# the accounts of the first and a login would fail at random. It is started before the gateway, which needs its name.
echo "==> Starting the user service (a single container)"
"$CLI" run -d $RUN_PROXY_FLAG --name user --hostname vm-a --network "$NET" \
  -e JAVA_OPTS="$JAVA_LOCAL" \
  user-service:local >/dev/null

# One cart container and one order container only (hostname vm-a), for the same reason as the user container:
# each container has its own H2 file, so a second cart would hold other items and a second order service would
# know other orders. Like the other services they reach each other through the gateway, the way they would
# through the ALB DNS name: the cart asks product-service, the order service asks the cart, product-service
# and inventory-service. Both are started before the gateway, which needs their names.
# Exception: the order service talks to inventory-a directly, not through the gateway. A reserve and its release
# must reach the same stock database, and inventory-a and inventory-b each have their own H2 file. The cost: stock
# shown through the gateway can come from inventory-b, which orders never change. A shared database (RDS) fixes this.
echo "==> Starting the cart service and the order service (a single container each)"
"$CLI" run -d $RUN_PROXY_FLAG --name cart --hostname vm-a --network "$NET" \
  -e JAVA_OPTS="$JAVA_LOCAL" -e PRODUCT_SERVICE_URL=http://gateway:8080 \
  cart-service:local >/dev/null
"$CLI" run -d $RUN_PROXY_FLAG --name order --hostname vm-a --network "$NET" \
  -e JAVA_OPTS="$JAVA_LOCAL" -e PRODUCT_SERVICE_URL=http://gateway:8080 \
  -e INVENTORY_SERVICE_URL=http://inventory-a:8082 -e CART_SERVICE_URL=http://gateway:8080 \
  order-service:local >/dev/null

# The gateway resolves the backend names when it starts, so it goes up last.
echo "==> Starting the gateway on port $PORT"
"$CLI" run -d $RUN_PROXY_FLAG --name gateway --network "$NET" -p "$PORT:8080" shop-gateway:local >/dev/null

wait_for() {
  local name="$1" pattern="$2"
  for _ in $(seq 1 90); do
    if "$CLI" logs "$name" 2>&1 | grep -q "$pattern"; then
      return 0
    fi
    sleep 2
  done
  echo "$name did not become ready. Look at its log:  $CLI logs $name"
  return 1
}

echo "==> Waiting for the Spring Boot apps (about 20-40 seconds each)"
wait_for product-a "Started ProductServiceApplication"
wait_for product-b "Started ProductServiceApplication"
wait_for inventory-a "Started InventoryServiceApplication"
wait_for inventory-b "Started InventoryServiceApplication"
wait_for user "Started UserServiceApplication"
wait_for cart "Started CartServiceApplication"
wait_for order "Started OrderServiceApplication"

echo
echo "Ready:  http://localhost:$PORT"
echo
echo "Try it:"
echo "  - Open the dashboard and press Refresh: the server name alternates between vm-a and vm-b."
echo "  - 'Send 20 requests' shows the split between the two VMs."
echo "  - Simulate a VM failing:   $CLI stop product-b"
echo "    Bring it back:           $CLI start product-b   (then:  $CLI restart gateway)"
echo "  - Stock you change on one VM is not visible on the other: each has its own H2 file."
echo "  - Log in on the Login page. The dev admin is admin@example.com / admin12345 (local placeholders, never for AWS)."
echo "    There is a single user container, so a login works whichever VM answers the other requests."
echo "  - Log in, add products to the cart and place an order. There is a single cart and a single order container too."
echo "    The two inventory containers keep separate stock files, so an order may reserve stock on one VM and a"
echo "    release may reach the other: see the README (this goes away once the VMs share a database)."
echo
echo "Stop everything:  deploy/local/down.sh"
