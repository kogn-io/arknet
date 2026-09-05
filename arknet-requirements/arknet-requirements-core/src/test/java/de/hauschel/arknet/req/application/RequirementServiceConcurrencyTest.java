// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RequirementSchemaSource;
import de.hauschel.arknet.req.application.port.out.RevisionToken;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementConcurrentlyModifiedException;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.TermRef;

/**
 * Regression tests for the concurrency guard: {@link RequirementService} used to read-then-write without any
 * concurrency guard, so two racing callers could silently lose one another's change (lost update)
 * or spuriously fail a legitimate {@code req_add} (a client-side {@code nextCode()} race, not a
 * real conflict).
 *
 * <p>Races are reproduced deterministically, without real threads: a {@link RequirementRepository}
 * decorator runs an "other caller"'s complete read-modify-write round trip exactly once, at the
 * precise point in the retry loop under test where a concurrent writer's commit would land between
 * this caller's read and its write. This pins the exact interleaving instead of relying on thread
 * scheduling, which would make these tests flaky.</p>
 */
class RequirementServiceConcurrencyTest {

    private static final ProjectId WS = new ProjectId("test-project");
    /** These races are orthogonal to issue #258's default-language resolution - always given explicitly. */
    private static final String DEFAULT_LANGUAGE = "en";
    private static final ResourceId TERM_1 = ResourceId.of("https://w3id.org/arknet/id/term-1");
    private static final ResourceId TERM_2 = ResourceId.of("https://w3id.org/arknet/id/term-2");
    /** These concurrency races are orthogonal to {@code req_schema} - never exercised here. */
    private static final RequirementSchemaSource UNUSED_SCHEMA_SOURCE = List::of;

    private InMemoryRequirementRepository store;
    private InMemoryTermLookup termLookup;
    /**
     * Shared across {@link #otherCaller} and every "under test" service built in these tests -
     * mirroring the composition root, which wires exactly one {@link ResourceIdFactory} bean
     * shared by all concurrent callers. Two independent factories would mint colliding ids for
     * the two concurrently-added requirements, a test artefact this bug does not have.
     */
    private SequentialResourceIdFactory resourceIdFactory;
    /** Unused by these races: none of them link or resolve a constraint. */
    private InMemoryConstraintRepository constraintRepository;
    /** Represents the concurrent "other" caller; always writes straight through to {@code store}. */
    private RequirementService otherCaller;

    @BeforeEach
    void setUp() {
        store = new InMemoryRequirementRepository();
        termLookup = new InMemoryTermLookup();
        termLookup.register("TERM-1", TERM_1);
        termLookup.register("TERM-2", TERM_2);
        resourceIdFactory = new SequentialResourceIdFactory();
        constraintRepository = new InMemoryConstraintRepository();
        otherCaller = new RequirementService(
                store, resourceIdFactory, termLookup, constraintRepository, UNUSED_SCHEMA_SOURCE);
    }

    /**
     * Befund 1 (lost update): two concurrent {@code req_link_term} calls for the same requirement,
     * linking different terms, must both survive. Before the fix, the second writer's {@code
     * repository.update} blindly overwrote the first writer's already-committed change because
     * neither read nor write carried any concurrency guard.
     */
    @Test
    void concurrentLinkTermCallsForDifferentTermsBothSurvive() {
        RequirementCode code = otherCaller.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();
        RaceOnFirstReadRepository racing = new RaceOnFirstReadRepository(store,
                () -> otherCaller.linkTerm(WS, code, "TERM-2"));
        RequirementService underTest =
                new RequirementService(racing, resourceIdFactory, termLookup, constraintRepository, UNUSED_SCHEMA_SOURCE);

        Requirement result = underTest.linkTerm(WS, code, "TERM-1");

        assertEquals(2, result.usesTerms().size());
        assertTrue(result.usesTerms().containsAll(List.of(new TermRef(TERM_1), new TermRef(TERM_2))));
        Requirement stored = store.findByCode(WS, code, null).orElseThrow();
        assertEquals(2, stored.usesTerms().size());
    }

    /**
     * Same race, exercised via {@code req_set_status} racing against a concurrent {@code
     * req_link_term}: accepting a requirement must not silently drop the concurrently linked term.
     */
    @Test
    void acceptSurvivesAConcurrentLinkTermBetweenReadAndWrite() {
        RequirementCode code = otherCaller.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();
        RaceOnFirstReadRepository racing = new RaceOnFirstReadRepository(store,
                () -> otherCaller.linkTerm(WS, code, "TERM-1"));
        RequirementService underTest =
                new RequirementService(racing, resourceIdFactory, termLookup, constraintRepository, UNUSED_SCHEMA_SOURCE);

        Requirement result = underTest.accept(WS, code);

        assertEquals(RequirementStatus.ACCEPTED, result.status());
        assertEquals(List.of(new TermRef(TERM_1)), result.usesTerms());
        Requirement stored = store.findByCode(WS, code, null).orElseThrow();
        assertEquals(RequirementStatus.ACCEPTED, stored.status());
        assertEquals(List.of(new TermRef(TERM_1)), stored.usesTerms());
    }

