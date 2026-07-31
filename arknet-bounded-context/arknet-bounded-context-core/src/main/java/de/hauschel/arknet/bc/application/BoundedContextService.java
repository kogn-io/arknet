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
import de.hauschel.arknet.bc.application.port.in.LinkTerm;
import de.hauschel.arknet.bc.application.port.in.ListBoundedContexts;
import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts;
import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.application.port.out.TermLookup;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextConcurrentlyModifiedException;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.BoundedContextNotFoundException;
import de.hauschel.arknet.bc.domain.DuplicateBoundedContextCodeException;
import de.hauschel.arknet.bc.domain.TermRef;
import de.hauschel.arknet.kernel.CodeAssignment;
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
 * independent per workspace, starting at 1). Linking a glossary term is idempotent - a term may
 * be linked to a bounded context at any time; the edge lives inside the aggregate and is
 * therefore carried along by every subsequent replace-by-identity write.</p>
 *
 * <p><strong>Concurrency (issues #144 and #176).</strong> {@link #add} recomputes its next code
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
        GetBoundedContext, LinkTerm, ResolveBoundedContexts {

    private static final String CODE_PREFIX = "BC";

    /**
     * Bound on {@link #updateWithOptimisticRetry}'s compare-and-set retry loop (issue #176). Two
     * callers read-modify-writing the same bounded context are resolved by a single retry in the
     * overwhelming majority of cases, since each retry re-reads the now-current state before
     * trying again; this bound only exists so a pathological, sustained storm of concurrent
     * writers against the very same bounded context fails loudly instead of looping forever.
     */
    private static final int MAX_RETRY_ATTEMPTS = 20;

    private final BoundedContextRepository repository;
    private final ResourceIdFactory resourceIdFactory;
    private final TermLookup termLookup;

    /**
     * Creates the service.
     *
     * @param repository        the driven persistence port (must not be {@code null})
     * @param resourceIdFactory mints the opaque identity of a newly added bounded context (must
     *                          not be {@code null})
     * @param termLookup        resolves a human-typed glossary term code to its opaque identity
     *                          (must not be {@code null})
     */
    public BoundedContextService(BoundedContextRepository repository, ResourceIdFactory resourceIdFactory,
            TermLookup termLookup) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        this.termLookup = Objects.requireNonNull(termLookup, "termLookup");
    }

    @Override
    public BoundedContext add(ProjectId projectId, NewBoundedContext command) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(command, "command");
        // Identity is opaque and stable, so it is minted once, outside the retry: only the
        // business code is recomputed when a concurrent bc_add claims the same candidate first
        // (issue #144). See CodeAssignment for why that race exists and why it must retry rather
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

    /**
     * Read-modify-write helper behind {@link #linkTerm}: reads the current bounded context and its
     * concurrency token together via {@link BoundedContextRepository#findCurrentByCode}, derives
     * the next state via {@code mutation}, and writes it back via
     * {@link BoundedContextRepository#compareAndUpdate} - retrying with a fresh read whenever a
     * concurrent writer commits a change in between (issue #176: two parallel {@code bc_link_term}
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
     * Derives the next free business code in {@code projectId}: the highest running number
     * currently in use, plus one (starting at 1).
     */
    private BoundedContextCode nextCode(ProjectId projectId) {
        int next = repository.findAll(projectId).stream()
                .mapToInt(bc -> runningNumber(bc.code()))
                .max()
                .orElse(0) + 1;
        return new BoundedContextCode(CODE_PREFIX + "-" + next);
    }

    /** Parses the running number from a code such as {@code BC-7} (0 if not parseable). */
    private static int runningNumber(BoundedContextCode code) {
        String value = code.value();
        int dash = value.lastIndexOf('-');
        if (dash < 0 || dash == value.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(value.substring(dash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
