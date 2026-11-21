#!/usr/bin/env bash
# Rebuilds the local arknet daemon from this checkout and replaces the running
# container (issue #545).
#
# Three things `docker compose up --build -d` alone does not do:
#   * replace the running container -- the rebuild alone was observed to leave
#     the old container running on the old image (#545); --force-recreate
#     forces the replacement;
#   * hit the running daemon's Compose project -- Compose derives the project
#     name from the checkout's directory name, so from a worktree or a checkout
#     under another name a plain `up` would start a second project that fails
#     on the published port while the old daemon keeps running. The script
#     passes the project name of the container currently running the
#     arknet-app service on port 47331, falling back to "arknet" when none runs;
#     COMPOSE_PROJECT_NAME, when set, wins;
#   * name the build -- without a build arg the image reports "dev" as its
#     serverInfo.version. This script passes ARKNET_VERSION=<full HEAD sha>-local
#     (plus -dirty when the working tree has uncommitted changes), so a local
#     build can never be mistaken for a CI image (which carries the bare sha).
#
# The Compose files are read from the checkout this script lives in, including
# an untracked docker-compose.override.yml there, not from the checkout that
# started the running container.
#
# The daemon is shared by every session on the machine; recreating it briefly
# interrupts them.
#
# Usage: scripts/rebuild-daemon.sh        (callable from any directory)
#        ARKNET_DRY_RUN=1 scripts/rebuild-daemon.sh
#            only prints the version, the project and the docker compose
#            command; changes nothing.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

VERSION="$(git rev-parse HEAD)-local"
if [ -n "$(git status --porcelain)" ]; then
  VERSION="${VERSION}-dirty"
fi

PROJECT="${COMPOSE_PROJECT_NAME:-}"
if [ -z "$PROJECT" ]; then
  PROJECT="$(docker ps \
    --filter label=com.docker.compose.service=arknet-app \
    --filter publish=47331 \
    --format '{{.Label "com.docker.compose.project"}}' 2>/dev/null | head -n 1 || true)"
fi
PROJECT="${PROJECT:-arknet}"

CMD=(docker compose -p "$PROJECT" up --build -d --force-recreate)

if [ "${ARKNET_DRY_RUN:-}" = "1" ]; then
  echo "ARKNET_VERSION=$VERSION"
  echo "Compose project: $PROJECT"
  printf '(cd %q && ARKNET_VERSION=%q' "$REPO_ROOT" "$VERSION"
  printf ' %q' "${CMD[@]}"
  printf ')\n'
  exit 0
fi

echo "Rebuilding arknet daemon as version $VERSION (Compose project $PROJECT)"
ARKNET_VERSION="$VERSION" "${CMD[@]}"
