// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.adr.application.port.in.AddAdr.NewAdr;
import de.hauschel.arknet.adr.application.port.in.UpdateAdr.AdrCorrection;
import de.hauschel.arknet.adr.application.port.out.AdrRepository;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrConcurrentlyModifiedException;
import de.hauschel.arknet.adr.domain.AdrId;
import de.hauschel.arknet.adr.domain.AdrNotFoundException;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.adr.domain.BoundedContextRef;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;

/**
 * Regression tests for the two concurrency races {@link AdrService} has to absorb - the same two the
 * requirements and bounded-context hexagons had to be retrofitted for, built
 * into this one from the start.
 *
 * <p><strong>Code assignment.</strong> {@link AdrService#add} computes the next business code
 * ({@code ADR-N}) client-side and only then {@code create}s it, so two racing {@code adr_add} calls
 * in the same project legitimately compute the same candidate. Without
 * {@code CodeAssignment}'s retry, one of two well-formed callers would see the out-adapter's
 * in-transaction uniqueness guard fire as a caller-visible failure.</p>
 *
 * <p><strong>Lost update.</strong> {@link AdrService#supersede}, {@link AdrService#accept} and
 * {@link AdrService#update} are read-modify-write round trips. Without the compare-and-set guard,
 * two racing {@code adr_supersede} calls on the same superseded decision would silently let the
 * second overwrite the first's already-committed {@code arkarch:supersededBy} edge and status, and
 * an {@code adr_update} would restore whatever a concurrent {@code adr_set_status} had just
 * committed.</p>
 *
 * <p>Both races are reproduced deterministically, without real threads: an {@link AdrRepository}
 * decorator runs an "other caller"'s complete round trip exactly once, at the precise point where a
 * concurrent writer's commit would land - after the first {@code findAll} (which the code
 * computation reads) for the code race, after the first {@code findCurrentByCode} for the lost
 * update. That pins the exact interleaving instead of relying on thread scheduling, which would make
 * these tests flaky.</p>
 */
class AdrServiceConcurrencyTest {

    private static final ProjectId PROJECT = new ProjectId("test-project");
    private static final ResourceId BC_1 = ResourceId.of("https://w3id.org/arknet/id/bc-1");

    private InMemoryAdrRepository store;
    /**
     * Shared across {@link #otherCaller} and the "under test" service, mirroring the composition
     * root, which wires exactly one {@link ResourceIdFactory} bean shared by all concurrent callers.
     * Two independent factories would mint colliding identities for the two concurrently added
     * decisions, a test artefact this bug does not have.
     */
    private SequentialResourceIdFactory resourceIdFactory;
    private InMemoryReferenceLookups.Requirements requirements;
    private InMemoryReferenceLookups.BoundedContexts contexts;
    /** Represents the concurrent "other" caller; always writes straight through to {@code store}. */
    private AdrService otherCaller;

    @BeforeEach
    void setUp() {
        store = new InMemoryAdrRepository();
        resourceIdFactory = new SequentialResourceIdFactory();
        requirements = new InMemoryReferenceLookups.Requirements();
        contexts = new InMemoryReferenceLookups.BoundedContexts();
        contexts.register("BC-1", BC_1);
        otherCaller = new AdrService(store, resourceIdFactory, requirements, contexts);
    }

    @Test
    void concurrentAddCallsBothGetDistinctCodesInsteadOfOneFailing() {
        RaceOnFirstFindAllRepository racing =
                new RaceOnFirstFindAllRepository(store, () -> otherCaller.add(PROJECT, newAdr()));
        AdrService underTest = new AdrService(racing, resourceIdFactory, requirements, contexts);

        Adr result = underTest.add(PROJECT, newAdr()).adr();

        assertEquals(new AdrCode("ADR-2"), result.code());
        assertEquals(2, store.findAll(PROJECT).size());
        assertTrue(store.findAll(PROJECT).stream().map(Adr::code).toList()
                .containsAll(List.of(new AdrCode("ADR-1"), new AdrCode("ADR-2"))));
    }

