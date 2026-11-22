#!/bin/bash
# Stops the node of the screenshot mode (asks it to quit, then kills what listens on its port).
. "$(dirname "$0")/env.sh"
curl -s -m 3 -X POST -H 'Content-Type: application/json' -d '{"cmd":"quit"}' "http://127.0.0.1:$SHOT_NODE_PORT/do" >/dev/null 2>&1
sleep 1
pids=$("$SHOT_ROOT/bin/listening-pids.sh" $SHOT_NODE_PORT)
[ -f "$SHOT_ROOT/.run/node.pid" ] && pids="$pids $(cat "$SHOT_ROOT/.run/node.pid")"
for p in $pids; do kill "$p" 2>/dev/null; done
rm -f "$SHOT_ROOT/.run/node.pid"
echo stopped
