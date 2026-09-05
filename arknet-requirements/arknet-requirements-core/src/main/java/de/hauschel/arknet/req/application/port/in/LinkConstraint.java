// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;

/**
 * Driving port: link a requirement to a constraint it is bound by.
 *
 * <p>Backs the MVP tool {@code req_link_constraint}. The edge ({@code oslc_rm:constrainedBy}) is
 * owned by the requirement, per the ontology's own class comment on {@code arkreq:Constraint}
 * ("Wird ueber oslc_rm:constrainedBy an Requirements gehaengt") - mirrors {@link LinkTerm}'s
 * shape exactly, one hexagon closer to home: {@link de.hauschel.arknet.req.domain.Constraint}
 * lives in this same bounded context, so resolving {@code constraintCode} to its opaque
 * {@link de.hauschel.arknet.req.domain.ConstraintRef} identity is a direct, same-module lookup
 * against {@code ConstraintRepository} - never a cross-BC driven lookup port the way
 * {@code termCode} needs {@code TermLookup}.</p>
 *
 * <p>{@code constraintCode} is exactly what a human types (e.g. {@code TCON-1}), never an IRI -
 * the MCP boundary never surfaces the store-internal identity.</p>
 *
 * <p><strong>{@code defaultLanguage} (issue #468).</strong> Linking a constraint touches no
 * language-tagged field itself, but the read-modify-write round trip behind this call still needs
 * the project's own default language so an untouched field is echoed back (and compared for the
 * idempotency check) under the project's own language rather than the process-wide configured
 * one.</p>
 */
public interface LinkConstraint {

    /**
     * Links the constraint identified by {@code constraintCode} to the requirement {@code code}.
     * Linking an already-linked constraint is an idempotent no-op.
     *
     * @param code            the requirement code, e.g. {@code FR-1}
     * @param constraintCode  the constraint's human-readable business code, e.g. {@code TCON-1}
     * @param defaultLanguage the target project's configured default language (see
     *                        {@link de.hauschel.arknet.kernel.ResolvedProject#defaultLanguage()}),
     *                        or {@code null} if it has none - consulted only for the read this call
     *                        makes to echo an untouched field back, never for a write
     * @return the requirement including the link
     */
    Requirement linkConstraint(
            ProjectId projectId, RequirementCode code, String constraintCode, String defaultLanguage);
}
