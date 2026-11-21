#!/usr/bin/env bash
# Builds the six MicroShop images with Podman and saves them as ONE compressed file for the AWS VM:
#     deploy/local/out/microshop-images-<commit>.tar.gz
# The ui image is built WITHOUT the product photos (they come from S3). Upload the file to your S3 bucket under
# images/ and put its address into deploy/aws/user-data-s3.sh (DEPLOY-AWS.md, Step 1c and Step 2).
#
# Usage:   deploy/local/build-images.sh            (uses podman)
#          CONTAINER_CLI=docker deploy/local/build-images.sh
# The images are built from this folder as it is now, so commit first: the file name then shows the commit
# ("-dirty" at the end means there were changes that are not committed).
set -euo pipefail

CLI="${CONTAINER_CLI:-podman}"
TAG="aws"
cd "$(dirname "$0")/../.."

if ! command -v "$CLI" >/dev/null 2>&1; then echo "$CLI was not found on your PATH."; exit 1; fi
if ! "$CLI" info >/dev/null 2>&1; then
  echo "$CLI is installed but cannot reach its engine."
  [ "$CLI" = "podman" ] && echo "Start the Podman machine first:  podman machine start"
  exit 1
fi

# A leftover Docker config can name credential helpers that fail without a terminal and stop every image pull.
if [ "$CLI" = "podman" ] && [ -z "${REGISTRY_AUTH_FILE:-}" ]; then
  AUTH_FILE="$(mktemp)"; echo '{}' > "$AUTH_FILE"
  trap 'rm -f "$AUTH_FILE"' EXIT
  export REGISTRY_AUTH_FILE="$AUTH_FILE"
fi

FORMAT=""; [ "$CLI" = "podman" ] && FORMAT="--format docker"
COMMIT="$(git rev-parse --short=8 HEAD 2>/dev/null || echo nogit)"
[ -z "$(git status --porcelain 2>/dev/null)" ] || COMMIT="$COMMIT-dirty"
OUT="deploy/local/out"; FILE="$OUT/microshop-images-$COMMIT.tar.gz"
mkdir -p "$OUT"

# linux/amd64 is what the EC2 types t3 and m5 run. (ARM / Graviton VMs would need linux/arm64.)
# The five Java builds run at the same time (each downloads its own Maven dependencies; one after another takes
# about four times longer). The ui is built after them, because six builds at once can run the Podman machine
# out of memory ("cannot allocate memory"). A build that fails is tried once more, alone.
LOGS="$OUT/logs"; mkdir -p "$LOGS"
SERVICES=(product-service inventory-service user-service cart-service order-service)
build_one() {   # name, extra build args...
  local name="$1"; shift
  "$CLI" build $FORMAT --platform linux/amd64 "$@" -t "microshop/$name:$TAG" "$name" > "$LOGS/$name.log" 2>&1
}
echo "==> Building the five services at the same time (about 6 to 15 minutes). Logs: $LOGS/<name>.log"
PIDS=()
for s in "${SERVICES[@]}"; do build_one "$s" & PIDS+=($!); done
RETRY=()
for i in "${!PIDS[@]}"; do
  if wait "${PIDS[$i]}"; then echo "    built: ${SERVICES[$i]}"; else echo "    failed, will retry alone: ${SERVICES[$i]}"; RETRY+=("${SERVICES[$i]}"); fi
done
for s in "${RETRY[@]+"${RETRY[@]}"}"; do
  if build_one "$s"; then echo "    built (second try): $s"; else echo "    FAILED: $s (see $LOGS/$s.log)"; exit 1; fi
done
echo "==> Building the ui WITHOUT the photos (they come from S3)"
if build_one ui-app --build-arg WITH_PHOTOS=no; then echo "    built: ui-app"; else echo "    FAILED: ui-app (see $LOGS/ui-app.log)"; exit 1; fi

echo "==> Saving all six images into one file (shared layers are stored once)"
IMAGES=(); for s in product-service inventory-service user-service cart-service order-service ui-app; do IMAGES+=("microshop/$s:$TAG"); done
if [ "$CLI" = "podman" ]; then
  "$CLI" save --multi-image-archive --format docker-archive "${IMAGES[@]}" | gzip > "$FILE"
else
  "$CLI" save "${IMAGES[@]}" | gzip > "$FILE"
fi

echo
echo "Done: $FILE ($(du -h "$FILE" | cut -f1))"
echo "Next: S3 console > your bucket > Upload this file into the folder images/, then put its address into user-data-s3.sh:"
echo "  IMAGES_URL=\"https://<your-bucket>.s3.us-west-2.amazonaws.com/images/$(basename "$FILE")\""
