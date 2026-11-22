#!/bin/bash
# Starts the server (mvn jetty:run of joi-server) on the screenshot port with the screenshot data folder.
# SHOT_MVN_ARGS: more Maven arguments. Logs: screenshots/.run/app.log. The first start builds the frontend and takes minutes.
. "$(dirname "$0")/env.sh"
mkdir -p "$SHOT_ROOT/.run" "$JOI_DATA"
cd "$REPO_ROOT"
nohup mvn -pl joi-server jetty:run -Djetty.http.port=$SHOT_PORT $SHOT_MVN_ARGS > "$SHOT_ROOT/.run/app.log" 2>&1 &
echo $! > "$SHOT_ROOT/.run/app.pid"
echo "started pid $(cat "$SHOT_ROOT/.run/app.pid"), waiting for $SHOT_URL"
for i in $(seq 1 300); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$SHOT_URL/" || true)
  if [ "$code" = "200" ] || [ "$code" = "302" ]; then echo "up after ${i}x2s"; exit 0; fi
  sleep 2
done
echo "not up, see $SHOT_ROOT/.run/app.log"; exit 1
