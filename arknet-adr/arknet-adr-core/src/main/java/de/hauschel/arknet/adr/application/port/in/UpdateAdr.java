// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import java.time.LocalDate;
import java.util.List;

import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrConcurrentlyModifiedException;
import de.hauschel.arknet.adr.domain.AdrNotFoundException;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.adr.domain.AdrTextImmutableException;
import de.hauschel.arknet.adr.domain.ConsequenceCorrection;
import de.hauschel.arknet.adr.domain.ConsideredOptionCorrection;
import de.hauschel.arknet.adr.domain.NewConsequence;
import de.hauschel.arknet.adr.domain.NewConsideredOption;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: correct an already-recorded architecture decision - its prose, its decision date
 * and/or its three reference lists - leaving {@code status} and {@code supersedes} to their own
 * ports ({@code adr_set_status}, {@code adr_supersede}).
 *
 * <p>Backs the tool {@code adr_update}. Every field of the {@link AdrCorrection} this port takes is
 * optional: {@code null} leaves it unchanged, so a caller can fix a typo in the context without
 * restating the decision. {@code null} is never a "remove this" signal for a scalar field - it is
 * already the sentinel for "leave it", and un-setting an optional text once set would need a
 * distinct signal rather than overloading {@code null}, exactly as {@code UpdateRequirement} settled
 * it for {@code priority}. Until this port existed, correcting a decision meant recording a
 * duplicate under a fresh {@link AdrCode}, orphaning every reference pointing at the old one.</p>
 *
 * <p><strong>One correction object rather than a parameter list.</strong> The correction travels as
 * a single {@link AdrCorrection}, built through {@link AdrCorrection#builder()}, exactly as
 * {@link AddAdr} takes one {@code NewAdr} and for the same reason {@code UpdateUseCase} adopted the
 * shape: a flat list of mostly-{@code null}/empty arguments puts every caller one silent
 * transposition away from writing the wrong field.</p>
 *
 * <p><strong>{@code name}/{@code context}/{@code decision} are correctable while {@link
 * AdrStatus#PROPOSED}, or to add a language they never had (kogn-io/arknet#357).</strong> In any of
 * the other four statuses a change to an <em>existing</em> language variant of {@code name}/
 * {@code context}/{@code decision}/{@code decisionDate} is refused with
 * {@link AdrTextImmutableException}, whose message names the path that fits the status it was raised
 * in: a decision in force is a record of what was decided at the time, and correcting it runs
 * through a decision of its own rather than an edit (Nygard) - linked with {@code adr_supersede}
 * from {@link AdrStatus#ACCEPTED}, which is the only status that edge accepts, and recorded
 * standalone from {@link AdrStatus#REJECTED}/{@link AdrStatus#DEPRECATED}/
 * {@link AdrStatus#SUPERSEDED}. A call that genuinely adds a translation - a language none of the
 * three fields carries yet - is exempt from that gate; see {@code Adr#reviseText}'s javadoc for the
 * exact rule and why it is one flag for the whole call, not per field. A call that would set a field
 * to the value it already holds changes nothing and is therefore accepted in any status - it is a
 * no-op, not a text change. {@code newConsequences}/{@code newConsideredOptions} (appending) are
 * likewise unlocked in every status; {@code consequenceCorrections}/{@code consideredOptionCorrections}
 * (in-place) are locked like {@code name}/{@code context}/{@code decision} but without that
 * new-language exemption - see {@code Adr#withConsequenceCorrections}'s javadoc for why.</p>
 *
 * <p><strong>The three reference lists are the deliberate exception and stay correctable in every
 * status.</strong> Adding an {@code addressesRequirement}, {@code affectsContext} or
 * {@code relatedTo} edge later completes a reference that could not be written when the decision
 * was recorded - the requirement, bounded context or peer decision did not exist yet - rather than
 * changing what was decided. The precedent is {@code adr_supersede}, which already writes into an
 * accepted decision's {@code supersedes}. For {@code relatedTo} this is also the answer to the
 * ordering problem the relation would otherwise have: the decision recorded first cannot name the
 * one recorded later at {@code adr_add} time, and this port is where it names it afterwards -
 * which is why the relation needs no {@code adr_link_related} tool of its own.</p>
 *
 * <p><strong>Tri-state reference lists.</strong> For {@code addressesRequirementCodes},
 * {@code affectsContextCodes} and {@code relatedToCodes} alike: {@code null} leaves the existing
 * edges untouched, an empty list is the explicit, unambiguous signal to remove every edge of that
 * relation, and a non-empty list replaces the relation wholesale. This is the one place where
 * {@code null} and empty must not be conflated - the same tri-state {@code UpdateUseCase}'s
 * {@code supportingActors} carries.</p>
 */
public interface UpdateAdr {

    /**
     * Updates the decision identified by {@code code} within a project, leaving any
     * {@code null}/omitted field of {@code correction} unchanged.
     *
     * @param projectId       the project (architecture model) the decision lives in
     * @param code            the decision's business code, e.g. {@code ADR-1}
     * @param correction      the fields to correct, built via {@link AdrCorrection#builder()}; every
     *                        field it leaves unset stays as it is
     * @param defaultLanguage the target project's configured default language, canonicalized - the
     *                        fallback {@link de.hauschel.arknet.kernel.LanguageTag#resolveWriteLanguage}
     *                        uses when {@code correction.language()} is {@code null} and this call
     *                        actually touches a multilingual field; a project with neither rejects
     *                        the call only then (issue #258) - a correction touching no multilingual
     *                        field never needs one
     * @return the corrected decision, in the same {@link AdrDetail} projection every driving port of
     *         this hexagon returns
     * @throws AdrNotFoundException             if no decision with {@code code} exists in
     *                                          {@code projectId}
     * @throws AdrTextImmutableException        if the correction would change a text field of a
     *                                          decision that is no longer {@link AdrStatus#PROPOSED}
     * @throws RuntimeException                 if a reference code names a requirement or bounded
     *                                          context unknown within {@code projectId} - the same
     *                                          didactic rejection {@link AddAdr} raises, thrown
     *                                          before anything is written
     * @throws AdrNotFoundException             if a {@code relatedTo} code names no decision in
     *                                          {@code projectId} - rejected before anything is
     *                                          written, exactly like the two cross-context lists
     * @throws IllegalArgumentException         if a {@code relatedTo} code names the very decision
     *                                          being corrected - the same self-reference refusal
     *                                          {@code adr_supersede} makes
     * @throws AdrConcurrentlyModifiedException if the write keeps losing the compare-and-set race
     *                                          against a concurrent writer across every retry
     *                                          attempt
     */
    AdrDetail update(ProjectId projectId, AdrCode code, AdrCorrection correction, String defaultLanguage);

    /**
     * The fields one {@code adr_update} call corrects - every one of them optional, {@code null}
     * meaning "leave this as it is".
     *
     * <p>Built through {@link #builder()} rather than its canonical constructor: naming each field
     * at the call site is the point of this type (see the port's own "one correction object"
     * note).</p>
     *
     * <p>Unlike {@link AddAdr.NewAdr}, the compact constructor does <strong>not</strong> normalise a
     * {@code null} reference list to an empty one: here the difference between the two carries
     * meaning ({@code null} = leave the relation alone, empty = clear it), so collapsing them would
     * silently turn every partial correction into a wipe of both relations. A non-{@code null} list
     * is still copied defensively.</p>
     *
     * @param name                      the corrected title, or {@code null} to leave it unchanged
     * @param context                   the corrected forces and constraints, or {@code null} to
     *                                  leave them unchanged
     * @param decision                  the corrected decision, or {@code null} to leave it unchanged
     * @param newConsequences           consequences to append (kogn-io/arknet#357), or {@code null}/
     *                                  empty for none - never a way to remove an already-recorded
     *                                  one; always allowed, in every status (see
     *                                  {@link de.hauschel.arknet.adr.domain.Adr#withAppendedConsequences})
     * @param consequenceCorrections    text/type corrections for existing consequences, addressed by
     *                                  position, or {@code null}/empty for none; only allowed while
     *                                  {@link AdrStatus#PROPOSED} (see
     *                                  {@link de.hauschel.arknet.adr.domain.Adr#withConsequenceCorrections})
     * @param newConsideredOptions      options to append, or {@code null}/empty for none - same
     *                                  always-allowed rule as {@code newConsequences}
     * @param consideredOptionCorrections corrections for existing options, addressed by position, or
     *                                  {@code null}/empty for none - same {@link AdrStatus#PROPOSED}-only
     *                                  rule as {@code consequenceCorrections}
     * @param decisionDate              the corrected decision date, or {@code null} to leave it
     *                                  unchanged
     * @param language                  the BCP-47 language tag every multilingual text this call
     *                                  touches is written under (a corrected {@code name}/
     *                                  {@code context}/{@code decision}, an appended or corrected
     *                                  consequence/option text); {@code null} resolves to the target
     *                                  project's configured default language, or is rejected if it
     *                                  has none and a multilingual field is actually touched
     * @param addressesRequirementCodes business codes of the requirements this decision should
     *                                  address going forward, e.g. {@code FR-1}, replacing the
     *                                  existing edges wholesale; an empty list clears them all,
     *                                  {@code null} leaves them unchanged
     * @param affectsContextCodes       business codes of the bounded contexts this decision should
     *                                  affect going forward, e.g. {@code BC-1}, with the same
     *                                  tri-state as {@code addressesRequirementCodes}
     * @param relatedToCodes            business codes of the peer decisions this one should
     *                                  cross-reference going forward, e.g. {@code ADR-3}, with the
     *                                  same tri-state again; only this direction is stored, and the
     *                                  decision's own code is refused
     */
    record AdrCorrection(
            String name,
            String context,
            String decision,
            List<NewConsequence> newConsequences,
            List<ConsequenceCorrection> consequenceCorrections,
            List<NewConsideredOption> newConsideredOptions,
            List<ConsideredOptionCorrection> consideredOptionCorrections,
            LocalDate decisionDate,
            String language,
            List<String> addressesRequirementCodes,
            List<String> affectsContextCodes,
            List<String> relatedToCodes) {

        public AdrCorrection {
            newConsequences = newConsequences == null ? List.of() : List.copyOf(newConsequences);
            consequenceCorrections = consequenceCorrections == null ? List.of() : List.copyOf(consequenceCorrections);
            newConsideredOptions = newConsideredOptions == null ? List.of() : List.copyOf(newConsideredOptions);
            consideredOptionCorrections =
                    consideredOptionCorrections == null ? List.of() : List.copyOf(consideredOptionCorrections);
            addressesRequirementCodes =
                    addressesRequirementCodes == null ? null : List.copyOf(addressesRequirementCodes);
            affectsContextCodes = affectsContextCodes == null ? null : List.copyOf(affectsContextCodes);
            relatedToCodes = relatedToCodes == null ? null : List.copyOf(relatedToCodes);
        }

        /** @return a builder for a correction that, until something is set on it, changes nothing */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Collects the fields of one correction by name.
         *
         * <p>Every setter is optional and a field never set stays {@code null} - "leave it as it
         * is". Not thread-safe, and meant to be built and handed over within one call.</p>
         */
        public static final class Builder {

            private String name;
            private String context;
            private String decision;
            private List<NewConsequence> newConsequences;
            private List<ConsequenceCorrection> consequenceCorrections;
            private List<NewConsideredOption> newConsideredOptions;
            private List<ConsideredOptionCorrection> consideredOptionCorrections;
            private LocalDate decisionDate;
            private String language;
            private List<String> addressesRequirementCodes;
            private List<String> affectsContextCodes;
            private List<String> relatedToCodes;

            private Builder() {
            }

            /** @param value see {@link AdrCorrection#name()} @return this builder */
            public Builder name(String value) {
                this.name = value;
                return this;
            }

            /** @param value see {@link AdrCorrection#context()} @return this builder */
            public Builder context(String value) {
                this.context = value;
                return this;
            }

            /** @param value see {@link AdrCorrection#decision()} @return this builder */
            public Builder decision(String value) {
                this.decision = value;
                return this;
            }

            /** @param value see {@link AdrCorrection#newConsequences()} @return this builder */
            public Builder newConsequences(List<NewConsequence> value) {
                this.newConsequences = value;
                return this;
            }

            /** @param value see {@link AdrCorrection#consequenceCorrections()} @return this builder */
            public Builder consequenceCorrections(List<ConsequenceCorrection> value) {
                this.consequenceCorrections = value;
                return this;
            }

            /** @param value see {@link AdrCorrection#newConsideredOptions()} @return this builder */
            public Builder newConsideredOptions(List<NewConsideredOption> value) {
                this.newConsideredOptions = value;
                return this;
            }

            /**
             * @param value see {@link AdrCorrection#consideredOptionCorrections()}
             * @return this builder
             */
            public Builder consideredOptionCorrections(List<ConsideredOptionCorrection> value) {
                this.consideredOptionCorrections = value;
                return this;
            }

            /** @param value see {@link AdrCorrection#decisionDate()} @return this builder */
            public Builder decisionDate(LocalDate value) {
                this.decisionDate = value;
                return this;
            }

            /** @param value see {@link AdrCorrection#language()} @return this builder */
            public Builder language(String value) {
                this.language = value;
                return this;
            }

            /**
             * @param value see {@link AdrCorrection#addressesRequirementCodes()}
             * @return this builder
             */
            public Builder addressesRequirementCodes(List<String> value) {
                this.addressesRequirementCodes = value;
                return this;
            }

            /** @param value see {@link AdrCorrection#affectsContextCodes()} @return this builder */
            public Builder affectsContextCodes(List<String> value) {
                this.affectsContextCodes = value;
                return this;
            }

            /** @param value see {@link AdrCorrection#relatedToCodes()} @return this builder */
            public Builder relatedToCodes(List<String> value) {
                this.relatedToCodes = value;
                return this;
            }

            /** @return the correction collected so far */
            public AdrCorrection build() {
                return new AdrCorrection(name, context, decision, newConsequences, consequenceCorrections,
                        newConsideredOptions, consideredOptionCorrections, decisionDate, language,
                        addressesRequirementCodes, affectsContextCodes, relatedToCodes);
            }
        }
    }
}
