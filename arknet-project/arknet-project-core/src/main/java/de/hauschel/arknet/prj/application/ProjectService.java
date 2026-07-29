// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import de.hauschel.arknet.prj.application.port.in.AdoptProject;
import de.hauschel.arknet.prj.application.port.in.AttachAnchor;
import de.hauschel.arknet.prj.application.port.in.FindProject;
import de.hauschel.arknet.prj.application.port.in.ListAdoptableDatasets;
import de.hauschel.arknet.prj.application.port.in.ListProjects;
import de.hauschel.arknet.prj.application.port.in.RegisterProject;
import de.hauschel.arknet.prj.application.port.in.RenameProject;
import de.hauschel.arknet.prj.application.port.in.ResolveProject;
import de.hauschel.arknet.prj.application.port.out.DatasetInventory;
import de.hauschel.arknet.prj.application.port.out.ProjectRegistry;
import de.hauschel.arknet.prj.application.port.out.ProjectSelfDescription;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorAlreadyRegisteredException;
import de.hauschel.arknet.prj.domain.DatasetAlreadyAdoptedException;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.domain.ProjectNotFoundException;
import de.hauschel.arknet.prj.domain.StaleProjectException;
import de.hauschel.arknet.prj.domain.UnknownAnchorException;
import de.hauschel.arknet.prj.domain.UnknownDatasetException;

/**
 * Application service implementing the project use cases (ADR-016 decision 8).
 *
 * <p>This is the policy seat of the hexagon: it drives the {@link ProjectRegistry} and
 * {@link ProjectSelfDescription} driven ports. The component is wired as a plain object
 * (constructor injection) by the composition root; there are deliberately no framework
 * annotations here.</p>
 *
 * <p><strong>Identity is minted here, not via a {@code ResourceIdFactory}.</strong> Every other
 * bounded context's application service mints its aggregate's identity through the shared-kernel
 * {@code ResourceIdFactory}, which produces a subject IRI under {@code
 * https://w3id.org/arknet/id/} - an identity meant to live <em>inside</em> a dataset. A
 * {@link ProjectId} instead becomes a dataset id (ADR-016 decision 1); minting it via that
 * factory would tie a project's identity to the id scheme of resources that live inside
 * datasets, which is the wrong direction of dependency for something that has to exist before
 * any dataset it names does. This service therefore mints a plain {@link UUID} directly.</p>
 *
 * <p><strong>Policy.</strong> {@link #register} rejects an anchor that already belongs to a
 * project before minting anything, so a caller who mistakenly tries to register an already-known
 * anchor never wastes a fresh identity on a rejected write. {@link #attach} and {@link #rename}
 * are both idempotent no-ops when the requested change is already true (the anchor is already
 * attached; the label is already the requested one) - mirroring {@code
 * BoundedContextService#linkTerm}'s "linking an already-linked term is a no-op" rule. Every
 * write that actually changes the registry - a fresh registration, an attached anchor, a rename -
 * is followed by writing the project's self-description into its own dataset
 * ({@link ProjectSelfDescription#describe}), in that order (ADR-016 decision 7): the registry is
 * where a duplicate anchor or label is caught, so it must run first.</p>
 *
 * <p><strong>Concurrency.</strong> {@link #attach} and {@link #rename} both read-modify-write via
 * {@link ProjectRegistry#findCurrentById}/{@link ProjectRegistry#compareAndUpdate} and retry the
 * whole round trip against a fresh read whenever a concurrent writer commits in between - see
 * {@link #updateWithOptimisticRetry}, the same pattern {@code RequirementService} established for
 * issue #108. Neither race is visible to a well-formed caller; only sustained, pathological
 * contention on the very same project surfaces as {@link StaleProjectException}.</p>
 */
