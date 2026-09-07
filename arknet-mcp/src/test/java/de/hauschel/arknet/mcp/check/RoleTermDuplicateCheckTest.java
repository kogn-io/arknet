// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.mcp.check.RoleTermDuplicateCheck.Finding;
import de.hauschel.arknet.mcp.store.RdfNode;
import de.hauschel.arknet.mcp.store.StoreSnapshot;
import de.hauschel.arknet.mcp.store.Triple;

/**
 * Unit tests for the rule behind {@code store_check ROLE_TERM_DUPLICATE} (kogn-io/arknet#512): a
 * role ({@code arkproc:Role}) and a glossary term ({@code skos:Concept}) that carry the same name
 * are reported, never rejected - {@code role_add}/{@code term_add} stay independent of each
 * other (no new cross-context edge, ADR-39 was removed by the #433 audit for exactly that reason).
 *
 * <p>Every fixture here is a bare triple list, never a live store - same discipline as
 * {@link LanguageGapCheckTest}: the rule has nothing to do with RDF4J or any bounded context.</p>
 */
class RoleTermDuplicateCheckTest {

    private static final String ID = "https://w3id.org/arknet/id/";
    private static final String ARKPROC = "https://w3id.org/arknet/process#";
    private static final String ARKNET_CORE = "https://w3id.org/arknet/core#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String DCTERMS = "http://purl.org/dc/terms/";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    /**
     * The case the whole issue turns on: a role's name is genuinely translated per language
     * (different words per tag), while a term's {@code skos:prefLabel} carries the very same word
     * under every tag (FR-10). The match has to be found through the role's English variant even
     * though its German variant names something else entirely.
     */
    @Test
    void reportsARoleAndATermSharingANameAcrossOneOfTheRolesLanguageVariants() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "role1", RDF_TYPE, ARKPROC + "Role"),
                literal(ID + "role1", DCTERMS + "identifier", "ROLE-1", null),
                literal(ID + "role1", ARKNET_CORE + "name", "Requirements Engineer", "en"),
                literal(ID + "role1", ARKNET_CORE + "name", "Anforderungsingenieur", "de"),
                iri(ID + "term1", RDF_TYPE, SKOS + "Concept"),
                literal(ID + "term1", DCTERMS + "identifier", "TERM-6", null),
                literal(ID + "term1", SKOS + "prefLabel", "Requirements Engineer", "en"),
                literal(ID + "term1", SKOS + "prefLabel", "Requirements Engineer", "de")));

        assertThat(RoleTermDuplicateCheck.run(snapshot))
                .extracting(Finding::roleCode, Finding::termCode, Finding::name)
                .containsExactly(tuple("ROLE-1", "TERM-6", "Requirements Engineer"));
    }

    @Test
    void reportsNothingWhenTheRoleAndTheTermNameDifferentThings() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "role1", RDF_TYPE, ARKPROC + "Role"),
                literal(ID + "role1", DCTERMS + "identifier", "ROLE-1", null),
                literal(ID + "role1", ARKNET_CORE + "name", "Product Owner", "en"),
                iri(ID + "term1", RDF_TYPE, SKOS + "Concept"),
                literal(ID + "term1", DCTERMS + "identifier", "TERM-1", null),
                literal(ID + "term1", SKOS + "prefLabel", "Order", "en")));

        assertThat(RoleTermDuplicateCheck.run(snapshot)).isEmpty();
    }

    @Test
    void matchesCaseInsensitivelyAndTrimmed() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "role1", RDF_TYPE, ARKPROC + "Role"),
                literal(ID + "role1", DCTERMS + "identifier", "ROLE-1", null),
                literal(ID + "role1", ARKNET_CORE + "name", " architect ", "en"),
                iri(ID + "term1", RDF_TYPE, SKOS + "Concept"),
                literal(ID + "term1", DCTERMS + "identifier", "TERM-1", null),
                literal(ID + "term1", SKOS + "prefLabel", "Architect", "en")));

        assertThat(RoleTermDuplicateCheck.run(snapshot))
                .extracting(Finding::roleCode, Finding::termCode)
                .containsExactly(tuple("ROLE-1", "TERM-1"));
    }

    /**
     * An {@code arkproc:Actor} carrying the same {@code arknet:name} as a term must not be
     * reported - the type filter, not the predicate, decides. Actor/glossary name collisions were
     * the whole reason Actor became its own resource type (kogn-io/arknet#322/#405).
     */
    @Test
    void ignoresAnActorCarryingTheSameNameAsATerm() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "actor1", RDF_TYPE, ARKPROC + "HumanActor"),
                literal(ID + "actor1", DCTERMS + "identifier", "ACTOR-1", null),
                literal(ID + "actor1", ARKNET_CORE + "name", "Architect", null),
                iri(ID + "term1", RDF_TYPE, SKOS + "Concept"),
                literal(ID + "term1", DCTERMS + "identifier", "TERM-1", null),
                literal(ID + "term1", SKOS + "prefLabel", "Architect", "en")));

        assertThat(RoleTermDuplicateCheck.run(snapshot)).isEmpty();
    }

    @Test
    void skipsARoleWithoutABusinessCodeRatherThanGuessingOne() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "role1", RDF_TYPE, ARKPROC + "Role"),
                literal(ID + "role1", ARKNET_CORE + "name", "Architect", "en"),
                iri(ID + "term1", RDF_TYPE, SKOS + "Concept"),
                literal(ID + "term1", DCTERMS + "identifier", "TERM-1", null),
                literal(ID + "term1", SKOS + "prefLabel", "Architect", "en")));

        assertThat(RoleTermDuplicateCheck.run(snapshot)).isEmpty();
    }

    @Test
    void skipsATermWithoutABusinessCodeRatherThanGuessingOne() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "role1", RDF_TYPE, ARKPROC + "Role"),
                literal(ID + "role1", DCTERMS + "identifier", "ROLE-1", null),
                literal(ID + "role1", ARKNET_CORE + "name", "Architect", "en"),
                iri(ID + "term1", RDF_TYPE, SKOS + "Concept"),
                literal(ID + "term1", SKOS + "prefLabel", "Architect", "en")));

        assertThat(RoleTermDuplicateCheck.run(snapshot)).isEmpty();
    }

    @Test
    void reportsNothingWhenTheStoreHasNoRolesOrNoTermsAtAll() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literal(ID + "r1", DCTERMS + "identifier", "FR-1", null)));

        assertThat(RoleTermDuplicateCheck.run(snapshot)).isEmpty();
    }

    @Test
    void ordersFindingsByRoleCodeThenTermCodeSoTwoRunsOverAnUnchangedStoreAgree() {
        StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(ID + "role2", RDF_TYPE, ARKPROC + "Role"),
                literal(ID + "role2", DCTERMS + "identifier", "ROLE-2", null),
                literal(ID + "role2", ARKNET_CORE + "name", "Architect", "en"),
                iri(ID + "role1", RDF_TYPE, ARKPROC + "Role"),
                literal(ID + "role1", DCTERMS + "identifier", "ROLE-1", null),
                literal(ID + "role1", ARKNET_CORE + "name", "Architect", "en"),
                iri(ID + "term1", RDF_TYPE, SKOS + "Concept"),
                literal(ID + "term1", DCTERMS + "identifier", "TERM-1", null),
                literal(ID + "term1", SKOS + "prefLabel", "Architect", "en")));

        assertThat(RoleTermDuplicateCheck.run(snapshot))
                .extracting(Finding::roleCode, Finding::termCode)
                .containsExactly(tuple("ROLE-1", "TERM-1"), tuple("ROLE-2", "TERM-1"));
    }

    private static Triple iri(String subject, String predicate, String object) {
        return new Triple(subject, predicate, new RdfNode.Resource(object));
    }

    private static Triple literal(String subject, String predicate, String value, String languageTag) {
        return new Triple(subject, predicate, new RdfNode.Literal(value, null, languageTag));
    }
}
