// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;

/** Invariants of the {@link BoundedContext} value object around its two term edges (kogn-io/arknet#610). */
class BoundedContextTest {

    private static final BoundedContextId ID =
            new BoundedContextId(ResourceId.of("https://w3id.org/arknet/id/bc-1"));
    private static final BoundedContextCode CODE = new BoundedContextCode("BC-1");
    private static final ResourceId TERM_1 = ResourceId.of("https://w3id.org/arknet/id/term-1");
    private static final ResourceId TERM_2 = ResourceId.of("https://w3id.org/arknet/id/term-2");

    @Test
    void aTermCannotBeBothALanguageTermAndADelimitedTerm() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedContext(ID, CODE, "Name", "Vision",
                null, null, List.of(TERM_1), List.of(TERM_1)));
    }

    @Test
    void theSevenArgumentFormDelimitsNothing() {
        assertEquals(List.of(), new BoundedContext(ID, CODE, "Name", "Vision", null, null, List.of(TERM_1))
                .delimitedTerms());
    }

    @Test
    void withTermsReplacesOnlyTheNamedRelation() {
        BoundedContext bc = new BoundedContext(ID, CODE, "Name", "Vision", null, null, List.of(TERM_1), null);

        BoundedContext delimited = bc.withTerms(TermRelation.DELIMITS, List.of(TERM_2));

        assertEquals(List.of(TERM_1), delimited.terms(TermRelation.USES));
        assertEquals(List.of(TERM_2), delimited.terms(TermRelation.DELIMITS));
    }
}
