// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.Arrays;
import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when a requirement's persisted {@code arkreq:status} denotes a value the requirements
 * SHACL shapes accept (see {@code requirements-shapes.ttl}'s {@code Requirement-status} shape,
 * which enumerates six individuals via {@code sh:in}) but {@link RequirementStatus} - deliberately
 * limited to an MVP subset ({@link RequirementStatus#PROPOSED}/{@link RequirementStatus#ACCEPTED})
 * per its own Javadoc - does not implement.
 *
 * <p><strong>Adapter-boundary translation, not a domain outcome.</strong> A SHACL-legal status
 * this narrow can only reach a requirement store-first (ADR-005; no MCP tool writes one of the
 * four unimplemented values), so this is not a business rule a caller can trigger through the
 * tool surface - it is the out-adapter refusing to silently misrepresent data it cannot map.
 * Thrown directly, in place of a raw, uncaught {@link IllegalStateException}, by every read path
 * that decodes a status IRI ({@code findByCode}, {@code findCurrentByCode}, {@code findAll}) - it
 * never wraps an {@link IllegalStateException} as a cause, so the actionable message here is not
 * buried behind a root-cause message a caller never asked for (the lesson of issue #137: a
 * composed message loses to a deeper cause once a driving adapter renders "deepest cause wins").
 * </p>
 *
 * <p><strong>Why not filter the value out instead.</strong> {@link
 * de.hauschel.arknet.req.application.port.out.RequirementRepository#findAll} already filters its
 * {@code rdf:type} join to the two known requirement types (an unfiltered join would hand
 * {@code typeFromIri} a value it cannot map either) - status is deliberately not filtered the same
 * way. Silently excluding a requirement whose status this adapter cannot decode would turn a
 * SHACL-legal, store-first-written requirement invisible to {@code req_list}/{@code req_get}
 * without any signal - the same silent-drop failure mode issue #136 already named as the bug for
 * blank-node {@code usesTerm} subjects, not a pattern to repeat for status. A legal store value
 * fails loudly here instead of vanishing quietly.</p>
 */
public class UnsupportedRequirementStatusException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient RequirementCode code;
    private final String statusIri;

    /**
     * Creates the exception.
     *
     * @param projectId the project the requirement lives in
     * @param code        the requirement code whose status could not be decoded
     * @param statusIri   the SHACL-legal but unsupported {@code arkreq:status} object IRI found
     *                    in the store
     */
    public UnsupportedRequirementStatusException(ProjectId projectId, RequirementCode code, String statusIri) {
        super("requirement " + Objects.requireNonNull(code, "code").value()
                + " in project " + Objects.requireNonNull(projectId, "projectId").value()
                + " carries status " + Objects.requireNonNull(statusIri, "statusIri")
                + ", which arknet's MVP does not implement (supported: "
                + Arrays.toString(RequirementStatus.values())
                + ") - this value was written store-first, not through req_set_status; "
                + "fix it by editing the arkreq:status triple in the store directly");
        this.projectId = projectId;
        this.code = code;
        this.statusIri = statusIri;
    }

    /** @return the project the requirement lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the requirement code whose status could not be decoded */
    public RequirementCode requirementCode() {
        return code;
    }

    /** @return the SHACL-legal but unsupported {@code arkreq:status} object IRI found in the store */
    public String statusIri() {
        return statusIri;
    }
}
