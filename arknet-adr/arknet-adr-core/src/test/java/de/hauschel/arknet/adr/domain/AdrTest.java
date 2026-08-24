// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/** Invariants and transition rules of the {@link Adr} value object. */
class AdrTest {

    private static final AdrId ID = new AdrId(ResourceId.of("https://w3id.org/arknet/id/adr-1"));
    private static final AdrId OTHER = new AdrId(ResourceId.of("https://w3id.org/arknet/id/adr-2"));
    private static final ProjectId PROJECT = new ProjectId("test-project");

    @Test
    void rejectsBlankMandatoryText() {
        assertThrows(IllegalArgumentException.class, () -> adr("  ", "context", "decision"));
        assertThrows(IllegalArgumentException.class, () -> adr("name", " ", "decision"));
        assertThrows(IllegalArgumentException.class, () -> adr("name", "context", ""));
    }

    @Test
    void normalisesNullCollectionsToEmpty() {
        Adr adr = adr("name", "context", "decision");

        assertEquals(List.of(), adr.consequences());
        assertEquals(List.of(), adr.consideredOptions());
        assertEquals(List.of(), adr.addressesRequirements());
        assertEquals(List.of(), adr.affectsContexts());
        assertNull(adr.supersededBy());
        assertEquals(List.of(), adr.relatedTo());
    }

