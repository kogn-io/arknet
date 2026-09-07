// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;

/**
 * Thrown when a caller tries to delete a decision whose status forbids it - see
 * {@link AdrStatus#isDeletable()}.
 *
 * <p>An expected domain outcome (not a programming error): driving adapters - e.g. the MCP tools -
 * translate it into a user-facing message rather than a stack trace.</p>
 *
 * <p><strong>What deletion is for.</strong> {@code adr_delete} removes a record created by mistake -
 * a duplicate, a typo recorded as its own decision, a draft that turned out to belong somewhere
 * else, or an accepted record that was never really an architecture decision
 * (kogn-io/arknet#528). It is not a lifecycle step. Once a decision has been superseded or
 * deprecated, or was deliberately turned down, somebody decided something, and that is precisely
 * what a decision record exists to keep (Nygard) - so this exception refuses and names the path
 * that fits each of those statuses instead: {@code adr_unsupersede} to undo a wrong successor for
 * {@link AdrStatus#SUPERSEDED}, or simply nothing for {@link AdrStatus#DEPRECATED} and a rejected
 * option, both of which stay as they are.</p>
 *
 * <p><strong>{@link AdrStatus#REJECTED} is not the way out either.</strong> "Considered and turned
 * down" is a documented decision with value - it is what stops the same option being proposed again
 * a year later - so a rejected record stays undeletable even though an accepted one may now be
 * deletable. Rejecting a record in order to get rid of it empties that signal;
 * {@code adr_delete} on a {@code PROPOSED} or an unreferenced {@code ACCEPTED} decision is the
 * honest way to undo a mistake.</p>
 */
public class AdrNotDeletableException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final transient AdrCode code;
    private final transient AdrStatus status;

    /**
     * Creates the exception.
     *
     * @param code   the decision the caller tried to delete
     * @param status the status that keeps it (anything for which {@link AdrStatus#isDeletable()} is
     *               {@code false})
     * @throws IllegalArgumentException if {@code status} is deletable - that decision does not
     *                                  belong here, this exception does not apply
     */
    public AdrNotDeletableException(AdrCode code, AdrStatus status) {
        super(message(code, status));
        this.code = code;
        this.status = status;
    }

    private static String message(AdrCode code, AdrStatus status) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(status, "status");
        return "ADR " + code.value() + " cannot be deleted while " + status
                + " - " + remedy(code, status);
    }

    /**
     * The remedy that actually fits the status the decision is in. Deliberately one text per status
     * rather than one generic sentence: what to do with a rejected decision and an already-obsolete
     * one are two different answers, and a caller reading "use adr_supersede" for a record they only
     * wanted to un-reject learns nothing.
     */
    private static String remedy(AdrCode code, AdrStatus status) {
        return switch (status) {
            case REJECTED -> "REJECTED means the option was considered and turned down, which is "
                    + "itself a decision worth keeping - it is what stops the same option coming "
                    + "back a year later. adr_delete is for a record created by mistake, not for an "
                    + "option you decided against";
            case DEPRECATED -> "the decision is already marked obsolete, and keeping that history is "
                    + "what a decision record is for - DEPRECATED is the end of this lifecycle, not "
                    + "a step before removal";
            case SUPERSEDED -> "the decision has already been replaced by a successor (see its "
                    + "supersededBy edge), and keeping that history is exactly what a decision "
                    + "record is for - adr_delete is for a record created by mistake, not for one "
                    + "that was decided and then superseded. If the supersession itself was the "
                    + "mistake (wrong successor named), adr_unsupersede undoes it and restores "
                    + "ACCEPTED instead";
            case PROPOSED, ACCEPTED -> throw new IllegalArgumentException(
                    "ADR " + code.value() + " is " + status + " and therefore deletable");
        };
    }

    /** @return the decision the caller tried to delete */
    public AdrCode adrCode() {
        return code;
    }

    /** @return the status that keeps the decision */
    public AdrStatus status() {
        return status;
    }
}
