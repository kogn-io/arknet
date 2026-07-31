// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import java.time.LocalDate;
import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: record a new architecture decision.
 *
 * <p>Backs the tool {@code adr_add}. Identity assignment (the opaque
 * {@link de.hauschel.arknet.adr.domain.AdrId}) and business-code assignment ({@code ADR-N}) are
 * policy of the implementing application service.</p>
 *
 * <p><strong>Coarse-grained write.</strong> A decision arrives complete, including its cross-context
 * references - the same shape {@code uc_add} uses for a use case's actors and realised requirements,
 * and for the same reason: the references are part of what makes the decision comprehensible, not a
 * later annotation. There is deliberately no separate {@code adr_link_requirement} tool; a decision
 * whose references need correcting is a decision that was not yet made.</p>
 */
public interface AddAdr {

    /**
     * Adds a new architecture decision, initially {@link de.hauschel.arknet.adr.domain.AdrStatus
     * #PROPOSED}.
     *
     * @param projectId the project (architecture model) to add the decision to
     * @param command   the data describing the decision to record
     * @return the persisted decision including its assigned identity and code
     */
    AdrDetail add(ProjectId projectId, NewAdr command);

    /**
     * Input data for {@link #add(ProjectId, NewAdr)}.
     *
     * <p>The two reference lists carry what a human types - {@code FR-1}, {@code BC-2} - never an
     * IRI. Resolving them to opaque identities, and rejecting an unknown or ambiguous code, happens
     * in the application service via dedicated driven lookup ports, not here and not in the driving
     * (MCP) adapter, which has no store access of its own.</p>
     *
     * @param name                      the decision's title
     * @param context                   why the decision was necessary - forces and constraints
     * @param decision                  what was decided
     * @param consequences              the decision's consequences; optional (may be {@code null})
     * @param alternatives              the considered but rejected options; optional (may be
     *                                  {@code null})
     * @param decisionDate              the day the decision was made; optional (may be {@code null})
     * @param addressesRequirementCodes business codes of the requirements this decision addresses,
     *                                  e.g. {@code FR-1}; may be {@code null} or empty
     * @param affectsContextCodes       business codes of the bounded contexts this decision affects,
     *                                  e.g. {@code BC-1}; may be {@code null} or empty
     */
    record NewAdr(
            String name,
            String context,
            String decision,
            String consequences,
            String alternatives,
            LocalDate decisionDate,
            List<String> addressesRequirementCodes,
            List<String> affectsContextCodes) {

        public NewAdr {
            addressesRequirementCodes =
                    addressesRequirementCodes == null ? List.of() : List.copyOf(addressesRequirementCodes);
            affectsContextCodes = affectsContextCodes == null ? List.of() : List.copyOf(affectsContextCodes);
        }
    }
}
