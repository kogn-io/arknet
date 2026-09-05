// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.architecture;

import static de.hauschel.arknet.architecture.support.OntologyFixtures.parse;
import static de.hauschel.arknet.architecture.support.OntologyFixtures.shippedOntologyResources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.mcp.version.OntologyVersions;

/**
 * Nails down the one deliberately fragile thing about {@link OntologyVersions}: it reads arknet's
 * shipped ontology headers with a narrow line scan rather than an RDF parser.
 *
 * <p><strong>Why it cannot parse.</strong> {@code arknet-mcp} is barred from RDF4J
 * ({@link DependencyRulesTest}'s composition-root rule) and the technology-neutral kognio-rdf
 * ports carry a serialiser but no parser, so the scan is the only way the composition root can
 * read a {@code .ttl} at all. The failure mode that buys is silent: reformat an ontology header -
 * put {@code a owl:Ontology} on the subject line, wrap the version literal - and the scan finds
 * nothing, {@code project_export}'s envelope quietly loses the module, and no other test notices,
 * because every other test of that envelope supplies its own versions.</p>
 *
 * <p>This module is where such a seam belongs, and it is the only one that can hold it: it may
 * parse with RDF4J (it already does, for the vocabulary-versus-ontology tests) and it depends on
 * {@code arknet-mcp}, so it can put both answers side by side. Same shape as
 * {@code ProvenanceVocabularyMatchesOntologyTest}: two independent readings of the same shipped
 * file, asserted equal.</p>
 */
class OntologyVersionsMatchOntologyTest {

    /** What {@code arknet-mcp} will put into the next export envelope. */
    private static final Map<String, String> SCANNED = OntologyVersions.onClasspath();

    /**
     * The same question answered by a real parse, over every {@code .ttl} {@code arknet-ontology}
     * ships - enumerated off the classpath, never listed here. A module added to the ontology but
     * forgotten in {@link OntologyVersions} is exactly the omission this must catch, and a
     * hand-kept list on this side would be the same forgotten line twice.
     */
    private static final Map<String, String> DECLARED = declaredVersions();

    @Test
    void theScanFindsEveryShippedOntologyModule() {
        assertEquals(DECLARED.keySet(), SCANNED.keySet(),
                "OntologyVersions must read every live ontology module arknet-ontology ships - "
                        + "a module it misses silently disappears from every project_export envelope");
    }

    @Test
    void theScanReportsTheVersionTheOntologyActuallyDeclares() {
        assertEquals(DECLARED, SCANNED,
                "OntologyVersions' line scan and a real RDF4J parse of the same files must agree; "
                        + "a reformatted ontology header breaks the scan, not the ontology");
    }

    /**
     * Guards the assumption the scan rests on: a file that declares an ontology declares exactly
     * one, carrying exactly one {@code owl:versionInfo}. A second of either would make "the first
     * version literal after the subject line" an arbitrary pick rather than the module's version.
     */
    @Test
    void everyModuleDeclaresExactlyOneOntologyWithOneVersion() {
        for (final String resource : shippedOntologyResources()) {
            final Model ontology = parse(resource, OntologyVersionsMatchOntologyTest.class);
            if (ontology.filter(null, RDF.TYPE, OWL.ONTOLOGY).subjects().isEmpty()) {
                // A shapes file, not an ontology module - it defines no vocabulary to version.
                continue;
            }
            assertEquals(1, ontology.filter(null, RDF.TYPE, OWL.ONTOLOGY).subjects().size(),
                    resource + " must declare exactly one owl:Ontology");
            assertEquals(1, ontology.filter(null, OWL.VERSIONINFO, null).size(),
                    resource + " must carry exactly one owl:versionInfo");
        }
    }

    @Test
    void everyModuleShipsANonBlankVersion() {
        assertFalse(SCANNED.isEmpty(), "no ontology module found - the ontology jar was not loaded");
        SCANNED.forEach((iri, version) ->
                assertTrue(!version.isBlank(), iri + " must declare a non-blank owl:versionInfo"));
    }

    /** Ontology IRI to {@code owl:versionInfo}, for every shipped {@code .ttl} that declares one. */
    private static Map<String, String> declaredVersions() {
        final Map<String, String> versions = new HashMap<>();
        for (final String resource : shippedOntologyResources()) {
            final Model ontology = parse(resource, OntologyVersionsMatchOntologyTest.class);
            for (final Resource subject : ontology.filter(null, RDF.TYPE, OWL.ONTOLOGY).subjects()) {
                ontology.filter(subject, OWL.VERSIONINFO, null).stream()
                        .map(Statement::getObject)
                        .findFirst()
                        .ifPresent(version -> versions.put(subject.stringValue(), version.stringValue()));
            }
        }
        return versions;
    }
}