    /**
     * The lost-update race {@code adr_supersede} guards against changed shape with kogn-io/arknet#357:
     * the write now lands on the <em>superseded</em> decision, so two concurrent calls superseding
     * two <em>different</em> older decisions with the same successor no longer share any mutable
     * state at all (each writes its own record) - that scenario is covered instead by
     * {@link #supersedeAccumulatesSeveralOlderDecisions()}-style sequential calls in
     * {@code AdrServiceTest}. The race that remains reachable is two callers superseding the
     * <em>same</em> older decision: the loser's compare-and-set fails against the winner's
     * already-committed head, retries with a fresh read, and finds the decision already SUPERSEDED -
     * so {@link de.hauschel.arknet.adr.domain.Adr#supersededBy(AdrId)}'s own ACCEPTED guard refuses
     * the retry cleanly (a real, informative failure) rather than silently overwriting the winner's
     * edge or looping forever.
     */
    @Test
    void aSecondConcurrentSupersedeOnTheSameDecisionFailsCleanlyAfterTheFirstWins() {
        AdrCode older = otherCaller.add(PROJECT, newAdr()).adr().code();
        otherCaller.accept(PROJECT, older);
        AdrCode winner = otherCaller.add(PROJECT, newAdr()).adr().code();
        otherCaller.accept(PROJECT, winner);
        AdrCode loserSuccessor = otherCaller.add(PROJECT, newAdr()).adr().code();
        otherCaller.accept(PROJECT, loserSuccessor);
        RaceOnFirstReadRepository racing = new RaceOnFirstReadRepository(store,
                () -> otherCaller.supersede(PROJECT, winner, older));
        AdrService underTest = new AdrService(racing, resourceIdFactory, requirements, contexts);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> underTest.supersede(PROJECT, loserSuccessor, older));

        assertTrue(thrown.getMessage().contains("ACCEPTED"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(older.value()), thrown.getMessage());
        Adr stored = store.findByCode(PROJECT, older).orElseThrow();
        assertEquals(AdrStatus.SUPERSEDED, stored.status());
        assertEquals(winner, store.findSupersedingCodes(PROJECT, stored.id()).stream().findFirst().orElseThrow());
    }

    /**
     * A read-modify-write that keeps losing the race on every single attempt (a repository whose
     * {@code compareAndUpdate} always reports a conflict) must fail loudly with
     * {@link AdrConcurrentlyModifiedException} instead of looping forever.
     */
    @Test
    void acceptGivesUpAfterExhaustingRetriesAgainstPermanentContention() {
        AdrCode code = otherCaller.add(PROJECT, newAdr()).adr().code();
        AdrService underTest =
                new AdrService(new AlwaysConflictingRepository(store), resourceIdFactory, requirements, contexts);

        assertThrows(AdrConcurrentlyModifiedException.class, () -> underTest.accept(PROJECT, code));
    }

    /**
     * Accepting an already-accepted decision stays a no-op: the mutation returns the state it was
     * given, so no write is attempted at all - not even a compare-and-set one that could fail.
     */
    @Test
    void acceptingAnAlreadyAcceptedAdrWritesNothingEvenUnderPermanentContention() {
        AdrCode code = otherCaller.add(PROJECT, newAdr()).adr().code();
        otherCaller.accept(PROJECT, code);
        AdrService underTest =
                new AdrService(new AlwaysConflictingRepository(store), resourceIdFactory, requirements, contexts);

        assertEquals(AdrStatus.ACCEPTED, underTest.accept(PROJECT, code).adr().status());
    }

    /**
     * {@code adr_update} is a read-modify-write round trip like every other one here, so it has to
     * absorb the same lost-update race: a concurrent writer committing between this caller's read and
     * its own write must be retried against, not overwritten. Here the "other caller" accepts the
     * very decision this caller is correcting - without the compare-and-set retry, this caller's
     * write would blindly restore the decision to {@code PROPOSED} and silently discard an
     * already-committed transition.
     */
    @Test
    void concurrentUpdateRetriesAgainstTheOtherWritersCommitInsteadOfOverwritingIt() {
        AdrCode code = otherCaller.add(PROJECT, newAdr()).adr().code();
        RaceOnFirstReadRepository racing =
                new RaceOnFirstReadRepository(store, () -> otherCaller.accept(PROJECT, code));
        AdrService underTest = new AdrService(racing, resourceIdFactory, requirements, contexts);

        // A reference-only correction, because the text of an accepted decision is immutable - and
        // by the time this write lands, the other caller has already accepted it.
        Adr result = underTest.update(PROJECT, code,
                AdrCorrection.builder().affectsContextCodes(List.of("BC-1")).build()).adr();

        assertEquals(AdrStatus.ACCEPTED, result.status());
        assertEquals(List.of(new BoundedContextRef(BC_1)), result.affectsContexts());
        Adr stored = store.findByCode(PROJECT, code).orElseThrow();
        assertEquals(AdrStatus.ACCEPTED, stored.status());
        assertEquals(List.of(new BoundedContextRef(BC_1)), stored.affectsContexts());
    }

