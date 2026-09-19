// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.in;

import java.util.List;

import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * Driving port: batch-resolves opaque bounded-context identities back to their identity and business
 * code.
 *
 * <p>The bounded-context component owns the bounded-context lifecycle, so it - not a caller reading
 * the store directly - is who answers "what does this identity currently name?" This exists for a
 * <em>sibling</em> bounded context's driving (In-) adapter to consume, the same pattern
 * {@code ResolveTerms} (ubiquitous-language) and {@code ResolveRequirements} (requirements) already
 * establish: an In-Adapter is the gate into its own hexagon, not part of its core, so it may call
 * another hexagon's driving port without breaking the "no {@code *-core} depends on another bounded
 * context" invariant, which binds the {@code *-core} modules, not the adapters around them.
 * The ADR MCP adapter uses this to render an affected context's business code
 * ({@code BC-1}) instead of its bare subject IRI, without the ADR bounded context ever depending on
 * {@code arknet-bounded-context-core}.</p>
 *
 * <p><strong>Never rejects.</strong> Unlike {@link GetBoundedContext} (single lookup by code, empty
 * if absent) this is a batch lookup by identity with no error case: an id that resolves to nothing in
 * the project is simply absent from the result. The caller - not this port - decides whether
 * "missing" means "fall back to something else" or is itself an error.</p>
 */
public interface ResolveBoundedContexts {

    /**
     * Resolves {@code ids} to the {@link ResolvedBoundedContext}s they currently identify within
     * {@code projectId}, in a single batch (one store round-trip, not one per id).
     *
     * @param projectId the project (architecture model) to resolve bounded contexts in
     * @param ids       the opaque identities to resolve; may be empty
     * @return the resolved bounded contexts found; an id absent from the project is simply absent
     *         here too, never {@code null}
     */
    List<ResolvedBoundedContext> resolveExisting(ProjectId projectId, ResourceId... ids);

    /**
     * The slim projection this port resolves an identity to: just enough for a caller to render a
     * referenced context's business code - not the full {@link BoundedContext} aggregate, which
     * would force every backing query to join fields (e.g. {@code name}, {@code domainVision}) a
     * display-only caller never reads.
     *
     * @param id   the resolved subject identity
     * @param code the resolved business code (e.g. {@code BC-1})
     */
    record ResolvedBoundedContext(ResourceId id, BoundedContextCode code) {
    }
}
