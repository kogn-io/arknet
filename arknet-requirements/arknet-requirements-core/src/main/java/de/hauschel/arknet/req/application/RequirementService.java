package de.hauschel.arknet.req.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.req.application.port.in.AddRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirementSchema;
import de.hauschel.arknet.req.application.port.in.LinkTerm;
import de.hauschel.arknet.req.application.port.in.ListRequirements;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.in.SetRequirementStatus;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RequirementSchemaSource;
import de.hauschel.arknet.req.application.port.out.TermLookup;
import de.hauschel.arknet.req.domain.DuplicateRequirementCodeException;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementConcurrentlyModifiedException;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementNotFoundException;
import de.hauschel.arknet.req.domain.RequirementSchemaTerm;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.TermRef;

/**
 * Application service implementing the requirement use cases.
 *
 * <p>This is the policy seat of the hexagon: it drives the {@link RequirementRepository}
 * driven port. The component is wired as a plain object (constructor injection) by the
 * composition root; there are deliberately no framework annotations here.</p>
 *
 * <p><strong>Policy.</strong> Identity ({@link RequirementId}) is opaque and minted once per
 * requirement via {@link ResourceIdFactory}; it never changes. The human-readable business code
 * ({@link RequirementCode}, {@code FR-N}/{@code NFR-N}) is assigned independently, where
 * {@code N} is one above the highest running number currently used by that type in the target
 * workspace (numbering is independent per type and per workspace). New requirements start
 * {@link RequirementStatus#PROPOSED}. The only advancing status transition is
 * {@code PROPOSED -> ACCEPTED}; setting the status a requirement already has is a no-op, and
 * reverting an accepted requirement is rejected. Linking a glossary term is idempotent and
 * independent of the status lifecycle - terms may be linked to a requirement in any status.</p>
 *
 * <p><strong>Concurrency (issue #108).</strong> {@link #add} retries its next-code computation
 * against a fresh read whenever a concurrent caller claims the same code first, and {@link
 * #setStatus}/{@link #linkTerm} retry their whole read-modify-write round trip via {@link
 * RequirementRepository#compareAndUpdate} whenever a concurrent writer commits in between - see
 * {@link #updateWithOptimisticRetry}. Neither race is visible to a well-formed caller; only
 * sustained, pathological contention on the very same requirement surfaces as {@link
 * RequirementConcurrentlyModifiedException}.</p>
 */
