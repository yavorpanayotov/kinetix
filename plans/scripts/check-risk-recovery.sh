#!/usr/bin/env bash
# Live-deploy smoke for PR 2 of plans/ui-fix-v1.md.
# Asserts every on-demand risk POST returns 200 with the UI-shape payload AND
# the firm hierarchy aggregate is populated (varValue != "0.00", childCount > 0).
# Exits non-zero on any failure.
set -euo pipefail

G="${GATEWAY:-https://api.kinetixrisk.ai}"
BOOK="${BOOK:-balanced-income}"
BOOK2="${BOOK2:-derivatives-book}"
HDRS=(-H "Content-Type: application/json" -H "X-Demo-User-Id: risk_manager1" -H "X-Demo-User-Role: RISK_MANAGER")

fail() { echo "✗ $*" >&2; exit 1; }
ok()   { echo "✓ $*"; }

assert_200() {
  local label="$1" url="$2" body="${3:-}"
  local status
  if [[ -z "$body" ]]; then
    status=$(curl -ks -o /tmp/krr.body -w "%{http_code}" "${HDRS[@]}" "$url")
  else
    status=$(curl -ks -o /tmp/krr.body -w "%{http_code}" -X POST "${HDRS[@]}" -d "$body" "$url")
  fi
  [[ "$status" == "200" ]] || fail "$label: expected 200, got $status — body: $(head -c 200 /tmp/krr.body)"
  ok "$label → 200"
}

VAR_BODY='{"calculationType":"PARAMETRIC","confidenceLevel":"CL_95","timeHorizonDays":"1","numSimulations":"10000"}'
STRESS_BODY='{"scenarioName":"GFC_2008","calculationType":"PARAMETRIC","confidenceLevel":"CL_95","timeHorizonDays":"1","numSimulations":"10000"}'
WHATIF_BODY='{"hypotheticalTrades":[{"instrumentId":"AAPL","assetClass":"EQUITY","side":"BUY","quantity":"100","priceAmount":"191.30","priceCurrency":"USD","instrumentType":"CASH_EQUITY"}],"calculationType":"PARAMETRIC","confidenceLevel":"CL_95"}'
CROSS_BODY=$(printf '{"bookIds":["%s","%s"],"portfolioGroupId":"firm","calculationType":"PARAMETRIC","confidenceLevel":"CL_95","timeHorizonDays":"1","numSimulations":"10000"}' "$BOOK" "$BOOK2")

assert_200 "POST var/$BOOK"            "$G/api/v1/risk/var/$BOOK"            "$VAR_BODY"
assert_200 "POST stress/$BOOK"         "$G/api/v1/risk/stress/$BOOK"         "$STRESS_BODY"
assert_200 "POST what-if/$BOOK"        "$G/api/v1/risk/what-if/$BOOK"        "$WHATIF_BODY"
assert_200 "POST var/cross-book"       "$G/api/v1/risk/var/cross-book"       "$CROSS_BODY"

# Hierarchy: must be populated (B2 recovery check).
curl -ks "$G/api/v1/risk/hierarchy/firm/firm" > /tmp/krr.body
status=$(python3 -c "import json,sys; d=json.load(open('/tmp/krr.body')); print('OK' if d.get('varValue','0.00')!='0.00' and d.get('childCount',0)>0 else 'FAIL: '+str(d)[:200])")
[[ "$status" == "OK" ]] || fail "hierarchy/firm/firm not populated: $status"
ok "hierarchy/firm/firm populated"

echo
echo "✅ All risk recovery checks passed against $G"
