// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.adr.application.port.out.TermLookup;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepositoryFactory;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Schema-drift regression guard (kogn-io/arknet#393), mirroring
 * {@link KognioRdfRequirementLookupRequirementsSchemaTest} exactly:
 * {@link KognioRdfTermLookup} hardcodes the exact storage schema (graph IRI, {@code skos:Concept}
 * type, {@code dcterms:identifier} predicate) that {@code arknet-ubiquitous-language-adapter-kogniordf}'s
 * {@code KognioRdfTermRepository} writes - with no Java import between the two adapter modules that
 * the compiler or ArchUnit could ever catch drift on; the only thing tying the two together is a
 * prose code comment.
 *
 * <p>This test is the substitute for that missing compile-time coupling: it writes a term through
 * the ubiquitous-language BC's <em>actual</em> out-adapter and resolves it through the ADR BC's
 * actual {@link KognioRdfTermLookup} against the very same store. If either adapter's private
 * schema ever drifts from the other's, this test - not a silent
 * {@link UnresolvedReferenceException} at runtime for a term that demonstrably exists - is what
 * fails.</p>
 */
class KognioRdfTermLookupUbiquitousLanguageSchemaTest {

    private static final ProjectId PROJECT = new ProjectId("adr-term-schema");

    private DatasetLifecycleRdf4j lifecycle;
    private TermRepository termRepository;
    private TermLookup adrTermLookup;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-adr-term-schema-it");
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), tmp);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        termRepository = KognioRdfTermRepositoryFactory.over(datasetLifecycle);
        adrTermLookup = new KognioRdfTermLookup(datasetLifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void adrResolvesATermWrittenByTheRealUbiquitousLanguageOutAdapter() {
        TermId id = new TermId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
        Term term = new Term(id, new TermCode("TERM-1"), "Bounded Context",
                "An explicit boundary within which a domain model applies.");

        termRepository.create(PROJECT, term, null);

        assertEquals(id.value(), adrTermLookup.resolveByCode(PROJECT, "TERM-1"));
    }

    @Test
    void anUnknownTermCodeIsRejectedDidactically() {
        assertThrows(UnresolvedReferenceException.class,
                () -> adrTermLookup.resolveByCode(PROJECT, "TERM-99"));
    }
}
