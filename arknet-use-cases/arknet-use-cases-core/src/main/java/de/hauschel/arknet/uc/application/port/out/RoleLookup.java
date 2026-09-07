// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.out;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driven port: resolves a role's human-typed business code to its opaque subject identity in the
 * shared project store.
 *
 * <p>This is the strict cross-BC reference resolution the use-cases component needs for
 * {@code arkreq:primaryRole}/{@code arkreq:supportingRole} (ADR-37/kogn-io/arknet#405 Part C),
 * the use-cases analogue of requirements' own equivalent: the use-cases component must not
 * depend on {@code arknet-actor-core}, so it cannot look a role up as a domain object - it can
 * only ask the shared store, through this port, which resource a code currently names.</p>
 *
 * <p><strong>Resolved by code, not by name - unlike this port's predecessor,
 * {@code ActorLookup}.</strong> A role's {@code name} is language-tagged (issue #405 Part B),
 * unlike an actor's untagged {@code arknet:name}: a lexical-form match against a language-tagged
 * literal would be ambiguous the moment a role carries more than one language variant, so the
 * reference must instead go via the role's stable, single-valued {@code dcterms:identifier}
 * (e.g. {@code ROLE-4}), the same key {@code filledBy} already resolves an occupant actor by
 * (its {@code ACTOR-N} code) and {@code TermLookup}/{@code RequirementLookup} already use for
 * their own referenced resources.</p>
 *
 * <p><strong>The type check the SHACL shape cannot do.</strong> {@code arkreq:primaryRole}'s
 * {@code sh:class arkproc:Role} constraint is asserted by the out-adapter as validation-only
 * context for whichever identity this port resolves - the shape trusts that assertion rather
 * than verifying it against the store, so <em>this</em> lookup is where a use case actually gets
 * rejected for naming an {@code ACTOR-N} code (or any other non-role resource) instead of a
 * {@code ROLE-N} one; a resolved identity that is not really an {@code arkproc:Role} would
 * otherwise reach the out-adapter unchecked.</p>
 *
 * <p>Called once, at the moment a use case is written - not on every subsequent read. An
 * implementation rejects an unknown or wrongly-typed code with a runtime exception rather than
 * returning an empty or default result; callers are meant to let that exception propagate as a
 * didactic rejection of the write, not to handle a missing role as a normal case.</p>
 */
public interface RoleLookup {

    /**
     * Resolves {@code roleCode} to the identity of the role it currently names within
     * {@code projectId}.
     *
     * @param projectId the project (architecture model) to resolve the code in
     * @param roleCode  the role's human-readable business code, e.g. {@code ROLE-4}
     * @return the resolved role's opaque subject identity
     * @throws RuntimeException if {@code roleCode} is unknown within {@code projectId}, or names
     *                          a resource that is not an {@code arkproc:Role} (e.g. an
     *                          {@code ACTOR-N} code). The concrete signal type is deliberately not
     *                          fixed by this port: a real implementation's {@code
     *                          UnresolvedReferenceException} lives in {@code
     *                          arknet-persistence-support}, a module {@code arknet-use-cases-core}
     *                          must not depend on.
     */
    ResourceId resolveByCode(ProjectId projectId, String roleCode);
}
