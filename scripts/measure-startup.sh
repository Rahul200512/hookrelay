#!/usr/bin/env bash
# Times how long a build takes to answer its health check, and how much memory it holds
# once it does. Run it against both artefacts to compare:
#
#   ./scripts/measure-startup.sh jvm     target/hookrelay.jar
#   ./scripts/measure-startup.sh native  target/hookrelay
#
# Needs a Postgres on 5432 and APP_ENCRYPTION_KEY set. Reports the median of five runs,
# because a single cold start is mostly noise.
set -euo pipefail

label=${1:?usage: measure-startup.sh <label> <artifact>}
artifact=${2:?usage: measure-startup.sh <label> <artifact>}
runs=${RUNS:-5}
port=${PORT:-18080}

: "${APP_ENCRYPTION_KEY:?set APP_ENCRYPTION_KEY}"
export DATABASE_URL=${DATABASE_URL:-jdbc:postgresql://localhost:5432/hookrelay}
export DATABASE_USERNAME=${DATABASE_USERNAME:-hookrelay}
export DATABASE_PASSWORD=${DATABASE_PASSWORD:-hookrelay}
export SERVER_PORT=$port

times=(); rss=()
for i in $(seq 1 "$runs"); do
  start=$(python3 -c 'import time; print(int(time.time()*1000))')
  if [[ "$artifact" == *.jar ]]; then
    java -jar "$artifact" >/tmp/startup-$label.log 2>&1 &
  else
    "$artifact" >/tmp/startup-$label.log 2>&1 &
  fi
  pid=$!
  until curl -sf "http://localhost:$port/actuator/health" >/dev/null 2>&1; do
    kill -0 $pid 2>/dev/null || { echo "process died; see /tmp/startup-$label.log"; tail -5 /tmp/startup-$label.log; exit 1; }
    sleep 0.02
  done
  end=$(python3 -c 'import time; print(int(time.time()*1000))')
  times+=( $((end - start)) )
  # Resident set once it is serving, in MB.
  rss+=( $(( $(ps -o rss= -p $pid | tr -d ' ') / 1024 )) )
  kill $pid 2>/dev/null || true
  wait $pid 2>/dev/null || true
done

median() { printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END {print (NR%2) ? a[(NR+1)/2] : int((a[NR/2]+a[NR/2+1])/2)}'; }
size=$( [[ "$artifact" == *.jar ]] && du -m "$artifact" | cut -f1 || du -m "$artifact" | cut -f1 )
printf '%-8s startup median %5s ms  (runs: %s)   rss median %4s MB   artifact %s MB\n' \
  "$label" "$(median "${times[@]}")" "${times[*]}" "$(median "${rss[@]}")" "$size"
