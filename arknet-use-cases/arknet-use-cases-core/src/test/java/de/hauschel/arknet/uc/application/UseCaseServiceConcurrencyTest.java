// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.application.port.in.UpdateUseCase.UseCaseCorrection;
import de.hauschel.arknet.uc.application.port.out.RevisionToken;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.TermRef;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseConcurrentlyModifiedException;
import de.hauschel.arknet.uc.domain.UseCaseNotFoundException;

/**
 * Regression test for the code-assignment race: {@link UseCaseService#add} used to compute the next business
 * code ({@code UCn}) client-side via {@code nextCode()} and then {@code create()} it with no
 * retry, so two racing {@code uc_add} calls in the same project both computed the same candidate
 * code and one of two well-formed callers saw the out-adapter's in-transaction uniqueness guard
 * fire as a caller-visible {@code DuplicateUseCaseCodeException} - even though nothing about its
 * own request was wrong.
 *
 * <p>The race is reproduced deterministically, without real threads: a {@link UseCaseRepository}
 * decorator runs an "other caller"'s complete add exactly once, right after the first {@code
 * findAllCodes} returns - {@code nextCode()} reads via {@code findAllCodes} rather than
 * {@code findAll} (kogn-io/arknet#360, see {@link UseCaseRepository#findAllCodes}'s own javadoc) -
 * pinning the exact interleaving instead of relying on thread scheduling, which would make the test
 * flaky. Mirrors {@code
 * RequirementServiceConcurrencyTest}, the one type that already guarded this.</p>
 */
class UseCaseServiceConcurrencyTest {

    private static final ProjectId WS = new ProjectId("test-project");
    /** These races are orthogonal to issue #258's default-language resolution - always given explicitly. */
    private static final String DEFAULT_LANGUAGE = "en";
    private static final ResourceId CUSTOMER_ID = ResourceId.of("https://w3id.org/arknet/id/actor-customer");
    private static final ResourceId TERM_1_ID = ResourceId.of("https://w3id.org/arknet/id/term-1");

    private InMemoryUseCaseRepository store;
    /**
     * Shared across {@link #otherCaller} and the "under test" service, mirroring the composition
     * root, which wires exactly one {@link ResourceIdFactory} bean shared by all concurrent
     * callers. Two independent factories would mint colliding identities for the two concurrently
     * added use cases, a test artefact this bug does not have.
     */
    private SequentialResourceIdFactory resourceIdFactory;
    private InMemoryActorLookup actorLookup;
    private InMemoryRequirementLookup requirementLookup;
    private InMemoryTermLookup termLookup;
    private InMemoryConstraintLookup constraintLookup;
    /** Represents the concurrent "other" caller; always writes straight through to {@code store}. */
    private UseCaseService otherCaller;

    @BeforeEach
    void setUp() {
        store = new InMemoryUseCaseRepository();
        resourceIdFactory = new SequentialResourceIdFactory();
        actorLookup = new InMemoryActorLookup();
        actorLookup.register("Customer", CUSTOMER_ID);
        requirementLookup = new InMemoryRequirementLookup();
        termLookup = new InMemoryTermLookup();
        termLookup.register("TERM-1", TERM_1_ID);
        constraintLookup = new InMemoryConstraintLookup();
        otherCaller = new UseCaseService(
                store, resourceIdFactory, requirementLookup, actorLookup, termLookup, constraintLookup);
    }

    @Test
    void concurrentAddCallsBothGetDistinctCodesInsteadOfOneFailing() {
        RaceOnFirstFindAllCodesRepository racing =
                new RaceOnFirstFindAllCodesRepository(store, () -> otherCaller.add(WS, newUseCase(), DEFAULT_LANGUAGE));
        UseCaseService underTest = new UseCaseService(
                racing, resourceIdFactory, requirementLookup, actorLookup, termLookup, constraintLookup);

        UseCase result = underTest.add(WS, newUseCase(), DEFAULT_LANGUAGE);

        assertEquals(new UseCaseCode("UC2"), result.code());
        assertEquals(2, store.findAll(WS, null).size());
        assertTrue(store.findAll(WS, null).stream()
                .map(UseCase::code)
                .toList()
                .containsAll(List.of(new UseCaseCode("UC1"), new UseCaseCode("UC2"))));
    }

