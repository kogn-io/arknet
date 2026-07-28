// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.out;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driven port: resolves a requirement's human-typed business code to its opaque subject identity
 * in the shared workspace store.
 *
 * <p>This is the strict cross-BC reference resolution the use-cases component needs for
 * {@code arkreq:stepRealises} (issue #89, the use-cases analogue of requirements' #77): the
 * use-cases component must not depend on {@code arknet-requirements-core}, so it cannot look a
 * requirement up as a domain object - it can only ask the shared store, through this port, which
 * resource a code currently names. Resolution goes via the requirement's
 * {@code dcterms:identifier}, so a link survives relabelling the requirement.</p>
 *
 * <p>Called once, at the moment a use-case step is written - not on every subsequent read.
 * An implementation rejects an unknown or ambiguous code with a runtime exception rather than
 * returning an empty or default result; callers are meant to let that exception propagate as a
 * didactic rejection of the write, not to handle a missing requirement as a normal case.</p>
 */
public interface RequirementLookup {

    /**
     * Resolves {@code requirementCode} to the identity of the requirement it currently names
     * within {@code projectId}.
     *
     * @param projectId      the workspace (architecture model) to resolve the code in
     * @param requirementCode  the requirement's human-readable business code, e.g. {@code FR-5}
     * @return the resolved requirement's opaque subject identity
     */
    ResourceId resolveByCode(ProjectId projectId, String requirementCode);
}
