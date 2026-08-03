// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.ConstraintCode;

/**
 * Driving port: batch-resolves opaque constraint identities back to their identity and business
 * code.
 *
 * <p>Mirrors {@link ResolveRequirements} exactly: this exists so a caller rendering a
 * {@link de.hauschel.arknet.req.domain.Requirement}'s linked constraints (the
 * {@code oslc_rm:constrainedBy} edges, {@link de.hauschel.arknet.req.domain.ConstraintRef}) can
 * show its business code ({@code TCON-N}/{@code BCON-N}/{@code RCON-N}) instead of a bare subject
 * IRI, batched once per {@code req_get}/{@code req_list} call rather than once per reference. Since
 * {@link de.hauschel.arknet.req.domain.Constraint} lives in this same bounded context (unlike a
 * glossary {@code Term}), this port needs no {@code ResolveTerms}-style ADR-008 in-adapter borrow
 * from a sibling hexagon - {@code arknet-requirements-adapter-mcp} simply depends on this in-port
 * the same way it already depends on every other one this module exposes.</p>
 *
 * <p><strong>Never rejects.</strong> Unlike {@code GetConstraint} (single lookup by code, empty
 * if absent) this is a batch lookup by identity with no error case: an id that resolves to
 * nothing in the project is simply absent from the result.</p>
 */
public interface ResolveConstraints {

    /**
     * Resolves {@code ids} to the {@link ResolvedConstraint}s they currently identify within
     * {@code projectId}, in a single batch (one store round-trip, not one per id).
     *
     * @param projectId the project (architecture model) to resolve constraints in
     * @param ids         the opaque identities to resolve; may be empty
     * @return the resolved constraints found; an id absent from the project is simply absent
     *         here too, never {@code null}
     */
    List<ResolvedConstraint> resolveExisting(ProjectId projectId, ResourceId... ids);

    /**
     * The slim projection this port resolves an identity to: just enough for a caller to render a
     * linked constraint's business code - not the full {@code Constraint} aggregate.
     *
     * @param id   the resolved subject identity
     * @param code the resolved business code (e.g. {@code TCON-1})
     */
    record ResolvedConstraint(ResourceId id, ConstraintCode code) {
    }
}