    /**
     * Same race, exercised via {@code req_update} racing against a concurrent
     * {@code req_link_term}: correcting a requirement's description must not silently drop a
     * concurrently linked term.
     */
    @Test
    void updateSurvivesAConcurrentLinkTermBetweenReadAndWrite() {
        RequirementCode code = otherCaller.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();
        RaceOnFirstReadRepository racing = new RaceOnFirstReadRepository(store,
                () -> otherCaller.linkTerm(WS, code, "TERM-1"));
        RequirementService underTest =
                new RequirementService(racing, resourceIdFactory, termLookup, constraintRepository, UNUSED_SCHEMA_SOURCE);

        Requirement result = underTest.update(WS, code, null, "Corrected description", null, null, null, null, null, DEFAULT_LANGUAGE);

        assertEquals("Corrected description", result.description());
        assertEquals(List.of(new TermRef(TERM_1)), result.usesTerms());
        Requirement stored = store.findByCode(WS, code, null).orElseThrow();
        assertEquals("Corrected description", stored.description());
        assertEquals(List.of(new TermRef(TERM_1)), stored.usesTerms());
    }

    /**
     * A read-modify-write that keeps losing the race on every single attempt (a repository whose
     * {@code compareAndUpdate} always reports a conflict) must fail loudly with {@link
     * RequirementConcurrentlyModifiedException} instead of looping forever - and must actually have
     * retried {@link RequirementService#MAX_RETRY_ATTEMPTS} times rather than giving up on the very
     * first attempt, which a stray {@code assertThrows} alone would not catch (a retry loop
     * accidentally removed down to a single try would still throw here).
     */
    @Test
    void linkTermGivesUpAfterExhaustingRetriesAgainstPermanentContention() {
        RequirementCode code = otherCaller.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();
        AlwaysConflictingRepository racing = new AlwaysConflictingRepository(store);
        RequirementService underTest =
                new RequirementService(racing, resourceIdFactory, termLookup, constraintRepository, UNUSED_SCHEMA_SOURCE);

        assertThrows(RequirementConcurrentlyModifiedException.class,
                () -> underTest.linkTerm(WS, code, "TERM-1"));

        assertEquals(RequirementService.MAX_RETRY_ATTEMPTS, racing.compareAndUpdateAttempts());
    }

    /**
     * Befund 2 (TOCTOU in {@code nextCode()}): two concurrent {@code req_add} calls for the same
     * requirement type, when none exists yet, both compute the same candidate code client-side.
     * Before the fix, the out-adapter's in-transaction uniqueness guard correctly rejected the
     * second writer's duplicate - but surfaced that rejection as a caller-visible {@code
     * DuplicateRequirementCodeException}, even though the second call was itself perfectly
     * well-formed. The fix retries with a freshly recomputed code instead of failing the caller.
     */
    @Test
    void concurrentAddCallsForTheSameTypeBothGetDistinctCodesInsteadOfOneFailing() {
        RaceOnFirstFindAllCodesRepository racing = new RaceOnFirstFindAllCodesRepository(store,
                () -> otherCaller.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE));
        RequirementService underTest =
                new RequirementService(racing, resourceIdFactory, termLookup, constraintRepository, UNUSED_SCHEMA_SOURCE);

