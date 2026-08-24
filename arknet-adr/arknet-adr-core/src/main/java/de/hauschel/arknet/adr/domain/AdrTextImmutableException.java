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
 * very history an ADR exists to keep (Nygard). The correction path for a decision in force is
 * therefore a successor decision linked with {@code adr_supersede}, not an edit - which is what this
 * exception's message tells the caller, so the rule teaches rather than merely blocks.</p>
 *
 * <p>The three reference relations ({@code addressesRequirement}, {@code affectsContext},
 * {@code relatedTo}) are deliberately <em>not</em> covered by this rule and never raise it:
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
     */
    public AdrTextImmutableException(AdrCode code, AdrStatus status) {
        super("the text of ADR " + Objects.requireNonNull(code, "code").value()
                + " can only be corrected while PROPOSED, but it is "
                + Objects.requireNonNull(status, "status")
                + " - record the correction as a new decision and link it with adr_supersede "
                + "instead of rewriting a decision that is already in force");
        this.code = code;
        this.status = status;
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
