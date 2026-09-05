// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The positions one {@code adr_update} call removes from {@link Adr#consequences()} or
 * {@link Adr#consideredOptions()} (kogn-io/arknet#483), together with the one rule that follows
 * from removing them: every surviving position after a removed one moves up by the number of
 * removed positions below it, so the survivors are consecutive from 1 again - the invariant
 * {@link Adr}'s compact constructor demands of both lists.
 *
 * <p><strong>Why the rule is a value object and not two loops.</strong> A consequence's position is
 * its only identity, and the out-adapter keys every other-language variant of a consequence's text
 * by that position when it carries the variant across a replacing write. A removal therefore has to
 * renumber in two places that must never disagree: the domain ({@link Adr#withoutConsequences}
 * renumbers the surviving records) and the write path ({@code AdrService} and the out-adapter
 * re-key each surviving position's language state under its new number, and drop the removed
 * position's). {@link #survivingPositionOf} is the single formulation both sides read the mapping
 * from, so a variant written at position 3 follows its consequence to position 2 when position 1
 * goes, instead of landing on whatever now sits at 3.</p>
 *
 * <p>Positions are the 1-based numbers the record carries <em>before</em> the removal - exactly
 * what {@code adr_get} shows the caller and what a {@link ConsequenceCorrection} in the same call
 * addresses. Duplicates collapse; a position that is not positive is rejected outright, because no
 * list ever carries one.</p>
 *
 * @param positions the 1-based positions to remove; never {@code null}, may be empty
 */
public record RemovedPositions(Set<Integer> positions) {

    /** Removes nothing - the value every write that does not remove a position passes. */
    public static final RemovedPositions NONE = new RemovedPositions(Set.of());

    public RemovedPositions {
        Objects.requireNonNull(positions, "positions");
        positions = Set.copyOf(positions);
        for (Integer position : positions) {
            if (position == null || position < 1) {
                throw new IllegalArgumentException("a position to remove must be 1 or higher, was: " + position);
            }
        }
    }

    /** @return {@code true} if this removes nothing at all */
    public boolean isEmpty() {
        return positions.isEmpty();
    }

    /** @return whether {@code position} is among the removed ones */
    public boolean contains(int position) {
        return positions.contains(position);
    }

    /**
     * The position a surviving entry holds once the removal is applied.
     *
     * @param formerPosition the entry's 1-based position before the removal
     * @return its position after every removed position below it has moved it up, or empty if
     *         {@code formerPosition} is itself removed
     */
    public OptionalInt survivingPositionOf(int formerPosition) {
        if (positions.contains(formerPosition)) {
            return OptionalInt.empty();
        }
        long removedBelow = positions.stream().filter(removed -> removed < formerPosition).count();
        return OptionalInt.of(formerPosition - (int) removedBelow);
    }
}
