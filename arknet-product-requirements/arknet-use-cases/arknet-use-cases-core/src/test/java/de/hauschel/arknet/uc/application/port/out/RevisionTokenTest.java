// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.out;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Compact-constructor invariant test for {@link RevisionToken}.
 */
class RevisionTokenTest {

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new RevisionToken(null));
    }
}
