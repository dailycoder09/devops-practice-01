#!/bin/bash
# Installs Terraform in AWS CloudShell.  Run:  bash install-terraform.sh
# It goes into ~/bin, because CloudShell keeps only your home folder between sessions.
set -euo pipefail

VERSION=""      # empty = the latest release; or write a number such as 1.9.8
[ -n "$VERSION" ] || VERSION=$(curl -fsSL https://checkpoint-api.hashicorp.com/v1/check/terraform | python3 -c 'import sys,json; print(json.load(sys.stdin)["current_version"])')
case "$(uname -m)" in
  x86_64) ARCH=amd64 ;;
  aarch64|arm64) ARCH=arm64 ;;
  *) echo "Unknown CPU type: $(uname -m)"; exit 1 ;;
esac
ZIP="terraform_${VERSION}_linux_${ARCH}.zip"
BASE="https://releases.hashicorp.com/terraform/${VERSION}"

command -v unzip >/dev/null || sudo dnf install -y unzip
TMP=$(mktemp -d)
cd "$TMP"
curl -fsSLO "$BASE/$ZIP"
curl -fsSLO "$BASE/terraform_${VERSION}_SHA256SUMS"
grep " $ZIP\$" "terraform_${VERSION}_SHA256SUMS" | sha256sum -c -     # stops here if the download is damaged

mkdir -p "$HOME/bin"
unzip -oq "$ZIP" terraform -d "$HOME/bin"
cd "$HOME"
rm -rf -- "$TMP"

grep -qs 'HOME/bin' "$HOME/.bashrc" || echo 'export PATH="$HOME/bin:$PATH"' >> "$HOME/.bashrc"
"$HOME/bin/terraform" version
echo "Done: Terraform $VERSION is in ~/bin. In this tab run:  source ~/.bashrc    (new tabs already have it)"
