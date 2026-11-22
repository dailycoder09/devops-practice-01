#!/bin/bash
# DropLink on one EC2 instance (Amazon Linux 2023). Paste this file into: EC2 > Launch instance > Advanced details > User data.
# First boot: installs Java 17, downloads the jar from your S3 bucket, writes the settings and starts DropLink as a service.
# The instance needs the IAM instance profile from docs/AWS-SETUP.md (Step 4): it lets it read the jar and use S3, DynamoDB and SNS.
# Watch it:  sudo tail -f /var/log/user-data.log        After it: sudo journalctl -u droplink -f
# Fill in the values ONLY in the copy you paste into the console, never in the file on GitHub.
# (Terraform sets the same six names before this file, so the lines below keep the value that is already set.)
# Use only letters and digits in SECRET (make one:  openssl rand -hex 32). No spaces, quotes or $ in any value.
set -eo pipefail
touch /var/log/user-data.log && chmod 600 /var/log/user-data.log   # root only
exec > >(tee -a /var/log/user-data.log) 2>&1

BUCKET="${BUCKET:-}"                      # the private S3 bucket, for example droplink-yourname-12345
TABLE="${TABLE:-}"                        # the DynamoDB table name, for example droplink
TOPIC_ARN="${TOPIC_ARN:-}"                # optional: the SNS topic ARN (e-mail on download). Empty = no e-mail.
SECRET="${SECRET:-}"                      # the SAME value on every instance. It signs the cookie of password-protected links.
JAR="${JAR:-droplink-latest.jar}"        # the file in s3://BUCKET/releases/ (or droplink-<commit>.jar for a fixed version)
PUBLIC_URL="${PUBLIC_URL:-}"                # optional: the public address, for example http://1.2.3.4:8080 (no slash at the end)

# ---- Guards: stop early, with a clear message ----
if [ -z "$BUCKET" ] || [ -z "$TABLE" ] || [ -z "$SECRET" ]; then
  echo "STOP: BUCKET, TABLE and SECRET must be filled in at the top of the user data. Nothing was installed."
  echo "      Terminate this instance and launch a new one with the values filled in (user data runs only at first boot)."
  exit 1
fi
if [ "${#SECRET}" -lt 16 ]; then
  echo "STOP: SECRET is too short (under 16 characters). Make one with:  openssl rand -hex 32"
  exit 1
fi
for pair in "BUCKET=$BUCKET" "TABLE=$TABLE" "TOPIC_ARN=$TOPIC_ARN" "SECRET=$SECRET" "JAR=$JAR" "PUBLIC_URL=$PUBLIC_URL"; do
  case "${pair#*=}" in
    *[[:space:]\"\'\\\$\`]*) echo "STOP: the value of ${pair%%=*} has a space, quote, backslash, dollar or backtick. Use plain characters only."; exit 1;;
  esac
done

# ---- The region of this instance (IMDSv2: a token first, then the value) ----
REGION=""
TOKEN=$(curl -fsS -m 5 -X PUT -H "X-aws-ec2-metadata-token-ttl-seconds: 60" http://169.254.169.254/latest/api/token) || TOKEN=""
if [ -n "$TOKEN" ]; then
  REGION=$(curl -fsS -m 5 -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/placement/region) || REGION=""
fi
if [ -z "$REGION" ]; then
  echo "WARNING: could not read the region from the instance metadata. DROPLINK_REGION stays empty (the app then uses the instance region)."
else
  echo "Region: $REGION"
fi
AWS_REGION_ARGS=()
[ -z "$REGION" ] || AWS_REGION_ARGS=(--region "$REGION")

# ---- Java and the service user ----
dnf install -y java-17-amazon-corretto-headless
id droplink >/dev/null 2>&1 || useradd --system --no-create-home --shell /sbin/nologin droplink
install -d -m 755 -o root -g root /opt/droplink

# ---- The jar, from S3 (needs the instance profile) ----
echo "Downloading s3://$BUCKET/releases/$JAR"
if ! aws s3 cp "s3://$BUCKET/releases/$JAR" /opt/droplink/droplink.jar.part "${AWS_REGION_ARGS[@]}" --only-show-errors; then
  rm -f /opt/droplink/droplink.jar.part
  echo "STOP: cannot download s3://$BUCKET/releases/$JAR."
  echo "      Most likely: (1) the instance has no IAM instance profile (EC2 > the instance > Actions > Security > Modify IAM role),"
  echo "      (2) the role lacks s3:GetObject on releases/*, (3) the jar is not uploaded yet (run cloudshell-build.sh), or (4) the bucket name or JAR is wrong."
  exit 1
fi
[ -s /opt/droplink/droplink.jar.part ] || { echo "STOP: the downloaded jar is empty."; exit 1; }
install -m 644 -o root -g root /opt/droplink/droplink.jar.part /opt/droplink/droplink.jar
rm -f /opt/droplink/droplink.jar.part

# ---- The settings: /etc/droplink.env, mode 600, owner root (systemd reads it as root; the secret is not printed) ----
install -m 600 -o root -g root /dev/null /etc/droplink.env
{
  echo "DROPLINK_MODE=aws"
  echo "DROPLINK_BUCKET=$BUCKET"
  echo "DROPLINK_KEY_PREFIX=files/"
  echo "DROPLINK_TABLE=$TABLE"
  echo "DROPLINK_REGION=$REGION"
  echo "DROPLINK_SECRET=$SECRET"
  [ -z "$TOPIC_ARN" ] || echo "DROPLINK_TOPIC_ARN=$TOPIC_ARN"
  [ -z "$PUBLIC_URL" ] || echo "DROPLINK_PUBLIC_URL=$PUBLIC_URL"
} > /etc/droplink.env

# ---- The service ----
cat > /etc/systemd/system/droplink.service <<'EOF'
[Unit]
Description=DropLink
After=network-online.target
Wants=network-online.target

[Service]
User=droplink
EnvironmentFile=/etc/droplink.env
ExecStart=/usr/bin/java -Xms128m -Xmx512m -jar /opt/droplink/droplink.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now droplink

# ---- Wait for it: up to 90 seconds ----
echo "Waiting for http://localhost:8080/actuator/health/readiness (up to 90 s)"
END=$((SECONDS + 90))
while [ "$SECONDS" -lt "$END" ]; do
  if curl -fsS -m 3 http://localhost:8080/actuator/health/readiness 2>/dev/null | grep -q '"status":"UP"'; then
    echo "DONE: DropLink is UP on port 8080 (open http://<public IP>:8080)."
    exit 0
  fi
  sleep 2
done
echo "FAILED: DropLink did not answer UP within 90 seconds."
echo "        Read the log:  sudo journalctl -u droplink --no-pager | tail -50"
exit 1
