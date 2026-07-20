// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Domain invariant tests for {@link RequirementSchemaTerm} (issue #31).
 */
class RequirementSchemaTermTest {

    @Test
    void holdsItsFields() {
        RequirementSchemaTerm term = new RequirementSchemaTerm(
                "Priority", "Priorisierung nach MoSCoW.", List.of("MUST_HAVE", "SHOULD_HAVE"));

        assertEquals("Priority", term.term());
        assertEquals("Priorisierung nach MoSCoW.", term.definition());
        assertEquals(List.of("MUST_HAVE", "SHOULD_HAVE"), term.values());
    }

    @Test
    void defensivelyCopiesValues() {
        List<String> mutable = new ArrayList<>(List.of("MUST_HAVE"));
        RequirementSchemaTerm term = new RequirementSchemaTerm("Priority", "d", mutable);

        mutable.add("SHOULD_HAVE");

        assertEquals(List.of("MUST_HAVE"), term.values());
    }

    @Test
    void rejectsBlankTerm() {
        assertThrows(IllegalArgumentException.class,
                () -> new RequirementSchemaTerm(" ", "d", List.of("X")));
    }

    @Test
    void rejectsBlankDefinition() {
        assertThrows(IllegalArgumentException.class,
                () -> new RequirementSchemaTerm("Priority", " ", List.of("X")));
    }

    @Test
    void rejectsNullValues() {
        assertThrows(NullPointerException.class,
                () -> new RequirementSchemaTerm("Priority", "d", null));
    }

    @Test
    void rejectsEmptyValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new RequirementSchemaTerm("Priority", "d", List.of()));
    }

    @Test
    void rejectsNullTerm() {
        assertThrows(NullPointerException.class,
                () -> new RequirementSchemaTerm(null, "d", List.of("X")));
    }

    @Test
    void rejectsNullDefinition() {
        assertThrows(NullPointerException.class,
                () -> new RequirementSchemaTerm("Priority", null, List.of("X")));
    }
}
