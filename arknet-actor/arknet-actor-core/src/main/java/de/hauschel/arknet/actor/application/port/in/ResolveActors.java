// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.in;

import java.util.List;

import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * Driving port: batch-resolves opaque actor identities back to their identity and business code.
 *
 * <p>The actor bounded context owns the actor register, so it - not a caller reading the store
 * directly - is who answers "what does this identity currently name?" This exists for a
 * <em>sibling</em> bounded context's driving (In-) adapter to consume, the same pattern
 * {@code ResolveRequirements}/{@code ResolveTerms} already establish: an In-Adapter is the gate
 * into its own hexagon, not part of its core, so it may call another hexagon's driving port
 * without breaking the "no {@code *-core} depends on another bounded context" invariant, which
 * binds the {@code *-core} modules, not the adapters around them (ADR-008). Since issue #336 the
 * use-cases MCP adapter uses this to render a use case's {@code primaryActor}/{@code
 * supportingActors} as their business code ({@code ACTOR-1}) instead of their bare subject IRI,
 * without the use-cases bounded context ever depending on {@code arknet-actor-core} - the
 * register {@code arkreq:primaryActor}/{@code supportingActor} used to resolve against before the
 * facet was removed from the glossary.</p>
 *
 * <p><strong>Never rejects.</strong> Unlike {@code GetActor} (single lookup by code, empty if
 * absent) this is a batch lookup by identity with no error case: an id that resolves to nothing
 * in the project is simply absent from the result. The caller - not this port - decides whether
 * "missing" means "fall back to something else" or is itself an error.</p>
 */
public interface ResolveActors {

    /**
     * Resolves {@code ids} to the {@link ResolvedActor}s they currently identify within
     * {@code projectId}, in a single batch (one store round-trip, not one per id).
     *
     * @param projectId the project (architecture model) to resolve actors in
     * @param ids       the opaque identities to resolve; may be empty
     * @return the resolved actors found; an id absent from the project is simply absent here too,
     *         never {@code null}
     */
    List<ResolvedActor> resolveExisting(ProjectId projectId, ResourceId... ids);

    /**
     * The slim projection this port resolves an identity to: just enough for a caller to render a
     * referenced actor's business code - not the full {@code Actor} aggregate, which would force
     * every backing query to join fields (e.g. {@code type}, {@code description}) a display-only
     * caller never reads.
     *
     * @param id   the resolved subject identity
     * @param code the resolved business code (e.g. {@code ACTOR-1})
     */
    record ResolvedActor(ResourceId id, ActorCode code) {
    }
}
