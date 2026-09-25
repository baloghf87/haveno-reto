#!/usr/bin/env bash
#
# Soak test for the Haveno orderbook REST API.
#
# Polls the API on an interval for a fixed duration and asserts it stays healthy and keeps
# serving data without intervention. Works against any deployment (local dev network or
# mainnet). Emits a JSONL log and a final PASS/FAIL summary; exits non-zero on failure.
#
# Usage:
#   scripts/soak-test.sh [BASE_URL] [DURATION_SECONDS] [INTERVAL_SECONDS]
#
# Env:
#   REQUIRE_OFFERS=1     require numOffers>0 after the grace period (default 1)
#   GRACE_SECONDS=900    time allowed to bootstrap before data assertions apply
#   MAX_FAILURES=5       consecutive request failures tolerated before aborting
#
set -uo pipefail

BASE_URL="${1:-http://localhost:8080}"
DURATION="${2:-3600}"
INTERVAL="${3:-30}"
REQUIRE_OFFERS="${REQUIRE_OFFERS:-1}"
GRACE_SECONDS="${GRACE_SECONDS:-900}"
MAX_FAILURES="${MAX_FAILURES:-5}"

API="${BASE_URL%/}/api/v1"
LOG="${LOG_FILE:-soak-$(date +%Y%m%d-%H%M%S).jsonl}"
start=$(date +%s)
deadline=$((start + DURATION))
consec_fail=0
polls=0
ok=0
saw_offers=0

echo "Soak test -> $API for ${DURATION}s every ${INTERVAL}s; log=$LOG"

json_get() { # $1=json $2=key  -> value or empty
  printf '%s' "$1" | python3 -c "import sys,json;
try:
  d=json.load(sys.stdin); print(d.get('$2',''))
except Exception: print('')" 2>/dev/null
}

while :; do
  now=$(date +%s)
  [ "$now" -ge "$deadline" ] && break
  polls=$((polls + 1))

  health=$(curl -s --max-time 15 "$API/health")
  hcode=$?
  markets=$(curl -s --max-time 30 "$API/markets")
  mcode=$?

  if [ $hcode -ne 0 ] || [ $mcode -ne 0 ] || [ -z "$health" ]; then
    consec_fail=$((consec_fail + 1))
    echo "{\"ts\":$now,\"event\":\"request_failed\",\"hcode\":$hcode,\"mcode\":$mcode,\"consec\":$consec_fail}" | tee -a "$LOG"
    if [ "$consec_fail" -ge "$MAX_FAILURES" ]; then
      echo "FAIL: $consec_fail consecutive request failures"; exit 1
    fi
    sleep "$INTERVAL"; continue
  fi
  consec_fail=0
  ok=$((ok + 1))

  status=$(json_get "$health" status)
  bootstrapped=$(json_get "$health" bootstrapped)
  numOffers=$(json_get "$health" numOffers)
  numMarkets=$(json_get "$health" numMarkets)
  numTrades=$(json_get "$health" numTradeStatistics)
  uptime=$(json_get "$health" uptimeSeconds)
  [ "${numOffers:-0}" -gt 0 ] 2>/dev/null && saw_offers=1

  echo "{\"ts\":$now,\"status\":\"$status\",\"bootstrapped\":\"$bootstrapped\",\"numOffers\":\"$numOffers\",\"numMarkets\":\"$numMarkets\",\"numTrades\":\"$numTrades\",\"uptime\":\"$uptime\"}" | tee -a "$LOG"

  sleep "$INTERVAL"
done

elapsed=$(( $(date +%s) - start ))
echo "---- summary ----"
echo "polls=$polls ok=$ok elapsed=${elapsed}s saw_offers=$saw_offers"

fail=0
[ "$ok" -eq 0 ] && { echo "FAIL: no successful polls"; fail=1; }
if [ "$REQUIRE_OFFERS" = "1" ] && [ "$elapsed" -ge "$GRACE_SECONDS" ] && [ "$saw_offers" -eq 0 ]; then
  echo "FAIL: never observed any offers after grace period"; fail=1
fi

if [ "$fail" -eq 0 ]; then echo "PASS"; else echo "FAILED"; fi
exit $fail
