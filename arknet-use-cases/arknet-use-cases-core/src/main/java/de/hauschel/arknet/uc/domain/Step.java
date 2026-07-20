// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.List;
import java.util.Objects;

/**
 * A single step in the flow of a {@link UseCase}.
 *
 * <p>Value object. Steps are numbered starting at {@code 1}; the ordering
 * invariants across a whole flow (gap-free, duplicate-free, ascending) are
 * enforced by {@link UseCase}, while this type only guards a single step.</p>
 *
 * @param position the 1-based position of the step in the flow ({@code >= 1})
 * @param text     the non-blank step description (an actor/system action)
 * @param realises the functional requirements this step realises; {@code 0..n},
 *                 held as {@link RequirementRef} (subject-identity references, never
 *                 {@code null}; a {@code null} argument is normalised to an empty
 *                 list). The list is copied defensively and is immutable.
 */
public record Step(int position, String text, List<RequirementRef> realises) {

    public Step {
        if (position < 1) {
            throw new IllegalArgumentException("Step position must be >= 1, was " + position);
        }
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("Step text must not be blank");
        }
        realises = realises == null ? List.of() : List.copyOf(realises);
    }
}