    /**
     * Regression test for {@code updateWithOptimisticRetry}: a concurrent writer
     * that commits a different field between this caller's read and its own write must cost the
     * caller nothing and lose neither field, mirroring {@code
     * RequirementServiceConcurrencyTest#updateSurvivesAConcurrentLinkTermBetweenReadAndWrite}.
     */
    @Test
    void updateSurvivesAConcurrentUpdateOfADifferentFieldBetweenReadAndWrite() {
        UseCaseCode code = otherCaller.add(WS, newUseCase(), DEFAULT_LANGUAGE).code();
        RaceOnFirstReadRepository racing = new RaceOnFirstReadRepository(store,
                () -> otherCaller.update(WS, code, UseCaseCorrection.builder()
                        .trigger("Concurrent trigger")
                        .build(), DEFAULT_LANGUAGE));
        UseCaseService underTest = new UseCaseService(
                racing, resourceIdFactory, requirementLookup, actorLookup, termLookup, constraintLookup);

        UseCase result = underTest.update(WS, code, UseCaseCorrection.builder()
                .precondition("Racing precondition")
                .build(), DEFAULT_LANGUAGE);

        assertEquals("Concurrent trigger", result.trigger());
        assertEquals("Racing precondition", result.precondition());
        UseCase stored = store.findByCode(WS, code, null).orElseThrow();
        assertEquals("Concurrent trigger", stored.trigger());
        assertEquals("Racing precondition", stored.precondition());
    }

    /**
     * Regression test for {@code updateWithOptimisticRetry}, exercised via {@code linkTerm}
     * (issue #329) rather than {@code update}: a concurrent writer that commits a different field
     * between this caller's read and its own write must cost neither caller their own change.
     * Mirrors {@code RequirementServiceConcurrencyTest#updateSurvivesAConcurrentLinkTermBetweenReadAndWrite}.
     */
    @Test
    void updateSurvivesAConcurrentLinkTermBetweenReadAndWrite() {
        UseCaseCode code = otherCaller.add(WS, newUseCase(), DEFAULT_LANGUAGE).code();
        RaceOnFirstReadRepository racing = new RaceOnFirstReadRepository(store,
                () -> otherCaller.linkTerm(WS, code, "TERM-1"));
        UseCaseService underTest = new UseCaseService(
                racing, resourceIdFactory, requirementLookup, actorLookup, termLookup, constraintLookup);

        UseCase result = underTest.update(WS, code, UseCaseCorrection.builder()
                .trigger("Concurrent trigger")
                .build(), DEFAULT_LANGUAGE);

        assertEquals("Concurrent trigger", result.trigger());
        assertEquals(List.of(new TermRef(TERM_1_ID)), result.usesTerms());
        UseCase stored = store.findByCode(WS, code, null).orElseThrow();
        assertEquals("Concurrent trigger", stored.trigger());
        assertEquals(List.of(new TermRef(TERM_1_ID)), stored.usesTerms());
    }

    /**
     * A read-modify-write that keeps losing the race on every single attempt (a repository whose
     * {@code compareAndUpdate} always reports a conflict) must fail loudly with {@link
     * UseCaseConcurrentlyModifiedException} instead of looping forever.
     */
    @Test
    void updateGivesUpAfterExhaustingRetriesAgainstPermanentContention() {
        UseCaseCode code = otherCaller.add(WS, newUseCase(), DEFAULT_LANGUAGE).code();
        AlwaysConflictingRepository racing = new AlwaysConflictingRepository(store);
        UseCaseService underTest = new UseCaseService(
                racing, resourceIdFactory, requirementLookup, actorLookup, termLookup, constraintLookup);

        assertThrows(UseCaseConcurrentlyModifiedException.class,
                () -> underTest.update(WS, code, UseCaseCorrection.builder()
                        .trigger("New trigger")
                        .build(), DEFAULT_LANGUAGE));

        assertEquals(UseCaseService.MAX_RETRY_ATTEMPTS, racing.compareAndUpdateAttempts());
    }

