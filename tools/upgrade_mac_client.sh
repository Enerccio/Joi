#!/bin/sh
# Builds the desktop app (server + node, see manual/developer/desktop.md) from the checkout and installs it as Joi.app on this Mac,
# in ~/Applications (the user's place for apps; --dest moves it). The data folder (~/.joi-bundled-client) is never touched: the new
# version upgrades its database when it starts.
#
#   tools/upgrade_mac_client.sh              build, stop a running Joi, replace Joi.app, start it
#   tools/upgrade_mac_client.sh --no-build   install what joi-desktop/target/desktop/package already holds
#   tools/upgrade_mac_client.sh --no-start   do not start it afterwards
#   tools/upgrade_mac_client.sh --dest DIR   install into DIR instead of ~/Applications
#
# Only the desktop app is stopped: a dev server (jetty:run) or a node of the checkout is left alone.
set -e
cd "$(dirname "$0")/.."

build=1
start=1
dest="$HOME/Applications"
while [ $# -gt 0 ]; do
    case "$1" in
        --no-build) build=0 ;;
        --no-start) start=0 ;;
        --dest) shift; dest="$1" ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
    shift
done

# mvn on the PATH may run on a JDK that cannot compile for 25 (CLAUDE.md, Machine notes)
if [ -z "$JAVA_HOME" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"2[5-9]'; then
    JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/corretto-26.0.2.1/Contents/Home"
    export JAVA_HOME
fi

image="joi-desktop/target/desktop/package/Joi.app"
target="$dest/Joi.app"

if [ "$build" = 1 ]; then
    # the installer (.dmg) is not needed for an install from the same machine
    mvn -B -DskipTests -Pfx,production,desktop -Ddesktop.skipInstaller=true package -pl joi-desktop -am
fi
[ -d "$image" ] || { echo "no $image: build first (without --no-build)" >&2; exit 1; }

# stop the installed app: ask it to quit, then end what is left that runs from it
if pgrep -f "$target/Contents" >/dev/null 2>&1; then
    echo "Stopping the running Joi..."
    osascript -e 'tell application "Joi" to quit' >/dev/null 2>&1 || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        pgrep -f "$target/Contents" >/dev/null 2>&1 || break
        sleep 1
    done
    pkill -f "$target/Contents" >/dev/null 2>&1 || true
    sleep 1
fi

mkdir -p "$dest"
rm -rf "$target"
ditto "$image" "$target"
codesign --verify --deep "$target" 2>/dev/null || codesign --force --deep --sign - "$target"
# a build on this machine carries no quarantine; a copy from elsewhere would
xattr -dr com.apple.quarantine "$target" 2>/dev/null || true

echo "Installed $target"
if [ "$start" = 1 ]; then
    open "$target"
fi
