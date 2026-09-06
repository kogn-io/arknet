// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;

import de.hauschel.arknet.actor.adapter.kogniordf.KognioRdfActorRepositoryFactory;
import de.hauschel.arknet.actor.application.port.out.ActorRepository;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorId;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfBoundedContextRepositoryFactory;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfContextRelationshipRepositoryFactory;
import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.application.port.out.ContextRelationshipRepository;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.ContextRelationship;
import de.hauschel.arknet.bc.domain.ContextRelationshipId;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcp.store.StoreReader;
import de.hauschel.arknet.mcp.store.StoreSnapshot;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfConstraintRepositoryFactory;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintId;
import de.hauschel.arknet.req.domain.ConstraintRef;
import de.hauschel.arknet.req.domain.ConstraintType;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.AcceptanceCriterion;
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
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Lower-level, pure-graph tests: a real kognio-rdf store, seeded through the real repositories
 * of all three bounded contexts (never hand-written triples), then queried through {@link
 * TraceabilityGraph} directly - no MCP/rendering layer involved.
 *
 * <p>Split into two nested classes by how each test uses the fixture (issue #367): {@link
 * BaseFixtureQueries} builds the base fixture exactly once for all its purely-reading tests,
 * while {@link FixtureExtendingQueries} keeps the original per-test fixture for the tests that
 * add their own resources on top of it.
 */
class TraceabilityGraphTest {

    private static final ProjectId PROJECT = new ProjectId("trace-graph-test");

    private static final String TERM_1_IRI = "https://w3id.org/arknet/id/trace-test-term-1";
    private static final String TERM_2_IRI = "https://w3id.org/arknet/id/trace-test-term-2";
    private static final String TERM_4_IRI = "https://w3id.org/arknet/id/trace-test-term-4";
    private static final String TERM_5_IRI = "https://w3id.org/arknet/id/trace-test-term-5";
    private static final String ACTOR_IRI = "https://w3id.org/arknet/id/trace-test-actor";
    private static final String FR_1_IRI = "https://w3id.org/arknet/id/trace-test-fr-1";
    private static final String FR_2_IRI = "https://w3id.org/arknet/id/trace-test-fr-2";
    private static final String UC_1_IRI = "https://w3id.org/arknet/id/trace-test-uc-1";
    private static final String UC_2_IRI = "https://w3id.org/arknet/id/trace-test-uc-2";
    private static final String BC_1_IRI = "https://w3id.org/arknet/id/trace-test-bc-1";
    private static final String CON_1_IRI = "https://w3id.org/arknet/id/trace-test-con-1";
    private static final String CON_2_IRI = "https://w3id.org/arknet/id/trace-test-con-2";
    private static final String CON_3_IRI = "https://w3id.org/arknet/id/trace-test-con-3";

    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String DCTERMS_DESCRIPTION = "http://purl.org/dc/terms/description";

    /**
     * Builds the shared base fixture (CON-1..3, TERM-1/2/4/5, the actor, FR-1/2, UC1, BC-1) into
     * {@code lifecycle} and returns the resulting graph - shared verbatim between {@link
     * BaseFixtureQueries#setUp()} (built once) and {@link FixtureExtendingQueries#setUp()} (built
     * per test), so the two nested classes cannot drift apart on what "the base fixture" means.
     */
    private static TraceabilityGraph buildBaseFixture(DatasetLifecycle lifecycle) {
        RequirementRepository requirements =
                KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
        TermRepository terms = KognioRdfTermRepositoryFactory.over(lifecycle);
        UseCaseRepository useCases = KognioRdfUseCaseRepositoryFactory.over(
                lifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT);
        WriteFunnel requirementsFunnel = KognioRdfRequirementRepositoryFactory.buildFunnel(
                lifecycle, DisplayLocale.DEFAULT);
        ConstraintRepository constraints = KognioRdfConstraintRepositoryFactory.over(
                lifecycle, DisplayLocale.DEFAULT, requirementsFunnel);
        ActorRepository actors = KognioRdfActorRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);

        // CON-1: bound to FR-1 via constrainedBy. CON-2: never referenced (orphan, issue #223).
        // CON-3: bound ONLY to UC1 via constrainedBy (issue #329) - never to any requirement.
        constraints.create(PROJECT, new Constraint(new ConstraintId(ResourceId.of(CON_1_IRI)),
                new ConstraintCode("CON-1"), "JVM only", "Must run on the JVM.", ConstraintType.TECHNICAL), "en");
        constraints.create(PROJECT, new Constraint(new ConstraintId(ResourceId.of(CON_2_IRI)),
                new ConstraintCode("CON-2"), "Budget cap", "Total spend must not exceed the approved budget.",
                ConstraintType.BUSINESS), "en");
        constraints.create(PROJECT, new Constraint(new ConstraintId(ResourceId.of(CON_3_IRI)),
                new ConstraintCode("CON-3"), "Accessibility", "Must meet WCAG AA.", ConstraintType.REGULATORY),
                "en");

