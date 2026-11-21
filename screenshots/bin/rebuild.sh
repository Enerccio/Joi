#!/bin/bash
# rebuild.sh [from-step]   Replays the setup chain (bin/chain.txt).
#   rebuild.sh              from the empty installation to the end of the chain
#   rebuild.sh 02-models    restores that step and runs the flows after it
# The server and the fake model are (re)started as needed; every step is saved to steps/<name>.tar.gz.
. "$(dirname "$0")/env.sh"
cd "$SHOT_ROOT"
from="${1:-00-empty}"
"$SHOT_ROOT/bin/start-llm.sh" >/dev/null
"$SHOT_ROOT/bin/restore.sh" "$from" --no-start || exit 1
"$SHOT_ROOT/bin/start-app.sh" || exit 1
started=0; [ "$from" = "00-empty" ] && started=1
while read -r flow step; do
  case "$flow" in ''|\#*) continue;; esac
  if [ $started = 0 ]; then
    [ "$step" = "$from" ] && started=1
    continue
  fi
  echo "=== $flow -> $step"
  curl -s -X POST "localhost:$SHOT_LLM_PORT/v1/_reset" >/dev/null
  node "flows/$flow" || { echo "FAILED in $flow"; exit 1; }
  "$SHOT_ROOT/bin/snapshot.sh" "$step" >/dev/null
done < "$SHOT_ROOT/bin/chain.txt"
echo "done"
