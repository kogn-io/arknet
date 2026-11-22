// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
import de.hauschel.arknet.prj.application.port.in.DeleteProject;
import de.hauschel.arknet.prj.application.port.in.FindProject;
import de.hauschel.arknet.prj.application.port.in.ListAdoptableDatasets;
import de.hauschel.arknet.prj.application.port.in.ListProjects;
import de.hauschel.arknet.prj.application.port.in.RegisterProject;
import de.hauschel.arknet.prj.application.port.in.RenameProject;
import de.hauschel.arknet.prj.application.port.in.ResolveProject;
import de.hauschel.arknet.prj.application.port.in.UpdateProject;
import de.hauschel.arknet.prj.application.port.out.DatasetInventory;
import de.hauschel.arknet.prj.application.port.out.ProjectDatasets;
import de.hauschel.arknet.prj.application.port.out.ProjectRegistry;
import de.hauschel.arknet.prj.application.port.out.ProjectSelfDescription;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorAlreadyRegisteredException;
import de.hauschel.arknet.prj.domain.DatasetAlreadyAdoptedException;
import de.hauschel.arknet.prj.domain.OwnProjectDeletionException;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.domain.ProjectDatasetRetainedException;
import de.hauschel.arknet.prj.domain.ProjectNotFoundException;
import de.hauschel.arknet.prj.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.prj.domain.StaleProjectException;
import de.hauschel.arknet.prj.domain.UnattributedRegistrationConflictException;
import de.hauschel.arknet.prj.domain.UnconfirmedProjectDeletionException;
import de.hauschel.arknet.prj.domain.UnknownAnchorException;
import de.hauschel.arknet.prj.domain.UnknownDatasetException;
import de.hauschel.arknet.prj.domain.UnknownProjectException;

