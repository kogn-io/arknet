#!/usr/bin/env bash
# Rebuilds the local arknet daemon from this checkout and replaces the running
# container (issue #545).
#
# Two things `docker compose up --build -d` alone does not do:
#   * replace the running container -- the service keeps the tag
#     ghcr.io/kogn-io/arknet:latest, so Compose sees an unchanged service
#     configuration and leaves the old container up; --force-recreate fixes that;
#   * name the build -- without a build arg the image reports "dev" as its
#     serverInfo.version. This script passes ARKNET_VERSION=<full HEAD sha>-local
#     (plus -dirty when the working tree has uncommitted changes), so a local
#     build can never be mistaken for a CI image (which carries the bare sha).
#
# The daemon is shared by every session on the machine; recreating it briefly
# interrupts them.
#
# Usage: scripts/rebuild-daemon.sh        (callable from any directory)
#        ARKNET_DRY_RUN=1 scripts/rebuild-daemon.sh
#            only prints the version and the docker compose command; runs nothing.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

VERSION="$(git rev-parse HEAD)-local"
if [ -n "$(git status --porcelain)" ]; then
  VERSION="${VERSION}-dirty"
fi

CMD=(docker compose up --build -d --force-recreate)

if [ "${ARKNET_DRY_RUN:-}" = "1" ]; then
  echo "ARKNET_VERSION=$VERSION"
  echo "(cd $REPO_ROOT && ARKNET_VERSION=$VERSION ${CMD[*]})"
  exit 0
fi

echo "Rebuilding arknet daemon as version $VERSION"
ARKNET_VERSION="$VERSION" "${CMD[@]}"
