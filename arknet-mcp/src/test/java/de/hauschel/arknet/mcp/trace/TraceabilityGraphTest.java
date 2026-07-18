package de.hauschel.arknet.mcp.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.mcp.store.StoreReader;
import de.hauschel.arknet.mcp.store.StoreSnapshot;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.TermRef;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfUseCaseRepositoryFactory;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseId;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepositoryFactory;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Lower-level, pure-graph tests: a real kognio-rdf store, seeded through the real repositories
 * of all three bounded contexts (never hand-written triples), then queried through {@link
 * TraceabilityGraph} directly - no MCP/rendering layer involved.
 */
class TraceabilityGraphTest {

    private static final WorkspaceId WORKSPACE = new WorkspaceId("trace-graph-test");

    private static final String TERM_1_IRI = "https://w3id.org/arknet/id/trace-test-term-1";
    private static final String TERM_2_IRI = "https://w3id.org/arknet/id/trace-test-term-2";
    private static final String ACTOR_IRI = "https://w3id.org/arknet/id/trace-test-actor";
    private static final String FR_1_IRI = "https://w3id.org/arknet/id/trace-test-fr-1";
    private static final String FR_2_IRI = "https://w3id.org/arknet/id/trace-test-fr-2";
    private static final String UC_1_IRI = "https://w3id.org/arknet/id/trace-test-uc-1";

    @TempDir
    Path storageDir;

    private DatasetLifecycle lifecycle;
    private TraceabilityGraph graph;

    @BeforeEach
    void setUp() {
        lifecycle = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        RequirementRepository requirements = KognioRdfRequirementRepositoryFactory.over(lifecycle);
        TermRepository terms = KognioRdfTermRepositoryFactory.over(lifecycle);
        UseCaseRepository useCases = KognioRdfUseCaseRepositoryFactory.over(lifecycle, new UuidResourceIdFactory());

        // TERM-1: used by FR-1. TERM-2: never referenced (orphan). Actor: never usesTerm'd but
        // referenced as UC1's primary actor - must NOT count as an orphan term.
        terms.create(WORKSPACE, new Term(
                new TermId(ResourceId.of(TERM_1_IRI)), new TermCode("TERM-1"), "Anmeldung",
                "The act of proving one's identity.", null));
        terms.create(WORKSPACE, new Term(
                new TermId(ResourceId.of(TERM_2_IRI)), new TermCode("TERM-2"), "Passwort",
                "A secret credential.", null));
        terms.create(WORKSPACE, new Term(
                new TermId(ResourceId.of(ACTOR_IRI)), new TermCode("TERM-3"), "Customer",
                "A person placing an order.", new ActorFacet(ActorKind.HUMAN, "orderer")));

        // FR-1: uses TERM-1, realised by UC1. FR-2: uses nothing, realised by nothing (orphan).
        requirements.create(WORKSPACE, new Requirement(
                new RequirementId(ResourceId.of(FR_1_IRI)), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null,
                List.of(new TermRef(ResourceId.of(TERM_1_IRI))),
                List.of("Login succeeds with valid credentials")));
        requirements.create(WORKSPACE, new Requirement(
                new RequirementId(ResourceId.of(FR_2_IRI)), new RequirementCode("FR-2"), "Logout",
                "The system shall let a user log out.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null,
                List.of(), List.of("Logout succeeds")));

        useCases.create(WORKSPACE, new UseCase(
                new UseCaseId(ResourceId.of(UC_1_IRI)), new UseCaseCode("UC1"), "Log in",
                "Customer authenticates", null, null,
                new ActorRef(ResourceId.of(ACTOR_IRI)), List.of(), null, null,
                List.of(new Step(1, "Customer enters credentials",
                        List.of(new RequirementRef(ResourceId.of(FR_1_IRI))))),
                List.of()));

        StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(WORKSPACE);
        graph = TraceabilityGraph.of(snapshot);
    }

    @AfterEach
    void tearDown() {
        lifecycle.close(new DatasetId(WORKSPACE.value()));
    }

    @Test
    void requirementIrisContainsBothFunctionalRequirements() {
        assertThat(graph.requirementIris()).containsExactlyInAnyOrder(FR_1_IRI, FR_2_IRI);
    }

    @Test
    void termIrisContainsAllThreeConceptsIncludingTheActorFacetted() {
        assertThat(graph.termIris()).containsExactlyInAnyOrder(TERM_1_IRI, TERM_2_IRI, ACTOR_IRI);
    }

    @Test
    void usedTermsOfFr1ContainsTerm1() {
        assertThat(graph.usedTerms(FR_1_IRI)).containsExactly(TERM_1_IRI);
    }

    @Test
    void usedTermsOfFr2IsEmpty() {
        assertThat(graph.usedTerms(FR_2_IRI)).isEmpty();
    }

    @Test
    void realisingUseCasesOfFr1ContainsUc1ViaTheStepHop() {
        assertThat(graph.realisingUseCases(FR_1_IRI)).containsExactly(UC_1_IRI);
    }

    @Test
    void realisingUseCasesOfFr2IsEmpty() {
        assertThat(graph.realisingUseCases(FR_2_IRI)).isEmpty();
    }

    @Test
    void isReferencedTermIsTrueForTheUsedTermAndTheActor() {
        assertThat(graph.isReferencedTerm(TERM_1_IRI)).isTrue();
        assertThat(graph.isReferencedTerm(ACTOR_IRI)).isTrue();
    }

    @Test
    void isReferencedTermIsFalseForTheOrphanTerm() {
        assertThat(graph.isReferencedTerm(TERM_2_IRI)).isFalse();
    }

    @Test
    void dependentsOfTerm1TransitivelyReachesFr1AndUc1ButNotTheStep() {
        // TERM-1 -(usesTerm)- FR-1 -(stepRealises, backwards through the Step)- UC1.
        assertThat(graph.dependents(TERM_1_IRI)).containsExactlyInAnyOrder(FR_1_IRI, UC_1_IRI);
    }

    @Test
    void dependentsOfTheActorReachesUc1Directly() {
        assertThat(graph.dependents(ACTOR_IRI)).containsExactly(UC_1_IRI);
    }

    @Test
    void dependentsOfFr2IsEmpty() {
        assertThat(graph.dependents(FR_2_IRI)).isEmpty();
    }

    @Test
    void dependentsOfTheOrphanTermIsEmpty() {
        assertThat(graph.dependents(TERM_2_IRI)).isEmpty();
    }
}
