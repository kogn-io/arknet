// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;

/**
 * Thrown when a caller tries to change the text of a decision that is no longer
 * {@link AdrStatus#PROPOSED}.
 *
 * <p>An expected domain outcome (not a programming error): driving adapters - e.g. the MCP tools -
 * translate it into a user-facing message rather than a stack trace.</p>
 *
 * <p><strong>Why this is a rule and not a nuisance.</strong> A decision that has been put in force
 * (or turned down) is a record of what was decided at the time, and rewriting that record erases the
 * very history an ADR exists to keep (Nygard). The correction is therefore recorded as a decision of
 * its own rather than as an edit - which is what this exception's message tells the caller, so the
 * rule teaches rather than merely blocks.</p>
 *
 * <p><strong>One remedy per status, not one sentence for all four.</strong> Only an
 * {@link AdrStatus#ACCEPTED} decision can be linked to that new decision with {@code adr_supersede}:
 * {@link Adr#supersededBy(AdrId)} refuses every other status (kogn-io/arknet#357), so pointing a
 * rejected, deprecated or already-superseded record at {@code adr_supersede} would send the caller
 * straight into a second rejection. The same shape {@link AdrNotDeletableException#remedy} settled
 * on.</p>
 *
 * <p>The four reference relations ({@code addressesRequirement}, {@code affectsContext},
 * {@code usesTerm}, {@code relatedTo}) are deliberately <em>not</em> covered by this rule and never raise it:
 * completing a reference that could not be written when the decision was made (because the
 * referenced resource did not exist yet) records the same decision more fully instead of changing
 * it.</p>
 */
public class AdrTextImmutableException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final transient AdrCode code;
    private final transient AdrStatus status;

    /**
     * Creates the exception.
     *
     * @param code   the decision whose text was to be changed
     * @param status the status that made it immutable (anything but {@link AdrStatus#PROPOSED})
     * @throws IllegalArgumentException if {@code status} is {@link AdrStatus#PROPOSED} - that
     *                                  decision's text is correctable, so this exception does not
     *                                  apply
     */
    public AdrTextImmutableException(AdrCode code, AdrStatus status) {
        super(message(code, status));
        this.code = code;
        this.status = status;
    }

    private static String message(AdrCode code, AdrStatus status) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(status, "status");
        return "the text of ADR " + code.value() + " can only be corrected while PROPOSED, but it is "
                + status + " - " + remedy(code, status);
    }

    /**
     * The remedy that actually fits the status the decision is in. {@code adr_supersede} appears in
     * exactly the one status that accepts it; the other three name the same underlying move - record
     * the correction as a decision of its own - without promising an edge the domain would refuse.
     */
    private static String remedy(AdrCode code, AdrStatus status) {
        return switch (status) {
            case ACCEPTED -> "a decision in force records what was decided at the time: record the "
                    + "correction as a new decision with adr_add and link it with adr_supersede "
                    + "instead of rewriting this one";
            case REJECTED -> "REJECTED means this option was considered and turned down at the time, "
                    + "and that is what the record is for - record the correction as a decision of "
                    + "its own with adr_add; there is no supersession edge out of a rejected "
                    + "decision, adr_supersede works only on one still in force (ACCEPTED)";
            case DEPRECATED -> "the decision is already marked obsolete, so its text is history now - "
                    + "record the correction as a decision of its own with adr_add; adr_supersede "
                    + "works only on a decision still in force (ACCEPTED), not on one already "
                    + "retired";
            case SUPERSEDED -> "the decision has already been replaced by a successor (see its "
                    + "supersededBy edge) - correct that successor while it is still PROPOSED, or "
                    + "record a further decision with adr_add and supersede the successor; a second "
                    + "supersession out of this record does not exist";
            case PROPOSED -> throw new IllegalArgumentException(
                    "the text of ADR " + code.value() + " is PROPOSED and therefore correctable");
        };
    }

    /** @return the decision whose text was to be changed */
    public AdrCode adrCode() {
        return code;
    }

    /** @return the status that made the text immutable */
    public AdrStatus status() {
        return status;
    }
}
