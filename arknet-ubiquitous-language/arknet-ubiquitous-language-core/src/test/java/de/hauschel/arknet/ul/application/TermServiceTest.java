// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermNotFoundException;

/**
 * Policy tests for {@link TermService}: opaque identity minting, code assignment, listing and
 * lookup-by-code, exercised against an in-memory fake repository.
 */
class TermServiceTest {

    private static final ProjectId WS = new ProjectId("test-project");

    private InMemoryTermRepository repository;
    private TermService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTermRepository();
        service = new TermService(repository, new UuidResourceIdFactory());
    }

    @Test
    void addAssignsFirstCode() {
        Term added = service.add(WS, new NewTerm("Gutschrift",
                "Rueckerstattung eines bereits gezahlten Betrags.", null));

        assertEquals(new TermCode("TERM-1"), added.code());
        assertEquals("Gutschrift", added.prefLabel());
        assertEquals("Rueckerstattung eines bereits gezahlten Betrags.", added.definition());
        assertEquals(added, repository.findByCode(WS, added.code()).orElseThrow());
    }

    @Test
    void addMintsAFreshOpaqueIdentityPerTerm() {
        Term first = service.add(WS, new NewTerm("Gutschrift", "def a", null));
        Term second = service.add(WS, new NewTerm("Bestellung", "def b", null));

        // Identity is opaque and minted once - never derived from the (sequential) code.
        assertNotEquals(first.id(), second.id());
        assertTrue(first.id().value().value().startsWith("https://"));
    }

    @Test
    void addNumbersRunSequentially() {
        TermCode first = service.add(WS, new NewTerm("Gutschrift", "def a", null)).code();
        TermCode second = service.add(WS, new NewTerm("Bestellung", "def b", null)).code();

        assertEquals(new TermCode("TERM-1"), first);
        assertEquals(new TermCode("TERM-2"), second);
    }

    @Test
    void addIsScopedPerWorkspace() {
        ProjectId other = new ProjectId("other");
        service.add(WS, new NewTerm("Gutschrift", "def a", null));

        Term inOther = service.add(other, new NewTerm("Bestellung", "def b", null));

        assertEquals(new TermCode("TERM-1"), inOther.code());
        assertTrue(service.list(other).stream().allMatch(t -> t.prefLabel().equals("Bestellung")));
        assertEquals(1, service.list(WS).size());
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        service.add(WS, new NewTerm("Gutschrift", "def a", null));
        service.add(WS, new NewTerm("Bestellung", "def b", null));

        List<Term> all = service.list(WS);

        assertEquals(2, all.size());
        assertEquals("Gutschrift", all.get(0).prefLabel());
        assertEquals("Bestellung", all.get(1).prefLabel());
    }

    @Test
    void getReturnsPersistedTerm() {
        TermCode code = service.add(WS, new NewTerm("Gutschrift", "def a", null)).code();

        assertTrue(service.get(WS, code).isPresent());
        assertEquals("Gutschrift", service.get(WS, code).orElseThrow().prefLabel());
    }

    @Test
    void getIsEmptyForUnknownCode() {
        assertFalse(service.get(WS, new TermCode("TERM-99")).isPresent());
    }

    @Test
    void addPassesThroughActorFacet() {
        ActorFacet facet = new ActorFacet(ActorKind.HUMAN, "Sachbearbeiter");

        Term added = service.add(WS, new NewTerm("Kunde", "Person, die eine Bestellung aufgibt.", facet));

        assertEquals(facet, added.actorFacet());
        assertEquals(facet, repository.findByCode(WS, added.code()).orElseThrow().actorFacet());
    }

    @Test
    void addWithoutActorFacetLeavesItNull() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null));

        assertNull(added.actorFacet());
    }

    /**
     * Issue #77 nachtrag (slimmed by #84): a sibling bounded context's driving adapter resolves
     * opaque term identities back to their identity and business code (e.g. to render
     * {@code TERM-N} for display) - in one batch, not per-id.
     */
    @Test
    void getByIdResolvesKnownIdentitiesInOneBatch() {
        Term first = service.add(WS, new NewTerm("Gutschrift", "def a", null));
        Term second = service.add(WS, new NewTerm("Bestellung", "def b", null));

        List<ResolveTerms.ResolvedTerm> resolved = service.getById(WS, first.id().value(), second.id().value());

        assertEquals(2, resolved.size());
        assertTrue(resolved.contains(new ResolveTerms.ResolvedTerm(first.id().value(), first.code())));
        assertTrue(resolved.contains(new ResolveTerms.ResolvedTerm(second.id().value(), second.code())));
    }

    /**
     * The port never rejects an unresolvable id - it simply omits it from the result, so the
     * caller (not this port) decides what "missing" means for its own display.
     */
    @Test
    void getByIdSilentlyOmitsUnknownIdentities() {
        Term known = service.add(WS, new NewTerm("Gutschrift", "def a", null));
        ResourceId unknown = ResourceId.of("https://w3id.org/arknet/id/does-not-exist");

        List<ResolveTerms.ResolvedTerm> resolved = service.getById(WS, known.id().value(), unknown);

        assertEquals(List.of(new ResolveTerms.ResolvedTerm(known.id().value(), known.code())), resolved);
    }

    @Test
    void getByIdWithNoIdsReturnsAnEmptyList() {
        assertEquals(List.of(), service.getById(WS));
    }

    @Test
    void getByIdIsScopedPerWorkspace() {
        Term inWs = service.add(WS, new NewTerm("Gutschrift", "def a", null));
        ProjectId other = new ProjectId("other");

        assertEquals(List.of(), service.getById(other, inWs.id().value()));
    }

    @Test
    void updateChangesOnlyPrefLabel() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null));

        Term updated = service.update(WS, added.code(), "Erstattung", null, null);

        assertEquals("Erstattung", updated.prefLabel());
        assertEquals("def a", updated.definition());
    }

    @Test
    void updateChangesOnlyDefinition() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null));

        Term updated = service.update(WS, added.code(), null, "def b", null);

        assertEquals("Gutschrift", updated.prefLabel());
        assertEquals("def b", updated.definition());
    }

    @Test
    void updateChangesOnlyActorFacet() {
        Term added = service.add(WS, new NewTerm("Kunde", "def a", null));
        ActorFacet facet = new ActorFacet(ActorKind.HUMAN, "Sachbearbeiter");

        Term updated = service.update(WS, added.code(), null, null, facet);

        assertEquals("Kunde", updated.prefLabel());
        assertEquals("def a", updated.definition());
        assertEquals(facet, updated.actorFacet());
    }

    @Test
    void updateWithNullActorFacetLeavesAnAlreadySetOneUnchanged() {
        ActorFacet facet = new ActorFacet(ActorKind.HUMAN, "Sachbearbeiter");
        Term added = service.add(WS, new NewTerm("Kunde", "def a", facet));

        Term updated = service.update(WS, added.code(), "Bestandskunde", null, null);

        assertEquals(facet, updated.actorFacet());
    }

    @Test
    void updateChangesAllFieldsAtOnce() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null));
        ActorFacet facet = new ActorFacet(ActorKind.SYSTEM, "Zahlungsdienst");

        Term updated = service.update(WS, added.code(), "Erstattung", "def b", facet);

        assertEquals("Erstattung", updated.prefLabel());
        assertEquals("def b", updated.definition());
        assertEquals(facet, updated.actorFacet());
    }

    @Test
    void updateKeepsIdentityAndCodeUnchanged() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null));

        Term updated = service.update(WS, added.code(), "Erstattung", "def b", null);

        assertEquals(added.id(), updated.id());
        assertEquals(added.code(), updated.code());
    }

    @Test
    void updatePersistsTheChange() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null));

        service.update(WS, added.code(), "Erstattung", null, null);

        assertEquals("Erstattung", repository.findByCode(WS, added.code()).orElseThrow().prefLabel());
    }

    @Test
    void updateThrowsWhenCodeIsUnknown() {
        assertThrows(TermNotFoundException.class,
                () -> service.update(WS, new TermCode("TERM-99"), "Erstattung", null, null));
    }
}
