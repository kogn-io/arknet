// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
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
                AdrStatus.PROPOSED, "context", "decision", "  ", null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null, "  ", null, null, null, null, null));
    }

    @Test
    void normalisesNullCollectionsToEmpty() {
        Adr adr = adr("name", "context", "decision");

        assertEquals(List.of(), adr.addressesRequirements());
        assertEquals(List.of(), adr.affectsContexts());
        assertEquals(List.of(), adr.supersedes());
        assertEquals(List.of(), adr.relatedTo());
    }

    @Test
    void rejectsDuplicateReferences() {
        RequirementRef ref = new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/fr-1"));

        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null, null, null, List.of(ref, ref), null, null, null));
    }

    /**
     * A decision that supersedes itself is a cycle of length one - the constructor rejects it rather
     * than letting {@code adr_supersede}'s own guard be the only thing standing between a caller and
     * an unreadable graph.
     */
    @Test
    void rejectsSupersedingItself() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null, null, null, null, null, List.of(ID), null));
    }

    /**
     * A "see also" edge onto the decision itself says nothing, and the merged reading of the
     * relation (see {@code AdrDetail}) would report the decision as its own peer. Refused in the
     * constructor rather than only in the service, exactly as superseding itself is.
     */
    @Test
    void rejectsBeingRelatedToItself() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null, null, null, null, null, null,
                List.of(ID)));
    }

    @Test
    void rejectsDuplicateRelatedToEntries() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null, null, null, null, null, null,
                List.of(OTHER, OTHER)));
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

    @Test
    void reviseTextCorrectsEveryFieldWhileProposed() {
        Adr proposed = adr("name", "context", "decision");

        Adr revised = proposed.reviseText("Better name", "Sharper context", "Sharper decision",
                "What follows", "What else was weighed", LocalDate.of(2026, 8, 23));

        assertEquals("Better name", revised.name());
        assertEquals("Sharper context", revised.context());
        assertEquals("Sharper decision", revised.decision());
        assertEquals("What follows", revised.consequences());
        assertEquals("What else was weighed", revised.alternatives());
        assertEquals(LocalDate.of(2026, 8, 23), revised.decisionDate());
        assertEquals(AdrStatus.PROPOSED, revised.status());
    }

    /**
     * The rule this whole port exists to state: a decision in force records what was decided at the
     * time, so its text is not editable. All three non-{@code PROPOSED} states are pinned, not just
     * {@code ACCEPTED} - a rejected or deprecated decision is just as much a record of its own past.
     */
    @Test
    void reviseTextThrowsFromEveryStatusButProposed() {
        for (AdrStatus status : List.of(AdrStatus.ACCEPTED, AdrStatus.REJECTED, AdrStatus.DEPRECATED)) {
            Adr inForce = withStatus(status);

            AdrTextImmutableException thrown = assertThrows(AdrTextImmutableException.class,
                    () -> inForce.reviseText("Better name", "context", "decision", null, null, null));

            assertEquals(status, thrown.status());
            assertEquals(new AdrCode("ADR-1"), thrown.adrCode());
            assertTrue(thrown.getMessage().contains("adr_supersede"), thrown.getMessage());
        }
    }

    /**
     * Load-bearing, not a convenience: a correction that only touches the references travels through
     * {@code reviseText} with every text value unchanged, so a no-op must pass in any status - if it
     * threw, correcting an accepted decision's edges would be impossible.
     */
    @Test
    void reviseTextIsANoOpWithIdenticalValuesEvenWhenAccepted() {
        Adr accepted = withStatus(AdrStatus.ACCEPTED);

        assertSame(accepted, accepted.reviseText("name", "context", "decision", null, null, null));
    }

    /**
     * The status is checked <em>after</em> the comparison, so an accepted decision handed an invalid
     * value is refused for the reason the call could never succeed (immutable), not for the value.
     */
    @Test
    void reviseTextReportsImmutabilityRatherThanTheInvalidValueWhenAccepted() {
        Adr accepted = withStatus(AdrStatus.ACCEPTED);

        assertThrows(AdrTextImmutableException.class,
                () -> accepted.reviseText("  ", "context", "decision", null, null, null));
    }

    /**
     * The deliberate exception to the immutability rule: an edge that could not be written when the
     * decision was recorded stays completable afterwards, in every status - the same licence
     * {@code supersede} already takes.
     */
    @Test
    void reviseReferencesReplacesInEveryStatusIncludingAccepted() {
        RequirementRef requirement = new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/fr-1"));
        BoundedContextRef boundedContext =
                new BoundedContextRef(ResourceId.of("https://w3id.org/arknet/id/bc-1"));

        for (AdrStatus status : AdrStatus.values()) {
            Adr revised = withStatus(status)
                    .reviseReferences(List.of(requirement), List.of(boundedContext), List.of());

            assertEquals(List.of(requirement), revised.addressesRequirements());
            assertEquals(List.of(boundedContext), revised.affectsContexts());
            assertEquals(status, revised.status());
        }
    }

    /**
     * {@code relatedTo} is not text: it names a peer decision, and the peer may well have been
     * recorded after this decision was accepted. Freezing the edge with the prose would leave a
     * decision in force permanently unable to point at it.
     */
    @Test
    void reviseReferencesSetsAndClearsRelatedToInEveryStatusIncludingAccepted() {
        for (AdrStatus status : AdrStatus.values()) {
            Adr linked = withStatus(status).reviseReferences(List.of(), List.of(), List.of(OTHER));

            assertEquals(List.of(OTHER), linked.relatedTo());
            assertEquals(status, linked.status());

            Adr cleared = linked.reviseReferences(List.of(), List.of(), List.of());

            assertEquals(List.of(), cleared.relatedTo());
            assertEquals(status, cleared.status());
        }
    }

    @Test
    void reviseReferencesRejectsRelatingADecisionToItself() {
        Adr accepted = withStatus(AdrStatus.ACCEPTED);

        assertThrows(IllegalArgumentException.class,
                () -> accepted.reviseReferences(List.of(), List.of(), List.of(ID)));
    }

    @Test
    void reviseReferencesClearsEveryRelationWhenGivenEmptyLists() {
        RequirementRef requirement = new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/fr-1"));
        BoundedContextRef boundedContext =
                new BoundedContextRef(ResourceId.of("https://w3id.org/arknet/id/bc-1"));
        Adr linked = withStatus(AdrStatus.ACCEPTED)
                .reviseReferences(List.of(requirement), List.of(boundedContext), List.of(OTHER));

        Adr cleared = linked.reviseReferences(List.of(), List.of(), List.of());

        assertEquals(List.of(), cleared.addressesRequirements());
        assertEquals(List.of(), cleared.affectsContexts());
        assertEquals(List.of(), cleared.relatedTo());
    }

    @Test
    void reviseReferencesIsANoOpWhenEveryListAlreadyMatches() {
        RequirementRef requirement = new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/fr-1"));
        Adr linked = adr("name", "context", "decision")
                .reviseReferences(List.of(requirement), List.of(), List.of());

        assertSame(linked, linked.reviseReferences(List.of(requirement), List.of(), List.of()));
    }

    private static Adr adr(String name, String context, String decision) {
        return new Adr(ID, new AdrCode("ADR-1"), name, AdrStatus.PROPOSED, context, decision,
                null, null, null, null, null, null, null);
    }

    private static Adr withStatus(AdrStatus status) {
        return new Adr(ID, new AdrCode("ADR-1"), "name", status, "context", "decision",
                null, null, null, null, null, null, null);
    }
}
