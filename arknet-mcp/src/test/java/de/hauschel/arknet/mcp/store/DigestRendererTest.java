// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Unit tests for the domain-agnostic digest rendering. Builds snapshots from hand-made
 * triples so the renderer is exercised without any store.
 */
class DigestRendererTest {

    private static final String REQ = "https://w3id.org/arknet/model/requirement/";
    private static final String TERM = "https://w3id.org/arknet/model/term/";
    private static final String OPAQUE = "https://w3id.org/arknet/id/";
    private static final String ARKREQ = "https://w3id.org/arknet/requirements#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String TITLE = "http://purl.org/dc/terms/title";
    private static final String IDENTIFIER = "http://purl.org/dc/terms/identifier";

    private final DigestRenderer renderer = new DigestRenderer(Prefixes.defaults());

    @Test
    void rendersHeaderCountersPrefixLegendAndResourceLines() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(REQ + "FR-1", RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(REQ + "FR-1", TITLE, "Login"),
                iri(REQ + "FR-1", ARKREQ + "status", ARKREQ + "Proposed"),
                iri(REQ + "FR-1", ARKREQ + "priority", ARKREQ + "MustHave"),
                iri(TERM + "login", RDF_TYPE, SKOS + "Concept"),
                lit(TERM + "login", SKOS + "prefLabel", "Anmeldung")));

        String digest = renderer.render(new ProjectId("noistill"), snapshot);

        assertThat(digest).contains("# Workspace noistill -- 2 resources, 6 triples, 2 types");
        assertThat(digest).contains("# Prefixes:");
        assertThat(digest).contains("req:").contains(REQ);
        assertThat(digest).contains("# Handle for resource_get is the IRI (as a CURIE), NOT the label.");
        assertThat(digest).contains("1 arkreq:FunctionalRequirement");
        assertThat(digest).contains("1 skos:Concept");
        assertThat(digest).contains("req:FR-1 [FunctionalRequirement] \"Login\" Proposed MustHave"
                + "  -> resource_get(\"req:FR-1\")");
        assertThat(digest).contains("term:login [Concept] \"Anmeldung\"  -> resource_get(\"term:login\")");
        assertThat(digest).contains("- no dangling references");
    }

    /**
     * Since requirement identity became an opaque {@code https://w3id.org/arknet/id/<uuid>}
     * IRI (#68, unbound to any {@link Prefixes} namespace), the digest handle falls back to
     * the resource's {@code dcterms:identifier} (its business code, e.g. {@code FR-1}) instead
     * of rendering the raw, human-unreadable IRI.
     */
    @Test
    void handleFallsBackToDctermsIdentifierWhenTheSubjectIriHasNoCurie() {
        String opaqueIri = OPAQUE + "11111111-1111-1111-1111-111111111111";
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(opaqueIri, RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(opaqueIri, TITLE, "Login"),
                lit(opaqueIri, IDENTIFIER, "FR-1")));

        String digest = renderer.render(new ProjectId("noistill"), snapshot);

        assertThat(digest).doesNotContain(opaqueIri);
        assertThat(digest).contains("FR-1 [FunctionalRequirement] \"Login\"  -> resource_get(\"FR-1\")");
    }

    @Test
    void reportsDanglingReference() {
        // Subjects and dangling targets are minted flat under the opaque /id/ base since the
        // opaque-ResourceId refactor (#68/#71/#72) - the dead /model/ base used above is no
        // longer produced by any write path and must not be what dangling detection keys on
        // (#107).
        String subject = OPAQUE + "11111111-1111-1111-1111-111111111111";
        String danglingTarget = OPAQUE + "22222222-2222-2222-2222-222222222222";
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(subject, RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(subject, TITLE, "Export"),
                iri(subject, ARKREQ + "refinesTerm", danglingTarget)));

        String digest = renderer.render(new ProjectId("ws"), snapshot);

        assertThat(digest).contains("dangling reference(s)");
        assertThat(digest).contains(subject).contains(danglingTarget).contains("(missing)");
    }

    private static Triple iri(String subject, String predicate, String objectIri) {
        return new Triple(subject, predicate, new RdfNode.Resource(objectIri));
    }

    private static Triple lit(String subject, String predicate, String lexical) {
        return new Triple(subject, predicate,
                new RdfNode.Literal(lexical, "http://www.w3.org/2001/XMLSchema#string", null));
    }
}
