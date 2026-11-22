#!/bin/bash
# shoot-all.sh   Retakes all screenshots that are not taken by the setup chain itself (bin/chain.txt takes those on the way).
#   Needs the chain to be done (bin/rebuild.sh). Pictures go to screenshots/out/; bin/publish.sh copies them to manual/images.
# A flow that only looks may run on the last step of the chain; a flow that changes data runs on a fresh copy of its step (`on`).
. "$(dirname "$0")/env.sh"
cd "$SHOT_ROOT"
"$SHOT_ROOT/bin/start-llm.sh" >/dev/null
run() { echo "=== $*"; node "flows/$@" 2>&1 | grep -v "^  saved\|dismissed"; }
on() { "$SHOT_ROOT/bin/restore.sh" "$1" >/dev/null; curl -s -X POST "localhost:$SHOT_LLM_PORT/v1/_reset" >/dev/null; }

on 01-admin
run shoot-workspace.mjs

# the JavaFX node (its screenshot mode, a window opens), on a node that was never started: the first start needs the empty server
"$SHOT_ROOT/bin/start-node.sh" --fresh >/dev/null || exit 1
run node-first-start.mjs
"$SHOT_ROOT/bin/stop-node.sh" >/dev/null

# the flows that only look, on the last step of the chain
on 09-conversation
run shoot-settings.mjs
run shoot-joi.mjs
run shoot-moods.mjs
run shoot-model.mjs

# the node with her body and the chat: needs the conversation step and the fake model; its crops are made by bin/crop-node.sh
"$SHOT_ROOT/bin/start-node.sh" --fresh >/dev/null || exit 1
run node-chat.mjs
"$SHOT_ROOT/bin/stop-node.sh" >/dev/null
"$SHOT_ROOT/bin/crop-node.sh"
echo done
