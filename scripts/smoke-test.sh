#!/usr/bin/env bash
# End-to-end smoke test against the running Docker Compose stack (used by CI, also handy locally):
#
#   docker compose up --build --wait && ./scripts/smoke-test.sh
#
# Exercises the real path a browser takes: nginx (static app + security headers) -> /api proxy ->
# Spring Boot -> PostgreSQL with demo data. Reads the demo password from .env; never prints it.
# No -e: every check runs and reports; the exit status comes from the failure count.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
# .env is Compose syntax (unquoted values may contain spaces), so read keys rather than source it.
env_value() { sed -n "s/^$1=//p" .env | tail -n 1; }
ESM_WEB_PORT=$(env_value ESM_WEB_PORT)
ESM_DEMO_PASSWORD=$(env_value ESM_DEMO_PASSWORD)
BASE="http://localhost:${ESM_WEB_PORT:-3000}"
failures=0

pass() { printf '  ok    %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; failures=$((failures + 1)); }
expect() { # expect <description> <expected> <actual>
  if [[ "$2" == "$3" ]]; then pass "$1"; else fail "$1 (expected '$2', got '$3')"; fi
}
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

echo "Smoke test against $BASE"

expect "web app is served" 200 "$(status "$BASE/")"
expect "client-side routes fall back to the app" 200 "$(status "$BASE/tickets/does-not-matter")"

headers=$(curl -s -D - -o /dev/null "$BASE/" || true)
for header in content-security-policy x-content-type-options x-frame-options referrer-policy; do
  if grep -qi "^$header:" <<<"$headers"; then pass "header $header"; else fail "header $header missing"; fi
done

expect "API rejects anonymous calls" 401 "$(status "$BASE/api/users/me")"
expect "API errors are problem+json" "application/problem+json" \
  "$(curl -s -o /dev/null -w '%{content_type}' "$BASE/api/users/me" || true)"
# Only /api/ is proxied: anything else (including /actuator) gets the app's index.html.
actuator_type=$(curl -s -o /dev/null -w '%{content_type}' "$BASE/actuator/health" || true)
expect "actuator is not reachable through the web tier" "text/html" "${actuator_type%%;*}"

login_body=$(jq -n --arg email rita@demo.local --arg password "$ESM_DEMO_PASSWORD" \
  '{email: $email, password: $password}')
login=$(curl -s -H 'Content-Type: application/json' -d "$login_body" "$BASE/api/auth/login" || true)
token=$(jq -r '.accessToken // empty' <<<"$login" 2>/dev/null || true)
if [[ -n "$token" ]]; then pass "demo requester can sign in"; else fail "demo requester sign-in"; fi
auth=(-H "Authorization: Bearer $token")

expect "signed-in user is a requester" REQUESTER "$(curl -s "${auth[@]}" "$BASE/api/users/me" | jq -r .role 2>/dev/null || true)"
tickets=$(curl -s "${auth[@]}" "$BASE/api/tickets" | jq -r .totalElements 2>/dev/null || true)
if [[ "$tickets" =~ ^[0-9]+$ && "$tickets" -gt 0 ]]; then pass "requester sees demo tickets ($tickets)"; else fail "requester tickets ($tickets)"; fi
expect "backend enforces roles (requester -> admin API)" 403 "$(status "${auth[@]}" "$BASE/api/admin/users")"

if ((failures > 0)); then
  echo "$failures check(s) failed"
  exit 1
fi
echo "All checks passed"
