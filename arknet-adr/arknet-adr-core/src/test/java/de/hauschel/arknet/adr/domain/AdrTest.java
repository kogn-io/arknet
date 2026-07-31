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

    /**
     * Accepting a rejected or deprecated decision would silently resurrect it - now that those are
     * real terminal-ish states, {@code accept()} must refuse rather than accept from anywhere but
     * {@code PROPOSED}.
     */
    @Test
    void acceptThrowsFromRejectedOrDeprecated() {
        Adr rejected = withStatus(AdrStatus.REJECTED);
        Adr deprecated = withStatus(AdrStatus.DEPRECATED);

        assertThrows(IllegalStateException.class, rejected::accept);
        assertThrows(IllegalStateException.class, deprecated::accept);
    }

    @Test
    void rejectTransitionsOnceAndIsIdempotent() {
        Adr proposed = adr("name", "context", "decision");

        Adr rejected = proposed.reject();

        assertEquals(AdrStatus.REJECTED, rejected.status());
        assertSame(rejected, rejected.reject());
    }

    @Test
    void rejectThrowsFromAcceptedOrDeprecated() {
        Adr accepted = withStatus(AdrStatus.ACCEPTED);
        Adr deprecated = withStatus(AdrStatus.DEPRECATED);

        assertThrows(IllegalStateException.class, accepted::reject);
        assertThrows(IllegalStateException.class, deprecated::reject);
    }

    @Test
    void deprecateTransitionsOnceAndIsIdempotent() {
        Adr accepted = withStatus(AdrStatus.ACCEPTED);

        Adr deprecated = accepted.deprecate();

        assertEquals(AdrStatus.DEPRECATED, deprecated.status());
        assertSame(deprecated, deprecated.deprecate());
    }

    @Test
    void deprecateThrowsFromProposedOrRejected() {
        Adr proposed = adr("name", "context", "decision");
        Adr rejected = withStatus(AdrStatus.REJECTED);

        assertThrows(IllegalStateException.class, proposed::deprecate);
        assertThrows(IllegalStateException.class, rejected::deprecate);
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

    private static Adr withStatus(AdrStatus status) {
        return new Adr(ID, new AdrCode("ADR-1"), "name", status, "context", "decision",
                null, null, null, null, null, null);
    }
}
