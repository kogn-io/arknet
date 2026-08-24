// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;

/**
 * Thrown when a caller tries to delete a decision that is no longer {@link AdrStatus#PROPOSED}.
 *
 * <p>An expected domain outcome (not a programming error): driving adapters - e.g. the MCP tools -
 * translate it into a user-facing message rather than a stack trace.</p>
 *
 * <p><strong>What deletion is for.</strong> {@code adr_delete} removes a record created by mistake -
 * a duplicate, a typo recorded as its own decision, a draft that turned out to belong somewhere
 * else. It is not a lifecycle step. Once a decision has left {@link AdrStatus#PROPOSED}, somebody
 * decided something, and that is precisely what a decision record exists to keep (Nygard) - so this
 * exception refuses and names the path that fits each status instead: a successor linked with
 * {@code adr_supersede}, or {@code adr_set_status DEPRECATED} for a decision that became obsolete
 * without one.</p>
 *
 * <p><strong>{@link AdrStatus#REJECTED} is not the way out either.</strong> "Considered and turned
 * down" is a documented decision with value - it is what stops the same option being proposed again
 * a year later - so a rejected record is as undeletable as an accepted one. Rejecting a record in
 * order to get rid of it empties that signal; {@code adr_delete} on a {@code PROPOSED} decision is
 * the honest way to undo an accidental {@code adr_add}.</p>
 */
public class AdrNotDeletableException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final transient AdrCode code;
    private final transient AdrStatus status;

    /**
     * Creates the exception.
     *
     * @param code   the decision the caller tried to delete
     * @param status the status that keeps it (anything but {@link AdrStatus#PROPOSED})
     * @throws IllegalArgumentException if {@code status} is {@link AdrStatus#PROPOSED} - that
     *                                  decision is deletable, so this exception does not apply
     */
    public AdrNotDeletableException(AdrCode code, AdrStatus status) {
        super(message(code, status));
        this.code = code;
        this.status = status;
    }

    private static String message(AdrCode code, AdrStatus status) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(status, "status");
        return "ADR " + code.value() + " can only be deleted while PROPOSED, but it is " + status
                + " - " + remedy(code, status);
    }

    /**
     * The remedy that actually fits the status the decision is in. Deliberately one text per status
     * rather than one generic sentence: what to do with an accepted decision, a rejected one and an
     * already-obsolete one are three different answers, and a caller reading "use adr_supersede" for
     * a record they only wanted to un-reject learns nothing.
     */
    private static String remedy(AdrCode code, AdrStatus status) {
        return switch (status) {
            case ACCEPTED -> "a decision in force records what was decided at the time: record its "
                    + "replacement with adr_add and link it with adr_supersede, or mark it obsolete "
                    + "with adr_set_status DEPRECATED. adr_delete is for a record created by "
                    + "mistake, not for one that was decided";
            case REJECTED -> "REJECTED means the option was considered and turned down, which is "
                    + "itself a decision worth keeping - it is what stops the same option coming "
                    + "back a year later. adr_delete is for a record created by mistake, not for an "
                    + "option you decided against";
            case DEPRECATED -> "the decision is already marked obsolete, and keeping that history is "
                    + "what a decision record is for - DEPRECATED is the end of this lifecycle, not "
                    + "a step before removal";
            case PROPOSED -> throw new IllegalArgumentException(
                    "ADR " + code.value() + " is PROPOSED and therefore deletable");
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
