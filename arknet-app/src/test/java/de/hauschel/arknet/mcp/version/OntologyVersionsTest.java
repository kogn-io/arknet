// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.version;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the ontology-version scan {@code project_export}'s envelope is built from.
 *
 * <p>Deliberately shallow on the values: whether the numbers it reports are the ones the shipped
 * {@code .ttl} files actually declare is decided against a real RDF4J parse in
 * {@code OntologyVersionsMatchOntologyTest} ({@code arknet-architecture-tests}), the one module
 * allowed to parse. What is worth pinning here is that the scan finds every live module at all,
 * and that a resource which is not there is skipped rather than thrown over.</p>
 */
class OntologyVersionsTest {

    @Test
    void findsEveryLiveOntologyModuleOnTheClasspath() {
        final Map<String, String> versions = OntologyVersions.onClasspath();

        assertThat(versions).containsOnlyKeys(
                "https://w3id.org/arknet/core",
                "https://w3id.org/arknet/ddd",
                "https://w3id.org/arknet/process",
                "https://w3id.org/arknet/requirements",
                "https://w3id.org/arknet/architecture",
                "https://w3id.org/arknet/provenance",
                "https://w3id.org/arknet/project");
    }

    /** Every module must state a version; an empty one would be a silently useless envelope. */
    @Test
    void everyModuleReportsANonBlankVersion() {
        assertThat(OntologyVersions.onClasspath().values()).allSatisfy(version ->
                assertThat(version).isNotBlank());
    }

    /**
     * A missing resource costs one line of the envelope, never the export. The parked modules
     * under {@code parked/} are exactly this case if one is ever named by mistake.
     */
    @Test
    void aMissingResourceIsSkippedRatherThanThrownOver() {
        assertThat(OntologyVersions.read("/arknet-no-such-module.ttl")).isEmpty();
    }

    /**
     * The scan reads the ontology header, not the first version literal it can find: the actor
     * module's ontology IRI is {@code .../process}, not {@code .../actor}, so a scan that guessed
     * the IRI from the file name would mislabel it.
     */
    @Test
    void namesTheModuleByItsOwnOntologyIriNotByItsFileName() {
        assertThat(OntologyVersions.read("/arknet-actor.ttl"))
                .hasValueSatisfying(module ->
                        assertThat(module.iri()).isEqualTo("https://w3id.org/arknet/process"));
    }
}
