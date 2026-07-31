// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;

/** Invariants and transition rules of the {@link Adr} value object. */
class AdrTest {

    private static final AdrId ID = new AdrId(ResourceId.of("https://w3id.org/arknet/id/adr-1"));
    private static final AdrId OTHER = new AdrId(ResourceId.of("https://w3id.org/arknet/id/adr-2"));

    @Test
    void rejectsBlankMandatoryText() {
        assertThrows(IllegalArgumentException.class, () -> adr("  ", "context", "decision"));
        assertThrows(IllegalArgumentException.class, () -> adr("name", " ", "decision"));
        assertThrows(IllegalArgumentException.class, () -> adr("name", "context", ""));
    }

    @Test
    void rejectsBlankOptionalTextWhenPresent() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", "  ", null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null, "  ", null, null, null, null));
    }

    @Test
    void normalisesNullCollectionsToEmpty() {
        Adr adr = adr("name", "context", "decision");

        assertEquals(List.of(), adr.addressesRequirements());
        assertEquals(List.of(), adr.affectsContexts());
        assertEquals(List.of(), adr.supersedes());
    }

    @Test
    void rejectsDuplicateReferences() {
        RequirementRef ref = new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/fr-1"));

        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null, null, null, List.of(ref, ref), null, null));
    }

    /**
     * A decision that supersedes itself is a cycle of length one - the constructor rejects it rather
     * than letting {@code adr_supersede}'s own guard be the only thing standing between a caller and
     * an unreadable graph.
     */
    @Test
    void rejectsSupersedingItself() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null, null, null, null, null, List.of(ID)));
    }

    @Test
    void acceptTransitionsOnceAndIsIdempotent() {
        Adr proposed = adr("name", "context", "decision");

        Adr accepted = proposed.accept();

        assertEquals(AdrStatus.ACCEPTED, accepted.status());
        assertSame(accepted, accepted.accept());
    }

    @Test
    void supersedeAppendsAndIsIdempotent() {
        Adr adr = adr("name", "context", "decision");

        Adr once = adr.supersede(OTHER);

        assertEquals(List.of(OTHER), once.supersedes());
        assertSame(once, once.supersede(OTHER));
    }

    @Test
    void supersedeRejectsItsOwnIdentity() {
        Adr adr = adr("name", "context", "decision");

        assertThrows(IllegalArgumentException.class, () -> adr.supersede(ID));
    }

    private static Adr adr(String name, String context, String decision) {
        return new Adr(ID, new AdrCode("ADR-1"), name, AdrStatus.PROPOSED, context, decision,
                null, null, null, null, null, null);
    }
}
