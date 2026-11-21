#!/bin/bash
# Crops the pictures that are a part of another picture of the node: node-controls (the "Her parts" column) from node-chat, node-looking-glass-tuning (the sliders) from the window with her on the Looking Glass.
. "$(dirname "$0")/env.sh"
cp "$SHOT_ROOT/out/node-chat.png" "$SHOT_ROOT/out/node-controls.png"
sips -c 400 560 --cropOffset 60 660 "$SHOT_ROOT/out/node-controls.png" >/dev/null
cp "$SHOT_ROOT/out/_node-tuning-window.png" "$SHOT_ROOT/out/node-looking-glass-tuning.png"
sips -c 630 980 --cropOffset 176 1215 "$SHOT_ROOT/out/node-looking-glass-tuning.png" >/dev/null
echo "node-controls.png and node-looking-glass-tuning.png cropped"
