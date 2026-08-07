// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.req.application.port.in.AddConstraint.NewConstraint;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.application.port.out.RevisionToken;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintConcurrentlyModifiedException;
import de.hauschel.arknet.req.domain.ConstraintNotFoundException;
import de.hauschel.arknet.req.domain.ConstraintType;

/**
 * Concurrency tests for {@code ConstraintService#updateWithOptimisticRetry} - the read-modify-write
 * loop {@code constraint_update} runs (issue #313), and the only place in the constraint use cases
 * where a lost update could arise.
 *
 * <p>Races are reproduced deterministically, without real threads, exactly as
 * {@link RequirementServiceConcurrencyTest} does it: a {@link ConstraintRepository} decorator runs
 * an "other caller"'s complete read-modify-write round trip exactly once, at the precise point in
 * the retry loop where a concurrent writer's commit would land between this caller's read and its
 * write. This pins the interleaving instead of relying on thread scheduling, which would make these
 * tests flaky.</p>
 *
 * <p>Kept apart from {@link ConstraintServiceTest} - which exercises the same {@link #update}
 * against an uncontended repository - because these tests are about the retry loop, not about the
 * text/language policy the loop applies.</p>
 */
class ConstraintServiceConcurrencyTest {

    private static final ProjectId WS = new ProjectId("test-project");
    /** These races are orthogonal to issue #258's default-language resolution - always given explicitly. */
    private static final String DEFAULT_LANGUAGE = "en";

    private InMemoryConstraintRepository store;
    private SequentialResourceIdFactory resourceIdFactory;
    /** Represents the concurrent "other" caller; always writes straight through to {@code store}. */
    private ConstraintService otherCaller;

    @BeforeEach
    void setUp() {
        store = new InMemoryConstraintRepository();
        resourceIdFactory = new SequentialResourceIdFactory();
        otherCaller = new ConstraintService(store, resourceIdFactory);
    }

    /**
     * Lost update: two concurrent {@code constraint_update} calls correcting different fields of
     * the same constraint must both survive. Without the compare-and-set guard plus retry, the
     * second writer would blindly overwrite the first writer's already-committed statement with the
     * stale one it had read before that commit.
     */
    @Test
    void concurrentUpdatesOfDifferentFieldsBothSurvive() {
        ConstraintCode code = addTechnicalConstraint().code();
        RaceOnFirstReadRepository racing = new RaceOnFirstReadRepository(store,
                () -> otherCaller.update(WS, code, null, "Must run on the JVM, version 25 or newer",
                        DEFAULT_LANGUAGE, DEFAULT_LANGUAGE));
        ConstraintService underTest = new ConstraintService(racing, resourceIdFactory);

        Constraint result = underTest.update(WS, code, "JVM baseline", null, DEFAULT_LANGUAGE, DEFAULT_LANGUAGE);

        assertEquals("JVM baseline", result.title());
        assertEquals("Must run on the JVM, version 25 or newer", result.statement());
        Constraint stored = store.findByCode(WS, code, null).orElseThrow();
        assertEquals("JVM baseline", stored.title());
        assertEquals("Must run on the JVM, version 25 or newer", stored.statement());
    }

    /**
     * A read-modify-write that keeps losing the race on every single attempt (a repository whose
     * {@code compareAndUpdate} always reports a conflict) must fail loudly with
     * {@link ConstraintConcurrentlyModifiedException} instead of looping forever - and must
     * actually have retried {@link ConstraintService#MAX_RETRY_ATTEMPTS} times rather than giving
     * up on the very first attempt, which a stray {@code assertThrows} alone would not catch (a
     * retry loop accidentally removed down to a single try would still throw here).
     */
    @Test
    void updateGivesUpAfterExhaustingRetriesAgainstPermanentContention() {
        ConstraintCode code = addTechnicalConstraint().code();
        AlwaysConflictingRepository racing = new AlwaysConflictingRepository(store);
        ConstraintService underTest = new ConstraintService(racing, resourceIdFactory);

        assertThrows(ConstraintConcurrentlyModifiedException.class,
                () -> underTest.update(WS, code, "JVM baseline", null, DEFAULT_LANGUAGE, DEFAULT_LANGUAGE));

        assertEquals(ConstraintService.MAX_RETRY_ATTEMPTS, racing.compareAndUpdateAttempts());
    }

