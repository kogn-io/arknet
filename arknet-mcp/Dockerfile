# arknet-mcp -- Docker image for the shared MCP HTTP daemon (issue #140).
#
# IMPORTANT: build with the REPOSITORY ROOT as the build context, not this module
# directory. arknet-mcp is a Maven multi-module reactor and depends on its sibling
# modules (arknet-requirements-adapter-mcp, ...) via ${project.version}, so the whole
# source tree must be visible to the build stage:
#
#     docker build -f arknet-mcp/Dockerfile -t arknet-mcp .
#
# ---------------------------------------------------------------------------
# Build stage: full JDK 25 + Maven, compiles the reactor and produces the
# repackaged Spring Boot fat jar via spring-boot-maven-plugin:repackage.
# The plugin is configured with classifier "exec" (issue #237) so the plain
# thin jar stays the reactor's main artifact -- the fat jar this stage copies
# below therefore carries the "-exec" suffix.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build

# Copy the entire reactor (the .dockerignore keeps target/, .git, IDE cruft out)
# and build only arknet-mcp plus the modules it needs (-am = also make dependencies).
COPY . .
RUN mvn -pl arknet-mcp -am package -DskipTests -B

# ---------------------------------------------------------------------------
# Runtime stage: slim JRE 25, non-root, only the fat jar.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime

# GHCR links a package to its source repository via this label. Without it the
# pushed package stays unlinked, "inherit access from source repository" cannot
# apply, and the workflow's GITHUB_TOKEN is denied write_package on the very
# first push -- even though Actions granted it `packages: write`.
LABEL org.opencontainers.image.source=https://github.com/kogn-io/arknet

# Non-root runtime user; the RDF store lives on a mounted volume it must own.
# su-exec drops root to a target uid:gid at startup -- entrypoint.sh passes it
# a bare numeric spec, so no /etc/passwd entry is needed for the identity the
# JVM actually runs as; the named arknet user below is only its documented
# fallback identity for a fresh, container-only store.
RUN apk add --no-cache su-exec \
    && addgroup -S arknet && adduser -S arknet -G arknet

WORKDIR /app
COPY --from=build /build/arknet-mcp/target/arknet-mcp-*-exec.jar app.jar
COPY arknet-mcp/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# The version this daemon reports everywhere (issue #159, extended by issue #194: the MCP
# initialize handshake, every project_export dump's metadata graph, and the suffix on every
# failed tool call). The caller passes the real released version (release.yml: vX.Y.Z) or a
# commit sha (docker.yml: continuous :latest/:sha build) as a build-arg; application.properties'
# arknet.version placeholder picks up this baked-in env var at runtime and prefers it over the
# jar's own build-info.properties. Defaults to "dev" for a manual, un-parameterized
# `docker build` -- an empty ARKNET_VERSION would otherwise win over that fallback chain
# outright, since Spring treats a present-but-empty env var as a real value.
ARG ARKNET_VERSION=dev
ENV ARKNET_VERSION=${ARKNET_VERSION}

# --- Container networking, read this before changing the run command ---------
# application.properties hardcodes server.address=127.0.0.1 on purpose: for a
# process running directly on its host, that loopback bind IS the trust boundary
# (no auth, ADR-009).
# Inside a container that default is BROKEN: Docker's published-port NAT delivers
# inbound traffic to the container's eth0, not its loopback interface, so a
# service bound only to 127.0.0.1 is unreachable even with -p published.
# We therefore bind to 0.0.0.0 INSIDE the container via Spring Boot's relaxed
# env-var binding (SERVER_ADDRESS overrides server.address at runtime WITHOUT
# editing application.properties -- its 127.0.0.1 default must stay correct for
# the non-Docker path).
#
# The trust boundary now moves to the HOST-side port publish. It MUST bind to
# host loopback only:  -p 127.0.0.1:47331:47331   -- NEVER  -p 47331:47331
# alone, which would expose this un-authenticated daemon to the whole LAN.
ENV SERVER_ADDRESS=0.0.0.0

# RDF store location inside the container. Maps to arknet.rdf.storage
# (application.properties). Mount the host path other tooling already uses:
#     -v ~/.arknet/rdf:/data/rdf
# A chown here only reaches the image's own filesystem layer -- a bind-mounted
# host directory (the normal case) covers it at runtime and keeps whatever
# ownership it already has. entrypoint.sh reconciles that at every container
# start instead, which is why the image stays root until then.
ENV ARKNET_RDF_STORAGE=/data/rdf
RUN mkdir -p /data/rdf && chown -R arknet:arknet /data/rdf
VOLUME /data/rdf

# store_overview's HTML report location (arknet.report.dir). Without this, the
# fallback report dir defaults to WORKDIR (/app), which is root-owned and not
# writable by the non-root uid the JVM runs as -- store_overview would fail to
# write a report for every caller whose own project directory isn't reachable
# from inside this container either (the normal case; issue #158). Mounting it
# also lets a human actually open the generated store-report.html from the
# host, which a container-only path never would:
#     -v ~/.arknet/report:/data/report
ENV ARKNET_REPORT_DIR=/data/report
RUN mkdir -p /data/report && chown -R arknet:arknet /data/report
VOLUME /data/report

# The container cannot discover its own bind mount's HOST-side path (Docker
# does not expose that to the process inside), so store_overview's digest
# would otherwise name the unreachable /data/report to a caller running
# outside the container (issue #160). Not set here -- it's host-specific, not
# an image default -- pass it at `docker run`/compose time naming the same
# path as the -v/volumes source for /data/report, e.g.
# -e ARKNET_REPORT_HOST_DIR=~/.arknet/report. Maps to arknet.report.host-dir.

# project_export's backup location (arknet.export.dir). Without this, the
# fallback export dir defaults to ${user.home}, which resolves to the
# container's root filesystem ("/") for the uid the JVM runs as -- every
# project_export call would fail with AccessDeniedException on /.arknet/export
# instead of writing anywhere:
#     -v ~/.arknet/export:/data/export
ENV ARKNET_EXPORT_DIR=/data/export
RUN mkdir -p /data/export && chown -R arknet:arknet /data/export
VOLUME /data/export

# Same host-path blind spot as ARKNET_REPORT_HOST_DIR above, for the same
# reason: pass it at `docker run`/compose time naming the same path as the
# -v/volumes source for /data/export, e.g. -e ARKNET_EXPORT_HOST_DIR=~/.arknet/export.
# Maps to arknet.export.host-dir.

# No USER here: entrypoint.sh needs root to reconcile arknet's uid/gid with the
# mounted store's owner (optionally via PUID/PGID) and chown accordingly, then
# drops to arknet itself via su-exec before exec-ing the JVM.
EXPOSE 47331

ENTRYPOINT ["/entrypoint.sh"]
