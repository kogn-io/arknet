// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the generic per-subject view - focused on {@link StoreResource#types()},
 * whose ordering guarantee changed with issue #150.
 */
class StoreResourceTest {

    private static final String ID = "https://w3id.org/arknet/id/";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String ARKPROC = "https://w3id.org/arknet/process#";
    private static final String ARKREQ = "https://w3id.org/arknet/requirements#";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    /**
     * {@code SELECT DISTINCT} carries no {@code ORDER BY}, so the triples backing a
     * multi-typed resource can arrive in either order across two calls. Before this fix
     * {@code types()} returned them in encounter (i.e. triple-list) order, which a
     * {@code store_overview} diff would then see reshuffled between two runs holding the exact
     * same model. Sorting alphabetically makes the result independent of the input order.
     */
    @Test
    void sortsTypesAlphabeticallyRegardlessOfStatementOrder() {
        final String subject = ID + "nutzer";
        final StoreResource inOneOrder = new StoreResource(subject, List.of(
                iri(subject, RDF_TYPE, ARKPROC + "HumanActor"),
                iri(subject, RDF_TYPE, SKOS + "Concept")));
        final StoreResource inTheOtherOrder = new StoreResource(subject, List.of(
                iri(subject, RDF_TYPE, SKOS + "Concept"),
                iri(subject, RDF_TYPE, ARKPROC + "HumanActor")));

        assertThat(inOneOrder.types()).containsExactly(SKOS + "Concept", ARKPROC + "HumanActor");
        assertThat(inTheOtherOrder.types()).containsExactly(SKOS + "Concept", ARKPROC + "HumanActor");
    }

    @Test
    void returnsAnEmptyListForAResourceWithNoRdfType() {
        final String subject = ID + "orphan";
        final StoreResource resource = new StoreResource(subject, List.of(
                literal(subject, ARKREQ + "stepText", "kein Typ")));

        assertThat(resource.types()).isEmpty();
    }

    private static Triple iri(final String subject, final String predicate, final String objectIri) {
        return new Triple(subject, predicate, new RdfNode.Resource(objectIri));
    }

    private static Triple literal(final String subject, final String predicate, final String value) {
        return new Triple(subject, predicate,
                new RdfNode.Literal(value, "http://www.w3.org/2001/XMLSchema#string", null));
    }
}
