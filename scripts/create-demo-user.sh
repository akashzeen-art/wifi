#!/bin/bash
# Create (or refresh) demo user with active plan + license on a running server.
# Usage: ./scripts/create-demo-user.sh [BASE_URL]
# Example: ./scripts/create-demo-user.sh https://wifi.vault-x.world
set -euo pipefail

BASE="${1:-http://127.0.0.1:8080}"
EMAIL="demo@wifiextender.com"
PASS="demo123"
NAME="Demo User"

echo "=== Creating demo user at ${BASE} ==="

# Register (ignore if already exists)
REG=$(curl -s -w "\n%{http_code}" -X POST "${BASE}/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"${NAME}\",\"email\":\"${EMAIL}\",\"password\":\"${PASS}\"}")
REG_BODY=$(echo "$REG" | sed '$d')
REG_CODE=$(echo "$REG" | tail -n1)

if [[ "$REG_CODE" == "200" || "$REG_CODE" == "201" ]]; then
  TOKEN=$(echo "$REG_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null || true)
  echo "Registered new user"
else
  echo "Register returned ${REG_CODE} — trying login..."
  LOGIN=$(curl -s -X POST "${BASE}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASS}\"}")
  TOKEN=$(echo "$LOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null || true)
fi

if [[ -z "${TOKEN:-}" ]]; then
  echo "ERROR: could not get access token. Check email/password or API URL."
  exit 1
fi

# Pick Premium plan (fallback Basic / first plan)
PLANS=$(curl -s "${BASE}/api/subscriptions/plans" -H "Authorization: Bearer ${TOKEN}")
PLAN_ID=$(echo "$PLANS" | python3 - <<'PY'
import sys, json
plans = json.load(sys.stdin)
prefer = ["Premium", "Basic", "Starter", "Free Trial"]
by_name = {p.get("name",""): p.get("id") for p in plans}
for name in prefer:
    if name in by_name:
        print(by_name[name]); break
else:
    print(plans[0]["id"] if plans else "")
PY
)

if [[ -z "$PLAN_ID" ]]; then
  echo "ERROR: no plans found"
  exit 1
fi

echo "Requesting plan id=${PLAN_ID}..."
curl -s -X POST "${BASE}/api/subscriptions/request/${PLAN_ID}" \
  -H "Authorization: Bearer ${TOKEN}" | python3 -m json.tool 2>/dev/null || true

LICENSES=$(curl -s "${BASE}/api/subscriptions/licenses" -H "Authorization: Bearer ${TOKEN}")
LICENSE_KEY=$(echo "$LICENSES" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0].get('licenseKey','') if d else '')" 2>/dev/null || true)

echo ""
echo "========================================"
echo "  DEMO CREDENTIALS (share with tester)"
echo "========================================"
echo "  Web / Android login:"
echo "    Email:    ${EMAIL}"
echo "    Password: ${PASS}"
echo "  API base:   ${BASE}/api/"
echo "  License:    ${LICENSE_KEY:-'(check Subscription → Licenses in app)'}"
echo "========================================"
