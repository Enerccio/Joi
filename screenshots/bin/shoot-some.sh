#!/bin/bash
# shoot-some.sh <state> <flow.mjs> [<flow.mjs> ...]   Restores a step (the fake model reset) and runs only the named flows
# (shoot-all.sh runs everything; use this for the flows that were fixed or are new).
. "$(dirname "$0")/env.sh"
cd "$SHOT_ROOT"
state="$1"; shift
"$SHOT_ROOT/bin/start-llm.sh" >/dev/null
"$SHOT_ROOT/bin/restore.sh" "$state" >/dev/null
curl -s -X POST "localhost:$SHOT_LLM_PORT/v1/_reset" >/dev/null
for f in "$@"; do echo "=== $f"; node flows/$f 2>&1 | grep -v "^  saved\|dismissed"; done
echo done
