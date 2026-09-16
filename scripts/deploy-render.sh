#!/usr/bin/env bash
# Creates (or finds) the Render free web service for this repository and deploys it.
#
#   RENDER_API_KEY   from Render → Account settings → API keys (required)
#   DATABASE_URL, DATABASE_USERNAME, DATABASE_PASSWORD   the Neon values (required on first create)
#   APP_ENCRYPTION_KEY  base64 of 32 bytes; generated and saved to ~/.hookrelay/app.env if unset
#
# Idempotent: a second run finds the existing service and triggers a deploy.
set -euo pipefail

: "${RENDER_API_KEY:?set RENDER_API_KEY}"
API=https://api.render.com/v1
NAME=${SERVICE_NAME:-hookrelay}
REPO=${REPO_URL:-https://github.com/Rahul200512/hookrelay}
REGION=${RENDER_REGION:-virginia}

auth=(-H "Authorization: Bearer $RENDER_API_KEY" -H "Content-Type: application/json" -H "Accept: application/json")

owner_id=$(curl -fsS "${auth[@]}" "$API/owners?limit=20" | python3 -c '
import sys, json
owners = [o["owner"] for o in json.load(sys.stdin)]
users = [o for o in owners if o.get("type") == "user"] or owners
print(users[0]["id"])')

existing=$(curl -fsS "${auth[@]}" "$API/services?name=$NAME&limit=20" | python3 -c '
import sys, json
for row in json.load(sys.stdin):
    s = row["service"]
    if s["name"] == sys.argv[1]:
        print(s["id"]); break' "$NAME")

if [ -z "$existing" ]; then
  : "${DATABASE_URL:?set DATABASE_URL}" "${DATABASE_USERNAME:?set DATABASE_USERNAME}" "${DATABASE_PASSWORD:?set DATABASE_PASSWORD}"
  # Signing secrets are encrypted at rest with this key and the service will not start
  # without it. Changing it later makes every stored secret unreadable, so keep a copy
  # somewhere that is not this terminal's scrollback.
  if [ -z "${APP_ENCRYPTION_KEY:-}" ]; then
    umask 077; mkdir -p "$HOME/.hookrelay"
    APP_ENCRYPTION_KEY=$(openssl rand -base64 32)
    printf 'APP_ENCRYPTION_KEY=%s\n' "$APP_ENCRYPTION_KEY" >> "$HOME/.hookrelay/app.env"
    echo "generated an encryption key and appended it to ~/.hookrelay/app.env"
  fi
  export APP_ENCRYPTION_KEY
  body=$(python3 - "$owner_id" "$NAME" "$REPO" "$REGION" <<'PY'
import json, os, sys
owner, name, repo, region = sys.argv[1:5]
print(json.dumps({
  "type": "web_service",
  "name": name,
  "ownerId": owner,
  "repo": repo,
  "branch": "main",
  "autoDeploy": "no",
  "serviceDetails": {
    "runtime": "docker",
    "plan": "free",
    "region": region,
    "numInstances": 1,
    "healthCheckPath": "/actuator/health",
    "envSpecificDetails": {"dockerfilePath": "./Dockerfile", "dockerContext": "."},
  },
  "envVars": [
    {"key": "DATABASE_URL", "value": os.environ["DATABASE_URL"]},
    {"key": "DATABASE_USERNAME", "value": os.environ["DATABASE_USERNAME"]},
    {"key": "DATABASE_PASSWORD", "value": os.environ["DATABASE_PASSWORD"]},
    {"key": "APP_ENCRYPTION_KEY", "value": os.environ["APP_ENCRYPTION_KEY"]},
  ],
}))
PY
)
  created=$(curl -fsS "${auth[@]}" -X POST "$API/services" -d "$body")
  service_id=$(echo "$created" | python3 -c 'import sys,json; print(json.load(sys.stdin)["service"]["id"])')
  echo "created service $service_id"
else
  service_id=$existing
  echo "found service $service_id; requesting a deploy"
  curl -fsS "${auth[@]}" -X POST "$API/services/$service_id/deploys" -d '{"clearCache":"do_not_clear"}' >/dev/null
fi

url=$(curl -fsS "${auth[@]}" "$API/services/$service_id" | python3 -c 'import sys,json; print(json.load(sys.stdin)["serviceDetails"]["url"])')
echo "service url: $url"

echo "waiting for the deploy to go live (a Maven build on the free builder takes a few minutes)"
for _ in $(seq 1 90); do
  status=$(curl -fsS "${auth[@]}" "$API/services/$service_id/deploys?limit=1" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d[0]["deploy"]["status"] if d else "none")')
  case "$status" in
    live) echo "live"; break ;;
    build_failed|update_failed|canceled|deactivated) echo "deploy $status"; exit 1 ;;
    *) printf '.'; sleep 10 ;;
  esac
done
echo
curl -fsS "$url/actuator/health" && echo
echo "RENDER_SERVICE_ID=$service_id"
echo "APP_URL=$url"
