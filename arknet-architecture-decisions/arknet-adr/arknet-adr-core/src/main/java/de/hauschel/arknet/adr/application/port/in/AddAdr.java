// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import java.util.List;

import de.hauschel.arknet.adr.domain.NewConsequence;
import de.hauschel.arknet.adr.domain.NewConsideredOption;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: record a new architecture decision.
 *
 * <p>Backs the tool {@code adr_add}. Identity assignment (the opaque
 * {@link de.hauschel.arknet.adr.domain.AdrId}) and business-code assignment ({@code ADR-N}) are
 * policy of the implementing application service.</p>
 *
 * <p><strong>Coarse-grained write.</strong> A decision arrives complete, including its references -
 * the same shape {@code uc_add} uses for a use case's actors and realised requirements, and for the
 * same reason: the references are part of what makes the decision comprehensible, not a later
 * annotation. There is deliberately no separate {@code adr_link_requirement}, {@code adr_link_term}
 * or {@code adr_link_related} tool - a reference that has to be completed later (because the
 * requirement, bounded context, glossary term or peer decision it points at did not exist yet) is
 * corrected through {@link UpdateAdr}, which replaces any of the four relations wholesale in any
 * status rather than growing a link tool per relation. {@code supersedes} keeps its own
 * {@code adr_supersede} tool because it records a lifecycle act, not a reference.</p>
 */
public interface AddAdr {

    /**
     * Adds a new architecture decision, initially {@link de.hauschel.arknet.adr.domain.AdrStatus
     * #PROPOSED}.
     *
     * @param projectId       the project (architecture model) to add the decision to
     * @param command         the data describing the decision to record
     * @param defaultLanguage the target project's configured default language, canonicalized - the
     *                        fallback {@link de.hauschel.arknet.kernel.LanguageTag#resolveWriteLanguage}
     *                        uses when {@code command.language()} is {@code null}; a project with
     *                        neither rejects the call (issue #258)
     * @return the persisted decision including its assigned identity and code
     */
    AdrDetail add(ProjectId projectId, NewAdr command, String defaultLanguage);

    /**
     * Input data for {@link #add(ProjectId, NewAdr)}.
     *
     * <p>The two reference lists carry what a human types - {@code FR-1}, {@code BC-2} - never an
     * IRI. Resolving them to opaque identities, and rejecting an unknown or ambiguous code, happens
     * in the application service via dedicated driven lookup ports, not here and not in the driving
     * (MCP) adapter, which has no store access of its own.</p>
     *
     * @param name                      the decision's title
     * @param context                   why the decision was necessary - forces and constraints
     * @param decision                  what was decided
     * @param consequences              the decision's consequences, each its own positioned
     *                                  {@link NewConsequence} (kogn-io/arknet#357, replacing the
     *                                  pre-#357 flat string); optional, may be {@code null} or empty
     * @param consideredOptions         the options considered while making the decision, each its
     *                                  own positioned {@link NewConsideredOption}; optional, may be
     *                                  {@code null} or empty. At most one may carry
     *                                  {@link de.hauschel.arknet.adr.domain.OptionOutcome#CHOSEN}
     * @param language                  the BCP-47 language tag every multilingual text this call
     *                                  writes ({@code name}, {@code context}, {@code decision}, every
     *                                  consequence's statement, every option's name/rationale) is
     *                                  recorded under; {@code null} resolves to the target project's
     *                                  configured default language, or is rejected if it has none
     * @param addressesRequirementCodes business codes of the requirements this decision addresses,
     *                                  e.g. {@code FR-1}; may be {@code null} or empty
     * @param affectsContextCodes       business codes of the bounded contexts this decision affects,
     *                                  e.g. {@code BC-1}; may be {@code null} or empty
     * @param usesTermCodes             business codes of the glossary terms this decision uses,
     *                                  e.g. {@code TERM-1} (kogn-io/arknet#393); may be {@code null}
     *                                  or empty
     * @param relatedToCodes            business codes of the peer decisions this one cross-references,
     *                                  e.g. {@code ADR-3}; may be {@code null} or empty, must not
     *                                  name the decision being recorded (which has no code yet) and
     *                                  is written in this direction only, however symmetric the
     *                                  relation reads
     */
    record NewAdr(
            String name,
            String context,
            String decision,
            List<NewConsequence> consequences,
            List<NewConsideredOption> consideredOptions,
            String language,
            List<String> addressesRequirementCodes,
            List<String> affectsContextCodes,
            List<String> usesTermCodes,
            List<String> relatedToCodes) {

        public NewAdr {
            consequences = consequences == null ? List.of() : List.copyOf(consequences);
            consideredOptions = consideredOptions == null ? List.of() : List.copyOf(consideredOptions);
            addressesRequirementCodes =
                    addressesRequirementCodes == null ? List.of() : List.copyOf(addressesRequirementCodes);
            affectsContextCodes = affectsContextCodes == null ? List.of() : List.copyOf(affectsContextCodes);
            usesTermCodes = usesTermCodes == null ? List.of() : List.copyOf(usesTermCodes);
            relatedToCodes = relatedToCodes == null ? List.of() : List.copyOf(relatedToCodes);
        }
    }
}
