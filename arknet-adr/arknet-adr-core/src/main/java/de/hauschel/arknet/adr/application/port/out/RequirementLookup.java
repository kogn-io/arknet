// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.out;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * Driven port: resolves a requirement's human-typed business code to its opaque subject identity in
 * the shared project store.
 *
 * <p>This is the strict cross-BC reference resolution the ADR component needs for
 * {@code arkarch:addressesRequirement}: the ADR component must not depend on
 * {@code arknet-requirements-core}, so it cannot look a requirement up as a domain object - it can
 * only ask the shared store, through this port, which resource a code currently names. Resolution
 * goes via the requirement's {@code dcterms:identifier}, never its {@code dcterms:title}, so a link
 * survives retitling the requirement.</p>
 *
 * <p>Called once, at the moment a decision is recorded - not on every subsequent write of that
 * decision. An implementation rejects an unknown or ambiguous code with a runtime exception rather
 * than returning an empty or default result; callers are meant to let that exception propagate as a
 * didactic rejection of the write, not to handle a missing requirement as a normal case.</p>
 */
public interface RequirementLookup {

    /**
     * Resolves {@code requirementCode} to the identity of the requirement it currently names within
     * {@code projectId}.
     *
     * @param projectId       the project (architecture model) to resolve the code in
     * @param requirementCode the requirement's human-readable business code, e.g. {@code FR-1}
     * @return the resolved requirement's opaque subject identity
     */
    ResourceId resolveByCode(ProjectId projectId, String requirementCode);
}
