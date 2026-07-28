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
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorAlreadyRegisteredException;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.domain.StaleProjectException;
import de.hauschel.arknet.prj.domain.UnknownAnchorException;

/**
 * Policy tests for {@link ProjectService}: registration, anchor attachment, renaming and
 * resolution rules (ADR-016), exercised against in-memory fakes for both driven ports.
 */
class ProjectServiceTest {

    private InMemoryProjectRegistry registry;
    private InMemoryProjectSelfDescription selfDescription;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        registry = new InMemoryProjectRegistry();
        selfDescription = new InMemoryProjectSelfDescription();
        service = new ProjectService(registry, selfDescription);
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
                new ProjectService(new ConflictsOnFirstWriteRegistry(registry), selfDescription);

        Project attached = retryingService.attach(project.id(), pathAnchor("/home/fred/arknet-worktree"));

        assertEquals(2, attached.anchors().size());
        assertEquals(2, registry.findById(project.id()).orElseThrow().anchors().size());
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
        public void compareAndUpdate(String expectedHead, Project project) {
            if (!firstWriteSeen) {
                firstWriteSeen = true;
                throw new StaleProjectException(project.id());
            }
            delegate.compareAndUpdate(expectedHead, project);
        }
    }
}
