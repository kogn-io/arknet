// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

import de.hauschel.arknet.bc.application.port.in.AddBoundedContext;
import de.hauschel.arknet.bc.application.port.in.GetBoundedContext;
import de.hauschel.arknet.bc.application.port.in.LinkContext;
import de.hauschel.arknet.bc.application.port.in.LinkTerm;
import de.hauschel.arknet.bc.application.port.in.ListBoundedContexts;
import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts;
import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.application.port.out.ContextRelationshipRepository;
import de.hauschel.arknet.bc.application.port.out.TermLookup;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextConcurrentlyModifiedException;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.BoundedContextNotFoundException;
import de.hauschel.arknet.bc.domain.ContextRelationship;
import de.hauschel.arknet.bc.domain.ContextRelationshipId;
import de.hauschel.arknet.bc.domain.DuplicateBoundedContextCodeException;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.bc.domain.TermRef;
import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.CodeCounter;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Application service implementing the bounded-context use cases.
 *
 * <p>This is the policy seat of the hexagon: it drives the {@link BoundedContextRepository}
 * driven port. The component is wired as a plain object (constructor injection) by the
 * composition root; there are deliberately no framework annotations here.</p>
 *
 * <p><strong>Policy.</strong> Identity ({@link BoundedContextId}) is opaque and minted once per
 * bounded context via {@link ResourceIdFactory}; it never changes. The human-readable business
 * code ({@link BoundedContextCode}, {@code BC-N}) is assigned independently, where {@code N} is
 * one above the highest running number currently used in the target project (numbering is
 * independent per project, starting at 1). Linking a glossary term is idempotent - a term may
 * be linked to a bounded context at any time; the edge lives inside the aggregate and is
 * therefore carried along by every subsequent replace-by-identity write. {@link #linkContext}, in
 * contrast, is pure create with no idempotency check: it resolves both bounded-context codes
 * against {@link #repository}, mints a fresh {@link ContextRelationshipId} and persists the
 * resulting {@link ContextRelationship} as its own resource - never as a field on either bounded
 * context.</p>
 *
 * <p><strong>Concurrency.</strong> {@link #add} recomputes its next code
 * against a fresh read whenever a concurrent {@code bc_add} claims the same {@code BC-N} first,
 * via {@link CodeAssignment#createRetryingOnCodeCollision}, and {@link #linkTerm} retries its
 * whole read-modify-write round trip via
 * {@link BoundedContextRepository#compareAndUpdate} whenever a concurrent writer commits in
 * between - see {@link #updateWithOptimisticRetry}. Neither race is visible to a well-formed
 * caller; only sustained, pathological contention on the very same bounded context surfaces as
 * {@link BoundedContextConcurrentlyModifiedException}. Parallel sessions of one user against one
 * local store are the normal case, not a remote/multi-writer concern (ADR-001).</p>
 */
public class BoundedContextService implements AddBoundedContext, ListBoundedContexts,
        GetBoundedContext, LinkTerm, ResolveBoundedContexts, LinkContext {

    private static final String CODE_PREFIX = "BC";

    /**
     * Bound on {@link #updateWithOptimisticRetry}'s compare-and-set retry loop. Two
     * callers read-modify-writing the same bounded context are resolved by a single retry in the
     * overwhelming majority of cases, since each retry re-reads the now-current state before
     * trying again; this bound only exists so a pathological, sustained storm of concurrent
     * writers against the very same bounded context fails loudly instead of looping forever.
     */
    private static final int MAX_RETRY_ATTEMPTS = 20;

    private final BoundedContextRepository repository;
    private final ResourceIdFactory resourceIdFactory;
    private final TermLookup termLookup;
    private final ContextRelationshipRepository contextRelationshipRepository;

    /**
     * Creates the service.
     *
     * @param repository                    the driven persistence port (must not be {@code null})
     * @param resourceIdFactory              mints the opaque identity of a newly added bounded
     *                                       context or context relationship (must not be
     *                                       {@code null})
     * @param termLookup                     resolves a human-typed glossary term code to its
     *                                       opaque identity (must not be {@code null})
     * @param contextRelationshipRepository  the driven persistence port for
     *                                       {@link ContextRelationship} (must not be
     *                                       {@code null})
     */
    public BoundedContextService(BoundedContextRepository repository, ResourceIdFactory resourceIdFactory,
            TermLookup termLookup, ContextRelationshipRepository contextRelationshipRepository) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        this.termLookup = Objects.requireNonNull(termLookup, "termLookup");
        this.contextRelationshipRepository =
                Objects.requireNonNull(contextRelationshipRepository, "contextRelationshipRepository");
    }

    @Override
    public BoundedContext add(ProjectId projectId, NewBoundedContext command) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(command, "command");
        // Identity is opaque and stable, so it is minted once, outside the retry: only the
        // business code is recomputed when a concurrent bc_add claims the same candidate first.
        // See CodeAssignment for why that race exists and why it must retry rather
        // than surface the out-adapter's uniqueness guard as a caller-visible failure.
        BoundedContextId id = new BoundedContextId(resourceIdFactory.newId());
        return CodeAssignment.createRetryingOnCodeCollision(
                DuplicateBoundedContextCodeException.class, () -> {
                    BoundedContextCode code = nextCode(projectId);
                    BoundedContext boundedContext = new BoundedContext(id, code, command.name(),
                            command.domainVision(), command.subdomain(), command.ownedBy(), List.of());
                    repository.create(projectId, boundedContext);
                    return boundedContext;
                });
    }

    @Override
    public List<BoundedContext> list(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return repository.findAll(projectId);
    }

    @Override
    public Optional<BoundedContext> get(ProjectId projectId, BoundedContextCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return repository.findByCode(projectId, code);
    }

    @Override
    public List<ResolvedBoundedContext> resolveExisting(ProjectId projectId, ResourceId... ids) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ids, "ids");
        if (ids.length == 0) {
            return List.of();
        }
        return repository.findByIds(projectId, List.of(ids));
    }

    @Override
    public BoundedContext linkTerm(ProjectId projectId, BoundedContextCode code, String termCode) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(termCode, "termCode");
        // Resolution does not depend on the bounded context's current state, so it happens once,
        // outside the retry loop below - an unknown/ambiguous term code must propagate as a
        // didactic rejection immediately and leave the bounded context untouched.
        TermRef term = new TermRef(termLookup.resolveByCode(projectId, termCode));
        return updateWithOptimisticRetry(projectId, code, current -> {
            if (current.usesTerms().contains(term)) {
                return current;
            }
            List<TermRef> linked = new ArrayList<>(current.usesTerms());
            linked.add(term);
            return new BoundedContext(current.id(), current.code(), current.name(),
                    current.domainVision(), current.subdomain(), current.ownedBy(), linked);
        });
    }

    @Override
    public ContextRelationship linkContext(ProjectId projectId, BoundedContextCode upstreamCode,
            BoundedContextCode downstreamCode, RelationshipType relationshipType) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(upstreamCode, "upstreamCode");
        Objects.requireNonNull(downstreamCode, "downstreamCode");
        Objects.requireNonNull(relationshipType, "relationshipType");
        BoundedContextId upstream = repository.findByCode(projectId, upstreamCode)
                .orElseThrow(() -> new BoundedContextNotFoundException(projectId, upstreamCode))
                .id();
        BoundedContextId downstream = repository.findByCode(projectId, downstreamCode)
                .orElseThrow(() -> new BoundedContextNotFoundException(projectId, downstreamCode))
                .id();
        ContextRelationshipId id = new ContextRelationshipId(resourceIdFactory.newId());
        ContextRelationship relationship = new ContextRelationship(id, upstream, downstream, relationshipType);
        return contextRelationshipRepository.create(projectId, relationship);
    }

    /**
     * Read-modify-write helper behind {@link #linkTerm}: reads the current bounded context and its
     * concurrency token together via {@link BoundedContextRepository#findCurrentByCode}, derives
     * the next state via {@code mutation}, and writes it back via
     * {@link BoundedContextRepository#compareAndUpdate} - retrying with a fresh read whenever a
     * concurrent writer commits a change in between (two parallel {@code bc_link_term}
     * round trips on the same bounded context used to silently lose whichever one committed last,
     * because the read happened outside any transaction and the write carried no guard at all).
     *
     * <p>{@code mutation} returning its input unchanged (by {@link Object#equals}) is treated as a
     * no-op: linking an already-linked term skips the write entirely, exactly as before this
     * fix.</p>
     *
     * @throws BoundedContextNotFoundException             if no bounded context with {@code code}
     *                                                     exists
     * @throws BoundedContextConcurrentlyModifiedException if the write keeps losing the race
     *                                                     across every retry attempt
     */
    private BoundedContext updateWithOptimisticRetry(
            ProjectId projectId, BoundedContextCode code, UnaryOperator<BoundedContext> mutation) {
        BoundedContextConcurrentlyModifiedException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            BoundedContextRepository.CurrentBoundedContext current =
                    repository.findCurrentByCode(projectId, code)
                            .orElseThrow(() -> new BoundedContextNotFoundException(projectId, code));
            BoundedContext updated = mutation.apply(current.value());
            if (updated.equals(current.value())) {
                return current.value();
            }
            try {
                repository.compareAndUpdate(projectId, current.head(), updated);
                return updated;
            } catch (BoundedContextConcurrentlyModifiedException e) {
                // A concurrent writer replaced the bounded context between our read and our write -
                // retry against the now-current state instead of silently discarding that change.
                lastConflict = e;
            }
        }
        throw lastConflict;
    }

    /**
     * Derives the next free business code in {@code projectId}: the highest running number the
     * project already uses, plus one (starting at 1).
     *
     * <p><strong>{@link BoundedContextRepository#findAllCodes}, not
     * {@link BoundedContextRepository#findAll} (kogn-io/arknet#360).</strong> A bounded context
     * written store-first (ADR-005) without {@code arknet:name} or {@code arkddd:domainVision} is
     * invisible to {@code findAll}, which joins both as mandatory - yet its {@code BC-N} is taken
     * just the same. Counting over {@code findAll} would therefore hand that very number out again
     * as soon as such a context holds the project's highest one; {@link #create}'s uniqueness guard
     * then rejects the write, and because every retry recomputes the identical number,
     * {@link CodeAssignment#createRetryingOnCodeCollision} cannot work its way past it either -
     * {@code bc_add} would be permanently dead for that project rather than merely racing.
     * {@code findAllCodes} reads the identifier without those joins, so this computation no longer
     * depends on how complete a context's other fields are.</p>
     */
    private BoundedContextCode nextCode(ProjectId projectId) {
        String prefix = CODE_PREFIX + "-";
        int highest = CodeCounter.highestRunningNumber(prefix,
                repository.findAllCodes(projectId), BoundedContextCode::value);
        return new BoundedContextCode(prefix + (highest + 1));
    }
}
