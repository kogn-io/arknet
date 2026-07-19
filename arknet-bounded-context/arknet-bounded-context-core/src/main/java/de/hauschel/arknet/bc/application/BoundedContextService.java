package de.hauschel.arknet.bc.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.bc.application.port.in.AddBoundedContext;
import de.hauschel.arknet.bc.application.port.in.GetBoundedContext;
import de.hauschel.arknet.bc.application.port.in.LinkTerm;
import de.hauschel.arknet.bc.application.port.in.ListBoundedContexts;
import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.application.port.out.TermLookup;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.BoundedContextNotFoundException;
import de.hauschel.arknet.bc.domain.DuplicateBoundedContextCodeException;
import de.hauschel.arknet.bc.domain.TermRef;
import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.WorkspaceId;

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
 * one above the highest running number currently used in the target workspace (numbering is
 * independent per workspace, starting at 1). Linking a glossary term is idempotent - a term may
 * be linked to a bounded context at any time; the edge lives inside the aggregate and is
 * therefore carried along by every subsequent replace-by-identity write.</p>
 *
 * <p><strong>Concurrency (issue #144).</strong> {@link #add} recomputes its next code against a
 * fresh read whenever a concurrent {@code bc_add} claims the same {@code BC-N} first, via
 * {@link CodeAssignment#createRetryingOnCodeCollision}; the race is invisible to a well-formed
 * caller. Parallel sessions of one user against one local store are the normal case, not a remote/
 * multi-writer concern (ADR-001).</p>
 */
public class BoundedContextService implements AddBoundedContext, ListBoundedContexts,
        GetBoundedContext, LinkTerm {

    private static final String CODE_PREFIX = "BC";

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
    public BoundedContext add(WorkspaceId workspaceId, NewBoundedContext command) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(command, "command");
        // Identity is opaque and stable, so it is minted once, outside the retry: only the
        // business code is recomputed when a concurrent bc_add claims the same candidate first
        // (issue #144). See CodeAssignment for why that race exists and why it must retry rather
        // than surface the out-adapter's uniqueness guard as a caller-visible failure.
        BoundedContextId id = new BoundedContextId(resourceIdFactory.newId());
        return CodeAssignment.createRetryingOnCodeCollision(
                DuplicateBoundedContextCodeException.class, () -> {
                    BoundedContextCode code = nextCode(workspaceId);
                    BoundedContext boundedContext = new BoundedContext(id, code, command.name(),
                            command.domainVision(), command.subdomain(), command.ownedBy(), List.of());
                    repository.create(workspaceId, boundedContext);
                    return boundedContext;
                });
    }

    @Override
    public List<BoundedContext> list(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return repository.findAll(workspaceId);
    }

    @Override
    public Optional<BoundedContext> get(WorkspaceId workspaceId, BoundedContextCode code) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(code, "code");
        return repository.findByCode(workspaceId, code);
    }

    @Override
    public BoundedContext linkTerm(WorkspaceId workspaceId, BoundedContextCode code, String termCode) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(termCode, "termCode");
        // Resolution happens before the read-modify-write: an unknown/ambiguous term code must
        // propagate as a didactic rejection and leave the bounded context untouched.
        TermRef term = new TermRef(termLookup.resolveByCode(workspaceId, termCode));
        BoundedContext current = repository.findByCode(workspaceId, code)
                .orElseThrow(() -> new BoundedContextNotFoundException(workspaceId, code));
        if (current.usesTerms().contains(term)) {
            return current;
        }
        List<TermRef> linked = new ArrayList<>(current.usesTerms());
        linked.add(term);
        BoundedContext updated = new BoundedContext(current.id(), current.code(), current.name(),
                current.domainVision(), current.subdomain(), current.ownedBy(), linked);
        repository.update(workspaceId, updated);
        return updated;
    }

    /**
     * Derives the next free business code in {@code workspaceId}: the highest running number
     * currently in use, plus one (starting at 1).
     */
    private BoundedContextCode nextCode(WorkspaceId workspaceId) {
        int next = repository.findAll(workspaceId).stream()
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
