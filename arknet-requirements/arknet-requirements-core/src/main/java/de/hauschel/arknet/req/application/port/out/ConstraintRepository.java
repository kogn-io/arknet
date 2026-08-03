// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.out;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.DuplicateConstraintCodeException;
import de.hauschel.arknet.req.domain.ResourceAlreadyExistsException;

/**
 * Driven port: persistence capability the component needs for {@link Constraint}s.
 *
 * <p>Named after the capability, not after any technology - mirrors {@link RequirementRepository}
 * in spirit, but far narrower: a {@link Constraint} is immutable once created in this scope (no
 * {@code constraint_update}/{@code constraint_set_status} tool exists, and the ontology gives it
 * no status field), so this port has no compare-and-set update path and needs no
 * {@link RevisionToken} of its own. {@link #create} still runs through the shared
 * {@link de.hauschel.arknet.persistence.WriteFunnel}, so a constraint still gets a PROV-O revision
 * and an {@code arkprov:head} recorded - there is simply no second write to guard with a CAS
 * check.</p>
 *
 * <p>The {@link ProjectId} routing key identifies which architecture model a constraint belongs
 * to, exactly as it does for {@link RequirementRepository}.</p>
 */
public interface ConstraintRepository {

    /**
     * Persists a brand-new constraint whose identity does not yet exist in the project.
     *
     * @param projectId  the project (architecture model) to store the constraint in
     * @param constraint the constraint to create
     * @throws ResourceAlreadyExistsException   if a constraint with this identity already exists
     * @throws DuplicateConstraintCodeException if another constraint already carries this
     *                                            constraint's {@link ConstraintCode} - identity
     *                                            collision and business-label collision are
     *                                            distinct failure modes, exactly as for
     *                                            {@link RequirementRepository#create}
     * @throws RuntimeException if {@code constraint} violates a SHACL write constraint. The
     *                          concrete signal type is deliberately not fixed by this port: a
     *                          real implementation's {@code WriteConstraintViolationException}
     *                          lives in {@code arknet-persistence-support}, a module
     *                          {@code arknet-requirements-core} must not depend on.
     */
    void create(ProjectId projectId, Constraint constraint);

    /**
     * Finds a constraint by its human-readable business code within a project.
     *
     * @param projectId the project (architecture model) to look up the constraint in
     * @param code        the constraint code (e.g. {@code TCON-1})
     * @return the constraint if present, otherwise {@link Optional#empty()}
     */
    Optional<Constraint> findByCode(ProjectId projectId, ConstraintCode code);

    /**
     * Returns all constraints stored in a project.
     *
     * @param projectId the project (architecture model) to list constraints from
     * @return all constraints, never {@code null}
     */
    List<Constraint> findAll(ProjectId projectId);

    /**
     * Finds every constraint in a project whose identity is among {@code ids}, in one store
     * round-trip - backs {@link ResolveConstraints}. This is a batch lookup, not a per-id
     * existence check: an id absent from the project is simply absent from the result, never an
     * error.
     *
     * @param projectId the project (architecture model) to look up constraints in
     * @param ids         the opaque identities to resolve; an empty list yields an empty result
     * @return the resolved constraints found, in no particular order, never {@code null}
     */
    List<ResolveConstraints.ResolvedConstraint> findByIds(ProjectId projectId, List<ResourceId> ids);
}
