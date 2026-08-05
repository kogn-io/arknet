// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;

/**
 * Regression test: {@link TermService#add} used to compute the next business code
 * ({@code TERM-N}) client-side via {@code nextCode()} and then {@code create()} it with no retry,
 * so two racing {@code term_add} calls in the same project both computed the same candidate code
 * and one of two well-formed callers saw the out-adapter's in-transaction uniqueness guard fire as
 * a caller-visible {@code DuplicateTermCodeException} - even though nothing about its own request
 * was wrong.
 *
 * <p>The race is reproduced deterministically, without real threads: a {@link TermRepository}
 * decorator runs an "other caller"'s complete add exactly once, right after the first {@code
 * findAll} (which {@code nextCode()} reads) returns - pinning the exact interleaving instead of
 * relying on thread scheduling, which would make the test flaky. Mirrors {@code
 * RequirementServiceConcurrencyTest}, the one type that already guarded this.</p>
 */
class TermServiceConcurrencyTest {

    private static final ProjectId WS = new ProjectId("test-project");

    private InMemoryTermRepository store;
    /**
     * Shared across {@link #otherCaller} and the "under test" service, mirroring the composition
     * root, which wires exactly one {@link ResourceIdFactory} bean shared by all concurrent
     * callers. Two independent factories would mint colliding identities for the two concurrently
     * added terms, a test artefact this bug does not have.
     */
    private SequentialResourceIdFactory resourceIdFactory;
    /** Represents the concurrent "other" caller; always writes straight through to {@code store}. */
    private TermService otherCaller;

    @BeforeEach
    void setUp() {
        store = new InMemoryTermRepository();
        resourceIdFactory = new SequentialResourceIdFactory();
        otherCaller = new TermService(store, resourceIdFactory);
    }

    @Test
    void concurrentAddCallsBothGetDistinctCodesInsteadOfOneFailing() {
        RaceOnFirstFindAllRepository racing =
                new RaceOnFirstFindAllRepository(store, () -> otherCaller.add(WS, newTerm(), "en"));
        TermService underTest = new TermService(racing, resourceIdFactory);

        Term result = underTest.add(WS, newTerm(), "en");

        assertEquals(new TermCode("TERM-2"), result.code());
        assertEquals(2, store.findAll(WS).size());
        assertTrue(store.findAll(WS).stream()
                .map(Term::code)
                .toList()
                .containsAll(List.of(new TermCode("TERM-1"), new TermCode("TERM-2"))));
    }

    private static NewTerm newTerm() {
        return new NewTerm("Gutschrift", "Rueckerstattung eines bereits gezahlten Betrags.", null, null);
    }

    /** Deterministic fake minting sequential opaque ids, so tests never depend on randomness. */
    private static final class SequentialResourceIdFactory implements ResourceIdFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public ResourceId newId() {
            return ResourceId.of("https://w3id.org/arknet/id/fake-" + counter.incrementAndGet());
        }
    }

    /**
     * Decorator that runs {@code injection} exactly once, synchronously, right after the first
     * {@link #findAll} call returns - {@code nextCode()} reads via {@code findAll}, so this
     * simulates a concurrent {@code term_add} committing between this caller's code computation and
     * its own {@code create()}.
     */
    private static final class RaceOnFirstFindAllRepository implements TermRepository {

        private final TermRepository delegate;
        private final Runnable injection;
        private boolean injected;

        RaceOnFirstFindAllRepository(TermRepository delegate, Runnable injection) {
            this.delegate = delegate;
            this.injection = injection;
        }

        @Override
        public void create(ProjectId projectId, Term term, String language) {
            delegate.create(projectId, term, language);
        }

        @Override
        public Term update(ProjectId projectId, TermCode code, String prefLabel, String definition,
                ActorFacet actorFacet, String language, String defaultLanguage, Optional<TermCode> broader) {
            return delegate.update(projectId, code, prefLabel, definition, actorFacet, language, defaultLanguage,
                    broader);
        }

        @Override
        public Optional<Term> findByCode(ProjectId projectId, TermCode code, String displayLocale) {
            return delegate.findByCode(projectId, code, displayLocale);
        }

        @Override
        public List<Term> findAll(ProjectId projectId) {
            List<Term> result = delegate.findAll(projectId);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }

        @Override
        public List<ResolveTerms.ResolvedTerm> findByIds(ProjectId projectId, List<ResourceId> ids) {
            return delegate.findByIds(projectId, ids);
        }
    }
}
