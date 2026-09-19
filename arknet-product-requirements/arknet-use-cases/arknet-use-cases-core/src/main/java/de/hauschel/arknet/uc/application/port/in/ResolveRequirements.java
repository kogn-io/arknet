// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.pr.shared.RequirementCode;

/**
 * Driving port: batch-resolves the opaque requirement identities a use case's steps realise back
 * to the business codes a human reads.
 *
 * <p><strong>This component's own port, not a borrowed one.</strong> The use-cases component used
 * to borrow the requirements component's equivalent in-port for this, which bound its in-adapter
 * to a neighbouring core. Since ADR-49 there is one mechanism for every reading edge: the core
 * asks its own driven {@code RequirementLookup} port, and the store adapter of this very component
 * answers it by reading the neighbour's published language. The result of this in-port therefore
 * carries the foreign code itself, and no module of this component depends on a module of the
 * requirements component.</p>
 *
 * <p><strong>Never rejects.</strong> Unlike a write-time resolution, this is a batch lookup by
 * identity with no error case: an id that resolves to nothing in the project is simply absent from
 * the result. The caller - not this port - decides whether "missing" means "fall back to something
 * else" or is itself an error.</p>
 */
public interface ResolveRequirements {

    /**
     * Resolves {@code ids} to the {@link ResolvedRequirement}s they currently identify within
     * {@code projectId}, in a single batch (one store round-trip, not one per id).
     *
     * @param projectId the project (architecture model) to resolve requirements in
     * @param ids       the opaque identities to resolve; may be empty
     * @return the resolved requirements found; an id absent from the project is simply absent here
     *         too, never {@code null}
     */
    List<ResolvedRequirement> resolveRequirements(ProjectId projectId, ResourceId... ids);

    /**
     * The slim projection this port resolves an identity to: just enough for a caller to render a
     * realised requirement's business code.
     *
     * @param id   the resolved subject identity
     * @param code the resolved business code (e.g. {@code FR-1})
     */
    record ResolvedRequirement(ResourceId id, RequirementCode code) {
    }
}
