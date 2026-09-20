// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.persistence.RdfNode;
import de.hauschel.arknet.persistence.StoreResource;
import de.hauschel.arknet.persistence.StoreSnapshot;
import de.hauschel.arknet.persistence.Triple;
import de.hauschel.arknet.persistence.ArkreqVocabulary;

/**
 * Finds the main-flow steps of a use case that no acceptance criterion stands behind
 * (kogn-io/arknet#622).
 *
 * <p><strong>The path is two hops long and runs backwards.</strong>
 * {@code arkreq:acceptanceCriterion} hangs off the requirement, not off the step, so a step
 * reaches a criterion only through the requirement it realises:</p>
 *
 * <pre>arkreq:Step --stepRealises--&gt; arkreq:Requirement --acceptanceCriterion--&gt; arkreq:AcceptanceCriterion</pre>
 *
 * <p>The model carries that question, but until this check nothing answered it: {@code
 * orphan_check} walks the opposite direction (a requirement no use case realises), {@code
 * trace_matrix} is requirement-centric rather than step-centric, and {@code impact_analysis} needs
 * a named starting node. A reader had to pull {@code uc_list}/{@code req_list} and join them in
 * their head.</p>
 *
 * <p><strong>Two findings, never one row.</strong> A step with no {@code stepRealises} edge
 * ({@link Kind#NO_REQUIREMENT}) and a step whose requirement carries no criterion
 * ({@link Kind#REQUIREMENT_WITHOUT_CRITERION}) are not the same defect - the first is a missing
 * edge, the second an incomplete requirement - and they are reported apart.</p>
 *
 * <p><strong>Main-flow steps only.</strong> An extension step carries no {@code realises} concept
 * at tool and adapter level (kogn-io/arknet#317), although the ontology allows one, so reporting
 * every extension step as {@link Kind#NO_REQUIREMENT} would be noise about a known model gap
 * rather than a finding. Should #317 land, the scope widens by itself: nothing here lists the
 * steps, it follows {@code arkreq:mainStep}.</p>
 *
 * <p>A use case without its own business code ({@code dcterms:identifier}) is skipped rather than
 * addressed by a guessed handle - the same discipline {@link RoleTermDuplicateCheck} and
 * {@link LanguageGapCheck} follow.</p>
 */
public final class StepAcceptanceCheck {

    private static final String USE_CASE_TYPE = ArkreqVocabulary.USE_CASE_TYPE;
    private static final String MAIN_STEP = ArkreqVocabulary.MAIN_STEP;
    private static final String STEP_REALISES = ArkreqVocabulary.STEP_REALISES;
    private static final String ACCEPTANCE_CRITERION = ArkreqVocabulary.ACCEPTANCE_CRITERION;
    private static final String POSITION = ArkreqVocabulary.POSITION;

    /** Which of the two gaps a finding reports. */
    public enum Kind {

        /** The step hangs off no requirement at all - there is structurally nothing to hold a criterion. */
        NO_REQUIREMENT,

        /** The chain exists but ends before the proof: no realised requirement carries a criterion. */
        REQUIREMENT_WITHOUT_CRITERION
    }

    /**
     * One main-flow step that no acceptance criterion stands behind.
     *
     * @param kind             which of the two gaps this is
     * @param useCaseCode      the owning use case's business code ({@code UC-N})
     * @param position         the step's 1-based {@code arkreq:position}, or {@code null} when it
     *                         carries none - the step is still reported, since a missing position
     *                         does not make it covered
     * @param requirementCodes the business codes of the requirements the step realises, empty for
     *                         {@link Kind#NO_REQUIREMENT} and for a realised requirement that
     *                         carries no code of its own
     */
    public record Finding(Kind kind, String useCaseCode, Integer position, List<String> requirementCodes) {

        public Finding {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(useCaseCode, "useCaseCode");
            requirementCodes = List.copyOf(requirementCodes);
        }
    }

    private StepAcceptanceCheck() {
    }

    /**
     * Runs the check over one snapshot.
     *
     * @param snapshot the model snapshot, already free of the provenance and identity graphs
     *                 {@code StoreReader} hides
     * @return every finding, ordered by use-case code then step position so two runs over an
     *         unchanged store produce the same report
     */
    public static List<Finding> run(final StoreSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        final Map<String, StoreResource> byIri = new LinkedHashMap<>();
        for (final StoreResource resource : snapshot.resources()) {
            byIri.put(resource.iri(), resource);
        }
        final List<Finding> findings = new ArrayList<>();
        for (final StoreResource resource : snapshot.resources()) {
            if (!resource.types().contains(USE_CASE_TYPE)) {
                continue;
            }
            final Optional<String> useCaseCode = resource.identifier();
            if (useCaseCode.isEmpty()) {
                continue;
            }
            for (final String stepIri : resourceObjects(resource, MAIN_STEP)) {
                findingFor(byIri, useCaseCode.get(), stepIri).ifPresent(findings::add);
            }
        }
        return findings.stream()
                .sorted(Comparator.comparing(Finding::useCaseCode)
                        .thenComparing(Finding::position, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * @return the finding for one main-flow step, or empty when at least one requirement it
     *         realises carries an acceptance criterion. One criterion is enough: the question is
     *         whether the step has a proof behind it, and a step already proven is not a gap,
     *         however many further requirements it also realises.
     */
    private static Optional<Finding> findingFor(final Map<String, StoreResource> byIri,
            final String useCaseCode, final String stepIri) {
        // A mainStep edge pointing at a subject with no statements of its own (StoreSnapshot
        // reports it as a dangling reference) has no stepRealises edge either, so it is the
        // first case rather than a skipped step.
        final StoreResource step = byIri.get(stepIri);
        final Integer position = step == null ? null : positionOf(step);
        final List<String> requirementIris =
                step == null ? List.of() : resourceObjects(step, STEP_REALISES);
        if (requirementIris.isEmpty()) {
            return Optional.of(new Finding(Kind.NO_REQUIREMENT, useCaseCode, position, List.of()));
        }
        final List<String> requirementCodes = new ArrayList<>();
        for (final String requirementIri : requirementIris) {
            final StoreResource requirement = byIri.get(requirementIri);
            if (requirement != null && !resourceObjects(requirement, ACCEPTANCE_CRITERION).isEmpty()) {
                return Optional.empty();
            }
            if (requirement != null) {
                requirement.identifier().ifPresent(requirementCodes::add);
            }
        }
        return Optional.of(new Finding(Kind.REQUIREMENT_WITHOUT_CRITERION, useCaseCode, position,
                List.copyOf(requirementCodes)));
    }

    /** @return the IRIs {@code resource} points at under {@code predicate}, in statement order. */
    private static List<String> resourceObjects(final StoreResource resource, final String predicate) {
        return resource.outgoing().stream()
                .filter(triple -> predicate.equals(triple.predicate()))
                .map(Triple::object)
                .filter(RdfNode.Resource.class::isInstance)
                .map(object -> ((RdfNode.Resource) object).iri())
                .distinct()
                .toList();
    }

    /** @return the step's 1-based position, or {@code null} when it carries none or an unparsable one. */
    private static Integer positionOf(final StoreResource step) {
        for (final Triple triple : step.outgoing()) {
            if (POSITION.equals(triple.predicate()) && triple.object() instanceof RdfNode.Literal literal) {
                try {
                    return Integer.valueOf(literal.lexicalForm().strip());
                } catch (final NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
