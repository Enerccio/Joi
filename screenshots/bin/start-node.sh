#!/bin/bash
# Starts the JavaFX node in its screenshot mode (it opens a window, OpenJFX has no headless mode) with settings of its own
# (a temporary folder, SHOT_NODE_HOME in env.sh; --fresh empties it first: a node that was never started) and waits for its command port. Logs: screenshots/.run/node.log. Pictures go to screenshots/out/.
. "$(dirname "$0")/env.sh"
mkdir -p "$SHOT_ROOT/.run" "$SHOT_NODE_HOME" "$SHOT_ROOT/out"
"$SHOT_ROOT/bin/stop-node.sh" >/dev/null
[ "$1" = "--fresh" ] && rm -rf "$SHOT_NODE_HOME" && mkdir -p "$SHOT_NODE_HOME"
cd "$REPO_ROOT"
export JOI_CLIENT="$SHOT_NODE_HOME"
nohup mvn -o -q -Pfx compile exec:exec@run -pl joi-node -Dnode.args="shots $SHOT_ROOT/out $SHOT_NODE_PORT" > "$SHOT_ROOT/.run/node.log" 2>&1 &
echo $! > "$SHOT_ROOT/.run/node.pid"
for i in $(seq 1 120); do
  curl -s -X POST -d '{"cmd":"texts"}' "http://127.0.0.1:$SHOT_NODE_PORT/do" >/dev/null 2>&1 && { echo "node up after ${i}x2s"; exit 0; }
  sleep 2
done
echo "node not up, see $SHOT_ROOT/.run/node.log"; exit 1
