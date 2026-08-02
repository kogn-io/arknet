// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.prj.application.port.out.ProjectRegistry;
import de.hauschel.arknet.prj.application.port.out.RevisionToken;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.DatasetAlreadyAdoptedException;
import de.hauschel.arknet.prj.domain.AnchorAlreadyRegisteredException;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.prj.domain.StaleProjectException;
import de.hauschel.arknet.prj.domain.UnattributedRegistrationConflictException;
import de.hauschel.arknet.prj.domain.UnknownAnchorException;
import de.hauschel.arknet.prj.domain.UnknownDatasetException;

/**
 * Policy tests for {@link ProjectService}: registration, anchor attachment, renaming and
 * resolution rules (ADR-016), exercised against in-memory fakes for both driven ports.
 */
class ProjectServiceTest {

    private InMemoryProjectRegistry registry;
    private InMemoryProjectSelfDescription selfDescription;
    private InMemoryDatasetInventory datasets;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        registry = new InMemoryProjectRegistry();
        selfDescription = new InMemoryProjectSelfDescription();
        datasets = new InMemoryDatasetInventory();
        service = new ProjectService(registry, selfDescription, datasets);
    }

    @Test
    void registerCreatesAProjectWithExactlyOneAnchorAndTheGivenLabel() {
        Anchor anchor = pathAnchor("/home/fred/arknet");

        Project project = service.register("arknet", anchor);

        assertNotNull(project.id());
        assertEquals("arknet", project.label());
        assertEquals(List.of(anchor), project.anchors());
    }

    @Test
    void registerWithAnAlreadyRegisteredAnchorThrowsAndNamesTheOwningProject() {
        Anchor anchor = pathAnchor("/home/fred/arknet");
        Project owner = service.register("arknet", anchor);

        AnchorAlreadyRegisteredException ex = assertThrows(AnchorAlreadyRegisteredException.class,
                () -> service.register("arknet-copy", anchor));

        assertEquals(owner.id(), ex.owner());
        assertEquals(anchor, ex.anchor());
    }

    @Test
    void resolveOnUnknownAnchorThrowsAndTheMessagePointsToProjectAdd() {
        UnknownAnchorException ex = assertThrows(UnknownAnchorException.class,
                () -> service.resolve(pathAnchor("/does/not/exist")));

        assertTrue(ex.getMessage().contains("project_add"));
    }

    /**
     * {@code store_overview} looks a resolved {@link ProjectId} back up to show the registered
     * label instead of the raw id - this is that lookup's driving-port surface.
     */
    @Test
    void findByIdReturnsTheRegisteredProject() {
        Project project = service.register("arknet", pathAnchor("/home/fred/arknet"));

        assertEquals(Optional.of(project), service.findById(project.id()));
    }

    @Test
    void findByIdOnAnUnregisteredIdReturnsEmpty() {
        assertEquals(Optional.empty(), service.findById(new ProjectId("never-registered")));
    }

    @Test
    void attachAddsASecondAnchorAndBothAnchorsThenResolveToTheSameProject() {
        Project project = service.register("arknet", pathAnchor("/home/fred/arknet"));

        Project attached = service.attach(project.id(), pathAnchor("/home/fred/arknet-worktree"));

        assertEquals(2, attached.anchors().size());
        assertEquals(project.id(), service.resolve(pathAnchor("/home/fred/arknet")).id());
        assertEquals(project.id(), service.resolve(pathAnchor("/home/fred/arknet-worktree")).id());
    }

    @Test
    void attachingTheSameAnchorTwiceIsIdempotentAndPerformsNoSecondWrite() {
        Project project = service.register("arknet", pathAnchor("/home/fred/arknet"));
        int writesAfterRegister = registry.writeCount();

        Project attached = service.attach(project.id(), pathAnchor("/home/fred/arknet"));

        assertEquals(project, attached);
        assertEquals(writesAfterRegister, registry.writeCount());
    }

    /**
     * Anchor identity is the value alone, not the (value, type) pair (see {@link Anchor}'s
     * javadoc): attaching the same value under a different type must therefore be recognised as
     * the same anchor already on file, not as a new one.
     */
    @Test
    void attachingTheSameAnchorValueUnderADifferentTypeIsIdempotentAndPerformsNoSecondWrite() {
        Project project = service.register("arknet", pathAnchor("/home/fred/arknet"));
        int writesAfterRegister = registry.writeCount();

        Project attached = service.attach(project.id(), new Anchor("/home/fred/arknet", AnchorType.URL));

        assertEquals(project, attached);
        assertEquals(writesAfterRegister, registry.writeCount());
    }

    @Test
    void attachingAnAnchorAlreadyOwnedByAnotherProjectThrows() {
        Project a = service.register("project-a", pathAnchor("/home/fred/a"));
        Project b = service.register("project-b", pathAnchor("/home/fred/b"));

        AnchorAlreadyRegisteredException ex = assertThrows(AnchorAlreadyRegisteredException.class,
                () -> service.attach(b.id(), pathAnchor("/home/fred/a")));

        assertEquals(a.id(), ex.owner());
    }

    @Test
    void renameChangesTheLabelKeepsTheAnchorsAndKeepsTheIdentityStable() {
        Project project = service.register("arknet", pathAnchor("/home/fred/arknet"));

        Project renamed = service.rename(project.id(), "arknet-renamed");

        assertEquals("arknet-renamed", renamed.label());
        assertEquals(project.id(), renamed.id());
        assertEquals(project.anchors(), renamed.anchors());
    }

    @Test
    void everySuccessfulWriteAlsoWritesTheSelfDescription() {
        Project registered = service.register("arknet", pathAnchor("/home/fred/arknet"));
        assertEquals(registered, selfDescription.lastDescribed(registered.id()));

        Project attached = service.attach(registered.id(), pathAnchor("/home/fred/arknet-worktree"));
        assertEquals(attached, selfDescription.lastDescribed(registered.id()));

        Project renamed = service.rename(registered.id(), "arknet-renamed");
        assertEquals(renamed, selfDescription.lastDescribed(registered.id()));

        assertEquals(3, selfDescription.writeCount());
    }

    // --- adoption of pre-ADR-016 datasets -------------------------------------

    @Test
    void adoptRegistersTheExistingDatasetUnderItsOwnIdentity() {
        ProjectId existing = new ProjectId("arknet");
        datasets.with(existing);
        Anchor anchor = pathAnchor("/home/fred/DEV/arknet");

        Project adopted = service.adopt(existing, "arknet", anchor);

        assertEquals(existing, adopted.id(), "the dataset keeps its identity - nothing is migrated");
        assertEquals("arknet", adopted.label());
        assertEquals(List.of(anchor), adopted.anchors());
        assertEquals(adopted, service.resolve(anchor));
        assertEquals(adopted, selfDescription.lastDescribed(existing));
    }

    @Test
    void adoptRejectsADatasetThatDoesNotExist() {
        assertThrows(UnknownDatasetException.class,
                () -> service.adopt(new ProjectId("never-written"), "ghost", pathAnchor("/home/fred/ghost")));
        assertEquals(0, registry.writeCount(), "a rejected adoption writes nothing");
    }

    @Test
    void adoptRejectsADatasetSomeProjectAlreadyHolds() {
        ProjectId existing = new ProjectId("arknet");
        datasets.with(existing);
        service.adopt(existing, "arknet", pathAnchor("/home/fred/DEV/arknet"));

        assertThrows(DatasetAlreadyAdoptedException.class,
                () -> service.adopt(existing, "arknet-again", pathAnchor("/home/other/arknet")));
    }

    @Test
    void adoptRejectsAnAnchorThatAlreadyBelongsToAnotherProject() {
        Anchor taken = pathAnchor("/home/fred/DEV/arknet");
        service.register("arknet", taken);
        ProjectId existing = new ProjectId("other-dataset");
        datasets.with(existing);

        assertThrows(AnchorAlreadyRegisteredException.class,
                () -> service.adopt(existing, "other", taken));
    }

    /**
     * The list is what keeps a caller from having to guess a dataset id, so it must shrink as
     * adoption proceeds - otherwise it would keep offering datasets that are already claimed.
     */
    @Test
    void adoptableListsOnlyDatasetsNoProjectClaims() {
        datasets.with(new ProjectId("arknet")).with(new ProjectId("second-project"));

        assertEquals(List.of(new ProjectId("arknet"), new ProjectId("second-project")), service.adoptable());

        service.adopt(new ProjectId("arknet"), "arknet", pathAnchor("/home/fred/DEV/arknet"));

        assertEquals(List.of(new ProjectId("second-project")), service.adoptable());
    }

    /**
     * A project registered the ordinary way mints a fresh identity, which by construction names no
     * existing dataset - so it never appears as adoptable, and the two paths cannot collide.
     */
    @Test
    void aFreshlyRegisteredProjectIsNotAdoptable() {
        datasets.with(new ProjectId("arknet"));
        service.register("something-new", pathAnchor("/home/fred/new"));

        assertEquals(List.of(new ProjectId("arknet")), service.adoptable());
    }

    @Test
    void projectRejectsAnEmptyAnchorList() {
        ProjectId id = new ProjectId(UUID.randomUUID().toString());

        assertThrows(IllegalArgumentException.class, () -> new Project(id, "arknet", List.of()));
    }

    /**
     * The retry loop in {@link ProjectService#updateWithOptimisticRetry} must stay invisible to a
     * well-formed caller: a repository whose first {@code compareAndUpdate} reports a stale
     * concurrency token (as if a concurrent writer had committed in between) still lets the call
     * succeed once the service re-reads and retries.
     */
    @Test
    void aStaleCompareAndUpdateOnTheFirstAttemptIsRetriedTransparently() {
        Project project = service.register("arknet", pathAnchor("/home/fred/arknet"));
        ProjectService retryingService =
                new ProjectService(new ConflictsOnFirstWriteRegistry(registry), selfDescription, datasets);

        Project attached = retryingService.attach(project.id(), pathAnchor("/home/fred/arknet-worktree"));

        assertEquals(2, attached.anchors().size());
        assertEquals(2, registry.findById(project.id()).orElseThrow().anchors().size());
    }

    /**
     * Issue #67: {@link ProjectService#register}'s retry loop must stay invisible to a
     * well-formed caller too - mirroring the test above for the create path. A registry whose
     * first {@code register} call loses a real store commit conflict that neither uniqueness
     * guard could explain (see {@link UnattributedRegistrationConflictException}) still lets the
     * call succeed once the service retries the very same, already-minted candidate.
     */
    @Test
    void anUnattributedRegistrationConflictOnTheFirstAttemptIsRetriedTransparently() {
        ProjectService retryingService =
                new ProjectService(new ConflictsOnRegisterRegistry(registry, 1), selfDescription, datasets);

        Project project = retryingService.register("arknet", pathAnchor("/home/fred/arknet"));

        assertEquals("arknet", project.label());
        assertEquals(1, registry.writeCount(), "the retry must apply the same candidate exactly once");
    }

    /**
     * The retry bound must eventually give up and surface the store's own signal rather than
     * looping forever - the same contract {@link StaleProjectException} gives
     * {@link ProjectService#updateWithOptimisticRetry}.
     */
    @Test
    void anUnattributedRegistrationConflictThatNeverClearsIsRethrownAfterTheRetryBudgetIsSpent() {
        ProjectService retryingService = new ProjectService(
                new ConflictsOnRegisterRegistry(registry, Integer.MAX_VALUE), selfDescription, datasets);

        assertThrows(UnattributedRegistrationConflictException.class,
                () -> retryingService.register("arknet", pathAnchor("/home/fred/arknet")));
        assertEquals(0, registry.writeCount(), "an exhausted retry must not have written anything");
    }

    /**
     * Issue #174: {@link ProjectService#adopt} must retry an unattributed registration conflict
     * the same way {@link ProjectService#register} does (see
     * {@link #anUnattributedRegistrationConflictOnTheFirstAttemptIsRetriedTransparently} for the
     * register-path counterpart) - two concurrent adopters of the same dataset can genuinely
     * overlap under a real store, and the loser's first attempt may surface this residual signal
     * before either uniqueness guard runs.
     */
    @Test
    void anUnattributedRegistrationConflictOnTheFirstAdoptAttemptIsRetriedTransparently() {
        ProjectId existing = new ProjectId("arknet");
        datasets.with(existing);
        ProjectService retryingService =
                new ProjectService(new ConflictsOnRegisterRegistry(registry, 1), selfDescription, datasets);

        Project adopted = retryingService.adopt(existing, "arknet", pathAnchor("/home/fred/DEV/arknet"));

        assertEquals(existing, adopted.id());
        assertEquals(1, registry.writeCount(), "the retry must apply the same candidate exactly once");
    }

    /** The adopt-path counterpart of {@link #anUnattributedRegistrationConflictThatNeverClearsIsRethrownAfterTheRetryBudgetIsSpent}. */
    @Test
    void anUnattributedRegistrationConflictThatNeverClearsOnAdoptIsRethrownAfterTheRetryBudgetIsSpent() {
        ProjectId existing = new ProjectId("arknet");
        datasets.with(existing);
        ProjectService retryingService = new ProjectService(
                new ConflictsOnRegisterRegistry(registry, Integer.MAX_VALUE), selfDescription, datasets);

        assertThrows(UnattributedRegistrationConflictException.class,
                () -> retryingService.adopt(existing, "arknet", pathAnchor("/home/fred/DEV/arknet")));
        assertEquals(0, registry.writeCount(), "an exhausted retry must not have written anything");
    }

    /**
     * Unlike {@code register}'s freshly minted identity, an adopt candidate's {@code datasetId}
     * is caller-chosen and can genuinely already be claimed by a concurrent adopter of the same
     * dataset by the time a retried write observes it - the retry above must not swallow that
     * real, well-attributed collision as if it were the residual, unattributed case.
     */
    @Test
    void aGenuineIdentityCollisionOnAdoptIsNotRetriedAndPropagatesTheAttributedException() {
        ProjectId existing = new ProjectId("arknet");
        datasets.with(existing);
        ProjectService retryingService = new ProjectService(
                new ClaimedByAConcurrentAdopterRegistry(registry), selfDescription, datasets);

        assertThrows(ResourceAlreadyExistsException.class,
                () -> retryingService.adopt(existing, "arknet", pathAnchor("/home/fred/DEV/arknet")));
    }

    private static Anchor pathAnchor(String value) {
        return new Anchor(value, AnchorType.PATH);
    }

    /**
     * Decorator that reports a stale concurrency token on the very first
     * {@link #compareAndUpdate} call, then delegates every subsequent call unchanged -
     * deterministically reproducing a concurrent writer's commit landing between a caller's read
     * and its own write, without relying on real threads or timing.
     */
    private static final class ConflictsOnFirstWriteRegistry implements ProjectRegistry {

        private final ProjectRegistry delegate;
        private boolean firstWriteSeen;

        ConflictsOnFirstWriteRegistry(ProjectRegistry delegate) {
            this.delegate = delegate;
        }

        @Override
        public void register(Project project) {
            delegate.register(project);
        }

        @Override
        public Optional<Project> findByAnchor(Anchor anchor) {
            return delegate.findByAnchor(anchor);
        }

        @Override
        public Optional<Project> findById(ProjectId id) {
            return delegate.findById(id);
        }

        @Override
        public List<Project> findAll() {
            return delegate.findAll();
        }

        @Override
        public Optional<CurrentProject> findCurrentById(ProjectId id) {
            return delegate.findCurrentById(id);
        }

        @Override
        public void compareAndUpdate(RevisionToken expectedHead, Project project) {
            if (!firstWriteSeen) {
                firstWriteSeen = true;
                throw new StaleProjectException(project.id());
            }
            delegate.compareAndUpdate(expectedHead, project);
        }
    }

    /**
     * Decorator that reports an {@link UnattributedRegistrationConflictException} on the first
     * {@code failuresBeforeSuccess} {@link #register} calls, then delegates every subsequent call
     * unchanged - deterministically reproducing a lost store commit conflict that neither
     * uniqueness guard could explain (issue #67), without relying on a real store's
     * {@code SERIALIZABLE} detection or real threads.
     */
    private static final class ConflictsOnRegisterRegistry implements ProjectRegistry {

        private final ProjectRegistry delegate;
        private final int failuresBeforeSuccess;
        private int attempts;

        ConflictsOnRegisterRegistry(ProjectRegistry delegate, int failuresBeforeSuccess) {
            this.delegate = delegate;
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public void register(Project project) {
            attempts++;
            if (attempts <= failuresBeforeSuccess) {
                throw new UnattributedRegistrationConflictException(new RuntimeException("lost the commit"));
            }
            delegate.register(project);
        }

        @Override
        public Optional<Project> findByAnchor(Anchor anchor) {
            return delegate.findByAnchor(anchor);
        }

        @Override
        public Optional<Project> findById(ProjectId id) {
            return delegate.findById(id);
        }

        @Override
        public List<Project> findAll() {
            return delegate.findAll();
        }

        @Override
        public Optional<CurrentProject> findCurrentById(ProjectId id) {
            return delegate.findCurrentById(id);
        }

        @Override
        public void compareAndUpdate(RevisionToken expectedHead, Project project) {
            delegate.compareAndUpdate(expectedHead, project);
        }
    }

    /**
     * Decorator that reports {@link ResourceAlreadyExistsException} on every {@link #register}
     * call, as the funnel's own synchronous identity guard would once a concurrent adopter of the
     * same dataset has already committed - unlike {@link ConflictsOnRegisterRegistry}, this is a
     * genuine, well-attributed collision that {@link ProjectService#registerRetryingOnUnattributedConflict}
     * must not catch and retry past.
     */
    private static final class ClaimedByAConcurrentAdopterRegistry implements ProjectRegistry {

        private final ProjectRegistry delegate;

        ClaimedByAConcurrentAdopterRegistry(ProjectRegistry delegate) {
            this.delegate = delegate;
        }

        @Override
        public void register(Project project) {
            throw new ResourceAlreadyExistsException(project.id());
        }

        @Override
        public Optional<Project> findByAnchor(Anchor anchor) {
            return delegate.findByAnchor(anchor);
        }

        @Override
        public Optional<Project> findById(ProjectId id) {
            return delegate.findById(id);
        }

        @Override
        public List<Project> findAll() {
            return delegate.findAll();
        }

        @Override
        public Optional<CurrentProject> findCurrentById(ProjectId id) {
            return delegate.findCurrentById(id);
        }

        @Override
        public void compareAndUpdate(RevisionToken expectedHead, Project project) {
            delegate.compareAndUpdate(expectedHead, project);
        }
    }
}
