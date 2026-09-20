// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.version;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Which build of arknet this daemon is running (issue #194).
 *
 * <p>One shared daemon serves every project on the machine and is started by hand, so "which
 * build am I calling?" is a question no caller could answer without knowing how the daemon was
 * started - it used to take {@code docker ps} and an image timestamp compared against a merge
 * date. This value object is the single answer three places give: the MCP {@code initialize}
 * handshake ({@code spring.ai.mcp.server.version}, which every client sees), the export envelope
 * of {@code project_export}, and the suffix stamped onto every failing tool call
 * ({@link ToolErrorVersionStamp}).</p>
 *
 * <p><strong>One string, three consumers.</strong> {@link #version} is deliberately not derived
 * independently here: the composition root reads the very property the handshake is configured
 * from ({@code arknet.version}), so a caller comparing an error suffix against the version its
 * client reported can never be told two different things by the same process.</p>
 *
 * <p><strong>Degrades, never throws.</strong> {@code build-info.properties} is produced by the
 * Maven build ({@code spring-boot-maven-plugin:build-info}); running straight from an IDE, or from
 * a test that never packaged anything, there is none. That case is reported as {@link #UNKNOWN}
 * with no build time rather than as a failure or an invented number: a placeholder a reader can
 * recognise is worth more than a plausible-looking wrong version.</p>
 *
 * @param version   the server version, or {@link #UNKNOWN} when no build information was found
 * @param buildTime when this build was produced, empty when unknown
 */
public record ServerVersion(String version, Optional<Instant> buildTime) {

    /**
     * The placeholder {@link #version} carries when nothing named a version. Kept a compile-time
     * constant so it can also serve as the {@code @Value} default of the property the composition
     * root reads.
     */
    public static final String UNKNOWN = "unknown";

    /**
     * @param version   must not be {@code null} or blank
     * @param buildTime must not be {@code null} (use {@link Optional#empty()})
     */
    public ServerVersion {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(buildTime, "buildTime");
        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
    }

    /**
     * @return a version that names no build at all - the honest answer when
     *         {@code build-info.properties} is absent
     */
    public static ServerVersion unknown() {
        return new ServerVersion(UNKNOWN, Optional.empty());
    }

    /**
     * The suffix appended to a failing tool call's message. Short on purpose: it rides on every
     * error an agent ever reads, so it has to cost a glance, not a line.
     *
     * @return e.g. {@code " [arknet 0.7.0-SNAPSHOT]"}
     */
    public String errorStamp() {
        return " [arknet " + version + "]";
    }
}