    @Test
    void rejectsDuplicateReferences() {
        RequirementRef ref = new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/fr-1"));

        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null, null, null, List.of(ref, ref), null, null, null));
    }

    @Test
    void rejectsSupersedingItself() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null, null, null, null, null, ID, null));
    }

    @Test
    void rejectsSupersededStatusWithoutSupersededByEdge() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.SUPERSEDED, "context", "decision", null, null, null, null, null, null, null));
    }

    @Test
    void rejectsSupersededByEdgeWithoutSupersededStatus() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.ACCEPTED, "context", "decision", null, null, null, null, null, OTHER, null));
    }

    @Test
    void permitsSupersededStatusTogetherWithSupersededByEdge() {
        Adr superseded = new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.SUPERSEDED, "context", "decision", null, null, null, null, null, OTHER, null);

        assertEquals(AdrStatus.SUPERSEDED, superseded.status());
        assertEquals(OTHER, superseded.supersededBy());
    }

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

    // --- consequences / considered options (kogn-io/arknet#357) ---------------

    @Test
    void rejectsAGapInConsequencePositions() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision",
                List.of(new Consequence(2, "text", ConsequenceType.NEUTRAL)), null, null, null, null, null, null));
    }

    @Test
    void rejectsADuplicateConsequencePosition() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision",
                List.of(new Consequence(1, "a", ConsequenceType.POSITIVE),
                        new Consequence(1, "b", ConsequenceType.NEGATIVE)),
                null, null, null, null, null, null));
    }

    @Test
    void rejectsAGapInConsideredOptionPositions() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null,
                List.of(new ConsideredOption(2, "name", "rationale", OptionOutcome.REJECTED)),
                null, null, null, null, null));
    }

    /** An empty consequence/considered-option list is legal - both are optional, unlike acceptanceCriterion. */
    @Test
    void permitsEmptyConsequenceAndConsideredOptionLists() {
        Adr adr = adr("name", "context", "decision");

        assertEquals(List.of(), adr.consequences());
        assertEquals(List.of(), adr.consideredOptions());
    }

    /**
     * At most one considered option may be {@link OptionOutcome#CHOSEN} - the domain-level half of
     * the invariant the SHACL {@code sh:qualifiedValueShape}/{@code sh:qualifiedMaxCount} enforces a
     * second time at the write gate (kogn-io/arknet#357). Mutation test: removing this check would
     * let two options both claim to be the decision's actual outcome.
     */
    @Test
    void rejectsMoreThanOneChosenConsideredOption() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null,
                List.of(new ConsideredOption(1, "A", "r1", OptionOutcome.CHOSEN),
                        new ConsideredOption(2, "B", "r2", OptionOutcome.CHOSEN)),
                null, null, null, null, null));
    }

    @Test
    void permitsExactlyOneChosenConsideredOptionAmongSeveral() {
        Adr adr = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.PROPOSED, "context", "decision", null,
                List.of(new ConsideredOption(1, "A", "r1", OptionOutcome.CHOSEN),
                        new ConsideredOption(2, "B", "r2", OptionOutcome.REJECTED)),
                null, null, null, null, null);

        assertEquals(2, adr.consideredOptions().size());
    }

    @Test
    void permitsZeroChosenConsideredOptions() {
        Adr adr = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.PROPOSED, "context", "decision", null,
                List.of(new ConsideredOption(1, "A", "r1", OptionOutcome.REJECTED)),
                null, null, null, null, null);

        assertEquals(1, adr.consideredOptions().size());
    }

    @Test
    void withAppendedConsequencesNumbersContinuingFromTheCurrentHighestPosition() {
        Adr adr = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.PROPOSED, "context", "decision",
                List.of(new Consequence(1, "first", ConsequenceType.POSITIVE)), null, null, null, null, null, null);

        Adr appended = adr.withAppendedConsequences(
                List.of(new NewConsequence("second", ConsequenceType.NEGATIVE)));

        assertEquals(List.of(
                new Consequence(1, "first", ConsequenceType.POSITIVE),
                new Consequence(2, "second", ConsequenceType.NEGATIVE)), appended.consequences());
    }

    /** Appending is never gated by status - a later-discovered consequence completes the record. */
    @Test
    void withAppendedConsequencesWorksOnAnAcceptedDecision() {
        Adr accepted = withStatus(AdrStatus.ACCEPTED);

        Adr appended = accepted.withAppendedConsequences(
                List.of(new NewConsequence("discovered later", ConsequenceType.NEGATIVE)));

        assertEquals(List.of(new Consequence(1, "discovered later", ConsequenceType.NEGATIVE)),
                appended.consequences());
        assertEquals(AdrStatus.ACCEPTED, appended.status());
    }

    @Test
    void withConsequenceCorrectionsPatchesOnlyTheNamedPositionWhileProposed() {
        Adr adr = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.PROPOSED, "context", "decision",
                List.of(new Consequence(1, "draft", ConsequenceType.NEUTRAL),
                        new Consequence(2, "other", ConsequenceType.NEGATIVE)),
                null, null, null, null, null, null);

        Adr patched = adr.withConsequenceCorrections(PROJECT,
                List.of(new ConsequenceCorrection(1, "sharper", ConsequenceType.POSITIVE)));

        assertEquals(List.of(
                new Consequence(1, "sharper", ConsequenceType.POSITIVE),
                new Consequence(2, "other", ConsequenceType.NEGATIVE)), patched.consequences());
    }

    @Test
    void withConsequenceCorrectionsThrowsForAnUnknownPosition() {
        Adr adr = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.PROPOSED, "context", "decision",
                List.of(new Consequence(1, "draft", ConsequenceType.NEUTRAL)), null, null, null, null, null, null);

        assertThrows(ConsequencePositionNotFoundException.class, () -> adr.withConsequenceCorrections(PROJECT,
                List.of(new ConsequenceCorrection(9, "x", ConsequenceType.NEUTRAL))));
    }

    /**
     * Deliberately narrower than {@link Adr#reviseText}: correcting an *existing* consequence is
     * locked once the decision is no longer PROPOSED, with no new-language exemption. Mutation test:
     * removing this status guard turns a rejected correction into a silently accepted one.
     */
    @Test
    void withConsequenceCorrectionsThrowsOnAnAcceptedDecision() {
        Adr accepted = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.ACCEPTED, "context", "decision",
                List.of(new Consequence(1, "draft", ConsequenceType.NEUTRAL)), null, null, null, null, null, null);

        assertThrows(AdrTextImmutableException.class, () -> accepted.withConsequenceCorrections(PROJECT,
                List.of(new ConsequenceCorrection(1, "rewritten", ConsequenceType.POSITIVE))));
    }

    /** A correction that changes nothing is a no-op in any status - mirrors {@code reviseText}. */
    @Test
    void withConsequenceCorrectionsIsANoOpWhenNothingChangesEvenWhenAccepted() {
        Adr accepted = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.ACCEPTED, "context", "decision",
                List.of(new Consequence(1, "draft", ConsequenceType.NEUTRAL)), null, null, null, null, null, null);

        Adr result = accepted.withConsequenceCorrections(PROJECT,
                List.of(new ConsequenceCorrection(1, "draft", ConsequenceType.NEUTRAL)));

        assertSame(accepted, result);
    }

    @Test
    void withAppendedConsideredOptionsWorksOnAnAcceptedDecision() {
        Adr accepted = withStatus(AdrStatus.ACCEPTED);

        Adr appended = accepted.withAppendedConsideredOptions(
                List.of(new NewConsideredOption("Discovered", "found later", OptionOutcome.REJECTED)));

        assertEquals(List.of(new ConsideredOption(1, "Discovered", "found later", OptionOutcome.REJECTED)),
                appended.consideredOptions());
    }

    /** Appending must still keep the at-most-one-Chosen invariant. */
    @Test
    void withAppendedConsideredOptionsRejectsASecondChosenOption() {
        Adr adr = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.PROPOSED, "context", "decision", null,
                List.of(new ConsideredOption(1, "A", "r1", OptionOutcome.CHOSEN)), null, null, null, null, null);

        assertThrows(IllegalArgumentException.class, () -> adr.withAppendedConsideredOptions(
                List.of(new NewConsideredOption("B", "r2", OptionOutcome.CHOSEN))));
    }

    @Test
    void withConsideredOptionCorrectionsThrowsOnAnAcceptedDecision() {
        Adr accepted = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.ACCEPTED, "context", "decision", null,
                List.of(new ConsideredOption(1, "A", "r1", OptionOutcome.REJECTED)), null, null, null, null, null);

        assertThrows(AdrTextImmutableException.class, () -> accepted.withConsideredOptionCorrections(PROJECT,
                List.of(new ConsideredOptionCorrection(1, "A", "rewritten", OptionOutcome.CHOSEN))));
    }

    @Test
    void withConsideredOptionCorrectionsThrowsForAnUnknownPosition() {
        Adr adr = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.PROPOSED, "context", "decision", null,
                List.of(new ConsideredOption(1, "A", "r1", OptionOutcome.REJECTED)), null, null, null, null, null);

        assertThrows(ConsideredOptionPositionNotFoundException.class, () -> adr.withConsideredOptionCorrections(
                PROJECT, List.of(new ConsideredOptionCorrection(9, "x", "y", OptionOutcome.REJECTED))));
    }

    @Test
    void acceptTransitionsOnceAndIsIdempotent() {
        Adr proposed = adr("name", "context", "decision");

        Adr accepted = proposed.accept();

        assertEquals(AdrStatus.ACCEPTED, accepted.status());
        assertSame(accepted, accepted.accept());
    }

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
    void supersededByTransitionsToSupersededAndIsIdempotent() {
        Adr accepted = withStatus(AdrStatus.ACCEPTED);

        Adr superseded = accepted.supersededBy(OTHER);

        assertEquals(AdrStatus.SUPERSEDED, superseded.status());
        assertEquals(OTHER, superseded.supersededBy());
        assertSame(superseded, superseded.supersededBy(OTHER));
    }

    @Test
    void supersededByThrowsWhenNotAccepted() {
        Adr proposed = adr("name", "context", "decision");
        Adr rejected = withStatus(AdrStatus.REJECTED);
        Adr deprecated = withStatus(AdrStatus.DEPRECATED);
        Adr alreadySuperseded = withStatus(AdrStatus.ACCEPTED).supersededBy(OTHER);

        assertThrows(IllegalStateException.class, () -> proposed.supersededBy(OTHER));
        assertThrows(IllegalStateException.class, () -> rejected.supersededBy(OTHER));
        assertThrows(IllegalStateException.class, () -> deprecated.supersededBy(OTHER));
        assertThrows(IllegalStateException.class,
                () -> alreadySuperseded.supersededBy(new AdrId(ResourceId.of("https://w3id.org/arknet/id/adr-3"))));
    }

    @Test
    void supersededByRejectsItsOwnIdentity() {
        Adr accepted = withStatus(AdrStatus.ACCEPTED);

        assertThrows(IllegalArgumentException.class, () -> accepted.supersededBy(ID));
    }

    @Test
    void reviseTextCorrectsEveryFieldWhileProposed() {
        Adr proposed = adr("name", "context", "decision");

        Adr revised = proposed.reviseText("Better name", "Sharper context", "Sharper decision",
                LocalDate.of(2026, 8, 23), false);

        assertEquals("Better name", revised.name());
        assertEquals("Sharper context", revised.context());
        assertEquals("Sharper decision", revised.decision());
        assertEquals(LocalDate.of(2026, 8, 23), revised.decisionDate());
        assertEquals(AdrStatus.PROPOSED, revised.status());
    }

    /**
     * The rule this whole port exists to state: a decision in force records what was decided at the
     * time, so an existing-language text is not editable. All four non-{@code PROPOSED} states are
     * pinned.
     */
    @Test
    void reviseTextThrowsFromEveryStatusButProposedForASameLanguageChange() {
        for (AdrStatus status : List.of(AdrStatus.ACCEPTED, AdrStatus.REJECTED, AdrStatus.DEPRECATED,
                AdrStatus.SUPERSEDED)) {
            Adr inForce = withStatus(status);

            AdrTextImmutableException thrown = assertThrows(AdrTextImmutableException.class,
                    () -> inForce.reviseText("Better name", "context", "decision", null, false));

            assertEquals(status, thrown.status());
            assertEquals(new AdrCode("ADR-1"), thrown.adrCode());
            assertTrue(thrown.getMessage().contains("adr_supersede"), thrown.getMessage());
        }
    }

    /**
     * kogn-io/arknet#357: a call that genuinely adds a language none of the three fields carries yet
     * is exempt from the status gate, even on a decision already in force - it makes the decision
     * accessible in a new language, it does not change what was decided. Mutation test: removing the
     * {@code newLanguageVariant} branch in {@code reviseText} turns this into an unexpected throw.
     */
    @Test
    void reviseTextAllowsANewLanguageVariantEvenWhenAccepted() {
        Adr accepted = withStatus(AdrStatus.ACCEPTED);

        Adr revised = accepted.reviseText("Ein neuer Titel", "context", "decision", null, true);

        assertEquals("Ein neuer Titel", revised.name());
        assertEquals(AdrStatus.ACCEPTED, revised.status());
    }

    /**
     * The mirror image: {@code newLanguageVariant=true} does not blanket-exempt every call - a
     * decisionDate change bundled into the same call is still gated (decisionDate carries no
     * language of its own, so it is treated like any other changed scalar).
     */
    @Test
    void reviseTextIsANoOpWithIdenticalValuesEvenWhenAccepted() {
        Adr accepted = withStatus(AdrStatus.ACCEPTED);

        assertSame(accepted, accepted.reviseText("name", "context", "decision", null, false));
        assertSame(accepted, accepted.reviseText("name", "context", "decision", null, true));
    }

    @Test
    void reviseTextReportsImmutabilityRatherThanTheInvalidValueWhenAccepted() {
        Adr accepted = withStatus(AdrStatus.ACCEPTED);

        assertThrows(AdrTextImmutableException.class,
                () -> accepted.reviseText("  ", "context", "decision", null, false));
    }

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

    /**
     * Builds a decision in {@code status}, satisfying the bi-implication for
     * {@link AdrStatus#SUPERSEDED}: only that one status carries a non-{@code null}
     * {@code supersededBy} (set to {@link #OTHER}), every other status carries {@code null}.
     */
    private static Adr withStatus(AdrStatus status) {
        AdrId supersededBy = status == AdrStatus.SUPERSEDED ? OTHER : null;
        return new Adr(ID, new AdrCode("ADR-1"), "name", status, "context", "decision",
                null, null, null, null, null, supersededBy, null);
    }
}
