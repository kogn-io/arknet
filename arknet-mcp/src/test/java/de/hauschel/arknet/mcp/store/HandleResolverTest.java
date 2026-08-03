// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepositoryFactory;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Unit tests for {@link HandleResolver#resolve(ProjectId, String)}'s handle contract
 * (CURIE/IRI-first, bare-id fallback via {@code dcterms:identifier}), seeded through the real
 * requirements/ubiquitous-language out-adapters against a real kognio-rdf store - never
 * hand-written triples.
 */
class HandleResolverTest {

    private static final ProjectId PROJECT = new ProjectId("handle-resolver-test");
    private static final String FR_1_IRI = "https://w3id.org/arknet/id/handle-resolver-test-fr-1";
    private static final String TERM_1_IRI = "https://w3id.org/arknet/id/handle-resolver-test-term-1";
    private static final String DUPLICATE_TERM_IRI = "https://w3id.org/arknet/id/handle-resolver-test-term-dup";

    @TempDir
    Path storageDir;

    private DatasetLifecycle lifecycle;
    private HandleResolver handleResolver;

    @BeforeEach
    void setUp() {
        lifecycle = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        RequirementRepository requirements =
                KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
        TermRepository terms = KognioRdfTermRepositoryFactory.over(lifecycle);

        requirements.create(PROJECT, new Requirement(
                new RequirementId(ResourceId.of(FR_1_IRI)), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null,
                List.of(), List.of("Login succeeds with valid credentials")), null);
        terms.create(PROJECT, new Term(
                new TermId(ResourceId.of(TERM_1_IRI)), new TermCode("TERM-1"), "Anmeldung",
                "The act of proving one's identity.", null), null);
        // Same dcterms:identifier as FR-1, minted by a different bounded context - the
        // cross-context ambiguity resolve() must reject rather than guess (issue #103/#14).
        terms.create(PROJECT, new Term(
                new TermId(ResourceId.of(DUPLICATE_TERM_IRI)), new TermCode("FR-1"), "Doppelter Code",
                "Shares FR-1's business code on purpose.", null), null);

        handleResolver = new HandleResolver(new StoreReader(lifecycle), Prefixes.defaults());
    }

    @AfterEach
    void tearDown() {
        lifecycle.close(new DatasetId(PROJECT.value()));
    }

    @Test
    void resolvesACurieToItsAbsoluteIri() {
        assertThat(handleResolver.resolve(PROJECT, "term:TERM-1")).isEqualTo(
                "https://w3id.org/arknet/model/term/TERM-1");
    }

    @Test
    void resolvesABareBusinessIdViaDctermsIdentifier() {
        assertThat(handleResolver.resolve(PROJECT, "TERM-1")).isEqualTo(TERM_1_IRI);
    }

    @Test
    void rejectsABareIdThatMatchesResourcesInSeveralBoundedContexts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> handleResolver.resolve(PROJECT, "FR-1"))
                .withMessageContaining("Ambiguous id 'FR-1'")
                .withMessageContaining(FR_1_IRI)
                .withMessageContaining(DUPLICATE_TERM_IRI);
    }

    /**
     * Regression test for issue #136's remaining gap: {@code store_overview} advertises a
     * store-first, blank-node-subject resource's drill-down as {@code resource_get("_:...")}
     * (see {@code StoreReaderTest}). Before this fix, that exact handle - it contains a colon but
     * no {@code "://"} - fell into the "unknown prefix" branch and was rejected, even though it
     * is not a CURIE at all. A blank-node reference is already the handle {@link StoreReader}
     * expects, so it must resolve to itself rather than through prefix expansion.
     */
    @Test
    void resolvesABlankNodeReferenceToItself() {
        assertThat(handleResolver.resolve(PROJECT, "_:b1")).isEqualTo("_:b1");
    }
}
