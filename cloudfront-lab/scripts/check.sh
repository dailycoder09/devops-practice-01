#!/bin/bash
# Shows what CloudFront says about a URL, one line per request.
#   bash scripts/check.sh URL [COUNT] [extra curl options...]
#   bash scripts/check.sh https://d1234abcd.cloudfront.net/index.html 3
#   SLEEP=5 bash scripts/check.sh https://d1234abcd.cloudfront.net/api/time?ttl=20 6
#   bash scripts/check.sh https://d1234abcd.cloudfront.net/api/time 1 -H "Accept-Language: fr"
#   BODY=1 bash scripts/check.sh https://d1234abcd.cloudfront.net/api/time?ttl=20 3     (also shows the start of the answer)
# Columns: request number, status, X-Cache (Hit/Miss/RefreshHit/Error), Age (seconds in the cache),
#          Cache-Control, Content-Encoding, the edge location (X-Amz-Cf-Pop), time in seconds.
# Needs only curl. Nothing is changed anywhere.
set -u

URL="${1:-}"
COUNT="${2:-1}"
if [ -z "$URL" ]; then
  sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
fi
shift
[ "$#" -gt 0 ] && shift
SLEEP="${SLEEP:-0}"
BODY="${BODY:-0}"
BODYFILE=$(mktemp)
trap 'rm -f "$BODYFILE"' EXIT

case "$COUNT" in ''|*[!0-9]*) echo "COUNT must be a number, got '$COUNT'"; exit 1;; esac

header() {   # header NAME  (reads $HEADERS)
  printf '%s\n' "$HEADERS" | tr -d '\r' | grep -i "^$1:" | head -1 | cut -d: -f2- | sed 's/^ *//'
}

i=1
while [ "$i" -le "$COUNT" ]; do
  RAW=$(curl -sS -o "$BODYFILE" -D - -w 'TIME:%{time_total}\n' -H 'Accept-Encoding: gzip, br' "$@" "$URL" 2>&1) || { echo "#$i  curl failed: $(printf '%s' "$RAW" | head -1)"; exit 1; }
  HEADERS=$(printf '%s\n' "$RAW" | grep -v '^TIME:')
  TIME=$(printf '%s\n' "$RAW" | grep '^TIME:' | cut -d: -f2)
  STATUS=$(printf '%s\n' "$HEADERS" | tr -d '\r' | grep '^HTTP/' | tail -1 | awk '{print $2}')
  printf '#%s  %s | X-Cache: %s | Age: %s | CC: %s | Enc: %s | POP: %s | %ss\n' \
    "$i" "${STATUS:-?}" "$(header x-cache | cut -c1-34)" "$(header age)" "$(header cache-control)" \
    "$(header content-encoding)" "$(header x-amz-cf-pop)" "$TIME"
  if [ "$BODY" = "1" ]; then printf '     body: %s\n' "$(head -c 400 "$BODYFILE" | tr '\n' ' ' | cut -c1-220)"; fi
  i=$((i + 1))
  if [ "$i" -le "$COUNT" ] && [ "$SLEEP" != "0" ]; then sleep "$SLEEP"; fi
done