        Requirement result = underTest.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE);

        assertEquals(new RequirementCode("FR-2"), result.code());
        assertEquals(2, store.findAll(WS, null).size());
        assertTrue(store.findAll(WS, null).stream()
                .map(Requirement::code)
                .toList()
                .containsAll(List.of(new RequirementCode("FR-1"), new RequirementCode("FR-2"))));
    }

    private static NewRequirement newFunctionalRequirement() {
        return new NewRequirement("User can log in", "The system shall let a registered user authenticate.", null,
                RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"), null);
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
     * unchanged.
     */
    private static final class RaceOnFirstReadRepository implements RequirementRepository {

        private final RequirementRepository delegate;
        private final Runnable injection;
        private boolean injected;

        RaceOnFirstReadRepository(RequirementRepository delegate, Runnable injection) {
            this.delegate = delegate;
            this.injection = injection;
        }

        @Override
        public void create(ProjectId projectId, Requirement requirement, String language) {
            delegate.create(projectId, requirement, language);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Requirement updated,
                String titleLanguage, String descriptionLanguage, String rationaleLanguage,
                java.util.Map<Integer, String> acceptanceCriteriaLanguageByPosition, String defaultLanguage) {
            delegate.compareAndUpdate(projectId, expectedHead, updated, titleLanguage, descriptionLanguage,
                    rationaleLanguage,
                    acceptanceCriteriaLanguageByPosition, defaultLanguage);
        }

        @Override
        public Optional<Requirement> findByCode(ProjectId projectId, RequirementCode code, String displayLocale) {
            return delegate.findByCode(projectId, code, displayLocale);
        }

        @Override
        public Optional<CurrentRequirement> findCurrentByCode(ProjectId projectId, RequirementCode code,
                String defaultLanguage) {
            Optional<CurrentRequirement> result = delegate.findCurrentByCode(projectId, code, defaultLanguage);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }

        @Override
        public List<Requirement> findAll(ProjectId projectId, String displayLocale) {
            return delegate.findAll(projectId, displayLocale);
        }

        @Override
        public List<RequirementCode> findAllCodes(ProjectId projectId) {
            return delegate.findAllCodes(projectId);
        }

        @Override
        public List<ResolveRequirements.ResolvedRequirement> findByIds(ProjectId projectId,
                List<ResourceId> ids) {
            return delegate.findByIds(projectId, ids);
        }
    }

    /**
     * Decorator that runs {@code injection} exactly once, synchronously, right after the first
     * {@link #findAllCodes} call returns - {@code nextCode()} reads via {@code findAllCodes} since
     * kogn-io/arknet#360, so this simulates a concurrent {@code req_add} committing between this
     * caller's code computation and its own {@code create()}.
     */
    private static final class RaceOnFirstFindAllCodesRepository implements RequirementRepository {

        private final RequirementRepository delegate;
        private final Runnable injection;
        private boolean injected;

        RaceOnFirstFindAllCodesRepository(RequirementRepository delegate, Runnable injection) {
            this.delegate = delegate;
            this.injection = injection;
        }

        @Override
        public void create(ProjectId projectId, Requirement requirement, String language) {
            delegate.create(projectId, requirement, language);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Requirement updated,
                String titleLanguage, String descriptionLanguage, String rationaleLanguage,
                java.util.Map<Integer, String> acceptanceCriteriaLanguageByPosition, String defaultLanguage) {
            delegate.compareAndUpdate(projectId, expectedHead, updated, titleLanguage, descriptionLanguage,
                    rationaleLanguage,
                    acceptanceCriteriaLanguageByPosition, defaultLanguage);
        }

        @Override
        public Optional<Requirement> findByCode(ProjectId projectId, RequirementCode code, String displayLocale) {
            return delegate.findByCode(projectId, code, displayLocale);
        }

        @Override
        public Optional<CurrentRequirement> findCurrentByCode(ProjectId projectId, RequirementCode code,
                String defaultLanguage) {
            return delegate.findCurrentByCode(projectId, code, defaultLanguage);
        }

        @Override
        public List<Requirement> findAll(ProjectId projectId, String displayLocale) {
            return delegate.findAll(projectId, displayLocale);
        }

        @Override
        public List<RequirementCode> findAllCodes(ProjectId projectId) {
            List<RequirementCode> result = delegate.findAllCodes(projectId);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }

        @Override
        public List<ResolveRequirements.ResolvedRequirement> findByIds(ProjectId projectId,
                List<ResourceId> ids) {
            return delegate.findByIds(projectId, ids);
        }
    }

    /** A repository whose {@code compareAndUpdate} always reports a conflict, never applying. */
    private static final class AlwaysConflictingRepository implements RequirementRepository {

        private final RequirementRepository delegate;
        private int compareAndUpdateAttempts;

        AlwaysConflictingRepository(RequirementRepository delegate) {
            this.delegate = delegate;
        }

        int compareAndUpdateAttempts() {
            return compareAndUpdateAttempts;
        }

        @Override
        public void create(ProjectId projectId, Requirement requirement, String language) {
            delegate.create(projectId, requirement, language);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Requirement updated,
                String titleLanguage, String descriptionLanguage, String rationaleLanguage,
                java.util.Map<Integer, String> acceptanceCriteriaLanguageByPosition, String defaultLanguage) {
            compareAndUpdateAttempts++;
            // Still enforce "must exist", same as the real contract - only ever report a conflict.
            delegate.findByCode(projectId, updated.code(), null)
                    .orElseThrow(() -> new de.hauschel.arknet.req.domain.RequirementNotFoundException(
                            projectId, updated.code()));
            throw new RequirementConcurrentlyModifiedException(
                    projectId, updated.code());
        }

        @Override
        public Optional<Requirement> findByCode(ProjectId projectId, RequirementCode code, String displayLocale) {
            return delegate.findByCode(projectId, code, displayLocale);
        }

        @Override
        public Optional<CurrentRequirement> findCurrentByCode(ProjectId projectId, RequirementCode code,
                String defaultLanguage) {
            return delegate.findCurrentByCode(projectId, code, defaultLanguage);
        }

        @Override
        public List<Requirement> findAll(ProjectId projectId, String displayLocale) {
            return delegate.findAll(projectId, displayLocale);
        }

        @Override
        public List<RequirementCode> findAllCodes(ProjectId projectId) {
            return delegate.findAllCodes(projectId);
        }

        @Override
        public List<ResolveRequirements.ResolvedRequirement> findByIds(ProjectId projectId,
                List<ResourceId> ids) {
            return delegate.findByIds(projectId, ids);
        }
    }
}
