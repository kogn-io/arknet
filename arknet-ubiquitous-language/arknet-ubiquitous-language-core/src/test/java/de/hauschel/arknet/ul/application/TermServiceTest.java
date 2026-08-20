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
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.MissingDefaultLanguageException;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;
import de.hauschel.arknet.ul.domain.TermNotFoundException;

/**
 * Policy tests for {@link TermService}: opaque identity minting, code assignment, listing and
 * lookup-by-code, exercised against an in-memory fake repository.
 */
class TermServiceTest {

    private static final ProjectId WS = new ProjectId("test-project");
    /**
     * A project default language for tests that do not themselves exercise issue #258's
     * language-resolution policy - passed explicitly so a {@code null} {@code language} argument
     * still resolves instead of throwing.
     */
    private static final String DEFAULT_LANGUAGE = "en";

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
                "Rueckerstattung eines bereits gezahlten Betrags.", null, null), DEFAULT_LANGUAGE);

        assertEquals(new TermCode("TERM-1"), added.code());
        assertEquals("Gutschrift", added.prefLabel());
        assertEquals("Rueckerstattung eines bereits gezahlten Betrags.", added.definition());
        assertEquals(added, repository.findByCode(WS, added.code(), null).orElseThrow());
    }

    @Test
    void addMintsAFreshOpaqueIdentityPerTerm() {
        Term first = service.add(WS, new NewTerm("Gutschrift", "def a", null, null), DEFAULT_LANGUAGE);
        Term second = service.add(WS, new NewTerm("Bestellung", "def b", null, null), DEFAULT_LANGUAGE);

        // Identity is opaque and minted once - never derived from the (sequential) code.
        assertNotEquals(first.id(), second.id());
        assertTrue(first.id().value().value().startsWith("https://"));
    }

    @Test
    void addNumbersRunSequentially() {
        TermCode first = service.add(WS, new NewTerm("Gutschrift", "def a", null, null), DEFAULT_LANGUAGE).code();
        TermCode second = service.add(WS, new NewTerm("Bestellung", "def b", null, null), DEFAULT_LANGUAGE).code();

        assertEquals(new TermCode("TERM-1"), first);
        assertEquals(new TermCode("TERM-2"), second);
    }

    @Test
    void addIsScopedPerProject() {
        ProjectId other = new ProjectId("other");
        service.add(WS, new NewTerm("Gutschrift", "def a", null, null), DEFAULT_LANGUAGE);

        Term inOther = service.add(other, new NewTerm("Bestellung", "def b", null, null), DEFAULT_LANGUAGE);

        assertEquals(new TermCode("TERM-1"), inOther.code());
        assertTrue(service.list(other, null).stream().allMatch(t -> t.prefLabel().equals("Bestellung")));
        assertEquals(1, service.list(WS, null).size());
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        service.add(WS, new NewTerm("Gutschrift", "def a", null, null), DEFAULT_LANGUAGE);
        service.add(WS, new NewTerm("Bestellung", "def b", null, null), DEFAULT_LANGUAGE);

        List<Term> all = service.list(WS, null);

        assertEquals(2, all.size());
        assertEquals("Gutschrift", all.get(0).prefLabel());
        assertEquals("Bestellung", all.get(1).prefLabel());
    }

    @Test
    void getReturnsPersistedTerm() {
        TermCode code = service.add(WS, new NewTerm("Gutschrift", "def a", null, null), DEFAULT_LANGUAGE).code();

        assertTrue(service.get(WS, code, null).isPresent());
        assertEquals("Gutschrift", service.get(WS, code, null).orElseThrow().prefLabel());
    }

    @Test
    void getIsEmptyForUnknownCode() {
        assertFalse(service.get(WS, new TermCode("TERM-99"), null).isPresent());
    }

    @Test
    void addPassesThroughActorFacet() {
        ActorFacet facet = new ActorFacet(ActorKind.HUMAN, "Sachbearbeiter");

        Term added = service.add(WS, new NewTerm("Kunde", "Person, die eine Bestellung aufgibt.", facet, null), DEFAULT_LANGUAGE);

        assertEquals(facet, added.actorFacet());
        assertEquals(facet, repository.findByCode(WS, added.code(), null).orElseThrow().actorFacet());
    }

    @Test
    void addWithoutActorFacetLeavesItNull() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null, null), DEFAULT_LANGUAGE);

        assertNull(added.actorFacet());
    }

    /**
     * Issue #258, decision 2: a write without an explicit {@code language} falls back to the
     * target project's configured {@code defaultLanguage} instead of writing an untagged literal.
     */
    @Test
    void addWithoutLanguageFallsBackToTheProjectsDefaultLanguage() {
        SpyTermRepository spy = new SpyTermRepository(repository);
        TermService serviceUnderTest = new TermService(spy, new UuidResourceIdFactory());

        serviceUnderTest.add(WS, new NewTerm("Gutschrift", "def a", null, null), "de");

        assertEquals("de", spy.lastCreateLanguage);
    }

    /**
     * Issue #258, decision 1: a write without an explicit {@code language}, targeting a project
     * with no configured default either, is rejected instead of silently writing an untagged
     * literal - and nothing is persisted.
     */
    @Test
    void addWithoutLanguageAndWithoutAProjectDefaultIsRejected() {
        assertThrows(MissingDefaultLanguageException.class,
                () -> service.add(WS, new NewTerm("Gutschrift", "def a", null, null), null));

        assertEquals(List.of(), service.list(WS, null));
    }

    /** Mirrors {@link #addWithoutLanguageFallsBackToTheProjectsDefaultLanguage}, for {@code update}. */
    @Test
    void updateWithoutLanguageFallsBackToTheProjectsDefaultLanguage() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null, null), DEFAULT_LANGUAGE);
        SpyTermRepository spy = new SpyTermRepository(repository);
        TermService serviceUnderTest = new TermService(spy, new UuidResourceIdFactory());

        serviceUnderTest.update(WS, added.code(), "Erstattung", null, null, null, "de", null);

        assertEquals("de", spy.lastUpdateLanguage);
    }

    /** Mirrors {@link #addWithoutLanguageAndWithoutAProjectDefaultIsRejected}, for {@code update}. */
    @Test
    void updateWithoutLanguageAndWithoutAProjectDefaultIsRejected() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null, null), DEFAULT_LANGUAGE);

        assertThrows(MissingDefaultLanguageException.class,
                () -> service.update(WS, added.code(), "Erstattung", null, null, null, null, null));

        assertEquals("Gutschrift", service.get(WS, added.code(), null).orElseThrow().prefLabel());
    }

    /**
     * A no-op {@code update} (neither {@code prefLabel} nor {@code definition} supplied) must
     * never consult {@code defaultLanguage} at all - unlike {@code RequirementService}/{@code
     * UseCaseService}, this method has no read-modify-write comparison to fall back on, so this is
     * the only guard against a spuriously rejected pure actor-facette correction.
     */
    @Test
    void updateTouchingOnlyTheActorFacetNeverConsultsTheProjectDefault() {
        Term added = service.add(WS, new NewTerm("Kunde", "def a", null, null), DEFAULT_LANGUAGE);
        ActorFacet facet = new ActorFacet(ActorKind.HUMAN, "Sachbearbeiter");

        Term updated = service.update(WS, added.code(), null, null, facet, null, null, null);

        assertEquals(facet, updated.actorFacet());
    }

    /**
     * A sibling bounded context's driving adapter resolves opaque term identities back to their
     * identity and business code (e.g. to render {@code TERM-N} for display) - in one batch, not
     * per-id.
     */
    @Test
    void resolveReturnsKnownIdentitiesInOneBatch() {
        Term first = service.add(WS, new NewTerm("Gutschrift", "def a", null, null), DEFAULT_LANGUAGE);
        Term second = service.add(WS, new NewTerm("Bestellung", "def b", null, null), DEFAULT_LANGUAGE);

        List<ResolveTerms.ResolvedTerm> resolved = service.resolve(WS, first.id().value(), second.id().value());

        assertEquals(2, resolved.size());
        assertTrue(resolved.contains(new ResolveTerms.ResolvedTerm(first.id().value(), first.code())));
        assertTrue(resolved.contains(new ResolveTerms.ResolvedTerm(second.id().value(), second.code())));
    }

    /**
     * The port never rejects an unresolvable id - it simply omits it from the result, so the
     * caller (not this port) decides what "missing" means for its own display.
     */
    @Test
    void resolveSilentlyOmitsUnknownIdentities() {
        Term known = service.add(WS, new NewTerm("Gutschrift", "def a", null, null), DEFAULT_LANGUAGE);
        ResourceId unknown = ResourceId.of("https://w3id.org/arknet/id/does-not-exist");

        List<ResolveTerms.ResolvedTerm> resolved = service.resolve(WS, known.id().value(), unknown);

        assertEquals(List.of(new ResolveTerms.ResolvedTerm(known.id().value(), known.code())), resolved);
    }

    @Test
    void resolveWithNoIdsReturnsAnEmptyList() {
        assertEquals(List.of(), service.resolve(WS));
    }

    @Test
    void resolveIsScopedPerProject() {
        Term inWs = service.add(WS, new NewTerm("Gutschrift", "def a", null, null), DEFAULT_LANGUAGE);
        ProjectId other = new ProjectId("other");

        assertEquals(List.of(), service.resolve(other, inWs.id().value()));
    }

    @Test
    void updateChangesOnlyPrefLabel() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null, null), DEFAULT_LANGUAGE);

        Term updated = service.update(WS, added.code(), "Erstattung", null, null, null, DEFAULT_LANGUAGE, null);

        assertEquals("Erstattung", updated.prefLabel());
        assertEquals("def a", updated.definition());
    }

    @Test
    void updateChangesOnlyDefinition() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null, null), DEFAULT_LANGUAGE);

        Term updated = service.update(WS, added.code(), null, "def b", null, null, DEFAULT_LANGUAGE, null);

        assertEquals("Gutschrift", updated.prefLabel());
        assertEquals("def b", updated.definition());
    }

    @Test
    void updateChangesOnlyActorFacet() {
        Term added = service.add(WS, new NewTerm("Kunde", "def a", null, null), DEFAULT_LANGUAGE);
        ActorFacet facet = new ActorFacet(ActorKind.HUMAN, "Sachbearbeiter");

        Term updated = service.update(WS, added.code(), null, null, facet, null, DEFAULT_LANGUAGE, null);

        assertEquals("Kunde", updated.prefLabel());
        assertEquals("def a", updated.definition());
        assertEquals(facet, updated.actorFacet());
    }

    @Test
    void updateWithNullActorFacetLeavesAnAlreadySetOneUnchanged() {
        ActorFacet facet = new ActorFacet(ActorKind.HUMAN, "Sachbearbeiter");
        Term added = service.add(WS, new NewTerm("Kunde", "def a", facet, null), DEFAULT_LANGUAGE);

        Term updated = service.update(WS, added.code(), "Bestandskunde", null, null, null, DEFAULT_LANGUAGE, null);

        assertEquals(facet, updated.actorFacet());
    }

    @Test
    void updateChangesAllFieldsAtOnce() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null, null), DEFAULT_LANGUAGE);
        ActorFacet facet = new ActorFacet(ActorKind.SYSTEM, "Zahlungsdienst");

        Term updated = service.update(WS, added.code(), "Erstattung", "def b", facet, null, DEFAULT_LANGUAGE, null);

        assertEquals("Erstattung", updated.prefLabel());
        assertEquals("def b", updated.definition());
        assertEquals(facet, updated.actorFacet());
    }

    @Test
    void updateKeepsIdentityAndCodeUnchanged() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null, null), DEFAULT_LANGUAGE);

        Term updated = service.update(WS, added.code(), "Erstattung", "def b", null, null, DEFAULT_LANGUAGE, null);

        assertEquals(added.id(), updated.id());
        assertEquals(added.code(), updated.code());
    }

    @Test
    void updatePersistsTheChange() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null, null), DEFAULT_LANGUAGE);

        service.update(WS, added.code(), "Erstattung", null, null, null, DEFAULT_LANGUAGE, null);

        assertEquals("Erstattung", repository.findByCode(WS, added.code(), null).orElseThrow().prefLabel());
    }

    @Test
    void updateThrowsWhenCodeIsUnknown() {
        assertThrows(TermNotFoundException.class,
                () -> service.update(WS, new TermCode("TERM-99"), "Erstattung", null, null, null, DEFAULT_LANGUAGE, null));
    }

    /** {@code add}'s optional {@code broader} command field passes straight through. */
    @Test
    void addPassesTheBroaderCodeThrough() {
        TermCode broader = service.add(WS, new NewTerm("Actor", "def a", null, null), DEFAULT_LANGUAGE).code();

        Term narrower = service.add(WS, new NewTerm("Human Actor", "def b", null, null, broader), DEFAULT_LANGUAGE);

        assertEquals(broader, narrower.broader());
    }

    /**
     * {@code update}'s {@code broader} argument is a {@code null}-or-{@link Optional} tri-state
     * (issue #252) - {@code update} must pass it through to the repository entirely unmerged,
     * exactly like every other field {@link #updateNeverReadsBeforeDelegatingToTheRepository}
     * already guards.
     */
    @Test
    void updatePassesTheBroaderOptionalThroughUnmerged() {
        TermCode broader = service.add(WS, new NewTerm("Actor", "def a", null, null), DEFAULT_LANGUAGE).code();
        Term added = service.add(WS, new NewTerm("Human Actor", "def b", null, null, broader), DEFAULT_LANGUAGE);
        assertEquals(broader, added.broader());

        Term updated = service.update(
                WS, added.code(), null, null, null, null, DEFAULT_LANGUAGE, Optional.empty());

        assertNull(updated.broader());
    }

    /**
     * Guards against a regression back to a read-then-merge {@link TermService#update}: because
     * {@link InMemoryTermRepository#update} itself merges via null-coalescing, a plain state
     * assertion against it cannot tell a pass-through {@code update()} apart from one that first
     * reads via {@code findByCode}/{@code findAll} and folds the result into a merged {@link Term}.
     * This test instead spies on the repository interactions: it seeds the delegate directly
     * (bypassing the spy) and asserts that {@code update()} calls {@code repository.update(...)}
     * without ever calling {@code findByCode} or {@code findAll} first.
     */
    @Test
    void updateNeverReadsBeforeDelegatingToTheRepository() {
        InMemoryTermRepository delegate = new InMemoryTermRepository();
        Term seeded = new Term(new TermId(new UuidResourceIdFactory().newId()),
                new TermCode("TERM-1"), "Gutschrift", "def a", null);
        delegate.create(WS, seeded, null);
        SpyTermRepository spy = new SpyTermRepository(delegate);
        TermService serviceUnderTest = new TermService(spy, new UuidResourceIdFactory());

        serviceUnderTest.update(WS, seeded.code(), "Erstattung", null, null, null, DEFAULT_LANGUAGE, null);

        assertEquals(0, spy.findByCodeCalls);
        assertEquals(0, spy.findAllCalls);
        assertEquals("Erstattung", delegate.findByCode(WS, seeded.code(), null).orElseThrow().prefLabel());
    }

    /** Spy decorator counting {@code findByCode}/{@code findAll} calls, delegating everything else. */
    private static final class SpyTermRepository implements TermRepository {

        private final TermRepository delegate;
        private int findByCodeCalls;
        private int findAllCalls;
        private String lastCreateLanguage;
        private String lastUpdateLanguage;

        SpyTermRepository(TermRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public void create(ProjectId projectId, Term term, String language) {
            lastCreateLanguage = language;
            delegate.create(projectId, term, language);
        }

        @Override
        public Term update(ProjectId projectId, TermCode code, String prefLabel, String definition,
                ActorFacet actorFacet, String language, String defaultLanguage, Optional<TermCode> broader) {
            lastUpdateLanguage = language;
            return delegate.update(projectId, code, prefLabel, definition, actorFacet, language, defaultLanguage,
                    broader);
        }

        @Override
        public Optional<Term> findByCode(ProjectId projectId, TermCode code, String displayLocale) {
            findByCodeCalls++;
            return delegate.findByCode(projectId, code, displayLocale);
        }

        @Override
        public List<Term> findAll(ProjectId projectId, String displayLocale) {
            findAllCalls++;
            return delegate.findAll(projectId, displayLocale);
        }

        @Override
        public List<ResolveTerms.ResolvedTerm> findByIds(ProjectId projectId, List<ResourceId> ids) {
            return delegate.findByIds(projectId, ids);
        }

        @Override
        public void delete(ProjectId projectId, TermCode code) {
            delegate.delete(projectId, code);
        }
    }
}
