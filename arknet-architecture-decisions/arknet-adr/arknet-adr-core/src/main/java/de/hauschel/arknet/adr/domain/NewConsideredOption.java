// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;

/**
 * An option to append to an {@link Adr} - the position-free counterpart of {@link ConsideredOption},
 * used by {@link Adr#withAppendedConsideredOptions(java.util.List)}. Mirrors {@link NewConsequence};
 * see that type's javadoc for why position is assigned by the appending method rather than supplied
 * here.
 *
 * @param name      the short, non-blank name of the option
 * @param rationale the non-blank reasoning for why the option was chosen or rejected
 * @param outcome   whether the option was chosen or rejected; never {@code null} - unlike
 *                  {@link ConsideredOption#outcome()}, an appended option always states one (only
 *                  the out-adapter's legacy-literal fallback ever synthesises one without)
 */
public record NewConsideredOption(String name, String rationale, OptionOutcome outcome) {

    public NewConsideredOption {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("NewConsideredOption name must not be blank");
        }
        Objects.requireNonNull(rationale, "rationale");
        if (rationale.isBlank()) {
            throw new IllegalArgumentException("NewConsideredOption rationale must not be blank");
        }
        Objects.requireNonNull(outcome, "outcome");
    }
}
