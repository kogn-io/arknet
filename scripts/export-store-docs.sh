#!/usr/bin/env bash
# Refreshes docs/adr-export/, the generated read surface that lets a
# repository visitor (or the build environment) see arknet's own
# architecture decisions without a running store (ADR-2, issue #415).
#
# GENERATED, never hand-edited, never a merge basis -- the store stays the
# only source of truth. Run manually, on demand, after writing to arknet's
# own store data (adr_*/req_*/uc_*/term_*/bc_*/constraint_*/actor_*).
#
# Usage: scripts/export-store-docs.sh <trig-source-path> [project-anchor]
#   <trig-source-path>  the path the arknet MCP tool `project_export`
#                        (projectOnly=true) reported for this project - call
#                        it first, then pass the path from its result line.
#   [project-anchor]    defaults to this checkout's root; override only if
#                        it differs from the anchor registered for "arknet".

set -euo pipefail

TRIG_SRC="${1:?usage: export-store-docs.sh <trig-source-path> [project-anchor]}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANCHOR="${2:-$REPO_ROOT}"
DAEMON="${ARKNET_DAEMON:-http://127.0.0.1:47331}"
OUT_DIR="$REPO_ROOT/docs/adr-export"

mkdir -p "$OUT_DIR"

# Strip the project-identity graph: it carries this machine's local anchor
# value (e.g. a filesystem path with a username) and has no place in a
# public repository. Every other graph in the backup dump is safe as-is.
# Aware of triple-quoted """...""" literals (used for multi-paragraph prose
# fields) so a stray "}" inside such a literal cannot end the skip early.
awk '
  BEGIN { skip = 0; inq = 0 }
  {
    line = $0
    tmp = line
    n = gsub(/"""/, "\"\"\"", tmp)
    if (skip) {
      if (n % 2 == 1) inq = !inq
      if (!inq && line == "}") skip = 0
      next
    }
    if (!inq && line ~ /^<https:\/\/w3id\.org\/arknet\/model\/project-identity> \{$/) {
      skip = 1
      if (n % 2 == 1) inq = !inq
      next
    }
    print line
    if (n % 2 == 1) inq = !inq
  }
' "$TRIG_SRC" > "$OUT_DIR/arknet.trig"

curl -fsS -G --data-urlencode "projectAnchor=$ANCHOR" "$DAEMON/report" -o "$OUT_DIR/report.html"

echo "Wrote $OUT_DIR/arknet.trig and $OUT_DIR/report.html"
echo "Review the diff, then: git add docs/adr-export && git commit"
