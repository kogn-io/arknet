// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;

/**
 * A single option considered while making an {@link Adr} - chosen, rejected, or (only via the
 * legacy-literal fallback, see below) left unclassified ({@code arkarch:ConsideredOption},
 * kogn-io/arknet#357).
 *
 * <p>Value object. Options are numbered starting at {@code 1}; the ordering invariants across a
 * whole list (gap-free, ascending) are enforced by {@link Adr}, together with the decision-wide
 * invariant that at most one option may carry {@link OptionOutcome#CHOSEN}. Mirrors
 * {@code de.hauschel.arknet.req.domain.AcceptanceCriterion} (issue #266) in shape, with two extra
 * fields ({@link #name()}, {@link #outcome()}) that criterion had no need for.</p>
 *
 * <p>Making the <em>chosen</em> option representable (not only the rejected ones the pre-#357
 * {@code arkarch:adrAlternatives} literal ever recorded) is the point of {@link #outcome()}: MADR's
 * "Decision Outcome" names the option that was actually picked as part of the same considered-options
 * list, not as a separate field duplicating {@link Adr#decision()}'s prose.</p>
 *
 * @param position  the 1-based position of the option in the list ({@code >= 1})
 * @param name      the short, non-blank name of the option (e.g. "Adopt library X")
 * @param rationale the non-blank reasoning for why this option was chosen or rejected
 * @param outcome   whether this option was chosen or rejected; {@code null} only for an option the
 *                  out-adapter synthesised from a store-first {@code arkarch:adrAlternatives}
 *                  literal that predates this resource and therefore carries no outcome of its own -
 *                  every option written through {@code adr_add}/{@code adr_update} carries one
 */
public record ConsideredOption(int position, String name, String rationale, OptionOutcome outcome) {

    public ConsideredOption {
        if (position < 1) {
            throw new IllegalArgumentException("ConsideredOption position must be >= 1, was " + position);
        }
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("ConsideredOption name must not be blank");
        }
        Objects.requireNonNull(rationale, "rationale");
        if (rationale.isBlank()) {
            throw new IllegalArgumentException("ConsideredOption rationale must not be blank");
        }
    }
}
