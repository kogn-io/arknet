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
import io.kogn.rdf.versioning.VersioningGraphs;
import io.kogn.rdf.versioning.VocabVersioning;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.persistence.ArkprjVocabulary;
import de.hauschel.arknet.persistence.ContentGraph;
import de.hauschel.arknet.persistence.RdfNode;
import de.hauschel.arknet.persistence.VersionEntry;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.persistence.StoreSnapshot;
import de.hauschel.arknet.persistence.Triple;
import de.hauschel.arknet.pr.shared.RequirementCode;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RevisionToken;
import de.hauschel.arknet.req.domain.AcceptanceCriterion;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
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
                "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null,
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
    }

    /** Reads {@code updated}'s current head and immediately applies it through the CAS guard. */
    private void replaceViaCompareAndUpdate(Requirement updated) {
        RevisionToken head = requirements.findCurrentByCode(PROJECT, updated.code(), null)
                .map(RequirementRepository.CurrentRequirement::head)
                .orElse(null);
        requirements.compareAndUpdate(PROJECT, head, updated, null, null, null, noAcceptanceCriteriaLanguages(updated),
                null, null);
    }

    /** An untagged (all-{@code null}) map, covering every position {@code updated} carries. */
    private static Map<Integer, String> noAcceptanceCriteriaLanguages(Requirement updated) {
        Map<Integer, String> languages = new LinkedHashMap<>();
        updated.acceptanceCriteria().forEach(criterion -> languages.put(criterion.position(), null));
        return languages;
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDown(new DatasetId(PROJECT.value()));
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

    /**
     * Regression test for issue #299: a store-first resource with no minted IRI is
     * RDF-legally allowed to carry a {@code dcterms:identifier} on a blank-node subject just like
     * an IRI subject can - {@code DigestRenderer} already prints such an identifier as a
     * drill-down handle (see {@code StoreReaderTest#readSnapshotIncludesStatementsOnABlankNodeSubject}
     * for the same shape). Before the fix, {@code findByIdentifier} filtered the query result down
     * to {@link IRI} subjects only, so resolving that exact handle back always found nothing.
     */
    @Test
    void findByIdentifierFindsABlankNodeSubject() {
        seedBlankNodeSubjectWithIdentifier("BN-1");

        List<String> matches = storeReader.findByIdentifier(PROJECT, "BN-1");

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0)).startsWith("_:");
    }

    // --- literalContaining (kogn-io/arknet#594 Part 1) ------------------------

    @Test
    void literalContainingFindsAMatchCaseInsensitively() {
        List<Triple> matches = storeReader.literalContaining(PROJECT, "LOGIN");

        assertThat(matches).anyMatch(triple -> triple.subject().equals(FR_1_IRI)
                && triple.object() instanceof RdfNode.Literal literal
                && literal.lexicalForm().contains("Login"));
    }

    @Test
    void literalContainingRejectsABlankNeedle() {
        assertThatIllegalArgumentException().isThrownBy(() -> storeReader.literalContaining(PROJECT, "   "));
    }

    /**
     * Every write publishes, so each version adds to the library's graphs; the search still finds
     * the content graph's literal once.
     */
    @Test
    void literalContainingDoesNotSurfaceThePublishedCopy() {
        List<Triple> beforeSecondVersion = storeReader.literalContaining(PROJECT, "Login");

        replaceViaCompareAndUpdate(requirementTitled("Login v2"));

        assertThat(storeReader.literalContaining(PROJECT, "Login")).hasSameSizeAs(beforeSecondVersion)
                .noneMatch(StoreReaderTest::isVersioning);
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
     * Every write copies the resource into the library's published view and records a history
     * entry. None of the read paths surfaces either: they read the content graph alone, so a
     * resource is shown once, and no versioning vocabulary reaches a renderer - also after a
     * second version.
     */
    @Test
    void noReadPathSurfacesTheLibraryGraphs() {
        long versioningBefore = versioningStatementCount();
        List<Triple> outgoingBefore = storeReader.outgoing(PROJECT, FR_1_IRI);
        List<Triple> incomingBefore = storeReader.incoming(PROJECT, FR_1_IRI);
        int snapshotBefore = snapshotTriples().size();

        replaceViaCompareAndUpdate(requirementTitled("Login v2"));

        assertThat(versioningStatementCount())
                .as("the second version must have written to the library's graphs - else this test is vacuous")
                .isGreaterThan(versioningBefore);
        assertThat(storeReader.outgoing(PROJECT, FR_1_IRI)).hasSameSizeAs(outgoingBefore)
                .noneMatch(StoreReaderTest::isVersioning);
        assertThat(storeReader.incoming(PROJECT, FR_1_IRI)).hasSameSizeAs(incomingBefore)
                .noneMatch(StoreReaderTest::isVersioning);
        assertThat(snapshotTriples()).hasSize(snapshotBefore).noneMatch(StoreReaderTest::isVersioning);
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

    private static boolean isVersioning(Triple triple) {
        return triple.predicate().startsWith(VocabVersioning.NAMESPACE);
    }

    private List<Triple> snapshotTriples() {
        return storeReader.readSnapshot(PROJECT).resources().stream()
                .flatMap(resource -> resource.outgoing().stream())
                .toList();
    }

    /** Counts the library's published view and history straight from the store. */
    private long versioningStatementCount() {
        return selectCount("SELECT ?s ?p ?o WHERE { VALUES ?g { <" + VersioningGraphs.PUBLISHED.getIRIString()
                + "> <" + VersioningGraphs.HISTORY.getIRIString() + "> } GRAPH ?g { ?s ?p ?o } }");
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
     * constraint (see {@code KognioRdfProjectDatasetsExportTest}), so its subject is RDF-legally allowed to be a
     * blank node too - a store-first SKOS concept with no minted IRI is a real, SHACL-legal case,
     * not a hypothetical one. {@code readSnapshot} already handles a blank-node
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
     * KognioRdfProjectDatasetsExportTest}'s identical seeding technique for the export-path counterpart of this
     * scenario).
     */
    private void seedBlankNodeSubjectTriple(String predicate) {
        RDF rdf = new SimpleRdf();
        Graph graph = rdf.createGraph();
        BlankNode subject = rdf.createBlankNode();
        graph.add(subject, rdf.createIRI(predicate), rdf.createIRI(FR_1_IRI));
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.add(rdf.createIRI(ContentGraph.GRAPH_IRI), graph);
                return null;
            });
        }
    }

    /**
     * Writes a single blank-node-subject {@code dcterms:identifier} triple straight into its own
     * named graph - the shape {@link #findByIdentifierFindsABlankNodeSubject} needs, analogous to
     * {@link #seedBlankNodeSubjectTriple} for the {@code outgoing}/{@code incoming} regressions.
     */
    private void seedBlankNodeSubjectWithIdentifier(String identifier) {
        RDF rdf = new SimpleRdf();
        Graph graph = rdf.createGraph();
        BlankNode subject = rdf.createBlankNode();
        graph.add(subject, rdf.createIRI("http://purl.org/dc/terms/identifier"), rdf.createLiteral(identifier));
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.add(rdf.createIRI(ContentGraph.GRAPH_IRI), graph);
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
                tx.add(rdf.createIRI(ContentGraph.GRAPH_IRI), graph);
                return null;
            });
        }
    }

    /** Every write publishes: right after the create the history holds its one version. */
    @Test
    void historyHoldsTheCreatedVersionRightAfterTheCreate() {
        assertThat(storeReader.history(PROJECT, FR_1_IRI)).hasSize(1);
        assertThat(storeReader.publishState(PROJECT, FR_1_IRI)).isEqualTo("PUBLISHED_UNCHANGED");
    }

    /**
     * The create and a changing update are two entries, oldest first, each naming a
     * content-addressed version, and nothing stays unpublished.
     */
    @Test
    void historyListsEveryWriteOldestFirst() {
        replaceViaCompareAndUpdate(requirementTitled("Login v2"));

        List<VersionEntry> history = storeReader.history(PROJECT, FR_1_IRI);
        assertThat(history).extracting(VersionEntry::sequence).containsExactly(1L, 2L);
        assertThat(history).extracting(VersionEntry::tombstone).containsExactly(false, false);
        assertThat(history).allMatch(entry -> entry.cid() != null);
        assertThat(history.get(0).cid()).isNotEqualTo(history.get(1).cid());
        assertThat(storeReader.publishState(PROJECT, FR_1_IRI)).isEqualTo("PUBLISHED_UNCHANGED");
    }

    /**
     * A resource the funnel has never written through - here, a store-first blank-node subject
     * seeded straight into the store, the same fixture {@link
     * #outgoingResolvesABlankNodeHandleToTheStatementsReadSnapshotGroupedUnderIt} uses - has no
     * revision to report. Empty, not an error: {@code resource_history} tells this apart from
     * "no such resource" itself, via {@link StoreReader#outgoing}/{@link StoreReader#incoming}.
     */
    @Test
    void historyIsEmptyForABlankNodeHandle() {
        String predicate = "https://w3id.org/arknet/requirements#usesTerm";
        seedBlankNodeSubjectTriple(predicate);
        String blankNodeHandle = snapshotTriples().stream()
                .filter(triple -> triple.subject().startsWith("_:") && triple.predicate().equals(predicate))
                .map(Triple::subject)
                .findFirst()
                .orElseThrow();

        assertThat(storeReader.history(PROJECT, blankNodeHandle)).isEmpty();
    }
    // --- languageTagsByField (kogn-io/arknet#474) -----------------------------

    /**
     * The store side of the stale-translation signal: after a German create and an English
     * correction, the requirement's title carries both tags - and so does its acceptance
     * criterion, whose text lives on a child resource and is therefore reported under the edge
     * that owns it.
     *
     * <p>This is the one place the per-field tags are visible at all: every layer above collapses
     * a field to a single {@link String} through the display-language fallback chain, which is
     * exactly why the signal needs its own read rather than the answer the tool already has.</p>
     */
    @Test
    void languageTagsByFieldReportsEveryTagAFieldCarriesIncludingAChildResources() {
        DatasetId dataset = new DatasetId(PROJECT.value());
        replaceViaCompareAndUpdateUnder(requirementTitled("Anmelden"), "de");
        replaceViaCompareAndUpdateUnder(requirementTitled("Sign in"), "en");

        Map<String, Set<String>> byField = storeReader.languageTagsByField(dataset, "FR-1");

        assertThat(byField.get("title")).containsExactlyInAnyOrder("de", "en");
        assertThat(byField.get("acceptanceCriterion")).containsExactlyInAnyOrder("de", "en");
    }

    /** An identifier nothing in the dataset carries yields an empty map, not an error. */
    @Test
    void languageTagsByFieldAnswersEmptyForAnUnknownIdentifier() {
        assertThat(storeReader.languageTagsByField(new DatasetId(PROJECT.value()), "FR-999")).isEmpty();
    }

    /** Applies {@code updated} through the CAS guard, tagging every text field with {@code language}. */
    private void replaceViaCompareAndUpdateUnder(Requirement updated, String language) {
        RevisionToken head = requirements.findCurrentByCode(PROJECT, updated.code(), null)
                .map(RequirementRepository.CurrentRequirement::head)
                .orElse(null);
        Map<Integer, String> criteriaLanguages = new LinkedHashMap<>();
        updated.acceptanceCriteria().forEach(criterion -> criteriaLanguages.put(criterion.position(), language));
        requirements.compareAndUpdate(PROJECT, head, updated, language, language, language, criteriaLanguages,
                null, language);
    }

}
