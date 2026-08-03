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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
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
import de.hauschel.arknet.prj.application.port.in.UpdateProject;
import de.hauschel.arknet.prj.application.port.out.DatasetInventory;
import de.hauschel.arknet.prj.application.port.out.ProjectRegistry;
import de.hauschel.arknet.prj.application.port.out.ProjectSelfDescription;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorAlreadyRegisteredException;
import de.hauschel.arknet.prj.domain.DatasetAlreadyAdoptedException;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.domain.ProjectNotFoundException;
import de.hauschel.arknet.prj.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.prj.domain.StaleProjectException;
import de.hauschel.arknet.prj.domain.UnattributedRegistrationConflictException;
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
 * {@link #updateWithOptimisticRetry}, the same pattern {@code RequirementService} established.
 * Neither race is visible to a well-formed caller; only sustained, pathological
 * contention on the very same project surfaces as {@link StaleProjectException}. {@link #register}
 * and {@link #adopt} are both creates, not a read-modify-write, so neither needs a fresh read to
 * retry: a real store commit conflict neither uniqueness guard can explain
 * ({@link UnattributedRegistrationConflictException}) is retried against the very same,
 * already-built candidate via {@link #registerRetryingOnUnattributedConflict} - see that
 * exception's javadoc for why repeating the identical write is honest here. Unlike
 * {@link #register}'s freshly minted identity, {@link #adopt}'s {@code datasetId} is caller-chosen
 * and can genuinely already be claimed by a concurrent adopter of the very same dataset; a retry
 * does not paper over that, it only converts an ambiguous, unattributed conflict into a
 * definitive answer - either the retry succeeds because nothing really collided, or the registry's
 * own synchronous identity guard now sees the winner's committed write and rejects the retry with a
 * well-attributed {@link ResourceAlreadyExistsException} instead of leaving the caller with a raw
 * commit-conflict signal it cannot act on (issue #174). Only sustained, pathological contention
 * surfaces {@link UnattributedRegistrationConflictException} itself to the caller.</p>
 *
 * <p><strong>Registry write and self-description commit as one unit per project.</strong> A
 * {@link ProjectRegistry} write (a fresh registration, an attached anchor, a rename) and the
 * {@link ProjectSelfDescription#describe} call that follows it are not one transaction - the
 * registry lives in the system dataset, the self-description in the project's own dataset (issue
 * #173). Left unguarded, two overlapping calls against the <em>same</em> {@link ProjectId} could
 * commit their registry writes in one order but their {@code describe} calls in the other, letting
 * a stale {@code describe} land after a fresher one and overwrite it - not mere staleness the next
 * write would heal, but an active regression the next write could just as easily hide again.
 * {@link #withProjectLock} closes that window by serialising "read/compare-and-update plus
 * describe" into one atomic unit per {@link ProjectId}; different projects still run fully in
 * parallel.</p>
 */
public class ProjectService
        implements RegisterProject, AdoptProject, AttachAnchor, RenameProject, ListProjects,
        ListAdoptableDatasets, ResolveProject, FindProject, UpdateProject {

    /**
     * Bound shared by {@link #updateWithOptimisticRetry} and {@link #register}'s retry loop,
     * mirroring {@code RequirementService#MAX_RETRY_ATTEMPTS}: the races both guard against - two
     * callers read-modify-writing the same project, or a fresh registration losing an
     * unattributable store commit conflict - are resolved by a single retry in the overwhelming
     * majority of cases; this bound only exists so pathological, sustained contention fails
     * loudly instead of looping forever.
     */
    private static final int MAX_RETRY_ATTEMPTS = 20;

    private final ProjectRegistry registry;
    private final ProjectSelfDescription selfDescription;
    private final DatasetInventory datasets;

    /**
     * One monitor per {@link ProjectId} that has ever been the target of a registry write in this
     * JVM, backing {@link #withProjectLock}. Grows with the number of distinct projects a caller
     * has touched, not with the number of writes - bounded by how many projects genuinely exist,
     * so it is not pruned.
     */
    private final ConcurrentHashMap<ProjectId, Object> projectLocks = new ConcurrentHashMap<>();

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
    public Project register(String label, Anchor anchor, String description, String descriptionLanguage,
            String defaultLanguage) {
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
        // The registry write below carries description/descriptionLanguage/defaultLanguage as
        // their own parameters (see registerRetryingOnUnattributedConflict), not through
        // `project` itself - but the caller-visible result should reflect what was actually
        // written, so it is built with them included here.
        Project resultingProject = new Project(id, label, List.of(anchor), description, defaultLanguage);
        return withProjectLock(id, () -> {
            registerRetryingOnUnattributedConflict(project, description, descriptionLanguage, defaultLanguage);
            selfDescription.describe(resultingProject);
            return resultingProject;
        });
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
     *
     * <p><strong>The three guards above run outside any transaction and cannot themselves close
     * the TOCTOU window between two concurrent adopters of the same dataset (issue #174).</strong>
     * Both can pass their {@code findById} check before either has committed; the registry write
     * that follows goes through {@link #registerRetryingOnUnattributedConflict} for exactly that
     * reason - see its javadoc for why a retry is still correct here even though this method,
     * unlike {@link #register}, hands it a caller-chosen rather than freshly minted identity.</p>
     *
     * @throws de.hauschel.arknet.prj.domain.ResourceAlreadyExistsException if a concurrent adopter
     *                                        won the race for the very same {@code datasetId}
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
        return withProjectLock(datasetId, () -> {
            registerRetryingOnUnattributedConflict(project, null, null, null);
            selfDescription.describe(project);
            return project;
        });
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
            // description/defaultLanguage carried forward unchanged: attach() never touches them,
            // and the registry's compareAndUpdate write never re-serialises them either way (see
            // KognioRdfProjectRegistry) - explicit here so equals()-based no-op detection and the
            // returned Project both still reflect them faithfully.
            return new Project(current.id(), current.label(), extended, current.description(),
                    current.defaultLanguage());
        });
    }

    @Override
    public Project rename(ProjectId projectId, String newLabel) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(newLabel, "newLabel");
        return updateWithOptimisticRetry(projectId,
                current -> current.label().equals(newLabel)
                        ? current
                        : new Project(current.id(), newLabel, current.anchors(), current.description(),
                                current.defaultLanguage()));
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
     * {@inheritDoc}
     *
     * <p>A no-op call ({@code description} and {@code defaultLanguage} both {@code null}) returns
     * the project's current state without consulting the registry's write path at all - no
     * revision, no moved head, mirroring {@code KognioRdfTermRepository#attemptUpdate}'s
     * equivalent no-op guard.</p>
     *
     * <p>Retries via {@link ProjectRegistry#findCurrentById}/{@link
     * ProjectRegistry#updateAttributes} on a lost CAS race, the same pattern {@link
     * #updateWithOptimisticRetry} already establishes for {@link #attach}/{@link #rename} - kept
     * as its own loop rather than folded into that one, since {@link
     * ProjectRegistry#updateAttributes} is a targeted patch with its own out-port method, not a
     * {@link Project}-in/{@link Project}-out mutation {@link #updateWithOptimisticRetry}'s shape
     * expects.</p>
     */
    @Override
    public Project update(ProjectId projectId, String description, String descriptionLanguage,
            String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        return withProjectLock(projectId, () -> {
            if (description == null && defaultLanguage == null) {
                return registry.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
            }
            StaleProjectException lastConflict = null;
            for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
                ProjectRegistry.CurrentProject current = registry.findCurrentById(projectId)
                        .orElseThrow(() -> new ProjectNotFoundException(projectId));
                try {
                    Project updated = registry.updateAttributes(projectId, current.head(), description,
                            descriptionLanguage, defaultLanguage);
                    selfDescription.describe(updated);
                    return updated;
                } catch (StaleProjectException e) {
                    // A concurrent writer advanced the head between our read and our write - retry
                    // against the now-current state instead of surfacing a transient race.
                    lastConflict = e;
                }
            }
            throw lastConflict;
        });
    }

    /**
     * Retries a {@link ProjectRegistry#register} that lost a real store commit conflict neither
     * uniqueness guard could explain (see {@link UnattributedRegistrationConflictException}'s
     * javadoc), shared by {@link #register} and {@link #adopt} (issue #174) - the first attempt
     * was fully rolled back, so repeating the same write is the same write, not a new one, and
     * runs through every guard again: {@link AnchorAlreadyRegisteredException}/
     * {@link DuplicateProjectLabelException}/{@link ResourceAlreadyExistsException} from a later
     * attempt are not caught here and propagate immediately, since a real, now-visible collision
     * must not be retried past.
     *
     * <p>The two callers reach that guarantee for different reasons. For {@link #register},
     * {@code project} carries a freshly minted, never-reused identity, so an identity collision
     * can never be genuine and any conflict must be a spurious commit-time artifact safe to
     * retry to success. For {@link #adopt}, {@code project}'s identity is the caller-chosen,
     * pre-existing {@code datasetId}, which a concurrent adopter of the very same dataset
     * genuinely can be racing for - a retry there is still correct, just for a different reason:
     * it does not manufacture success where none is possible, it only re-runs the synchronous
     * guards against the now-committed state, turning an ambiguous, unattributed conflict into
     * either a real success (nothing actually collided) or the well-attributed
     * {@link ResourceAlreadyExistsException} the loser of a genuine identity race must see instead
     * of a raw, unactionable signal.</p>
     *
     * @throws UnattributedRegistrationConflictException if the write keeps losing an
     *                                         unattributable conflict across every retry attempt
     */
    private void registerRetryingOnUnattributedConflict(Project project, String description,
            String descriptionLanguage, String defaultLanguage) {
        UnattributedRegistrationConflictException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                registry.register(project, description, descriptionLanguage, defaultLanguage);
                return;
            } catch (UnattributedRegistrationConflictException e) {
                lastConflict = e;
            }
        }
        throw lastConflict;
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
        return withProjectLock(id, () -> {
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
                    // A concurrent writer replaced the project between our read and our write -
                    // retry against the now-current state instead of silently discarding that
                    // change. In normal operation this can no longer be another ProjectService
                    // caller once withProjectLock serialises every writer of this project id; kept
                    // for a write that reaches ProjectRegistry from outside this service.
                    lastConflict = e;
                }
            }
            throw lastConflict;
        });
    }

    /**
     * Runs {@code action} - a registry write immediately followed by
     * {@link ProjectSelfDescription#describe} - as one atomic unit against every other caller
     * writing the <em>same</em> {@code projectId} (issue #173): the two calls target different
     * datasets (the system registry vs. the project's own) and cannot commit as one transaction,
     * so without this lock two overlapping writers could commit their registry writes in one
     * order and their {@code describe} calls in the other, leaving the project's self-description
     * actively wrong rather than merely stale. Callers writing <em>different</em> projects never
     * contend with each other.
     */
    private <T> T withProjectLock(ProjectId projectId, Supplier<T> action) {
        Object lock = projectLocks.computeIfAbsent(projectId, id -> new Object());
        synchronized (lock) {
            return action.get();
        }
    }
}
