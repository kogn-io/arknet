// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;

/**
 * A single positive, negative or neutral consequence of an {@link Adr} ({@code arkarch:Consequence},
 * kogn-io/arknet#357).
 *
 * <p>Value object. Consequences are numbered starting at {@code 1}; the ordering invariants across
 * a whole list (gap-free, ascending) are enforced by {@link Adr}, while this type only guards a
 * single consequence. Mirrors {@code de.hauschel.arknet.req.domain.AcceptanceCriterion} (issue
 * #266), the precedent for turning a flat string field into its own positioned resource, plus one
 * extra classifying field ({@link #type()}) that criterion had no need for.</p>
 *
 * <p>Deliberately plain free text - no structured impact scoring, no severity. Position is purely
 * technical (write ordering), never a business identity a caller reasons about.</p>
 *
 * @param position  the 1-based position of the consequence in the list ({@code >= 1})
 * @param statement the non-blank consequence text
 * @param type      whether this consequence is positive, negative or neutral; never {@code null}
 */
public record Consequence(int position, String statement, ConsequenceType type) {

    public Consequence {
        if (position < 1) {
            throw new IllegalArgumentException("Consequence position must be >= 1, was " + position);
        }
        Objects.requireNonNull(statement, "statement");
        if (statement.isBlank()) {
            throw new IllegalArgumentException("Consequence statement must not be blank");
        }
        Objects.requireNonNull(type, "type");
    }
}
