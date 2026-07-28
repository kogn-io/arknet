// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import java.util.List;

import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: register a new requirement.
 *
 * <p>Backs the MVP tool {@code req_add}. Identity assignment
 * ({@code FR-N}/{@code NFR-N}) and initial status are policy of the
 * implementing application service.</p>
 */
public interface AddRequirement {

    /**
     * Adds a new requirement.
     *
     * @param projectId the workspace (architecture model) to add the requirement to
     * @param command     the data describing the requirement to create
     * @return the persisted requirement including its assigned identity
     */
    Requirement add(ProjectId projectId, NewRequirement command);

    /**
     * Input data for {@link #add(ProjectId, NewRequirement)}.
     *
     * @param title           short human-readable summary
     * @param description     the normative statement ("The system shall ...")
     * @param type            functional vs. non-functional classification
     * @param priority        MoSCoW priority; optional (may be {@code null})
     * @param motivatedBy     IRI of the motivating {@code arkreq:Goal}; optional (may be
     *                        {@code null})
     * @param qualityCategory free-text quality category; optional (may be {@code null}),
     *                        only meaningful for {@link RequirementType#NON_FUNCTIONAL}
     * @param acceptanceCriteria the testable "Done when ..." criteria; required, at least one
     *                        entry
     */
    record NewRequirement(
            String title,
            String description,
            RequirementType type,
            Priority priority,
            String motivatedBy,
            String qualityCategory,
            List<String> acceptanceCriteria) {
    }
}
