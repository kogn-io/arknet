// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.actor.application.port.in.AddActor;
import de.hauschel.arknet.actor.application.port.in.DeleteActor;
import de.hauschel.arknet.actor.application.port.in.GetActor;
import de.hauschel.arknet.actor.application.port.in.ListActors;
import de.hauschel.arknet.actor.application.port.in.UpdateActor;
import de.hauschel.arknet.actor.application.port.out.ActorRepository;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorConcurrentlyModifiedException;
import de.hauschel.arknet.actor.domain.ActorId;
import de.hauschel.arknet.actor.domain.ActorNotFoundException;
import de.hauschel.arknet.actor.domain.DuplicateActorCodeException;
import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.CodeCounter;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;

/**
 * Application service implementing the actor use cases.
 *
 * <p>This is the policy seat of the hexagon: it drives the {@link ActorRepository} driven port. The
 * component is wired as a plain object (constructor injection) by the composition root; there are
 * deliberately no framework annotations here.</p>
 *
 * <p><strong>Policy.</strong> Identity ({@link ActorId}) is opaque and minted once per actor via
 * {@link ResourceIdFactory}; it never changes. The human-readable business code
 * ({@link ActorCode}, {@code ACTOR-N}) is assigned independently, where {@code N} is one above the
 * highest running number currently used in the target project (numbering is independent per
 * project, starting at 1). Unlike {@code ConstraintService}, there is exactly one counter for all
 * four {@link de.hauschel.arknet.actor.domain.ActorType}s - see that enum for why. Neither code nor
 * type is ever reassigned afterwards: {@link #update} changes text only (see {@link UpdateActor}).
 * </p>
 *
 * <p><strong>Concurrency.</strong> {@link #add} recomputes its next code against a fresh read
 * whenever a concurrent {@code actor_add} claims the same {@code ACTOR-N} first, via
 * {@link CodeAssignment#createRetryingOnCodeCollision}, and {@link #update} runs the
 * read-modify-write retry loop {@link #updateWithOptimisticRetry} against the compare-and-set guard
 * on {@link ActorRepository#compareAndUpdate} whenever a concurrent writer commits in between.
 * Neither race is visible to a well-formed caller; only sustained, pathological contention on the
 * very same actor surfaces as {@link ActorConcurrentlyModifiedException}. Parallel sessions of one
 * user against one local store are the normal case, not a remote/multi-writer concern.</p>
 */
public class ActorService implements AddActor, ListActors, GetActor, UpdateActor, DeleteActor {

    private static final String CODE_PREFIX = "ACTOR";

    /**
     * Bound on {@link #add}'s and {@link #updateWithOptimisticRetry}'s retry loops. Two callers
     * read-modify-writing the same actor are resolved by a single retry in the overwhelming
     * majority of cases, since each retry re-reads the now-current state before trying again; this
     * bound only exists so a pathological, sustained storm of concurrent writers against the very
     * same actor fails loudly instead of looping forever.
     */
    static final int MAX_RETRY_ATTEMPTS = CodeAssignment.DEFAULT_MAX_ATTEMPTS;

    private final ActorRepository repository;
    private final ResourceIdFactory resourceIdFactory;

