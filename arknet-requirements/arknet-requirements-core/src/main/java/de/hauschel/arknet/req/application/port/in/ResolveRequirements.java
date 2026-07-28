// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.RequirementCode;

/**
 * Driving port: batch-resolves opaque requirement identities back to their identity and business
 * code.
 *
 * <p>The requirements bounded context owns the requirement lifecycle, so it - not a caller
 * reading the store directly - is who answers "what does this identity currently name?" This
 * exists for a <em>sibling</em> bounded context's driving (In-) adapter to consume (issue #88),
 * the same pattern {@code ResolveTerms} already establishes for the ubiquitous-language bounded
 * context: an In-Adapter is the gate into its own hexagon, not part of its core, so it may call
 * another hexagon's driving port without breaking the "no {@code *-core} depends on another
 * bounded context" invariant, which binds the {@code *-core} modules, not the adapters around
 * them (ADR-008). A future use-cases MCP adapter uses this to render a linked requirement's
 * business code ({@code FR-1}/{@code NFR-1}) instead of its bare subject IRI, without the
 * use-cases bounded context ever depending on {@code arknet-requirements-core}.</p>
 *
 * <p><strong>Never rejects.</strong> Unlike {@code GetRequirement} (single lookup by code, empty
 * if absent) this is a batch lookup by identity with no error case: an id that resolves to
 * nothing in the workspace is simply absent from the result. The caller - not this port -
 * decides whether "missing" means "fall back to something else" or is itself an error.</p>
 */
public interface ResolveRequirements {

    /**
     * Resolves {@code ids} to the {@link ResolvedRequirement}s they currently identify within
     * {@code projectId}, in a single batch (one store round-trip, not one per id).
     *
     * @param projectId the workspace (architecture model) to resolve requirements in
     * @param ids         the opaque identities to resolve; may be empty
     * @return the resolved requirements found; an id absent from the workspace is simply absent
     *         here too, never {@code null}
     */
    List<ResolvedRequirement> getById(ProjectId projectId, ResourceId... ids);

    /**
     * The slim projection this port resolves an identity to: just enough for a caller to render a
     * linked requirement's business code - not the full {@code Requirement} aggregate, which
     * would force every backing query to join fields (e.g. {@code title}, {@code description}) a
     * display-only caller never reads.
     *
     * @param id   the resolved subject identity
     * @param code the resolved business code (e.g. {@code FR-1})
     */
    record ResolvedRequirement(ResourceId id, RequirementCode code) {
    }
}
