// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.in;

import java.util.List;

import de.hauschel.arknet.dm.shared.TermCode;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * Driving port: batch-resolves the opaque term identities a bounded context links back to the
 * business codes a human typed.
 *
 * <p><strong>Why this component answers it.</strong> {@link BoundedContextDetail} and
 * {@link GetBoundedContext} hand out a {@code BoundedContext} whose {@code usesTerms} are bare
 * identities - but a human who typed {@code TERM-1} into {@code bc_link_term} expects to see
 * {@code TERM-1} again, not an IRI they cannot re-type. Before ADR-49 the driving adapter closed
 * that gap by borrowing the glossary component's own driving port; since ADR-49 there is one
 * mechanism for every read of a neighbour, and it runs through this component: the core asks its
 * driven {@code TermLookup} port, whose store adapter reads the glossary's published language.
 * The adapter therefore stays inside its own hexagon, and no module of this component depends on
 * a module of the glossary component.</p>
 *
 * <p><strong>Never rejects.</strong> An id that names no term in the project is simply absent
 * from the result; the caller - not this port - decides what to render instead.</p>
 */
public interface ResolveLinkedTerms {

    /**
     * Resolves {@code ids} to the {@link LinkedTerm}s they currently identify within
     * {@code projectId}, in a single batch (one store round-trip, not one per id).
     *
     * @param projectId the project (architecture model) to resolve the identities in
     * @param ids       the opaque identities to resolve; may be empty
     * @return the terms found; an id naming no term in the project is simply absent here too,
     *         never {@code null}
     */
    List<LinkedTerm> resolveLinkedTerms(ProjectId projectId, ResourceId... ids);

    /**
     * The slim projection this port resolves an identity to: just enough to render a linked
     * term's business code, the same shape {@link ResolveBoundedContexts.ResolvedBoundedContext}
     * has for the opposite direction.
     *
     * @param id   the resolved subject identity
     * @param code the resolved business code (e.g. {@code TERM-1})
     */
    record LinkedTerm(ResourceId id, TermCode code) {
    }
}
