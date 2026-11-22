#!/bin/bash
# DropLink: build the jar in AWS CloudShell and upload it to your S3 bucket.
# Run it in CloudShell (same region as the bucket):   BUCKET=your-bucket-name bash droplink/deploy/cloudshell-build.sh
# Only build the jar to a file (for terraform):        OUT=$PWD/droplink.jar bash ../deploy/cloudshell-build.sh
# It installs Maven 3.9.6 into $HOME/tools if missing, clones the repo (shallow) into a temporary folder,
# builds droplink/target/droplink-1.0.0.jar and copies it to
#   s3://BUCKET/releases/droplink-<short commit>.jar   and   s3://BUCKET/releases/droplink-latest.jar
# Nothing secret is used or printed here. The temporary clone is removed at the end (also after an error).
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/dailycoder09/devops-practice-01.git}"   # must be a public repo
BUCKET="${BUCKET:-}"          # the DropLink bucket (required, unless OUT is set). Set it here or on the command line.
OUT="${OUT:-}"                # optional: only build, and copy the jar to this file (used by terraform/). No bucket, no upload.
MAVEN_VERSION="3.9.6"
MAVEN_URL="https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
MAVEN_MIN_BYTES=5000000       # the real archive is about 9 MB; anything under 5 MB is a failed or partial download
TOOLS_DIR="$HOME/tools"

if [ -z "$BUCKET" ] && [ -z "$OUT" ]; then
  echo "STOP: BUCKET is empty. Run:  BUCKET=your-bucket-name bash $0   (or write the name at the top of this script)."
  exit 1
fi

REQUIRED="git java curl tar"
[ -n "$OUT" ] || REQUIRED="aws $REQUIRED"
for c in $REQUIRED; do
  command -v "$c" >/dev/null 2>&1 || { echo "STOP: '$c' was not found. This script is meant for AWS CloudShell."; exit 1; }
done
echo "== Java in use: $(java -version 2>&1 | head -1)"

if [ -z "$OUT" ]; then
  echo "== Checking that the bucket '$BUCKET' exists and you can reach it"
  aws s3api head-bucket --bucket "$BUCKET" >/dev/null 2>&1 || {
    echo "STOP: cannot reach the bucket '$BUCKET'. Check the name, and that CloudShell is in the bucket's region."; exit 1; }
fi

# ---- Maven ----
MVN_HOME="$TOOLS_DIR/apache-maven-$MAVEN_VERSION"
if [ -x "$MVN_HOME/bin/mvn" ]; then
  echo "== Maven $MAVEN_VERSION is already in $MVN_HOME"
else
  echo "== Installing Maven $MAVEN_VERSION into $TOOLS_DIR"
  mkdir -p "$TOOLS_DIR"
  TARBALL="$TOOLS_DIR/maven-$MAVEN_VERSION.tar.gz.part"
  curl -fsSL --retry 3 -o "$TARBALL" "$MAVEN_URL" || { rm -f "$TARBALL"; echo "STOP: could not download Maven from $MAVEN_URL"; exit 1; }
  SIZE=$(wc -c < "$TARBALL" | tr -d ' ')
  if [ "$SIZE" -lt "$MAVEN_MIN_BYTES" ]; then
    rm -f "$TARBALL"; echo "STOP: the Maven download is only $SIZE bytes (expected about 9 MB). Try again."; exit 1
  fi
  tar -xzf "$TARBALL" -C "$TOOLS_DIR" || { rm -f "$TARBALL"; echo "STOP: the Maven archive could not be unpacked."; exit 1; }
  rm -f "$TARBALL"
  [ -x "$MVN_HOME/bin/mvn" ] || { echo "STOP: Maven was unpacked but $MVN_HOME/bin/mvn is missing."; exit 1; }
fi
export PATH="$MVN_HOME/bin:$PATH"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx1g}"      # CloudShell has 2 GiB of RAM

# ---- Clone ----
WORK=$(mktemp -d "$HOME/droplink-build.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
echo "== Cloning $REPO_URL (shallow) into a temporary folder"
git clone --quiet --depth 1 "$REPO_URL" "$WORK/src" || { echo "STOP: git clone failed. Is the repo public and pushed?"; exit 1; }
[ -f "$WORK/src/droplink/pom.xml" ] || { echo "STOP: there is no droplink/pom.xml in the repo. Push the droplink folder to GitHub first."; exit 1; }
COMMIT=$(git -C "$WORK/src" rev-parse --short HEAD)
echo "== Commit: $COMMIT"

# ---- Build ----
echo "== Building (the first run downloads the dependencies: a few minutes)"
(cd "$WORK/src/droplink" && mvn -q -DskipTests package) || { echo "STOP: the Maven build failed. Scroll up for the first error."; exit 1; }
JAR="$WORK/src/droplink/target/droplink-1.0.0.jar"
[ -s "$JAR" ] || { echo "STOP: the build finished but $JAR does not exist."; exit 1; }
JAR_BYTES=$(wc -c < "$JAR" | tr -d ' ')

# ---- Only a file wanted (terraform uploads it itself) ----
if [ -n "$OUT" ]; then
  cp "$JAR" "$OUT" || { echo "STOP: could not copy the jar to $OUT."; exit 1; }
  echo "DONE: the jar ($((JAR_BYTES / 1024 / 1024)) MB, commit $COMMIT) is at $OUT"
  exit 0
fi

# ---- Upload ----
VERSIONED="s3://$BUCKET/releases/droplink-$COMMIT.jar"
LATEST="s3://$BUCKET/releases/droplink-latest.jar"
echo "== Uploading"
aws s3 cp "$JAR" "$VERSIONED" --only-show-errors || { echo "STOP: the upload failed (bucket name, region, or permissions)."; exit 1; }
aws s3 cp "$JAR" "$LATEST" --only-show-errors || { echo "STOP: the upload of droplink-latest.jar failed."; exit 1; }

echo "== Jar size: $JAR_BYTES bytes (about $((JAR_BYTES / 1024 / 1024)) MB)"
echo "== Uploaded:"
echo "   $VERSIONED"
echo "   $LATEST"
echo "DONE: put  droplink-latest.jar  (or droplink-$COMMIT.jar) in JAR= of the user data."
echo "      Free space in CloudShell later:  rm -rf \$HOME/.m2   (Maven's download cache, can be a few hundred MB)"
