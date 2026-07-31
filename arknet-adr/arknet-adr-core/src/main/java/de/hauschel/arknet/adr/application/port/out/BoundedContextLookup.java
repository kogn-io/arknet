// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.out;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * Driven port: resolves a bounded context's human-typed business code to its opaque subject identity
 * in the shared project store.
 *
 * <p>Structurally 1:1 to {@link RequirementLookup}, for {@code arkarch:affectsContext}: the ADR
 * component must not depend on {@code arknet-bounded-context-core}, so it asks the shared store
 * which resource a code currently names, via {@code dcterms:identifier} and never via
 * {@code arknet:name}, so a link survives renaming the context.</p>
 *
 * <p>Called once, at the moment a decision is recorded. An implementation rejects an unknown or
 * ambiguous code with a runtime exception rather than returning an empty or default result.</p>
 */
public interface BoundedContextLookup {

    /**
     * Resolves {@code boundedContextCode} to the identity of the bounded context it currently names
     * within {@code projectId}.
     *
     * @param projectId          the project (architecture model) to resolve the code in
     * @param boundedContextCode the bounded context's human-readable business code, e.g.
     *                           {@code BC-1}
     * @return the resolved bounded context's opaque subject identity
     */
    ResourceId resolveByCode(ProjectId projectId, String boundedContextCode);
}
