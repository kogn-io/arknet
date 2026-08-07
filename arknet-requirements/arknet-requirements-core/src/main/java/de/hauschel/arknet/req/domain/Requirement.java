// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * A single requirement (functional or non-functional) under management.
 *
 * <p>Value object of the requirements component. All invariants are enforced
 * in the compact constructor; instances are immutable and their collections are
 * defensively copied.</p>
 *
 * @param id               opaque, unchanging identity of this requirement (never a business
 *                         label); minted once by a {@link de.hauschel.arknet.kernel.ResourceIdFactory}
 *                         and stable across relabelling
 * @param code             human-readable business label (e.g. {@code FR-1}); maps to
 *                         {@code dcterms:identifier}
 * @param title            short human-readable summary; maps to {@code dcterms:title}
 * @param description      the normative statement ("The system shall ..."); maps to
 *                         {@code dcterms:description} and is required by the requirements
 *                         SHACL shape
 * @param type             functional vs. non-functional classification
 * @param status           current lifecycle state
 * @param priority         MoSCoW priority; maps to {@code arkreq:priority}. Optional (may be
 *                         {@code null}); applies to both functional and non-functional
 *                         requirements
 * @param motivatedBy      IRI of the {@code arkreq:Goal} this requirement is motivated by;
 *                         maps to {@code arkreq:motivatedBy}. Optional (may be {@code null});
 *                         no Goal aggregate exists yet, so this is carried as a plain IRI
 *                         reference rather than resolved to a domain object
 * @param qualityCategory  free-text quality category (e.g. "performance", "security"); maps
 *                         to {@code arkreq:qualityCategory}. Optional (may be {@code null});
 *                         only meaningful for {@link RequirementType#NON_FUNCTIONAL}
 * @param usesTerms        the glossary terms of the ubiquitous language this requirement
 *                         uses; maps to {@code arkreq:usesTerm}, {@code 0..n}, held as bare
 *                         identity references (never {@code null} or containing duplicates;
 *                         a {@code null} argument is normalised to an empty list). Part of
 *                         the requirement's own state
 *                         rather than a side edge: the out-adapter persists a requirement by
 *                         replacing it wholesale, so a link kept outside this record would be
 *                         silently dropped by the next status change.
 * @param acceptanceCriteria the testable "Done when ..." criteria for this requirement, each its
 *                         own positioned {@link AcceptanceCriterion} value object (issue #266,
 *                         mirroring {@code de.hauschel.arknet.uc.domain.UseCase#steps()}); maps
 *                         to {@code arkreq:acceptanceCriterion} object-property edges to
 *                         {@code arkreq:AcceptanceCriterion} resources, {@code 1..n} and required
 *                         by the requirements SHACL shape (never {@code null} or empty). Numbered
 *                         {@code 1, 2, ..., n} with no gaps, no duplicates and in ascending order -
 *                         i.e. the criterion at list index {@code i} carries position
 *                         {@code i + 1} - and no two criteria carry the same {@code text}
 * @param constrainedBy    the {@code arkreq:Constraint}s this requirement is bound by; maps to
 *                         {@code oslc_rm:constrainedBy}, {@code 0..n}, held as bare identity
 *                         references (never {@code null} or containing duplicates; a
 *                         {@code null} argument is normalised to an empty list) - same reasoning
 *                         as {@code usesTerms}: the out-adapter persists a requirement by
 *                         replacing it wholesale, so a link kept outside this record would be
 *                         silently dropped by the next status change
 */
public record Requirement(
        RequirementId id,
        RequirementCode code,
        String title,
        String description,
        RequirementType type,
        RequirementStatus status,
        Priority priority,
        String motivatedBy,
        String qualityCategory,
        List<TermRef> usesTerms,
        List<AcceptanceCriterion> acceptanceCriteria,
        List<ConstraintRef> constrainedBy) {

    public Requirement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        usesTerms = usesTerms == null ? List.of() : List.copyOf(usesTerms);
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        constrainedBy = constrainedBy == null ? List.of() : List.copyOf(constrainedBy);
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        if (acceptanceCriteria.isEmpty()) {
            throw new IllegalArgumentException("acceptanceCriteria must not be empty");
        }
        requireConsecutiveAcceptanceCriterionPositions(acceptanceCriteria);
        if (new HashSet<>(acceptanceCriteria.stream().map(AcceptanceCriterion::text).toList()).size()
                != acceptanceCriteria.size()) {
            throw new IllegalArgumentException("acceptanceCriteria must not contain duplicate entries");
        }
        if (new HashSet<>(usesTerms).size() != usesTerms.size()) {
            throw new IllegalArgumentException("usesTerms must not contain duplicate entries");
        }
        if (new HashSet<>(constrainedBy).size() != constrainedBy.size()) {
            throw new IllegalArgumentException("constrainedBy must not contain duplicate entries");
        }
        if (qualityCategory != null && type != RequirementType.NON_FUNCTIONAL) {
            throw new IllegalArgumentException("qualityCategory is only allowed for non-functional requirements");
        }
    }

    /**
     * Advances this requirement to {@link RequirementStatus#ACCEPTED}. Calling this on a
     * requirement that is already {@link RequirementStatus#ACCEPTED} is a no-op, returning
     * {@code this} unchanged, so a caller never has to check the current status first; any other
     * status - today only {@link RequirementStatus#PROPOSED} - transitions cleanly. This is the
     * rule itself, not a generic setter: a richer lifecycle (rejected, deprecated, ...) would
     * extend this method, not reintroduce a caller-supplied target status.
     *
     * <p>Per ADR-019, the status is a non-binding maturity signal without enforcement, and is
     * settable in both directions - {@link #propose} is this method's mirror image, resetting an
     * accepted requirement back to {@link RequirementStatus#PROPOSED} (issue #291: an
     * unconditional one-way transition made setting this signal irreversible, which is exactly
     * what a non-binding signal must not be).</p>
     *
     * @return a new {@link Requirement} with status {@link RequirementStatus#ACCEPTED}, or
     *         {@code this} if already accepted
     * @throws IllegalStateException if this requirement's status is neither
     *         {@link RequirementStatus#PROPOSED} nor already {@link RequirementStatus#ACCEPTED}
     */
    public Requirement accept() {
        if (status() == RequirementStatus.ACCEPTED) {
            return this;
        }
        if (status() != RequirementStatus.PROPOSED) {
            throw new IllegalStateException(
                    "illegal status transition " + status() + " -> " + RequirementStatus.ACCEPTED);
        }
        return new Requirement(id(), code(), title(), description(), type(), RequirementStatus.ACCEPTED, priority(),
                motivatedBy(), qualityCategory(), usesTerms(), acceptanceCriteria(), constrainedBy());
    }

    /**
     * Resets this requirement to {@link RequirementStatus#PROPOSED} - the mirror image of
     * {@link #accept()}, closing the gap ADR-019 identifies as a defect (issue #291): an
     * {@link RequirementStatus#ACCEPTED} requirement could be set but never unset, which made the
     * status a one-way freeze rather than the unbinding maturity signal it is meant to be.
     * Calling this on a requirement that is already {@link RequirementStatus#PROPOSED} is a
     * no-op, returning {@code this} unchanged, exactly mirroring {@link #accept()}'s own
     * idempotency.
     *
     * @return a new {@link Requirement} with status {@link RequirementStatus#PROPOSED}, or
     *         {@code this} if already proposed
     * @throws IllegalStateException if this requirement's status is neither
     *         {@link RequirementStatus#ACCEPTED} nor already {@link RequirementStatus#PROPOSED}
     */
    public Requirement propose() {
        if (status() == RequirementStatus.PROPOSED) {
            return this;
        }
        if (status() != RequirementStatus.ACCEPTED) {
            throw new IllegalStateException(
                    "illegal status transition " + status() + " -> " + RequirementStatus.PROPOSED);
        }
        return new Requirement(id(), code(), title(), description(), type(), RequirementStatus.PROPOSED, priority(),
                motivatedBy(), qualityCategory(), usesTerms(), acceptanceCriteria(), constrainedBy());
    }

    /**
     * Returns a new requirement with {@code newCriteriaTexts} appended to {@link #acceptanceCriteria()},
     * numbered continuing from the current highest position - the only way this bounded context
     * lets a caller add criteria to an already-created requirement (mirrors
     * {@code de.hauschel.arknet.uc.domain.UseCase}'s own "no mid-list insert/reorder" stance for
     * its {@code extensions}, deliberately narrower than that type's own step machinery: see
     * {@link #withAcceptanceCriteriaTextPatches} for the complementary in-place correction).
     *
     * @param newCriteriaTexts the non-blank criterion texts to append, in order; {@code null} or
     *                         empty is a no-op returning {@code this} unchanged
     * @return a new requirement with the additional criteria appended
     */
    public Requirement withAppendedAcceptanceCriteria(List<String> newCriteriaTexts) {
        if (newCriteriaTexts == null || newCriteriaTexts.isEmpty()) {
            return this;
        }
        List<AcceptanceCriterion> appended = new ArrayList<>(acceptanceCriteria);
        int nextPosition = acceptanceCriteria.size() + 1;
        for (String text : newCriteriaTexts) {
            appended.add(new AcceptanceCriterion(nextPosition++, text));
        }
        return new Requirement(id, code, title, description, type, status, priority, motivatedBy,
                qualityCategory, usesTerms, appended, constrainedBy);
    }

    /**
     * Returns a new requirement with {@code patches} applied to {@link #acceptanceCriteria()} by
     * position - correcting only each matched criterion's {@code text} and leaving every unmatched
     * criterion and every other field of this requirement untouched. Mirrors
     * {@code de.hauschel.arknet.uc.domain.UseCase#withStepTextPatches} exactly (issue #266): the
     * safe, non-reorder in-place pattern, deliberately not the restructuring-capable
     * {@code extensions} pattern that same type also carries - a criterion's position is its only
     * identity, and letting it shift would misattach an already-written language variant to the
     * wrong criterion on the next read (the exact bug class {@code UseCase#extensions()}'s own
     * restructuring guard exists to close, avoided here by never allowing the shift in the first
     * place).
     *
     * <p>{@code projectId} is a pure pass-through for
     * {@link AcceptanceCriterionPositionNotFoundException}'s message - it is never stored on this
     * record, the same rule {@code Step}'s sibling patch method follows.</p>
     *
     * @param projectId the project the correction is issued against, for the exception message only
     * @param patches   text corrections for individual existing criteria, addressed by their
     *                  {@code position}; never {@code null}
     * @return a new requirement with the patched criteria
     * @throws AcceptanceCriterionPositionNotFoundException if a patch names a position no criterion
     *                                                       in {@link #acceptanceCriteria()} carries
     */
    public Requirement withAcceptanceCriteriaTextPatches(ProjectId projectId, List<AcceptanceCriterionTextPatch> patches) {
        Objects.requireNonNull(patches, "patches");
        Map<Integer, String> textByPosition = new LinkedHashMap<>();
        for (AcceptanceCriterionTextPatch patch : patches) {
            textByPosition.put(patch.position(), patch.text());
        }
        List<AcceptanceCriterion> patched = acceptanceCriteria.stream()
                .map(criterion -> {
                    String newText = textByPosition.remove(criterion.position());
                    return newText != null ? new AcceptanceCriterion(criterion.position(), newText) : criterion;
                })
                .toList();
        if (!textByPosition.isEmpty()) {
            int unmatchedPosition = textByPosition.keySet().iterator().next();
            throw new AcceptanceCriterionPositionNotFoundException(projectId, code, unmatchedPosition);
        }
        return new Requirement(id, code, title, description, type, status, priority, motivatedBy,
                qualityCategory, usesTerms, patched, constrainedBy);
    }

    /**
     * Enforces that acceptance-criterion positions are gap-free, duplicate-free and ascending:
     * the criterion at index {@code i} must carry position {@code i + 1}. Mirrors
     * {@code UseCase#requireConsecutiveStepPositions}.
     */
    private static void requireConsecutiveAcceptanceCriterionPositions(List<AcceptanceCriterion> criteria) {
        for (int i = 0; i < criteria.size(); i++) {
            int expected = i + 1;
            int actual = criteria.get(i).position();
            if (actual != expected) {
                throw new IllegalArgumentException(
                        "acceptance-criterion positions must be gap-free, duplicate-free and ascending "
                                + "(1.." + criteria.size() + "); expected position " + expected
                                + " at index " + i + " but was " + actual);
            }
        }
    }
}
