// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

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
 * @param acceptanceCriteria the testable "Done when ..." criteria for this requirement; maps
 *                         to {@code arkreq:acceptanceCriterion}, {@code 1..n} and required by
 *                         the requirements SHACL shape (never {@code null} or empty)
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
        List<String> acceptanceCriteria) {

    public Requirement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        usesTerms = usesTerms == null ? List.of() : List.copyOf(usesTerms);
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        if (acceptanceCriteria.isEmpty()) {
            throw new IllegalArgumentException("acceptanceCriteria must not be empty");
        }
        if (acceptanceCriteria.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("acceptanceCriteria must not contain blank entries");
        }
        if (new HashSet<>(acceptanceCriteria).size() != acceptanceCriteria.size()) {
            throw new IllegalArgumentException("acceptanceCriteria must not contain duplicate entries");
        }
        if (new HashSet<>(usesTerms).size() != usesTerms.size()) {
            throw new IllegalArgumentException("usesTerms must not contain duplicate entries");
        }
        if (qualityCategory != null && type != RequirementType.NON_FUNCTIONAL) {
            throw new IllegalArgumentException("qualityCategory is only allowed for non-functional requirements");
        }
    }

    /**
     * Advances this requirement to {@link RequirementStatus#ACCEPTED} - the only status
     * transition the requirements lifecycle permits (issue #190). Calling this on a requirement
     * that is already {@link RequirementStatus#ACCEPTED} is a no-op, returning {@code this}
     * unchanged, so a caller never has to check the current status first; any other status -
     * today only {@link RequirementStatus#PROPOSED} - transitions cleanly. This is the rule
     * itself, not a generic setter: a richer lifecycle (rejected, deprecated, ...) would extend
     * this method, not reintroduce a caller-supplied target status.
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
                motivatedBy(), qualityCategory(), usesTerms(), acceptanceCriteria());
    }
}
