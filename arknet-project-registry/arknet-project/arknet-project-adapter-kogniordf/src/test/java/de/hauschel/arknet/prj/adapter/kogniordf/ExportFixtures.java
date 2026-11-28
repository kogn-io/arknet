// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.adapter.kogniordf;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.persistence.ExportMetadata;

/**
 * The fixed export envelope every {@link KognioRdfProjectDatasets} in these tests is built with.
 *
 * <p>Pinned rather than read off the classpath: a test asserting on an exported dump must not
 * change its expectations the day an ontology module's {@code owl:versionInfo} is bumped, and a
 * fixed clock keeps the exported timestamp assertable. {@code ExportMetadataTest} in
 * {@code arknet-persistence-support} covers the envelope's own rendering.</p>
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

    static KognioRdfProjectDatasets exporterOver(final DatasetLifecycle lifecycle) {
        return new KognioRdfProjectDatasets(lifecycle, metadata());
    }
}
