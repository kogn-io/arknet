// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.HandleResolver;
import de.hauschel.arknet.persistence.Prefixes;
import de.hauschel.arknet.persistence.RdfNode;
import de.hauschel.arknet.persistence.StoreSnapshot;
import de.hauschel.arknet.persistence.Triple;

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
    private static final String ARKNET_NAME = "https://w3id.org/arknet/core#name";

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

        String digest = renderer.render(new ProjectId("sample-project"), Optional.empty(), Optional.empty(), snapshot, DisplayLocale.DEFAULT);

        assertThat(digest).contains("# Project sample-project -- 2 resources, 6 triples, 2 types");
        assertThat(digest).contains("# Prefixes:");
        assertThat(digest).contains("req:").contains(REQ);
        assertThat(digest).contains("# Handle for resource_get: a CURIE, or a bare business id, or the"
                + " full IRI when neither is unique. NEVER the label.");
        assertThat(digest).contains("1 arkreq:FunctionalRequirement");
        assertThat(digest).contains("1 skos:Concept");
        assertThat(digest).contains("req:FR-1 [FunctionalRequirement] \"Login\" Proposed MustHave"
                + "  -> resource_get(\"req:FR-1\")");
        assertThat(digest).contains("term:login [Concept] \"Anmeldung\"  -> resource_get(\"term:login\")");
        assertThat(digest).contains("- no dangling references");
    }

    /**
     * Since requirement identity became an opaque {@code https://w3id.org/arknet/id/<uuid>}
     * IRI (unbound to any {@link Prefixes} namespace), the digest handle falls back to
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

        String digest = renderer.render(new ProjectId("sample-project"), Optional.empty(), Optional.empty(), snapshot, DisplayLocale.DEFAULT);

        assertThat(digest).doesNotContain(opaqueIri);
        assertThat(digest).contains("FR-1 [FunctionalRequirement] \"Login\"  -> resource_get(\"FR-1\")");
    }

    /**
     * Issue #150, finding 3: the header promises a working {@code resource_get} drill-down, but
     * {@link HandleResolver} rejects a bare id shared by several resources as ambiguous. Two
     * resources with the same {@code dcterms:identifier} (a malformed store - business codes are
     * meant to be unique - but the digest must not paper over it with a broken affordance) must
     * therefore both fall back to their full IRI as the handle, never the shared bare id.
     */
    @Test
    void handleFallsBackToTheFullIriWhenTheDctermsIdentifierIsSharedByAnotherResource() {
        String first = OPAQUE + "11111111-1111-1111-1111-111111111111";
        String second = OPAQUE + "22222222-2222-2222-2222-222222222222";
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(first, RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(first, TITLE, "Login"),
                lit(first, IDENTIFIER, "FR-1"),
                iri(second, RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(second, TITLE, "Logout"),
                lit(second, IDENTIFIER, "FR-1")));

        String digest = renderer.render(new ProjectId("sample-project"), Optional.empty(), Optional.empty(), snapshot, DisplayLocale.DEFAULT);

        assertThat(digest).contains("resource_get(\"" + first + "\")");
        assertThat(digest).contains("resource_get(\"" + second + "\")");
        assertThat(digest).doesNotContain("resource_get(\"FR-1\")");
    }

    /**
     * Issue #600: {@code arknet:name} is the predicate an Actor, Role, BoundedContext or ADR
     * carries its display name under - before this, none of the three recognised label
     * predicates matched, so all four showed up nameless in the digest.
     */
    @Test
    void rendersTheArknetNamePredicateAsALabel() {
        String actorIri = "https://w3id.org/arknet/id/store-overview-test-actor-1";
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(actorIri, RDF_TYPE, "https://w3id.org/arknet/process#Actor"),
                lit(actorIri, ARKNET_NAME, "Product Owner"),
                lit(actorIri, IDENTIFIER, "ACTOR-1")));

        String digest = renderer.render(new ProjectId("sample-project"), Optional.empty(), Optional.empty(),
                snapshot, DisplayLocale.DEFAULT);

        assertThat(digest).contains("\"Product Owner\"");
    }

    /**
     * Issue #600: a resource with neither a label nor a {@code dcterms:identifier} - an
     * {@code arkreq:Step}, an {@code arkreq:AcceptanceCriterion} and their like, meant to be read
     * only through their owning resource - is collapsed into one trailing summary line per type
     * rather than listed as an unreadable bare IRI, once for every such resource.
     */
    @Test
    void collapsesUnlabeledIdLessResourcesIntoOneSummaryLinePerType() {
        String uc1 = "https://w3id.org/arknet/id/store-overview-test-uc-1";
        String step1 = "https://w3id.org/arknet/id/store-overview-test-step-1";
        String step2 = "https://w3id.org/arknet/id/store-overview-test-step-2";
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(uc1, RDF_TYPE, ARKREQ + "UseCase"),
                lit(uc1, TITLE, "Login"),
                lit(uc1, IDENTIFIER, "UC-1"),
                iri(uc1, ARKREQ + "mainStep", step1),
                iri(step1, RDF_TYPE, ARKREQ + "Step"),
                lit(step1, ARKREQ + "stepText", "Enter credentials"),
                iri(step2, RDF_TYPE, ARKREQ + "Step"),
                lit(step2, ARKREQ + "stepText", "Submit form")));

        String digest = renderer.render(new ProjectId("sample-project"), Optional.empty(), Optional.empty(),
                snapshot, DisplayLocale.DEFAULT);

        assertThat(digest).doesNotContain(step1).doesNotContain(step2);
        assertThat(digest).contains("2 more without a label or business id");
        assertThat(digest).contains("UC-1 [UseCase] \"Login\"");
    }

    @Test
    void reportsDanglingReference() {
        // Subjects and dangling targets are minted flat under the opaque /id/ base since the
        // opaque-ResourceId refactor - the dead /model/ base used above is no
        // longer produced by any write path and must not be what dangling detection keys on.
        String subject = OPAQUE + "11111111-1111-1111-1111-111111111111";
        String danglingTarget = OPAQUE + "22222222-2222-2222-2222-222222222222";
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(subject, RDF_TYPE, ARKREQ + "FunctionalRequirement"),
                lit(subject, TITLE, "Export"),
                iri(subject, ARKREQ + "refinesTerm", danglingTarget)));

        String digest = renderer.render(new ProjectId("ws"), Optional.empty(), Optional.empty(), snapshot, DisplayLocale.DEFAULT);

        assertThat(digest).contains("dangling reference(s)");
        assertThat(digest).contains(subject).contains(danglingTarget).contains("(missing)");
    }

    /**
     * A project registered with a human-readable label must show that label in the
     * header, with the raw id kept alongside - not the id alone, and not the label instead of the
     * id (the id stays the write target and must remain visible for the report path etc.).
     */
    @Test
    void headerNamesTheRegisteredLabelAlongsideTheId() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of());

        String digest = renderer.render(
                new ProjectId("ff92cedd-a76a-4f1d-acc5-7aad9ccb1ac8"), Optional.of("arknet-demo"),
                Optional.empty(), snapshot, DisplayLocale.DEFAULT);

        assertThat(digest).contains("# Project arknet-demo (id: ff92cedd-a76a-4f1d-acc5-7aad9ccb1ac8) --");
    }

    /** issue #110: a project's optional description is shown right below the header line when present. */
    @Test
    void headerShowsTheProjectDescriptionWhenPresent() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of());

        String digest = renderer.render(new ProjectId("p-1"), Optional.of("arknet-demo"),
                Optional.of("A demo project for arknet."), snapshot, DisplayLocale.DEFAULT);

        assertThat(digest).contains("# Project arknet-demo (id: p-1) --")
                .contains("# A demo project for arknet.");
    }

    /** No description is simply omitted - unchanged from before issue #110. */
    @Test
    void headerOmitsTheDescriptionLineWhenAbsent() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of());

        String digest = renderer.render(new ProjectId("p-1"), Optional.of("arknet-demo"), Optional.empty(),
                snapshot, DisplayLocale.DEFAULT);

        assertThat(digest).doesNotContain("# A demo project");
    }

    /**
     * No label means no registry entry for this id - the header falls back to the raw id exactly
     * as it did before this lookup existed, rather than printing an empty or placeholder name.
     */
    @Test
    void headerFallsBackToTheRawIdWhenNoLabelIsAvailable() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of());

        String digest = renderer.render(new ProjectId("chat-app-project"), Optional.empty(), Optional.empty(), snapshot, DisplayLocale.DEFAULT);

        assertThat(digest).contains("# Project chat-app-project --");
    }

    private static Triple iri(String subject, String predicate, String objectIri) {
        return new Triple(subject, predicate, new RdfNode.Resource(objectIri));
    }

    private static Triple lit(String subject, String predicate, String lexical) {
        return new Triple(subject, predicate,
                new RdfNode.Literal(lexical, "http://www.w3.org/2001/XMLSchema#string", null));
    }
}
