// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;

/**
 * A correction of the existing {@link ConsideredOption} at {@code position}: every other option,
 * and the very existence/ordering of every option, is untouched.
 *
 * <p>{@code name}, {@code rationale} and {@code outcome} are all mandatory and replace the
 * position's content wholesale - a correction always completes the option (never leaves it in the
 * legacy-fallback's outcome-less state), mirroring
 * {@code de.hauschel.arknet.req.domain.AcceptanceCriterionTextPatch} (issue #266): in-place-only,
 * no reorder - see {@link Adr#withConsideredOptionCorrections} for why that restriction transfers
 * unchanged from the requirements precedent.</p>
 *
 * @param position  the 1-based position of the existing option to correct - must match an option
 *                  already present on the decision
 * @param name      the corrected, non-blank option name
 * @param rationale the corrected, non-blank reasoning
 * @param outcome   the corrected outcome; never {@code null}
 */
public record ConsideredOptionCorrection(int position, String name, String rationale, OptionOutcome outcome) {

    public ConsideredOptionCorrection {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("ConsideredOptionCorrection name must not be blank");
        }
        Objects.requireNonNull(rationale, "rationale");
        if (rationale.isBlank()) {
            throw new IllegalArgumentException("ConsideredOptionCorrection rationale must not be blank");
        }
        Objects.requireNonNull(outcome, "outcome");
    }
}
