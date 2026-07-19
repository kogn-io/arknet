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

# Non-root runtime user; the RDF store lives on a mounted volume it must own.
RUN addgroup -S arknet && adduser -S arknet -G arknet

WORKDIR /app
COPY --from=build /build/arknet-mcp/target/arknet-mcp-*.jar app.jar

# --- Container networking, read this before changing the run command ---------
# application.properties hardcodes server.address=127.0.0.1 on purpose: on the
# bare-jar path that loopback bind IS the trust boundary (no auth, ADR-009).
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
ENV ARKNET_RDF_STORAGE=/data/rdf
RUN mkdir -p /data/rdf && chown -R arknet:arknet /data/rdf
VOLUME /data/rdf

USER arknet
EXPOSE 47331

ENTRYPOINT ["java", "-jar", "app.jar"]
