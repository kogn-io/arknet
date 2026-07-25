#!/bin/sh
# Reconciles the JVM's runtime uid/gid with whatever owns the mounted RDF
# store, then drops root and execs the JVM under that identity.
#
# Why this exists: the image bakes in a fixed non-root user (arknet), but
# ARKNET_RDF_STORAGE is normally a bind-mounted host directory (e.g.
# ~/.arknet/rdf, shared with the non-Docker bare-jar path per README) owned by
# whatever uid/gid runs that bare-jar on the host. A one-time `chown` in the
# Dockerfile only touches the image's own filesystem layer and is invisible
# once a bind mount covers the same path at runtime -- it can neither fix an
# existing, differently-owned host directory nor know its owner in advance.
#
# Default (no PUID/PGID set): adopt the store's existing owner and run the JVM
# as that uid:gid -- this is the "shared with a non-Docker run" case, and it
# must NOT chown the host's files to the image's own arknet uid, which would
# break the bare-jar path's access instead of fixing the container's. A store
# owned by root (a fresh bind-mount target Docker just created, or a fresh
# named volume) is the one case with no real owner to adopt; that falls back
# to the image's built-in arknet uid/gid.
#
# PUID/PGID override this entirely, for a caller who wants a specific mapping
# regardless of what currently owns the store (e.g. provisioning a fresh
# volume ahead of first use). su-exec accepts a bare numeric uid:gid with no
# /etc/passwd entry required, so none of this needs useradd/usermod.
set -e

STORE="${ARKNET_RDF_STORAGE:-/data/rdf}"
mkdir -p "$STORE"

DEFAULT_UID="$(id -u arknet)"
DEFAULT_GID="$(id -g arknet)"

if [ -n "$PUID" ]; then
  TARGET_UID="$PUID"
  TARGET_GID="${PGID:-$PUID}"
else
  STORE_UID="$(stat -c '%u' "$STORE")"
  STORE_GID="$(stat -c '%g' "$STORE")"
  if [ "$STORE_UID" = "0" ]; then
    TARGET_UID="$DEFAULT_UID"
    TARGET_GID="$DEFAULT_GID"
  else
    TARGET_UID="$STORE_UID"
    TARGET_GID="$STORE_GID"
  fi
fi

chown -R "$TARGET_UID:$TARGET_GID" "$STORE"

exec su-exec "$TARGET_UID:$TARGET_GID" java -jar app.jar "$@"
