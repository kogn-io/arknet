// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;

/**
 * A correction of the existing {@link Consequence} at {@code position}: every other consequence,
 * and the very existence/ordering of every consequence, is untouched.
 *
 * <p>Both {@code statement} and {@code type} are mandatory and replace the position's content
 * wholesale - a correction is a replacement of what was recorded, not a way to accidentally
 * no-op one field while touching the other. Mirrors
 * {@code de.hauschel.arknet.req.domain.AcceptanceCriterionTextPatch} (issue #266): in-place-only,
 * no reorder - see {@link Adr#withConsequenceCorrections} for why that restriction transfers
 * unchanged from the requirements precedent (position is the only identity a consequence has).</p>
 *
 * @param position  the 1-based position of the existing consequence to correct - must match a
 *                  consequence already present on the decision
 * @param statement the corrected, non-blank consequence text
 * @param type      the corrected consequence type; never {@code null}
 */
public record ConsequenceCorrection(int position, String statement, ConsequenceType type) {

    public ConsequenceCorrection {
        Objects.requireNonNull(statement, "statement");
        if (statement.isBlank()) {
            throw new IllegalArgumentException("ConsequenceCorrection statement must not be blank");
        }
        Objects.requireNonNull(type, "type");
    }
}
