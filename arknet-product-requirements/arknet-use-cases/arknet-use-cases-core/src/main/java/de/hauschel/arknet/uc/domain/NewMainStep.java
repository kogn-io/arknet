// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.List;
import java.util.Objects;

/**
 * A main-flow step to append to a {@link UseCase} (kogn-io/arknet#513) - the position-free
 * counterpart of {@link Step}, used by {@link UseCase#withAppendedMainSteps(java.util.List)}: the
 * next position is assigned by that method, continuing from the use case's current highest one,
 * the same way {@code de.hauschel.arknet.adr.domain.NewConsequence} assigns positions for a newly
 * appended consequence.
 *
 * @param text     the non-blank step description (an actor/system action)
 * @param realises the functional requirements this step realises; {@code 0..n}, held as
 *                 {@link RequirementRef} (never {@code null}; a {@code null} argument is
 *                 normalised to an empty list)
 */
public record NewMainStep(String text, List<RequirementRef> realises) {

    public NewMainStep {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("NewMainStep text must not be blank");
        }
        realises = realises == null ? List.of() : List.copyOf(realises);
    }
}
