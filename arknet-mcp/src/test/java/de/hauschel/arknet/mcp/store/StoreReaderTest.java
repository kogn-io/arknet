// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkprjVocabulary;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RevisionToken;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.AcceptanceCriterion;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;

/**
 * Unit tests for {@link StoreReader#outgoing(ProjectId, String)} and
 * {@link StoreReader#incoming(ProjectId, String)}: they must reject a resource handle that
 * cannot appear unescaped inside a SPARQL {@code IRIREF} instead of splicing it into the query
 * text (SPARQL injection via {@code resource_get}'s {@code id} parameter).
 */
class StoreReaderTest {

    private static final ProjectId PROJECT = new ProjectId("sample-project");
    private static final String FR_1_IRI = "https://w3id.org/arknet/id/store-reader-test-fr-1";
    private static final String PROJECT_IRI = "https://w3id.org/arknet/id/store-reader-test-project";

    /**
     * A handle carrying a payload that, if concatenated unescaped into {@code "<" + iri + ">"},
     * breaks out of the IRIREF and splices live SPARQL syntax into the query (the exact shape
     * originally reported).
     */
    private static final String INJECTION_PAYLOAD =
            "https://x/a> } UNION { ?s ?p ?o . FILTER(1=1) #";

    /**
     * A raw newline is legal in a Java string but SPARQL forbids it unescaped inside a
     * {@code STRING_LITERAL2} - {@link #findByIdentifier} used to hand-roll its escaping and
     * missed exactly this case, so a payload like this broke the query and leaked a raw
     * kognio-rdf/RDF4J exception instead of the handle contract's documented rejection.
     */
    private static final String MULTILINE_IDENTIFIER = "FR-1\nX";

    @TempDir
    Path storageDir;

    private DatasetLifecycle lifecycle;
    private RequirementRepository requirements;
    private StoreReader storeReader;

    @BeforeEach
    void setUp() {
        lifecycle = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        requirements = KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
        requirements.create(PROJECT, requirementTitled("Login"), null);
        storeReader = new StoreReader(lifecycle);
    }

