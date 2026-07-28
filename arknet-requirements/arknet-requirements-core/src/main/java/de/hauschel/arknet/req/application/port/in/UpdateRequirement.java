// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;

/**
 * Driving port: correct the title, description, acceptance criteria and/or MoSCoW priority of an
 * already-created requirement.
 *
 * <p>Backs the MVP tool {@code req_update} (issue #162). Requirements elicited during an
 * interview are sometimes sharpened afterwards - e.g. a domain fact only surfaces once the
 * conversation continues - and until this port existed there was no way to correct a requirement
 * already in the store short of duplicating it under a new code. Unlike {@code req_add}'s
 * required arguments, every field here is optional: {@code null} leaves that field unchanged, so
 * a caller can correct only the description without having to restate the title.</p>
 *
 * <p><strong>Priority (issue #170).</strong> {@code req_add} accepted a {@link Priority} from the
 * start, but nothing could change it afterwards: {@code req_set_status} only covers {@code
 * RequirementStatus}, so a whole register mis-prioritised as {@code MUST_HAVE} could only be
 * corrected by re-creating every requirement - losing its code and every {@code usesTerm} /
 * {@code realises} reference pointing at it. That is why {@code priority} joined this port's
 * optional fields.</p>
 *
 * <p><strong>Deliberately interim.</strong> The {@code priority} parameter is throw-away: issue
 * #169's generic {@code resource_update} facade (ADR-014 phase 3) is meant to set fields
 * generically and to absorb the growing pile of per-bounded-context update tools this parameter
 * adds to. It exists because the mis-prioritised register needed a correction path before that
 * facade lands - not because widening this signature per field is the intended direction. Once
 * the facade exists, this parameter goes, not the other way round.</p>
 *
 * <p><strong>No clearing.</strong> A {@code null} {@code priority} leaves an already-set one
 * untouched - it is not a "remove the priority" signal, since {@code null} is already the
 * sentinel for every other field here. Un-setting a priority once set is out of scope (no caller
 * need has surfaced; the concrete one was {@code MUST_HAVE} to {@code SHOULD_HAVE}), and would
 * need a distinct signal rather than overloading {@code null} - the same rule the sibling
 * {@code UpdateTerm} port applies to its Actor facette.</p>
 */
public interface UpdateRequirement {

    /**
     * Updates the requirement identified by {@code code} within a project, leaving any
     * {@code null} argument unchanged.
     *
     * @param projectId         the project (architecture model) the requirement lives in
     * @param code                the requirement code, e.g. {@code FR-1}
     * @param title               the new title, or {@code null} to leave it unchanged
     * @param description         the new normative statement, or {@code null} to leave it unchanged
     * @param acceptanceCriteria  the new "Done when ..." criteria, or {@code null} to leave them
     *                            unchanged
     * @param priority            the new MoSCoW priority, or {@code null} to leave an already-set
     *                            one unchanged (never a request to remove it)
     * @return the updated requirement
     */
    Requirement update(ProjectId projectId, RequirementCode code, String title, String description,
            List<String> acceptanceCriteria, Priority priority);
}
