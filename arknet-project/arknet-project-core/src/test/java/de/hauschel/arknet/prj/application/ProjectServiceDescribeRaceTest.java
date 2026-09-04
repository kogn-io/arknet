// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.prj.application.port.out.ProjectSelfDescription;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.Project;

/**
 * Regression test for issue #173: {@link ProjectService#withProjectLock} must serialise a
 * registry read/compare-and-update plus the matching {@link ProjectSelfDescription#describe} call
 * per {@code ProjectId}, so that two overlapping writers of the <em>same</em> project can never
 * commit their {@code describe} calls in a different order than their registry writes.
 *
 * <p><strong>How the race is forced deterministically.</strong> The two datasets a write touches -
 * the registry and the project's own - never share a transaction, so the bug lived in the gap
 * between a writer's {@code compareAndUpdate} committing and its {@code describe} call landing: a
 * writer whose registry write commits first but whose {@code describe} lands last overwrites a
 * later writer's fresher description with its own stale one. {@link DelayedSelfDescription}
 * reproduces exactly that ordering: the caller whose {@code describe} is entered <em>first</em>
 * (started via a shared {@link CyclicBarrier}, so both attempt to enter their critical section at
 * once) is held there with a long sleep, while whichever enters second sleeps not at all - so the
 * second writer's {@code describe} always lands before the first's delayed one finally does,
 * exactly issue #173's "the loser's late {@code describe} overwrites the winner's fresh one". With
 * {@link ProjectService#withProjectLock} in place this cannot deadlock: the two callers' critical
 * sections (read/compare-and-update <em>and</em> describe) no longer interleave at all, so the
 * second caller's {@code describe} is never entered until the first caller's - delay included -
 * has completely finished and released the monitor.</p>
 *
 * <p><strong>What the assertion proves.</strong> {@link ProjectService#attach} and
 * {@link ProjectService#rename} both read-modify-write, so whichever caller's critical section
 * runs second reads the <em>other's already-committed change</em> - the final registry state
 * carries both. Without the lock, the loser's late {@code describe} call could still land after
 * the winner's and overwrite it with a state missing the winner's change - the exact defect issue
 * #173 reported. The test asserts the self-description equals the final registry state, not merely
 * that it is non-null, which is the property that was broken.</p>
 *
 * <p><strong>Timeout.</strong> Both callers park on a {@link CyclicBarrier}: a regression that
 * stops either from ever reaching it would hang {@code join()} forever and stall the build instead
 * of failing it. The backstop is project-wide rather than class-level -
 * {@code junit.jupiter.execution.timeout.default} in the root POM's Surefire
 * {@code configurationParameters} (kogn-io/arknet#458).</p>
 */
class ProjectServiceDescribeRaceTest {

    private InMemoryProjectRegistry registry;
    private InMemoryProjectSelfDescription recordedDescriptions;
    private ProjectService service;
    private Project seedProject;

    @BeforeEach
    void setUp() {
        registry = new InMemoryProjectRegistry();
        recordedDescriptions = new InMemoryProjectSelfDescription();
        // DelayedSelfDescription delays only the first describe() call it ever sees, so the
        // project used to race must be created through an undelayed service first - otherwise
        // that setup registration, not either racing writer, would be "the first call".
        ProjectService setup = new ProjectService(registry, recordedDescriptions, new InMemoryDatasetInventory());
        service = new ProjectService(registry, new DelayedSelfDescription(recordedDescriptions),
                new InMemoryDatasetInventory());
        seedProject = setup.register("arknet", pathAnchor("/home/fred/arknet"), null, null, null);
    }

    @Test
    void concurrentAttachAndRenameOfTheSameProjectLeaveTheSelfDescriptionMatchingTheFinalRegistryState()
            throws InterruptedException {
        Anchor original = pathAnchor("/home/fred/arknet");
        Anchor attached = pathAnchor("/home/fred/arknet-worktree");

        CyclicBarrier bothReady = new CyclicBarrier(2);
        AtomicReference<Throwable> attachFailure = new AtomicReference<>();
        AtomicReference<Throwable> renameFailure = new AtomicReference<>();

        Thread attachThread = new Thread(() -> {
            try {
                awaitBarrier(bothReady);
                service.attach(seedProject.id(), attached);
            } catch (Throwable t) {
                // Throwable, not RuntimeException: a broken harness must surface as a failed
                // assertion below rather than as a silently absent failure.
                attachFailure.set(t);
            }
        });
        Thread renameThread = new Thread(() -> {
            try {
                awaitBarrier(bothReady);
                service.rename(seedProject.id(), "arknet-renamed");
            } catch (Throwable t) {
                renameFailure.set(t);
            }
        });

        attachThread.start();
        renameThread.start();
        attachThread.join();
        renameThread.join();

        assertNull(attachFailure.get(), "attach must not fail on a well-formed overlapping race");
        assertNull(renameFailure.get(), "rename must not fail on a well-formed overlapping race");

        Project finalState = registry.findById(seedProject.id()).orElseThrow();
        assertEquals(List.of(original, attached), finalState.anchors(),
                "the registry itself already merges overlapping writes via the optimistic retry - "
                        + "this is the pre-existing guarantee, not what issue #173 was about");
        assertEquals("arknet-renamed", finalState.label());
        assertEquals(finalState, recordedDescriptions.lastDescribed(seedProject.id()),
                "issue #173: the self-description must reflect the same final state as the registry, "
                        + "not a stale write from whichever caller's describe() landed out of order");
    }

    private static Anchor pathAnchor(String value) {
        return new Anchor(value, AnchorType.PATH);
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Wraps a {@link ProjectSelfDescription}, holding the <em>first</em> {@link #describe} call it
     * sees with a long sleep and letting every later call through immediately - deterministically
     * forcing whichever caller's registry write committed later to land its description first, and
     * the earlier committer's now-stale description to land last and overwrite it. That is exactly
     * the ordering issue #173 reported; see the class javadoc for why this cannot deadlock once
     * {@link ProjectService#withProjectLock} is in place.
     */
    private static final class DelayedSelfDescription implements ProjectSelfDescription {

        private static final long FIRST_CALLER_DELAY_MILLIS = 300;

        private final ProjectSelfDescription delegate;
        private final AtomicInteger callsSeen = new AtomicInteger();

        DelayedSelfDescription(ProjectSelfDescription delegate) {
            this.delegate = delegate;
        }

        @Override
        public void describe(Project project) {
            if (callsSeen.incrementAndGet() == 1) {
                try {
                    Thread.sleep(FIRST_CALLER_DELAY_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            delegate.describe(project);
        }
    }
}
