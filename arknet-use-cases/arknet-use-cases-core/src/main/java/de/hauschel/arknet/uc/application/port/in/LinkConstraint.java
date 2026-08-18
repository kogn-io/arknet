// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;

/**
 * Driving port: link a use case to a constraint it is bound by.
 *
 * <p>Backs the tool {@code uc_link_constraint}. {@code oslc_rm:constrainedBy} declares no
 * {@code rdfs:domain} in the ontology - the binding to a subject type is a SHACL/tool-surface
 * concern, not an OWL one (see {@code arknet-requirements.ttl}'s own comment on the reused OSLC
 * RM properties). Mirrors the sibling requirements bounded context's own {@code LinkConstraint}
 * one hexagon further from home: unlike there, where {@code Constraint} lives inside the same
 * module and resolution is a direct same-module lookup, here {@code Constraint} lives in the
 * neighbouring requirements bounded context - resolving the human-typed constraint code happens
 * via a dedicated driven lookup port ({@code ConstraintLookup}), the same shape
 * {@code arkreq:usesTerm}'s resolution already takes for the genuinely cross-BC case.</p>
 *
 * <p>{@code constraintCode} is exactly what a human types (e.g. {@code TCON-1}), never an IRI -
 * the MCP boundary never surfaces the store-internal identity.</p>
 */
public interface LinkConstraint {

    /**
     * Links the constraint identified by {@code constraintCode} to the use case {@code code}.
     * Linking an already-linked constraint is an idempotent no-op.
     *
     * @param code           the use-case code, e.g. {@code UC1}
     * @param constraintCode the constraint's human-readable business code, e.g. {@code TCON-1}
     * @return the use case including the link
     */
    UseCase linkConstraint(ProjectId projectId, UseCaseCode code, String constraintCode);
}