public class ProjectService
        implements RegisterProject, AdoptProject, AttachAnchor, RenameProject, ListProjects,
        ListAdoptableDatasets, ResolveProject, FindProject {

    /**
     * Bound on {@link #updateWithOptimisticRetry}'s retry loop, mirroring {@code
     * RequirementService#MAX_RETRY_ATTEMPTS}: the race this guards against - two callers
     * read-modify-writing the same project - is resolved by a single retry in the overwhelming
     * majority of cases, since each retry re-reads the now-current state before trying again;
     * this bound only exists so a pathological, sustained storm of concurrent writers against the
     * very same project fails loudly instead of looping forever.
     */
    private static final int MAX_RETRY_ATTEMPTS = 20;

    private final ProjectRegistry registry;
    private final ProjectSelfDescription selfDescription;
    private final DatasetInventory datasets;

    /**
     * Creates the service.
     *
     * @param registry        the driven registry port (must not be {@code null})
     * @param selfDescription the driven self-description port (must not be {@code null})
     * @param datasets        the driven inventory of datasets present in the store, consulted only
     *                        by {@link #adopt} and {@link #adoptable} (must not be {@code null})
     */
    public ProjectService(ProjectRegistry registry, ProjectSelfDescription selfDescription,
            DatasetInventory datasets) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.selfDescription = Objects.requireNonNull(selfDescription, "selfDescription");
        this.datasets = Objects.requireNonNull(datasets, "datasets");
    }

    @Override
    public Project register(String label, Anchor anchor) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(anchor, "anchor");
        // Checked before minting anything: an anchor that already belongs to a project must
        // reject the write without spending a fresh identity, and the registry - not this
        // client-side check - remains the final authority against a race with a concurrent
        // registration of the same anchor (the out-adapter re-checks under its own write gate).
        Optional<Project> existingOwner = registry.findByAnchor(anchor);
        if (existingOwner.isPresent()) {
            throw new AnchorAlreadyRegisteredException(anchor, existingOwner.get().id());
        }
        ProjectId id = new ProjectId(UUID.randomUUID().toString());
        Project project = new Project(id, label, List.of(anchor));
        registry.register(project);
        selfDescription.describe(project);
        return project;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The three guards run in this order for a reason. The dataset must exist, or the caller
     * would end up with a registration pointing at nothing and would take the empty dataset it then
     * writes into for its recovered model. It must be unclaimed, or two projects would share one
     * body of data. Only then is the anchor checked - the registry re-checks it under its own write
     * gate anyway, so this earlier check is for the message's sake, not for correctness.</p>
     *
     * <p>Note what is <em>not</em> checked: whether the dataset actually holds anything. An empty
     * one is adoptable, because the alternative is worse - a project whose data was legitimately
     * deleted, or a dataset created moments ago by a failed call, would otherwise be unreachable
     * with no way to say so.</p>
     */
    @Override
    public Project adopt(ProjectId datasetId, String label, Anchor anchor) {
        Objects.requireNonNull(datasetId, "datasetId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(anchor, "anchor");
        if (!datasets.existingDatasets().contains(datasetId)) {
            throw new UnknownDatasetException(datasetId);
        }
        Optional<Project> alreadyAdopted = registry.findById(datasetId);
        if (alreadyAdopted.isPresent()) {
            throw new DatasetAlreadyAdoptedException(datasetId, alreadyAdopted.get().label());
        }
        Optional<Project> anchorOwner = registry.findByAnchor(anchor);
        if (anchorOwner.isPresent()) {
            throw new AnchorAlreadyRegisteredException(anchor, anchorOwner.get().id());
        }
        Project project = new Project(datasetId, label, List.of(anchor));
        registry.register(project);
        selfDescription.describe(project);
        return project;
    }

    @Override
    public List<ProjectId> adoptable() {
        Set<ProjectId> registered = registry.findAll().stream().map(Project::id).collect(Collectors.toSet());
        return datasets.existingDatasets().stream()
                .filter(id -> !registered.contains(id))
                .sorted(Comparator.comparing(ProjectId::value))
                .toList();
    }

    @Override
    public Project attach(ProjectId projectId, Anchor anchor) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(anchor, "anchor");
        return updateWithOptimisticRetry(projectId, current -> {
            if (current.anchors().contains(anchor)) {
                return current;
            }
            Optional<Project> existingOwner = registry.findByAnchor(anchor);
            if (existingOwner.isPresent() && !existingOwner.get().id().equals(projectId)) {
                throw new AnchorAlreadyRegisteredException(anchor, existingOwner.get().id());
            }
            List<Anchor> extended = new ArrayList<>(current.anchors());
            extended.add(anchor);
            return new Project(current.id(), current.label(), extended);
        });
    }

    @Override
    public Project rename(ProjectId projectId, String newLabel) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(newLabel, "newLabel");
        return updateWithOptimisticRetry(projectId,
                current -> current.label().equals(newLabel)
                        ? current
                        : new Project(current.id(), newLabel, current.anchors()));
    }

    @Override
    public List<Project> list() {
        return registry.findAll();
    }

    @Override
    public Project resolve(Anchor anchor) {
        Objects.requireNonNull(anchor, "anchor");
        return registry.findByAnchor(anchor).orElseThrow(() -> new UnknownAnchorException(anchor));
    }

    @Override
    public Optional<Project> findById(ProjectId id) {
        Objects.requireNonNull(id, "id");
        return registry.findById(id);
    }

    /**
     * Read-modify-write helper shared by {@link #attach} and {@link #rename}: reads the current
     * project and its concurrency token together via {@link ProjectRegistry#findCurrentById},
     * derives the next state via {@code mutation}, and writes it back via
     * {@link ProjectRegistry#compareAndUpdate} plus {@link ProjectSelfDescription#describe} -
     * retrying with a fresh read whenever a concurrent writer commits a change in between.
     *
     * <p>{@code mutation} returning its input unchanged (by {@link Object#equals}) is treated as
     * a no-op: the idempotency rules of {@link #attach} (anchor already present) and
     * {@link #rename} (label unchanged) skip both the registry write and the self-description
     * write entirely.</p>
     *
     * @throws ProjectNotFoundException if no project with {@code id} is registered
     * @throws StaleProjectException    if the write keeps losing the race across every retry
     *                                  attempt
     */
    private Project updateWithOptimisticRetry(ProjectId id, UnaryOperator<Project> mutation) {
        StaleProjectException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            ProjectRegistry.CurrentProject current = registry.findCurrentById(id)
                    .orElseThrow(() -> new ProjectNotFoundException(id));
            Project updated = mutation.apply(current.project());
            if (updated.equals(current.project())) {
                return current.project();
            }
            try {
                registry.compareAndUpdate(current.head(), updated);
                selfDescription.describe(updated);
                return updated;
            } catch (StaleProjectException e) {
                // A concurrent writer replaced the project between our read and our write - retry
                // against the now-current state instead of silently discarding that change.
                lastConflict = e;
            }
        }
        throw lastConflict;
    }
}
