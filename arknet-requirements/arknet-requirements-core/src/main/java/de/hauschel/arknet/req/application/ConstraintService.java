// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.AddConstraint;
import de.hauschel.arknet.req.application.port.in.GetConstraint;
import de.hauschel.arknet.req.application.port.in.ListConstraints;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintId;
import de.hauschel.arknet.req.domain.ConstraintType;
import de.hauschel.arknet.req.domain.DuplicateConstraintCodeException;

/**
 * Application service implementing the constraint use cases.
 *
 * <p>This is the policy seat of the constraint side of the requirements hexagon - mirrors
 * {@link RequirementService} in shape but is far simpler, because a {@link Constraint} is
 * immutable once created in this scope: there is no {@code accept}/{@code update}/{@code linkTerm}
 * equivalent here, and therefore no read-modify-write retry loop, no {@link
 * de.hauschel.arknet.req.application.port.out.RevisionToken} and no compare-and-set guard.
 * {@code req_link_constraint} - the one operation that mutates state as a consequence of a
 * constraint existing - mutates the <em>requirement</em>, not the constraint, and therefore lives
 * on {@link RequirementService} (see {@link
 * de.hauschel.arknet.req.application.port.in.LinkConstraint}), constructor-injected with the
 * same {@link ConstraintRepository} this service also depends on.</p>
 *
 * <p><strong>Policy.</strong> Identity ({@link ConstraintId}) is opaque and minted once per
 * constraint via {@link ResourceIdFactory}; it never changes. The human-readable business code
 * ({@link ConstraintCode}, {@code TCON-N}/{@code BCON-N}/{@code RCON-N}) is assigned
 * independently, where {@code N} is one above the highest running number currently used by that
 * subtype in the target project - numbering is independent per subtype and per project, exactly
 * mirroring {@link RequirementService}'s {@code FR-N}/{@code NFR-N} numbering.</p>
 *
 * <p><strong>Concurrency.</strong> {@link #add} retries its next-code computation against a
 * fresh read whenever a concurrent caller claims the same code first - the same
 * {@link CodeAssignment} TOCTOU-retry helper {@code RequirementService#add} already uses,
 * generalised across bc/ul/uc/req and now constraint.</p>
 */
public class ConstraintService implements AddConstraint, GetConstraint, ListConstraints, ResolveConstraints {

    /**
     * Bound on {@link #add}'s retry loop - see {@link RequirementService#MAX_RETRY_ATTEMPTS} for
     * the rationale, identical here.
     */
    static final int MAX_RETRY_ATTEMPTS = CodeAssignment.DEFAULT_MAX_ATTEMPTS;

    private final ConstraintRepository repository;
    private final ResourceIdFactory resourceIdFactory;

    /**
     * Creates the service.
     *
     * @param repository        the driven persistence port (must not be {@code null})
     * @param resourceIdFactory mints the opaque identity of a newly added constraint (must not
     *                          be {@code null})
     */
    public ConstraintService(ConstraintRepository repository, ResourceIdFactory resourceIdFactory) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
    }

    @Override
    public Constraint add(ProjectId projectId, NewConstraint command) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(command, "command");
        // Identity is opaque and stable, so it is minted once, outside the retry - see
        // RequirementService#add's javadoc for the full rationale, identical here.
        ConstraintId id = new ConstraintId(resourceIdFactory.newId());
        return CodeAssignment.createRetryingOnCodeCollision(MAX_RETRY_ATTEMPTS,
                DuplicateConstraintCodeException.class, () -> {
                    ConstraintCode code = nextCode(projectId, command.type());
                    Constraint constraint = new Constraint(id, code, command.title(), command.statement(),
                            command.type());
                    repository.create(projectId, constraint);
                    return constraint;
                });
    }

    @Override
    public List<Constraint> list(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return repository.findAll(projectId);
    }

    @Override
    public Optional<Constraint> get(ProjectId projectId, ConstraintCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return repository.findByCode(projectId, code);
    }

    @Override
    public List<ResolvedConstraint> resolveExisting(ProjectId projectId, ResourceId... ids) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ids, "ids");
        if (ids.length == 0) {
            return List.of();
        }
        return repository.findByIds(projectId, List.of(ids));
    }

    /**
     * Derives the next free business code for {@code type} in {@code projectId}: the highest
     * running number currently used by that subtype, plus one (starting at 1) - mirrors
     * {@code RequirementService#nextCode} exactly.
     */
    private ConstraintCode nextCode(ProjectId projectId, ConstraintType type) {
        int next = repository.findAll(projectId).stream()
                .filter(c -> c.type() == type)
                .mapToInt(c -> runningNumber(c.code()))
                .max()
                .orElse(0) + 1;
        return new ConstraintCode(type.idPrefix() + "-" + next);
    }

    /** Parses the running number from a code such as {@code TCON-7} (0 if not parseable). */
    private static int runningNumber(ConstraintCode code) {
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
