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
     * @param projectId       the project (architecture model) to add the requirement to
     * @param command         the data describing the requirement to create
     * @param defaultLanguage the target project's configured default language (see
     *                        {@link de.hauschel.arknet.kernel.ResolvedProject#defaultLanguage()}),
     *                        or {@code null} if it has none - the tag {@code title}/
     *                        {@code description}/{@code rationale} are written under when
     *                        {@code command.language()} is {@code null} (see
     *                        {@link de.hauschel.arknet.kernel.LanguageTag#resolveWriteLanguage})
     * @return the persisted requirement including its assigned identity
     * @throws de.hauschel.arknet.kernel.MissingDefaultLanguageException if {@code
     *                        command.language()} and {@code defaultLanguage} are both {@code null}
     */
    Requirement add(ProjectId projectId, NewRequirement command, String defaultLanguage);

    /**
     * Input data for {@link #add(ProjectId, NewRequirement, String)}.
     *
     * @param title           short human-readable summary
     * @param description     the normative statement ("The system shall ...")
     * @param rationale       why this requirement exists (issue #321); optional (may be
     *                        {@code null}). Deliberately not mandatory: {@code acceptanceCriteria}
     *                        is already a required field, and a second one would raise the bar for
     *                        registering a requirement more than a recorded reason is worth
     * @param type            functional vs. non-functional classification
     * @param priority        MoSCoW priority; optional (may be {@code null})
     * @param motivatedBy     IRI of the motivating {@code arkreq:Goal}; optional (may be
     *                        {@code null})
     * @param qualityCategory free-text quality category; optional (may be {@code null}),
     *                        only meaningful for {@link RequirementType#NON_FUNCTIONAL}
     * @param acceptanceCriteria the testable "Done when ..." criteria, as plain texts; required,
     *                        at least one entry - assigned positions {@code 1..n} in list order by
     *                        the implementing service (issue #266; a fresh requirement has no
     *                        caller-addressable position yet, unlike {@code req_update}'s
     *                        position-addressed corrections)
     * @param language        the BCP-47 language tag {@code title}/{@code description}/a
     *                        non-{@code null} {@code rationale} are written in (e.g. {@code "de"}),
     *                        or {@code null} to fall back to the target project's configured
     *                        default language - the same tag applies to all of them, since a
     *                        requirement is normally elicited in one language at a time
     */
    record NewRequirement(
            String title,
            String description,
            String rationale,
            RequirementType type,
            Priority priority,
            String motivatedBy,
            String qualityCategory,
            List<String> acceptanceCriteria,
            String language) {
    }
}
