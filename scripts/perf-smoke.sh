#!/usr/bin/env bash
#
# perf-smoke.sh — hit every GET endpoint once and rank them by wall time.
#
# This is the client-side half of the profiler. It gives you total round-trip
# time as the browser experiences it (network + server). The server-side report
# at /v1/admin/perf/report gives you the query count that explains WHY.
# Run both, read them side by side.
#
# USAGE
#   export KASHI_TOKEN='eyJhbGci...'        # JWT from devtools -> any request header
#   export KASHI_TENANT=4                    # X-Tenant-ID
#   export KASHI_BASE=http://localhost:8080
#   export ASSESSMENT_ID=71 VENDOR_ID=12 INSTANCE_ID=331
#   ./perf-smoke.sh
#
# Only GETs are listed — this must be safe to run against a live tenant. Add your
# own lines to ENDPOINTS as needed; anything with a path parameter reads from the
# env vars above so nothing is hardcoded to one dataset.

set -uo pipefail

BASE="${KASHI_BASE:-http://localhost:8080}"
TOKEN="${KASHI_TOKEN:?Set KASHI_TOKEN to a valid JWT}"
TENANT="${KASHI_TENANT:?Set KASHI_TENANT to your tenant id}"

A="${ASSESSMENT_ID:-}"
V="${VENDOR_ID:-}"
I="${INSTANCE_ID:-}"

# ── Endpoints to exercise ────────────────────────────────────────────────────
ENDPOINTS=(
  "/v1/assessments"
  "/v1/workflows/my-tasks"
  "/v1/action-items/my"
  "/v1/action-items/my/count"
  "/v1/notifications"
  "/v1/ui-config/navigation"
)
[ -n "$A" ] && ENDPOINTS+=(
  "/v1/assessments/$A"
  "/v1/assessments/$A/review"
  "/v1/assessments/$A/sections/status"
  "/v1/assessments/$A/my-sections"
  "/v1/assessments/$A/my-questions"
)
[ -n "$V" ] && ENDPOINTS+=( "/v1/vendors/$V/assessments" )
[ -n "$I" ] && ENDPOINTS+=(
  "/v1/workflows/instances/$I/status"
  "/v1/workflow-instances/$I/progress"
)

printf '%-56s %8s %8s\n' "ENDPOINT" "HTTP" "ms"
printf '%.0s-' {1..76}; printf '\n'

TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT

for ep in "${ENDPOINTS[@]}"; do
  # %{time_total} is the full round trip; -o /dev/null so body size does not
  # distort the terminal, but the bytes are still transferred and timed.
  read -r code secs < <(curl -s -o /dev/null \
      -w '%{http_code} %{time_total}\n' \
      -H "Authorization: Bearer $TOKEN" \
      -H "X-Tenant-ID: $TENANT" \
      --max-time 120 \
      "$BASE$ep")
  ms=$(awk -v s="$secs" 'BEGIN{printf "%.0f", s*1000}')
  printf '%-56s %8s %8s\n' "$ep" "$code" "$ms"
  echo "$ms|$ep|$code" >> "$TMP"
done

echo
echo "── Slowest first ─────────────────────────────────────────────────────────"
sort -t'|' -k1 -rn "$TMP" | while IFS='|' read -r ms ep code; do
  flag=""
  [ "$ms" -gt 2000 ] && flag="  <-- over 2s target"
  printf '%8s ms  %-50s%s\n' "$ms" "$ep" "$flag"
done

echo
echo "Now read the server-side view for query counts:"
echo "  curl -H \"Authorization: Bearer \$KASHI_TOKEN\" -H \"X-Tenant-ID: \$KASHI_TENANT\" \\"
echo "       '$BASE/v1/admin/perf/report?minMs=200' | jq '.data.likelyNPlusOne'"