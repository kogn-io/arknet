// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;

import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfBoundedContextRepositoryFactory;
import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextId;
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

    private static final ProjectId PROJECT = new ProjectId("trace-graph-test");

    private static final String TERM_1_IRI = "https://w3id.org/arknet/id/trace-test-term-1";
    private static final String TERM_2_IRI = "https://w3id.org/arknet/id/trace-test-term-2";
    private static final String TERM_4_IRI = "https://w3id.org/arknet/id/trace-test-term-4";
    private static final String ACTOR_IRI = "https://w3id.org/arknet/id/trace-test-actor";
    private static final String FR_1_IRI = "https://w3id.org/arknet/id/trace-test-fr-1";
    private static final String FR_2_IRI = "https://w3id.org/arknet/id/trace-test-fr-2";
    private static final String UC_1_IRI = "https://w3id.org/arknet/id/trace-test-uc-1";
    private static final String BC_1_IRI = "https://w3id.org/arknet/id/trace-test-bc-1";
    private static final String CON_1_IRI = "https://w3id.org/arknet/id/trace-test-con-1";
    private static final String CON_2_IRI = "https://w3id.org/arknet/id/trace-test-con-2";

    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String DCTERMS_DESCRIPTION = "http://purl.org/dc/terms/description";

    @TempDir
    Path storageDir;

    private DatasetLifecycle lifecycle;
    private TraceabilityGraph graph;

    @BeforeEach
    void setUp() {
        lifecycle = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        RequirementRepository requirements =
                KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
        TermRepository terms = KognioRdfTermRepositoryFactory.over(lifecycle);
        UseCaseRepository useCases = KognioRdfUseCaseRepositoryFactory.over(
                lifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT);
        WriteFunnel requirementsFunnel = KognioRdfRequirementRepositoryFactory.buildFunnel(
                lifecycle, DisplayLocale.DEFAULT);
        ConstraintRepository constraints = KognioRdfConstraintRepositoryFactory.over(lifecycle, requirementsFunnel);

        // CON-1: bound to FR-1 via constrainedBy. CON-2: never referenced (orphan, issue #223).
        constraints.create(PROJECT, new Constraint(new ConstraintId(ResourceId.of(CON_1_IRI)),
                new ConstraintCode("CON-1"), "JVM only", "Must run on the JVM.", ConstraintType.TECHNICAL));
        constraints.create(PROJECT, new Constraint(new ConstraintId(ResourceId.of(CON_2_IRI)),
                new ConstraintCode("CON-2"), "Budget cap", "Total spend must not exceed the approved budget.",
                ConstraintType.BUSINESS));

        // TERM-1: used by FR-1. TERM-2: never referenced (orphan). Actor: never usesTerm'd but
        // referenced as UC1's primary actor - must NOT count as an orphan term.
        terms.create(PROJECT, new Term(
                new TermId(ResourceId.of(TERM_1_IRI)), new TermCode("TERM-1"), "Anmeldung",
                "The act of proving one's identity.", null), null);
        terms.create(PROJECT, new Term(
                new TermId(ResourceId.of(TERM_2_IRI)), new TermCode("TERM-2"), "Passwort",
                "A secret credential.", null), null);
        terms.create(PROJECT, new Term(
                new TermId(ResourceId.of(ACTOR_IRI)), new TermCode("TERM-3"), "Customer",
                "A person placing an order.", new ActorFacet(ActorKind.HUMAN, "orderer")), null);
        // TERM-4: never usesTerm'd, referenced only through BC-1's ubiquitousLanguageTerm edge -
        // must NOT count as an orphan term either.
        terms.create(PROJECT, new Term(
                new TermId(ResourceId.of(TERM_4_IRI)), new TermCode("TERM-4"), "Vertrag",
                "A binding agreement.", null), null);

        // FR-1: uses TERM-1, realised by UC1. FR-2: uses nothing, realised by nothing (orphan).
        requirements.create(PROJECT, new Requirement(
                new RequirementId(ResourceId.of(FR_1_IRI)), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null,
                List.of(new TermRef(ResourceId.of(TERM_1_IRI))),
                List.of("Login succeeds with valid credentials"),
                List.of(new ConstraintRef(ResourceId.of(CON_1_IRI)))), null);
        requirements.create(PROJECT, new Requirement(
                new RequirementId(ResourceId.of(FR_2_IRI)), new RequirementCode("FR-2"), "Logout",
                "The system shall let a user log out.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null,
                List.of(), List.of("Logout succeeds"), List.of()), null);

        useCases.create(PROJECT, new UseCase(
                new UseCaseId(ResourceId.of(UC_1_IRI)), new UseCaseCode("UC1"), "Log in",
                "Customer authenticates", null, null,
                new ActorRef(ResourceId.of(ACTOR_IRI)), List.of(), null, null,
                List.of(new Step(1, "Customer enters credentials",
                        List.of(new RequirementRef(ResourceId.of(FR_1_IRI))))),
                List.of()), null);

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
        graph = TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        lifecycle.close(new DatasetId(PROJECT.value()));
    }

    @Test
    void requirementIrisContainsBothFunctionalRequirements() {
        assertThat(graph.requirementIris()).containsExactlyInAnyOrder(FR_1_IRI, FR_2_IRI);
    }

    @Test
    void termIrisContainsAllFourConceptsIncludingTheActorFacetted() {
        assertThat(graph.termIris()).containsExactlyInAnyOrder(TERM_1_IRI, TERM_2_IRI, ACTOR_IRI, TERM_4_IRI);
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
        assertThat(graph.termLabels()).containsExactlyInAnyOrderEntriesOf(Map.of(
                TERM_1_IRI, "Anmeldung", TERM_2_IRI, "Passwort", ACTOR_IRI, "Customer", TERM_4_IRI, "Vertrag"));
    }

    /**
     * Regression test for issue #141: a term with several language-tagged {@code skos:prefLabel}s
     * (SKOS textbook, ADR-005) used to resolve to whichever literal {@link
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
     * Writes a fresh {@code skos:Concept}, additionally typed {@code arkproc:HumanActor}, with no
     * {@code primaryActor}/{@code supportingActor} edge from any use case - for {@link
     * #actorIrisIncludesAnActorNoUseCaseReferencesYet()}.
     */
    private void seedActorConcept(String actorIri, String label) {
        RDF rdf = new SimpleRdf();
        Graph graph = rdf.createGraph();
        IRI actor = rdf.createIRI(actorIri);
        graph.add(actor, rdf.createIRI(RDF_TYPE), rdf.createIRI("http://www.w3.org/2004/02/skos/core#Concept"));
        graph.add(actor, rdf.createIRI(RDF_TYPE), rdf.createIRI("https://w3id.org/arknet/process#HumanActor"));
        graph.add(actor, rdf.createIRI("http://www.w3.org/2004/02/skos/core#prefLabel"), rdf.createLiteral(label));
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.add(rdf.createIRI("https://w3id.org/arknet/id/trace-test-unreferenced-actor-graph"), graph);
                return null;
            });
        }
    }

    /** Same as {@link #seedActorConcept(String, String)}, but typed {@code arkproc:LegalActor}. */
    private void seedLegalActorConcept(String actorIri, String label) {
        RDF rdf = new SimpleRdf();
        Graph graph = rdf.createGraph();
        IRI actor = rdf.createIRI(actorIri);
        graph.add(actor, rdf.createIRI(RDF_TYPE), rdf.createIRI("http://www.w3.org/2004/02/skos/core#Concept"));
        graph.add(actor, rdf.createIRI(RDF_TYPE), rdf.createIRI("https://w3id.org/arknet/process#LegalActor"));
        graph.add(actor, rdf.createIRI("http://www.w3.org/2004/02/skos/core#prefLabel"), rdf.createLiteral(label));
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.add(rdf.createIRI("https://w3id.org/arknet/id/trace-test-unreferenced-legal-actor-graph"), graph);
                return null;
            });
        }
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
    void constraintIrisContainsBothConstraints() {
        assertThat(graph.constraintIris()).containsExactlyInAnyOrder(CON_1_IRI, CON_2_IRI);
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

    @Test
    void actorIrisContainsTheActorFacettedTerm() {
        assertThat(graph.actorIris()).containsExactly(ACTOR_IRI);
    }

    /**
     * Regression test for issue #147: {@link TraceabilityGraph#actorIris()} must find an actor
     * from its {@code arkproc:HumanActor}/{@code SystemActor} type alone, independent of whether
     * any use case's {@code primaryActor}/{@code supportingActor} edge references it yet.
     */
    @Test
    void actorIrisIncludesAnActorNoUseCaseReferencesYet() {
        String unreferencedActorIri = "https://w3id.org/arknet/id/trace-test-actor-unreferenced";
        seedActorConcept(unreferencedActorIri, "Auditor");
        StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);

        TraceabilityGraph freshGraph = TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT);

        assertThat(freshGraph.actorIris()).containsExactlyInAnyOrder(ACTOR_IRI, unreferencedActorIri);
        assertThat(freshGraph.useCasesOf(unreferencedActorIri)).isEmpty();
    }

    /** Same regression as {@link #actorIrisIncludesAnActorNoUseCaseReferencesYet()}, for the third actor kind. */
    @Test
    void actorIrisIncludesALegalActorNoUseCaseReferencesYet() {
        String legalActorIri = "https://w3id.org/arknet/id/trace-test-legal-actor-unreferenced";
        seedLegalActorConcept(legalActorIri, "Kunde GmbH");
        StoreSnapshot snapshot = new StoreReader(lifecycle).readSnapshot(PROJECT);

        TraceabilityGraph freshGraph = TraceabilityGraph.of(snapshot, DisplayLocale.DEFAULT);

        assertThat(freshGraph.actorIris()).containsExactlyInAnyOrder(ACTOR_IRI, legalActorIri);
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

    @Test
    void useCaseProseTextsOfUc1ContainsItsGoal() {
        assertThat(graph.useCaseProseTexts(UC_1_IRI)).containsExactly("Customer authenticates");
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