        // TERM-1: used by FR-1. TERM-2: never referenced (orphan).
        terms.create(PROJECT, new Term(
                new TermId(ResourceId.of(TERM_1_IRI)), new TermCode("TERM-1"), "Anmeldung",
                "The act of proving one's identity.", null), null);
        terms.create(PROJECT, new Term(
                new TermId(ResourceId.of(TERM_2_IRI)), new TermCode("TERM-2"), "Passwort",
                "A secret credential.", null), null);
        // Actor (since issue #336, its own resource type in arknet-actor's register, no longer a
        // glossary term): never usesTerm'd but referenced as UC1's primary actor - must show up
        // in actorIris() independent of that reference (issue #147).
        actors.create(PROJECT, new Actor(
                new ActorId(ResourceId.of(ACTOR_IRI)), new ActorCode("ACTOR-1"), ActorType.HUMAN, "Customer", null));
        // TERM-4: never usesTerm'd, referenced only through BC-1's ubiquitousLanguageTerm edge -
        // must NOT count as an orphan term either.
        terms.create(PROJECT, new Term(
                new TermId(ResourceId.of(TERM_4_IRI)), new TermCode("TERM-4"), "Vertrag",
                "A binding agreement.", null), null);
        // TERM-5: used ONLY by UC1's own arkreq:usesTerm edge (issue #329) - never by a
        // requirement - must NOT count as an orphan term either.
        terms.create(PROJECT, new Term(
                new TermId(ResourceId.of(TERM_5_IRI)), new TermCode("TERM-5"), "Warenkorb",
                "Where selected items are held before checkout.", null), null);

        // FR-1: uses TERM-1, realised by UC1. FR-2: uses nothing, realised by nothing (orphan).
        requirements.create(PROJECT, new Requirement(
                new RequirementId(ResourceId.of(FR_1_IRI)), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null,
                List.of(new TermRef(ResourceId.of(TERM_1_IRI))),
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")),
                List.of(new ConstraintRef(ResourceId.of(CON_1_IRI)))), null);
        requirements.create(PROJECT, new Requirement(
                new RequirementId(ResourceId.of(FR_2_IRI)), new RequirementCode("FR-2"), "Logout",
                "The system shall let a user log out.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null,
                List.of(), List.of(new AcceptanceCriterion(1, "Logout succeeds")), List.of()), null);

        // UC1 additionally uses TERM-5 and is bound by CON-3 (issue #329) - neither edge exists
        // on any requirement, so isReferencedTerm(TERM-5)/isConstraintReferenced(CON-3) are only
        // true if the predicate-based traversal is genuinely subject-agnostic.
        useCases.create(PROJECT, new UseCase(
                new UseCaseId(ResourceId.of(UC_1_IRI)), new UseCaseCode("UC1"), "Log in",
                "Customer authenticates", null, null,
                new ActorRef(ResourceId.of(ACTOR_IRI)), List.of(), null, null,
                List.of(new Step(1, "Customer enters credentials",
                        List.of(new RequirementRef(ResourceId.of(FR_1_IRI))))),
                List.of(),
                List.of(new de.hauschel.arknet.uc.domain.TermRef(ResourceId.of(TERM_5_IRI))),
                List.of(new de.hauschel.arknet.uc.domain.ConstraintRef(ResourceId.of(CON_3_IRI)))), null);

        // BC-1: links TERM-4 via ubiquitousLanguageTerm, its own vision text does not name it -
        // graph-level accessors are what is under test here, text-mention matching is a
        // TraceabilityRenderer concern (see TraceabilityRendererTest).
        BoundedContextRepository boundedContexts = KognioRdfBoundedContextRepositoryFactory.over(
                lifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT);
        boundedContexts.create(PROJECT, new BoundedContext(
                new BoundedContextId(ResourceId.of(BC_1_IRI)), new BoundedContextCode("BC-1"), "Ordering",
                "Wir verarbeiten Bestellungen.", null, null,
                List.of(new de.hauschel.arknet.bc.domain.TermRef(ResourceId.of(TERM_4_IRI)))));

        StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);
        return TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT);
    }

    /**
     * The 41 tests that only read from the base fixture, never adding anything to the store -
     * they share one instance of the fixture, built once in {@link #setUp()}, instead of paying
     * for a fresh on-disk store per test (issue #367).
     */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BaseFixtureQueries {

        private DatasetLifecycle lifecycle;
        private TraceabilityGraph graph;

        @BeforeAll
        void setUp(@TempDir Path storageDir) {
            lifecycle = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
            graph = buildBaseFixture(lifecycle);
        }

        @AfterAll
        void tearDown() {
            lifecycle.close(new DatasetId(PROJECT.value()));
        }

        @Test
        void requirementIrisContainsBothFunctionalRequirements() {
            assertThat(graph.requirementIris()).containsExactlyInAnyOrder(FR_1_IRI, FR_2_IRI);
        }

        /**
         * Since issue #336 an actor is no longer a glossary term - {@link #ACTOR_IRI} therefore does
         * not appear here any more, unlike before the facet was removed.
         */
        @Test
        void termIrisContainsAllFourConcepts() {
            assertThat(graph.termIris())
                    .containsExactlyInAnyOrder(TERM_1_IRI, TERM_2_IRI, TERM_4_IRI, TERM_5_IRI);
        }

        /**
         * {@code arkreq:usesTerm}'s domain was widened from {@code arkreq:Requirement} alone to a
         * union with {@code arkreq:UseCase} (issue #329) - the predicate-based traversal
         * {@link TraceabilityGraph#isReferencedTerm(String)} already ignores the subject's own type,
         * so a term used only by a use case must already count as referenced without any traversal
         * change.
         */
        @Test
        void isReferencedTermIsTrueForATermUsedOnlyByAUseCase() {
            assertThat(graph.isReferencedTerm(TERM_5_IRI)).isTrue();
        }

        @Test
        void usedTermsOfUc1ContainsTerm5() {
            assertThat(graph.usedTerms(UC_1_IRI)).containsExactly(TERM_5_IRI);
        }

        @Test
        void dependentsOfTerm5ReachesUc1Directly() {
            assertThat(graph.dependents(TERM_5_IRI)).containsExactly(UC_1_IRI);
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
        void isReferencedTermIsTrueForTheUsedTerm() {
            assertThat(graph.isReferencedTerm(TERM_1_IRI)).isTrue();
        }

        @Test
        void isReferencedTermIsTrueForTheActor() {
            assertThat(graph.isReferencedTerm(ACTOR_IRI)).isTrue();
        }

        @Test
        void isReferencedTermIsFalseForTheOrphanTerm() {
            assertThat(graph.isReferencedTerm(TERM_2_IRI)).isFalse();
        }

        /** A term linked only through a bounded context's ubiquitous language is not orphaned either. */
        @Test
        void isReferencedTermIsTrueForATermLinkedOnlyThroughABoundedContext() {
            assertThat(graph.isReferencedTerm(TERM_4_IRI)).isTrue();
        }

        @Test
        void broaderTermOfATermWithNoBroaderIsEmpty() {
            assertThat(graph.broaderTerm(TERM_1_IRI)).isEmpty();
        }

        @Test
        void termProseTextsOfATermContainsItsDefinition() {
            assertThat(graph.termProseTexts(TERM_1_IRI)).containsExactly("The act of proving one's identity.");
        }

        @Test
        void boundedContextIrisContainsBc1() {
            assertThat(graph.boundedContextIris()).containsExactly(BC_1_IRI);
        }

        @Test
        void linkedTermsOfBc1ContainsTerm4() {
            assertThat(graph.linkedTerms(BC_1_IRI)).containsExactly(TERM_4_IRI);
        }

        @Test
        void boundedContextProseTextsOfBc1ContainsItsDomainVision() {
            assertThat(graph.boundedContextProseTexts(BC_1_IRI)).containsExactly("Wir verarbeiten Bestellungen.");
        }

        @Test
        void requirementProseTextsOfFr1ContainsDescriptionAndAcceptanceCriteria() {
            assertThat(graph.requirementProseTexts(FR_1_IRI)).containsExactlyInAnyOrder(
                    "The system shall authenticate a user.", "Login succeeds with valid credentials");
        }

        @Test
        void termLabelsMapsEveryTermIriToItsPrefLabel() {
            // ACTOR_IRI is deliberately absent: since issue #336 it is registered in arknet-actor's
            // own register, no longer a glossary term.
            assertThat(graph.termLabels()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    TERM_1_IRI, "Anmeldung", TERM_2_IRI, "Passwort", TERM_4_IRI, "Vertrag",
                    TERM_5_IRI, "Warenkorb"));
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

        /** {@code arkddd:ubiquitousLanguageTerm} must be a traversable dependent edge too. */
        @Test
        void dependentsOfTerm4ReachesBc1ViaTheUbiquitousLanguageTermEdge() {
            assertThat(graph.dependents(TERM_4_IRI)).containsExactly(BC_1_IRI);
        }

        @Test
        void dependentsOfFr2IsEmpty() {
            assertThat(graph.dependents(FR_2_IRI)).isEmpty();
        }

        @Test
        void dependentsOfTheOrphanTermIsEmpty() {
            assertThat(graph.dependents(TERM_2_IRI)).isEmpty();
        }

        @Test
        void constraintIrisContainsAllThreeConstraints() {
            assertThat(graph.constraintIris()).containsExactlyInAnyOrder(CON_1_IRI, CON_2_IRI, CON_3_IRI);
        }

        /**
         * {@code oslc_rm:constrainedBy}'s subject was never restricted to {@code arkreq:Requirement}
         * at the ontology level (no {@code rdfs:domain} at all) - {@code uc_link_constraint}
         * (issue #329) is the first writer to actually use a {@code UseCase} subject. The
         * predicate-based traversal already ignores the subject's own type, so a constraint bound
         * only by a use case must already count as referenced without any traversal change.
         */
        @Test
        void isConstraintReferencedIsTrueForAConstraintBoundOnlyByAUseCase() {
            assertThat(graph.isConstraintReferenced(CON_3_IRI)).isTrue();
        }

        @Test
        void dependentsOfCon3ReachesUc1Directly() {
            assertThat(graph.dependents(CON_3_IRI)).containsExactly(UC_1_IRI);
        }

        @Test
        void isConstraintReferencedIsTrueForTheLinkedConstraint() {
            assertThat(graph.isConstraintReferenced(CON_1_IRI)).isTrue();
        }

        @Test
        void isConstraintReferencedIsFalseForTheOrphanConstraint() {
            assertThat(graph.isConstraintReferenced(CON_2_IRI)).isFalse();
        }

        /**
         * {@code oslc_rm:constrainedBy} must be a traversable dependent edge too (issue #223) - and,
         * like {@link #dependentsOfTerm1TransitivelyReachesFr1AndUc1ButNotTheStep}, the closure
         * continues transitively from FR-1 to UC-1 (which realises FR-1 via the step hop).
         */
        @Test
        void dependentsOfTheConstraintTransitivelyReachesFr1AndUc1() {
            assertThat(graph.dependents(CON_1_IRI)).containsExactlyInAnyOrder(FR_1_IRI, UC_1_IRI);
        }

        @Test
        void dependentsOfTheOrphanConstraintIsEmpty() {
            assertThat(graph.dependents(CON_2_IRI)).isEmpty();
        }

        @Test
        void useCaseIrisContainsUc1() {
            assertThat(graph.useCaseIris()).containsExactly(UC_1_IRI);
        }

        /**
         * Since issue #336 {@link #ACTOR_IRI} is registered in {@code arknet-actor}'s own register
         * rather than written as a term - {@link TraceabilityGraph#actorIris()} is graph-agnostic, so
         * it finds it there exactly as it used to find the old term-facetted actor.
         */
        @Test
        void actorIrisContainsTheRegisteredActor() {
            assertThat(graph.actorIris()).containsExactly(ACTOR_IRI);
        }

        @Test
        void actorsOfUc1ContainsTheActor() {
            assertThat(graph.actorsOf(UC_1_IRI)).containsExactly(ACTOR_IRI);
        }

        @Test
        void useCasesOfTheActorContainsUc1() {
            assertThat(graph.useCasesOf(ACTOR_IRI)).containsExactly(UC_1_IRI);
        }

        @Test
        void useCasesOfATermThatIsNeverAnActorIsEmpty() {
            assertThat(graph.useCasesOf(TERM_1_IRI)).isEmpty();
        }

        /**
         * UC1's only populated prose fields are the goal and its single main-flow step (issue #333
         * widened {@link TraceabilityGraph#useCaseProseTexts(String)} beyond just the goal).
         */
        @Test
        void useCaseProseTextsOfUc1ContainsItsGoalAndStepText() {
            assertThat(graph.useCaseProseTexts(UC_1_IRI))
                    .containsExactlyInAnyOrder("Customer authenticates", "Customer enters credentials");
        }

        /**
         * UC1's goal names its own primary actor ("Customer authenticates" mentions {@code Customer},
         * the actor {@link #ACTOR_IRI} is registered under). Since issue #336 an actor is no longer a
         * glossary term by default, so {@code Customer} is not even a mention candidate here any
         * more - this now holds vacuously rather than by the {@code actorsOf(useCaseIri)} suppression
         * {@link TraceabilityGraph#unlinkedMentions()} still applies. That suppression remains live
         * for the (still legal) case of a resource that is both a registered actor and a separately
         * registered glossary term sharing the same label - not pinned by this test.
         */
        @Test
        void unlinkedMentionsDoesNotFlagAUseCasesOwnPrimaryActorMentionInItsGoal() {
            assertThat(graph.unlinkedMentions())
                    .filteredOn(mention -> mention.sourceIri().equals(UC_1_IRI))
                    .isEmpty();
        }

        @Test
        void knowsIsTrueForEveryResourceWithAtLeastOneStatement() {
            assertThat(graph.knows(FR_1_IRI)).isTrue();
            assertThat(graph.knows(TERM_2_IRI)).isTrue();
            assertThat(graph.knows(BC_1_IRI)).isTrue();
        }

        @Test
        void knowsIsFalseForAnIriTheStoreNeverWrote() {
            assertThat(graph.knows("https://w3id.org/arknet/id/never-written")).isFalse();
        }
    }

    /**
     * The 12 tests that add their own resources on top of the base fixture and then re-read a
     * freshly-built graph - each keeps its own store, rebuilt per test exactly as before issue
     * #367, since a shared fixture would leak one test's added resources into the next (a real
     * collision already existed: two tests independently created a resource under the same {@link
     * #UC_2_IRI}, which only isolation, not a shared store, can tolerate).
     */
    @Nested
    class FixtureExtendingQueries {

        @TempDir
        Path storageDir;

        private DatasetLifecycle lifecycle;
        private TraceabilityGraph graph;

        @BeforeEach
        void setUp() {
            lifecycle = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
            graph = buildBaseFixture(lifecycle);
        }

        @AfterEach
        void tearDown() {
            lifecycle.close(new DatasetId(PROJECT.value()));
        }

        /**
         * Issue #252: an interior/root taxonomy term must stop being reported as an orphan once
         * something is hung under it via {@code skos:broader}, even though nothing else references it.
         */
        @Test
        void isReferencedTermIsTrueForATermThatIsAnotherTermsBroaderTerm() {
            String broaderIri = "https://w3id.org/arknet/id/trace-test-broader-parent";
            String narrowerIri = "https://w3id.org/arknet/id/trace-test-broader-child";
            seedTermWithBroader(broaderIri, "TERM-BROADER-1", "Actor", "Someone or something acting.", null);
            seedTermWithBroader(narrowerIri, "TERM-BROADER-2", "Human Actor", "A human acting.", broaderIri);
            StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);
            TraceabilityGraph freshGraph = TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT);

            assertThat(freshGraph.isReferencedTerm(broaderIri)).isTrue();
        }

        /**
         * kogn-io/arknet#420: the symmetric relation is written in one direction only, so the term
         * at the receiving end must stop counting as an orphan without asserting anything itself.
         */
        @Test
        void isReferencedTermIsTrueForATermThatIsAnotherTermsRelatedPeer() {
            String peerIri = "https://w3id.org/arknet/id/trace-test-related-peer";
            String namerIri = "https://w3id.org/arknet/id/trace-test-related-namer";
            seedTermWithRelated(peerIri, "TERM-REL-1", "Projekt", "Ein Vorhaben.", null);
            seedTermWithRelated(namerIri, "TERM-REL-2", "Anker", "Ein opakes Merkmal.", peerIri);
            StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);
            TraceabilityGraph freshGraph = TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT);

            assertThat(freshGraph.isReferencedTerm(peerIri)).isTrue();
        }

        /** Both ends of the one asserted edge see the peer - that is what "symmetric" has to mean here. */
        @Test
        void relatedTermsReadsBothDirectionsOfTheOneAssertedEdge() {
            String peerIri = "https://w3id.org/arknet/id/trace-test-relatedterms-peer";
            String namerIri = "https://w3id.org/arknet/id/trace-test-relatedterms-namer";
            seedTermWithRelated(peerIri, "TERM-RT-1", "Projekt", "Ein Vorhaben.", null);
            seedTermWithRelated(namerIri, "TERM-RT-2", "Anker", "Ein opakes Merkmal.", peerIri);
            StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);
            TraceabilityGraph freshGraph = TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT);

            assertThat(freshGraph.relatedTerms(namerIri)).containsExactly(peerIri);
            assertThat(freshGraph.relatedTerms(peerIri)).containsExactly(namerIri);
        }

        /**
         * The {@code skos:related} counterpart of
         * {@link #unlinkedMentionsDoesNotFlagATermsMentionOfItsOwnBroaderTerm()}: a term whose
         * definition names a peer it is already related to has recorded that link, so the mention is
         * not unlinked - and the edge counts from either end, since only one is asserted.
         */
        @Test
        void unlinkedMentionsDoesNotFlagATermsMentionOfARelatedPeer() {
            String peerIri = "https://w3id.org/arknet/id/trace-test-mention-related-peer";
            String namerIri = "https://w3id.org/arknet/id/trace-test-mention-related-namer";
            seedTermWithRelated(peerIri, "TERM-MR-1", "Projekt", "Wird durch einen Anker identifiziert.", null);
            seedTermWithRelated(namerIri, "TERM-MR-2", "Anker", "Identifiziert ein Projekt.", peerIri);
            StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);
            TraceabilityGraph freshGraph = TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT);

            assertThat(freshGraph.unlinkedMentions())
                    .noneMatch(mention -> mention.sourceIri().equals(namerIri)
                            || mention.sourceIri().equals(peerIri));
        }

        @Test
        void broaderTermOfATermWithABroaderReturnsTheTarget() {
            String broaderIri = "https://w3id.org/arknet/id/trace-test-broadertermof-parent";
            String narrowerIri = "https://w3id.org/arknet/id/trace-test-broadertermof-child";
            seedTermWithBroader(broaderIri, "TERM-BT-1", "Actor", "Someone or something acting.", null);
            seedTermWithBroader(narrowerIri, "TERM-BT-2", "Human Actor", "A human acting.", broaderIri);
            StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);
            TraceabilityGraph freshGraph = TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT);

            assertThat(freshGraph.broaderTerm(narrowerIri)).contains(broaderIri);
        }

        /**
         * Issue #252's positive case: a term's definition commonly mentions its own broader term's
         * label without that being an accident ("A Human Actor is an Actor who ..."), so
         * {@link TraceabilityGraph#unlinkedMentions()} must not flag it.
         */
        @Test
        void unlinkedMentionsDoesNotFlagATermsMentionOfItsOwnBroaderTerm() {
            String broaderIri = "https://w3id.org/arknet/id/trace-test-mention-broader-parent";
            String narrowerIri = "https://w3id.org/arknet/id/trace-test-mention-broader-child";
            seedTermWithBroader(broaderIri, "TERM-MB-1", "Actor", "Someone or something acting.", null);
            seedTermWithBroader(narrowerIri, "TERM-MB-2", "Human Actor", "A human Actor who buys.", broaderIri);
            StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);
            TraceabilityGraph freshGraph = TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT);

            assertThat(freshGraph.unlinkedMentions())
                    .filteredOn(mention -> mention.sourceIri().equals(narrowerIri) && mention.termIri().equals(broaderIri))
                    .isEmpty();
        }

        /**
         * Issue #252's negative case: a term's definition mentioning some <em>other</em> glossary
         * term - not its own broader term - is exactly the kind of unlinked mention
         * {@code orphan_check} should surface, with {@code "broader"} as the missing edge's name.
         */
        @Test
        void unlinkedMentionsFlagsATermsMentionOfAnotherTermThatIsNotItsBroaderTerm() {
            // Since issue #336 the actor is no longer a term itself, so this regression needs its own,
            // unrelated glossary term to be mentioned - it used to reuse ACTOR_IRI's old "Customer"
            // term facet for that.
            String otherTermIri = "https://w3id.org/arknet/id/trace-test-unrelated-customer-term";
            seedTermWithBroader(otherTermIri, "TERM-MU-0", "Customer", "Someone who buys.", null);
            String mentioningIri = "https://w3id.org/arknet/id/trace-test-mention-unlinked";
            seedTermWithBroader(mentioningIri, "TERM-MU-1", "Regular Customer",
                    "A Customer who orders repeatedly.", null);
            StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);
            TraceabilityGraph freshGraph = TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT);

            assertThat(freshGraph.unlinkedMentions())
                    .filteredOn(mention -> mention.sourceIri().equals(mentioningIri))
                    .extracting(TraceabilityGraph.UnlinkedMention::termIri, TraceabilityGraph.UnlinkedMention::edgeLocalName)
                    .containsExactly(org.assertj.core.api.Assertions.tuple(otherTermIri, "broader"));
        }

        /**
         * Regression test for issue #141: a term with several language-tagged {@code skos:prefLabel}s
         * (SKOS textbook) used to resolve to whichever literal {@link
         * de.hauschel.arknet.mcp.store.StoreResource#label(de.hauschel.arknet.kernel.DisplayLocale)}'s
         * predecessor happened to read first - independent of the {@code displayLocale} the caller
         * asked for, and disagreeing with the HTML report, which resolves the very same multi-language
         * term through {@code report.Glossary}'s {@code DisplayLocale}-selected {@code Term::prefLabel}.
         * Two graphs built from the same snapshot with different requested languages must now pick the
         * matching literal each time.
         */
        @Test
        void termLabelsSelectsTheLiteralMatchingEachGraphsRequestedLanguage() {
            String multilingualTermIri = "https://w3id.org/arknet/id/trace-test-term-multilingual";
            seedMultilingualPrefLabel(multilingualTermIri, "Customer", "en", "Kunde", "de");
            StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);

            TraceabilityGraph germanGraph = TraceabilityGraph.of(
                    snapshot, new DisplayLocale(Locale.GERMAN, Locale.ENGLISH));
            TraceabilityGraph englishGraph = TraceabilityGraph.of(
                    snapshot, new DisplayLocale(Locale.ENGLISH, Locale.ENGLISH));

            assertThat(germanGraph.labelOf(multilingualTermIri)).contains("Kunde");
            assertThat(englishGraph.labelOf(multilingualTermIri)).contains("Customer");
        }

        /**
         * Regression test for issue #141's user-visible effect: the exact verified scenario from the
         * issue report. A requirement's text names a term only under its German label; with a German
         * {@code displayLocale}, {@code orphan_check}'s {@link TraceabilityGraph#unlinkedMentions()}
         * must find that mention - matching against {@code label()}'s English literal (as the
         * pre-fix, locale-blind code could pick) would silently miss it.
         */
        @Test
        void unlinkedMentionsFindsAGermanMentionOfATermWhoseOtherLabelIsEnglish() {
            String multilingualTermIri = "https://w3id.org/arknet/id/trace-test-term-multilingual";
            seedMultilingualPrefLabel(multilingualTermIri, "Customer", "en", "Kunde", "de");
            seedRequirementDescription(FR_2_IRI, "Der Kunde meldet sich ab.");
            StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);

            TraceabilityGraph germanGraph = TraceabilityGraph.of(
                    snapshot, new DisplayLocale(Locale.GERMAN, Locale.ENGLISH));

            assertThat(germanGraph.unlinkedMentions())
                    .extracting(TraceabilityGraph.UnlinkedMention::termIri)
                    .contains(multilingualTermIri);
        }

        /** Writes a fresh {@code skos:Concept} with two language-tagged {@code skos:prefLabel}s directly. */
        private void seedMultilingualPrefLabel(
                String termIri, String labelA, String languageA, String labelB, String languageB) {
            RDF rdf = new SimpleRdf();
            Graph graph = rdf.createGraph();
            IRI term = rdf.createIRI(termIri);
            graph.add(term, rdf.createIRI(RDF_TYPE), rdf.createIRI("http://www.w3.org/2004/02/skos/core#Concept"));
            graph.add(term, rdf.createIRI("http://purl.org/dc/terms/identifier"), rdf.createLiteral("TERM-ML"));
            graph.add(term, rdf.createIRI("http://www.w3.org/2004/02/skos/core#prefLabel"),
                    rdf.createLiteral(labelA, languageA));
            graph.add(term, rdf.createIRI("http://www.w3.org/2004/02/skos/core#prefLabel"),
                    rdf.createLiteral(labelB, languageB));
            try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
                handle.transactor().inTransaction(tx -> {
                    tx.add(rdf.createIRI("https://w3id.org/arknet/id/trace-test-multilingual-graph"), graph);
                    return null;
                });
            }
        }

        /**
         * Writes a fresh {@code skos:Concept} with a code, {@code skos:prefLabel}, {@code
         * skos:definition} and, if {@code broaderIri} is non-{@code null}, a {@code skos:broader}
         * edge to it - directly, bypassing {@link de.hauschel.arknet.ul.application.TermService} and
         * its cycle protection, since these graph-level tests only need the resulting triples, not
         * the write path's own validation (issue #252).
         */
        private void seedTermWithBroader(String termIri, String code, String prefLabel, String definition,
                String broaderIri) {
            RDF rdf = new SimpleRdf();
            Graph graph = rdf.createGraph();
            IRI term = rdf.createIRI(termIri);
            graph.add(term, rdf.createIRI(RDF_TYPE), rdf.createIRI("http://www.w3.org/2004/02/skos/core#Concept"));
            graph.add(term, rdf.createIRI("http://purl.org/dc/terms/identifier"), rdf.createLiteral(code));
            graph.add(term, rdf.createIRI("http://www.w3.org/2004/02/skos/core#prefLabel"), rdf.createLiteral(prefLabel));
            graph.add(term, rdf.createIRI("http://www.w3.org/2004/02/skos/core#definition"),
                    rdf.createLiteral(definition));
            if (broaderIri != null) {
                graph.add(term, rdf.createIRI("http://www.w3.org/2004/02/skos/core#broader"), rdf.createIRI(broaderIri));
            }
            try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
                handle.transactor().inTransaction(tx -> {
                    tx.add(rdf.createIRI("https://w3id.org/arknet/id/trace-test-broader-graph"), graph);
                    return null;
                });
            }
        }

        /**
         * The {@code skos:related} counterpart of {@link #seedTermWithBroader} - one term, with the
         * forward direction of the symmetric relation asserted on it (kogn-io/arknet#420).
         */
        private void seedTermWithRelated(String termIri, String code, String prefLabel, String definition,
                String relatedIri) {
            RDF rdf = new SimpleRdf();
            Graph graph = rdf.createGraph();
            IRI term = rdf.createIRI(termIri);
            graph.add(term, rdf.createIRI(RDF_TYPE), rdf.createIRI("http://www.w3.org/2004/02/skos/core#Concept"));
            graph.add(term, rdf.createIRI("http://purl.org/dc/terms/identifier"), rdf.createLiteral(code));
            graph.add(term, rdf.createIRI("http://www.w3.org/2004/02/skos/core#prefLabel"),
                    rdf.createLiteral(prefLabel));
            graph.add(term, rdf.createIRI("http://www.w3.org/2004/02/skos/core#definition"),
                    rdf.createLiteral(definition));
            if (relatedIri != null) {
                graph.add(term, rdf.createIRI("http://www.w3.org/2004/02/skos/core#related"),
                        rdf.createIRI(relatedIri));
            }
            try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
                handle.transactor().inTransaction(tx -> {
                    tx.add(rdf.createIRI("https://w3id.org/arknet/id/trace-test-related-graph"), graph);
                    return null;
                });
            }
        }

        /**
         * Registers a fresh actor of {@code type} via the real {@link ActorRepository}, with no
         * {@code primaryActor}/{@code supportingActor} edge from any use case - for {@link
         * #actorIrisIncludesAnActorNoUseCaseReferencesYet()} and its GROUP/LEGAL siblings.
         */
        private void seedActor(String actorIri, ActorCode code, ActorType type, String name) {
            ActorRepository actors = KognioRdfActorRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
            actors.create(PROJECT, new Actor(new ActorId(ResourceId.of(actorIri)), code, type, name, null));
        }

        /** Overwrites a requirement's {@code dcterms:description} with raw prose, for prose-matching tests. */
        private void seedRequirementDescription(String requirementIri, String description) {
            RDF rdf = new SimpleRdf();
            Graph graph = rdf.createGraph();
            graph.add(rdf.createIRI(requirementIri), rdf.createIRI(DCTERMS_DESCRIPTION),
                    rdf.createLiteral(description));
            try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
                handle.transactor().inTransaction(tx -> {
                    tx.add(rdf.createIRI("https://w3id.org/arknet/id/trace-test-description-graph"), graph);
                    return null;
                });
            }
        }

        /**
         * Regression test for issue #293: a {@code bc_link_context}-created {@code
         * arkddd:ContextRelationship} must show up in {@code impact_analysis} on either of the two
         * bounded contexts it names - before the fix, {@code arkddd:upstream}/{@code arkddd:downstream}
         * were missing from {@link TraceabilityGraph}'s {@code DEPENDENT_EDGE_PREDICATES}, so a
         * recorded context-map relationship was invisible to a changed bounded context's impact
         * report. Both directions are checked: the relationship is reported as affected whichever of
         * its two bounded contexts changes.
         */
        @Test
        void dependentsOfEitherBoundedContextReachesTheirContextRelationship() {
            String bc2Iri = "https://w3id.org/arknet/id/trace-test-bc-2";
            String relationshipIri = "https://w3id.org/arknet/id/trace-test-context-relationship";
            BoundedContextRepository boundedContexts = KognioRdfBoundedContextRepositoryFactory.over(
                    lifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT);
            boundedContexts.create(PROJECT, new BoundedContext(
                    new BoundedContextId(ResourceId.of(bc2Iri)), new BoundedContextCode("BC-2"), "Billing",
                    "Wir stellen Rechnungen.", null, null, List.of()));
            ContextRelationshipRepository contextRelationships =
                    KognioRdfContextRelationshipRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
            contextRelationships.create(PROJECT, new ContextRelationship(
                    new ContextRelationshipId(ResourceId.of(relationshipIri)),
                    new BoundedContextId(ResourceId.of(BC_1_IRI)), new BoundedContextId(ResourceId.of(bc2Iri)),
                    RelationshipType.CUSTOMER_SUPPLIER));

            StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);
            TraceabilityGraph freshGraph = TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT);

            assertThat(freshGraph.dependents(BC_1_IRI)).contains(relationshipIri);
            assertThat(freshGraph.dependents(bc2Iri)).contains(relationshipIri);
        }

        /**
         * Regression test for issue #147: {@link TraceabilityGraph#actorIris()} must find an actor
         * from its {@code arkproc:HumanActor}/{@code SystemActor} type alone, independent of whether
         * any use case's {@code primaryActor}/{@code supportingActor} edge references it yet.
         */
        @Test
        void actorIrisIncludesAnActorNoUseCaseReferencesYet() {
            String unreferencedActorIri = "https://w3id.org/arknet/id/trace-test-actor-unreferenced";
            seedActor(unreferencedActorIri, new ActorCode("ACTOR-2"), ActorType.HUMAN, "Auditor");
            StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);

            TraceabilityGraph freshGraph = TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT);

            assertThat(freshGraph.actorIris()).containsExactlyInAnyOrder(ACTOR_IRI, unreferencedActorIri);
            assertThat(freshGraph.useCasesOf(unreferencedActorIri)).isEmpty();
        }

        /** Same regression as {@link #actorIrisIncludesAnActorNoUseCaseReferencesYet()}, for the third actor kind. */
        @Test
        void actorIrisIncludesALegalActorNoUseCaseReferencesYet() {
            String legalActorIri = "https://w3id.org/arknet/id/trace-test-legal-actor-unreferenced";
            seedActor(legalActorIri, new ActorCode("ACTOR-3"), ActorType.LEGAL, "Kunde GmbH");
            StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);

            TraceabilityGraph freshGraph = TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT);

            assertThat(freshGraph.actorIris()).containsExactlyInAnyOrder(ACTOR_IRI, legalActorIri);
        }

        /**
         * Same regression as {@link #actorIrisIncludesAnActorNoUseCaseReferencesYet()}, for the
         * fourth actor kind ({@code GROUP}, issue #336) - {@code arkproc:GroupActor} was missing from
         * {@link TraceabilityGraph#actorIris()}'s type filter before this fix, since it did not exist
         * yet when #147 first pinned the human/system/legal cases.
         */
        @Test
        void actorIrisIncludesAGroupActorNoUseCaseReferencesYet() {
            String groupActorIri = "https://w3id.org/arknet/id/trace-test-group-actor-unreferenced";
            seedActor(groupActorIri, new ActorCode("ACTOR-4"), ActorType.GROUP, "Compliance Team");
            StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);

            TraceabilityGraph freshGraph = TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT);

            assertThat(freshGraph.actorIris()).containsExactlyInAnyOrder(ACTOR_IRI, groupActorIri);
        }

        /**
         * Every optional prose field, every main-flow step and every extension - not just the goal
         * (issue #333).
         */
        @Test
        void useCaseProseTextsCoversEveryOptionalFieldAndEveryStepAndExtension() {
            UseCaseRepository useCases = KognioRdfUseCaseRepositoryFactory.over(
                    lifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT);
            useCases.create(PROJECT, new UseCase(
                    new UseCaseId(ResourceId.of(UC_2_IRI)), new UseCaseCode("UC2"), "Manage cart",
                    "Customer manages the cart", "Checkout subsystem", "Customer opens the cart",
                    new ActorRef(ResourceId.of(ACTOR_IRI)), List.of(), "Cart is empty", "Cart is saved",
                    List.of(new Step(1, "Customer adds an item", List.of())),
                    List.of("Customer cancels"), List.of(), List.of()), null);
            StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);
            TraceabilityGraph freshGraph = TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT);

            assertThat(freshGraph.useCaseProseTexts(UC_2_IRI)).containsExactlyInAnyOrder(
                    "Customer manages the cart", "Checkout subsystem", "Customer opens the cart",
                    "Cart is empty", "Cart is saved", "Customer adds an item", "Customer cancels");
        }

        /**
         * The exact symptom issue #333 exists for: a use-case goal names a glossary term nothing
         * links to.
         */
        @Test
        void unlinkedMentionsFlagsAUseCaseGoalMentionWithNoUsesTermEdge() {
            UseCaseRepository useCases = KognioRdfUseCaseRepositoryFactory.over(
                    lifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT);
            useCases.create(PROJECT, new UseCase(
                    new UseCaseId(ResourceId.of(UC_2_IRI)), new UseCaseCode("UC2"), "Reset",
                    "Customer resets their Passwort", null, null,
                    new ActorRef(ResourceId.of(ACTOR_IRI)), List.of(), null, null,
                    List.of(new Step(1, "Customer confirms", List.of())), List.of(), List.of(), List.of()), null);
            StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);
            TraceabilityGraph freshGraph = TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT);

            assertThat(freshGraph.unlinkedMentions())
                    .filteredOn(mention -> mention.sourceIri().equals(UC_2_IRI))
                    .extracting(TraceabilityGraph.UnlinkedMention::termIri, TraceabilityGraph.UnlinkedMention::edgeLocalName)
                    .containsExactly(org.assertj.core.api.Assertions.tuple(TERM_2_IRI, "usesTerm"));
        }
    }
}
