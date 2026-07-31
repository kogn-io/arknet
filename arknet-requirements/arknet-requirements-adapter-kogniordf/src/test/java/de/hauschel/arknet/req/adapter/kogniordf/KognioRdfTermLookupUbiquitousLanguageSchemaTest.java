// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.out.TermLookup;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepositoryFactory;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Issue #109 regression guard: {@link KognioRdfTermLookup} hardcodes the exact storage schema
 * (graph IRI, {@code skos:Concept} type, {@code dcterms:identifier} predicate) that
 * {@code arknet-ubiquitous-language-adapter-kogniordf}'s {@code KognioRdfTermRepository} writes -
 * with no Java import between the two adapter modules that the compiler or ArchUnit could ever
 * catch drift on; the only thing tying the two together used to be a prose code comment.
 *
 * <p>This test is the substitute for that missing compile-time coupling: it writes a term through
 * the ubiquitous-language BC's <em>actual</em> out-adapter and resolves it through the
 * requirements BC's actual {@link KognioRdfTermLookup} against the very same store. If either
 * adapter's private schema ever drifts from the other's, this test - not a silent
 * {@code UnresolvedReferenceException} at runtime for a term that demonstrably exists - is what
 * fails.</p>
 *
 * <p>Deliberately narrower than {@code CrossBoundedContextStoreWiringTest} in {@code arknet-mcp}:
 * that test proves the two contexts share a single {@code DatasetLifecycle} bean through Spring
 * wiring end to end (issue #41), going through the application/service layer.
 * This test isolates the two out-adapters directly - no Spring context, no service layer, no
 * dependency on arknet-mcp - so it is the more precise place to catch a lookup/repository schema
 * mismatch between exactly the two classes the issue names.</p>
 */
class KognioRdfTermLookupUbiquitousLanguageSchemaTest {

    private static final ProjectId WORKSPACE = new ProjectId("req-ul-schema");

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
    private TermLookup reqTermLookup;

    @BeforeEach
    void setUp() {
        lifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        ulTermRepository = KognioRdfTermRepositoryFactory.over(lifecycle);
        reqTermLookup = new KognioRdfTermLookup(lifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void reqResolvesATermWrittenByTheRealUbiquitousLanguageOutAdapter() {
        TermId id = new TermId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
        Term term = new Term(id, new TermCode("TERM-1"), "Kunde",
                "Person, die eine Bestellung aufgibt.", null);

        ulTermRepository.create(WORKSPACE, term);

        ResourceId resolved = reqTermLookup.resolveByCode(WORKSPACE, "TERM-1");

        assertEquals(id.value(), resolved);
    }
}