    /**
     * Creates the service.
     *
     * @param repository        the driven persistence port (must not be {@code null})
     * @param resourceIdFactory mints the opaque identity of a newly added actor (must not be
     *                          {@code null})
     */
    public ActorService(ActorRepository repository, ResourceIdFactory resourceIdFactory) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
    }

    @Override
    public Actor add(ProjectId projectId, NewActor command) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(command, "command");
        // Identity is opaque and stable, so it is minted once, outside the retry: only the business
        // code is recomputed when a concurrent actor_add claims the same candidate first. See
        // CodeAssignment for why that race exists and why it must retry rather than surface the
        // out-adapter's uniqueness guard as a caller-visible failure.
        ActorId id = new ActorId(resourceIdFactory.newId());
        return CodeAssignment.createRetryingOnCodeCollision(MAX_RETRY_ATTEMPTS,
                DuplicateActorCodeException.class, () -> {
                    ActorCode code = nextCode(projectId);
                    Actor actor = new Actor(id, code, command.type(), command.name(), command.description());
                    repository.create(projectId, actor);
                    return actor;
                });
    }

    @Override
    public List<Actor> list(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return repository.findAll(projectId);
    }

    @Override
    public Optional<Actor> get(ProjectId projectId, ActorCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return repository.findByCode(projectId, code);
    }

    /**
     * Batch-resolves opaque actor identities back to their identity and business code, in a
     * single store round-trip - a plain, directly usable capability of this service rather than
     * an implementation of a driving port. Until ADR-37/kogn-io/arknet#405 Part C this method
     * implemented the driving port {@code ResolveActors}, published for {@code arknet-use-cases}'
     * driving adapter to render a use case's {@code primaryActor}/{@code supportingActor} as their
     * business code; Part C repointed those edges at {@code arkproc:Role}, so that port and its
     * last external consumer are both gone. Never rejects: an id that resolves to nothing in the
     * project is simply absent from the result.
     *
     * @param projectId the project (architecture model) to resolve actors in
     * @param ids       the opaque identities to resolve; may be empty
     * @return the resolved actors found; an id absent from the project is simply absent here too,
     *         never {@code null}
     */
    public List<ActorRepository.ActorProjection> resolveExisting(ProjectId projectId, ResourceId... ids) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ids, "ids");
        if (ids.length == 0) {
            return List.of();
        }
        return repository.findByIds(projectId, List.of(ids));
    }

    @Override
    public Actor update(ProjectId projectId, ActorCode code, String name, String description) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return updateWithOptimisticRetry(projectId, code, name, description);
    }

    @Override
    public void delete(ProjectId projectId, ActorCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        // The reference check (is anything else in the project still pointing at this actor?) is
        // the out-adapter's business - it is the only side that can traverse the store's other
        // named graphs.
        repository.delete(projectId, code);
    }

    /**
     * Read-modify-write helper behind {@link #update}: reads the current actor and its concurrency
     * token together via {@link ActorRepository#findCurrentByCode}, derives the next state, and
     * writes it back via {@link ActorRepository#compareAndUpdate} - retrying with a fresh read
     * whenever a concurrent writer commits a change in between, so two parallel round trips on the
     * same actor cannot silently lose whichever committed last.
     *
     * <p>A call that changes nothing is a no-op: it returns the actor as read without writing. With
     * no language tags in play (see {@link Actor}), value equality is the whole test - there is no
     * {@code ConstraintService}-style "same text, different tag is still a write" case here.</p>
     *
     * @throws ActorNotFoundException             if no actor with {@code code} exists
     * @throws ActorConcurrentlyModifiedException if the write keeps losing the race across every
     *                                            retry attempt
     */
    private Actor updateWithOptimisticRetry(
            ProjectId projectId, ActorCode code, String name, String description) {
        ActorConcurrentlyModifiedException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            ActorRepository.CurrentActor current = repository.findCurrentByCode(projectId, code)
                    .orElseThrow(() -> new ActorNotFoundException(projectId, code));
            Actor updated = new Actor(current.value().id(), current.value().code(), current.value().type(),
                    name != null ? name : current.value().name(),
                    description != null ? description : current.value().description());
            if (updated.equals(current.value())) {
                return current.value();
            }
            try {
                repository.compareAndUpdate(projectId, current.head(), updated);
                return updated;
            } catch (ActorConcurrentlyModifiedException e) {
                // A concurrent writer replaced the actor between our read and our write - retry
                // against the now-current state instead of silently discarding that change.
                lastConflict = e;
            }
        }
        throw lastConflict;
    }

    /**
     * Derives the next free business code in {@code projectId}: the highest running number the
     * project has ever used, plus one (starting at 1). One counter for every
     * {@link de.hauschel.arknet.actor.domain.ActorType} - no per-type filter, unlike
     * {@code ConstraintService#nextCode}.
     *
     * <p><strong>Ever used, not currently in use.</strong> The maximum runs over the living actors
     * <em>and</em> the codes {@link ActorRepository#findRetainedCodes} kept from deleted ones
     * (issue #350). Over the living ones alone, deleting the highest-numbered actor would let the
     * maximum fall back and the next {@code actor_add} hand out that same number again - and a code
     * that already appeared in a commit message or a note would then name something else
     * entirely.</p>
     *
     * <p><strong>{@link ActorRepository#findAllCodes}, not {@link ActorRepository#findAll}
     * (kogn-io/arknet#360).</strong> A living actor can still be missing from {@link #list}/
     * {@link ActorRepository#findAll}: building an {@link de.hauschel.arknet.actor.domain.Actor}
     * needs a name, and a store-first actor written without one has no name to give - it
     * is skipped on the way out while keeping its {@code ACTOR-N}. Deriving the maximum from
     * {@code findAll} would recompute that number the moment such an actor holds the project's
     * highest one, and every retry recomputes it again, so
     * {@link CodeAssignment#createRetryingOnCodeCollision} could not retry its way out of the
     * resulting {@link DuplicateActorCodeException} - {@code actor_add} would be dead for the project
     * rather than merely racing. {@code findAllCodes} reads only the type/identifier pair, which no
     * missing field can hide, so this maximum never depends on materialisability.</p>
     */
    private ActorCode nextCode(ProjectId projectId) {
        String prefix = CODE_PREFIX + "-";
        int highestLiving = CodeCounter.highestRunningNumber(prefix,
                repository.findAllCodes(projectId), ActorCode::value);
        int highestRetained = CodeCounter.highestRunningNumber(prefix,
                repository.findRetainedCodes(projectId), ActorCode::value);
        return new ActorCode(prefix + (Math.max(highestLiving, highestRetained) + 1));
    }
}
