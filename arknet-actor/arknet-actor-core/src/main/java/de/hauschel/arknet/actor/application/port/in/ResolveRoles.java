// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.in;

import java.util.List;

import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * Driving port: batch-resolves opaque role identities back to their identity, business code and
 * display name.
 *
 * <p>The actor bounded context owns the role register, so it - not a caller reading the store
 * directly - is who answers "what does this identity currently name?" This exists for a
 * <em>sibling</em> bounded context's driving (In-) adapter to consume, the same pattern
 * {@code ResolveRequirements}/{@code ResolveTerms} already establish: an In-Adapter is the gate
 * into its own hexagon, not part of its core, so it may call another hexagon's driving port
 * without breaking the "no {@code *-core} depends on another bounded context" invariant, which
 * binds the {@code *-core} modules, not the adapters around them. Since ADR-37/kogn-io/arknet#405
 * Part C, {@code arknet-use-cases}' driving adapters use this to render a use case's
 * {@code primaryRole}/{@code supportingRoles} as their business code ({@code ROLE-1}) and display
 * name, instead of their bare subject IRI - replacing this same bounded context's now-deleted
 * {@code ResolveActors}, which the use-cases MCP adapter resolved {@code primaryActor}/
 * {@code supportingActors} against before {@code arkreq:primaryRole}/{@code supportingRole}
 * repointed those edges at {@code arkproc:Role} instead of {@code arkproc:Actor}.</p>
 *
 * <p><strong>Carries a resolved name, unlike {@code ResolveActors} did.</strong> A role's
 * {@code name} is language-tagged (see {@link de.hauschel.arknet.actor.domain.Role}'s own javadoc
 * for why this hexagon's two resource types disagree on that), so a caller needs it resolved under
 * a {@code displayLocale} the same way {@link de.hauschel.arknet.actor.application.port.out.
 * RoleRepository#findByCode} already accepts one - a bare {@code ROLE-1} would otherwise force
 * every renderer to look the name up a second time. {@code ResolveActors.ResolvedActor} never
 * carried a name because an actor's own name is untagged, and its one caller
 * ({@code UseCasePresenter}) rendered only the code.</p>
 *
 * <p><strong>Never rejects.</strong> Unlike {@code GetRole} (single lookup by code, empty if
 * absent) this is a batch lookup by identity with no error case: an id that resolves to nothing
 * in the project is simply absent from the result. The caller - not this port - decides whether
 * "missing" means "fall back to something else" or is itself an error.</p>
 */
public interface ResolveRoles {

    /**
     * Resolves {@code ids} to the {@link ResolvedRole}s they currently identify within
     * {@code projectId}, in a single batch (one store round-trip, not one per id).
     *
     * @param projectId     the project (architecture model) to resolve roles in
     * @param displayLocale the BCP-47 language tag the caller wants each role's resolved
     *                      {@code name} shown in, overriding this hexagon's own configured
     *                      display-language preference for this one call, or {@code null} to use
     *                      that preference unchanged - the same override
     *                      {@code RoleRepository#findByCode} accepts
     * @param ids           the opaque identities to resolve; may be empty
     * @return the resolved roles found; an id absent from the project is simply absent here too,
     *         never {@code null}
     */
    List<ResolvedRole> resolveExisting(ProjectId projectId, String displayLocale, ResourceId... ids);

    /**
     * The slim projection this port resolves an identity to: just enough for a caller to render a
     * referenced role's business code and display name - not the full {@code Role} aggregate,
     * which would force every backing query to join fields (e.g. {@code description},
     * {@code filledBy}) a display-only caller never reads.
     *
     * @param id   the resolved subject identity
     * @param code the resolved business code (e.g. {@code ROLE-1})
     * @param name the resolved {@code name}, selected under the caller's {@code displayLocale}
     *             along the usual fallback chain
     */
    record ResolvedRole(ResourceId id, RoleCode code, String name) {
    }
}
