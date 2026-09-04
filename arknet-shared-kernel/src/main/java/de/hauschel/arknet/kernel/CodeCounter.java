// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;

/**
 * Reads the running number out of a project-unique business code ({@code TERM-7}, {@code FR-3},
 * {@code UC12}, {@code TCON-2}, ...) and derives the highest one a set of codes carries - the read
 * half of every bounded context's code counter, whose write half is minting
 * {@code prefix + (highest + 1)}.
 *
 * <p><strong>The prefix is the anchor, not a separator convention.</strong> A caller hands in the
 * very same prefix expression it mints with, and only codes starting with it are counted; the rest
 * of the code must then be digits or it counts for nothing. That single rule replaces two divergent
 * parses this codebase carried side by side - "the digits after the last dash" for {@code TERM-7}
 * and "the digits after the leading letters" for {@code UC12} - and needs no separator convention of
 * its own, because {@code UC} and {@code TERM-} are each simply the literal their own bounded
 * context prepends.</p>
 *
 * <p><strong>Anchoring doubles as the type filter.</strong> Requirements and constraints run one
 * counter per type ({@code FR}/{@code NFR}, {@code TCON}/{@code BCON}/{@code RCON}), which they used
 * to obtain by filtering materialised resources on their domain type before counting. Passing
 * {@code type.idPrefix() + "-"} here gets the same partition out of the code itself, so the counter
 * no longer depends on a resource's type triple being readable - the point of counting over raw
 * codes in the first place (kogn-io/arknet#360). {@code FR-} never matches {@code NFR-3}, since the
 * match is anchored at the start rather than searched for.</p>
 *
 * <p><strong>Why it lives in the shared kernel.</strong> The same reason
 * {@link CodeAssignment} does: every {@code *-core} needs it, and every {@code *-core} must stay
 * free of RDF technology (ArchUnit rule 3), so the one technology-neutral module they all already
 * depend on is where a helper reachable from all of them belongs. The out-adapters may use it too -
 * they sit above the kernel as well, and ordering retained codes by running number is the same
 * parse.</p>
 */
public final class CodeCounter {

    private CodeCounter() {
    }

    /**
     * Returns the running number {@code code} carries after {@code codePrefix}, or {@code 0} if it
     * carries none - because {@code code} does not start with {@code codePrefix} at all, or because
     * what follows the prefix is not a plain sequence of digits.
     *
     * <p>{@code 0} rather than an exception or an empty {@link java.util.Optional}: this parses
     * store-first data nothing validated on the way in, where an unparseable code is a
     * fact to survive rather than an error to raise, and "contributes nothing to the maximum" is
     * exactly the right contribution for one. Since a minted number always starts at {@code 1},
     * {@code 0} can never collide with a real one.</p>
     *
     * @param codePrefix the literal a code of this counter starts with, e.g. {@code "TERM-"},
     *                   {@code "UC"} or {@code "FR-"} (must not be {@code null})
     * @param code       the code to read, e.g. {@code "TERM-7"}; {@code null} carries no number
     * @return the running number, or {@code 0}
     */
    public static int runningNumber(String codePrefix, String code) {
        Objects.requireNonNull(codePrefix, "codePrefix");
        if (code == null || !code.startsWith(codePrefix)) {
            return 0;
        }
        String number = code.substring(codePrefix.length());
        if (number.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < number.length(); i++) {
            // Rejected explicitly rather than left to parseInt, which would accept a leading sign:
            // a code such as "TERM--1" would otherwise yield a negative number and quietly lose
            // against every well-formed one, where 0 makes it lose openly and identically to any
            // other unreadable code.
            if (!Character.isDigit(number.charAt(i))) {
                return 0;
            }
        }
        try {
            return Integer.parseInt(number);
        } catch (NumberFormatException e) {
            // All digits, but more of them than an int holds - store-first data again, and just as
            // unusable as a counter value.
            return 0;
        }
    }

    /**
     * Returns the highest running number among {@code codes} that start with {@code codePrefix}, or
     * {@code 0} if none does - so that a caller minting {@code codePrefix + (result + 1)} hands out
     * {@code 1} for an empty project.
     *
     * @param <C>        the caller's code type, typically its own value object
     * @param codePrefix the literal a code of this counter starts with (must not be {@code null})
     * @param codes      the codes to count over (must not be {@code null}, may be empty)
     * @param codeValue  reads the raw string out of one code (must not be {@code null})
     * @return the highest running number, or {@code 0}
     */
    public static <C> int highestRunningNumber(String codePrefix, Collection<C> codes,
            Function<? super C, String> codeValue) {
        Objects.requireNonNull(codePrefix, "codePrefix");
        Objects.requireNonNull(codes, "codes");
        Objects.requireNonNull(codeValue, "codeValue");

        return codes.stream()
                .map(codeValue)
                .mapToInt(value -> runningNumber(codePrefix, value))
                .max()
                .orElse(0);
    }
}
