#!/usr/bin/env bash
# Starts the arknet MCP server daemon: a single, long-running process per workspace,
# reachable over Streamable HTTP on 127.0.0.1:47331 (issue #137).
#
# Unlike earlier versions, Claude Code no longer spawns this per session (.mcp.json now
# points at the HTTP endpoint directly) - you start this once, yourself, and keep it
# running (foreground in a terminal/tmux, or as a systemd/launchd service) for as long as
# you want to use arknet against this workspace. Builds the fat JAR on first run.
# Prerequisites: Java 21+, Maven 3.9+

set -euo pipefail

PLUGIN_ROOT="${CLAUDE_PLUGIN_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
JAR="$PLUGIN_ROOT/arknet-mcp/target/arknet-mcp-0.1.0-SNAPSHOT.jar"
POM="$PLUGIN_ROOT/pom.xml"

# Check prerequisites
for cmd in java mvn; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "arknet: '$cmd' not found. Install Java 21+ and Maven 3.9+." >&2
        exit 1
    fi
done

# Build if JAR doesn't exist or pom.xml is newer
if [ ! -f "$JAR" ] || [ "$POM" -nt "$JAR" ]; then
    echo "arknet: building MCP server (first run)..." >&2
    (cd "$PLUGIN_ROOT" && mvn -q -pl arknet-mcp -am clean package -DskipTests) >&2
fi

exec java -jar "$JAR" "$@"
