// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementNotFoundException;
import de.hauschel.arknet.req.domain.RequirementReferencedException;

/**
 * Driving port: removes a requirement and its triples from the project entirely
 * (kogn-io/arknet#566), mirroring {@link DeleteConstraint} exactly.
 *
 * <p>Unlike {@link UpdateRequirement}, there is no field-level correction here - the whole
 * resource goes away, including the {@code arkreq:AcceptanceCriterion} resources hanging off it,
 * which have no identity of their own and no reader once their requirement is gone.</p>
 *
 * <p><strong>The status does not gate this.</strong> An {@code ACCEPTED} requirement is as
 * deletable as a {@code PROPOSED} one, exactly as with {@link DeleteConstraint} and deliberately
 * unlike {@code adr_delete}: a requirement is not a decision but a promise, and a promise changes.
 * The occasion this exists for is the accepted duplicate. A requirement that actually carries
 * something is locked by that anyway - a decision addresses it, a use case satisfies it, a step
 * realises it - see {@link RequirementReferencedException}. What stays deletable is an accepted
 * requirement nothing points at: a promise that never arrived anywhere.</p>
 */
public interface DeleteRequirement {

    /**
     * Deletes the requirement identified by {@code code} from {@code projectId}, together with its
     * acceptance criteria.
     *
     * @param projectId the project (architecture model) the requirement lives in
     * @param code      the requirement code, e.g. {@code FR-1} or {@code NFR-3}
     * @throws RequirementNotFoundException   if no requirement with this identity exists
     * @throws RequirementReferencedException if a decision, use case, use-case step or another
     *                                        requirement still references the requirement
     */
    void delete(ProjectId projectId, RequirementCode code);
}
