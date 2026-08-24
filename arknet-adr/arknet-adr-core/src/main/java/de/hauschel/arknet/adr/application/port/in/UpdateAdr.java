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
 * shape: a flat list of nine mostly-{@code null} arguments, five of them adjacent
 * {@link String}s and three of them adjacent {@code List<String>}s, puts every caller one silent
 * transposition away from writing the wrong field.</p>
 *
 * <p><strong>The text is only correctable while {@link AdrStatus#PROPOSED}.</strong> From
 * {@link AdrStatus#ACCEPTED} on (and likewise {@link AdrStatus#REJECTED}/
 * {@link AdrStatus#DEPRECATED}) a change to {@code name}/{@code context}/{@code decision}/
 * {@code consequences}/{@code alternatives}/{@code decisionDate} is refused with
 * {@link AdrTextImmutableException}, whose message points at {@code adr_supersede}: a decision in
 * force is a record of what was decided at the time, and correcting it runs through a successor
 * rather than an edit (Nygard). A call that would set a field to the value it already holds changes
 * nothing and is therefore accepted in any status - it is a no-op, not a text change.</p>
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
     * @param projectId  the project (architecture model) the decision lives in
     * @param code       the decision's business code, e.g. {@code ADR-1}
     * @param correction the fields to correct, built via {@link AdrCorrection#builder()}; every
     *                   field it leaves unset stays as it is
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
    AdrDetail update(ProjectId projectId, AdrCode code, AdrCorrection correction);

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
     * @param consequences              the corrected consequences, or {@code null} to leave them
     *                                  unchanged - never a signal to remove an already-recorded one
     * @param alternatives              the corrected considered options, or {@code null} to leave
     *                                  them unchanged - same rule as {@code consequences}
     * @param decisionDate              the corrected decision date, or {@code null} to leave it
     *                                  unchanged - same rule again
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
            String consequences,
            String alternatives,
            LocalDate decisionDate,
            List<String> addressesRequirementCodes,
            List<String> affectsContextCodes,
            List<String> relatedToCodes) {

        public AdrCorrection {
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
            private String consequences;
            private String alternatives;
            private LocalDate decisionDate;
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

            /** @param value see {@link AdrCorrection#consequences()} @return this builder */
            public Builder consequences(String value) {
                this.consequences = value;
                return this;
            }

            /** @param value see {@link AdrCorrection#alternatives()} @return this builder */
            public Builder alternatives(String value) {
                this.alternatives = value;
                return this;
            }

            /** @param value see {@link AdrCorrection#decisionDate()} @return this builder */
            public Builder decisionDate(LocalDate value) {
                this.decisionDate = value;
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
                return new AdrCorrection(name, context, decision, consequences, alternatives,
                        decisionDate, addressesRequirementCodes, affectsContextCodes, relatedToCodes);
            }
        }
    }
}
