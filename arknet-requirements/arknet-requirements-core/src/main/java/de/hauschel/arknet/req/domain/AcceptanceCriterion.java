// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.Objects;

/**
 * A single testable "Done when ..." criterion of a {@link Requirement}.
 *
 * <p>Value object. Criteria are numbered starting at {@code 1}; the ordering invariants across a
 * whole list (gap-free, ascending) are enforced by {@link Requirement}, while this type only
 * guards a single criterion. Mirrors {@code de.hauschel.arknet.uc.domain.Step} (issue #266),
 * minus that type's {@code realises} edge: a criterion is the requirement's own proof of
 * completion, not a cross-resource traceability reference, so it carries none.</p>
 *
 * <p>Deliberately plain free text - no Given/When/Then, no Gherkin, no sub-fields. Position is
 * purely technical (write ordering), never a business identity a caller reasons about.</p>
 *
 * @param position the 1-based position of the criterion in the list ({@code >= 1})
 * @param text     the non-blank criterion text
 */
public record AcceptanceCriterion(int position, String text) {

    public AcceptanceCriterion {
        if (position < 1) {
            throw new IllegalArgumentException("AcceptanceCriterion position must be >= 1, was " + position);
        }
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("AcceptanceCriterion text must not be blank");
        }
    }
}
