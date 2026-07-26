// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Thrown when {@link de.hauschel.arknet.ul.application.port.out.TermRepository#update} loses a
 * genuine, store-level write conflict against another writer that concurrently patched the very
 * same predicate of the very same term (e.g. two overlapping {@code term_update} calls both
 * setting {@code skos:prefLabel} to a different value at the same time).
 *
 * <p>Distinct from {@link DuplicateTermCodeException} on purpose: that one means a business-code
 * collision, an entirely different failure mode that {@code update()} can no longer even
 * reach, since it never rewrites {@code dcterms:identifier}. It is also distinct from a classic
 * lost-update between an application-level read and its later write - {@code update()} resolves
 * the term and reads whatever it needs to preserve inside the very transaction that writes, so
 * there is no read-then-write gap left to lose a change in. What remains is narrower: two
 * genuinely overlapping {@code SERIALIZABLE} transactions (kogn-io/rdf-core#18) racing to replace
 * the identical triple pattern, where the store itself rejects the loser's commit rather than
 * silently letting one overwrite the other. An expected-but-rare domain outcome, not a programming
 * error - the caller should re-check the term's current state and retry, not treat this as a
 * permanent failure.</p>
 */
public class TermConcurrentlyModifiedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient WorkspaceId workspaceId;
    private final transient TermCode code;

    /**
     * Creates the exception.
     *
     * @param workspaceId the workspace the term lives in
     * @param code        the term code whose update lost the write conflict
     */
    public TermConcurrentlyModifiedException(WorkspaceId workspaceId, TermCode code) {
        super("term " + Objects.requireNonNull(code, "code").value()
                + " in workspace " + Objects.requireNonNull(workspaceId, "workspaceId").value()
                + " could not be updated - a concurrent update to the same field committed at the same time; "
                + "re-check the term's current state and retry");
        this.workspaceId = workspaceId;
        this.code = code;
    }

    /** @return the workspace the term lives in */
    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    /** @return the term code whose update lost the write conflict */
    public TermCode termCode() {
        return code;
    }
}
