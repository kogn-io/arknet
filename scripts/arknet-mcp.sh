#!/usr/bin/env bash
# Wrapper script for arknet MCP server.
# Builds the fat JAR on first run, then starts it.
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
