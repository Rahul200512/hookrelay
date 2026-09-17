#!/usr/bin/env bash
# Runs the README's loop against a running hookrelay and asserts the result.
#
#   ./scripts/smoke.sh https://hookrelay-u7ml.onrender.com
#   HOOKRELAY_API_KEY=hr_live_... ./scripts/smoke.sh http://localhost:8080
#
# Creates a sink, subscribes it, publishes an event, and fails unless exactly one signed
# delivery arrives at that sink. Used against the live service after every deploy and
# against the native binary in CI, because a build that starts fast and cannot serve a
# signed delivery proves nothing.
#
# Everything is scoped to the endpoint this run creates, and that endpoint is removed at
# the end. A tenant reused across runs otherwise accumulates endpoints, every event fans
# out to all of them, and a check that watched "the first delivery" would be watching
# some previous run's.
set -euo pipefail

HOST=${1:?usage: smoke.sh <base-url>}
HOST=${HOST%/}
DIR=$(cd "$(dirname "$0")" && pwd)

api() { curl -sS --max-time 90 "$@"; }
field() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

cleanup() {
  if [ -n "${ENDPOINT_ID:-}" ]; then
    curl -sS --max-time 30 -o /dev/null -X DELETE "$HOST/v1/endpoints/$ENDPOINT_ID" \
      -H "authorization: Bearer $KEY" || true
  fi
}
trap cleanup EXIT

# Signup is rate limited per address, so a key can be supplied instead of creating one.
if [ -n "${HOOKRELAY_API_KEY:-}" ]; then
  KEY=$HOOKRELAY_API_KEY
else
  signup=$(curl -sS --max-time 90 -w '\n%{http_code}' -X POST "$HOST/v1/tenants" \
    -H 'content-type: application/json' -d '{"name":"smoke"}')
  code=$(printf '%s' "$signup" | tail -1)
  body=$(printf '%s' "$signup" | sed '$d')
  if [ "$code" != "201" ]; then
    echo "smoke: could not create a tenant (HTTP $code)" >&2
    echo "$body" >&2
    [ "$code" = "429" ] && echo "smoke: signup is rate limited here; set HOOKRELAY_API_KEY to reuse a key" >&2
    exit 1
  fi
  KEY=$(printf '%s' "$body" | field '["apiKey"]')
fi

SINK=$(api -X POST "$HOST/v1/sinks" -H "authorization: Bearer $KEY")
SINK_URL=$(printf '%s' "$SINK" | field '["url"]')
SINK_ID=$(printf '%s' "$SINK" | field '["id"]')

created=$(curl -sS --max-time 90 -w '\n%{http_code}' -X POST "$HOST/v1/endpoints" \
  -H "authorization: Bearer $KEY" -H 'content-type: application/json' \
  -d "{\"url\":\"$SINK_URL\",\"eventTypes\":[\"smoke.test\"]}")
code=$(printf '%s' "$created" | tail -1)
if [ "$code" != "201" ]; then
  echo "smoke: could not register the endpoint (HTTP $code)" >&2
  printf '%s' "$created" | sed '$d' >&2
  exit 1
fi
ENDPOINT_ID=$(printf '%s' "$created" | sed '$d' | field '["endpoint"]["id"]')

# Unique per run. A fixed key would make every run after the first return the original
# event -- correct idempotency, and useless as a repeatable check, because that event's
# deliveries were fanned out before this run's endpoint existed.
IDEM="smoke-$(date +%s)-$$"

event=$(api -X POST "$HOST/v1/events" -H "authorization: Bearer $KEY" \
  -H 'content-type: application/json' -H "idempotency-key: $IDEM" \
  -d '{"type":"smoke.test","payload":{"ok":true}}')
# This run's delivery, not whichever happens to be first.
DELIVERY=$(printf '%s' "$event" | ENDPOINT_ID="$ENDPOINT_ID" python3 -c '
import json, os, sys
deliveries = json.load(sys.stdin)["deliveries"]
mine = [d for d in deliveries if d["endpointId"] == os.environ["ENDPOINT_ID"]]
if not mine:
    print("smoke: the event did not fan out to the endpoint this run created", file=sys.stderr)
    raise SystemExit(1)
print(mine[0]["id"])')

# Publishing the same key again must return the original rather than fan out twice.
REPEAT=$(curl -sS --max-time 90 -o /dev/null -w '%{http_code}' -X POST "$HOST/v1/events" \
  -H "authorization: Bearer $KEY" -H 'content-type: application/json' \
  -H "idempotency-key: $IDEM" -d '{"type":"smoke.test","payload":{"ok":true}}')

status=unknown
for _ in $(seq 1 30); do
  status=$(api "$HOST/v1/deliveries/$DELIVERY" -H "authorization: Bearer $KEY" | field '["status"]')
  case "$status" in
    SUCCEEDED|DEAD) break ;;
  esac
  sleep 1
done

api "$HOST/v1/sinks/$SINK_ID/requests" -H "authorization: Bearer $KEY" \
  | python3 "$DIR/check_smoke.py" "$DELIVERY" "$status" "$REPEAT"
