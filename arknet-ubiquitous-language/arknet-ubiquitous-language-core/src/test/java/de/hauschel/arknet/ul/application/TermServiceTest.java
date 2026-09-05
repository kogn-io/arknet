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
                "Rueckerstattung eines bereits gezahlten Betrags.", null), DEFAULT_LANGUAGE);

        assertEquals(new TermCode("TERM-1"), added.code());
        assertEquals("Gutschrift", added.prefLabel());
        assertEquals("Rueckerstattung eines bereits gezahlten Betrags.", added.definition());
        assertEquals(added, repository.findByCode(WS, added.code(), null).orElseThrow());
    }

    @Test
    void addMintsAFreshOpaqueIdentityPerTerm() {
        Term first = service.add(WS, new NewTerm("Gutschrift", "def a", null), DEFAULT_LANGUAGE);
        Term second = service.add(WS, new NewTerm("Bestellung", "def b", null), DEFAULT_LANGUAGE);

        // Identity is opaque and minted once - never derived from the (sequential) code.
        assertNotEquals(first.id(), second.id());
        assertTrue(first.id().value().value().startsWith("https://"));
    }

    @Test
    void addNumbersRunSequentially() {
        TermCode first = service.add(WS, new NewTerm("Gutschrift", "def a", null), DEFAULT_LANGUAGE).code();
        TermCode second = service.add(WS, new NewTerm("Bestellung", "def b", null), DEFAULT_LANGUAGE).code();

        assertEquals(new TermCode("TERM-1"), first);
        assertEquals(new TermCode("TERM-2"), second);
    }

    /**
     * Mutation-tests {@code nextCode}'s reliance on {@link TermRepository#findAllCodes} rather than
     * {@link TermRepository#findAll} (kogn-io/arknet#360): turn {@code nextCode} back into a count
     * over {@code findAll} and this goes red - the seeded {@code TERM-2} holds the project's highest
     * number while being invisible to {@code findAll}, exactly as a store-first concept
     * without {@code skos:prefLabel}/{@code skos:definition} is to the real out-adapter, so
     * {@code add} would mint {@code TERM-2} again instead of {@code TERM-3} and collide with a code
     * that is still very much taken.
     */
    @Test
    void addSkipsOverACodeThatIsAssignedButNotCurrentlyMaterialisable() {
        service.add(WS, new NewTerm("Gutschrift", "def a", null), DEFAULT_LANGUAGE);
        repository.seedUnmaterialisableCode(WS, new TermCode("TERM-2"));

        Term third = service.add(WS, new NewTerm("Bestellung", "def b", null), DEFAULT_LANGUAGE);

        assertEquals(new TermCode("TERM-3"), third.code());
    }

    @Test
    void addIsScopedPerProject() {
        ProjectId other = new ProjectId("other");
        service.add(WS, new NewTerm("Gutschrift", "def a", null), DEFAULT_LANGUAGE);

        Term inOther = service.add(other, new NewTerm("Bestellung", "def b", null), DEFAULT_LANGUAGE);

        assertEquals(new TermCode("TERM-1"), inOther.code());
        assertTrue(service.list(other, null).stream().allMatch(t -> t.prefLabel().equals("Bestellung")));
        assertEquals(1, service.list(WS, null).size());
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        service.add(WS, new NewTerm("Gutschrift", "def a", null), DEFAULT_LANGUAGE);
        service.add(WS, new NewTerm("Bestellung", "def b", null), DEFAULT_LANGUAGE);

        List<Term> all = service.list(WS, null);

        assertEquals(2, all.size());
        assertEquals("Gutschrift", all.get(0).prefLabel());
        assertEquals("Bestellung", all.get(1).prefLabel());
    }

    @Test
    void getReturnsPersistedTerm() {
        TermCode code = service.add(WS, new NewTerm("Gutschrift", "def a", null), DEFAULT_LANGUAGE).code();

        assertTrue(service.get(WS, code, null).isPresent());
        assertEquals("Gutschrift", service.get(WS, code, null).orElseThrow().prefLabel());
    }

    @Test
    void getIsEmptyForUnknownCode() {
        assertFalse(service.get(WS, new TermCode("TERM-99"), null).isPresent());
    }

    /**
     * Issue #258, decision 2: a write without an explicit {@code language} falls back to the
     * target project's configured {@code defaultLanguage} instead of writing an untagged literal.
     */
    @Test
    void addWithoutLanguageFallsBackToTheProjectsDefaultLanguage() {
        SpyTermRepository spy = new SpyTermRepository(repository);
        TermService serviceUnderTest = new TermService(spy, new UuidResourceIdFactory());

        serviceUnderTest.add(WS, new NewTerm("Gutschrift", "def a", null), "de");

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
                () -> service.add(WS, new NewTerm("Gutschrift", "def a", null), null));

        assertEquals(List.of(), service.list(WS, null));
    }

    /** Mirrors {@link #addWithoutLanguageFallsBackToTheProjectsDefaultLanguage}, for {@code update}. */
    @Test
    void updateWithoutLanguageFallsBackToTheProjectsDefaultLanguage() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null), DEFAULT_LANGUAGE);
        SpyTermRepository spy = new SpyTermRepository(repository);
        TermService serviceUnderTest = new TermService(spy, new UuidResourceIdFactory());

        serviceUnderTest.update(WS, added.code(), "Erstattung", null, null, "de", null, null);

        assertEquals("de", spy.lastUpdateLanguage);
    }

    /** Mirrors {@link #addWithoutLanguageAndWithoutAProjectDefaultIsRejected}, for {@code update}. */
    @Test
    void updateWithoutLanguageAndWithoutAProjectDefaultIsRejected() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null), DEFAULT_LANGUAGE);

        assertThrows(MissingDefaultLanguageException.class,
                () -> service.update(WS, added.code(), "Erstattung", null, null, null, null, null));

        assertEquals("Gutschrift", service.get(WS, added.code(), null).orElseThrow().prefLabel());
    }

    /**
     * A no-op-for-language {@code update} (neither {@code prefLabel} nor {@code definition}
     * supplied, only {@code broader}) must never consult {@code defaultLanguage} at all - unlike
     * {@code RequirementService}/{@code UseCaseService}, this method has no read-modify-write
     * comparison to fall back on, so this is the only guard against a spuriously rejected pure
     * broader-term correction.
     */
    @Test
    void updateTouchingOnlyBroaderNeverConsultsTheProjectDefault() {
        TermCode broaderCode = service.add(WS, new NewTerm("Actor", "def a", null), DEFAULT_LANGUAGE).code();
        Term added = service.add(WS, new NewTerm("Kunde", "def a", null), DEFAULT_LANGUAGE);

        Term updated = service.update(WS, added.code(), null, null, null, null, Optional.of(broaderCode), null);

        assertEquals(broaderCode, updated.broader());
    }

    // ---- skos:related, read symmetrically (kogn-io/arknet#420) -------------------------------

    /**
     * The forward direction alone is written, but {@code term_get} must show the relation from
     * either end: TERM-2 names TERM-1, and TERM-1 - which asserts nothing itself - still reports
     * TERM-2 as its peer.
     */
    @Test
    void getMergesTheBackwardDirectionOfTheSymmetricRelation() {
        Term first = service.add(WS, new NewTerm("Projekt", "def a", null), DEFAULT_LANGUAGE);
        Term second = service.add(WS, new NewTerm("Anker", "def b", null, null, List.of(first.code())),
                DEFAULT_LANGUAGE);

        assertEquals(List.of(second.code()), service.get(WS, first.code(), null).orElseThrow().related());
        assertEquals(List.of(first.code()), service.get(WS, second.code(), null).orElseThrow().related());
    }

    /** A peer reachable in both directions is named once, not twice. */
    @Test
    void getDoesNotReportAMutuallyAssertedPeerTwice() {
        Term first = service.add(WS, new NewTerm("Projekt", "def a", null), DEFAULT_LANGUAGE);
        Term second = service.add(WS, new NewTerm("Anker", "def b", null, null, List.of(first.code())),
                DEFAULT_LANGUAGE);
        service.update(WS, first.code(), null, null, null, DEFAULT_LANGUAGE, null, List.of(second.code()));

        assertEquals(List.of(second.code()), service.get(WS, first.code(), null).orElseThrow().related());
    }

    /** {@code term_list} shows the same symmetric view {@code term_get} does, without a reverse read. */
    @Test
    void listMergesTheBackwardDirectionToo() {
        Term first = service.add(WS, new NewTerm("Projekt", "def a", null), DEFAULT_LANGUAGE);
        service.add(WS, new NewTerm("Anker", "def b", null, null, List.of(first.code())), DEFAULT_LANGUAGE);

        List<Term> listed = service.list(WS, null);

        assertEquals(List.of(new TermCode("TERM-2")), listed.get(0).related());
        assertEquals(List.of(new TermCode("TERM-1")), listed.get(1).related());
    }

    /** {@code term_update}'s own answer is merged the same way its {@code term_get} would be. */
    @Test
    void updateReturnsTheMergedRelatedView() {
        Term first = service.add(WS, new NewTerm("Projekt", "def a", null), DEFAULT_LANGUAGE);
        Term second = service.add(WS, new NewTerm("Anker", "def b", null, null, List.of(first.code())),
                DEFAULT_LANGUAGE);
        Term third = service.add(WS, new NewTerm("Dataset", "def c", null), DEFAULT_LANGUAGE);

        Term updated = service.update(WS, first.code(), null, null, null, DEFAULT_LANGUAGE, null,
                List.of(third.code()));

        assertEquals(List.of(second.code(), third.code()), updated.related());
    }

    /**
     * Clearing a term's own peers only removes what it asserts itself - the edge the other term
     * asserts towards it survives, and is therefore still reported.
     */
    @Test
    void clearingRelatedLeavesAnIncomingEdgeIntact() {
        Term first = service.add(WS, new NewTerm("Projekt", "def a", null), DEFAULT_LANGUAGE);
        Term second = service.add(WS, new NewTerm("Anker", "def b", null, null, List.of(first.code())),
                DEFAULT_LANGUAGE);

        Term updated = service.update(WS, first.code(), null, null, null, DEFAULT_LANGUAGE, null, List.of());

        assertEquals(List.of(second.code()), updated.related());
    }

    /** Rejected before the store is touched, not while rendering the result afterwards. */
    @Test
    void updateRejectsATermAsItsOwnRelatedPeer() {
        Term added = service.add(WS, new NewTerm("Projekt", "def a", null), DEFAULT_LANGUAGE);

        assertThrows(IllegalArgumentException.class, () -> service.update(
                WS, added.code(), null, null, null, DEFAULT_LANGUAGE, null, List.of(added.code())));
        assertEquals(List.of(), service.get(WS, added.code(), null).orElseThrow().related());
    }

    @Test
    void updateRejectsTheSameRelatedPeerTwice() {
        Term added = service.add(WS, new NewTerm("Projekt", "def a", null), DEFAULT_LANGUAGE);
        Term peer = service.add(WS, new NewTerm("Anker", "def b", null), DEFAULT_LANGUAGE);

        assertThrows(IllegalArgumentException.class, () -> service.update(
                WS, added.code(), null, null, null, DEFAULT_LANGUAGE, null,
                List.of(peer.code(), peer.code())));
    }

    /**
     * A sibling bounded context's driving adapter resolves opaque term identities back to their
     * identity and business code (e.g. to render {@code TERM-N} for display) - in one batch, not
     * per-id.
     */
    @Test
    void resolveReturnsKnownIdentitiesInOneBatch() {
        Term first = service.add(WS, new NewTerm("Gutschrift", "def a", null), DEFAULT_LANGUAGE);
        Term second = service.add(WS, new NewTerm("Bestellung", "def b", null), DEFAULT_LANGUAGE);

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
        Term known = service.add(WS, new NewTerm("Gutschrift", "def a", null), DEFAULT_LANGUAGE);
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
        Term inWs = service.add(WS, new NewTerm("Gutschrift", "def a", null), DEFAULT_LANGUAGE);
        ProjectId other = new ProjectId("other");

        assertEquals(List.of(), service.resolve(other, inWs.id().value()));
    }

    @Test
    void updateChangesOnlyPrefLabel() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null), DEFAULT_LANGUAGE);

        Term updated = service.update(WS, added.code(), "Erstattung", null, null, DEFAULT_LANGUAGE, null, null);

        assertEquals("Erstattung", updated.prefLabel());
        assertEquals("def a", updated.definition());
    }

    @Test
    void updateChangesOnlyDefinition() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null), DEFAULT_LANGUAGE);

        Term updated = service.update(WS, added.code(), null, "def b", null, DEFAULT_LANGUAGE, null, null);

        assertEquals("Gutschrift", updated.prefLabel());
        assertEquals("def b", updated.definition());
    }

    @Test
    void updateChangesBothFieldsAtOnce() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null), DEFAULT_LANGUAGE);

        Term updated = service.update(WS, added.code(), "Erstattung", "def b", null, DEFAULT_LANGUAGE, null, null);

        assertEquals("Erstattung", updated.prefLabel());
        assertEquals("def b", updated.definition());
    }

    @Test
    void updateKeepsIdentityAndCodeUnchanged() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null), DEFAULT_LANGUAGE);

        Term updated = service.update(WS, added.code(), "Erstattung", "def b", null, DEFAULT_LANGUAGE, null, null);

        assertEquals(added.id(), updated.id());
        assertEquals(added.code(), updated.code());
    }

    @Test
    void updatePersistsTheChange() {
        Term added = service.add(WS, new NewTerm("Gutschrift", "def a", null), DEFAULT_LANGUAGE);

        service.update(WS, added.code(), "Erstattung", null, null, DEFAULT_LANGUAGE, null, null);

        assertEquals("Erstattung", repository.findByCode(WS, added.code(), null).orElseThrow().prefLabel());
    }

    @Test
    void updateThrowsWhenCodeIsUnknown() {
        assertThrows(TermNotFoundException.class,
                () -> service.update(WS, new TermCode("TERM-99"), "Erstattung", null, null, DEFAULT_LANGUAGE, null, null));
    }

    /** {@code add}'s optional {@code broader} command field passes straight through. */
    @Test
    void addPassesTheBroaderCodeThrough() {
        TermCode broader = service.add(WS, new NewTerm("Actor", "def a", null), DEFAULT_LANGUAGE).code();

        Term narrower = service.add(WS, new NewTerm("Human Actor", "def b", null, broader), DEFAULT_LANGUAGE);

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
        TermCode broader = service.add(WS, new NewTerm("Actor", "def a", null), DEFAULT_LANGUAGE).code();
        Term added = service.add(WS, new NewTerm("Human Actor", "def b", null, broader), DEFAULT_LANGUAGE);
        assertEquals(broader, added.broader());

        Term updated = service.update(
                WS, added.code(), null, null, null, DEFAULT_LANGUAGE, Optional.empty(), null);

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

        serviceUnderTest.update(WS, seeded.code(), "Erstattung", null, null, DEFAULT_LANGUAGE, null, null);

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
                String language, String defaultLanguage, Optional<TermCode> broader, List<TermCode> related) {
            lastUpdateLanguage = language;
            return delegate.update(projectId, code, prefLabel, definition, language, defaultLanguage, broader,
                    related);
        }

        @Override
        public List<TermCode> findRelatedCodes(ProjectId projectId, TermId id) {
            return delegate.findRelatedCodes(projectId, id);
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
        public List<TermCode> findAllCodes(ProjectId projectId) {
            return delegate.findAllCodes(projectId);
        }

        @Override
        public List<ResolveTerms.ResolvedTerm> findByIds(ProjectId projectId, List<ResourceId> ids) {
            return delegate.findByIds(projectId, ids);
        }

        @Override
        public void delete(ProjectId projectId, TermCode code) {
            delegate.delete(projectId, code);
        }

        @Override
        public List<TermCode> findRetainedCodes(ProjectId projectId) {
            return delegate.findRetainedCodes(projectId);
        }
    }
}
