// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

/**
 * The fixed export envelope {@link ExportMetadataTest} renders.
 *
 * <p>Pinned rather than read off the classpath: a test asserting on an exported dump must not
 * change its expectations the day an ontology module's {@code owl:versionInfo} is bumped, and a
 * fixed clock keeps the exported timestamp assertable. {@code OntologyVersionsTest} in
 * {@code arknet-app} covers what the real classpath yields.</p>
 */
final class ExportFixtures {

    /** A recognisable, unmistakably-not-real version, so a leaked fixture is obvious. */
    static final String TEST_VERSION = "0.0.0-test";

    /** The instant {@link #EXPORT_CLOCK} reports, and the fixture's build time. */
    static final Instant FIXED_INSTANT = Instant.parse("2026-09-05T10:15:30Z");

    static final Clock EXPORT_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private ExportFixtures() {
    }

    static ExportMetadata metadata() {
        return new ExportMetadata(
                TEST_VERSION, Optional.of(FIXED_INSTANT),
                Map.of("https://w3id.org/arknet/core", "9.9.9"),
                EXPORT_CLOCK);
    }
}
