#!/usr/bin/env bash
# Runs the README's loop against a running hookrelay and asserts the result.
#
#   ./scripts/smoke.sh https://hookrelay-u7ml.onrender.com
#   ./scripts/smoke.sh http://localhost:8080
#
# Creates a tenant, a sink, subscribes the sink as an endpoint, publishes an event, and
# fails unless exactly one signed delivery arrives. Used against the live service and
# against the native binary in CI, because a build that starts fast but cannot serve a
# signed delivery is worth nothing.
set -euo pipefail

HOST=${1:?usage: smoke.sh <base-url>}
HOST=${HOST%/}

api() { curl -sS --max-time 90 "$@"; }
field() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

KEY=$(api -X POST "$HOST/v1/tenants" -H 'content-type: application/json' \
  -d '{"name":"smoke"}' | field '["apiKey"]')

SINK=$(api -X POST "$HOST/v1/sinks" -H "authorization: Bearer $KEY")
SINK_URL=$(printf '%s' "$SINK" | field '["url"]')
SINK_ID=$(printf '%s' "$SINK" | field '["id"]')

api -X POST "$HOST/v1/endpoints" -H "authorization: Bearer $KEY" \
  -H 'content-type: application/json' -d "{\"url\":\"$SINK_URL\"}" >/dev/null

DELIVERY=$(api -X POST "$HOST/v1/events" -H "authorization: Bearer $KEY" \
  -H 'content-type: application/json' -H 'idempotency-key: smoke-1' \
  -d '{"type":"smoke.test","payload":{"ok":true}}' | field '["deliveries"][0]["id"]')

# Publishing the same key again must return the original rather than fan out twice.
REPEAT=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$HOST/v1/events" \
  -H "authorization: Bearer $KEY" -H 'content-type: application/json' \
  -H 'idempotency-key: smoke-1' -d '{"type":"smoke.test","payload":{"ok":true}}')

for _ in $(seq 1 30); do
  status=$(api "$HOST/v1/deliveries/$DELIVERY" -H "authorization: Bearer $KEY" | field '["status"]')
  [ "$status" = "SUCCEEDED" ] && break
  sleep 1
done

api "$HOST/v1/sinks/$SINK_ID/requests" -H "authorization: Bearer $KEY" \
  | python3 "$(dirname "$0")/check_smoke.py" "$DELIVERY" "$status" "$REPEAT"
