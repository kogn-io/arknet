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
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RequirementSchemaSource;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementConcurrentlyModifiedException;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.TermRef;

/**
 * Regression tests for issue #108: {@link RequirementService} used to read-then-write without any
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

    private static final WorkspaceId WS = WorkspaceId.DEFAULT;
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
    /** Represents the concurrent "other" caller; always writes straight through to {@code store}. */
    private RequirementService otherCaller;

    @BeforeEach
    void setUp() {
        store = new InMemoryRequirementRepository();
        termLookup = new InMemoryTermLookup();
        termLookup.register("TERM-1", TERM_1);
        termLookup.register("TERM-2", TERM_2);
        resourceIdFactory = new SequentialResourceIdFactory();
        otherCaller = new RequirementService(store, resourceIdFactory, termLookup, UNUSED_SCHEMA_SOURCE);
    }

    /**
     * Befund 1 (lost update): two concurrent {@code req_link_term} calls for the same requirement,
     * linking different terms, must both survive. Before the fix, the second writer's {@code
     * repository.update} blindly overwrote the first writer's already-committed change because
     * neither read nor write carried any concurrency guard.
     */
    @Test
    void concurrentLinkTermCallsForDifferentTermsBothSurvive() {
        RequirementCode code = otherCaller.add(WS, newFunctionalRequirement()).code();
        RaceOnFirstReadRepository racing = new RaceOnFirstReadRepository(store,
                () -> otherCaller.linkTerm(WS, code, "TERM-2"));
        RequirementService underTest =
                new RequirementService(racing, resourceIdFactory, termLookup, UNUSED_SCHEMA_SOURCE);

        Requirement result = underTest.linkTerm(WS, code, "TERM-1");

        assertEquals(2, result.usesTerms().size());
        assertTrue(result.usesTerms().containsAll(List.of(new TermRef(TERM_1), new TermRef(TERM_2))));
        Requirement stored = store.findByCode(WS, code).orElseThrow();
        assertEquals(2, stored.usesTerms().size());
    }

    /**
     * Same race, exercised via {@code req_set_status} racing against a concurrent {@code
     * req_link_term}: the status change must not silently drop the concurrently linked term.
     */
    @Test
    void setStatusSurvivesAConcurrentLinkTermBetweenReadAndWrite() {
        RequirementCode code = otherCaller.add(WS, newFunctionalRequirement()).code();
        RaceOnFirstReadRepository racing = new RaceOnFirstReadRepository(store,
                () -> otherCaller.linkTerm(WS, code, "TERM-1"));
        RequirementService underTest =
                new RequirementService(racing, resourceIdFactory, termLookup, UNUSED_SCHEMA_SOURCE);

        Requirement result = underTest.setStatus(WS, code, RequirementStatus.ACCEPTED);

        assertEquals(RequirementStatus.ACCEPTED, result.status());
        assertEquals(List.of(new TermRef(TERM_1)), result.usesTerms());
        Requirement stored = store.findByCode(WS, code).orElseThrow();
        assertEquals(RequirementStatus.ACCEPTED, stored.status());
        assertEquals(List.of(new TermRef(TERM_1)), stored.usesTerms());
    }

    /**
     * Same race, exercised via {@code req_update} (issue #162) racing against a concurrent
     * {@code req_link_term}: correcting a requirement's description must not silently drop a
     * concurrently linked term.
     */
    @Test
    void updateSurvivesAConcurrentLinkTermBetweenReadAndWrite() {
        RequirementCode code = otherCaller.add(WS, newFunctionalRequirement()).code();
        RaceOnFirstReadRepository racing = new RaceOnFirstReadRepository(store,
                () -> otherCaller.linkTerm(WS, code, "TERM-1"));
        RequirementService underTest =
                new RequirementService(racing, resourceIdFactory, termLookup, UNUSED_SCHEMA_SOURCE);

        Requirement result = underTest.update(WS, code, null, "Corrected description", null);

        assertEquals("Corrected description", result.description());
        assertEquals(List.of(new TermRef(TERM_1)), result.usesTerms());
        Requirement stored = store.findByCode(WS, code).orElseThrow();
        assertEquals("Corrected description", stored.description());
        assertEquals(List.of(new TermRef(TERM_1)), stored.usesTerms());
    }

    /**
     * A read-modify-write that keeps losing the race on every single attempt (a repository whose
     * {@code compareAndUpdate} always reports a conflict) must fail loudly with {@link
     * RequirementConcurrentlyModifiedException} instead of looping forever.
     */
    @Test
    void linkTermGivesUpAfterExhaustingRetriesAgainstPermanentContention() {
        RequirementCode code = otherCaller.add(WS, newFunctionalRequirement()).code();
        RequirementService underTest =
                new RequirementService(new AlwaysConflictingRepository(store), resourceIdFactory,
                        termLookup, UNUSED_SCHEMA_SOURCE);

        assertThrows(RequirementConcurrentlyModifiedException.class,
                () -> underTest.linkTerm(WS, code, "TERM-1"));
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
        RaceOnFirstFindAllRepository racing = new RaceOnFirstFindAllRepository(store,
                () -> otherCaller.add(WS, newFunctionalRequirement()));
        RequirementService underTest =
                new RequirementService(racing, resourceIdFactory, termLookup, UNUSED_SCHEMA_SOURCE);

        Requirement result = underTest.add(WS, newFunctionalRequirement());

        assertEquals(new RequirementCode("FR-2"), result.code());
        assertEquals(2, store.findAll(WS).size());
        assertTrue(store.findAll(WS).stream()
                .map(Requirement::code)
                .toList()
                .containsAll(List.of(new RequirementCode("FR-1"), new RequirementCode("FR-2"))));
    }

    private static NewRequirement newFunctionalRequirement() {
        return new NewRequirement("User can log in", "The system shall let a registered user authenticate.",
                RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"));
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
        public void create(WorkspaceId workspaceId, Requirement requirement) {
            delegate.create(workspaceId, requirement);
        }

        @Override
        public void compareAndUpdate(WorkspaceId workspaceId, String expectedHead, Requirement updated) {
            delegate.compareAndUpdate(workspaceId, expectedHead, updated);
        }

        @Override
        public Optional<Requirement> findByCode(WorkspaceId workspaceId, RequirementCode code) {
            return delegate.findByCode(workspaceId, code);
        }

        @Override
        public Optional<CurrentRequirement> findCurrentByCode(WorkspaceId workspaceId, RequirementCode code) {
            Optional<CurrentRequirement> result = delegate.findCurrentByCode(workspaceId, code);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }

        @Override
        public List<Requirement> findAll(WorkspaceId workspaceId) {
            return delegate.findAll(workspaceId);
        }

        @Override
        public List<ResolveRequirements.ResolvedRequirement> findByIds(WorkspaceId workspaceId,
                List<ResourceId> ids) {
            return delegate.findByIds(workspaceId, ids);
        }
    }

    /**
     * Decorator that runs {@code injection} exactly once, synchronously, right after the first
     * {@link #findAll} call returns - {@code nextCode()} reads via {@code findAll}, so this
     * simulates a concurrent {@code req_add} committing between this caller's code computation and
     * its own {@code create()}.
     */
    private static final class RaceOnFirstFindAllRepository implements RequirementRepository {

        private final RequirementRepository delegate;
        private final Runnable injection;
        private boolean injected;

        RaceOnFirstFindAllRepository(RequirementRepository delegate, Runnable injection) {
            this.delegate = delegate;
            this.injection = injection;
        }

        @Override
        public void create(WorkspaceId workspaceId, Requirement requirement) {
            delegate.create(workspaceId, requirement);
        }

        @Override
        public void compareAndUpdate(WorkspaceId workspaceId, String expectedHead, Requirement updated) {
            delegate.compareAndUpdate(workspaceId, expectedHead, updated);
        }

        @Override
        public Optional<Requirement> findByCode(WorkspaceId workspaceId, RequirementCode code) {
            return delegate.findByCode(workspaceId, code);
        }

        @Override
        public Optional<CurrentRequirement> findCurrentByCode(WorkspaceId workspaceId, RequirementCode code) {
            return delegate.findCurrentByCode(workspaceId, code);
        }

        @Override
        public List<Requirement> findAll(WorkspaceId workspaceId) {
            List<Requirement> result = delegate.findAll(workspaceId);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }

        @Override
        public List<ResolveRequirements.ResolvedRequirement> findByIds(WorkspaceId workspaceId,
                List<ResourceId> ids) {
            return delegate.findByIds(workspaceId, ids);
        }
    }

    /** A repository whose {@code compareAndUpdate} always reports a conflict, never applying. */
    private static final class AlwaysConflictingRepository implements RequirementRepository {

        private final RequirementRepository delegate;

        AlwaysConflictingRepository(RequirementRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public void create(WorkspaceId workspaceId, Requirement requirement) {
            delegate.create(workspaceId, requirement);
        }

        @Override
        public void compareAndUpdate(WorkspaceId workspaceId, String expectedHead, Requirement updated) {
            // Still enforce "must exist", same as the real contract - only ever report a conflict.
            delegate.findByCode(workspaceId, updated.code())
                    .orElseThrow(() -> new de.hauschel.arknet.req.domain.RequirementNotFoundException(
                            workspaceId, updated.code()));
            throw new RequirementConcurrentlyModifiedException(
                    workspaceId, updated.code());
        }

        @Override
        public Optional<Requirement> findByCode(WorkspaceId workspaceId, RequirementCode code) {
            return delegate.findByCode(workspaceId, code);
        }

        @Override
        public Optional<CurrentRequirement> findCurrentByCode(WorkspaceId workspaceId, RequirementCode code) {
            return delegate.findCurrentByCode(workspaceId, code);
        }

        @Override
        public List<Requirement> findAll(WorkspaceId workspaceId) {
            return delegate.findAll(workspaceId);
        }

        @Override
        public List<ResolveRequirements.ResolvedRequirement> findByIds(WorkspaceId workspaceId,
                List<ResourceId> ids) {
            return delegate.findByIds(workspaceId, ids);
        }
    }
}
