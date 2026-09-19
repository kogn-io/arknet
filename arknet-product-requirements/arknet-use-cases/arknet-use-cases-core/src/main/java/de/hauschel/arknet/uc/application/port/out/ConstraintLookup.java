// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.out;

import java.util.Map;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.pr.shared.ConstraintCode;

/**
 * Driven port: resolves a constraint's human-typed business code to its opaque subject identity
 * in the shared project store.
 *
 * <p>Backs {@code uc_link_constraint} (issue #329). {@code Constraint} is a second resource type
 * of the <em>requirements</em> bounded context (not a hexagon of its own), so - unlike the
 * sibling requirements bounded context's own {@code LinkConstraint}, where {@code
 * RequirementService} resolves a constraint code via a direct, same-module read against {@code
 * ConstraintRepository} - the use-cases component must not depend on
 * {@code arknet-requirements-core} and cannot look a constraint up as a domain object. It can
 * only ask the shared store, through this port, which resource a code currently names, the same
 * cross-BC shape {@link TermLookup} already takes for {@code arkreq:usesTerm}. Resolution goes
 * via the constraint's {@code dcterms:identifier} (e.g. {@code TCON-1}, {@code BCON-1},
 * {@code RCON-1}), never a business-typed accessor, since {@link ResourceId} is the only
 * constraint-shaped value this component's domain is allowed to know.</p>
 *
 * <p>Called once, at the moment a constraint is linked - not on every subsequent write of the use
 * case that links it. An implementation rejects an unknown or ambiguous code with a runtime
 * exception rather than returning an empty or default result; callers are meant to let that
 * exception propagate as a didactic rejection of the write, not to handle a missing constraint as
 * a normal case.</p>
 */
public interface ConstraintLookup {

    /**
     * Resolves {@code constraintCode} to the identity of the constraint it currently names within
     * {@code projectId}.
     *
     * @param projectId      the project (architecture model) to resolve the code in
     * @param constraintCode the constraint's human-readable business code, e.g. {@code TCON-1}
     * @return the resolved constraint's opaque subject identity
     * @throws RuntimeException if {@code constraintCode} is unknown or ambiguous within
     *                          {@code projectId}. The concrete signal type is deliberately not
     *                          fixed by this port: a real implementation's {@code
     *                          UnresolvedReferenceException} lives in {@code
     *                          arknet-persistence-support}, a module {@code arknet-use-cases-core}
     *                          must not depend on.
     */
    ResourceId resolveByCode(ProjectId projectId, String constraintCode);

    /**
     * Resolves {@code ids} back to the business codes the constraints they name currently carry within
     * {@code projectId}, in a single batch (one store round-trip, not one per id).
     *
     * <p>The reverse direction of {@link #resolveByCode}, and the reason this component needs no
     * port of the requirements component to render a constraint reference: what a stored identity is
     * called is read from the neighbour's published language by the same adapter that resolves a
     * code on write (ADR-49).</p>
     *
     * <p><strong>Never rejects.</strong> Unlike {@link #resolveByCode}, called once at write time
     * against a code a human typed, this is a display-time lookup by identity with no error case:
     * an id that names nothing in the project is simply absent from the result.</p>
     *
     * @param projectId the project (architecture model) to resolve the identities in
     * @param ids       the opaque subject identities to resolve; may be empty
     * @return the code of every id that currently names a constraint in {@code projectId}, keyed by
     *         that id; never {@code null}
     */
    Map<ResourceId, ConstraintCode> resolveCodes(ProjectId projectId, ResourceId... ids);
}