    private static NewAdr newAdr() {
        return new NewAdr("Use an embedded triple store",
                "The model has to live somewhere a single-user client can reach without a server.",
                "Use kognio-rdf as the embedded RDF substrate behind an out-port.",
                null, null, null, null, null, null);
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
     * Base decorator delegating every {@link AdrRepository} method, so each race decorator below only
     * has to state the one call it interferes with.
     */
    private abstract static class DelegatingRepository implements AdrRepository {

        protected final AdrRepository delegate;

        DelegatingRepository(AdrRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public void create(ProjectId projectId, Adr adr) {
            delegate.create(projectId, adr);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, String expectedHead, Adr updated) {
            delegate.compareAndUpdate(projectId, expectedHead, updated);
        }

        @Override
        public Optional<Adr> findByCode(ProjectId projectId, AdrCode code) {
            return delegate.findByCode(projectId, code);
        }

        @Override
        public Optional<CurrentAdr> findCurrentByCode(ProjectId projectId, AdrCode code) {
            return delegate.findCurrentByCode(projectId, code);
        }

        @Override
        public List<Adr> findAll(ProjectId projectId) {
            return delegate.findAll(projectId);
        }

        @Override
        public Map<AdrId, AdrCode> findCodesByIds(ProjectId projectId, Collection<AdrId> ids) {
            return delegate.findCodesByIds(projectId, ids);
        }

        @Override
        public List<AdrCode> findSupersedingCodes(ProjectId projectId, AdrId supersededId) {
            return delegate.findSupersedingCodes(projectId, supersededId);
        }

        @Override
        public List<AdrCode> findSupersededCodes(ProjectId projectId, AdrId supersedingId) {
            return delegate.findSupersededCodes(projectId, supersedingId);
        }

        @Override
        public List<AdrCode> findSupersessionReferrers(ProjectId projectId, AdrId target) {
            return delegate.findSupersessionReferrers(projectId, target);
        }

        @Override
        public List<LegacySupersession> findLegacySupersedesEdges(ProjectId projectId) {
            return delegate.findLegacySupersedesEdges(projectId);
        }

        @Override
        public List<AdrCode> findRelatedCodes(ProjectId projectId, AdrId relatedId) {
            return delegate.findRelatedCodes(projectId, relatedId);
        }

        @Override
        public void delete(ProjectId projectId, AdrCode code) {
            delegate.delete(projectId, code);
        }

        @Override
        public List<AdrCode> findRetainedCodes(ProjectId projectId) {
            return delegate.findRetainedCodes(projectId);
        }
    }

    /**
     * Runs {@code injection} exactly once, synchronously, right after the first {@link #findAll} call
     * returns - the code computation reads via {@code findAll}, so this simulates a concurrent
     * {@code adr_add} committing between this caller's code computation and its own {@code create}.
     */
    private static final class RaceOnFirstFindAllRepository extends DelegatingRepository {

        private final Runnable injection;
        private boolean injected;

        RaceOnFirstFindAllRepository(AdrRepository delegate, Runnable injection) {
            super(delegate);
            this.injection = injection;
        }

        @Override
        public List<Adr> findAll(ProjectId projectId) {
            List<Adr> result = delegate.findAll(projectId);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }
    }

    /**
     * Runs {@code injection} exactly once, synchronously, right after the first
     * {@link #findCurrentByCode} call returns - simulating a concurrent caller whose own complete
     * read-modify-write round trip commits in the window between this caller's read and its own
     * write. Every other call, including every subsequent {@code findCurrentByCode} the retry issues,
     * delegates unchanged.
     */
    private static final class RaceOnFirstReadRepository extends DelegatingRepository {

        private final Runnable injection;
        private boolean injected;

        RaceOnFirstReadRepository(AdrRepository delegate, Runnable injection) {
            super(delegate);
            this.injection = injection;
        }

        @Override
        public Optional<CurrentAdr> findCurrentByCode(ProjectId projectId, AdrCode code) {
            Optional<CurrentAdr> result = delegate.findCurrentByCode(projectId, code);
            if (!injected) {
                injected = true;
                injection.run();
            }
            return result;
        }
    }

    /** A repository whose {@code compareAndUpdate} always reports a conflict, never applying. */
    private static final class AlwaysConflictingRepository extends DelegatingRepository {

        AlwaysConflictingRepository(AdrRepository delegate) {
            super(delegate);
        }

        @Override
        public void compareAndUpdate(ProjectId projectId, String expectedHead, Adr updated) {
            // Still enforce "must exist", same as the real contract - only ever report a conflict.
            delegate.findByCode(projectId, updated.code())
                    .orElseThrow(() -> new AdrNotFoundException(projectId, updated.code()));
            throw new AdrConcurrentlyModifiedException(projectId, updated.code());
        }
    }
}
