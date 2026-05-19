#!/usr/bin/env bash
# Live-deploy smoke for PR 5 of plans/ui-fix-v1.md.
# Asserts the EOD timeline returns at least one entry for balanced-income
# over the last 30 days. Exits non-zero if the timeline is empty.
set -euo pipefail

G="${GATEWAY:-https://api.kinetixrisk.ai}"
BOOK="${BOOK:-balanced-income}"

if date -v-30d +%F >/dev/null 2>&1; then
  FROM=$(date -v-30d +%F)   # macOS
else
  FROM=$(date -d "30 days ago" +%F)   # GNU
fi
TO=$(date +%F)

URL="$G/api/v1/risk/eod-timeline/$BOOK?from=$FROM&to=$TO"
echo "Probing $URL"

curl -ks "$URL" > /tmp/keod.body
status=$(python3 -c "
import json, sys
d = json.load(open('/tmp/keod.body'))
n = len(d.get('entries', []))
print('OK ' + str(n) if n > 0 else 'FAIL: 0 entries — body: ' + str(d)[:300])
")
case "$status" in
  OK\ *) echo "✓ eod-timeline returned ${status#OK }entries" ;;
  *)     echo "✗ $status" >&2; exit 1 ;;
esac

echo
echo "✅ EOD recovery check passed against $G"
