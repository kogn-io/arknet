// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/** Invariants and transition rules of the {@link Adr} value object. */
class AdrTest {

    private static final AdrId ID = new AdrId(ResourceId.of("https://w3id.org/arknet/id/adr-1"));
    private static final AdrId OTHER = new AdrId(ResourceId.of("https://w3id.org/arknet/id/adr-2"));
    private static final ProjectId PROJECT = new ProjectId("test-project");
    /** A fixed day, so a stamped decision date is asserted against a value and not against "today". */
    private static final LocalDate DECIDED_ON = LocalDate.of(2026, 8, 23);

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
        assertEquals(List.of(), adr.usesTerms());
        assertNull(adr.supersededBy());
        assertEquals(List.of(), adr.relatedTo());
    }

    @Test
    void rejectsDuplicateReferences() {
        RequirementRef ref = new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/fr-1"));

        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null, null, null, List.of(ref, ref), null, null, null,
                null));
    }

    /** {@link #rejectsDuplicateReferences} for {@code usesTerms} (kogn-io/arknet#393). */
    @Test
    void rejectsDuplicateUsesTermsReferences() {
        TermRef ref = new TermRef(ResourceId.of("https://w3id.org/arknet/id/term-1"));

        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null, null, null, null, null, List.of(ref, ref), null,
                null));
    }

    @Test
    void rejectsSupersedingItself() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null, null, null, null, null, null, ID, null));
    }

    @Test
    void rejectsSupersededStatusWithoutSupersededByEdge() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.SUPERSEDED, "context", "decision", null, null, null, null, null, null, null, null));
    }

    @Test
    void rejectsSupersededByEdgeWithoutSupersededStatus() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.ACCEPTED, "context", "decision", null, null, null, null, null, null, OTHER, null));
    }

    @Test
    void permitsSupersededStatusTogetherWithSupersededByEdge() {
        Adr superseded = new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.SUPERSEDED, "context", "decision", null, null, null, null, null, null, OTHER, null);

        assertEquals(AdrStatus.SUPERSEDED, superseded.status());
        assertEquals(OTHER, superseded.supersededBy());
    }

    @Test
    void rejectsBeingRelatedToItself() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null, null, null, null, null, null, null,
                List.of(ID)));
    }

    @Test
    void rejectsDuplicateRelatedToEntries() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null, null, null, null, null, null, null,
                List.of(OTHER, OTHER)));
    }

    // --- consequences / considered options (kogn-io/arknet#357) ---------------

    @Test
    void rejectsAGapInConsequencePositions() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision",
                List.of(new Consequence(2, "text", ConsequenceType.NEUTRAL)), null, null, null, null, null, null,
                null));
    }

    @Test
    void rejectsADuplicateConsequencePosition() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision",
                List.of(new Consequence(1, "a", ConsequenceType.POSITIVE),
                        new Consequence(1, "b", ConsequenceType.NEGATIVE)),
                null, null, null, null, null, null, null));
    }

    @Test
    void rejectsAGapInConsideredOptionPositions() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null,
                List.of(new ConsideredOption(2, "name", "rationale", OptionOutcome.REJECTED)),
                null, null, null, null, null, null));
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
     * the invariant the SHACL write gate's {@code ashapes:ADR-consideredOption-atMostOneChosen}
     * enforces a second time (kogn-io/arknet#357; a {@code sh:sparql}-based constraint since
     * kogn-io/arknet#376, not the original {@code sh:qualifiedValueShape}/{@code sh:qualifiedMaxCount}
     * form). Mutation test: removing this check would let two options both claim to be the
     * decision's actual outcome.
     */
    @Test
    void rejectsMoreThanOneChosenConsideredOption() {
        assertThrows(IllegalArgumentException.class, () -> new Adr(ID, new AdrCode("ADR-1"), "name",
                AdrStatus.PROPOSED, "context", "decision", null,
                List.of(new ConsideredOption(1, "A", "r1", OptionOutcome.CHOSEN),
                        new ConsideredOption(2, "B", "r2", OptionOutcome.CHOSEN)),
                null, null, null, null, null, null));
    }

    @Test
    void permitsExactlyOneChosenConsideredOptionAmongSeveral() {
        Adr adr = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.PROPOSED, "context", "decision", null,
                List.of(new ConsideredOption(1, "A", "r1", OptionOutcome.CHOSEN),
                        new ConsideredOption(2, "B", "r2", OptionOutcome.REJECTED)),
                null, null, null, null, null, null);

        assertEquals(2, adr.consideredOptions().size());
    }

    @Test
    void permitsZeroChosenConsideredOptions() {
        Adr adr = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.PROPOSED, "context", "decision", null,
                List.of(new ConsideredOption(1, "A", "r1", OptionOutcome.REJECTED)),
                null, null, null, null, null, null);

        assertEquals(1, adr.consideredOptions().size());
    }

    @Test
    void withAppendedConsequencesNumbersContinuingFromTheCurrentHighestPosition() {
        Adr adr = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.PROPOSED, "context", "decision",
                List.of(new Consequence(1, "first", ConsequenceType.POSITIVE)), null, null, null, null, null, null,
                null);

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
                null, null, null, null, null, null, null);

        Adr patched = adr.withConsequenceCorrections(PROJECT,
                List.of(new ConsequenceCorrection(1, "sharper", ConsequenceType.POSITIVE)), Set.of());

        assertEquals(List.of(
                new Consequence(1, "sharper", ConsequenceType.POSITIVE),
                new Consequence(2, "other", ConsequenceType.NEGATIVE)), patched.consequences());
    }

    @Test
    void withConsequenceCorrectionsThrowsForAnUnknownPosition() {
        Adr adr = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.PROPOSED, "context", "decision",
                List.of(new Consequence(1, "draft", ConsequenceType.NEUTRAL)), null, null, null, null, null, null,
                null);

        assertThrows(ConsequencePositionNotFoundException.class, () -> adr.withConsequenceCorrections(PROJECT,
                List.of(new ConsequenceCorrection(9, "x", ConsequenceType.NEUTRAL)), Set.of()));
    }

    /**
     * Correcting an *existing* consequence's wording in a language it already carries is locked once
     * the decision is no longer PROPOSED - {@code newLanguageVariantPositions} is empty, so position 1
     * is not exempt. Mutation test: removing this status guard turns a rejected correction into a
     * silently accepted one.
     */
    @Test
    void withConsequenceCorrectionsThrowsOnAnAcceptedDecision() {
        Adr accepted = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.ACCEPTED, "context", "decision",
                List.of(new Consequence(1, "draft", ConsequenceType.NEUTRAL)), null, null, null, null, null, null,
                null);

        assertThrows(AdrTextImmutableException.class, () -> accepted.withConsequenceCorrections(PROJECT,
                List.of(new ConsequenceCorrection(1, "rewritten", ConsequenceType.POSITIVE)), Set.of()));
    }

    /**
     * kogn-io/arknet#357's follow-up: a correction that only changes {@code statement} at a position
     * named in {@code newLanguageVariantPositions} is exempt from the status gate, even on an accepted
     * decision - it adds a translation, it does not change what was decided. Mutation test: removing
     * the exemption branch in {@code withConsequenceCorrections} turns this into an unexpected throw.
     */
    @Test
    void withConsequenceCorrectionsAllowsANewLanguageVariantEvenWhenAccepted() {
        Adr accepted = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.ACCEPTED, "context", "decision",
                List.of(new Consequence(1, "draft", ConsequenceType.NEUTRAL)), null, null, null, null, null, null,
                null);

        Adr patched = accepted.withConsequenceCorrections(PROJECT,
                List.of(new ConsequenceCorrection(1, "Entwurf", ConsequenceType.NEUTRAL)), Set.of(1));

        assertEquals(List.of(new Consequence(1, "Entwurf", ConsequenceType.NEUTRAL)), patched.consequences());
        assertEquals(AdrStatus.ACCEPTED, patched.status());
    }

    /**
     * The mirror image: a position in {@code newLanguageVariantPositions} does not exempt a
     * {@code type} change bundled into the same correction - classifying a consequence as
     * positive/negative/neutral is a judgement about the decision, not a fact of its wording, so a
     * type change is gated regardless of language. Mutation test: dropping the {@code typeChanged}
     * check turns this into an unexpected pass.
     */
    @Test
    void withConsequenceCorrectionsRejectsATypeChangeEvenWithANewLanguageVariant() {
        Adr accepted = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.ACCEPTED, "context", "decision",
                List.of(new Consequence(1, "draft", ConsequenceType.NEUTRAL)), null, null, null, null, null, null,
                null);

        assertThrows(AdrTextImmutableException.class, () -> accepted.withConsequenceCorrections(PROJECT,
                List.of(new ConsequenceCorrection(1, "Entwurf", ConsequenceType.POSITIVE)), Set.of(1)));
    }

    /** A correction that changes nothing is a no-op in any status - mirrors {@code reviseText}. */
    @Test
    void withConsequenceCorrectionsIsANoOpWhenNothingChangesEvenWhenAccepted() {
        Adr accepted = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.ACCEPTED, "context", "decision",
                List.of(new Consequence(1, "draft", ConsequenceType.NEUTRAL)), null, null, null, null, null, null,
                null);

        Adr result = accepted.withConsequenceCorrections(PROJECT,
                List.of(new ConsequenceCorrection(1, "draft", ConsequenceType.NEUTRAL)), Set.of());

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
                List.of(new ConsideredOption(1, "A", "r1", OptionOutcome.CHOSEN)), null, null, null, null, null,
                null);

        assertThrows(IllegalArgumentException.class, () -> adr.withAppendedConsideredOptions(
                List.of(new NewConsideredOption("B", "r2", OptionOutcome.CHOSEN))));
    }

    /**
     * Correcting an *existing* option's wording in a language it already carries is locked once the
     * decision is no longer PROPOSED - {@code newLanguageVariantPositions} is empty, so position 1 is
     * not exempt (this correction also changes {@code outcome}, which would gate it regardless).
     */
    @Test
    void withConsideredOptionCorrectionsThrowsOnAnAcceptedDecision() {
        Adr accepted = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.ACCEPTED, "context", "decision", null,
                List.of(new ConsideredOption(1, "A", "r1", OptionOutcome.REJECTED)), null, null, null, null, null,
                null);

        assertThrows(AdrTextImmutableException.class, () -> accepted.withConsideredOptionCorrections(PROJECT,
                List.of(new ConsideredOptionCorrection(1, "A", "rewritten", OptionOutcome.CHOSEN)), Set.of()));
    }

    @Test
    void withConsideredOptionCorrectionsThrowsForAnUnknownPosition() {
        Adr adr = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.PROPOSED, "context", "decision", null,
                List.of(new ConsideredOption(1, "A", "r1", OptionOutcome.REJECTED)), null, null, null, null, null,
                null);

        assertThrows(ConsideredOptionPositionNotFoundException.class, () -> adr.withConsideredOptionCorrections(
                PROJECT, List.of(new ConsideredOptionCorrection(9, "x", "y", OptionOutcome.REJECTED)), Set.of()));
    }

    /**
     * kogn-io/arknet#357's follow-up, {@code ConsideredOption}'s counterpart to
     * {@code withConsequenceCorrectionsAllowsANewLanguageVariantEvenWhenAccepted}: a correction that
     * only changes {@code name}/{@code rationale} at a position named in
     * {@code newLanguageVariantPositions} is exempt, even on an accepted decision.
     */
    @Test
    void withConsideredOptionCorrectionsAllowsANewLanguageVariantEvenWhenAccepted() {
        Adr accepted = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.ACCEPTED, "context", "decision", null,
                List.of(new ConsideredOption(1, "A", "r1", OptionOutcome.REJECTED)), null, null, null, null, null,
                null);

        Adr patched = accepted.withConsideredOptionCorrections(PROJECT,
                List.of(new ConsideredOptionCorrection(1, "Ein A", "r1-de", OptionOutcome.REJECTED)), Set.of(1));

        assertEquals(List.of(new ConsideredOption(1, "Ein A", "r1-de", OptionOutcome.REJECTED)),
                patched.consideredOptions());
        assertEquals(AdrStatus.ACCEPTED, patched.status());
    }

    /**
     * The mirror image: a position in {@code newLanguageVariantPositions} does not exempt an
     * {@code outcome} change - whether an option was chosen or rejected is a judgement about the
     * decision, not a fact of its wording.
     */
    @Test
    void withConsideredOptionCorrectionsRejectsAnOutcomeChangeEvenWithANewLanguageVariant() {
        Adr accepted = new Adr(ID, new AdrCode("ADR-1"), "name", AdrStatus.ACCEPTED, "context", "decision", null,
                List.of(new ConsideredOption(1, "A", "r1", OptionOutcome.REJECTED)), null, null, null, null, null,
                null);

        assertThrows(AdrTextImmutableException.class, () -> accepted.withConsideredOptionCorrections(PROJECT,
                List.of(new ConsideredOptionCorrection(1, "Ein A", "r1-de", OptionOutcome.CHOSEN)), Set.of(1)));
    }

    @Test
    void acceptTransitionsOnceAndIsIdempotent() {
        Adr proposed = adr("name", "context", "decision");

        Adr accepted = proposed.accept(DECIDED_ON);

        assertEquals(AdrStatus.ACCEPTED, accepted.status());
        assertSame(accepted, accepted.accept(DECIDED_ON));
    }

    /**
     * The invariant kogn-io/arknet#374 buys: a decision has no date until it is one. Before the
     * transition nothing can have put a date there ({@code adr_add}/{@code adr_update} no longer
     * carry the field at all), and the transition itself is what records it.
     */
    @Test
    void acceptStampsTheDecisionDateOnADecisionThatHadNone() {
        Adr proposed = adr("name", "context", "decision");
        assertNull(proposed.decisionDate());

        assertEquals(DECIDED_ON, proposed.accept(DECIDED_ON).decisionDate());
    }

    /**
     * A decision is made once. Re-accepting an already-accepted one is a no-op returning the very
     * same instance, so a second call on a later day cannot quietly move the date it was decided on.
     */
    @Test
    void acceptDoesNotRestampAnAlreadyAcceptedDecision() {
        Adr accepted = adr("name", "context", "decision").accept(DECIDED_ON);

        Adr again = accepted.accept(DECIDED_ON.plusDays(30));

        assertSame(accepted, again);
        assertEquals(DECIDED_ON, again.decisionDate());
    }

    @Test
    void acceptRequiresADecisionDate() {
        Adr proposed = adr("name", "context", "decision");

        assertThrows(NullPointerException.class, () -> proposed.accept(null));
    }

    @Test
    void acceptThrowsFromRejectedOrDeprecated() {
        Adr rejected = withStatus(AdrStatus.REJECTED);
        Adr deprecated = withStatus(AdrStatus.DEPRECATED);

        assertThrows(IllegalStateException.class, () -> rejected.accept(DECIDED_ON));
        assertThrows(IllegalStateException.class, () -> deprecated.accept(DECIDED_ON));
    }

    @Test
    void rejectTransitionsOnceAndIsIdempotent() {
        Adr proposed = adr("name", "context", "decision");

        Adr rejected = proposed.reject(DECIDED_ON);

        assertEquals(AdrStatus.REJECTED, rejected.status());
        assertSame(rejected, rejected.reject(DECIDED_ON));
    }

    /** Turning an option down is a decision made on a day too - same rule as {@code accept}. */
    @Test
    void rejectStampsTheDecisionDateOnADecisionThatHadNone() {
        Adr proposed = adr("name", "context", "decision");
        assertNull(proposed.decisionDate());

        assertEquals(DECIDED_ON, proposed.reject(DECIDED_ON).decisionDate());
    }

    @Test
    void rejectRequiresADecisionDate() {
        Adr proposed = adr("name", "context", "decision");

        assertThrows(NullPointerException.class, () -> proposed.reject(null));
    }

    @Test
    void rejectThrowsFromAcceptedOrDeprecated() {
        Adr accepted = withStatus(AdrStatus.ACCEPTED);
        Adr deprecated = withStatus(AdrStatus.DEPRECATED);

        assertThrows(IllegalStateException.class, () -> accepted.reject(DECIDED_ON));
        assertThrows(IllegalStateException.class, () -> deprecated.reject(DECIDED_ON));
    }

    /**
     * Deprecating retires a decision that was already made; it does not make one, so it leaves the
     * day it was accepted on exactly as it stands.
     */
    @Test
    void deprecateLeavesTheDecisionDateUntouched() {
        Adr accepted = adr("name", "context", "decision").accept(DECIDED_ON);

        assertEquals(DECIDED_ON, accepted.deprecate().decisionDate());
    }

    /** Same reasoning as {@code deprecate}: being replaced does not re-date the original decision. */
    @Test
    void supersededByLeavesTheDecisionDateUntouched() {
        Adr accepted = adr("name", "context", "decision").accept(DECIDED_ON);

        assertEquals(DECIDED_ON, accepted.supersededBy(OTHER).decisionDate());
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
    void unsupersedeReversesSupersededByInOneStep() {
        Adr accepted = withStatus(AdrStatus.ACCEPTED);
        Adr superseded = accepted.supersededBy(OTHER);

        Adr restored = superseded.unsupersede();

        assertEquals(AdrStatus.ACCEPTED, restored.status());
        assertNull(restored.supersededBy());
    }

    @Test
    void unsupersedeLeavesTheDecisionDateUntouched() {
        Adr accepted = adr("name", "context", "decision").accept(DECIDED_ON);
        Adr superseded = accepted.supersededBy(OTHER);

        assertEquals(DECIDED_ON, superseded.unsupersede().decisionDate());
    }

    @Test
    void unsupersedeThrowsUnlessSuperseded() {
        Adr proposed = adr("name", "context", "decision");
        Adr accepted = withStatus(AdrStatus.ACCEPTED);
        Adr rejected = withStatus(AdrStatus.REJECTED);
        Adr deprecated = withStatus(AdrStatus.DEPRECATED);

        assertThrows(IllegalStateException.class, proposed::unsupersede);
        assertThrows(IllegalStateException.class, accepted::unsupersede);
        assertThrows(IllegalStateException.class, rejected::unsupersede);
        assertThrows(IllegalStateException.class, deprecated::unsupersede);
    }

    @Test
    void reviseTextCorrectsEveryFieldWhileProposed() {
        Adr proposed = adr("name", "context", "decision");

        Adr revised = proposed.reviseText("Better name", "Sharper context", "Sharper decision", false);

        assertEquals("Better name", revised.name());
        assertEquals("Sharper context", revised.context());
        assertEquals("Sharper decision", revised.decision());
        assertEquals(AdrStatus.PROPOSED, revised.status());
    }

    /**
     * The other half of kogn-io/arknet#374: correcting a decision cannot reach its date. Before
     * #374 {@code decisionDate} travelled with the prose through this method, so an accepted
     * decision's date could be rewritten under the new-language exemption - a translation carries
     * no date, yet it lifted the gate for the whole call. Now the field is simply not addressable
     * here, and the correction leaves the stamped date exactly as the transition wrote it.
     */
    @Test
    void reviseTextCannotReachTheDecisionDate() {
        Adr accepted = adr("name", "context", "decision").accept(DECIDED_ON);

        Adr translated = accepted.reviseText("Ein Titel", "context", "decision", true);

        assertEquals(DECIDED_ON, translated.decisionDate());
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
                    () -> inForce.reviseText("Better name", "context", "decision", false));

            assertEquals(status, thrown.status());
            assertEquals(new AdrCode("ADR-1"), thrown.adrCode());
            assertTrue(thrown.getMessage().contains("adr_add"), thrown.getMessage());
        }
    }

    /**
     * The refusal has to name a path the domain would actually accept. Only an {@code ACCEPTED}
     * decision may be linked with {@code adr_supersede} - {@link Adr#supersededBy(AdrId)} refuses
     * every other status (kogn-io/arknet#357) - so pointing a rejected, deprecated or already
     * superseded record at that tool would send the caller into a second rejection.
     */
    @Test
    void reviseTextOffersSupersedeOnlyWhereSupersedeWouldBeAccepted() {
        assertTrue(assertThrows(AdrTextImmutableException.class,
                () -> withStatus(AdrStatus.ACCEPTED)
                        .reviseText("Better name", "context", "decision", false))
                .getMessage().contains("adr_supersede"));

        for (AdrStatus terminal : List.of(AdrStatus.REJECTED, AdrStatus.DEPRECATED,
                AdrStatus.SUPERSEDED)) {
            Adr record = withStatus(terminal);

            String message = assertThrows(AdrTextImmutableException.class,
                    () -> record.reviseText("Better name", "context", "decision", false))
                    .getMessage();

            assertFalse(message.contains("link it with adr_supersede"), message);
            assertTrue(message.contains("adr_add"), message);
        }
    }

    /**
     * A {@code PROPOSED} decision's text is correctable, so this exception cannot describe it -
     * constructing it anyway is a bug in the caller, not a domain outcome. Same guard as
     * {@link AdrNotDeletableException}.
     */
    @Test
    void textImmutableExceptionRefusesToDescribeAProposedDecision() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdrTextImmutableException(new AdrCode("ADR-1"), AdrStatus.PROPOSED));
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

        Adr revised = accepted.reviseText("Ein neuer Titel", "context", "decision", true);

        assertEquals("Ein neuer Titel", revised.name());
        assertEquals(AdrStatus.ACCEPTED, revised.status());
    }

    /**
     * Load-bearing, not a convenience: a correction that only touches the references travels through
     * {@code reviseText} with every text value unchanged, so a no-op must pass in any status - if it
     * threw, correcting an accepted decision's edges would be impossible.
     *
     */
    @Test
    void reviseTextIsANoOpWithIdenticalValuesEvenWhenAccepted() {
        Adr accepted = withStatus(AdrStatus.ACCEPTED);

        assertSame(accepted, accepted.reviseText("name", "context", "decision", false));
        assertSame(accepted, accepted.reviseText("name", "context", "decision", true));
    }

    @Test
    void reviseTextReportsImmutabilityRatherThanTheInvalidValueWhenAccepted() {
        Adr accepted = withStatus(AdrStatus.ACCEPTED);

        assertThrows(AdrTextImmutableException.class,
                () -> accepted.reviseText("  ", "context", "decision", false));
    }

    @Test
    void reviseReferencesReplacesInEveryStatusIncludingAccepted() {
        RequirementRef requirement = new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/fr-1"));
        BoundedContextRef boundedContext =
                new BoundedContextRef(ResourceId.of("https://w3id.org/arknet/id/bc-1"));
        TermRef term = new TermRef(ResourceId.of("https://w3id.org/arknet/id/term-1"));

        for (AdrStatus status : AdrStatus.values()) {
            Adr revised = withStatus(status)
                    .reviseReferences(List.of(requirement), List.of(boundedContext), List.of(term), List.of());

            assertEquals(List.of(requirement), revised.addressesRequirements());
            assertEquals(List.of(boundedContext), revised.affectsContexts());
            assertEquals(List.of(term), revised.usesTerms());
            assertEquals(status, revised.status());
        }
    }

    @Test
    void reviseReferencesSetsAndClearsRelatedToInEveryStatusIncludingAccepted() {
        for (AdrStatus status : AdrStatus.values()) {
            Adr linked = withStatus(status).reviseReferences(List.of(), List.of(), List.of(), List.of(OTHER));

            assertEquals(List.of(OTHER), linked.relatedTo());
            assertEquals(status, linked.status());

            Adr cleared = linked.reviseReferences(List.of(), List.of(), List.of(), List.of());

            assertEquals(List.of(), cleared.relatedTo());
            assertEquals(status, cleared.status());
        }
    }

    @Test
    void reviseReferencesRejectsRelatingADecisionToItself() {
        Adr accepted = withStatus(AdrStatus.ACCEPTED);

        assertThrows(IllegalArgumentException.class,
                () -> accepted.reviseReferences(List.of(), List.of(), List.of(), List.of(ID)));
    }

    @Test
    void reviseReferencesClearsEveryRelationWhenGivenEmptyLists() {
        RequirementRef requirement = new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/fr-1"));
        BoundedContextRef boundedContext =
                new BoundedContextRef(ResourceId.of("https://w3id.org/arknet/id/bc-1"));
        TermRef term = new TermRef(ResourceId.of("https://w3id.org/arknet/id/term-1"));
        Adr linked = withStatus(AdrStatus.ACCEPTED)
                .reviseReferences(List.of(requirement), List.of(boundedContext), List.of(term), List.of(OTHER));

        Adr cleared = linked.reviseReferences(List.of(), List.of(), List.of(), List.of());

        assertEquals(List.of(), cleared.addressesRequirements());
        assertEquals(List.of(), cleared.affectsContexts());
        assertEquals(List.of(), cleared.usesTerms());
        assertEquals(List.of(), cleared.relatedTo());
    }

    @Test
    void reviseReferencesIsANoOpWhenEveryListAlreadyMatches() {
        RequirementRef requirement = new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/fr-1"));
        Adr linked = adr("name", "context", "decision")
                .reviseReferences(List.of(requirement), List.of(), List.of(), List.of());

        assertSame(linked, linked.reviseReferences(List.of(requirement), List.of(), List.of(), List.of()));
    }

    /** {@code usesTerms}'s own no-op check, mirroring {@link #reviseReferencesIsANoOpWhenEveryListAlreadyMatches}. */
    @Test
    void reviseReferencesIsANoOpWhenUsesTermsAlreadyMatches() {
        TermRef term = new TermRef(ResourceId.of("https://w3id.org/arknet/id/term-1"));
        Adr linked = adr("name", "context", "decision")
                .reviseReferences(List.of(), List.of(), List.of(term), List.of());

        assertSame(linked, linked.reviseReferences(List.of(), List.of(), List.of(term), List.of()));
    }

    private static Adr adr(String name, String context, String decision) {
        return new Adr(ID, new AdrCode("ADR-1"), name, AdrStatus.PROPOSED, context, decision,
                null, null, null, null, null, null, null, null);
    }

    /**
     * Builds a decision in {@code status}, satisfying the bi-implication for
     * {@link AdrStatus#SUPERSEDED}: only that one status carries a non-{@code null}
     * {@code supersededBy} (set to {@link #OTHER}), every other status carries {@code null}.
     */
    private static Adr withStatus(AdrStatus status) {
        AdrId supersededBy = status == AdrStatus.SUPERSEDED ? OTHER : null;
        return new Adr(ID, new AdrCode("ADR-1"), "name", status, "context", "decision",
                null, null, null, null, null, null, supersededBy, null);
    }
}