/**
 * Application service implementing the project use cases.
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
 * {@link ProjectId} instead becomes a dataset id; minting it via that
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
 * ({@link ProjectSelfDescription#describe}), in that order: the registry is
 * where a duplicate anchor or label is caught, so it must run first.</p>
 *
 * <p><strong>The two language fields are a pair, and this service is where the pair is kept
 * consistent.</strong> {@code arkprj:defaultLanguage} (a fallback) and {@code
 * arkprj:maintainedLanguage} (a commitment) are written through two different out-port methods and
 * can each move on its own, so neither the domain record nor the out-adapter is in a position to
 * see both sides of the resulting state. {@link #register} and {@link #update} therefore evaluate
 * {@link Project#requireDefaultLanguageMaintained} against the state the call would leave behind -
 * for {@link #update} that means after merging the omitted arguments with what was just read, and
 * re-evaluated on every optimistic retry.</p>
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
 * and could already be claimed by a registry entry written outside this service's lock; a retry
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
        ListAdoptableDatasets, ResolveProject, FindProject, UpdateProject, DeleteProject {

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
    private final ProjectDatasets projectDatasets;

    /**
     * One monitor per {@link ProjectId} that {@link #withProjectLock} was ever called for in this
     * JVM. Grows for the lifetime of the process: by one fresh id per {@link #register} call that
     * gets past its language and anchor checks (a call rejected there never takes the lock), and
     * by every distinct id {@link #adopt}, {@link #update}, {@link #attach} and {@link #rename} are
     * called with - including one that names no project, since these calls take the lock before
     * they check that the id exists. {@link #delete} adds no such entry: it locks only an id it
     * has already resolved to a registered project or an existing unregistered dataset. Entries
     * are never pruned: removing one while another caller holds or waits on its monitor would let
     * a third caller obtain a fresh monitor through {@code computeIfAbsent} and run unserialised
     * alongside it; each entry is one small object, so the map is left to grow.
     */
    private final ConcurrentHashMap<ProjectId, Object> projectLocks = new ConcurrentHashMap<>();

    /**
     * Creates the service.
     *
     * @param registry        the driven registry port (must not be {@code null})
     * @param selfDescription the driven self-description port (must not be {@code null})
     * @param datasets        the driven inventory of datasets present in the store, consulted by
     *                        {@link #adopt}, {@link #adoptable} and {@link #delete} (must not be
     *                        {@code null})
     * @param projectDatasets the driven port that inspects and removes a project's own dataset,
     *                        consulted only by {@link #delete} (must not be {@code null})
     */
    public ProjectService(ProjectRegistry registry, ProjectSelfDescription selfDescription,
            DatasetInventory datasets, ProjectDatasets projectDatasets) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.selfDescription = Objects.requireNonNull(selfDescription, "selfDescription");
        this.datasets = Objects.requireNonNull(datasets, "datasets");
        this.projectDatasets = Objects.requireNonNull(projectDatasets, "projectDatasets");
    }

    @Override
    public Project register(String label, Anchor anchor, String description, String descriptionLanguage,
            String defaultLanguage, List<String> maintainedLanguages) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(anchor, "anchor");
        List<String> languages = Project.canonicalLanguages(maintainedLanguages);
        Project.requireDefaultLanguageMaintained(defaultLanguage, languages);
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
        // The registry write below carries description/descriptionLanguage/defaultLanguage/
        // maintainedLanguages as their own parameters (see
        // registerRetryingOnUnattributedConflict), not through `project` itself - but the
        // caller-visible result should reflect what was actually written, so it is built with
        // them included here.
        Project resultingProject =
                new Project(id, label, List.of(anchor), description, defaultLanguage, languages);
        return withProjectLock(id, () -> {
            registerRetryingOnUnattributedConflict(project, description, descriptionLanguage, defaultLanguage,
                    languages);
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
     * <p><strong>The guards run under the project lock</strong>, so an overlapping {@link #delete}
     * of the same dataset cannot remove it between guard and registry write and leave a registered
     * project without a model. The same lock serialises two adopters of the same dataset: the
     * second one sees the first one's registration and gets {@link DatasetAlreadyAdoptedException}.
     * The registry write still goes through {@link #registerRetryingOnUnattributedConflict}
     * (issue #174), because the lock only orders writers that take it: a registry entry for the
     * same {@code datasetId} written outside this service's lock still surfaces as an unattributed
     * commit conflict, and a registry write for another project lands in the same system dataset
     * and may conflict with this one, depending on the store behind the port.</p>
     *
     * @throws de.hauschel.arknet.prj.domain.ResourceAlreadyExistsException only if a registry entry
     *                                        for {@code datasetId} was written outside this
     *                                        service's lock between the guard and the write
     */
    @Override
    public Project adopt(ProjectId datasetId, String label, Anchor anchor) {
        Objects.requireNonNull(datasetId, "datasetId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(anchor, "anchor");
        Project project = new Project(datasetId, label, List.of(anchor));
        return withProjectLock(datasetId, () -> {
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
            registerRetryingOnUnattributedConflict(project, null, null, null, List.of());
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
            // description/defaultLanguage/maintainedLanguages carried forward unchanged: attach()
            // never touches them, and the registry's compareAndUpdate write never re-serialises
            // them either way (see KognioRdfProjectRegistry) - explicit here so equals()-based
            // no-op detection and the returned Project both still reflect them faithfully.
            return new Project(current.id(), current.label(), extended, current.description(),
                    current.defaultLanguage(), current.maintainedLanguages());
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
                                current.defaultLanguage(), current.maintainedLanguages()));
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
            String defaultLanguage, List<String> maintainedLanguages) {
        Objects.requireNonNull(projectId, "projectId");
        // null means "leave the set alone"; an empty list means "remove it" - the one argument
        // here whose empty value is a change rather than an omission (see UpdateProject).
        List<String> languages =
                maintainedLanguages == null ? null : Project.canonicalLanguages(maintainedLanguages);
        return withProjectLock(projectId, () -> {
            if (description == null && defaultLanguage == null && languages == null) {
                return registry.findById(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
            }
            StaleProjectException lastConflict = null;
            for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
                ProjectRegistry.CurrentProject current = registry.findCurrentById(projectId)
                        .orElseThrow(() -> new ProjectNotFoundException(projectId));
                // The invariant is checked against the state this call would LEAVE BEHIND, not
                // against either argument on its own: an omitted argument keeps the current value,
                // so a default language moving out of an untouched set and a set moving out from
                // under an untouched default language are the very same violation, and both have
                // to be caught here. Re-evaluated on every retry, because the state it is checked
                // against was just re-read.
                Project.requireDefaultLanguageMaintained(
                        defaultLanguage != null ? defaultLanguage : current.project().defaultLanguage(),
                        Project.maintainedLanguagesAfter(current.project().maintainedLanguages(), languages));
                try {
                    Project updated = registry.updateAttributes(projectId, current.head(), description,
                            descriptionLanguage, defaultLanguage, languages);
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
     * {@inheritDoc}
     *
     * <p>The guards run before anything is removed, in this order: the reference must name exactly
     * one registered project - or, if it names none, an unregistered dataset -, that project must
     * not be the caller's own, and a dataset still holding model resources or version history needs
     * {@code confirmed}. Only then is the registry entry removed, and after it the dataset - see
     * {@link DeleteProject} for why that order. An unregistered dataset has no registry entry and
     * no own project to guard; only the dataset is removed.</p>
     *
     * <p>The count and the removals run under {@link #withProjectLock}, so no attach, rename or
     * update of the same project through this service interleaves with them. A call already past
     * its anchor resolution when the registry entry goes can still touch the dataset afterwards.
     * The store refuses to remove a dataset such a call holds open, and any refusal of the dataset
     * removal is reported as {@link ProjectDatasetRetainedException}. A call that opens the dataset
     * only after the removal brings back a dataset no project points to - the same orphan, visible
     * in {@code project_list} and removable by its id.</p>
     *
     * <p>The store's short retry for an open lease runs inside the lock on purpose: it only blocks
     * calls on the project being deleted, and releasing the lock for the wait would let an attach,
     * rename or adopt of that project slip in between the registry removal and the dataset
     * removal.</p>
     *
     * <p><strong>The lock does not cover writers of other contexts.</strong> {@code req_add} and
     * the other model writers do not take this service's per-project lock, so a resource written
     * between the count and the removal is deleted without the confirmation it would have needed,
     * and the answer reports only what the count found. The window is the time between two store
     * calls; closing it would need a lock shared by every context, which the project component
     * does not own.</p>
     */
    @Override
    public Deletion delete(String project, List<Anchor> callerAnchors, boolean confirmed) {
        Objects.requireNonNull(callerAnchors, "callerAnchors");
        String wanted = requireReference(project);
        Optional<Project> registered = findRegisteredByReference(wanted);
        if (registered.isEmpty()) {
            return deleteUnregisteredDataset(wanted, confirmed);
        }
        Project target = registered.get();
        for (Anchor callerAnchor : callerAnchors) {
            registry.findByAnchor(callerAnchor)
                    .filter(own -> own.id().equals(target.id()))
                    .ifPresent(own -> {
                        throw new OwnProjectDeletionException(own);
                    });
        }
        return withProjectLock(target.id(), () -> {
            Map<String, Long> resources = projectDatasets.modelResourceCounts(target.id());
            long historyEntries = projectDatasets.historyEntryCount(target.id());
            if (needsConfirmation(resources, historyEntries) && !confirmed) {
                throw new UnconfirmedProjectDeletionException(target, resources, historyEntries);
            }
            try {
                registry.delete(target.id());
            } catch (ProjectNotFoundException e) {
                // A concurrent call deleted it between the lookup above and this removal - the
                // same answer as a reference that never named a project, in the port's own terms.
                throw new UnknownProjectException(wanted);
            }
            try {
                projectDatasets.delete(target.id());
            } catch (RuntimeException e) {
                // Whatever stopped the removal - an open lease or a storage failure - the registry
                // entry is already gone, so the state and its remedy are the same.
                throw new ProjectDatasetRetainedException(target, e);
            }
            return Deletion.ofProject(target, resources, historyEntries);
        });
    }

    /**
     * Removes the unregistered dataset whose id is {@code wanted}. The candidates come from
     * {@link #adoptable()}, never from probing the store with the value: opening a dataset by an
     * unknown id would create it.
     */
    private Deletion deleteUnregisteredDataset(String wanted, boolean confirmed) {
        ProjectId dataset = adoptable().stream()
                .filter(candidate -> candidate.value().equals(wanted))
                .findFirst()
                .orElseThrow(() -> new UnknownProjectException(wanted));
        return withProjectLock(dataset, () -> {
            if (!datasets.existingDatasets().contains(dataset)) {
                // A concurrent call removed it between the lookup above and the lock - the same
                // answer as for a registered project that went away.
                throw new UnknownProjectException(wanted);
            }
            if (registry.findById(dataset).isPresent()) {
                throw new IllegalArgumentException("The dataset '" + wanted + "' was registered as a project "
                        + "while it was being deleted. Name it by its id or label again.");
            }
            Map<String, Long> resources = projectDatasets.modelResourceCounts(dataset);
            long historyEntries = projectDatasets.historyEntryCount(dataset);
            if (needsConfirmation(resources, historyEntries) && !confirmed) {
                throw new UnconfirmedProjectDeletionException(dataset, resources, historyEntries);
            }
            try {
                projectDatasets.delete(dataset);
            } catch (RuntimeException e) {
                throw new ProjectDatasetRetainedException(dataset, e);
            }
            return Deletion.ofUnregisteredDataset(dataset, resources, historyEntries);
        });
    }

    /** A dataset is empty only if it holds neither model resources nor history. */
    private static boolean needsConfirmation(Map<String, Long> resources, long historyEntries) {
        return !resources.isEmpty() || historyEntries > 0;
    }

    private static String requireReference(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Name the project to delete by its id or its label, "
                    + "or an unregistered dataset by its id, as project_list shows them.");
        }
        return reference.strip();
    }

    /**
     * Finds the one registered project whose id or label is {@code wanted}, if any. Ids are opaque
     * and labels free-form, so one project's label can spell another project's id; such a reference
     * is refused rather than resolved to either, since a deletion must not guess.
     */
    private Optional<Project> findRegisteredByReference(String wanted) {
        List<Project> matches = registry.findAll().stream()
                .filter(candidate -> candidate.id().value().equals(wanted) || candidate.label().equals(wanted))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalArgumentException("'" + wanted + "' is ambiguous: it is the id of one registered "
                    + "project and the label of another. Rename one of them with project_rename first.");
        }
        return matches.stream().findFirst();
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
     * pre-existing {@code datasetId}; a second adopter of that dataset is already turned away by
     * the guards {@link #adopt} runs under the project lock, so a conflict reaching this method
     * comes from a writer that does not take that lock: a registry entry for the same
     * {@code datasetId} written outside this service, or - depending on the store behind the
     * port - a registry write for another project in the shared system dataset. A retry is still
     * correct: it does not manufacture success where none is possible, it only
     * re-runs the registry's own guards against the now-committed state, turning an ambiguous,
     * unattributed conflict into either a real success (nothing actually collided) or a
     * well-attributed exception such as {@link AnchorAlreadyRegisteredException} instead of a raw,
     * unactionable signal.</p>
     *
     * @throws UnattributedRegistrationConflictException if the write keeps losing an
     *                                         unattributable conflict across every retry attempt
     */
    private void registerRetryingOnUnattributedConflict(Project project, String description,
            String descriptionLanguage, String defaultLanguage, List<String> maintainedLanguages) {
        UnattributedRegistrationConflictException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                registry.register(project, description, descriptionLanguage, defaultLanguage,
                        maintainedLanguages);
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
