// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application.port.out;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorAlreadyRegisteredException;
import de.hauschel.arknet.prj.domain.DuplicateProjectLabelException;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.domain.ProjectNotFoundException;
import de.hauschel.arknet.prj.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.prj.domain.StaleProjectException;
import de.hauschel.arknet.prj.domain.UnattributedRegistrationConflictException;

/**
 * Driven port: persistence capability for the anchor-to-project registry itself (ADR-016
 * decision 6).
 *
 * <p>Named after the capability ("register and look up projects"), not after any technology.
 * Implementations live in adapter modules (e.g. an RDF-backed adapter writing into the reserved
 * system dataset {@link ProjectId#RESERVED_SYSTEM_DATASET}) and must not leak their mechanism
 * into this contract.</p>
 *
 * <p><strong>There is exactly one registry, so there is no routing parameter.</strong> Every
 * other bounded context's out-port (e.g. {@code BoundedContextRepository},
 * {@code RequirementRepository}) takes a {@code ProjectId}/{@code ProjectId} first parameter,
 * because those repositories address one of many possible datasets. This port cannot: the
 * registry is what a caller consults <em>before</em> it knows which project's dataset it is
 * even talking to, and it lives permanently in the one reserved system dataset (ADR-016
 * decision 6), not in a caller-selectable one. Passing a routing parameter here would be
 * meaningless - there is nothing left to route by.</p>
 *
 * <p><strong>Create vs. compare-and-update.</strong> Identity is opaque and minted once (see
 * {@link ProjectId}), so "insert or replace by identity" is no longer a coherent single
 * operation, the same reasoning {@code RequirementRepository} follows: an identity either
 * already exists (an update) or it does not (a create). {@link #register} and
 * {@link #compareAndUpdate} therefore make that distinction explicit, and - like
 * {@code RequirementRepository} - there is no unconditional update: every correction to an
 * already-registered project goes through the compare-and-set guard, so a guarded write path can
 * never be bypassed by accident.</p>
 */
public interface ProjectRegistry {

    /**
     * Registers a brand-new project whose identity does not yet exist in the registry.
     *
     * @param project the project to register
     * @throws ResourceAlreadyExistsException  if a project with this identity already exists
     * @throws DuplicateProjectLabelException  if another project already carries this project's
     *                                         label
     * @throws AnchorAlreadyRegisteredException if one of this project's anchors already belongs
     *                                         to a different project
     * @throws UnattributedRegistrationConflictException if the write lost a real store commit
     *                                         conflict that neither guard above explains - safe
     *                                         to retry against the same, unmodified {@code project}
     *                                         (see that exception's javadoc)
     * @throws RuntimeException if {@code project} violates a SHACL write constraint. The
     *                          concrete signal type is deliberately not fixed by this port: a
     *                          real implementation's {@code WriteConstraintViolationException}
     *                          lives in {@code arknet-persistence-support}, a module
     *                          {@code arknet-project-core} must not depend on.
     */
    void register(Project project);

    /**
     * Finds the project a given anchor currently resolves to.
     *
     * <p>This is the lookup the whole component exists for: every request that needs to know
     * "which project am I in" ultimately goes through this method, directly or via
     * {@link de.hauschel.arknet.prj.application.port.in.ResolveProject}.</p>
     *
     * @param anchor the anchor to resolve
     * @return the owning project if the anchor is registered, otherwise {@link Optional#empty()}
     */
    Optional<Project> findByAnchor(Anchor anchor);

    /**
     * Finds a project by its opaque identity.
     *
     * @param id the project identity to look up
     * @return the project if present, otherwise {@link Optional#empty()}
     */
    Optional<Project> findById(ProjectId id);

    /**
     * Returns every project currently registered.
     *
     * @return all projects, never {@code null}
     */
    List<Project> findAll();

    /**
     * Reads a project's current state together with its concurrency token, mirroring
     * {@code RequirementRepository#findCurrentByCode}: state and token must come from one read,
     * or the token would already be stale by the time the caller observes it. Backs the read side
     * of the read-modify-write round trip {@link #compareAndUpdate} guards the write side of -
     * used by {@code project_attach_anchor} and {@code project_rename}.
     *
     * @param id the project identity to look up
     * @return the project and its current head, or {@link Optional#empty()} if no project with
     *         this identity is registered
     */
    Optional<CurrentProject> findCurrentById(ProjectId id);

    /**
     * A project's state paired with its current concurrency token, as read together by
     * {@link #findCurrentById}.
     *
     * @param project the project's current state
     * @param head    the concurrency token last observed for this project, or {@code null} if
     *                the project was never written through the shared write funnel (ADR-014)
     */
    record CurrentProject(Project project, RevisionToken head) {
    }

    /**
     * Replaces an existing project by identity, but only if its current concurrency token still
     * equals {@code expectedHead} - the compare-and-set guard against the lost-update race,
     * mirroring {@code RequirementRepository#compareAndUpdate}.
     *
     * @param expectedHead the {@link RevisionToken} the caller last observed for this project
     *                     (from {@link #findCurrentById}), or {@code null} if the caller expects
     *                     no token to exist yet
     * @param project      the project to store in place of the current one, if its token still
     *                     matches {@code expectedHead}
     * @throws ProjectNotFoundException       if no project with this identity is registered at
     *                                        all
     * @throws StaleProjectException          if {@code expectedHead} no longer matches the
     *                                        registered project's current token - a concurrent
     *                                        write raced ahead
     * @throws DuplicateProjectLabelException if the write would rename the project to a label
     *                                        already used by a different project
     * @throws AnchorAlreadyRegisteredException if the write would attach an anchor already
     *                                        belonging to a different project
     * @throws RuntimeException if {@code project} violates a SHACL write constraint. The
     *                          concrete signal type is deliberately not fixed by this port: a
     *                          real implementation's {@code WriteConstraintViolationException}
     *                          lives in {@code arknet-persistence-support}, a module
     *                          {@code arknet-project-core} must not depend on.
     */
    void compareAndUpdate(RevisionToken expectedHead, Project project);
}
