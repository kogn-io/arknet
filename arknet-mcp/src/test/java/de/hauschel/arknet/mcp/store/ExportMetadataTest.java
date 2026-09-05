// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.mcp.version.ServerVersion;
import de.hauschel.arknet.persistence.ExportMetadataVocabulary;

/**
 * Unit tests for the envelope {@code project_export} appends to every dump (issue #194).
 *
 * <p>Asserts on a real TriG <em>parse</em> rather than on substrings: the block is hand-written
 * text appended to a serialiser's output, so "is this still valid TriG, and does it say what it
 * is supposed to say" are the only two questions worth asking of it, and a substring assertion
 * answers neither.</p>
 */
class ExportMetadataTest {

    private static final String CORE = "https://w3id.org/arknet/core";
    private static final String REQUIREMENTS = "https://w3id.org/arknet/requirements";

    @Test
    void statesTheServerVersionItsBuildTimeAndTheExportTime() {
        final Model metadata = parse(ExportFixtures.metadata().trigBlock());

        assertThat(objectsOf(metadata, ExportMetadataVocabulary.SERVER_AGENT,
                ExportMetadataVocabulary.OWL_VERSION_INFO)).containsExactly(ExportFixtures.TEST_VERSION);
        assertThat(objectsOf(metadata, ExportMetadataVocabulary.SERVER_AGENT,
                ExportMetadataVocabulary.DCTERMS_CREATED)).containsExactly(ExportFixtures.FIXED_INSTANT.toString());
        assertThat(metadata.filter(null, Values.iri(ExportMetadataVocabulary.PROV_ENDED_AT_TIME), null)
                .objects().iterator().next().stringValue())
                .isEqualTo(ExportFixtures.FIXED_INSTANT.toString());
    }

    /**
     * The whole envelope sits in one named graph of its own. That is what lets a reader strip it
     * and be left with exactly the dataset the store held - the reason it may be added to a backup
     * at all.
     */
    @Test
    void everyStatementSitsInTheExportMetadataGraphAndNowhereElse() {
        final Model metadata = parse(ExportFixtures.metadata().trigBlock());

        assertThat(metadata.contexts())
                .containsExactly(Values.iri(ExportMetadataVocabulary.EXPORT_METADATA_GRAPH));
    }

    /**
     * The ontology modules are what a future reader needs to interpret the dump, and they never
     * enter a dataset - the runtime loads them only as shapes and axioms.
     */
    @Test
    void listsEveryOntologyModuleWithItsVersionAndLinksItToTheExport() {
        final Model metadata = parse(metadataOver(Map.of(CORE, "0.2.0", REQUIREMENTS, "0.9.9")).trigBlock());

        assertThat(objectsOf(metadata, CORE, ExportMetadataVocabulary.OWL_VERSION_INFO))
                .containsExactly("0.2.0");
        assertThat(objectsOf(metadata, REQUIREMENTS, ExportMetadataVocabulary.OWL_VERSION_INFO))
                .containsExactly("0.9.9");
        assertThat(metadata.filter(null, Values.iri(ExportMetadataVocabulary.PROV_USED), null).objects())
                .containsExactlyInAnyOrder(Values.iri(CORE), Values.iri(REQUIREMENTS));
    }

    /**
     * No build-info on the classpath (an IDE run against a source tree that was never packaged) is
     * reported as such: a recognisable placeholder version and no build time at all, rather than
     * an invented timestamp a reader could not tell from a real one.
     */
    @Test
    void anUnknownBuildClaimsNoBuildTime() {
        final ExportMetadata metadata = new ExportMetadata(
                ServerVersion.unknown(), Map.of(), ExportFixtures.EXPORT_CLOCK);

        final Model parsed = parse(metadata.trigBlock());

        assertThat(objectsOf(parsed, ExportMetadataVocabulary.SERVER_AGENT,
                ExportMetadataVocabulary.OWL_VERSION_INFO)).containsExactly(ServerVersion.UNKNOWN);
        assertThat(parsed.filter(null, Values.iri(ExportMetadataVocabulary.DCTERMS_CREATED), null)).isEmpty();
    }

    /** No ontology module at all must still yield a parseable block, not a dangling {@code ;}. */
    @Test
    void anEmptyModuleListStillYieldsValidTrig() {
        final Model parsed = parse(metadataOver(Map.of()).trigBlock());

        assertThat(parsed.filter(null, Values.iri(ExportMetadataVocabulary.PROV_USED), null)).isEmpty();
        assertThat(parsed.filter(null, Values.iri(ExportMetadataVocabulary.PROV_WAS_ASSOCIATED_WITH), null))
                .isNotEmpty();
    }

    /**
     * Two exports are two events. A shared identity would let a reader take a dump's envelope for
     * the one it saw in another file.
     */
    @Test
    void eachExportIsItsOwnActivity() {
        final ExportMetadata metadata = ExportFixtures.metadata();

        assertThat(activityOf(parse(metadata.trigBlock())))
                .isNotEqualTo(activityOf(parse(metadata.trigBlock())));
    }

    private static ExportMetadata metadataOver(final Map<String, String> ontologyVersions) {
        return new ExportMetadata(
                new ServerVersion(ExportFixtures.TEST_VERSION, Optional.of(ExportFixtures.FIXED_INSTANT)),
                ontologyVersions, ExportFixtures.EXPORT_CLOCK);
    }

    private static String activityOf(final Model metadata) {
        return metadata.filter(null, Values.iri(ExportMetadataVocabulary.RDF_TYPE),
                        Values.iri(ExportMetadataVocabulary.PROV_ACTIVITY))
                .subjects().iterator().next().stringValue();
    }

    private static Iterable<String> objectsOf(final Model metadata, final String subject, final String predicate) {
        return metadata.filter(Values.iri(subject), Values.iri(predicate), null).objects().stream()
                .map(org.eclipse.rdf4j.model.Value::stringValue)
                .toList();
    }

    private static Model parse(final String trig) {
        try {
            return Rio.parse(new ByteArrayInputStream(trig.getBytes(StandardCharsets.UTF_8)), RDFFormat.TRIG);
        } catch (final java.io.IOException unreachable) {
            throw new IllegalStateException(unreachable);
        }
    }
}