    private Constraint addTechnicalConstraint() {
        return otherCaller.add(WS,
                new NewConstraint("JVM", "Must run on the JVM", ConstraintType.TECHNICAL, DEFAULT_LANGUAGE),
                DEFAULT_LANGUAGE);
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
     * {@link #findCurrentByCode} call returns - simulating a concurrent caller whose own complete
     * read-modify-write round trip commits in the window between this caller's read and its own
     * write. Every other call, including every subsequent {@code findCurrentByCode}, delegates
     * unchanged, so the retry sees the concurrent writer's committed state.
     */
    private static final class RaceOnFirstReadRepository implements ConstraintRepository {

        private final ConstraintRepository delegate;
        private final Runnable injection;
        private boolean injected;

        RaceOnFirstReadRepository(ConstraintRepository delegate, Runnable injection) {
            this.delegate = delegate;
            this.injection = injection;
        }

        @Override
        public void create(ProjectId projectId, Constraint constraint, String language) {
            delegate.create(projectId, constraint, language);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Constraint updated,
                String titleLanguage, String statementLanguage, String defaultLanguage) {
            delegate.compareAndUpdate(projectId, expectedHead, updated, titleLanguage, statementLanguage,
                    defaultLanguage);
        }

        @Override
        public Optional<Constraint> findByCode(ProjectId projectId, ConstraintCode code, String displayLocale) {
            return delegate.findByCode(projectId, code, displayLocale);
        }

        @Override
        public Optional<CurrentConstraint> findCurrentByCode(ProjectId projectId, ConstraintCode code) {
            Optional<CurrentConstraint> result = delegate.findCurrentByCode(projectId, code);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }

        @Override
        public List<Constraint> findAll(ProjectId projectId, String displayLocale) {
            return delegate.findAll(projectId, displayLocale);
        }

        @Override
        public List<ResolveConstraints.ResolvedConstraint> findByIds(ProjectId projectId, List<ResourceId> ids) {
            return delegate.findByIds(projectId, ids);
        }
    }

    /** A repository whose {@code compareAndUpdate} always reports a conflict, never applying. */
    private static final class AlwaysConflictingRepository implements ConstraintRepository {

        private final ConstraintRepository delegate;
        private int compareAndUpdateAttempts;

        AlwaysConflictingRepository(ConstraintRepository delegate) {
            this.delegate = delegate;
        }

        int compareAndUpdateAttempts() {
            return compareAndUpdateAttempts;
        }

        @Override
        public void create(ProjectId projectId, Constraint constraint, String language) {
            delegate.create(projectId, constraint, language);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Constraint updated,
                String titleLanguage, String statementLanguage, String defaultLanguage) {
            compareAndUpdateAttempts++;
            // Still enforce "must exist", same as the real contract - only ever report a conflict.
            delegate.findByCode(projectId, updated.code(), null)
                    .orElseThrow(() -> new ConstraintNotFoundException(projectId, updated.code()));
            throw new ConstraintConcurrentlyModifiedException(projectId, updated.code());
        }

        @Override
        public Optional<Constraint> findByCode(ProjectId projectId, ConstraintCode code, String displayLocale) {
            return delegate.findByCode(projectId, code, displayLocale);
        }

        @Override
        public Optional<CurrentConstraint> findCurrentByCode(ProjectId projectId, ConstraintCode code) {
            return delegate.findCurrentByCode(projectId, code);
        }

        @Override
        public List<Constraint> findAll(ProjectId projectId, String displayLocale) {
            return delegate.findAll(projectId, displayLocale);
        }

        @Override
        public List<ResolveConstraints.ResolvedConstraint> findByIds(ProjectId projectId, List<ResourceId> ids) {
            return delegate.findByIds(projectId, ids);
        }
    }
}
