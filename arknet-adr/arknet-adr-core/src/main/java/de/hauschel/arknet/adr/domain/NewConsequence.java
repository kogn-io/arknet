// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;

/**
 * A consequence to append to an {@link Adr} - the position-free counterpart of {@link Consequence},
 * used by {@link Adr#withAppendedConsequences(java.util.List)}: the next position is assigned by
 * that method, continuing from the decision's current highest one, the same way
 * {@code de.hauschel.arknet.req.domain.Requirement#withAppendedAcceptanceCriteria} assigns positions
 * for a newly appended acceptance criterion.
 *
 * @param statement the non-blank consequence text
 * @param type      whether the consequence is positive, negative or neutral; never {@code null}
 */
public record NewConsequence(String statement, ConsequenceType type) {

    public NewConsequence {
        Objects.requireNonNull(statement, "statement");
        if (statement.isBlank()) {
            throw new IllegalArgumentException("NewConsequence statement must not be blank");
        }
        Objects.requireNonNull(type, "type");
    }
}
