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
 * already-created requirement, leaving {@code status} and {@code usesTerms} to their own ports
 * ({@code req_set_status}, {@code req_link_term}).
 *
 * <p>Every field is optional: {@code null} leaves that field unchanged, so a caller can correct
 * only the description without restating the title. A non-{@code null} value must still satisfy
 * {@link Requirement}'s own invariants (non-blank, non-empty/duplicate-free criteria). A
 * {@code null} {@code priority} leaves an already-set one untouched - it is never a "remove the
 * priority" signal, since {@code null} is already the sentinel for every other field here;
 * un-setting a priority once set is out of scope, and would need a distinct signal rather than
 * overloading {@code null} (the same rule the sibling {@code UpdateTerm} port applies to its
 * Actor facette).</p>
 *
 * <p><strong>Background.</strong> Backs the MVP tool {@code req_update} (issue #162): requirements
 * elicited during an interview are sometimes sharpened afterwards, and until this port existed the
 * only correction path was duplicating the requirement under a new code. {@code priority} joined
 * the optional fields later (issue #170) because nothing else could change it once set -
 * {@code req_set_status} covers only {@code RequirementStatus} - and re-creating a requirement to
 * fix its priority loses its code and every {@code usesTerm}/{@code realises} reference. That
 * parameter is deliberately interim: issue #169's generic {@code resource_update} facade (ADR-014
 * phase 3) is meant to absorb it and the growing pile of per-bounded-context update tools it
 * belongs to; once that facade exists, this parameter goes, not the other way round.</p>
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