public class RequirementService implements AddRequirement, ListRequirements, GetRequirement,
        SetRequirementStatus, LinkTerm, ResolveRequirements, GetRequirementSchema {

    /**
     * Bound on {@link #add}'s and {@link #updateWithOptimisticRetry}'s retry loops (issue #108).
     * Both races this guards against - two callers computing the same next-free {@link
     * RequirementCode}, or two callers read-modify-writing the same requirement - are resolved by
     * a single retry in the overwhelming majority of cases, since each retry re-reads the
     * now-current state before trying again; this bound only exists so a pathological, sustained
     * storm of concurrent writers against the very same requirement fails loudly instead of
     * looping forever.
     */
    private static final int MAX_RETRY_ATTEMPTS = 20;

    private final RequirementRepository repository;
    private final ResourceIdFactory resourceIdFactory;
    private final TermLookup termLookup;
    private final RequirementSchemaSource schemaSource;

    /**
     * Creates the service.
     *
     * @param repository        the driven persistence port (must not be {@code null})
     * @param resourceIdFactory mints the opaque identity of a newly added requirement (must not
     *                          be {@code null})
     * @param termLookup        resolves a human-typed glossary term code to its opaque identity
     *                          (must not be {@code null})
     * @param schemaSource      supplies the {@code arkreq:} vocabulary as data, backing
     *                          {@code req_schema} (must not be {@code null})
     */
    public RequirementService(
            RequirementRepository repository, ResourceIdFactory resourceIdFactory, TermLookup termLookup,
            RequirementSchemaSource schemaSource) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        this.termLookup = Objects.requireNonNull(termLookup, "termLookup");
        this.schemaSource = Objects.requireNonNull(schemaSource, "schemaSource");
    }

    @Override
    public Requirement add(WorkspaceId workspaceId, NewRequirement command) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(command, "command");
        // Identity is opaque and stable, so it is minted once, outside the retry: only the
        // business code is recomputed when a concurrent add() claims the same candidate first
        // (issue #108, generalised to all four bounded contexts in issue #144). nextCode() reads
        // the highest running number client-side, before create()'s own in-transaction uniqueness
        // check, so two concurrent req_add calls for the same type can legitimately compute the
        // same candidate code; CodeAssignment turns that race into an invisible, automatic retry
        // instead of surfacing the out-adapter's guard as a caller-visible failure.
        RequirementId id = new RequirementId(resourceIdFactory.newId());
        return CodeAssignment.createRetryingOnCodeCollision(MAX_RETRY_ATTEMPTS,
                DuplicateRequirementCodeException.class, () -> {
                    RequirementCode code = nextCode(workspaceId, command.type());
                    Requirement requirement = new Requirement(id, code, command.title(),
                            command.description(), command.type(), RequirementStatus.PROPOSED,
                            command.priority(), command.motivatedBy(), command.qualityCategory(),
                            List.of(), command.acceptanceCriteria());
                    repository.create(workspaceId, requirement);
                    return requirement;
                });
    }

    @Override
    public List<Requirement> list(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return repository.findAll(workspaceId);
    }

    @Override
    public Optional<Requirement> get(WorkspaceId workspaceId, RequirementCode code) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(code, "code");
        return repository.findByCode(workspaceId, code);
    }

    @Override
    public Requirement setStatus(WorkspaceId workspaceId, RequirementCode code, RequirementStatus status) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(status, "status");
        return updateWithOptimisticRetry(workspaceId, code, current -> {
            if (current.status() == status) {
                return current;
            }
            requireLegalTransition(current.status(), status);
            return new Requirement(current.id(), current.code(), current.title(),
                    current.description(), current.type(), status, current.priority(), current.motivatedBy(),
                    current.qualityCategory(), current.usesTerms(), current.acceptanceCriteria());
        });
    }

    @Override
    public Requirement linkTerm(WorkspaceId workspaceId, RequirementCode code, String termCode) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(termCode, "termCode");
        // Resolution does not depend on the requirement's current state, so it happens once,
        // outside the retry loop below - a lookup failure must propagate immediately and leave
        // the requirement untouched, exactly as before.
        TermRef term = new TermRef(termLookup.resolveByCode(workspaceId, termCode));
        return updateWithOptimisticRetry(workspaceId, code, current -> {
            if (current.usesTerms().contains(term)) {
                return current;
            }
            List<TermRef> linked = new ArrayList<>(current.usesTerms());
            linked.add(term);
            return new Requirement(current.id(), current.code(), current.title(),
                    current.description(), current.type(), current.status(), current.priority(),
                    current.motivatedBy(), current.qualityCategory(), linked, current.acceptanceCriteria());
        });
    }

    /**
     * Read-modify-write helper shared by {@link #setStatus} and {@link #linkTerm}: reads the
     * current requirement, derives the next state via {@code mutation}, and writes it back via
     * {@link RequirementRepository#compareAndUpdate} - retrying with a fresh read whenever a
     * concurrent writer commits a change in between (issue #108, Befund 1: two parallel
     * read-modify-write round trips on the same requirement used to silently lose whichever one
     * committed last via plain {@link RequirementRepository#update}).
     *
     * <p>{@code mutation} returning its input unchanged (by {@link Object#equals}) is treated as
     * a no-op: the existing idempotency rules ({@code setStatus} to the same status, linking an
     * already-linked term) skip the write entirely, exactly as before this fix.</p>
     *
     * @throws RequirementNotFoundException            if no requirement with {@code code} exists
     * @throws RequirementConcurrentlyModifiedException if the write keeps losing the race across
     *                                                   every retry attempt
     */
    private Requirement updateWithOptimisticRetry(
            WorkspaceId workspaceId, RequirementCode code, UnaryOperator<Requirement> mutation) {
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            Requirement current = repository.findByCode(workspaceId, code)
                    .orElseThrow(() -> new RequirementNotFoundException(workspaceId, code));
            Requirement updated = mutation.apply(current);
            if (updated.equals(current)) {
                return current;
            }
            if (repository.compareAndUpdate(workspaceId, current, updated)) {
                return updated;
            }
            // A concurrent writer replaced the requirement between our read and our write -
            // retry against the now-current state instead of silently discarding that change.
        }
        throw new RequirementConcurrentlyModifiedException(workspaceId, code);
    }

    @Override
    public List<ResolvedRequirement> getById(WorkspaceId workspaceId, ResourceId... ids) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(ids, "ids");
        if (ids.length == 0) {
            return List.of();
        }
        return repository.findByIds(workspaceId, List.of(ids));
    }

    @Override
    public List<RequirementSchemaTerm> schema() {
        return schemaSource.schema();
    }

    /**
     * Derives the next free business code for {@code type} in {@code workspaceId}: the highest
     * running number currently used by that type, plus one (starting at 1).
     */
    private RequirementCode nextCode(WorkspaceId workspaceId, RequirementType type) {
        int next = repository.findAll(workspaceId).stream()
                .filter(r -> r.type() == type)
                .mapToInt(r -> runningNumber(r.code()))
                .max()
                .orElse(0) + 1;
        return new RequirementCode(type.idPrefix() + "-" + next);
    }

    /** Parses the running number from a code such as {@code FR-7} (0 if not parseable). */
    private static int runningNumber(RequirementCode code) {
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

    private static void requireLegalTransition(RequirementStatus from, RequirementStatus to) {
        boolean legal = from == RequirementStatus.PROPOSED && to == RequirementStatus.ACCEPTED;
        if (!legal) {
            throw new IllegalStateException(
                    "illegal status transition " + from + " -> " + to);
        }
    }
}
