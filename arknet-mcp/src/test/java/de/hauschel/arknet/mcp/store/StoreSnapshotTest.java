package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the domain-agnostic snapshot aggregation: grouping, counts, primary-type
 * selection and dangling-reference detection.
 */
class StoreSnapshotTest {

    private static final String REQ = "https://w3id.org/arknet/model/requirement/";
    private static final String TERM = "https://w3id.org/arknet/model/term/";
    private static final String ID = "https://w3id.org/arknet/id/";
    private static final String ARKREQ = "https://w3id.org/arknet/requirements#";
    private static final String ARKPROC = "https://w3id.org/arknet/process#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    @Test
    void groupsSubjectsAndCounts() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(REQ + "FR-1", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                iri(REQ + "FR-2", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                iri(TERM + "login", RDF_TYPE, SKOS + "Concept")));

        assertThat(snapshot.resourceCount()).isEqualTo(3);
        assertThat(snapshot.tripleCount()).isEqualTo(3);
        assertThat(snapshot.typeCount()).isEqualTo(2);
        assertThat(snapshot.typeCounts()).containsEntry(ARKREQ + "FunctionalRequirement", 2);
        assertThat(snapshot.typeCounts()).containsEntry(SKOS + "Concept", 1);
    }

    @Test
    void picksAlphabeticallySmallestTypeAsPrimarySoMultiTypedResourceIsCountedOnce() {
        // A term that is also an actor: skos:Concept (http) sorts before arkproc (https),
        // so it lands in the skos:Concept bucket and is counted exactly once.
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(TERM + "nutzer", RDF_TYPE, SKOS + "Concept"),
                iri(TERM + "nutzer", RDF_TYPE, ARKPROC + "HumanActor")));

        assertThat(snapshot.resourceCount()).isEqualTo(1);
        assertThat(snapshot.typeCounts()).containsOnly(
                org.assertj.core.api.Assertions.entry(SKOS + "Concept", 1));
    }

    @Test
    void detectsDanglingInstanceReferenceButNotVocabularyObjects() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "fr-3", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                iri(ID + "fr-3", ARKREQ + "status", ARKREQ + "Proposed"),
                iri(ID + "fr-3", ARKREQ + "refinesTerm", ID + "rezept")));

        assertThat(snapshot.danglingReferences()).hasSize(1);
        StoreSnapshot.DanglingRef dangling = snapshot.danglingReferences().get(0);
        assertThat(dangling.subject()).isEqualTo(ID + "fr-3");
        assertThat(dangling.target()).isEqualTo(ID + "rezept");
    }

    @Test
    void noDanglingWhenTargetIsPresent() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "fr-3", ARKREQ + "refinesTerm", ID + "rezept"),
                iri(ID + "rezept", RDF_TYPE, SKOS + "Concept")));

        assertThat(snapshot.danglingReferences()).isEmpty();
    }

    /**
     * Regression for #107: {@code UuidResourceIdFactory} (arknet-shared-kernel) has minted
     * every instance identity flat under {@code https://w3id.org/arknet/id/<uuid>} - no
     * {@code /model/...} segment - since the opaque-{@code ResourceId} refactor (#68/#71/#72).
     * {@code detectDangling()} must recognize that current base, or a real dangling edge (e.g.
     * a {@code arkreq:realises} pointing at a deleted use case) silently goes unreported.
     */
    @Test
    void detectsDanglingReferenceAgainstCurrentOpaqueIdBase() {
        String existingUseCase = ID + "11111111-1111-1111-1111-111111111111";
        String deletedRequirement = ID + "22222222-2222-2222-2222-222222222222";
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(existingUseCase, RDF_TYPE, ARKREQ + "UseCase"),
                iri(existingUseCase, ARKREQ + "realises", deletedRequirement)));

        assertThat(snapshot.danglingReferences()).hasSize(1);
        StoreSnapshot.DanglingRef dangling = snapshot.danglingReferences().get(0);
        assertThat(dangling.subject()).isEqualTo(existingUseCase);
        assertThat(dangling.target()).isEqualTo(deletedRequirement);
    }

    private static Triple iri(String subject, String predicate, String objectIri) {
        return new Triple(subject, predicate, new RdfNode.Resource(objectIri));
    }
}
