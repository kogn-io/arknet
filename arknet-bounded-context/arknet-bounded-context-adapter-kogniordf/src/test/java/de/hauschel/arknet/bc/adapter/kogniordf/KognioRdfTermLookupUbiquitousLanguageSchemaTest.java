// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.bc.application.port.out.TermLookup;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepositoryFactory;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Schema-drift regression guard (mirrors the requirements adapter's homonymous test):
 * {@link KognioRdfTermLookup} hardcodes the exact storage schema (graph IRI, {@code skos:Concept}
 * type, {@code dcterms:identifier} predicate) that
 * {@code arknet-ubiquitous-language-adapter-kogniordf}'s {@code KognioRdfTermRepository} writes -
 * with no Java import between the two adapter modules that the compiler or ArchUnit could ever
 * catch drift on; the only thing tying the two together is a prose code comment.
 *
 * <p>This test is the substitute for that missing compile-time coupling: it writes a term through
 * the ubiquitous-language BC's <em>actual</em> out-adapter and resolves it through the
 * bounded-context BC's actual {@link KognioRdfTermLookup} against the very same store. If either
 * adapter's private schema ever drifts from the other's, this test - not a silent
 * {@code UnresolvedReferenceException} at runtime for a term that demonstrably exists - is what
 * fails.</p>
 */
class KognioRdfTermLookupUbiquitousLanguageSchemaTest {

    private static final ProjectId PROJECT = new ProjectId("bc-ul-schema");

    /**
     * The store's on-disk home, managed by JUnit rather than {@code Files.createTempDirectory},
     * which left its directories behind - harmless while the store is {@code IN_MEMORY}, but
     * still an inode left in {@code /tmp} for every test run. Deleted after {@link #tearDown()}
     * has shut the store down.
     */
    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private TermRepository ulTermRepository;
    private TermLookup bcTermLookup;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        ulTermRepository = KognioRdfTermRepositoryFactory.over(datasetLifecycle);
        bcTermLookup = new KognioRdfTermLookup(datasetLifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void bcResolvesATermWrittenByTheRealUbiquitousLanguageOutAdapter() {
        TermId id = new TermId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
        Term term = new Term(id, new TermCode("TERM-1"), "Kunde",
                "Person, die eine Bestellung aufgibt.", null);

        ulTermRepository.create(PROJECT, term, null);

        ResourceId resolved = bcTermLookup.resolveByCode(PROJECT, "TERM-1");

        assertEquals(id.value(), resolved);
    }
}
