#!/bin/bash
# restore.sh <name> [--no-start]   Stops the app, replaces the data folder with steps/<name>.tar.gz (00-empty: an empty folder), starts the app again.
. "$(dirname "$0")/env.sh"
name="$1"; [ -z "$name" ] && { echo "usage: restore.sh <name>"; ls "$SHOT_ROOT/steps" 2>/dev/null; exit 2; }
"$SHOT_ROOT/bin/stop-app.sh"
rm -rf "$JOI_DATA"
if [ "$name" = "00-empty" ]; then
  mkdir -p "$JOI_DATA"
else
  [ -f "$SHOT_ROOT/steps/$name.tar.gz" ] || { echo "no such step: $name"; ls "$SHOT_ROOT/steps" 2>/dev/null; exit 1; }
  tar -C "$SHOT_HOME" --exclude="._*" -xzf "$SHOT_ROOT/steps/$name.tar.gz"
fi
echo "restored $name"
[ "$2" = "--no-start" ] || "$SHOT_ROOT/bin/start-app.sh"
