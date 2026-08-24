// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * A single architecture decision under management: a documented decision with its context, the
 * decision itself, its consequences and the options that were considered
 * ({@code arkarch:ArchitectureDecisionRecord}, Nygard/MADR style).
 *
 * <p>Value object of the ADR component. All invariants are enforced in the compact constructor;
 * instances are immutable and their collections are defensively copied.</p>
 *
 * <p><strong>Every edge lives inside the record.</strong> All four relations
 * ({@code addressesRequirement}, {@code affectsContext}, {@code supersededBy}, {@code relatedTo}) are
 * part of the decision's own state rather than side edges: the out-adapter persists a decision by
 * replacing its triples wholesale, so an edge kept outside this record would be silently dropped by
 * the next write - the lesson the requirements and bounded-context contexts already paid for.</p>
 *
 * <p><strong>Consequences and considered options are structured resources (kogn-io/arknet#357),
 * not flat strings.</strong> {@link #consequences()}/{@link #consideredOptions()} replace the
 * pre-#357 free-text {@code arkarch:adrConsequences}/{@code arkarch:adrAlternatives} literals with
 * lists of own, positioned {@link Consequence}/{@link ConsideredOption} resources - mirroring
 * {@code de.hauschel.arknet.req.domain.Requirement#acceptanceCriteria()} (issue #266), the precedent
 * for this exact shape. Both lists may legally be empty (unlike {@code acceptanceCriteria}, which
 * {@code Requirement} requires at least one of): the pre-#357 fields were {@code sh:Warning}-only
 * best practice, never mandatory, and this change does not raise that bar.</p>
 */
public record Adr(
        AdrId id,
        AdrCode code,
        String name,
        AdrStatus status,
        String context,
        String decision,
        List<Consequence> consequences,
        List<ConsideredOption> consideredOptions,
        LocalDate decisionDate,
        List<RequirementRef> addressesRequirements,
        List<BoundedContextRef> affectsContexts,
        AdrId supersededBy,
        List<AdrId> relatedTo) {

    public Adr {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(decision, "decision");
        consequences = consequences == null ? List.of() : List.copyOf(consequences);
        consideredOptions = consideredOptions == null ? List.of() : List.copyOf(consideredOptions);
        addressesRequirements = addressesRequirements == null ? List.of() : List.copyOf(addressesRequirements);
        affectsContexts = affectsContexts == null ? List.of() : List.copyOf(affectsContexts);
        relatedTo = relatedTo == null ? List.of() : List.copyOf(relatedTo);
        requireNotBlank(name, "name");
        requireNotBlank(context, "context");
        requireNotBlank(decision, "decision");
        requireConsecutivePositions(consequences.stream().map(Consequence::position).toList(), "consequences");
        requireConsecutivePositions(
                consideredOptions.stream().map(ConsideredOption::position).toList(), "consideredOptions");
        long chosenCount = consideredOptions.stream().filter(option -> option.outcome() == OptionOutcome.CHOSEN)
                .count();
        if (chosenCount > 1) {
            throw new IllegalArgumentException(
                    "at most one considered option may be CHOSEN, was " + chosenCount + ": " + code.value());
        }
        requireNoDuplicates(addressesRequirements, "addressesRequirements");
        requireNoDuplicates(affectsContexts, "affectsContexts");
        requireNoDuplicates(relatedTo, "relatedTo");
        if (relatedTo.contains(id)) {
            throw new IllegalArgumentException("an ADR must not be related to itself");
        }
        if (supersededBy != null && supersededBy.equals(id)) {
            throw new IllegalArgumentException("an ADR must not supersede itself");
        }
        // The bi-implication kogn-io/arknet#357 introduced: SUPERSEDED and supersededBy are set
        // only ever together, never one without the other. architecture-shapes.ttl's
        // ashapes:ADR-supersededByRequiresSupersededStatus enforces only one direction of it a
        // second time at the write gate (supersededBy set implies status SUPERSEDED) - this compact
        // constructor is the only place the converse (status SUPERSEDED implies supersededBy set)
        // is enforced at write time at all, since a node shape targeting every ADR would also fire
        // on the validation-only peer copies the gate never should (kogn-io/arknet#359).
        if (status == AdrStatus.SUPERSEDED && supersededBy == null) {
            throw new IllegalArgumentException(
                    "status SUPERSEDED requires supersededBy to be set: " + code.value());
        }
        if (status != AdrStatus.SUPERSEDED && supersededBy != null) {
            throw new IllegalArgumentException(
                    "supersededBy may only be set when status is SUPERSEDED, was " + status
                            + ": " + code.value());
        }
    }

    /**
     * Advances this decision from {@link AdrStatus#PROPOSED} to {@link AdrStatus#ACCEPTED}. Calling
     * this on an already {@link AdrStatus#ACCEPTED} decision is a no-op, returning {@code this}
     * unchanged, so a caller never has to check the current status first. Called on a
     * {@link AdrStatus#REJECTED}, {@link AdrStatus#DEPRECATED} or {@link AdrStatus#SUPERSEDED}
     * decision it throws instead of resurrecting it: silently reviving one of those back to accepted
     * would be wrong now that all three are real terminal states, not merely unimplemented ones -
     * and for {@link AdrStatus#SUPERSEDED} it would additionally strand the {@link #supersededBy}
     * edge the compact constructor ties to that status. This is the rule itself, not a generic
     * setter: a richer lifecycle would extend this method rather than reintroduce a caller-supplied
     * target status - the same shape {@code Requirement#accept()} settled on.
     *
     * @return the accepted decision, or {@code this} if it was already accepted
     * @throws IllegalStateException if this decision is {@link AdrStatus#REJECTED},
     *                                {@link AdrStatus#DEPRECATED} or {@link AdrStatus#SUPERSEDED}
     */
    public Adr accept() {
        if (status == AdrStatus.ACCEPTED) {
            return this;
        }
        if (status != AdrStatus.PROPOSED) {
            throw new IllegalStateException("an ADR can only be accepted while PROPOSED, was " + status);
        }
        return new Adr(id, code, name, AdrStatus.ACCEPTED, context, decision, consequences, consideredOptions,
                decisionDate, addressesRequirements, affectsContexts, supersededBy, relatedTo);
    }

    /**
     * Rejects this decision, advancing it from {@link AdrStatus#PROPOSED} to
     * {@link AdrStatus#REJECTED}. Calling this on an already {@link AdrStatus#REJECTED} decision is a
     * no-op, returning {@code this} unchanged. Called on an {@link AdrStatus#ACCEPTED},
     * {@link AdrStatus#DEPRECATED} or {@link AdrStatus#SUPERSEDED} decision it throws instead: each
     * of those was in force at some point rather than merely proposed, and rejecting it
     * retroactively would misrepresent its own history.
     *
     * @return the rejected decision, or {@code this} if it was already rejected
     * @throws IllegalStateException if this decision is {@link AdrStatus#ACCEPTED},
     *                                {@link AdrStatus#DEPRECATED} or {@link AdrStatus#SUPERSEDED}
     */
    public Adr reject() {
        if (status == AdrStatus.REJECTED) {
            return this;
        }
        if (status != AdrStatus.PROPOSED) {
            throw new IllegalStateException("an ADR can only be rejected while PROPOSED, was " + status);
        }
        return new Adr(id, code, name, AdrStatus.REJECTED, context, decision, consequences, consideredOptions,
                decisionDate, addressesRequirements, affectsContexts, supersededBy, relatedTo);
    }

    /**
     * Deprecates this decision, advancing it from {@link AdrStatus#ACCEPTED} to
     * {@link AdrStatus#DEPRECATED} - an obsolete decision without a successor (as opposed to one
     * superseded by a newer decision, which {@link #supersededBy(AdrId)} records on the superseded
     * decision itself). Calling this on an already {@link AdrStatus#DEPRECATED} decision is a no-op,
     * returning {@code this} unchanged. Called on a {@link AdrStatus#PROPOSED} or
     * {@link AdrStatus#REJECTED} decision it throws instead: neither was ever in force, and only a
     * decision that was can become obsolete. Called on a {@link AdrStatus#SUPERSEDED} decision it
     * throws as well, for the opposite reason: that decision <em>was</em> in force, but it has
     * already reached a terminal state that says more than {@code DEPRECATED} does - downgrading it
     * would have to drop the {@link #supersededBy} edge the compact constructor ties to
     * {@code SUPERSEDED}, trading a named successor for a bare "obsolete".
     *
     * @return the deprecated decision, or {@code this} if it was already deprecated
     * @throws IllegalStateException if this decision is {@link AdrStatus#PROPOSED},
     *                                {@link AdrStatus#REJECTED} or {@link AdrStatus#SUPERSEDED}
     */
    public Adr deprecate() {
        if (status == AdrStatus.DEPRECATED) {
            return this;
        }
        if (status != AdrStatus.ACCEPTED) {
            throw new IllegalStateException("an ADR can only be deprecated while ACCEPTED, was " + status);
        }
        return new Adr(id, code, name, AdrStatus.DEPRECATED, context, decision, consequences, consideredOptions,
                decisionDate, addressesRequirements, affectsContexts, supersededBy, relatedTo);
    }

    /**
     * Records that this decision has been superseded by {@code supersedingId}, advancing it from
     * {@link AdrStatus#ACCEPTED} to {@link AdrStatus#SUPERSEDED} and setting {@link #supersededBy} in
     * the very same step (kogn-io/arknet#357). The two can only ever change together: the compact
     * constructor enforces the bi-implication, so there is no way to reach one without the other
     * through this method.
     *
     * @param supersedingId the identity of the decision that replaces this one
     * @return the superseded decision, or {@code this} if it was already recorded as superseded by
     *         the very same successor
     * @throws IllegalStateException    if this decision is not currently {@link AdrStatus#ACCEPTED}
     *                                  (which includes already being {@link AdrStatus#SUPERSEDED} by
     *                                  a different successor)
     * @throws IllegalArgumentException if {@code supersedingId} is this decision's own identity
     */
    public Adr supersededBy(AdrId supersedingId) {
        Objects.requireNonNull(supersedingId, "supersedingId");
        if (supersedingId.equals(supersededBy)) {
            return this;
        }
        if (status != AdrStatus.ACCEPTED) {
            throw new IllegalStateException("an ADR can only be superseded while ACCEPTED, was "
                    + status + ": " + code.value());
        }
        return new Adr(id, code, name, AdrStatus.SUPERSEDED, context, decision, consequences, consideredOptions,
                decisionDate, addressesRequirements, affectsContexts, supersedingId, relatedTo);
    }

    /**
     * Returns this decision with {@code name}/{@code context}/{@code decision}/{@code decisionDate}
     * corrected - the rule behind {@code adr_update}'s flagship text fields.
     *
     * <p><strong>The text of a decision in force is not editable - except to add a language it never
     * had.</strong> Before kogn-io/arknet#357 this was a single, per-field rule: correcting the
     * wording only stayed honest while still {@link AdrStatus#PROPOSED}; in any of the other four
     * states it is a record of what was decided at the time, and rewriting it erases the history an
     * ADR exists to keep (Nygard). Since these three fields became multilingual, the rule is finer, per
     * language variant rather than per field: recording a <em>translation</em> of an already-accepted
     * decision does not change what was decided, it makes the same decision accessible in a language
     * it was not recorded in - so {@code newLanguageVariant} exempts it from the status gate.
     * Correcting the wording <em>already</em> on record in a given language is still what it always
     * was: a decision in force is a record of what was decided at the time, and rewriting that record
     * erases the history an ADR exists to keep (Nygard). The correction is recorded as a decision of
     * its own from there - linked with {@link #supersededBy(AdrId)} where that method accepts it,
     * which is {@link AdrStatus#ACCEPTED} alone; {@link AdrTextImmutableException} names the path that
     * fits each of the four statuses rather than promising an edge the domain would refuse. The status
     * is checked here, in the domain, rather than in the SHACL write gate: a shape validates one graph
     * state, not a transition between two, so "this text must not have changed" is not expressible
     * there at all.</p>
     *
     * <p><strong>Only these three fields carry the exemption.</strong> {@link #consequences()}/
     * {@link #consideredOptions()} corrections ({@link #withConsequenceCorrections}/
     * {@link #withConsideredOptionCorrections}) do not: see those methods' javadoc for why the
     * reasoning does not transfer to them one-for-one.</p>
     *
     * <p><strong>{@code newLanguageVariant} is one flag for the whole call, not per field.</strong>
     * {@code adr_add}/{@code adr_update} take a single {@code language} argument for the entire call
     * (mirroring {@code req_add}'s - not the more granular per-field {@code req_update} - shape),
     * so the caller (the application service) computes one boolean: whether the resolved write
     * language is already used by <em>any</em> of {@code name}/{@code context}/{@code decision}. A
     * call that genuinely adds a still-unused language to all three is exempt for all three; a call
     * that reuses a language already present on even one of them is not exempt for any - the
     * simplification this trades away is a single call mixing a genuinely new language for one field
     * with a same-language edit of another, which {@code adr_update}'s one-language-per-call shape
     * makes an unusual thing to attempt in the first place.</p>
     *
     * <p><strong>Order matters: compare first, then check eligibility.</strong> A call that changes
     * nothing is a no-op returning {@code this} - in <em>any</em> status, without throwing. That is
     * load-bearing rather than a convenience: a caller correcting only the references travels the
     * very same path (see {@link #reviseReferences}), so a correction that leaves the text alone must
     * not be refused just because the decision is accepted.</p>
     *
     * @param name              the corrected title
     * @param context           the corrected forces and constraints
     * @param decision          the corrected decision
     * @param decisionDate      the corrected decision date, or {@code null} for none
     * @param newLanguageVariant whether the language this call writes under is new to all three
     *                          fields - see this method's own javadoc
     * @return the corrected decision, or {@code this} if every value already matched
     * @throws AdrTextImmutableException if a field's value differs, {@code newLanguageVariant} is
     *                                   {@code false}, and this decision is no longer
     *                                   {@link AdrStatus#PROPOSED}
     * @throws IllegalArgumentException  if a corrected value violates this record's own invariants
     */
    public Adr reviseText(String name, String context, String decision, LocalDate decisionDate,
            boolean newLanguageVariant) {
        if (Objects.equals(this.name, name)
                && Objects.equals(this.context, context)
                && Objects.equals(this.decision, decision)
                && Objects.equals(this.decisionDate, decisionDate)) {
            return this;
        }
        boolean anyFieldChanged = !Objects.equals(this.name, name)
                || !Objects.equals(this.context, context)
                || !Objects.equals(this.decision, decision);
        if (anyFieldChanged && !newLanguageVariant && status != AdrStatus.PROPOSED) {
            throw new AdrTextImmutableException(code, status);
        }
        return new Adr(id, code, name, status, context, decision, consequences, consideredOptions,
                decisionDate, addressesRequirements, affectsContexts, supersededBy, relatedTo);
    }

    /**
     * Returns this decision with {@code newConsequences} appended to {@link #consequences()}, numbered
     * continuing from the current highest position - mirrors
     * {@code Requirement#withAppendedAcceptanceCriteria} exactly.
     *
     * <p><strong>Unlike {@link #reviseText}, never gated by status.</strong> A newly discovered
     * consequence completes the record rather than rewriting it - the same licence the three
     * reference lists already have via {@link #reviseReferences} (a later-completed reference states
     * the decision more fully instead of changing what was decided), and the natural extension of it:
     * noticing a consequence after the fact is discovering information, not un-deciding anything.</p>
     *
     * @param newConsequences the consequences to append, in order; {@code null} or empty is a no-op
     *                        returning {@code this} unchanged
     * @return a new decision with the additional consequences appended
     */
    public Adr withAppendedConsequences(List<NewConsequence> newConsequences) {
        if (newConsequences == null || newConsequences.isEmpty()) {
            return this;
        }
        List<Consequence> appended = new ArrayList<>(consequences);
        int nextPosition = consequences.size() + 1;
        for (NewConsequence draft : newConsequences) {
            appended.add(new Consequence(nextPosition++, draft.statement(), draft.type()));
        }
        return new Adr(id, code, name, status, context, decision, appended, consideredOptions,
                decisionDate, addressesRequirements, affectsContexts, supersededBy, relatedTo);
    }

    /**
     * Returns this decision with {@code corrections} applied to {@link #consequences()} by position -
     * correcting only each matched consequence's {@code statement}/{@code type} and leaving every
     * unmatched consequence and every other field of this decision untouched. Mirrors
     * {@code Requirement#withAcceptanceCriteriaTextPatches} (issue #266): the safe, non-reorder
     * in-place pattern - a consequence's position is its only identity, and letting it shift would
     * misattach an already-written language variant to the wrong consequence on the next read,
     * exactly the reasoning the requirements precedent gives for its own acceptance criteria and
     * which transfers here unchanged.
     *
     * <p><strong>Deliberately narrower than {@link #reviseText}: no new-language exemption.</strong>
     * A correction here is gated by {@link AdrStatus#PROPOSED} unconditionally - {@link
     * #withAppendedConsequences} is the always-available path for stating a consequence in an
     * additional language once the decision is in force, by adding it as its own new entry rather
     * than rewriting an existing one. The reasoning behind {@link #reviseText}'s per-language
     * exemption does not transfer one-for-one: {@code adr_update} carries a single {@code language}
     * argument for the whole call, and extending the exemption down to individual positions would
     * need a per-position language-tag set the write path does not otherwise require, for a
     * comparatively rare case (translating one already-accepted consequence while also fixing another
     * one's wording in the same call). Reasoned deviation, not an oversight - see kogn-io/arknet#357's
     * PR description for the alternative considered and rejected.</p>
     *
     * @param projectId   the project the correction is issued against, for the exception message only
     * @param corrections corrections for individual existing consequences, addressed by their
     *                    {@code position}; never {@code null}
     * @return a new decision with the corrected consequences
     * @throws ConsequencePositionNotFoundException if a correction names a position no consequence in
     *                                               {@link #consequences()} carries
     * @throws AdrTextImmutableException             if a correction changes an existing consequence
     *                                               and this decision is no longer
     *                                               {@link AdrStatus#PROPOSED}
     */
    public Adr withConsequenceCorrections(ProjectId projectId, List<ConsequenceCorrection> corrections) {
        Objects.requireNonNull(corrections, "corrections");
        if (corrections.isEmpty()) {
            return this;
        }
        Map<Integer, ConsequenceCorrection> byPosition = new LinkedHashMap<>();
        for (ConsequenceCorrection correction : corrections) {
            byPosition.put(correction.position(), correction);
        }
        boolean[] changed = {false};
        List<Consequence> patched = consequences.stream()
                .map(current -> {
                    ConsequenceCorrection correction = byPosition.remove(current.position());
                    if (correction == null) {
                        return current;
                    }
                    Consequence revised = new Consequence(current.position(), correction.statement(),
                            correction.type());
                    if (!revised.equals(current)) {
                        changed[0] = true;
                    }
                    return revised;
                })
                .toList();
        if (!byPosition.isEmpty()) {
            int unmatchedPosition = byPosition.keySet().iterator().next();
            throw new ConsequencePositionNotFoundException(projectId, code, unmatchedPosition);
        }
        if (!changed[0]) {
            return this;
        }
        if (status != AdrStatus.PROPOSED) {
            throw new AdrTextImmutableException(code, status);
        }
        return new Adr(id, code, name, status, context, decision, patched, consideredOptions,
                decisionDate, addressesRequirements, affectsContexts, supersededBy, relatedTo);
    }

    /**
     * {@link #withAppendedConsequences} for {@link #consideredOptions()}. Never gated by status, for
     * the same reason.
     *
     * @param newOptions the options to append, in order; {@code null} or empty is a no-op returning
     *                   {@code this} unchanged
     * @return a new decision with the additional options appended
     * @throws IllegalArgumentException if the result would carry more than one
     *                                  {@link OptionOutcome#CHOSEN} option
     */
    public Adr withAppendedConsideredOptions(List<NewConsideredOption> newOptions) {
        if (newOptions == null || newOptions.isEmpty()) {
            return this;
        }
        List<ConsideredOption> appended = new ArrayList<>(consideredOptions);
        int nextPosition = consideredOptions.size() + 1;
        for (NewConsideredOption draft : newOptions) {
            appended.add(new ConsideredOption(nextPosition++, draft.name(), draft.rationale(), draft.outcome()));
        }
        return new Adr(id, code, name, status, context, decision, consequences, appended,
                decisionDate, addressesRequirements, affectsContexts, supersededBy, relatedTo);
    }

    /**
     * {@link #withConsequenceCorrections} for {@link #consideredOptions()} - same in-place-only
     * pattern, same {@link AdrStatus#PROPOSED}-only gate, same reasoning for why {@link #reviseText}'s
     * new-language exemption does not transfer.
     *
     * @param projectId   the project the correction is issued against, for the exception message only
     * @param corrections corrections for individual existing options, addressed by their
     *                    {@code position}; never {@code null}
     * @return a new decision with the corrected options
     * @throws ConsideredOptionPositionNotFoundException if a correction names a position no option in
     *                                                    {@link #consideredOptions()} carries
     * @throws AdrTextImmutableException                 if a correction changes an existing option
     *                                                    and this decision is no longer
     *                                                    {@link AdrStatus#PROPOSED}
     * @throws IllegalArgumentException                  if the result would carry more than one
     *                                                    {@link OptionOutcome#CHOSEN} option
     */
    public Adr withConsideredOptionCorrections(ProjectId projectId, List<ConsideredOptionCorrection> corrections) {
        Objects.requireNonNull(corrections, "corrections");
        if (corrections.isEmpty()) {
            return this;
        }
        Map<Integer, ConsideredOptionCorrection> byPosition = new LinkedHashMap<>();
        for (ConsideredOptionCorrection correction : corrections) {
            byPosition.put(correction.position(), correction);
        }
        boolean[] changed = {false};
        List<ConsideredOption> patched = consideredOptions.stream()
                .map(current -> {
                    ConsideredOptionCorrection correction = byPosition.remove(current.position());
                    if (correction == null) {
                        return current;
                    }
                    ConsideredOption revised = new ConsideredOption(current.position(), correction.name(),
                            correction.rationale(), correction.outcome());
                    if (!revised.equals(current)) {
                        changed[0] = true;
                    }
                    return revised;
                })
                .toList();
        if (!byPosition.isEmpty()) {
            int unmatchedPosition = byPosition.keySet().iterator().next();
            throw new ConsideredOptionPositionNotFoundException(projectId, code, unmatchedPosition);
        }
        if (!changed[0]) {
            return this;
        }
        if (status != AdrStatus.PROPOSED) {
            throw new AdrTextImmutableException(code, status);
        }
        return new Adr(id, code, name, status, context, decision, consequences, patched,
                decisionDate, addressesRequirements, affectsContexts, supersededBy, relatedTo);
    }

    /**
     * Returns this decision with all three of its reference lists replaced wholesale, in
     * <em>every</em> status - the deliberate exception to {@link #reviseText}'s immutability rule.
     *
     * <p><strong>Why no status check here.</strong> Adding {@code addressesRequirement},
     * {@code affectsContext} or {@code relatedTo} later does not change what was decided; it
     * completes a reference that could not be written when the decision was recorded, because the
     * requirement, bounded context or peer decision it points at did not exist yet. Freezing these
     * along with the prose would leave a decision in force permanently unable to state what it
     * applies to. The precedent is already in this record: {@link #supersededBy(AdrId)} writes into
     * an accepted decision's {@code supersededBy} for exactly the same reason.</p>
     *
     * <p>Every argument is a replacement, not an addition: an empty (or {@code null}) list clears
     * that relation, mirroring the compact constructor's own {@code null}-to-empty normalisation.
     * Deciding whether an omitted list means "clear" or "leave as it is" belongs to the application
     * service, which passes the current value through when the caller named none.</p>
     *
     * @param addressesRequirements the requirements this decision should address going forward
     * @param affectsContexts       the bounded contexts it should affect going forward
     * @param relatedTo             the peer decisions it should cross-reference going forward
     * @return the corrected decision, or {@code this} if all three lists already matched
     * @throws IllegalArgumentException if a list contains duplicates, or if {@code relatedTo}
     *                                  contains this decision's own identity
     */
    public Adr reviseReferences(List<RequirementRef> addressesRequirements,
            List<BoundedContextRef> affectsContexts, List<AdrId> relatedTo) {
        List<RequirementRef> requirements =
                addressesRequirements == null ? List.of() : List.copyOf(addressesRequirements);
        List<BoundedContextRef> contexts =
                affectsContexts == null ? List.of() : List.copyOf(affectsContexts);
        List<AdrId> peers = relatedTo == null ? List.of() : List.copyOf(relatedTo);
        if (requirements.equals(this.addressesRequirements) && contexts.equals(this.affectsContexts)
                && peers.equals(this.relatedTo)) {
            return this;
        }
        return new Adr(id, code, name, status, context, decision, consequences, consideredOptions,
                decisionDate, requirements, contexts, supersededBy, peers);
    }

    private static void requireNotBlank(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireNoDuplicates(List<?> values, String field) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(field + " must not contain duplicate entries");
        }
    }

    /**
     * Enforces that positions are gap-free, duplicate-free and ascending: the element at index
     * {@code i} must carry position {@code i + 1}. Legal for an empty list (unlike
     * {@code Requirement#requireConsecutiveAcceptanceCriterionPositions}, which its caller never
     * invokes on one): {@link #consequences()}/{@link #consideredOptions()} are optional, best-practice
     * lists, not a mandatory one.
     */
    private static void requireConsecutivePositions(List<Integer> positions, String field) {
        for (int i = 0; i < positions.size(); i++) {
            int expected = i + 1;
            int actual = positions.get(i);
            if (actual != expected) {
                throw new IllegalArgumentException(
                        field + " positions must be gap-free, duplicate-free and ascending "
                                + "(1.." + positions.size() + "); expected position " + expected
                                + " at index " + i + " but was " + actual);
            }
        }
    }
}