    private static NewUseCase newUseCase() {
        return new NewUseCase("Place order", "goal of Place order", null, null, "Customer",
                List.of(), null, null, List.of(new NewStep(1, "do something", List.of())), List.of(), null);
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
     * {@link #findAllCodes} call returns - {@code nextCode()} reads via {@code findAllCodes}
     * (kogn-io/arknet#360), so this simulates a concurrent {@code uc_add} committing between this
     * caller's code computation and its own {@code create()}.
     */
    private static final class RaceOnFirstFindAllCodesRepository implements UseCaseRepository {

        private final UseCaseRepository delegate;
        private final Runnable injection;
        private boolean injected;

        RaceOnFirstFindAllCodesRepository(UseCaseRepository delegate, Runnable injection) {
            this.delegate = delegate;
            this.injection = injection;
        }

        @Override
        public void create(ProjectId projectId, UseCase useCase, String language) {
            delegate.create(projectId, useCase, language);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, UseCase updated,
                String titleLanguage, String goalLanguage, String scopeLanguage, String triggerLanguage,
                String preconditionLanguage, String postconditionLanguage,
                Map<Integer, String> stepTextLanguageByPosition, Map<Integer, String> extensionTextLanguageByPosition,
                String defaultLanguage, int stableExtensionPrefixLength) {
            delegate.compareAndUpdate(projectId, expectedHead, updated, titleLanguage, goalLanguage, scopeLanguage,
                    triggerLanguage, preconditionLanguage, postconditionLanguage, stepTextLanguageByPosition,
                    extensionTextLanguageByPosition, defaultLanguage, stableExtensionPrefixLength);
        }

        @Override
        public Optional<UseCase> findByCode(ProjectId projectId, UseCaseCode code, String displayLocale) {
            return delegate.findByCode(projectId, code, displayLocale);
        }

        @Override
        public Optional<CurrentUseCase> findCurrentByCode(ProjectId projectId, UseCaseCode code,
                String defaultLanguage) {
            return delegate.findCurrentByCode(projectId, code, defaultLanguage);
        }

        @Override
        public List<UseCase> findAll(ProjectId projectId, String displayLocale) {
            return delegate.findAll(projectId, displayLocale);
        }

        @Override
        public List<UseCaseCode> findAllCodes(ProjectId projectId) {
            List<UseCaseCode> result = delegate.findAllCodes(projectId);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }
    }

    /**
     * Decorator that runs {@code injection} exactly once, synchronously, right after the first
     * {@link #findCurrentByCode} call returns - simulating a concurrent caller whose own complete
     * read-modify-write round trip commits in the window between this caller's read and its own
     * write. Every other call, including every subsequent {@code findCurrentByCode}, delegates
     * unchanged. Mirrors {@code RequirementServiceConcurrencyTest}'s
     * {@code RaceOnFirstReadRepository}.
     */
    private static final class RaceOnFirstReadRepository implements UseCaseRepository {

        private final UseCaseRepository delegate;
        private final Runnable injection;
        private boolean injected;

        RaceOnFirstReadRepository(UseCaseRepository delegate, Runnable injection) {
            this.delegate = delegate;
            this.injection = injection;
        }

        @Override
        public void create(ProjectId projectId, UseCase useCase, String language) {
            delegate.create(projectId, useCase, language);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, UseCase updated,
                String titleLanguage, String goalLanguage, String scopeLanguage, String triggerLanguage,
                String preconditionLanguage, String postconditionLanguage,
                Map<Integer, String> stepTextLanguageByPosition, Map<Integer, String> extensionTextLanguageByPosition,
                String defaultLanguage, int stableExtensionPrefixLength) {
            delegate.compareAndUpdate(projectId, expectedHead, updated, titleLanguage, goalLanguage, scopeLanguage,
                    triggerLanguage, preconditionLanguage, postconditionLanguage, stepTextLanguageByPosition,
                    extensionTextLanguageByPosition, defaultLanguage, stableExtensionPrefixLength);
        }

        @Override
        public Optional<UseCase> findByCode(ProjectId projectId, UseCaseCode code, String displayLocale) {
            return delegate.findByCode(projectId, code, displayLocale);
        }

        @Override
        public Optional<CurrentUseCase> findCurrentByCode(ProjectId projectId, UseCaseCode code,
                String defaultLanguage) {
            Optional<CurrentUseCase> result = delegate.findCurrentByCode(projectId, code, defaultLanguage);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }

        @Override
        public List<UseCase> findAll(ProjectId projectId, String displayLocale) {
            return delegate.findAll(projectId, displayLocale);
        }

        @Override
        public List<UseCaseCode> findAllCodes(ProjectId projectId) {
            return delegate.findAllCodes(projectId);
        }
    }

    /** A repository whose {@code compareAndUpdate} always reports a conflict, never applying. */
    private static final class AlwaysConflictingRepository implements UseCaseRepository {

        private final UseCaseRepository delegate;
        private int compareAndUpdateAttempts;

        AlwaysConflictingRepository(UseCaseRepository delegate) {
            this.delegate = delegate;
        }

        int compareAndUpdateAttempts() {
            return compareAndUpdateAttempts;
        }

        @Override
        public void create(ProjectId projectId, UseCase useCase, String language) {
            delegate.create(projectId, useCase, language);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, UseCase updated,
                String titleLanguage, String goalLanguage, String scopeLanguage, String triggerLanguage,
                String preconditionLanguage, String postconditionLanguage,
                Map<Integer, String> stepTextLanguageByPosition, Map<Integer, String> extensionTextLanguageByPosition,
                String defaultLanguage, int stableExtensionPrefixLength) {
            compareAndUpdateAttempts++;
            // Still enforce "must exist", same as the real contract - only ever report a conflict.
            delegate.findByCode(projectId, updated.code(), null)
                    .orElseThrow(() -> new UseCaseNotFoundException(projectId, updated.code()));
            throw new UseCaseConcurrentlyModifiedException(projectId, updated.code());
        }

        @Override
        public Optional<UseCase> findByCode(ProjectId projectId, UseCaseCode code, String displayLocale) {
            return delegate.findByCode(projectId, code, displayLocale);
        }

        @Override
        public Optional<CurrentUseCase> findCurrentByCode(ProjectId projectId, UseCaseCode code,
                String defaultLanguage) {
            return delegate.findCurrentByCode(projectId, code, defaultLanguage);
        }

        @Override
        public List<UseCase> findAll(ProjectId projectId, String displayLocale) {
            return delegate.findAll(projectId, displayLocale);
        }

        @Override
        public List<UseCaseCode> findAllCodes(ProjectId projectId) {
            return delegate.findAllCodes(projectId);
        }
    }
}
