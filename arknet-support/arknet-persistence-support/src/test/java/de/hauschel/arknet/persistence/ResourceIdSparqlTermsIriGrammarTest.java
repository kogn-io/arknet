// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.hauschel.arknet.kernel.ResourceId;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Drift guard between two independently-maintained "characters forbidden in an IRIREF" checks:
 * {@link ResourceId#of(String)} (in {@code arknet-shared-kernel}) and
 * {@link SparqlTerms#isValidIriReference(String)} (in this module).
 *
 * <p>The duplication is deliberate (the kernel must stay dependency-free, see
 * {@code arknet-shared-kernel}'s module description), but nothing else keeps the two character
 * sets in lockstep. Without this test, a grammar fix applied to only one of them would surface as
 * a confusing, far-from-the-input-site failure deep inside a {@code KognioRdf*Repository} write
 * path instead of an early, clear rejection at the domain boundary.</p>
 *
 * <p>Every fixture shares the {@code https://example.org/} prefix so that {@link ResourceId}'s
 * additional {@code https://} scheme requirement (which {@link SparqlTerms} does not enforce,
 * since it validates only the {@code IRIREF} body, not a scheme) never affects the comparison -
 * this test isolates agreement on the forbidden-character set alone.</p>
 *
 * <p>This lives in {@code arknet-persistence-support}, not {@code arknet-shared-kernel}: the
 * dependency direction allows it ({@code arknet-persistence-support} may depend on
 * {@code arknet-shared-kernel} in test scope without violating the kernel's dependency-freedom),
 * and the reverse would not compile.</p>
 */
class ResourceIdSparqlTermsIriGrammarTest {

    private static final String PREFIX = "https://example.org/";

    static Stream<Arguments> candidates() {
        return Stream.of(
                Arguments.of("ordinary IRI", PREFIX + "thing"),
                Arguments.of("less-than", PREFIX + "<thing"),
                Arguments.of("greater-than", PREFIX + ">thing"),
                Arguments.of("double quote", PREFIX + "\"thing"),
                Arguments.of("open brace", PREFIX + "{thing"),
                Arguments.of("close brace", PREFIX + "}thing"),
                Arguments.of("pipe", PREFIX + "|thing"),
                Arguments.of("caret", PREFIX + "^thing"),
                Arguments.of("backtick", PREFIX + "`thing"),
                Arguments.of("backslash", PREFIX + "\\thing"),
                Arguments.of("space", PREFIX + " thing"),
                Arguments.of("control character", PREFIX + "\u0001thing"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("candidates")
    void resourceIdAndSparqlTermsAgreeOnIriGrammar(final String label, final String candidate) {
        final boolean sparqlTermsAccepts = SparqlTerms.isValidIriReference(candidate);
        final boolean resourceIdAccepts = isAcceptedByResourceId(candidate);

        assertEquals(sparqlTermsAccepts, resourceIdAccepts,
                "SparqlTerms.isValidIriReference() and ResourceId.of() disagree for " + label + ": " + candidate);
    }

    private static boolean isAcceptedByResourceId(final String candidate) {
        try {
            ResourceId.of(candidate);
            return true;
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }
}
