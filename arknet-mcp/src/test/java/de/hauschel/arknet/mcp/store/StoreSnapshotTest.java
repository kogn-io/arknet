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
                iri(REQ + "FR-3", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                iri(REQ + "FR-3", ARKREQ + "status", ARKREQ + "Proposed"),
                iri(REQ + "FR-3", ARKREQ + "refinesTerm", TERM + "rezept")));

        assertThat(snapshot.danglingReferences()).hasSize(1);
        StoreSnapshot.DanglingRef dangling = snapshot.danglingReferences().get(0);
        assertThat(dangling.subject()).isEqualTo(REQ + "FR-3");
        assertThat(dangling.target()).isEqualTo(TERM + "rezept");
    }

    @Test
    void noDanglingWhenTargetIsPresent() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(REQ + "FR-3", ARKREQ + "refinesTerm", TERM + "rezept"),
                iri(TERM + "rezept", RDF_TYPE, SKOS + "Concept")));

        assertThat(snapshot.danglingReferences()).isEmpty();
    }

    private static Triple iri(String subject, String predicate, String objectIri) {
        return new Triple(subject, predicate, new RdfNode.Resource(objectIri));
    }
}
