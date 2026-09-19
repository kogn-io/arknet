// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.pr.shared.ConstraintCode;

/**
 * Driving port: batch-resolves the opaque constraint identities a use case is bound by back to the
 * business codes a human reads.
 *
 * <p>Mirrors {@link ResolveRequirements} exactly, for the same reason and with the same mechanism:
 * this component's core asks its own driven {@code ConstraintLookup} port, whose store adapter
 * reads the neighbouring component's published language (ADR-49). No module of this component
 * depends on a module of the requirements component.</p>
 *
 * <p><strong>Never rejects.</strong> An id that resolves to nothing in the project is simply
 * absent from the result.</p>
 */
public interface ResolveConstraints {

    /**
     * Resolves {@code ids} to the {@link ResolvedConstraint}s they currently identify within
     * {@code projectId}, in a single batch (one store round-trip, not one per id).
     *
     * @param projectId the project (architecture model) to resolve constraints in
     * @param ids       the opaque identities to resolve; may be empty
     * @return the resolved constraints found; an id absent from the project is simply absent here
     *         too, never {@code null}
     */
    List<ResolvedConstraint> resolveConstraints(ProjectId projectId, ResourceId... ids);

    /**
     * The slim projection this port resolves an identity to: just enough for a caller to render a
     * linked constraint's business code.
     *
     * @param id   the resolved subject identity
     * @param code the resolved business code (e.g. {@code TCON-1})
     */
    record ResolvedConstraint(ResourceId id, ConstraintCode code) {
    }
}
