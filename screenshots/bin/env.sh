# Shared settings of the screenshot environment. Source it: `. screenshots/bin/env.sh`
SHOT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
REPO_ROOT="$(cd "$SHOT_ROOT/.." && pwd)"
export SHOT_ROOT REPO_ROOT
export SHOT_HOME="$SHOT_ROOT/.data"            # the folder that holds the data folder
export JOI_DATA="$SHOT_HOME/joi"               # the server's data folder (JOI_DATA): database, secret.key, files
export SHOT_PORT=8099                          # the server (the user's own development server is on 8090)
export SHOT_LLM_PORT=8098                      # the fake inference server
export SHOT_URL="http://127.0.0.1:$SHOT_PORT"  # 127.0.0.1, not localhost: cookies ignore ports, two localhost servers log each other out
# Maven needs JDK 25+; the machine's default java is older (CLAUDE.md)
export JAVA_HOME="${JOI_JAVA_HOME:-$HOME/Library/Java/JavaVirtualMachines/corretto-26.0.2.1/Contents/Home}"
# the JavaFX node in its screenshot mode (joi-node fx/NodeShots): the commands of a script arrive on this port
export SHOT_NODE_PORT=8097
# JOI_CLIENT: the node's own settings, a temporary folder of their own: not ~/.joi-client (the personal node), not ~/.joi-dev-client (the
# development node), and not in the repository. Fixed, so every script of a run finds it; the system empties the temporary folder now and then
# (the node unpacks Chromium into it again), and `bin/start-node.sh --fresh` empties it.
export SHOT_NODE_HOME="${TMPDIR:-/tmp}/joi-shots-client"
