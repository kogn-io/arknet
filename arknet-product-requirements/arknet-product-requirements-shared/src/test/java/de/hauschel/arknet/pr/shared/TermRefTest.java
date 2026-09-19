// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.pr.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Domain invariant tests for {@link TermRef}: the opaque reference to a glossary term this
 * bounded context's resources carry, wrapping the shared-kernel {@link ResourceId} rather than a
 * ubiquitous-language-specific type.
 */
class TermRefTest {

    private static final ResourceId TERM_ID = ResourceId.of("https://w3id.org/arknet/id/term-1");

    @Test
    void holdsItsValue() {
        TermRef ref = new TermRef(TERM_ID);

        assertEquals(TERM_ID, ref.value());
    }

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new TermRef(null));
    }
}