    private static Requirement requirementTitled(String title) {
        return new Requirement(
                new RequirementId(ResourceId.of(FR_1_IRI)), new RequirementCode("FR-1"), title,
                "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null, null,
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
    }

    /** Reads {@code updated}'s current head and immediately applies it through the CAS guard. */
    private void replaceViaCompareAndUpdate(Requirement updated) {
        RevisionToken head = requirements.findCurrentByCode(PROJECT, updated.code())
                .map(RequirementRepository.CurrentRequirement::head)
                .orElse(null);
        requirements.compareAndUpdate(PROJECT, head, updated, null, null, noAcceptanceCriteriaLanguages(updated), null);
    }

    /** An untagged (all-{@code null}) map, covering every position {@code updated} carries. */
    private static Map<Integer, String> noAcceptanceCriteriaLanguages(Requirement updated) {
        Map<Integer, String> languages = new LinkedHashMap<>();
        updated.acceptanceCriteria().forEach(criterion -> languages.put(criterion.position(), null));
        return languages;
    }

    @AfterEach
    void tearDown() {
        lifecycle.close(new DatasetId(PROJECT.value()));
    }

    @Test
    void outgoingRejectsAnIriThatCannotAppearUnescapedInASparqlIrirefInsteadOfExecutingIt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> storeReader.outgoing(PROJECT, INJECTION_PAYLOAD));
    }

    @Test
    void incomingRejectsAnIriThatCannotAppearUnescapedInASparqlIrirefInsteadOfExecutingIt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> storeReader.incoming(PROJECT, INJECTION_PAYLOAD));
    }

    @Test
    void findByIdentifierDoesNotLeakARawBackendExceptionForAnIdentifierContainingARawNewline() {
        assertThat(storeReader.findByIdentifier(PROJECT, MULTILINE_IDENTIFIER)).isEmpty();
    }

    @Test
    void findByIdentifierStillFindsAWellFormedIdentifier() {
        assertThat(storeReader.findByIdentifier(PROJECT, "FR-1")).containsExactly(FR_1_IRI);
    }

    @Test
    void outgoingStillReturnsTheStatementsOfAWellFormedIri() {
        List<Triple> outgoing = storeReader.outgoing(PROJECT, FR_1_IRI);

        assertThat(outgoing).isNotEmpty();
        assertThat(outgoing).allMatch(triple -> triple.subject().equals(FR_1_IRI));
    }

    /**
     * Regression test: the requirement adapter writes into a named graph
     * ({@code REQUIREMENTS_GRAPH}), and {@link StoreReader}'s queries union a plain triple
     * pattern with an explicit {@code GRAPH ?g} pattern to also reach named-graph data. Without
     * {@code DISTINCT}, a backend whose plain pattern already spans every context (as the
     * RDF4J-based adapter does) matches each named-graph triple twice, doubling every row in
     * the generated store report.
     */
    @Test
    void outgoingDoesNotDuplicateStatementsLivingInANamedGraph() {
        List<Triple> outgoing = storeReader.outgoing(PROJECT, FR_1_IRI);

        Set<Triple> distinct = new HashSet<>(outgoing);
        assertThat(outgoing).hasSameSizeAs(distinct);
    }

    /**
     * Every guarded write records a PROV-O revision plus a head pointer into the provenance
     * graph (ADR-014), and that trail grows with every write, forever. None of the three read
     * paths surfaces it: the snapshot feeds the store report, a view of the model rather than of
     * its change history, and the head pointer stays hidden even though every user-reachable
     * write now moves it through the funnel ({@code req_update}, {@code
     * req_set_status}, {@code req_link_term} and {@code term_update} were resolved into it, ADR-014 decision 4)
     * - whether and how to expose it through this generic read path is a separate, still open
     * decision, not gated on the head being a usable token any more.
     */
    @Test
    void noReadPathSurfacesTheProvenanceGraph() {
        assertThat(provenanceStatementCount())
                .as("the write in setUp must have recorded a revision - else this test is vacuous")
                .isPositive();

        assertThat(storeReader.outgoing(PROJECT, FR_1_IRI)).noneMatch(StoreReaderTest::isProvenance);
        assertThat(storeReader.incoming(PROJECT, FR_1_IRI)).noneMatch(StoreReaderTest::isProvenance);
        assertThat(snapshotTriples()).noneMatch(StoreReaderTest::isProvenance);
    }

    /**
     * The generic read path must not grow with the revision trail: every revision names its
     * resource via {@code prov:specializationOf} and rewrites its head, so an unfiltered view
     * would add rows per write, without bound. The revisions themselves are not model resources
     * either - reaching one by its IRI yields nothing.
     */
    @Test
    void furtherWritesGrowTheTrailInTheStoreButNotTheReadPath() {
        List<Triple> incomingAfterOneWrite = storeReader.incoming(PROJECT, FR_1_IRI);
        List<Triple> outgoingAfterOneWrite = storeReader.outgoing(PROJECT, FR_1_IRI);
        long trailAfterOneWrite = provenanceStatementCount();
        String firstHead = headIri();

        replaceViaCompareAndUpdate(requirementTitled("Login v2"));
        replaceViaCompareAndUpdate(requirementTitled("Login v3"));

        assertThat(provenanceStatementCount())
                .as("the two updates must have extended the trail in the store")
                .isGreaterThan(trailAfterOneWrite);
        assertThat(headIri())
                .as("and moved the head - so the read path is hiding something that really changed")
                .isNotEqualTo(firstHead);

        assertThat(storeReader.incoming(PROJECT, FR_1_IRI))
                .as("two further writes must not add neighbour rows")
                .hasSameSizeAs(incomingAfterOneWrite);
        assertThat(storeReader.outgoing(PROJECT, FR_1_IRI))
                .as("nor statement rows")
                .hasSameSizeAs(outgoingAfterOneWrite);
        assertThat(storeReader.outgoing(PROJECT, headIri()))
                .as("a revision is not a model resource - the generic read path does not reach it")
                .isEmpty();
    }

    /**
     * The second infrastructure graph (ADR-016 decision 7): a project describes itself - its
     * anchors and its label - inside its own dataset, so that the registry stays a rebuildable
     * index and a restored backup carries its identity with it. That record is routing machinery,
     * not model: without the exclusion every store report would open with the anchors by which
     * the calling client was routed here.
     *
     * <p>Written raw rather than through the project out-adapter on purpose - what is under test
     * is {@link StoreReader}'s filter, and going through the adapter would make this test fail for
     * reasons that have nothing to do with the filter.</p>
     */
    @Test
    void noReadPathSurfacesTheProjectSelfDescription() {
        writeSelfDescription();

        assertThat(selectCount("SELECT ?s ?p ?o WHERE { GRAPH <"
                + ArkprjVocabulary.IDENTITY_GRAPH + "> { ?s ?p ?o } }"))
                .as("the self-description must be in the store - else this test is vacuous")
                .isPositive();

        assertThat(snapshotTriples()).noneMatch(StoreReaderTest::isProjectIdentity);
        assertThat(storeReader.outgoing(PROJECT, PROJECT_IRI)).isEmpty();
        assertThat(storeReader.incoming(PROJECT, PROJECT_IRI)).isEmpty();
    }

    /** Writes a minimal project self-description straight into the identity graph. */
    private void writeSelfDescription() {
        RDF rdf = new SimpleRdf();
        Graph identity = rdf.createGraph();
        IRI project = rdf.createIRI(PROJECT_IRI);
        identity.add(project, rdf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                rdf.createIRI(ArkprjVocabulary.PROJECT_TYPE));
        identity.add(project, rdf.createIRI(ArkprjVocabulary.ANCHOR_VALUE),
                rdf.createLiteral("/home/somebody/DEV/sample-project"));
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.add(rdf.createIRI(ArkprjVocabulary.IDENTITY_GRAPH), identity);
                return null;
            });
        }
    }

    private static boolean isProjectIdentity(Triple triple) {
        return triple.subject().equals(PROJECT_IRI)
                || triple.predicate().startsWith(ArkprjVocabulary.NAMESPACE);
    }

    private static boolean isProvenance(Triple triple) {
        return triple.predicate().equals(ArkprovVocabulary.HEAD)
                || triple.predicate().equals(ArkprovVocabulary.SPECIALIZATION_OF);
    }

    private List<Triple> snapshotTriples() {
        return storeReader.readSnapshot(PROJECT).resources().stream()
                .flatMap(resource -> resource.outgoing().stream())
                .toList();
    }

    /** Reads the trail straight from the store - the read path under test cannot show it. */
    private long provenanceStatementCount() {
        return selectCount("SELECT ?s ?p ?o WHERE { GRAPH <"
                + ArkprovVocabulary.PROVENANCE_GRAPH + "> { ?s ?p ?o } }");
    }

    private String headIri() {
        String query = "SELECT ?v WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { <"
                + FR_1_IRI + "> <" + ArkprovVocabulary.HEAD + "> ?v } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> ((IRI) row.getValue("v").orElseThrow()).getIRIString())
                    .findFirst()
                    .orElseThrow();
        }
    }

    private long selectCount(String query) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            return handle.sparqlQuery().select(query).count();
        }
    }

    @Test
    void readSnapshotDoesNotDuplicateStatementsLivingInANamedGraph() {
        StoreSnapshot snapshot = storeReader.readSnapshot(PROJECT);

        List<Triple> triples = snapshot.resources().stream()
                .flatMap(resource -> resource.outgoing().stream())
                .toList();
        Set<Triple> distinct = new HashSet<>(triples);
        assertThat(triples).hasSameSizeAs(distinct);
    }

    /**
     * Regression test for issue #136: {@code arkreq:usesTerm} carries no {@code sh:nodeKind}
     * constraint (see {@code StoreExporterTest}), so its subject is RDF-legally allowed to be a
     * blank node too - a store-first SKOS concept with no minted IRI is a real, SHACL-legal case
     * (ADR-005), not a hypothetical one. {@code readSnapshot} already handles a blank-node
     * <em>object</em> via {@code toNode}; before this fix it silently dropped a row whose
     * <em>subject</em> was a blank node instead, so such a resource neither counted towards
     * {@code tripleCount()}/{@code resourceCount()} nor appeared among {@link
     * StoreSnapshot#resources()}.
     */
    @Test
    void readSnapshotIncludesStatementsOnABlankNodeSubject() {
        String predicate = "https://w3id.org/arknet/requirements#usesTerm";
        int tripleCountBefore = storeReader.readSnapshot(PROJECT).tripleCount();

        seedBlankNodeSubjectTriple(predicate);

        StoreSnapshot snapshot = storeReader.readSnapshot(PROJECT);
        assertThat(snapshot.tripleCount())
                .as("the blank-node-subject triple must be counted, not silently dropped")
                .isEqualTo(tripleCountBefore + 1);
        assertThat(snapshotTriples())
                .anyMatch(triple -> triple.subject().startsWith("_:") && triple.predicate().equals(predicate));
    }

    /**
     * Regression test for issue #136, the {@code incoming} counterpart: a blank-node subject
     * referencing {@code FR_1_IRI} must show up as a neighbour of {@code FR_1_IRI}, exactly like
     * an IRI subject would - {@code resource_get}/{@code impact_analysis}/{@code orphan_check}
     * all read this list to decide whether a resource is referenced.
     */
    @Test
    void incomingFindsABlankNodeSubjectThatReferencesTheIri() {
        String predicate = "https://w3id.org/arknet/requirements#usesTerm";

        seedBlankNodeSubjectTriple(predicate);

        assertThat(storeReader.incoming(PROJECT, FR_1_IRI))
                .as("a blank-node subject referencing FR_1_IRI must not be silently dropped")
                .anyMatch(triple -> triple.subject().startsWith("_:") && triple.predicate().equals(predicate));
    }

    /**
     * Writes a single blank-node-subject triple pointing at {@link #FR_1_IRI} straight into its
     * own named graph - the requirement domain path never produces one itself (see {@code
     * StoreExporterTest}'s identical seeding technique for the export-path counterpart of this
     * scenario).
     */
    private void seedBlankNodeSubjectTriple(String predicate) {
        RDF rdf = new SimpleRdf();
        Graph graph = rdf.createGraph();
        BlankNode subject = rdf.createBlankNode();
        graph.add(subject, rdf.createIRI(predicate), rdf.createIRI(FR_1_IRI));
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.add(rdf.createIRI("https://w3id.org/arknet/id/store-reader-test-blank-node-graph"), graph);
                return null;
            });
        }
    }

    /**
     * Regression test for issue #136's remaining gap: {@code readSnapshot} now surfaces a
     * blank-node subject resource, and {@code store_overview}'s digest advertises it right back
     * as a {@code resource_get(...)} drill-down handle ({@code DigestRenderer#handleFor} falls
     * back to the raw {@code "_:..."} reference when the resource carries neither a CURIE-able
     * IRI nor a {@code dcterms:identifier}, exactly the shape {@link #seedBlankNodeSubjectTriple}
     * seeds). {@link StoreReader#outgoing} must resolve that exact handle back to the resource's
     * statements instead of only working for absolute IRIs.
     */
    @Test
    void outgoingResolvesABlankNodeHandleToTheStatementsReadSnapshotGroupedUnderIt() {
        String predicate = "https://w3id.org/arknet/requirements#usesTerm";
        seedBlankNodeSubjectTriple(predicate);
        String blankNodeHandle = snapshotTriples().stream()
                .filter(triple -> triple.subject().startsWith("_:") && triple.predicate().equals(predicate))
                .map(Triple::subject)
                .findFirst()
                .orElseThrow();

        List<Triple> outgoing = storeReader.outgoing(PROJECT, blankNodeHandle);

        assertThat(outgoing).hasSize(1);
        assertThat(outgoing.get(0).predicate()).isEqualTo(predicate);
        assertThat(outgoing.get(0).subject()).isEqualTo(blankNodeHandle);
    }

    /**
     * Regression test for issue #136's remaining gap, the {@code incoming} counterpart: a blank
     * node can legally sit in <em>object</em> position too (nothing in the {@code usesTerm}
     * shape rules it out, same as the subject case above), and {@code resource_get} must be able
     * to look up what points at such an object exactly as it does for an IRI object.
     */
    @Test
    void incomingResolvesABlankNodeHandleToWhatPointsAtIt() {
        String predicate = "https://w3id.org/arknet/requirements#usesTerm";
        seedBlankNodeObjectTriple(predicate);
        String blankNodeHandle = snapshotTriples().stream()
                .filter(triple -> triple.subject().equals(FR_1_IRI) && triple.predicate().equals(predicate))
                .map(triple -> ((RdfNode.Resource) triple.object()).iri())
                .findFirst()
                .orElseThrow();

        List<Triple> incoming = storeReader.incoming(PROJECT, blankNodeHandle);

        assertThat(incoming).hasSize(1);
        assertThat(incoming.get(0).subject()).isEqualTo(FR_1_IRI);
        assertThat(incoming.get(0).predicate()).isEqualTo(predicate);
    }

    /** Writes a single triple with {@link #FR_1_IRI} as subject and a fresh blank node as object. */
    private void seedBlankNodeObjectTriple(String predicate) {
        RDF rdf = new SimpleRdf();
        Graph graph = rdf.createGraph();
        BlankNode object = rdf.createBlankNode();
        graph.add(rdf.createIRI(FR_1_IRI), rdf.createIRI(predicate), object);
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.add(rdf.createIRI("https://w3id.org/arknet/id/store-reader-test-blank-node-object-graph"), graph);
                return null;
            });
        }
    }

    /**
     * Regression/happy-path coverage for issue #251: the {@code setUp} create is the resource's
     * only write so far, so it must have recorded exactly one revision, and that one is the
     * current head.
     */
    @Test
    void historyHasExactlyOneCurrentRevisionAfterASingleCreate() {
        List<Revision> history = storeReader.history(PROJECT, FR_1_IRI);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).current()).isTrue();
        assertThat(history.get(0).generatedAtTime()).isNotBlank();
        assertThat(history.get(0).iri()).isEqualTo(headIri());
    }

    /**
     * Issue #251's central case: three writes through the funnel (the {@code setUp} create plus
     * two {@link #replaceViaCompareAndUpdate} calls) must be listed oldest first, matching the
     * exact revision identities the head pointer moved through, with only the last one marked
     * {@link Revision#current()}.
     */
    @Test
    void historyListsEveryRevisionOldestFirstWithOnlyTheHeadMarkedCurrent() {
        String firstRevision = headIri();

        replaceViaCompareAndUpdate(requirementTitled("Login v2"));
        String secondRevision = headIri();
        replaceViaCompareAndUpdate(requirementTitled("Login v3"));
        String thirdRevision = headIri();

        List<Revision> history = storeReader.history(PROJECT, FR_1_IRI);

        assertThat(history).extracting(Revision::iri)
                .containsExactly(firstRevision, secondRevision, thirdRevision);
        assertThat(history).extracting(Revision::current)
                .containsExactly(false, false, true);
    }

    /**
     * A resource the funnel has never written through - here, a store-first blank-node subject
     * seeded straight into the store, the same fixture {@link
     * #outgoingResolvesABlankNodeHandleToTheStatementsReadSnapshotGroupedUnderIt} uses - has no
     * revision to report. Empty, not an error: {@code resource_history} tells this apart from
     * "no such resource" itself, via {@link StoreReader#outgoing}/{@link StoreReader#incoming}.
     */
    @Test
    void historyIsEmptyForAResourceNoWriteHasGoneThroughTheFunnelFor() {
        String predicate = "https://w3id.org/arknet/requirements#usesTerm";
        seedBlankNodeSubjectTriple(predicate);
        String blankNodeHandle = snapshotTriples().stream()
                .filter(triple -> triple.subject().startsWith("_:") && triple.predicate().equals(predicate))
                .map(Triple::subject)
                .findFirst()
                .orElseThrow();

        assertThat(storeReader.history(PROJECT, blankNodeHandle)).isEmpty();
    }
}
